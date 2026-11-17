package com.gungreeneyes.trekui;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.DisplayMetrics;

import androidx.window.layout.WindowMetrics;
import androidx.window.layout.WindowMetricsCalculator;

/**
 * Single source of truth for responsive sizing on every screen the launcher runs on:
 * small phones, tall 21:9 phones, foldables (folded and open), tablets and desktop windows.
 *
 * <p>All layout code asks this class for margins, gutters, a UI scale factor and grid column
 * counts instead of using hard-coded dp values. The maths lives in pure static functions
 * ({@link #compute}) so it is unit-testable on the JVM without a device.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li><b>Width size class</b> follows Material 3 window size classes:
 *       COMPACT &lt; 600dp, MEDIUM &lt; 840dp, EXPANDED &ge; 840dp.</li>
 *   <li><b>Height size class</b>: COMPACT &lt; 480dp, MEDIUM &lt; 900dp, EXPANDED &ge; 900dp.</li>
 *   <li><b>Margin</b> (outer edge padding): 12 / 24 / 32dp by width class, never more than 6% of width.</li>
 *   <li><b>Gutter</b> (space between elements): 6 / 10 / 14dp by width class.</li>
 *   <li><b>UI scale</b>: the shorter screen side relative to a 360dp reference phone, clamped to
 *       0.85 .. 1.6 so text and frames neither crush on small phones nor balloon on tablets.</li>
 *   <li><b>Grid columns</b>: as many tiles of at least the minimum tile width as fit after margins.</li>
 * </ul>
 */
public final class ScreenMetrics {

    /** Material 3 window size classes. */
    public enum SizeClass { COMPACT, MEDIUM, EXPANDED }

    /** Reference phone width the original layouts were designed against. */
    public static final float REFERENCE_SHORT_SIDE_DP = 360f;
    public static final float MIN_SCALE = 0.85f;
    public static final float MAX_SCALE = 1.6f;

    /** Window width in dp. */
    public final float widthDp;
    /** Window height in dp. */
    public final float heightDp;
    /** Pixels per dp. */
    public final float density;
    public final SizeClass widthClass;
    public final SizeClass heightClass;
    /** Outer edge padding in dp. */
    public final float marginDp;
    /** Space between neighbouring elements in dp. */
    public final float gutterDp;
    /** Multiplier for fixed-size console/Pipe elements (bars, elbows, text). */
    public final float uiScale;

    private ScreenMetrics(float widthDp, float heightDp, float density) {
        this.widthDp = widthDp;
        this.heightDp = heightDp;
        this.density = density;
        this.widthClass = widthClassFor(widthDp);
        this.heightClass = heightClassFor(heightDp);
        this.marginDp = marginFor(widthDp);
        this.gutterDp = gutterFor(widthDp);
        this.uiScale = uiScaleFor(widthDp, heightDp);
    }

    // ---------------------------------------------------------------------------------------------
    // Factories
    // ---------------------------------------------------------------------------------------------

    /** Metrics for the activity's current window (multi-window and foldable aware). */
    public static ScreenMetrics of(Activity activity) {
        WindowMetrics wm = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity);
        Rect b = wm.getBounds();
        float density = activity.getResources().getDisplayMetrics().density;
        return new ScreenMetrics(b.width() / density, b.height() / density, density);
    }

    /** Metrics from a context's configuration, for views and dialogs without an activity handle. */
    public static ScreenMetrics of(Context context) {
        Configuration c = context.getResources().getConfiguration();
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        float w = c.screenWidthDp > 0 ? c.screenWidthDp : dm.widthPixels / dm.density;
        float h = c.screenHeightDp > 0 ? c.screenHeightDp : dm.heightPixels / dm.density;
        return new ScreenMetrics(w, h, dm.density);
    }

    /** Pure factory used by unit tests and previews. */
    public static ScreenMetrics compute(float widthDp, float heightDp, float density) {
        if (widthDp <= 0 || heightDp <= 0 || density <= 0) {
            throw new IllegalArgumentException("dimensions and density must be positive");
        }
        return new ScreenMetrics(widthDp, heightDp, density);
    }

    // ---------------------------------------------------------------------------------------------
    // Pure rules (static so tests can exercise them directly)
    // ---------------------------------------------------------------------------------------------

    static SizeClass widthClassFor(float widthDp) {
        if (widthDp < 600f) return SizeClass.COMPACT;
        if (widthDp < 840f) return SizeClass.MEDIUM;
        return SizeClass.EXPANDED;
    }

    static SizeClass heightClassFor(float heightDp) {
        if (heightDp < 480f) return SizeClass.COMPACT;
        if (heightDp < 900f) return SizeClass.MEDIUM;
        return SizeClass.EXPANDED;
    }

    static float marginFor(float widthDp) {
        float base;
        switch (widthClassFor(widthDp)) {
            case COMPACT: base = 12f; break;
            case MEDIUM: base = 24f; break;
            default: base = 32f; break;
        }
        return Math.min(base, widthDp * 0.06f);
    }

    static float gutterFor(float widthDp) {
        switch (widthClassFor(widthDp)) {
            case COMPACT: return 6f;
            case MEDIUM: return 10f;
            default: return 14f;
        }
    }

    static float uiScaleFor(float widthDp, float heightDp) {
        float shortSide = Math.min(widthDp, heightDp);
        return clamp(shortSide / REFERENCE_SHORT_SIDE_DP, MIN_SCALE, MAX_SCALE);
    }

    /**
     * Number of grid columns that fit, given a minimum tile width, after outer margins and gutters.
     * Always at least {@code minColumns} and at most {@code maxColumns}.
     */
    static int columnsFor(float widthDp, float minTileDp, int minColumns, int maxColumns) {
        float usable = widthDp - 2 * marginFor(widthDp);
        float gutter = gutterFor(widthDp);
        int n = (int) Math.floor((usable + gutter) / (minTileDp + gutter));
        return Math.max(minColumns, Math.min(maxColumns, n));
    }

    static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------------------------------------------------------------------------------------------
    // Instance helpers
    // ---------------------------------------------------------------------------------------------

    /** Width / height; above 1 is landscape. */
    public float aspectRatio() { return widthDp / heightDp; }

    public boolean isLandscape() { return widthDp > heightDp; }

    /** True for 20:9 and taller phones, where vertical stacks need extra spacing. */
    public boolean isTall() { return Math.max(widthDp, heightDp) / Math.min(widthDp, heightDp) >= 2.0f; }

    /** True when there is room for a two-pane layout (tablets, open foldables, landscape phones). */
    public boolean isTwoPane() { return widthClass != SizeClass.COMPACT; }

    public int columns(float minTileDp, int minColumns, int maxColumns) {
        return columnsFor(widthDp, minTileDp, minColumns, maxColumns);
    }

    /** dp to whole pixels. */
    public int px(float dp) { return Math.round(dp * density); }

    /** A design-time dp value scaled for this screen, in pixels. */
    public int scaledPx(float dp) { return Math.round(dp * uiScale * density); }

    public int marginPx() { return px(marginDp); }

    public int gutterPx() { return px(gutterDp); }

    @Override
    public String toString() {
        return String.format(java.util.Locale.US, "ScreenMetrics[%.0fx%.0fdp @%.2f %s/%s scale=%.2f margin=%.0f gutter=%.0f]",
                widthDp, heightDp, density, widthClass, heightClass, uiScale, marginDp, gutterDp);
    }
}
