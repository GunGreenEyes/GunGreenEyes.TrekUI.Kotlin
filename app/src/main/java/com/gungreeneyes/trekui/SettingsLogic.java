package com.gungreeneyes.trekui;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure (Android-free) rules used by the CONFIG screen ({@link SettingsActivity}): option-row layout,
 * preference ↔ choice mapping, text masking and report formatting. Unit-tested in {@code SettingsLogicTest}.
 */
final class SettingsLogic {

    private SettingsLogic() {}

    /** The starship interfaces in CONFIG order: Command, its 23rd- and 25th-century eras, Imperial, Collective. */
    static final int[] STARSHIP_FACTIONS = {Skin.COMMAND, Skin.ERA23, Skin.ERA25, Skin.IMPERIAL, Skin.COLLECTIVE};

    /** True for a starship interface (any of {@link #STARSHIP_FACTIONS}). */
    static boolean isStarshipFaction(int f) {
        for (int t : STARSHIP_FACTIONS) if (t == f) return true;
        return false;
    }

    /** Wallpaper dimming choices (percent) for BACKGROUND = WALLPAPER. */
    static final int[] DIM_LEVELS = {30, 55, 75};
    /** Dock slot counts offered (0 = no dock). */
    static final int[] DOCK_SIZES = {0, 4, 5};

    /** WallpaperManager.FLAG_SYSTEM / FLAG_LOCK (same values, kept here so this class stays framework-free). */
    static final int WALLPAPER_HOME = 1, WALLPAPER_LOCK = 2;

    /** How many unmatched / failed files an import report lists before summarising. */
    static final int REPORT_MAX_UNMATCHED = 12, REPORT_MAX_FAILED = 8;

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /**
     * Buttons per row in an option group: all of them when they fit ({@code ≤4}, or {@code ≤6} on wide
     * windows), otherwise rows of three.
     */
    static int choiceColumns(int optionCount, boolean wide) {
        return optionCount <= (wide ? 6 : 4) ? Math.max(1, optionCount) : 3;
    }

    /**
     * Corner style (Ui.SQUARE / PILL / LEFT / RIGHT) for option {@code index} so each row reads as one
     * segmented console bar: rounded left end, square middles, rounded right end; a lone option is a pill.
     */
    static int choiceCorner(int index, int columns, int optionCount) {
        if (columns == 1) return Ui.PILL;
        int column = index % columns;
        if (column == 0) return Ui.LEFT;
        if (column == columns - 1 || index == optionCount - 1) return Ui.RIGHT;
        return Ui.SQUARE;
    }

    /** Selected WALLPAPER DIMMING option for a stored dim percentage. */
    static int dimIndex(int dimPercent) {
        if (dimPercent <= DIM_LEVELS[0]) return 0;
        return dimPercent <= DIM_LEVELS[1] ? 1 : 2;
    }

    /** Selected DOCK SLOTS option for a stored dock size. */
    static int dockIndex(int dockCount) {
        if (dockCount == 0) return 0;
        return dockCount == 4 ? 1 : 2;
    }

    /** WallpaperManager flags for the APPLY dialog choice (HOME SCREEN / LOCK SCREEN / HOME + LOCK). */
    static int wallpaperTarget(int choice) {
        switch (choice) {
            case 0: return WALLPAPER_HOME;
            case 1: return WALLPAPER_LOCK;
            default: return WALLPAPER_HOME | WALLPAPER_LOCK;
        }
    }

    /** True for a dotted IPv4 address such as 192.168.1.42 (each part 0..255). */
    static boolean isIpv4(String text) {
        if (text == null || !IPV4.matcher(text).matches()) return false;
        for (String part : text.split("\\.")) {
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /** "••••" plus the last four characters, for API keys and secrets. */
    static String maskSecret(String secret) {
        return "••••" + secret.substring(Math.max(0, secret.length() - 4));
    }

    /** Shortens long ids to "first 12…last 8" so they fit on a button. */
    static String abbreviate(String text) {
        return text.length() > 26 ? text.substring(0, 12) + "…" + text.substring(text.length() - 8) : text;
    }

    /** "#RRGGBB" for an ARGB colour (alpha dropped). */
    static String hexColor(int argb) {
        return String.format(Locale.US, "#%06X", argb & 0xFFFFFF);
    }

    /** Display name for a hidden-app key ("pkg/Activity#user") when the app is no longer installed. */
    static String hiddenAppName(String key) {
        int hash = key.indexOf('#');
        return hash > 0 ? key.substring(0, hash) : key;
    }

    /** Title of a home panel row in PANELS (ORDER &amp; VISIBILITY). */
    static String panelTitle(String panelId) {
        int widgetSlot = WidgetSlots.slotOf(panelId);
        if (widgetSlot > 0) return "ANDROID " + WidgetSlots.fallbackName(widgetSlot);
        switch (panelId) {
            case "weather": return "WEATHER";
            case "calendar": return "SCHEDULE (CALENDAR)";
            case "media": return "AUDIO SYSTEMS (MEDIA)";
            case "power": return "POWER SYSTEMS (BATTERY)";
            default: return "SHIP SYSTEMS (DEVICE)";
        }
    }

    /** "1 LIGHT" / "3 LIGHTS" style counts. */
    static String count(int n, String singular, String plural) {
        return n + " " + (n == 1 ? singular : plural);
    }

    /** Whole minutes left, rounded up (cloak countdown). */
    static long minutesLeft(long remainingMs) {
        return (remainingMs + 59_999L) / 60_000L;
    }

    /**
     * The text of the sound-import report dialog: assigned lines, then up to 12 unmatched files and up to
     * 8 failures.
     */
    static String importReport(List<String> lines, List<String> unmatched, List<String> failed) {
        StringBuilder sb = new StringBuilder();
        if (lines.isEmpty()) sb.append("No events were assigned.\n");
        for (String line : lines) sb.append(line).append('\n');
        if (!unmatched.isEmpty()) {
            sb.append("\nNOT MATCHED (assign manually if wanted):\n");
            for (int i = 0; i < unmatched.size() && i < REPORT_MAX_UNMATCHED; i++) {
                sb.append("• ").append(unmatched.get(i)).append('\n');
            }
            if (unmatched.size() > REPORT_MAX_UNMATCHED) {
                sb.append("• +").append(unmatched.size() - REPORT_MAX_UNMATCHED).append(" more\n");
            }
        }
        if (!failed.isEmpty()) {
            sb.append("\nCOULD NOT USE:\n");
            for (int i = 0; i < failed.size() && i < REPORT_MAX_FAILED; i++) {
                sb.append("• ").append(failed.get(i)).append('\n');
            }
        }
        return sb.toString().trim();
    }

    /** Signal's colour sliders: hue in degrees and saturation in percent, from an ARGB colour's HSV. */
    static int[] signalSliders(float[] hsv) {
        return new int[]{Math.round(hsv[0]), Math.round(hsv[1] * 100f)};
    }
}
