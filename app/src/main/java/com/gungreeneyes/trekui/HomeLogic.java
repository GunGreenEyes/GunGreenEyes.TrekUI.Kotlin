package com.gungreeneyes.trekui;

import java.util.Calendar;
import java.util.Locale;

/**
 * Pure (Android-free) rules used by the home screen ({@link MainActivity}).
 *
 * <p>Everything here is a static function of its arguments, or a tiny state holder with no
 * framework dependencies, so it can be unit-tested on the JVM (see {@code HomeLogicTest}).
 * MainActivity keeps the views; this class keeps the decisions.
 */
final class HomeLogic {

    private HomeLogic() {}

    /** Window width (dp) the original single-column-family layouts were designed for. */
    static final float REFERENCE_WIDTH_DP = 360f;

    /** Longest label put on a tile or dock slot when one is assigned from an app name. */
    static final int SHORT_LABEL_MAX = 14;

    /** Hub page shown in the reveal layouts: the shortcut tiles. */
    static final int HUB_PAGE_TILES = 0;
    /** Hub page: one node per information panel (weather, calendar…). */
    static final int HUB_PAGE_INFO = 1;
    /** Hub page: the "stations" (ship stations) list. */
    static final int HUB_PAGE_STATIONS = 2;

    /** Package-name fragments of chat, call and messaging apps (launching one plays the COMM chirp). */
    static final String[] COMMS_PACKAGES = {"thoughtcrime.securesms", "com.microsoft.teams", "whatsapp", "telegram",
            "com.discord", "com.facebook.orca", "com.slack", "com.skype", "us.zoom", "com.google.android.apps.tachyon",
            "com.google.android.apps.meetings", "com.viber", "jp.naver.line", "com.kakao.talk", "ch.threema", "im.vector",
            "com.wire", "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.samsung.android.dialer",
            "com.google.android.dialer", "com.android.dialer", "com.android.mms", "com.anthropic.claude"};

    /** Package-name fragments of e-mail apps, which are never treated as comms apps. */
    static final String[] EMAIL_MARKERS = {"mail", "outlook", "android.gm", ".email", "office", "protonmail", "spark"};

    /** Palette accent order used to colour consecutive tiles and hub nodes. */
    private static final int[] ACCENT_CYCLE = {0, 1, 3, 2, 4, 5};

    /** Palette accent index for the n-th tile / node / settings row (cycles through six accents). */
    static int accentIndex(int position) {
        return ACCENT_CYCLE[Math.floorMod(position, ACCENT_CYCLE.length)];
    }

    /**
     * Number of shortcut-tile columns for the grid layouts.
     *
     * @param baseColumns     columns the interface uses on a 360dp-wide phone (Command 3, Imperial 2, Collective 1…)
     * @param availableWidthDp width the tile grid can use, in dp
     * @return {@code baseColumns} on phones, growing with width up to twice that on tablets / unfolded foldables
     */
    static int tileColumns(int baseColumns, float availableWidthDp) {
        int base = Math.max(1, baseColumns);
        int scaled = (int) Math.floor(base * availableWidthDp / REFERENCE_WIDTH_DP);
        return Math.max(base, Math.min(base * 2, scaled));
    }

    /** Trims an app label to at most {@link #SHORT_LABEL_MAX} characters for a tile caption. */
    static String shortLabel(String label) {
        String trimmed = label == null ? "" : label.trim();
        return trimmed.length() > SHORT_LABEL_MAX ? trimmed.substring(0, SHORT_LABEL_MAX).trim() : trimmed;
    }

    /** Pictogram name (see IconFactory.glyph) for an console function assigned to a tile or slot. */
    static String actionGlyph(String action) {
        if (action == null) return "apps";
        switch (action) {
            case Tile.A_SEARCH: return "search";
            case Tile.A_CONFIG:
            case Tile.A_SHADE:
            case Tile.A_CASCADE: return "system";
            case Tile.A_ANDROID:
            case Tile.A_PANEL: return "settings";
            case Tile.A_DIALER: return "phone";
            case Tile.A_CLOAK:
            case Tile.A_LOCK: return "secure";
            case Tile.A_RED_ALERT: return "alert";
            case Tile.A_GOVEE: return "weather";
            case Tile.A_NEST:
            case Tile.A_HA: return "home";
            default: return "apps";
        }
    }

    /** Short upper-case caption for an information panel id ("weather" → "WEATHER"). */
    static String shortPanelName(String panelId) {
        int widgetSlot = WidgetSlots.slotOf(panelId);
        if (widgetSlot > 0) return WidgetSlots.fallbackName(widgetSlot);
        return panelId.toUpperCase(Locale.US);
    }

    /** Pictogram for an information panel's hub node, keyed by its short name. */
    static String panelGlyph(String shortName) {
        switch (shortName) {
            case "WEATHER": return "weather";
            case "CALENDAR": return "calendar";
            case "MEDIA": return "music";
            case "POWER": return "alert";
            case "SYSTEMS": return "computer";
            default: return "apps"; // also hosted widget panels ("WIDGET 3")
        }
    }

    /**
     * True when an app should get the communications launch chirp instead of the normal launch sound.
     *
     * @param pkg           package name
     * @param label         app label (may be null)
     * @param isCommCategory whether the app was categorised as COMM by AppInfo
     * @param dialerPkg     default dialer package (may be null)
     * @param smsPkg        default SMS package (may be null)
     */
    static boolean isCommsApp(String pkg, String label, boolean isCommCategory, String dialerPkg, String smsPkg) {
        String p = pkg.toLowerCase(Locale.US);
        for (String marker : EMAIL_MARKERS) {
            if (p.contains(marker)) return false;
        }
        if (p.equals(dialerPkg) || p.equals(smsPkg)) return true;
        for (String fragment : COMMS_PACKAGES) {
            if (p.contains(fragment)) return true;
        }
        String l = label == null ? "" : label.toLowerCase(Locale.US);
        return isCommCategory && (l.contains("messag") || l.contains("chat") || l.contains("phone") || l.contains("call")
                || p.contains("messag") || p.contains("chat") || p.contains("dialer"));
    }

    /**
     * Stardate: 1000 units per year, 41000.0 at the start of 1987, with the fraction of the
     * year (day plus time of day) in the remaining units. Formatted with one decimal.
     */
    static String stardate(Calendar calendar) {
        int year = calendar.get(Calendar.YEAR);
        double dayFraction = (calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)) / 1440.0d;
        double yearFraction = (calendar.get(Calendar.DAY_OF_YEAR) - 1 + dayFraction) / calendar.getActualMaximum(Calendar.DAY_OF_YEAR);
        return String.format(Locale.US, "%.1f", yearFraction * 1000.0d + (year - 1987) * 1000 + 41000);
    }

    /** Battery percentage from the BATTERY_CHANGED level/scale extras, or -1 when unknown. */
    static int batteryPercent(int level, int scale) {
        if (level < 0 || scale <= 0) return -1;
        return Math.round(level * 100.0f / scale);
    }

    /** Clock ticker period while the data cascade animation runs. */
    static final long CASCADE_PERIOD_MS = 2600L;
    /** Small slack so the ticker fires just after the minute/second boundary, never just before. */
    static final long TICK_SLACK_MS = 15L;

    /**
     * Delay until the home ticker should next run: the next second (seconds shown) or minute
     * boundary, but no later than the next cascade step when animations are on.
     */
    static long nextTickDelayMs(long nowMs, boolean showSeconds, boolean animate) {
        long delay = showSeconds ? 1000 - (nowMs % 1000) : 60000 - (nowMs % 60000);
        if (animate) delay = Math.min(delay, CASCADE_PERIOD_MS);
        return delay + TICK_SLACK_MS;
    }

    /** Random-looking console code block text, "NN-NNNN". */
    static String codeText(int twoDigits, int fourDigits) {
        return String.format(Locale.US, "%02d-%04d", twoDigits, fourDigits);
    }

    /** Two-digit tile number ("01".."12") for a zero-based tile position. */
    static String tileNumber(int position) {
        return String.format(Locale.US, "%02d", position + 1);
    }

    /** Slot key for an ConsoleFrame part: prefix "h" + part "elbowT" → "hElbowT". */
    static String framePartSlot(String prefix, String part) {
        return prefix + Character.toUpperCase(part.charAt(0)) + part.substring(1);
    }

    /** The battery sound the home screen should play after a BATTERY_CHANGED update. */
    enum BatteryAlert { NONE, PLUGGED_IN, LOW, CRITICAL }

    /**
     * Battery warning state: plays the power chirp when a charger is connected, a warning at 15%
     * and then every further 4%, and the critical klaxon once at 5%. Plugging in re-arms it.
     */
    static final class BatteryWatch {
        static final int LOW_PERCENT = 15;
        static final int CRITICAL_PERCENT = 5;
        static final int REPEAT_STEP = 4;
        private static final int DISARMED = 101;

        private int lastPlugged = -1;
        private int warnedLevel = DISARMED;
        private boolean criticalWarned;

        /**
         * Feeds one battery update.
         *
         * @param plugged  BatteryManager EXTRA_PLUGGED (0 = on battery)
         * @param percent  battery percentage, or -1 when unknown
         * @param announce false for silent refreshes (initial sticky broadcast, UI rebuilds, not resumed)
         * @return which alert to play
         */
        BatteryAlert update(int plugged, int percent, boolean announce) {
            BatteryAlert alert = BatteryAlert.NONE;
            if (announce) {
                if (lastPlugged == 0 && plugged != 0) {
                    alert = BatteryAlert.PLUGGED_IN;
                }
                if (plugged == 0 && percent >= 0 && percent <= CRITICAL_PERCENT && !criticalWarned) {
                    alert = BatteryAlert.CRITICAL;
                    criticalWarned = true;
                    warnedLevel = percent - REPEAT_STEP;
                } else if (plugged == 0 && percent >= 0 && percent <= LOW_PERCENT && percent < warnedLevel) {
                    alert = BatteryAlert.LOW;
                    warnedLevel = percent - REPEAT_STEP;
                }
            }
            if (plugged != 0) {
                warnedLevel = DISARMED;
                criticalWarned = false;
            }
            lastPlugged = plugged;
            return alert;
        }
    }
}
