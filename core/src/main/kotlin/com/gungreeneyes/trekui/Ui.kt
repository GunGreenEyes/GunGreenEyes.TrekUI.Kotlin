package com.gungreeneyes.trekui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Shared look-and-feel toolkit used by every screen: unit conversion, typefaces, text views, cell
 * shapes, buttons, touch feedback (haptics + sound) and small colour helpers.
 *
 * ## Responsive sizing
 * All fixed sizes in the interface are written in dp/sp against a ~360-412dp phone. [dp] and
 * the text helpers multiply them by a per-window *zoom* derived from [ScreenMetrics.uiScale]:
 * - phones (shorter side < 600dp, either orientation): layout zoom 1, so phone layouts are
 *   exactly as designed; text shrinks with `uiScale` on very small phones (< 360dp);
 * - tablets and open foldables (shorter side ≥ 600dp): layout and text grow by half of
 *   `uiScale - 1` (about 1.3x at the 1.6 cap), so bars, elbows and text keep their
 *   proportions instead of looking like a stretched phone.
 *
 * The rules are the pure functions [layoutZoomFor] and [textZoomFor] (unit-tested);
 * the current values are cached per configuration so `dp()` stays cheap inside `onDraw`.
 * Use [rawDp] for sizes that must stay physical (hairlines, touch slop).
 *
 * Java interop: the constants are `const`, the [cloak] switch is a [JvmField] and every function is
 * [JvmStatic], so Java keeps calling `Ui.dp(c, 8)` and assigning `Ui.cloak`.
 */
object Ui {
    /** Corner styles for [shape]: all corners, fully round, left side round, right side round. */
    const val SQUARE = 0
    const val PILL = 1
    const val LEFT = 2
    const val RIGHT = 3

    /*
     * Spacing scale (dp). Every padding, margin and gutter in the interface uses one of these, so
     * gaps line up from screen to screen instead of drifting between 3, 5, 7, 9… dp.
     *  HAIR 2 · XS 4 · GUTTER 6 (space between neighbouring cells) · S 8 · M 12 · L 16 · XL 24
     */
    const val HAIR = 2
    const val XS = 4
    const val GUTTER = 6
    const val S = 8
    const val M = 12
    const val L = 16
    const val XL = 24

    /** Shorter screen side (dp) from which a window counts as a tablet / open foldable. */
    const val LARGE_SCREEN_SHORT_SIDE_DP = 600f
    /** Share of `uiScale - 1` applied as zoom on large screens (0.5 → 1.6 cap becomes 1.3x). */
    const val LARGE_SCREEN_ZOOM_DAMPING = 0.5f
    /** Corner radius large enough to turn any cell into a pill. */
    const val PILL_RADIUS_DP = 999f
    /** Minimum touch target height for buttons (Material guideline). */
    const val MIN_TOUCH_DP = 48f

    private const val RIPPLE_ON_FILL = 0x70FFFFFF
    private const val RIPPLE_ON_CLEAR = 0x50FFFFFF
    private const val ANTONIO = "fonts/Antonio.ttf"
    private const val ANTONIO_REGULAR = 500
    private const val ANTONIO_HEAVY = 700

    private var antonioRegular: Typeface? = null
    private var antonioBold: Typeface? = null
    private val factionRegular = arrayOfNulls<Typeface>(Skin.NAMES.size)
    private val factionBold = arrayOfNulls<Typeface>(Skin.NAMES.size)
    private var cachedLettering = -1

    // zoom cache, keyed on the configuration it was computed for
    private var zoomW = -1
    private var zoomH = -1
    private var zoomDpi = -1
    private var cachedLayoutZoom = 1f
    private var cachedTextZoom = 1f

    // ---- responsive units -------------------------------------------------------------------------

    /**
     * Layout zoom for a window: 1 on phones, `1 + (uiScale - 1) * damping` on tablets.
     *
     * @param uiScale     [ScreenMetrics.uiScale] for the window
     * @param shortSideDp the window's shorter side in dp
     */
    @JvmStatic
    fun layoutZoomFor(uiScale: Float, shortSideDp: Float): Float {
        if (shortSideDp < LARGE_SCREEN_SHORT_SIDE_DP) return 1f
        return max(1f, 1f + (uiScale - 1f) * LARGE_SCREEN_ZOOM_DAMPING)
    }

    /**
     * Text zoom for a window: the layout zoom, additionally shrunk by `uiScale` on phones narrower
     * than the 360dp reference so long labels still fit (never below [ScreenMetrics.MIN_SCALE]).
     */
    @JvmStatic
    fun textZoomFor(uiScale: Float, shortSideDp: Float): Float {
        var zoom = layoutZoomFor(uiScale, shortSideDp)
        if (shortSideDp < LARGE_SCREEN_SHORT_SIDE_DP && uiScale < 1f) {
            zoom *= max(ScreenMetrics.MIN_SCALE, uiScale)
        }
        return zoom
    }

    /** Refreshes the cached zooms if the context's configuration (size or density) changed. */
    private fun updateZoom(c: Context) {
        val cfg = c.resources.configuration
        if (cfg.screenWidthDp == zoomW && cfg.screenHeightDp == zoomH && cfg.densityDpi == zoomDpi) return
        val m = ScreenMetrics.of(c)
        val shortSide = min(m.widthDp, m.heightDp)
        cachedLayoutZoom = layoutZoomFor(m.uiScale, shortSide)
        cachedTextZoom = textZoomFor(m.uiScale, shortSide)
        zoomW = cfg.screenWidthDp
        zoomH = cfg.screenHeightDp
        zoomDpi = cfg.densityDpi
    }

    /** Current layout zoom (see class docs). */
    @JvmStatic
    fun zoom(c: Context): Float {
        updateZoom(c)
        return cachedLayoutZoom
    }

    /** Current text zoom (see class docs). */
    @JvmStatic
    fun textZoom(c: Context): Float {
        updateZoom(c)
        return cachedTextZoom
    }

    /** Pixels per zoomed dp: use instead of `DisplayMetrics.density` in custom drawing code. */
    @JvmStatic
    fun density(c: Context): Float = c.resources.displayMetrics.density * zoom(c)

    /** Converts a design dp value to whole pixels, including the window's layout zoom. */
    @JvmStatic
    fun dp(c: Context, v: Float): Int = Math.round(rawDpF(c, v) * zoom(c))

    /** Converts dp to whole pixels without zoom, for sizes that must stay physical. */
    @JvmStatic
    fun rawDp(c: Context, v: Float): Int = Math.round(rawDpF(c, v))

    private fun rawDpF(c: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)

    /**
     * Sets a TextView's size from a design sp value, applying the faction's typeface scale and the
     * window's text zoom. Use this instead of `TextView.setTextSize(float)`.
     */
    @JvmStatic
    fun setTextSp(t: TextView, sp: Float) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Skin.textScale() * textZoom(t.context))
    }

    /** Text size in pixels for text drawn directly on a Canvas (faction typeface scale and zoom applied). */
    @JvmStatic
    fun px(c: Context, sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp * Skin.textScale() * textZoom(c),
            c.resources.displayMetrics)

    // ---- type -------------------------------------------------------------------------------------

    /**
     * The interface typeface. Command uses Antonio (variable weight 500/700); every other faction
     * loads its own pair from assets ([Skin.fontRegular] / [Skin.fontHeavy]), cached per
     * faction. The Imperial pair is re-read when the lettering option changes.
     */
    @JvmStatic
    fun font(c: Context, heavy: Boolean): Typeface? {
        val f = Skin.faction
        if (cachedLettering != Skin.lettering) {
            cachedLettering = Skin.lettering
            factionRegular[Skin.IMPERIAL] = null
            factionBold[Skin.IMPERIAL] = null
        }
        if (f != Skin.COMMAND) {
            if (factionRegular[f] == null && Skin.fontRegular() == null) {
                // an Antonio variant at the interface's own weights (25th century: light and regular)
                factionRegular[f] = antonio(c, Skin.antonioWeight(false), false)
                factionBold[f] = antonio(c, Skin.antonioWeight(true), true)
            }
            if (factionRegular[f] == null) {
                try {
                    factionRegular[f] = Typeface.createFromAsset(c.assets, Skin.fontRegular())
                    factionBold[f] = Typeface.createFromAsset(c.assets, Skin.fontHeavy())
                } catch (t: Throwable) {
                    factionRegular[f] = Typeface.create("sans-serif", Typeface.NORMAL)
                    factionBold[f] = Typeface.create("sans-serif", Typeface.BOLD)
                }
            }
            return if (heavy) factionBold[f] else factionRegular[f]
        }
        if (antonioRegular == null) {
            // variable weights on API 26+; the default instance (bolded for heavy) on 23-25
            antonioRegular = Compat.variableFont(c.assets, ANTONIO, ANTONIO_REGULAR, false)
            antonioBold = Compat.variableFont(c.assets, ANTONIO, ANTONIO_HEAVY, true)
            if (antonioRegular == null) antonioRegular = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            if (antonioBold == null) antonioBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        }
        return if (heavy) antonioBold else antonioRegular
    }

    /** Antonio at a variable-font weight, falling back to condensed sans-serif. */
    private fun antonio(c: Context, weight: Int, heavy: Boolean): Typeface =
        Compat.variableFont(c.assets, ANTONIO, weight, heavy)

    /** A plain label in the interface typeface; `sp` is the design size before zoom. */
    @JvmStatic
    fun text(c: Context, s: CharSequence?, sp: Float, color: Int): TextView {
        val t = TextView(c)
        t.text = s
        setTextSp(t, sp)
        t.setTextColor(color)
        t.typeface = font(c, false)
        t.includeFontPadding = false
        t.letterSpacing = Skin.letterSpacing()
        return t
    }

    /** Like [text] in the heavy weight. */
    @JvmStatic
    fun bold(c: Context, s: CharSequence?, sp: Float, color: Int): TextView {
        val t = text(c, s, sp, color)
        t.typeface = font(c, true)
        return t
    }

    /** Makes a label single-line with a trailing ellipsis. */
    @JvmStatic
    fun oneLine(t: TextView): TextView {
        t.setSingleLine(true)
        t.ellipsize = TextUtils.TruncateAt.END
        return t
    }

    /**
     * Keeps a single-line label inside its view: whenever the view's size or text changes, the text
     * shrinks (never below half of the size it had when this was called) until it fits the width
     * and, with `fitHeight`, the height. Only for views whose height doesn't wrap the text
     * when `fitHeight` is set. Call after the label's final text size is set.
     */
    @JvmStatic
    fun fitText(t: TextView, fitHeight: Boolean): TextView {
        val base = t.textSize
        val fit = Runnable {
            val w = (t.width - t.totalPaddingLeft - t.totalPaddingRight).toFloat()
            val h = (t.height - t.totalPaddingTop - t.totalPaddingBottom).toFloat()
            if (w <= 0 || (fitHeight && h <= 0)) return@Runnable
            val p = Paint(t.paint)
            p.textSize = base
            var scale = 1f
            val textW = p.measureText(t.text.toString())
            if (textW > w) scale = w / textW
            if (fitHeight) {
                val fm = p.fontMetrics
                val textH = fm.descent - fm.ascent
                if (textH > h) scale = min(scale, h / textH)
            }
            val size = if (scale >= 1f) base else max(base * 0.5f, base * scale * 0.97f)
            if (abs(size - t.textSize) > 0.5f) t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
        t.addOnLayoutChangeListener { v, l, top, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - top != oldB - oldT) v.post(fit)
        }
        t.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                t.post(fit)
            }
        })
        return t
    }

    // ---- colour and shapes ------------------------------------------------------------------------

    /** CLOAK engaged: cells draw as coloured outlines on black with orange lettering. */
    @JvmField
    var cloak = false

    const val CLOAK_INK = 0xFFFF9C00.toInt()

    /** Lettering colour on cells: black on console colour, orange while cloaked; light on faction plates. */
    @JvmStatic
    fun ink(): Int = Skin.ink()

    /**
     * A cell background. Command draws flat console colour with the given corner style; other
     * factions delegate to [Skin.cell]. `Color.WHITE` is the "mask only" colour used for
     * ripple masks and is never skinned or cloaked.
     *
     * @param kind one of [SQUARE], [PILL], [LEFT], [RIGHT]
     */
    @JvmStatic
    fun shape(c: Context, color: Int, kind: Int, radiusDp: Float): Drawable {
        if (!Skin.command() && color != Color.WHITE) return Skin.cell(c, color, kind, radiusDp)
        if (Skin.era() && color != Color.WHITE) return EraDraw.cell(c, color, kind)
        val g = GradientDrawable()
        if (cloak && color != Color.WHITE && Color.alpha(color) > 0x80) {
            g.setColor(Color.BLACK)
            g.setStroke(max(1, dp(c, 1.5f)), alpha(color, 0.75f))
        } else {
            g.setColor(color)
        }
        val r = dp(c, radiusDp).toFloat()
        when (kind) {
            PILL -> g.cornerRadius = dp(c, PILL_RADIUS_DP).toFloat()
            LEFT -> g.cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            RIGHT -> g.cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            else -> g.cornerRadius = r
        }
        return g
    }

    /** Wraps a filled background in a white ripple. */
    @JvmStatic
    fun touch(content: Drawable?): Drawable = RippleDrawable(ColorStateList.valueOf(RIPPLE_ON_FILL), content, null)

    /** Ripple for transparent touch targets (needs an explicit mask or the ripple is invisible). */
    @JvmStatic
    fun touchArea(c: Context, radiusDp: Float): Drawable =
        RippleDrawable(ColorStateList.valueOf(RIPPLE_ON_CLEAR), null, shape(c, Color.WHITE, SQUARE, radiusDp))

    /** A rounded rectangle with a fill and a stroke (fields, dialogs, empty states). */
    @JvmStatic
    fun outline(c: Context, fill: Int, stroke: Int, radiusDp: Float, strokeDp: Float): Drawable {
        if (Skin.era25()) return EraDraw.panel(c, fill, stroke, strokeDp)
        var radius = radiusDp
        var strokeW = strokeDp
        if (Skin.era23()) {
            // square console trays with a heavier edge
            radius = min(radius, Skin.ERA23_RADIUS)
            strokeW = max(strokeW, 2f)
        }
        val g = GradientDrawable()
        g.setColor(fill)
        g.cornerRadius = dp(c, radius).toFloat()
        g.setStroke(dp(c, strokeW), stroke)
        return g
    }

    /** Classic console button: a solid colour block with the label bottom-right. */
    @JvmStatic
    fun button(c: Context, label: String, color: Int, kind: Int, l: View.OnClickListener?): TextView {
        val t = bold(c, label.uppercase(Locale.getDefault()), 16f, ink())
        t.gravity = Gravity.BOTTOM or Gravity.END
        if (Skin.imperial()) {
            t.setPadding(dp(c, 12f), dp(c, 6f), dp(c, 20f), dp(c, 10f)) // clear of the octagon's corner cuts
        } else if (Skin.era23()) {
            // console keys letter in the middle, clear of the indicator lamp on the left
            t.gravity = Gravity.CENTER
            t.setPadding(dp(c, 18f), dp(c, 6f), dp(c, 10f), dp(c, 6f))
        } else if (Skin.era25()) {
            t.setPadding(dp(c, 14f), dp(c, 6f), dp(c, 14f), dp(c, 7f)) // clear of the cut corners
        } else {
            t.setPadding(dp(c, 12f), dp(c, 6f), dp(c, 14f), dp(c, 7f))
        }
        t.background = touch(shape(c, color, kind, 20f))
        t.minHeight = dp(c, MIN_TOUCH_DP)
        t.setSingleLine(true)
        t.ellipsize = TextUtils.TruncateAt.END
        if (l != null) t.setOnClickListener(feedback(l))
        return t
    }

    // ---- feedback ---------------------------------------------------------------------------------

    /** Long-press feedback: haptic (if enabled) and the long-press chirp. */
    @JvmStatic
    fun longPress(v: View) {
        if (TrekPrefs.get(v.context).haptics()) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        Sfx.play(v.context, Sfx.LONG_PRESS)
    }

    /** Click wrapper using the quieter tap, for frequently repeated controls. */
    @JvmStatic
    fun feedbackSoft(l: View.OnClickListener): View.OnClickListener = View.OnClickListener { v ->
        if (TrekPrefs.get(v.context).haptics()) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        Sfx.play(v.context, Sfx.TAP_SOFT)
        l.onClick(v)
    }

    /** Wraps a click listener with haptic + optional chirp. */
    @JvmStatic
    fun feedback(l: View.OnClickListener): View.OnClickListener = View.OnClickListener { v ->
        tap(v)
        l.onClick(v)
    }

    /** Tap feedback: haptic (if enabled) and the tap chirp. */
    @JvmStatic
    fun tap(v: View) {
        if (TrekPrefs.get(v.context).haptics()) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        Sfx.play(v.context, Sfx.TAP)
    }

    /** True when the user allows animations and the system animator scale is not zero. */
    @JvmStatic
    fun animate(c: Context): Boolean = TrekPrefs.get(c).animations() && Compat.animatorsEnabled(c)

    // ---- colour maths (pure) ----------------------------------------------------------------------

    /** Replaces a colour's alpha with `a` (0..1). */
    @JvmStatic
    fun alpha(color: Int, a: Float): Int = (color and 0x00FFFFFF) or (Math.round(a * 255) shl 24)

    /** Saturated, bright version of a palette colour (for the VIVID icon options). */
    @JvmStatic
    fun vivid(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        // more saturation but full brightness, so blues/violets don't sink into the black background
        hsv[1] = max(hsv[1], min(0.85f, hsv[1] * 1.5f + 0.1f))
        hsv[2] = 1f
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    /** Scales a colour's RGB channels by `f`; the result is opaque. */
    @JvmStatic
    fun darken(color: Int, f: Float): Int =
        0xFF000000.toInt() or
            (Math.round(((color shr 16) and 0xFF) * f) shl 16) or
            (Math.round(((color shr 8) and 0xFF) * f) shl 8) or
            Math.round((color and 0xFF) * f)

    /** A label in upper case when the "upper-case labels" option is on; null becomes "". */
    @JvmStatic
    fun up(c: Context, s: String?): String {
        if (s == null) return ""
        return if (TrekPrefs.get(c).upperLabels()) s.uppercase(Locale.getDefault()) else s
    }
}
