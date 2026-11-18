package com.gungreeneyes.trekui.keyboard.logic;

import java.util.List;

/**
 * Key-height presets, one-handed width and the language cycle. Pure Java (unit-tested).
 */
public final class Sizing {
    private Sizing() {}

    /** Size presets S, M, L, XL. */
    public static final int S = 0, M = 1, L = 2, XL = 3;
    /** Row heights (dp) per preset in portrait. */
    static final float[] PORTRAIT_DP = {46f, 52f, 58f, 64f};
    /** Row heights (dp) per preset in landscape. */
    static final float[] LANDSCAPE_DP = {36f, 40f, 45f, 50f};
    /** The keys (without suggestion strip) never take more than this share of the window height. */
    static final float MAX_HEIGHT_SHARE = 0.5f;
    /** One-handed mode: share of the width the keys keep. */
    public static final float ONE_HANDED_SHARE = 0.82f;
    /** One-handed modes. */
    public static final int ONE_HANDED_OFF = 0, ONE_HANDED_LEFT = 1, ONE_HANDED_RIGHT = 2;

    /** Clamps a stored preset into S..XL (M for unknown values). */
    public static int clampPreset(int preset) {
        return preset < S || preset > XL ? M : preset;
    }

    /** Row height in dp for {@code preset} in the given orientation. */
    public static float rowDp(int preset, boolean landscape) {
        return (landscape ? LANDSCAPE_DP : PORTRAIT_DP)[clampPreset(preset)];
    }

    /**
     * Row height in px: the preset, shrunk if {@code rows} rows would exceed {@link #MAX_HEIGHT_SHARE} of a
     * {@code windowPx}-high window (landscape phones), never below 60 % of the preset.
     */
    public static int rowPx(int preset, boolean landscape, float density, int rows, int windowPx) {
        float want = rowDp(preset, landscape) * density;
        if (rows > 0 && windowPx > 0) {
            float max = windowPx * MAX_HEIGHT_SHARE / rows;
            want = Math.max(want * 0.6f, Math.min(want, max));
        }
        return Math.round(want);
    }

    /** The language after {@code current} among {@code enabled} (wraps; the first if current is not on). */
    public static Language nextLanguage(Language current, List<Language> enabled) {
        if (enabled == null || enabled.isEmpty()) return current;
        int i = enabled.indexOf(current);
        return enabled.get((i + 1) % enabled.size());
    }
}
