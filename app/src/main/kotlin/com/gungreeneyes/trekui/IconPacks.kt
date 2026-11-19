package com.gungreeneyes.trekui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.Collator
import java.util.Calendar
import java.util.concurrent.ExecutorService

/**
 * Third-party icon pack support (LCA-T22): finds installed ADW / Nova / Go / Apex / Atom-format
 * icon packs, parses the chosen one's `appfilter.xml` with [IconPackParser], and
 * swaps each app's raw icon for the pack's drawing.
 *
 * The swap happens inside [AppRepo]'s background load ([apply]), so every interface
 * that shows [AppInfo.rawIcon] (FRAMED and NATIVE icon styles, dock, tiles, shade)
 * gets pack icons with no further changes, while the PICTOGRAM / console style keeps
 * drawing our pictograms. Apps the pack doesn't cover get the pack's backplate / mask / overlay
 * when it defines them ([compose]). Parsed packs and their [Resources] are cached for
 * the life of the process; nothing here runs on the main thread except the [async] hand-off.
 *
 * Per-app choices (CHANGE ICON in the drawer, see [IconPicker]) are stored as
 * `appIcon.<AppInfo.key>` preferences: `glyph:<name>`, `pack:<pkg>/<drawable>`
 * or `app` (always the app's own icon). Format helpers are pure and unit-tested in
 * `IconPacksTest`.
 */
internal object IconPacks {
    /** Intent actions icon packs register (each launcher family's own); queried in the manifest's `<queries>`. */
    @JvmField
    val THEME_ACTIONS: Array<String> = arrayOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.dlto.atom.launcher.THEME",
    )

    /** Categories some packs use on a MAIN activity instead of an action. */
    @JvmField
    val THEME_CATEGORIES: Array<String> = arrayOf(
        "com.fede.launcher.THEME_ICONPACK",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
    )

    /** Selected pack's package, or "" for none. */
    const val PREF_PACK = "iconPack"

    /** Per-app icon choice key prefix (followed by [AppInfo.key]). */
    const val PREF_APP_PREFIX = "appIcon."

    /** Give apps without a core pictogram a keyword-matched one ([Pictograms.defaultGlyph]). */
    const val PREF_AUTO_GLYPHS = "autoGlyphs"

    /** Per-app choice values. */
    const val CHOICE_GLYPH = "glyph:"
    const val CHOICE_PACK = "pack:"
    const val CHOICE_APP = "app"

    /** Side of composed (backplate/mask) icons in px. */
    const val COMPOSED_PX = 192

    /** An installed icon pack. */
    class Pack(@JvmField val pkg: String, @JvmField val label: String)

    /** Result callback on the main thread; `value` is null when the work failed. */
    fun interface Callback<T> {
        fun done(value: T?)
    }

    /** Background work for [async] (no checked exceptions). */
    fun interface Callable<T> {
        fun call(): T
    }

    private val parsed = HashMap<String, IconPackParser.Result>()
    private val resources = HashMap<String, Resources?>()
    private val MAIN = Handler(Looper.getMainLooper())
    private var worker: ExecutorService? = null

    /** Last [installed] result, or null before the first scan (main thread reads it for CONFIG). */
    @Volatile
    private var lastInstalled: List<Pack>? = null

    // ---- pure helpers ---------------------------------------------------------------------------

    /** Preference key of an app's icon choice. */
    @JvmStatic
    fun prefKey(appKey: String): String = PREF_APP_PREFIX + appKey

    /** The pictogram name in a choice, or null when it isn't a pictogram choice. */
    @JvmStatic
    fun choiceGlyph(choice: String?): String? =
        if (choice != null && choice.startsWith(CHOICE_GLYPH) && choice.length > CHOICE_GLYPH.length) {
            choice.substring(CHOICE_GLYPH.length)
        } else {
            null
        }

    /** {pack package, drawable} of a pack choice, or null when it isn't a pack choice or is malformed. */
    @JvmStatic
    fun choicePack(choice: String?): Array<String>? {
        if (choice == null || !choice.startsWith(CHOICE_PACK)) return null
        val rest = choice.substring(CHOICE_PACK.length)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash == rest.length - 1) return null
        return arrayOf(rest.substring(0, slash), rest.substring(slash + 1))
    }

    /** A pack choice value. */
    @JvmStatic
    fun packChoice(packPkg: String, drawable: String): String = "$CHOICE_PACK$packPkg/$drawable"

    // ---- discovery --------------------------------------------------------------------------------

    /** Installed icon packs sorted by label (background thread: queries the package manager). */
    @JvmStatic
    fun installed(c: Context): List<Pack> {
        val pm = c.packageManager
        val found = LinkedHashMap<String, Pack>()
        val queries = ArrayList<Intent>()
        for (a in THEME_ACTIONS) queries.add(Intent(a))
        for (cat in THEME_CATEGORIES) queries.add(Intent(Intent.ACTION_MAIN).addCategory(cat))
        for (q in queries) {
            val list = try {
                pm.queryIntentActivities(q, PackageManager.GET_META_DATA)
            } catch (e: RuntimeException) {
                continue
            }
            for (ri in list) {
                val info = ri.activityInfo ?: continue
                val pkg = info.packageName
                if (found.containsKey(pkg) || pkg == c.packageName) continue
                val l = info.applicationInfo?.loadLabel(pm)
                found[pkg] = Pack(pkg, l?.toString() ?: pkg)
            }
        }
        val out = ArrayList(found.values)
        val col = Collator.getInstance()
        col.strength = Collator.PRIMARY
        out.sortWith { x, y -> col.compare(x.label, y.label) }
        return out
    }

    /** The packs found by the last scan, or null when none has run yet. */
    @JvmStatic
    fun cachedInstalled(): List<Pack>? = lastInstalled

    /**
     * Rescans installed packs off the main thread; `cb` gets, on the main thread,
     * true when the list differs from [cachedInstalled] (or no scan had run before).
     */
    @JvmStatic
    fun refreshInstalled(c: Context, cb: Callback<Boolean>) {
        val app = c.applicationContext
        val before = lastInstalled
        async<List<Pack>>({ installed(app) }) { found ->
            val now = found ?: emptyList()
            lastInstalled = now
            cb.done(before == null || !samePacks(before, now))
        }
    }

    /** Pure: same packages in the same order. */
    @JvmStatic
    fun samePacks(x: List<Pack>, y: List<Pack>): Boolean {
        if (x.size != y.size) return false
        for (i in x.indices) if (x[i].pkg != y[i].pkg) return false
        return true
    }

    /** Runs `work` on the icon-pack worker and hands the result to `cb` on the main thread. */
    @JvmStatic
    fun <T> async(work: Callable<T>, cb: Callback<T>) {
        val w = synchronized(this) {
            worker ?: Http.newExecutor("trekui-iconpack").also { worker = it }
        }
        w.execute {
            var v: T? = null
            try {
                v = work.call()
            } catch (ignored: RuntimeException) {
                // reported as null
            }
            val value = v
            MAIN.post { cb.done(value) }
        }
    }

    // ---- parsing ----------------------------------------------------------------------------------

    /** The pack's resources, or null when it is not installed. Cached. */
    @JvmStatic
    fun resources(c: Context, pkg: String): Resources? = synchronized(this) {
        if (resources.containsKey(pkg)) return@synchronized resources[pkg]
        var r: Resources? = null
        try {
            r = c.packageManager.getResourcesForApplication(pkg)
        } catch (ignored: PackageManager.NameNotFoundException) {
            // not installed
        } catch (ignored: RuntimeException) {
            // not installed
        }
        resources[pkg] = r
        r
    }

    /** Forgets cached packs (a pack was updated, or the choice changed). */
    @JvmStatic
    fun clearCache() {
        synchronized(this) {
            parsed.clear()
            resources.clear()
        }
    }

    /**
     * The parsed pack (background thread). Reads `appfilter.xml` from the pack's assets, or
     * `res/xml/appfilter`; then `drawable.xml` (assets or res/xml) for the full icon
     * catalogue. Returns an empty result for packs without an appfilter. Cached.
     */
    @JvmStatic
    fun load(c: Context, pkg: String): IconPackParser.Result {
        synchronized(this) { parsed[pkg]?.let { return it } }
        val p = IconPackParser()
        val res = resources(c, pkg)
        if (res != null) {
            readXml(res, pkg, "appfilter", p, false)
            readXml(res, pkg, "drawable", p, true)
        }
        val r = p.result()
        synchronized(this) { parsed[pkg] = r }
        return r
    }

    /** Feeds one XML file (assets/{name}.xml, else res/xml/{name}) into the parser. */
    private fun readXml(res: Resources, pkg: String, name: String, p: IconPackParser, catalogue: Boolean) {
        var xp: XmlPullParser? = null
        var input: InputStream? = null
        try {
            try {
                input = res.assets.open("$name.xml")
                xp = XmlPullParserFactory.newInstance().newPullParser()
                xp.setInput(input, null)
            } catch (noAsset: Exception) {
                input = null
                val id = res.getIdentifier(name, "xml", pkg)
                if (id != 0) xp = res.getXml(id)
            }
            val parser = xp ?: return
            val attrs = HashMap<String, String?>()
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    if (catalogue) {
                        if ("item" == parser.name) p.catalogueItem(parser.getAttributeValue(null, "drawable"))
                    } else {
                        attrs.clear()
                        for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                        p.element(parser.name, attrs)
                    }
                }
                ev = parser.next()
            }
        } catch (ignored: Exception) {
            // a malformed file: keep whatever parsed before the error
        } finally {
            (xp as? XmlResourceParser)?.close()
            if (input != null) {
                try {
                    input.close()
                } catch (ignored: Exception) {
                    // nothing to do
                }
            }
        }
    }

    // ---- drawables --------------------------------------------------------------------------------

    /** A drawable from a pack by name (drawable, then mipmap), or null (background thread preferred). */
    @JvmStatic
    fun drawable(c: Context, packPkg: String?, name: String?): Drawable? {
        if (packPkg == null || name == null) return null
        val res = resources(c, packPkg) ?: return null
        return try {
            var id = res.getIdentifier(name, "drawable", packPkg)
            if (id == 0) id = res.getIdentifier(name, "mipmap", packPkg)
            if (id == 0) null else decodeCapped(c, res, id) ?: res.getDrawable(id, null)
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * A bitmap pack drawable decoded at no more than [COMPOSED_PX] a side (packs ship icons of any
     * size, and full-size decodes of a whole pack exhaust the heap); null when the resource isn't a
     * bitmap (vector, XML), which [drawable] then loads as is.
     */
    @Suppress("ReturnCount")
    private fun decodeCapped(c: Context, res: Resources, id: Int): Drawable? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        o.inScaled = false
        BitmapFactory.decodeResource(res, id, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= COMPOSED_PX && o.outHeight / (sample * 2) >= COMPOSED_PX) sample *= 2
        o.inJustDecodeBounds = false
        o.inSampleSize = sample
        var bmp = BitmapFactory.decodeResource(res, id, o) ?: return null
        val side = Math.max(bmp.width, bmp.height)
        if (side > COMPOSED_PX) {
            val w = Math.max(1, bmp.width * COMPOSED_PX / side)
            val h = Math.max(1, bmp.height * COMPOSED_PX / side)
            val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return BitmapDrawable(c.resources, bmp)
    }

    /**
     * The pack's icon for an app: its own drawing (calendar-aware), else the app's icon on the
     * pack's backplate when the pack themes unknown apps, else null (keep the app's icon).
     */
    @JvmStatic
    fun iconFor(c: Context, packPkg: String, r: IconPackParser.Result, a: AppInfo, appIcon: Drawable?): Drawable? {
        val cls = a.cn.className
        val cal = r.calendarFor(a.pkg, cls, Calendar.getInstance().get(Calendar.DAY_OF_MONTH))
        val d = (if (cal != null) drawable(c, packPkg, cal) else null)
            ?: drawable(c, packPkg, r.drawableFor(a.pkg, cls))
        if (d != null) return d
        if (appIcon != null && r.themesUnknownApps()) return compose(c, packPkg, r, a.pkg, appIcon)
        return null
    }

    /**
     * Themes an icon the pack doesn't cover, ADW style: backplate, then the app icon scaled by the
     * pack's factor with the mask's opaque pixels cut away, then the overlay.
     */
    @JvmStatic
    fun compose(c: Context, packPkg: String, r: IconPackParser.Result, appPkg: String, appIcon: Drawable): Drawable {
        val s = COMPOSED_PX
        val out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val back = drawable(c, packPkg, r.backFor(appPkg))
        if (back != null) {
            back.setBounds(0, 0, s, s)
            back.draw(cv)
        }
        val icon = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val ic = Canvas(icon)
        val inset = Math.round(s * (1f - r.scale) / 2f)
        val copy = IconFactory.copy(appIcon)
        copy.setBounds(inset, inset, s - inset, s - inset)
        copy.draw(ic)
        val mask = drawable(c, packPkg, r.mask)
        if (mask != null) {
            val mb = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val mc = Canvas(mb)
            mask.setBounds(0, 0, s, s)
            mask.draw(mc)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
            ic.drawBitmap(mb, 0f, 0f, p)
            mb.recycle()
        }
        cv.drawBitmap(icon, 0f, 0f, null)
        icon.recycle()
        val upon = drawable(c, packPkg, r.upon)
        if (upon != null) {
            upon.setBounds(0, 0, s, s)
            upon.draw(cv)
        }
        return BitmapDrawable(c.resources, out)
    }

    /**
     * Background thread (called from [AppRepo]'s load): keeps each app's system icon in
     * [AppInfo.sysIcon] and replaces [AppInfo.rawIcon] with the selected pack's icon,
     * or with the per-app pack drawable the user picked; `app` choices keep the system icon.
     */
    @JvmStatic
    @Suppress("SwallowedException") // an OOM falls back to the apps' own icons
    fun apply(c: Context, apps: List<AppInfo>) {
        val prefs = Prefs.get(c)
        val pack = prefs.s(PREF_PACK, "") ?: ""
        for (a in apps) a.sysIcon = a.rawIcon
        try {
            val r = if (pack.isEmpty()) null else load(c, pack)
            for (a in apps) {
                val choice = prefs.s(prefKey(a.key), null)
                val pick = choicePack(choice)
                var d: Drawable? = null
                if (pick != null) {
                    d = drawable(c, pick[0], pick[1])
                } else if (r != null && CHOICE_APP != choice && a.mainProfile) {
                    d = iconFor(c, pack, r, a, a.rawIcon)
                }
                if (d != null) a.rawIcon = d
            }
        } catch (oom: OutOfMemoryError) {
            // a pack too heavy for this heap: drop its icons and keep every app's own
            for (a in apps) a.rawIcon = a.sysIcon
        }
    }
}
