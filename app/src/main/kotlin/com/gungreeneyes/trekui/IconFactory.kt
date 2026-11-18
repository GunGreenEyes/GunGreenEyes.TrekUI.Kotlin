package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/**
 * Builds launcher icons in the selected style ([Prefs.iconStyle]): console pictograms, app
 * icons inside a faction frame, the faction's own artwork badges, or the native icon.
 *
 * Pictograms ("glyphs") exist for a fixed set of core apps ([GLYPH_RULES]). Decoded
 * bitmaps are cached for the life of the process.
 */
internal object IconFactory {
    /** Core apps that get an console pictogram. First installed package in each row wins, so glyphs stay unique. */
    @JvmField
    val GLYPH_RULES: Array<Array<String>> = arrayOf(
        arrayOf("phone", "com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"),
        arrayOf("messages", "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms", "com.android.messaging"),
        arrayOf("browser", "com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.android.browser"),
        arrayOf("camera", "com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera2", "com.android.camera"),
        arrayOf("settings", "com.android.settings"),
        arrayOf("calculator", "com.sec.android.app.popupcalculator", "com.google.android.calculator", "com.android.calculator2"),
        arrayOf("calendar", "com.samsung.android.calendar", "com.google.android.calendar", "com.android.calendar"),
        arrayOf("clock", "com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock"),
        arrayOf("contacts", "com.samsung.android.app.contacts", "com.google.android.contacts", "com.android.contacts"),
        arrayOf("files", "com.sec.android.app.myfiles", "com.google.android.apps.nbu.files", "com.android.documentsui"),
        arrayOf("fitness", "com.sec.android.app.shealth", "com.google.android.apps.fitness"),
        arrayOf("mail", "com.google.android.gm", "com.samsung.android.email.provider", "com.android.email"),
        arrayOf("maps", "com.google.android.apps.maps"),
        arrayOf("music", "com.sec.android.app.music", "com.google.android.apps.youtube.music"),
        arrayOf("notes", "com.samsung.android.app.notes", "com.google.android.keep"),
        arrayOf("photos", "com.sec.android.gallery3d", "com.google.android.apps.photos", "com.android.gallery3d"),
        arrayOf("store", "com.android.vending", "com.sec.android.app.samsungapps"),
        arrayOf("video", "com.google.android.youtube", "com.samsung.android.video"),
        arrayOf("weather", "com.sec.android.daemonapp", "com.google.android.apps.weather"),
        arrayOf("radio", "com.sec.android.app.fm", "com.google.android.apps.podcasts"),
        arrayOf("search", "com.google.android.googlequicksearchbox"),
        arrayOf("system", "com.samsung.android.lool"),
        arrayOf("home", "com.samsung.android.oneconnect"),
        arrayOf("secure", "org.thoughtcrime.securesms"),
        arrayOf("officemail", "com.microsoft.office.outlook"),
        arrayOf("computer", "com.anthropic.claude"),
    )

    /** Glyph inset as a fraction of the icon's side. */
    private const val GLYPH_INSET = 0.22f

    /** App icon inset as a fraction of the icon's side. */
    private const val ICON_INSET = 0.17f

    /** Size reported to views that ask for an intrinsic size (they normally set bounds). */
    private const val INTRINSIC_PX = 192

    /** Alpha of faction artwork while cloaked. */
    private const val CLOAKED_ART_ALPHA = 150

    /** Pictogram ink on a SOLID (filled) frame. */
    private const val SOLID_MARK = 0xB3000000.toInt()

    private val glyphs = HashMap<String?, Bitmap?>()
    private val art = HashMap<String, Bitmap?>()

    /**
     * Pure rule matching: for each [GLYPH_RULES] row, the first listed package that is
     * installed gets that row's glyph name.
     *
     * @param installed installed main-profile package names
     * @return package name → glyph name
     */
    @JvmStatic
    fun matchGlyphs(installed: Collection<String>): Map<String, String> {
        val pkgs = installed as? Set<String> ?: LinkedHashSet(installed)
        val out = HashMap<String, String>()
        for (rule in GLYPH_RULES) {
            for (i in 1 until rule.size) {
                if (pkgs.contains(rule[i])) {
                    out[rule[i]] = rule[0]
                    break
                }
            }
        }
        return out
    }

    /** Assigns pictogram names to the main-profile apps that match a rule (work-profile apps keep their icons). */
    @JvmStatic
    fun assignGlyphs(apps: List<AppInfo>) {
        val byPkg = HashMap<String, AppInfo>()
        for (a in apps) {
            a.glyph = null
            a.glyphPinned = false
            if (a.mainProfile && !byPkg.containsKey(a.pkg)) byPkg[a.pkg] = a
        }
        for ((pkg, glyph) in matchGlyphs(byPkg.keys)) byPkg.getValue(pkg).glyph = glyph
    }

    /**
     * [assignGlyphs] plus the user's per-app choices and, when `auto` is on,
     * keyword-matched defaults for every other main-profile app (LCA-T22).
     *
     * @param choices AppInfo.key → stored icon choice ([IconPacks.CHOICE_GLYPH] etc.); missing = automatic
     */
    @JvmStatic
    fun assignGlyphs(apps: List<AppInfo>, choices: Map<String, String>, auto: Boolean) {
        assignGlyphs(apps)
        for (a in apps) {
            val choice = choices[a.key]
            a.glyph = resolveGlyph(a.glyph, choice, auto && a.mainProfile, a.pkg, a.label, a.category)
            a.glyphPinned = IconPacks.choiceGlyph(choice) != null && a.glyph != null
        }
    }

    /**
     * Pure: the pictogram an app ends up with. A pictogram choice wins (if the name exists); an
     * "own icon" or icon-pack choice means no pictogram; otherwise the core rule's glyph, else
     * (when `auto`) the keyword default from [Pictograms.defaultGlyph].
     *
     * @param core the glyph from [GLYPH_RULES], or null
     */
    @JvmStatic
    fun resolveGlyph(core: String?, choice: String?, auto: Boolean, pkg: String?, label: String?, category: Int): String? {
        val picked = IconPacks.choiceGlyph(choice)
        if (picked != null) return if (Pictograms.exists(picked)) picked else core
        if (IconPacks.CHOICE_APP == choice || IconPacks.choicePack(choice) != null) return null
        if (core != null) return core
        return if (auto) Pictograms.defaultGlyph(pkg, label, category) else null
    }

    /** Asset path of the pictogram `name`. */
    @JvmStatic
    fun glyphPath(name: String?): String = "glyphs/$name.png"

    /** The pictogram bitmap for a glyph name, or null. Cached. */
    @JvmStatic
    fun glyph(c: Context, name: String?): Bitmap? = synchronized(this) {
        if (glyphs.containsKey(name)) {
            glyphs[name]
        } else {
            decodeAsset(c, glyphPath(name)).also { glyphs[name] = it }
        }
    }

    /** The faction artwork badge for a pictogram name (Imperial octagon, Collective module), or null. */
    @JvmStatic
    fun factionArt(c: Context, name: String?): Bitmap? = synchronized(this) {
        if (Skin.command() || name == null) {
            null
        } else {
            val path = Skin.artFolder() + name + ".png"
            if (art.containsKey(path)) art[path] else decodeAsset(c, path).also { art[path] = it }
        }
    }

    private fun decodeAsset(c: Context, path: String): Bitmap? = try {
        c.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (ignored: Exception) {
        null
    }

    /** Palette slot 0..3 an app's frame uses, stable per package. */
    @JvmStatic
    fun accentIndex(pkg: String): Int = Math.abs(pkg.hashCode() % 4)

    /** The accent colour of an app's frame. */
    @JvmStatic
    fun accentFor(a: AppInfo, p: Palette): Int = p.c(accentIndex(a.pkg))

    /**
     * The drawer/tile icon for an app.
     *
     * @param style one of [Prefs.ICON_CONSOLE], [Prefs.ICON_FRAMED], [Prefs.ICON_NATIVE], [Prefs.ICON_FACTION]
     * @param tone  one of [Prefs.TONE_CLASSIC], [Prefs.TONE_VIVID], [Prefs.TONE_SOLID]
     */
    @JvmStatic
    fun style(c: Context, a: AppInfo, p: Palette, style: Int, tone: Int): Drawable? {
        val raw = a.rawIcon ?: return null
        var st = style
        // a pictogram the user picked for this app (CHANGE ICON) shows in every style but faction art
        if (a.glyphPinned && st != Prefs.ICON_FACTION) st = Prefs.ICON_CONSOLE
        if (st == Prefs.ICON_NATIVE) return copy(raw)
        var color = accentFor(a, p)
        if (tone != Prefs.TONE_CLASSIC) color = Ui.vivid(color)
        color = Skin.glow(color)
        val solid = tone == Prefs.TONE_SOLID && !Ui.cloak // cloaked icons stay outlines
        if (st == Prefs.ICON_FACTION) {
            val fa = factionArt(c, a.glyph)
            if (fa != null) {
                val bd = BitmapDrawable(c.resources, fa)
                if (Ui.cloak) bd.alpha = CLOAKED_ART_ALPHA
                return bd
            }
            st = Prefs.ICON_CONSOLE
        }
        val glyphName = a.glyph
        if (st == Prefs.ICON_CONSOLE && glyphName != null) {
            val g = glyph(c, glyphName)
            if (g != null) return Framed(null, g, color, p.panel, solid)
        }
        return Framed(copy(raw), null, color, p.panel, solid)
    }

    /** Each view needs its own Drawable instance (bounds are per-instance). */
    @JvmStatic
    fun copy(d: Drawable): Drawable {
        val cs = d.constantState
        return if (cs != null) cs.newDrawable().mutate() else d
    }

    /**
     * Tile frame around an icon or glyph, in the geometry of the faction active when it was created:
     * console rounded key with two accent bars, Imperial octagon with a diamond marker, Collective disk with an
     * inner ring and node, the 23rd-century square key with a lamp or 25th-century cut-corner glass
     * ([EraDraw.iconFrame]). Scales with its bounds.
     *
     * @param solid filled console button (colour fill, black pictogram) instead of a coloured outline
     */
    class Framed @JvmOverloads constructor(
        private val inner: Drawable?,
        private val glyph: Bitmap?,
        private val color: Int,
        fill: Int,
        solid: Boolean = false,
    ) : Drawable() {
        private val fill: Int = if (solid) color else fill
        private val mark: Int = if (solid) SOLID_MARK else color
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val gp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val r = RectF()
        private val src = Rect()
        private val dst = Rect()
        private val path = Path()

        /** Frame geometry follows the interface it was created for. */
        private val faction = Skin.faction

        init {
            val glyphInk = if (solid) 0xFF000000.toInt() else color
            gp.colorFilter = PorterDuffColorFilter(glyphInk, PorterDuff.Mode.SRC_IN)
        }

        override fun draw(cv: Canvas) {
            val b = bounds
            val s = Math.min(b.width(), b.height()).toFloat()
            val x0 = b.centerX() - s / 2f
            val y0 = b.centerY() - s / 2f
            val sw = Math.max(2f, s * 0.035f)
            r.set(x0 + sw, y0 + sw, x0 + s - sw, y0 + s - sw)
            if (Skin.eraFaction(faction)) {
                EraDraw.iconFrame(cv, r, s, sw, color, fill, mark, faction)
            } else if (faction == Skin.COMMAND) {
                paint.style = Paint.Style.FILL
                paint.color = fill
                cv.drawRoundRect(r, s * 0.22f, s * 0.22f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = sw
                paint.color = color
                cv.drawRoundRect(r, s * 0.22f, s * 0.22f, paint)
                paint.style = Paint.Style.FILL
                paint.color = mark
                val bh = s * 0.055f
                r.set(x0 + s * 0.10f, y0 + s * 0.11f, x0 + s * 0.32f, y0 + s * 0.11f + bh)
                cv.drawRoundRect(r, bh, bh, paint)
                r.set(x0 + s * 0.68f, y0 + s * 0.89f - bh, x0 + s * 0.90f, y0 + s * 0.89f)
                cv.drawRoundRect(r, bh, bh, paint)
            } else {
                path.reset()
                if (faction == Skin.IMPERIAL) {
                    val k = (r.right - r.left) * 0.29f
                    path.moveTo(r.left + k, r.top)
                    path.lineTo(r.right - k, r.top)
                    path.lineTo(r.right, r.top + k)
                    path.lineTo(r.right, r.bottom - k)
                    path.lineTo(r.right - k, r.bottom)
                    path.lineTo(r.left + k, r.bottom)
                    path.lineTo(r.left, r.bottom - k)
                    path.lineTo(r.left, r.top + k)
                    path.close()
                } else {
                    path.addCircle(r.centerX(), r.centerY(), (r.right - r.left) / 2f, Path.Direction.CW)
                }
                paint.style = Paint.Style.FILL
                paint.color = fill
                cv.drawPath(path, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = sw
                paint.color = color
                cv.drawPath(path, paint)
                paint.style = Paint.Style.FILL
                paint.color = mark
                if (faction == Skin.IMPERIAL) {
                    // diamond marker on the upper-left bevel
                    val cx = x0 + s * 0.2f
                    val cy = y0 + s * 0.2f
                    val d = s * 0.06f
                    path.reset()
                    path.moveTo(cx, cy - d)
                    path.lineTo(cx + d, cy)
                    path.lineTo(cx, cy + d)
                    path.lineTo(cx - d, cy)
                    path.close()
                    cv.drawPath(path, paint)
                } else {
                    // inner ring and a square node at three o'clock
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = sw * 0.6f
                    cv.drawCircle(r.centerX(), r.centerY(), (r.right - r.left) / 2f - sw * 2.2f, paint)
                    paint.style = Paint.Style.FILL
                    val q = s * 0.05f
                    cv.drawRect(r.right - q * 1.2f, r.centerY() - q, r.right + q * 0.8f, r.centerY() + q, paint)
                }
            }
            if (glyph != null) {
                val inset = Math.round(s * GLYPH_INSET)
                src.set(0, 0, glyph.width, glyph.height)
                dst.set(Math.round(x0) + inset, Math.round(y0) + inset, Math.round(x0 + s) - inset, Math.round(y0 + s) - inset)
                cv.drawBitmap(glyph, src, dst, gp)
            } else if (inner != null) {
                val inset = Math.round(s * ICON_INSET)
                inner.setBounds(Math.round(x0) + inset, Math.round(y0) + inset, Math.round(x0 + s) - inset, Math.round(y0 + s) - inset)
                inner.draw(cv)
            }
        }

        override fun getIntrinsicWidth(): Int = INTRINSIC_PX

        override fun getIntrinsicHeight(): Int = INTRINSIC_PX

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            gp.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
