package com.gungreeneyes.trekui;

import android.content.Context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * All persisted launcher settings, stored in the {@code "trekui"} SharedPreferences file.
 *
 * <p>The look-and-sound part (interface, sub-theme, palette, sound set, haptics…) lives in the shared
 * {@link TrekPrefs} base class in {@code :core}, so the drawing code and the companion apps read it the
 * same way; this subclass adds the launcher's home-layout, widget and app settings.
 *
 * <p>One process-wide instance ({@link #get}) holding only the application context. Any change that
 * affects the home layout calls {@link #bump()}; MainActivity compares {@link #version()} on resume and
 * rebuilds the home screen when it moved. The string encodings (widget order, per-faction keys) are
 * pure static functions so they can be unit-tested without a device.
 */
final class Prefs extends TrekPrefs {
    static final int BG_BLACK = 0, BG_STARFIELD = 1, BG_WALLPAPER = 2;
    static final int ICON_CONSOLE = 0, ICON_FRAMED = 1, ICON_NATIVE = 2, ICON_FACTION = 3;
    static final int CLOCK_SYSTEM = 0, CLOCK_12 = 1, CLOCK_24 = 2;
    static final int WX_NONE = 0, WX_DEVICE = 1, WX_CITY = 2;
    static final int TONE_CLASSIC = 0, TONE_VIVID = 1, TONE_SOLID = 2;

    static final String W_WEATHER = "weather", W_CALENDAR = "calendar", W_MEDIA = "media",
            W_POWER = "power", W_SYSTEMS = "systems";
    static final String[] ALL_WIDGETS = {W_WEATHER, W_CALENDAR, W_MEDIA, W_POWER, W_SYSTEMS};

    /** Default drawer column count on a phone. */
    static final int DEFAULT_DRAWER_COLS = 4;

    private static Prefs instance;

    private Prefs(Context c) {
        super(c);
    }

    /** The shared instance (created on first use from the application context, so no Activity leaks). */
    public static synchronized Prefs get(Context c) {
        if (instance == null) instance = new Prefs(c);
        return instance;
    }

    /** Layout version, bumped by every change that requires the home screen to be rebuilt. */
    int version() { return sp.getInt("ver", 0); }
    /** Marks the home layout as stale. */
    void bump() { sp.edit().putInt("ver", version() + 1).apply(); }

    // ---- interface / faction ------------------------------------------------------------------

    /** Collective home layout: false = trace column (default), true = hub and spoke. */
    boolean collectiveHub() { return i("collectiveLayout", 0) == 1; }

    /** Pref key for the current faction's home layout: 0 = grid/column, 1 = deploying layout. */
    String layoutKey() { return layoutKeyFor(faction()); }

    /** Pref key holding the home layout choice for {@code faction} (pipe interfaces each have their own). */
    static String layoutKeyFor(int faction) {
        if (Skin.pipeFaction(faction)) return "pipeLayout" + faction;
        if (faction == Skin.COLLECTIVE) return "collectiveLayout";
        if (faction == Skin.IMPERIAL) return "imperialLayout";
        return "commandLayout";
    }

    /** True when the home tiles use the press-to-deploy layout (hub, cascade or tactical deploy). Never for pipe interfaces. */
    boolean revealMode() {
        if (Skin.pipeFaction(faction())) return false;
        return i(layoutKey(), 0) == 1;
    }

    int bgMode() { return i("bg", BG_BLACK); }

    /** Header line under the title. Each faction keeps its own ("header", "header1", "header2", ...). */
    String headerKey() { return headerKeyFor(faction()); }

    /** Pref key of the header line for {@code faction}. */
    static String headerKeyFor(int faction) {
        return faction == Skin.COMMAND ? "header" : "header" + faction;
    }

    String headerDefault() { return Skin.ship("STARSHIP • REGISTRY 2401-A"); }

    /** The header line for the current faction (also syncs Skin.faction so the default is the right ship). */
    String headerText() {
        Skin.faction = faction();
        return s(headerKey(), headerDefault());
    }

    int clockMode() { return i("clock", CLOCK_SYSTEM); }
    boolean seconds() { return b("seconds", false); }
    boolean stardate() { return b("stardate", true); }

    /** Icon style; FACTION ART has no Command set, so Command and its eras fall back to console pictograms. */
    int iconStyle() {
        int st = i("icons", ICON_CONSOLE);
        return st == ICON_FACTION && Skin.commandFamily(faction()) ? ICON_CONSOLE : st;
    }

    /** Icon colouring: classic console pastels, saturated outlines, or saturated solid buttons. */
    int iconTone() { return i("iconTone", TONE_CLASSIC); }
    boolean vividTiles() { return b("vividTiles", false); }
    /** The user's preferred drawer column count on a phone (wider windows add columns, see DrawerView). */
    int drawerCols() { return i("cols", DEFAULT_DRAWER_COLS); }
    int tileCount() { return i("tiles", 6); }
    int dockCount() { return i("dock", 4); }

    /** Temperature unit: the user's choice, else Fahrenheit in the countries that use it. */
    boolean fahrenheit() {
        if (sp.contains("wxF")) return b("wxF", true);
        return defaultFahrenheit(Locale.getDefault().getCountry());
    }

    /** True for the countries that use Fahrenheit (US, Liberia, Myanmar). */
    static boolean defaultFahrenheit(String country) {
        return "US".equals(country) || "LR".equals(country) || "MM".equals(country);
    }

    // ---- home widgets -----------------------------------------------------------------------

    /** The home widget order: every known widget exactly once. */
    List<String> widgetOrder() {
        return parseWidgetOrder(s("worder", joinWidgetOrder(Arrays.asList(ALL_WIDGETS))),
                WidgetSlots.panelIds(WidgetSlots.load(this)));
    }

    void setWidgetOrder(List<String> order) {
        put("worder", joinWidgetOrder(order));
        bump();
    }

    /**
     * Decodes a comma-separated widget order: unknown ids and duplicates are dropped, then any widget
     * missing from the string is appended in its default position order. {@code null} gives the default.
     */
    static List<String> parseWidgetOrder(String raw) {
        return parseWidgetOrder(raw, new ArrayList<String>());
    }

    /**
     * Like {@link #parseWidgetOrder(String)}, but the extra panel ids in {@code extras} (hosted Android
     * widget panels, see {@link WidgetSlots}) are known too: they keep their stored position, and any
     * missing from the string are appended after the built-in panels. Ids of removed widget panels are
     * not in {@code extras}, so they drop out.
     */
    static List<String> parseWidgetOrder(String raw, List<String> extras) {
        List<String> known = new ArrayList<>(Arrays.asList(ALL_WIDGETS));
        known.addAll(extras);
        List<String> out = new ArrayList<>();
        if (raw != null) {
            for (String p : raw.split(",")) if (known.contains(p) && !out.contains(p)) out.add(p);
        }
        for (String w : known) if (!out.contains(w)) out.add(w);
        return out;
    }

    /** Encodes a widget order as a comma-separated string. */
    static String joinWidgetOrder(List<String> order) {
        StringBuilder sb = new StringBuilder();
        for (String w : order) {
            if (sb.length() > 0) sb.append(',');
            sb.append(w);
        }
        return sb.toString();
    }

    Set<String> hiddenWidgets() { return new HashSet<>(sp.getStringSet("whidden", new HashSet<String>())); }

    void setWidgetHidden(String id, boolean hidden) {
        sp.edit().putStringSet("whidden", toggled(hiddenWidgets(), id, hidden)).apply();
        bump();
    }

    // ---- hidden apps ------------------------------------------------------------------------

    /** Keys (AppInfo.key) of apps hidden from the drawer. */
    Set<String> hiddenApps() { return new HashSet<>(sp.getStringSet("hidden", new HashSet<String>())); }

    void setAppHidden(String key, boolean hidden) {
        sp.edit().putStringSet("hidden", toggled(hiddenApps(), key, hidden)).apply();
    }

    /** Adds or removes {@code id} in a copy-safe set and returns it. */
    static Set<String> toggled(Set<String> set, String id, boolean present) {
        if (present) set.add(id); else set.remove(id);
        return set;
    }
}
