package com.gungreeneyes.trekui

import android.app.Activity
import android.content.Context
import androidx.window.layout.WindowMetricsCalculator
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Single source of truth for responsive sizing on every screen the launcher runs on:
 * small phones, tall 21:9 phones, foldables (folded and open), tablets and desktop windows.
 *
 * All layout code asks this class for margins, gutters, a UI scale factor and grid column
 * counts instead of using hard-coded dp values. The maths lives in pure static functions
 * ([compute]) so it is unit-testable on the JVM without a device.
 *
 * ## Rules
 * - **Width size class** follows Material 3 window size classes:
 *   COMPACT < 600dp, MEDIUM < 840dp, EXPANDED >= 840dp.
 * - **Height size class**: COMPACT < 480dp, MEDIUM < 900dp, EXPANDED >= 900dp.
 * - **Margin** (outer edge padding): 12 / 24 / 32dp by width class, never more than 6% of width.
 * - **Gutter** (space between elements): 6 / 10 / 14dp by width class.
 * - **UI scale**: the shorter screen side relative to a 360dp reference phone, clamped to
 *   0.85 .. 1.6 so text and frames neither crush on small phones nor balloon on tablets.
 * - **Grid columns**: as many tiles of at least the minimum tile width as fit after margins.
 *
 * Java interop: the measured values are [JvmField]s and the factories and rules [JvmStatic], so Java
 * keeps reading `m.uiScale` and calling `ScreenMetrics.of(activity)`; [isLandscape], [isTall] and
 * [isTwoPane] keep their `isX()` getters.
 */
class ScreenMetrics private constructor(
    /** Window width in dp. */
    @JvmField val widthDp: Float,
    /** Window height in dp. */
    @JvmField val heightDp: Float,
    /** Pixels per dp. */
    @JvmField val density: Float,
) {
    /** Material 3 window size classes. */
    enum class SizeClass { COMPACT, MEDIUM, EXPANDED }

    @JvmField val widthClass: SizeClass = widthClassFor(widthDp)
    @JvmField val heightClass: SizeClass = heightClassFor(heightDp)
    /** Outer edge padding in dp. */
    @JvmField val marginDp: Float = marginFor(widthDp)
    /** Space between neighbouring elements in dp. */
    @JvmField val gutterDp: Float = gutterFor(widthDp)
    /** Multiplier for fixed-size console elements (bars, elbows, text). */
    @JvmField val uiScale: Float = uiScaleFor(widthDp, heightDp)

    // ---------------------------------------------------------------------------------------------
    // Instance helpers
    // ---------------------------------------------------------------------------------------------

    /** Width / height; above 1 is landscape. */
    fun aspectRatio(): Float = widthDp / heightDp

    val isLandscape: Boolean
        get() = widthDp > heightDp

    /** True for 20:9 and taller phones, where vertical stacks need extra spacing. */
    val isTall: Boolean
        get() = max(widthDp, heightDp) / min(widthDp, heightDp) >= 2.0f

    /** True when there is room for a two-pane layout (tablets, open foldables, landscape phones). */
    val isTwoPane: Boolean
        get() = widthClass != SizeClass.COMPACT

    fun columns(minTileDp: Float, minColumns: Int, maxColumns: Int): Int =
        columnsFor(widthDp, minTileDp, minColumns, maxColumns)

    /** dp to whole pixels. */
    fun px(dp: Float): Int = Math.round(dp * density)

    /** A design-time dp value scaled for this screen, in pixels. */
    fun scaledPx(dp: Float): Int = Math.round(dp * uiScale * density)

    fun marginPx(): Int = px(marginDp)

    fun gutterPx(): Int = px(gutterDp)

    override fun toString(): String = String.format(
        Locale.US, "ScreenMetrics[%.0fx%.0fdp @%.2f %s/%s scale=%.2f margin=%.0f gutter=%.0f]",
        widthDp, heightDp, density, widthClass, heightClass, uiScale, marginDp, gutterDp,
    )

    companion object {
        /** Reference phone width the original layouts were designed against. */
        const val REFERENCE_SHORT_SIDE_DP = 360f
        const val MIN_SCALE = 0.85f
        const val MAX_SCALE = 1.6f

        // -----------------------------------------------------------------------------------------
        // Factories
        // -----------------------------------------------------------------------------------------

        /** Metrics for the activity's current window (multi-window and foldable aware). */
        @JvmStatic
        fun of(activity: Activity): ScreenMetrics {
            val b = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).bounds
            val density = activity.resources.displayMetrics.density
            return ScreenMetrics(b.width() / density, b.height() / density, density)
        }

        /** Metrics from a context's configuration, for views and dialogs without an activity handle. */
        @JvmStatic
        fun of(context: Context): ScreenMetrics {
            val c = context.resources.configuration
            val dm = context.resources.displayMetrics
            val w = if (c.screenWidthDp > 0) c.screenWidthDp.toFloat() else dm.widthPixels / dm.density
            val h = if (c.screenHeightDp > 0) c.screenHeightDp.toFloat() else dm.heightPixels / dm.density
            return ScreenMetrics(w, h, dm.density)
        }

        /** Pure factory used by unit tests and previews. */
        @JvmStatic
        fun compute(widthDp: Float, heightDp: Float, density: Float): ScreenMetrics {
            if (widthDp <= 0 || heightDp <= 0 || density <= 0) {
                throw IllegalArgumentException("dimensions and density must be positive")
            }
            return ScreenMetrics(widthDp, heightDp, density)
        }

        // -----------------------------------------------------------------------------------------
        // Pure rules (static so tests can exercise them directly)
        // -----------------------------------------------------------------------------------------

        @JvmStatic
        fun widthClassFor(widthDp: Float): SizeClass = when {
            widthDp < 600f -> SizeClass.COMPACT
            widthDp < 840f -> SizeClass.MEDIUM
            else -> SizeClass.EXPANDED
        }

        @JvmStatic
        fun heightClassFor(heightDp: Float): SizeClass = when {
            heightDp < 480f -> SizeClass.COMPACT
            heightDp < 900f -> SizeClass.MEDIUM
            else -> SizeClass.EXPANDED
        }

        @JvmStatic
        fun marginFor(widthDp: Float): Float {
            val base = when (widthClassFor(widthDp)) {
                SizeClass.COMPACT -> 12f
                SizeClass.MEDIUM -> 24f
                SizeClass.EXPANDED -> 32f
            }
            return min(base, widthDp * 0.06f)
        }

        @JvmStatic
        fun gutterFor(widthDp: Float): Float = when (widthClassFor(widthDp)) {
            SizeClass.COMPACT -> 6f
            SizeClass.MEDIUM -> 10f
            SizeClass.EXPANDED -> 14f
        }

        @JvmStatic
        fun uiScaleFor(widthDp: Float, heightDp: Float): Float =
            clamp(min(widthDp, heightDp) / REFERENCE_SHORT_SIDE_DP, MIN_SCALE, MAX_SCALE)

        /**
         * Number of grid columns that fit, given a minimum tile width, after outer margins and gutters.
         * Always at least `minColumns` and at most `maxColumns`.
         */
        @JvmStatic
        fun columnsFor(widthDp: Float, minTileDp: Float, minColumns: Int, maxColumns: Int): Int {
            val usable = widthDp - 2 * marginFor(widthDp)
            val gutter = gutterFor(widthDp)
            val n = floor(((usable + gutter) / (minTileDp + gutter)).toDouble()).toInt()
            return max(minColumns, min(maxColumns, n))
        }

        @JvmStatic
        fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))
    }
}
