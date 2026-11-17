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

    /** The selected interface (Skin.COMMAND .. Skin.ERA25); out-of-range values fall back to Command. */
    public int faction() {
        SuiteTheme o = override;
        if (o != null) return clampFaction(o.faction);
        return clampFaction(i(KEY_FACTION, Skin.COMMAND));
    }

    /** Stores the app's own interface choice (ignored for display while an override is set). */
    public void setFaction(int faction) { put(KEY_FACTION, clampFaction(faction)); }

    /** Maps a stored faction index to a valid one. */
    public static int clampFaction(int f) {
        return f < 0 || f > MAX_FACTION ? Skin.COMMAND : f;
    }

    /** Pref key of the colour theme for {@code faction}: Command keeps "theme", each era its own. */
    public static String themeKeyFor(int faction) {
        return Skin.eraFaction(faction) ? "theme" + faction : "theme";
    }

    /** Clamps a stored Command theme index into {@link Palette#ALL}. */
    public static int themeIndex(int t) {
        return t < 0 || t >= Palette.ALL.length ? 0 : t;
    }

    /** Clamps a stored sub-theme index into the theme list {@code faction} offers. */
    public static int clampSubTheme(int faction, int stored) {
        return Skin.eraFaction(faction) ? Era.themeIndex(faction, stored) : themeIndex(stored);
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

    /** The active colours. Also keeps Skin.faction in step, since every screen asks for this first. */
    public Palette palette() {
        Skin.faction = faction();
        Skin.lettering = lettering();
        if (Skin.pipe()) return Skin.pipePalette(Skin.faction);
        if (Skin.imperial()) return Skin.IMPERIAL_PALETTE;
        if (Skin.collective()) return Skin.COLLECTIVE_PALETTE;
        if (Skin.era()) return Palette.themesFor(Skin.faction)[eraTheme(Skin.faction)];
        return Palette.ALL[subTheme(Skin.COMMAND)];
    }

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
