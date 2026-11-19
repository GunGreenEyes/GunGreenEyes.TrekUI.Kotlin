package com.gungreeneyes.trekui

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.Locale

/**
 * All persisted launcher settings, stored in the `"trekui"` SharedPreferences file.
 *
 * The look-and-sound part (interface, sub-theme, palette, sound set, haptics…) lives in the shared
 * [TrekPrefs] base class in `:core`, so the drawing code and the companion apps read it the
 * same way; this subclass adds the launcher's home-layout, widget and app settings.
 *
 * One process-wide instance ([get]) holding only the application context. Any change that
 * affects the home layout calls [bump]; MainActivity compares [version] on resume and
 * rebuilds the home screen when it moved. The string encodings (widget order, per-faction keys) are
 * pure functions in the companion so they can be unit-tested without a device.
 *
 * Java interop: the companion's functions are [JvmStatic] and its constants `const`/[JvmField], so the
 * launcher's Java keeps calling `Prefs.get(c)`, `Prefs.layoutKeyFor(f)` and `Prefs.W_WEATHER`. The
 * inherited statics of [TrekPrefs] (`Prefs.clampFaction`, `Prefs.FILE`, …) still resolve from Java.
 */
internal class Prefs private constructor(c: Context) : TrekPrefs(c) {

    /** Layout version, bumped by every change that requires the home screen to be rebuilt. */
    fun version(): Int = sp.getInt("ver", 0)

    /** Marks the home layout as stale. */
    fun bump() = sp.edit().putInt("ver", version() + 1).apply()

    // ---- interface / faction ------------------------------------------------------------------

    /** Collective home layout: false = trace column (default), true = hub and spoke. */
    fun collectiveHub(): Boolean = i("collectiveLayout", 0) == 1

    /** Pref key for the current faction's home layout: 0 = grid/column, 1 = deploying layout. */
    fun layoutKey(): String = layoutKeyFor(faction())

    /** True when the home tiles use the press-to-deploy layout (hub, cascade or tactical deploy). */
    fun revealMode(): Boolean = i(layoutKey(), 0) == 1

    fun bgMode(): Int = i("bg", BG_BLACK)

    // ---- smart-home secrets ------------------------------------------------------------------

    /** Private file holding the Govee and Nest credentials ([SECRET_KEYS]); excluded from backup. */
    private val secrets: SharedPreferences =
        c.applicationContext.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)

    /** A Govee/Nest credential from [SECRETS_FILE] ("" when unset). */
    fun secret(k: String): String {
        migrateSecrets()
        return secrets.getString(k, "").orEmpty()
    }

    fun putSecret(k: String, v: String?) {
        migrateSecrets()
        secrets.edit().putString(k, v ?: "").apply()
    }

    /**
     * Moves any [SECRET_KEYS] still in the backed-up `"trekui"` file into [SECRETS_FILE] and deletes
     * them there. Runs once for upgrades, and again whenever an older code path wrote one to `"trekui"`
     * (the newer value wins). The copy is committed before the originals are removed.
     */
    fun migrateSecrets() {
        if (SECRET_KEYS.none { sp.contains(it) }) return
        val all = sp.all
        val se = secrets.edit()
        val e = sp.edit()
        for (k in SECRET_KEYS) {
            if (!all.containsKey(k)) continue
            se.putString(k, all[k] as? String ?: "")
            e.remove(k)
        }
        se.commit()
        e.apply()
    }

    init {
        migrateSecrets()
    }

    /**
     * Once per process: deletes what the retired interfaces stored (their records file and their pref
     * keys). A saved choice of one of them is switched to Command by [TrekPrefs.faction].
     */
    fun dropRetiredData(c: Context) {
        if (retiredDropped) return
        retiredDropped = true
        dropRetiredData(c, sp)
    }

    /** Header line under the title. Each faction keeps its own ("header", "header1", "header2", ...). */
    fun headerKey(): String = headerKeyFor(faction())

    fun headerDefault(): String? = Skin.ship("STARSHIP • REGISTRY 2401-A")

    /** The header line for the current faction (also syncs Skin.faction so the default is the right ship). */
    fun headerText(): String? {
        Skin.faction = faction()
        return s(headerKey(), headerDefault())
    }

    fun clockMode(): Int = i("clock", CLOCK_SYSTEM)
    fun seconds(): Boolean = b("seconds", false)
    fun stardate(): Boolean = b("stardate", true)

    /** Icon style; FACTION ART has no Command set, so Command and its eras fall back to console pictograms. */
    fun iconStyle(): Int {
        val st = i("icons", ICON_CONSOLE)
        return if (st == ICON_FACTION && Skin.commandFamily(faction())) ICON_CONSOLE else st
    }

    /** Icon colouring: classic console pastels, saturated outlines, or saturated solid buttons. */
    fun iconTone(): Int = i("iconTone", TONE_CLASSIC)
    fun vividTiles(): Boolean = b("vividTiles", false)

    /** The user's preferred drawer column count on a phone (wider windows add columns, see DrawerView). */
    fun drawerCols(): Int = i("cols", DEFAULT_DRAWER_COLS)
    fun tileCount(): Int = i("tiles", 6)
    fun dockCount(): Int = i("dock", 4)

    /** Temperature unit: the user's choice, else Fahrenheit in the countries that use it. */
    fun fahrenheit(): Boolean {
        if (sp.contains("wxF")) return b("wxF", true)
        return defaultFahrenheit(Locale.getDefault().country)
    }

    // ---- home widgets -----------------------------------------------------------------------

    /** The home widget order: every known widget exactly once. */
    fun widgetOrder(): MutableList<String> =
        parseWidgetOrder(s("worder", joinWidgetOrder(ALL_WIDGETS.asList())), WidgetSlots.panelIds(WidgetSlots.load(this)))

    fun setWidgetOrder(order: List<String>) {
        put("worder", joinWidgetOrder(order))
        bump()
    }

    fun hiddenWidgets(): MutableSet<String> = HashSet(sp.getStringSet("whidden", HashSet()) ?: emptySet())

    fun setWidgetHidden(id: String, hidden: Boolean) {
        sp.edit().putStringSet("whidden", toggled(hiddenWidgets(), id, hidden)).apply()
        bump()
    }

    // ---- hidden apps ------------------------------------------------------------------------

    /** Keys (AppInfo.key) of apps hidden from the drawer. */
    fun hiddenApps(): MutableSet<String> = HashSet(sp.getStringSet("hidden", HashSet()) ?: emptySet())

    fun setAppHidden(key: String, hidden: Boolean) {
        sp.edit().putStringSet("hidden", toggled(hiddenApps(), key, hidden)).apply()
    }

    companion object {
        const val BG_BLACK = 0
        const val BG_STARFIELD = 1
        const val BG_WALLPAPER = 2
        const val ICON_CONSOLE = 0
        const val ICON_FRAMED = 1
        const val ICON_NATIVE = 2
        const val ICON_FACTION = 3
        const val CLOCK_SYSTEM = 0
        const val CLOCK_12 = 1
        const val CLOCK_24 = 2
        const val WX_NONE = 0
        const val WX_DEVICE = 1
        const val WX_CITY = 2
        const val TONE_CLASSIC = 0
        const val TONE_VIVID = 1
        const val TONE_SOLID = 2

        const val W_WEATHER = "weather"
        const val W_CALENDAR = "calendar"
        const val W_MEDIA = "media"
        const val W_POWER = "power"
        const val W_SYSTEMS = "systems"

        @JvmField
        val ALL_WIDGETS: Array<String> = arrayOf(W_WEATHER, W_CALENDAR, W_MEDIA, W_POWER, W_SYSTEMS)

        /** Default drawer column count on a phone. */
        const val DEFAULT_DRAWER_COLS = 4

        /** SharedPreferences file of the Govee and Nest credentials (excluded from backup and transfer). */
        const val SECRETS_FILE = "trekui_secrets"

        /** Credential keys kept in [SECRETS_FILE] instead of the backed-up `"trekui"` file. */
        @JvmField
        val SECRET_KEYS: Set<String> = hashSetOf("goveeKey", "nestSecret", "nestRefresh", "nestAccess")

        // ---- retired interfaces (ids 3-8, removed in 44) --------------------------------------

        /** SharedPreferences file of the retired interfaces' own records (dropped on upgrade). */
        const val RETIRED_FILE = "pipe_collection_v3"

        /** Exact pref keys only the retired interfaces used (family memory and their session timer). */
        private val RETIRED_KEYS: Set<String> =
            hashSetOf("lastPipe", "lastStarship", "pipeTimerAcc", "pipeTimerRun", "pipeTimerStart")

        private var instance: Prefs? = null

        private var retiredDropped = false

        /**
         * The shared instance (created on first use from the application context, so no Activity leaks).
         * Locks `Prefs.class`, the monitor of the Java edition's `static synchronized`.
         */
        @JvmStatic
        fun get(c: Context): Prefs = synchronized(Prefs::class.java) {
            instance ?: Prefs(c).also { instance = it }
        }

        /** Pref key holding the home layout choice for `faction`. */
        @JvmStatic
        fun layoutKeyFor(faction: Int): String = when (faction) {
            Skin.COLLECTIVE -> "collectiveLayout"
            Skin.IMPERIAL -> "imperialLayout"
            else -> "commandLayout"
        }

        /**
         * True for a pref key that belonged only to the retired interfaces: the keys above and the
         * per-interface keys of ids 3-8 (layout "pipeLayout<id>", header "header<id>", colour
         * theme "theme<id>", sound picks "sndBi<id>_<event>"). They are never exported or
         * imported ([LayoutArchive.isExcluded]) and [dropRetiredData] deletes them.
         */
        @JvmStatic
        fun retiredKey(key: String?): Boolean {
            if (key == null) return false
            if (key in RETIRED_KEYS) return true
            for (prefix in arrayOf("pipeLayout", "header", "theme")) {
                if (key.length == prefix.length + 1 && key.startsWith(prefix) && retiredDigit(key[prefix.length])) return true
            }
            return key.startsWith("sndBi") && key.length > 7 && retiredDigit(key[5]) && key[6] == '_'
        }

        private fun retiredDigit(ch: Char): Boolean =
            ch >= '0' + Skin.FIRST_RETIRED && ch <= '0' + Skin.LAST_RETIRED

        /** [dropRetiredData] on the given `"trekui"` preferences, without the once-per-process guard. */
        @JvmStatic
        fun dropRetiredData(c: Context, sp: SharedPreferences) {
            val e = sp.edit()
            var any = false
            for (k in sp.all.keys) {
                if (retiredKey(k)) {
                    e.remove(k)
                    any = true
                }
            }
            if (any) e.apply()
            dropRetiredFile(c.applicationContext)
        }

        /** Deletes [RETIRED_FILE]; Android 6.0 has no deleteSharedPreferences (API 24), so it empties it instead. */
        private fun dropRetiredFile(app: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                app.deleteSharedPreferences(RETIRED_FILE)
            } else {
                val records = app.getSharedPreferences(RETIRED_FILE, Context.MODE_PRIVATE)
                if (records.all.isNotEmpty()) records.edit().clear().apply()
            }
        }

        /** Pref key of the header line for `faction`. */
        @JvmStatic
        fun headerKeyFor(faction: Int): String = if (faction == Skin.COMMAND) "header" else "header$faction"

        /** True for the countries that use Fahrenheit (US, Liberia, Myanmar). */
        @JvmStatic
        fun defaultFahrenheit(country: String?): Boolean = country == "US" || country == "LR" || country == "MM"

        /**
         * Decodes a comma-separated widget order: unknown ids and duplicates are dropped, then any widget
         * missing from the string is appended in its default position order. `null` gives the default.
         */
        @JvmStatic
        fun parseWidgetOrder(raw: String?): MutableList<String> = parseWidgetOrder(raw, ArrayList())

        /**
         * Like [parseWidgetOrder], but the extra panel ids in `extras` (hosted Android
         * widget panels, see [WidgetSlots]) are known too: they keep their stored position, and any
         * missing from the string are appended after the built-in panels. Ids of removed widget panels are
         * not in `extras`, so they drop out.
         */
        @JvmStatic
        fun parseWidgetOrder(raw: String?, extras: List<String>): MutableList<String> {
            val known = ArrayList(ALL_WIDGETS.asList())
            known.addAll(extras)
            val out = ArrayList<String>()
            if (raw != null) {
                for (p in raw.split(",")) if (p in known && p !in out) out.add(p)
            }
            for (w in known) if (w !in out) out.add(w)
            return out
        }

        /** Encodes a widget order as a comma-separated string. */
        @JvmStatic
        fun joinWidgetOrder(order: List<String>): String = order.joinToString(",")

        /** Adds or removes `id` in a copy-safe set and returns it. */
        @JvmStatic
        fun toggled(set: MutableSet<String>, id: String, present: Boolean): MutableSet<String> {
            if (present) set.add(id) else set.remove(id)
            return set
        }
    }
}
