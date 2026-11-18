package com.gungreeneyes.trekui

import android.content.Context
import android.content.SharedPreferences
import com.gungreeneyes.trekui.core.SuiteTheme

/**
 * The look-and-sound settings every TrekUI app shares, stored in the `"trekui"` SharedPreferences
 * file of the app that uses it (each app has its own copy of the file in its own private storage).
 *
 * Holds the typed accessors the drawing and sound code in `:core` need: the selected interface
 * ([faction]), its sub-theme ([subTheme]), the resulting [palette], the
 * Imperial lettering, the sound set and the haptics / animation / label-case switches. The launcher's
 * `Prefs` extends this class with its home-layout settings; a companion app (Messages, Keyboard,
 * Lock Screen) uses this class directly for its own local choice.
 *
 * Following the launcher: when the launcher is installed, `SuiteClient` reads its choice through
 * the suite provider and installs it here with [setThemeOverride]. While an override is set,
 * [faction], [subTheme], [lettering] and [soundSetChoice]
 * answer from it instead of the local file, so every :core drawing helper follows the launcher without
 * the app's own (fallback) choice being overwritten. The launcher itself never sets an override.
 *
 * Java interop: the class stays `open` (the launcher's `Prefs` extends it) with a `protected`
 * constructor; [sp] is a [JvmField]; keys are `const` and the statics [JvmStatic], so Java keeps calling
 * `TrekPrefs.get(c).sp` and `TrekPrefs.themeKeyFor(f)`.
 */
open class TrekPrefs protected constructor(c: Context) {
    /** The backing preferences (the app's own `"trekui"` file). */
    @JvmField
    val sp: SharedPreferences = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun i(k: String?, d: Int): Int = sp.getInt(k, d)
    fun b(k: String?, d: Boolean): Boolean = sp.getBoolean(k, d)
    fun s(k: String?, d: String?): String? = sp.getString(k, d)
    fun l(k: String?, d: Long): Long = sp.getLong(k, d)
    fun put(k: String?, v: Int) = sp.edit().putInt(k, v).apply()
    fun put(k: String?, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    fun put(k: String?, v: String?) = sp.edit().putString(k, v).apply()
    fun put(k: String?, v: Long) = sp.edit().putLong(k, v).apply()
    fun remove(k: String?) = sp.edit().remove(k).apply()

    // ---- interface / sub-theme ----------------------------------------------------------------

    /**
     * The selected interface (one of [Skin.INTERFACES]); out-of-range values and the retired
     * ids 3-8 read as Command.
     */
    fun faction(): Int {
        overrideTheme?.let { return clampFaction(it.faction) }
        val stored = i(KEY_FACTION, Skin.COMMAND)
        if (retiredFaction(stored)) {
            migrateRetired()
            return Skin.COMMAND
        }
        return clampFaction(stored)
    }

    /**
     * A saved choice of a retired interface (ids 3-8) switches to Command at its first sub-theme: the
     * stored faction and Command's theme key are rewritten, so the change survives and the rest of
     * the app (sounds, layouts, the suite provider) sees one consistent choice.
     */
    internal fun migrateRetired() {
        sp.edit().putInt(KEY_FACTION, Skin.COMMAND).putInt(themeKeyFor(Skin.COMMAND), 0).apply()
    }

    /** Stores the app's own interface choice (ignored for display while an override is set). */
    fun setFaction(faction: Int) = put(KEY_FACTION, clampFaction(faction))

    /**
     * The sub-theme (colour theme) chosen for `faction`: an index into
     * [Palette.themesFor]. Follows the launcher's choice for its own faction while an
     * override is set.
     */
    fun subTheme(faction: Int): Int {
        val o = overrideTheme
        if (o != null && o.faction == faction) return clampSubTheme(faction, o.subTheme)
        return clampSubTheme(faction, i(themeKeyFor(faction), 0))
    }

    /** Stores the app's own sub-theme choice for `faction`. */
    fun setSubTheme(faction: Int, index: Int) = put(themeKeyFor(faction), clampSubTheme(faction, index))

    /** The stored theme index of an era faction, clamped into its theme list. */
    fun eraTheme(faction: Int): Int = subTheme(faction)

    /** Imperial lettering (Skin.BLADE_MIXED, BLADE_ALL or PLAIN). */
    fun lettering(): Int = overrideTheme?.lettering ?: i(KEY_LETTERING, Skin.BLADE_MIXED)

    /**
     * The active colours: the chosen sub-theme of the selected interface. Also keeps Skin.faction (and,
     * for Imperial / Collective, the sub-theme's chrome tones) in step, since every screen asks for this
     * first.
     */
    fun palette(): Palette {
        Skin.faction = faction()
        Skin.lettering = lettering()
        val p = Palette.of(Skin.faction, subTheme(Skin.faction))
        Skin.useSubTheme(Skin.faction, p)
        return p
    }

    // ---- suite switches (published on the suite provider) ------------------------------------

    /** True while the wake-screen companion may show (default on); published on the suite provider. */
    fun lockscreenEnabled(): Boolean = b(KEY_SUITE_LOCKSCREEN, true)

    /** Stores the LOCK SCREEN switch. */
    fun setLockscreenEnabled(on: Boolean) = put(KEY_SUITE_LOCKSCREEN, on)

    /** The current choice as a suite theme value (what the suite provider publishes). */
    fun currentTheme(): SuiteTheme {
        val f = faction()
        return SuiteTheme(f, subTheme(f), lettering(), soundSetChoice(null))
    }

    // ---- sound / feel -------------------------------------------------------------------------

    /** The stored SOUND SET id (Sfx.SET_*), or `fallback` when none is stored. */
    fun soundSetChoice(fallback: String?): String? = overrideTheme?.soundSet ?: s(KEY_SOUND_SET, fallback)

    fun upperLabels(): Boolean = b("upper", true)
    fun animations(): Boolean = b("anim", true)
    fun haptics(): Boolean = b("haptics", true)

    companion object {
        /** SharedPreferences file name (unchanged since 1.0 so upgrades keep their settings). */
        const val FILE = "trekui"
        /** Highest valid faction index (Skin.ERA25). */
        const val MAX_FACTION = Skin.ERA25

        /** Pref keys of the shared look-and-sound choice (also the suite contract's vocabulary). */
        const val KEY_FACTION = "faction"
        const val KEY_LETTERING = "imperialLettering"
        const val KEY_SOUND_SET = "sndSet"

        /** Pref key of the launcher's LOCK SCREEN switch for the wake-screen companion (LCA-T35). */
        const val KEY_SUITE_LOCKSCREEN = "suiteLockscreen"

        private var instance: TrekPrefs? = null

        /** The launcher's choice while this app follows it (see [setThemeOverride]); null otherwise. */
        @Volatile
        private var overrideTheme: SuiteTheme? = null

        /**
         * The shared instance (created on first use from the application context, so no Activity leaks).
         * Locks `TrekPrefs.class`, the monitor of the Java edition's `static synchronized`. The launcher's
         * `Prefs` has its own `Prefs.get` returning the launcher instance.
         */
        @JvmStatic
        fun get(c: Context): TrekPrefs = synchronized(TrekPrefs::class.java) {
            instance ?: TrekPrefs(c).also { instance = it }
        }

        // ---- following the launcher -----------------------------------------------------------

        /**
         * Makes every look/sound accessor answer from `theme` (the launcher's choice) instead of the
         * local file; `null` returns to the app's own choice. Process-wide, not persisted.
         */
        @JvmStatic
        fun setThemeOverride(theme: SuiteTheme?) {
            overrideTheme = theme
        }

        /** The installed override (the launcher's choice), or null while the app uses its own choice. */
        @JvmStatic
        fun themeOverride(): SuiteTheme? = overrideTheme

        /**
         * Maps a stored faction index to a valid one: an out-of-range value or a retired interface (ids
         * 3-8, removed in 44) reads as Command, whose sub-theme then comes from its own key
         * ([subTheme]), so the old choice cannot leak into it.
         */
        @JvmStatic
        fun clampFaction(f: Int): Int = if (Skin.exists(f)) f else Skin.COMMAND

        /** True for a stored faction index that no longer names an interface (the retired ids 3-8). */
        @JvmStatic
        fun retiredFaction(f: Int): Boolean = f in Skin.FIRST_RETIRED..Skin.LAST_RETIRED

        /**
         * Pref key of the colour theme (sub-theme) for `faction`: Command keeps "theme" (unchanged
         * since 1.0), every other interface its own "theme<faction>" (eras "theme9"/"theme10" as in 6.3,
         * Imperial "theme1", Collective "theme2").
         */
        @JvmStatic
        fun themeKeyFor(faction: Int): String = if (faction == Skin.COMMAND) "theme" else "theme$faction"

        /** Clamps a stored Command theme index into [Palette.ALL]. */
        @JvmStatic
        fun themeIndex(t: Int): Int = SubThemes.clamp(Skin.COMMAND, t)

        /** Clamps a stored sub-theme index into the theme list `faction` offers ([SubThemes.clamp]). */
        @JvmStatic
        fun clampSubTheme(faction: Int, stored: Int): Int = SubThemes.clamp(faction, stored)
    }
}
