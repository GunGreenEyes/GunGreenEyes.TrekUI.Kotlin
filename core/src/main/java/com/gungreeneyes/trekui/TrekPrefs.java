package com.gungreeneyes.trekui;

import android.content.Context;
import android.content.SharedPreferences;

import com.gungreeneyes.trekui.core.SuiteTheme;

/**
 * The look-and-sound settings every TrekUI app shares, stored in the {@code "trekui"} SharedPreferences
 * file of the app that uses it (each app has its own copy of the file in its own private storage).
 *
 * <p>Holds the typed accessors the drawing and sound code in {@code :core} need: the selected interface
 * ({@link #faction()}), its sub-theme ({@link #subTheme(int)}), the resulting {@link #palette()}, the
 * Imperial lettering, the sound set and the haptics / animation / label-case switches. The launcher's
 * {@code Prefs} extends this class with its home-layout settings; a companion app (Messages, Keyboard,
 * Lock Screen) uses this class directly for its own local choice.
 *
 * <p>Following the launcher: when the launcher is installed, {@code SuiteClient} reads its choice through
 * the suite provider and installs it here with {@link #setThemeOverride}. While an override is set,
 * {@link #faction()}, {@link #subTheme(int)}, {@link #lettering()} and {@link #soundSetChoice(String)}
 * answer from it instead of the local file, so every :core drawing helper follows the launcher without
 * the app's own (fallback) choice being overwritten. The launcher itself never sets an override.
 */
public class TrekPrefs {
    /** SharedPreferences file name (unchanged since 1.0 so upgrades keep their settings). */
    public static final String FILE = "trekui";
    /** Highest valid faction index (Skin.ERA25). */
    public static final int MAX_FACTION = Skin.ERA25;

    /** Pref keys of the shared look-and-sound choice (also the suite contract's vocabulary). */
    public static final String KEY_FACTION = "faction", KEY_LETTERING = "imperialLettering", KEY_SOUND_SET = "sndSet";

    private static TrekPrefs instance;
    /** The launcher's choice while this app follows it (see {@link #setThemeOverride}); null otherwise. */
    private static volatile SuiteTheme override;

    /** The backing preferences (the app's own {@code "trekui"} file). */
    public final SharedPreferences sp;

    protected TrekPrefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The shared instance (created on first use from the application context, so no Activity leaks). */
    public static synchronized TrekPrefs get(Context c) {
        if (instance == null) instance = new TrekPrefs(c);
        return instance;
    }

    public int i(String k, int d) { return sp.getInt(k, d); }
    public boolean b(String k, boolean d) { return sp.getBoolean(k, d); }
    public String s(String k, String d) { return sp.getString(k, d); }
    public long l(String k, long d) { return sp.getLong(k, d); }
    public void put(String k, int v) { sp.edit().putInt(k, v).apply(); }
    public void put(String k, boolean v) { sp.edit().putBoolean(k, v).apply(); }
    public void put(String k, String v) { sp.edit().putString(k, v).apply(); }
    public void put(String k, long v) { sp.edit().putLong(k, v).apply(); }
    public void remove(String k) { sp.edit().remove(k).apply(); }

    // ---- following the launcher ---------------------------------------------------------------

    /**
     * Makes every look/sound accessor answer from {@code theme} (the launcher's choice) instead of the
     * local file; {@code null} returns to the app's own choice. Process-wide, not persisted.
     */
    public static void setThemeOverride(SuiteTheme theme) { override = theme; }

    /** The installed override (the launcher's choice), or null while the app uses its own choice. */
    public static SuiteTheme themeOverride() { return override; }

    // ---- interface / sub-theme ----------------------------------------------------------------

    /**
     * The selected interface (one of {@link Skin#INTERFACES}); out-of-range values and the retired
     * ids 3-8 read as Command.
     */
    public int faction() {
        SuiteTheme o = override;
        if (o != null) return clampFaction(o.faction);
        int stored = i(KEY_FACTION, Skin.COMMAND);
        if (retiredFaction(stored)) {
            migrateRetired();
            return Skin.COMMAND;
        }
        return clampFaction(stored);
    }

    /**
     * A saved choice of a retired interface (ids 3-8) switches to Command at its first sub-theme: the
     * stored faction and Command's theme key are rewritten, so the change survives and the rest of
     * the app (sounds, layouts, the suite provider) sees one consistent choice.
     */
    void migrateRetired() {
        sp.edit().putInt(KEY_FACTION, Skin.COMMAND).putInt(themeKeyFor(Skin.COMMAND), 0).apply();
    }

    /** Stores the app's own interface choice (ignored for display while an override is set). */
    public void setFaction(int faction) { put(KEY_FACTION, clampFaction(faction)); }

    /**
     * Maps a stored faction index to a valid one: an out-of-range value or a retired interface (ids
     * 3-8, removed in 44) reads as Command, whose sub-theme then comes from its own key ({@link
     * #subTheme}), so the old choice cannot leak into it.
     */
    public static int clampFaction(int f) {
        return Skin.exists(f) ? f : Skin.COMMAND;
    }

    /** True for a stored faction index that no longer names an interface (the retired ids 3-8). */
    public static boolean retiredFaction(int f) {
        return f >= Skin.FIRST_RETIRED && f <= Skin.LAST_RETIRED;
    }

    /**
     * Pref key of the colour theme (sub-theme) for {@code faction}: Command keeps "theme" (unchanged
     * since 1.0), every other interface its own "theme&lt;faction&gt;" (eras "theme9"/"theme10" as in 6.3,
     * Imperial "theme1", Collective "theme2").
     */
    public static String themeKeyFor(int faction) {
        return faction == Skin.COMMAND ? "theme" : "theme" + faction;
    }

    /** Clamps a stored Command theme index into {@link Palette#ALL}. */
    public static int themeIndex(int t) {
        return SubThemes.clamp(Skin.COMMAND, t);
    }

    /** Clamps a stored sub-theme index into the theme list {@code faction} offers ({@link SubThemes#clamp}). */
    public static int clampSubTheme(int faction, int stored) {
        return SubThemes.clamp(faction, stored);
    }

    /**
     * The sub-theme (colour theme) chosen for {@code faction}: an index into
     * {@link Palette#themesFor(int)}. Follows the launcher's choice for its own faction while an
     * override is set.
     */
    public int subTheme(int faction) {
        SuiteTheme o = override;
        if (o != null && o.faction == faction) return clampSubTheme(faction, o.subTheme);
        return clampSubTheme(faction, i(themeKeyFor(faction), 0));
    }

    /** Stores the app's own sub-theme choice for {@code faction}. */
    public void setSubTheme(int faction, int index) { put(themeKeyFor(faction), clampSubTheme(faction, index)); }

    /** The stored theme index of an era faction, clamped into its theme list. */
    public int eraTheme(int faction) { return subTheme(faction); }

    /** Imperial lettering (Skin.BLADE_MIXED, BLADE_ALL or PLAIN). */
    public int lettering() {
        SuiteTheme o = override;
        return o != null ? o.lettering : i(KEY_LETTERING, Skin.BLADE_MIXED);
    }

    /**
     * The active colours: the chosen sub-theme of the selected interface. Also keeps Skin.faction (and,
     * for Imperial / Collective, the sub-theme's chrome tones) in step, since every screen asks for this
     * first.
     */
    public Palette palette() {
        Skin.faction = faction();
        Skin.lettering = lettering();
        Palette p = Palette.of(Skin.faction, subTheme(Skin.faction));
        Skin.useSubTheme(Skin.faction, p);
        return p;
    }

    // ---- suite switches (published on the suite provider) ------------------------------------

    /** Pref key of the launcher's LOCK SCREEN switch for the wake-screen companion (LCA-T35). */
    public static final String KEY_SUITE_LOCKSCREEN = "suiteLockscreen";

    /** True while the wake-screen companion may show (default on); published on the suite provider. */
    public boolean lockscreenEnabled() { return b(KEY_SUITE_LOCKSCREEN, true); }

    /** Stores the LOCK SCREEN switch. */
    public void setLockscreenEnabled(boolean on) { put(KEY_SUITE_LOCKSCREEN, on); }

    /** The current choice as a suite theme value (what the suite provider publishes). */
    public SuiteTheme currentTheme() {
        int f = faction();
        return new SuiteTheme(f, subTheme(f), lettering(), soundSetChoice(null));
    }

    // ---- sound / feel -------------------------------------------------------------------------

    /** The stored SOUND SET id (Sfx.SET_*), or {@code fallback} when none is stored. */
    public String soundSetChoice(String fallback) {
        SuiteTheme o = override;
        if (o != null && o.soundSet != null) return o.soundSet;
        return s(KEY_SOUND_SET, fallback);
    }

    public boolean upperLabels() { return b("upper", true); }
    public boolean animations() { return b("anim", true); }
    public boolean haptics() { return b("haptics", true); }
}
