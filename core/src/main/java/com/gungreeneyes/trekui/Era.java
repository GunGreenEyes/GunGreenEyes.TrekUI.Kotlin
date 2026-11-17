package com.gungreeneyes.trekui;

/**
 * Pure (Android-free) rules for the two extra Command eras, {@link Skin#ERA23} and
 * {@link Skin#ERA25}: their colour themes (as {@code #RRGGBB} specs, so JVM tests can check
 * contrast without {@code android.graphics.Color}), WCAG contrast maths, the blinking indicator
 * lamps of the 23rd-century console and the cut-corner polygons of the 25th-century glass panels.
 *
 * <p>The drawing itself lives in {@link EraDraw} (controls, frame corners, brackets, icon frames,
 * ornaments, previews) and {@link EraBoot} (start-up sequences); both take every size and timing
 * decision from here so it is unit-tested in {@code EraTest}.
 *
 * <p>Both looks are original geometry evoking an era, not copies of any screen art:
 * <pre>
 *  23RD CENTURY  chunky square keys in primary colours with a bevel, rows of indicator lamps
 *                (some blinking), plain geometric sans lettering (Josefin Sans), no elbows.
 *  25TH CENTURY  thin translucent blue/grey panels layered over each other, 1dp outlines,
 *                45° cut corners, fine tick rules and light Antonio lettering.
 * </pre>
 */
final class Era {
    private Era() {}

    // ---- colour themes ----------------------------------------------------------------------------

    /**
     * Theme spec layout: name, six accents (slot 0 primary .. 5), text high, text low, alert, panel.
     * {@link Palette#fromSpec} turns a spec into a {@link Palette}.
     */
    static final int SPEC_NAME = 0, SPEC_ACCENT0 = 1, SPEC_ACCENTS = 6, SPEC_TEXT_HI = 7, SPEC_TEXT_LO = 8,
            SPEC_ALERT = 9, SPEC_PANEL = 10, SPEC_LENGTH = 11;

    /**
     * 23rd-century console themes. Every accent is bright enough to carry the black lettering of a
     * key (WCAG contrast at least {@link #MIN_TEXT_CONTRAST} against black).
     */
    static final String[][] ERA23_THEMES = {
        {"BRIDGE PRIMARIES", "#F2C230", "#E5402F", "#4C8FE0", "#46B85C", "#F08A24", "#B88AE0",
            "#F4EEDC", "#C9B98A", "#FF3B2B", "#15120C"},
        {"ENGINEERING DECK", "#F0A030", "#D9472B", "#2FA8A0", "#E8D25A", "#C8642A", "#7FC6E8",
            "#F6EBD6", "#D2B48A", "#FF3B2B", "#16100A"},
        {"SICKBAY", "#7FD0E0", "#E86A8A", "#9FD27A", "#F0E08A", "#6FA0E8", "#D8A0E8",
            "#EEF6F6", "#A8C8CC", "#FF4A3A", "#0C1214"},
    };

    /**
     * 25th-century glass themes: blue/grey families with one warm signal accent. Accents are drawn
     * as lines and translucent fills on the dark background, with light lettering on top.
     */
    static final String[][] ERA25_THEMES = {
        {"FLEET GLASS", "#7FA8D8", "#A9BCCF", "#5C86B8", "#C9D6E3", "#E0A060", "#8A9BB0",
            "#E8F0F8", "#9FB3C8", "#F0604A", "#0B121A"},
        {"STARLIGHT SILVER", "#B8C4D0", "#8FA4BA", "#D8E0E8", "#7A90A8", "#9CC8E8", "#A0A8C8",
            "#F0F4F8", "#A8B4C0", "#F06A50", "#0E1216"},
        {"DEEP SPACE CYAN", "#5FC0D8", "#8AA8C0", "#4A9CC0", "#B8E0EC", "#E8B060", "#7090B0",
            "#E4F4FA", "#8FB8C8", "#F05A48", "#08121A"},
    };

    /** WCAG AA contrast for normal text. */
    static final double MIN_TEXT_CONTRAST = 4.5;
    /** WCAG contrast for UI components and graphical objects (lines on the background). */
    static final double MIN_LINE_CONTRAST = 3.0;

    /** Screen backgrounds: warm console black (23rd) and blue-black glass (25th). */
    static final int ERA23_BG = 0xFF0A0908, ERA25_BG = 0xFF05080C;
    /** Lettering on a control: black on the 23rd-century keys, pale blue-white on 25th-century glass. */
    static final int ERA23_INK = 0xFF000000, ERA25_INK = 0xFFE8F0F8;
    /** How much of a control's colour tints a 25th-century glass fill (over the background). */
    static final float GLASS_TINT = 0.16f;

    /** Themes offered for an era faction (empty for any other faction). */
    static String[][] themes(int faction) {
        if (faction == Skin.ERA23) return ERA23_THEMES;
        if (faction == Skin.ERA25) return ERA25_THEMES;
        return new String[0][];
    }

    /** Clamps a stored theme index into the era's theme list (0 when out of range). */
    static int themeIndex(int faction, int stored) {
        int n = themes(faction).length;
        return stored < 0 || stored >= n ? 0 : stored;
    }

    /** Parses {@code #RRGGBB} (or {@code #AARRGGBB}) to an ARGB int; RGB-only input becomes opaque. */
    static int rgb(String hex) {
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        long v = Long.parseLong(s, 16);
        if (s.length() == 6) v |= 0xFF000000L;
        return (int) v;
    }

    /** sRGB channel (0..255) to linear light (0..1). */
    static double linear(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    /** WCAG relative luminance of a colour's RGB channels (alpha ignored), 0..1. */
    static double relLuminance(int color) {
        return 0.2126 * linear((color >> 16) & 0xFF) + 0.7152 * linear((color >> 8) & 0xFF) + 0.0722 * linear(color & 0xFF);
    }

    /** WCAG contrast ratio between two colours, 1..21. */
    static double contrast(int a, int b) {
        double la = relLuminance(a), lb = relLuminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** The opaque colour a 25th-century glass control shows: {@code color} at {@link #GLASS_TINT} over the background. */
    static int glassFill(int color) {
        return Skin.mix(ERA25_BG, color, GLASS_TINT);
    }

    // ---- indicator lamps (23rd century) ------------------------------------------------------------

    /** Lamp colours: signal red, amber, green, blue and white, as lit bulbs behind coloured caps. */
    static final int[] LAMPS = {0xFFFF3B2B, 0xFFFFC83A, 0xFF4CE06A, 0xFF5AA8FF, 0xFFF4F0E0};
    /** Base blink half-period; each lamp adds its own offset so a console never blinks in step. */
    static final long BLINK_MS = 520;
    /** Lamp size, spacing and inset from the key edge, in dp. */
    static final float LAMP_DP = 6f, LAMP_GAP_DP = 4f, LAMP_INSET_DP = 7f;
    /** At most this many lamps on one key. */
    static final int MAX_LAMPS = 3;

    /** A small, stable scramble of an int (used to vary lamps per key without randomness). */
    static int hash(int seed) {
        int h = seed * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA77;
        h ^= h >>> 13;
        return h & 0x7FFFFFFF;
    }

    /** True for the lamps that blink (about one in three); the rest burn steadily. */
    static boolean blinks(int seed) {
        return hash(seed) % 3 == 0;
    }

    /** Half-period of a lamp's blink: {@link #BLINK_MS} plus up to 3/4 of it more, fixed per lamp. */
    static long halfPeriod(int seed) {
        return BLINK_MS + (hash(seed ^ 0x5bd1e995) % (BLINK_MS * 3 / 4));
    }

    /** Whether a lamp is lit at {@code timeMs}: steady lamps always are; blinking lamps alternate. */
    static boolean lampOn(long timeMs, int seed) {
        if (!blinks(seed)) return true;
        long half = halfPeriod(seed);
        long phase = hash(seed ^ 0x27d4eb2d) % (half * 2);
        return ((timeMs + phase) / half) % 2 == 0;
    }

    /** Milliseconds until a blinking lamp next changes state (a steady lamp never does: -1). */
    static long nextToggleIn(long timeMs, int seed) {
        if (!blinks(seed)) return -1;
        long half = halfPeriod(seed);
        long phase = hash(seed ^ 0x27d4eb2d) % (half * 2);
        long into = (timeMs + phase) % half;
        return half - into;
    }

    /** Colour of lamp {@code index} on the key with {@code seed}. */
    static int lampColor(int seed, int index) {
        return LAMPS[hash(seed * 31 + index) % LAMPS.length];
    }

    /** How many lamps fit on a key {@code widthPx} wide ({@code d} px per dp): 1 on buttons, up to 3 on tiles. */
    static int lampCount(float widthPx, float heightPx, float d) {
        if (widthPx < 40 * d || heightPx < 18 * d) return 0;
        if (heightPx < 40 * d) return 1;
        float room = widthPx * 0.35f;
        int n = (int) ((room + LAMP_GAP_DP * d) / ((LAMP_DP + LAMP_GAP_DP) * d));
        return Math.max(1, Math.min(MAX_LAMPS, n));
    }

    // ---- cut corners (25th century) ----------------------------------------------------------------

    /** Corner flags for {@link #cutPolygon}: top-left, top-right, bottom-right, bottom-left. */
    static final int TL = 1, TR = 2, BR = 4, BL = 8;
    /** Largest corner cut, in dp, and its share of the shorter side. */
    static final float MAX_CUT_DP = 12f, CUT_SHARE = 0.32f;

    /** Corner cut for a w x h panel: {@link #CUT_SHARE} of the shorter side, at most {@link #MAX_CUT_DP}. */
    static float cutSize(float w, float h, float d) {
        return Math.max(0f, Math.min(Math.min(w, h) * CUT_SHARE, MAX_CUT_DP * d));
    }

    /**
     * Which corners a 25th-century control cuts for an {@link Ui} corner kind: SQUARE cuts the
     * top-left and bottom-right (the signature diagonal), LEFT both left corners, RIGHT both right
     * corners, PILL all four.
     */
    static int cutsFor(int kind) {
        switch (kind) {
            case Ui.LEFT: return TL | BL;
            case Ui.RIGHT: return TR | BR;
            case Ui.PILL: return TL | TR | BR | BL;
            default: return TL | BR;
        }
    }

    /**
     * Outline of a rectangle with 45° cuts of size {@code k} on the flagged corners, clockwise from
     * the top-left, as x,y pairs. {@code k} is clamped to half the shorter side. Uncut corners add
     * one point, cut corners two.
     */
    static float[] cutPolygon(float l, float t, float r, float b, float k, int corners) {
        k = Math.max(0f, Math.min(k, Math.min(r - l, b - t) / 2f));
        float[] out = new float[16];
        int n = 0;
        if ((corners & TL) != 0 && k > 0) { out[n++] = l; out[n++] = t + k; out[n++] = l + k; out[n++] = t; }
        else { out[n++] = l; out[n++] = t; }
        if ((corners & TR) != 0 && k > 0) { out[n++] = r - k; out[n++] = t; out[n++] = r; out[n++] = t + k; }
        else { out[n++] = r; out[n++] = t; }
        if ((corners & BR) != 0 && k > 0) { out[n++] = r; out[n++] = b - k; out[n++] = r - k; out[n++] = b; }
        else { out[n++] = r; out[n++] = b; }
        if ((corners & BL) != 0 && k > 0) { out[n++] = l + k; out[n++] = b; out[n++] = l; out[n++] = b - k; }
        else { out[n++] = l; out[n++] = b; }
        float[] trimmed = new float[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    /** Fine tick rule: spacing between ticks in dp, and every how many ticks one is drawn long. */
    static final float TICK_SPACING_DP = 8f;
    static final int TICK_MAJOR_EVERY = 5;

    /** Number of ticks on a rule {@code lengthPx} long (at least 0, one at each end when it fits). */
    static int tickCount(float lengthPx, float d) {
        float step = TICK_SPACING_DP * d;
        if (lengthPx < step || step <= 0) return 0;
        return (int) (lengthPx / step) + 1;
    }
}
