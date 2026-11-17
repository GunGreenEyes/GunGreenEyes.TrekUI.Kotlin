package com.gungreeneyes.trekui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

/**
 * Shared look-and-feel toolkit used by every screen: unit conversion, typefaces, text views, cell
 * shapes, buttons, touch feedback (haptics + sound) and small colour helpers.
 *
 * <h2>Responsive sizing</h2>
 * All fixed sizes in the interface are written in dp/sp against a ~360-412dp phone. {@link #dp} and
 * the text helpers multiply them by a per-window <em>zoom</em> derived from
 * {@link ScreenMetrics#uiScale}:
 * <ul>
 *   <li>phones (shorter side &lt; 600dp, either orientation): layout zoom 1, so phone layouts are
 *       exactly as designed; text shrinks with {@code uiScale} on very small phones (&lt; 360dp);</li>
 *   <li>tablets and open foldables (shorter side &ge; 600dp): layout and text grow by half of
 *       {@code uiScale - 1} (about 1.3x at the 1.6 cap), so bars, elbows and text keep their
 *       proportions instead of looking like a stretched phone.</li>
 * </ul>
 * The rules are the pure functions {@link #layoutZoomFor} and {@link #textZoomFor} (unit-tested);
 * the current values are cached per configuration so {@code dp()} stays cheap inside {@code onDraw}.
 * Use {@link #rawDp} for sizes that must stay physical (hairlines, touch slop).
 */
final class Ui {
    /** Corner styles for {@link #shape}: all corners, fully round, left side round, right side round. */
    static final int SQUARE = 0, PILL = 1, LEFT = 2, RIGHT = 3;

    /**
     * Spacing scale (dp). Every padding, margin and gutter in the interface uses one of these, so
     * gaps line up from screen to screen instead of drifting between 3, 5, 7, 9… dp.
     *  HAIR 2 · XS 4 · GUTTER 6 (space between neighbouring cells) · S 8 · M 12 · L 16 · XL 24
     */
    static final int HAIR = 2, XS = 4, GUTTER = 6, S = 8, M = 12, L = 16, XL = 24;

    /** Shorter screen side (dp) from which a window counts as a tablet / open foldable. */
    static final float LARGE_SCREEN_SHORT_SIDE_DP = 600f;
    /** Share of {@code uiScale - 1} applied as zoom on large screens (0.5 → 1.6 cap becomes 1.3x). */
    static final float LARGE_SCREEN_ZOOM_DAMPING = 0.5f;
    /** Corner radius large enough to turn any cell into a pill. */
    static final float PILL_RADIUS_DP = 999f;
    /** Minimum touch target height for buttons (Material guideline). */
    static final float MIN_TOUCH_DP = 48f;

    private static final int RIPPLE_ON_FILL = 0x70FFFFFF;
    private static final int RIPPLE_ON_CLEAR = 0x50FFFFFF;
    private static final String ANTONIO = "fonts/Antonio.ttf";

    private static Typeface regular, bold;
    private static final Typeface[] factionRegular = new Typeface[Skin.NAMES.length];
    private static final Typeface[] factionBold = new Typeface[Skin.NAMES.length];
    private static int cachedLettering = -1;

    // zoom cache, keyed on the configuration it was computed for
    private static int zoomW = -1, zoomH = -1, zoomDpi = -1;
    private static float layoutZoom = 1f, textZoom = 1f;

    private Ui() {}

    // ---- responsive units -------------------------------------------------------------------------

    /**
     * Layout zoom for a window: 1 on phones, {@code 1 + (uiScale - 1) * damping} on tablets.
     *
     * @param uiScale      {@link ScreenMetrics#uiScale} for the window
     * @param shortSideDp  the window's shorter side in dp
     */
    static float layoutZoomFor(float uiScale, float shortSideDp) {
        if (shortSideDp < LARGE_SCREEN_SHORT_SIDE_DP) return 1f;
        return Math.max(1f, 1f + (uiScale - 1f) * LARGE_SCREEN_ZOOM_DAMPING);
    }

    /**
     * Text zoom for a window: the layout zoom, additionally shrunk by {@code uiScale} on phones narrower
     * than the 360dp reference so long labels still fit (never below {@link ScreenMetrics#MIN_SCALE}).
     */
    static float textZoomFor(float uiScale, float shortSideDp) {
        float zoom = layoutZoomFor(uiScale, shortSideDp);
        if (shortSideDp < LARGE_SCREEN_SHORT_SIDE_DP && uiScale < 1f) {
            zoom *= Math.max(ScreenMetrics.MIN_SCALE, uiScale);
        }
        return zoom;
    }

    /** Refreshes the cached zooms if the context's configuration (size or density) changed. */
    private static void updateZoom(Context c) {
        Configuration cfg = c.getResources().getConfiguration();
        if (cfg.screenWidthDp == zoomW && cfg.screenHeightDp == zoomH && cfg.densityDpi == zoomDpi) return;
        ScreenMetrics m = ScreenMetrics.of(c);
        float shortSide = Math.min(m.widthDp, m.heightDp);
        layoutZoom = layoutZoomFor(m.uiScale, shortSide);
        textZoom = textZoomFor(m.uiScale, shortSide);
        zoomW = cfg.screenWidthDp;
        zoomH = cfg.screenHeightDp;
        zoomDpi = cfg.densityDpi;
    }

    /** Current layout zoom (see class docs). */
    static float zoom(Context c) {
        updateZoom(c);
        return layoutZoom;
    }

    /** Current text zoom (see class docs). */
    static float textZoom(Context c) {
        updateZoom(c);
        return textZoom;
    }

    /** Pixels per zoomed dp: use instead of {@code DisplayMetrics.density} in custom drawing code. */
    static float density(Context c) {
        return c.getResources().getDisplayMetrics().density * zoom(c);
    }

    /** Converts a design dp value to whole pixels, including the window's layout zoom. */
    static int dp(Context c, float v) {
        return Math.round(rawDpF(c, v) * zoom(c));
    }

    /** Converts dp to whole pixels without zoom, for sizes that must stay physical. */
    static int rawDp(Context c, float v) {
        return Math.round(rawDpF(c, v));
    }

    private static float rawDpF(Context c, float v) {
        DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm);
    }

    /**
     * Sets a TextView's size from a design sp value, applying the faction's typeface scale and the
     * window's text zoom. Use this instead of {@code TextView.setTextSize(float)}.
     */
    static void setTextSp(TextView t, float sp) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * Skin.textScale() * textZoom(t.getContext()));
    }

    /** Text size in pixels for text drawn directly on a Canvas (faction typeface scale and zoom applied). */
    static float px(Context c, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp * Skin.textScale() * textZoom(c),
                c.getResources().getDisplayMetrics());
    }

    // ---- type -------------------------------------------------------------------------------------

    /**
     * The interface typeface. Command uses Antonio (variable weight 500/700); every other faction
     * loads its own pair from assets ({@link Skin#fontRegular()} / {@link Skin#fontHeavy()}), cached per
     * faction. The Imperial pair is re-read when the lettering option changes.
     */
    static Typeface font(Context c, boolean heavy) {
        int f = Skin.faction;
        if (cachedLettering != Skin.lettering) {
            cachedLettering = Skin.lettering;
            factionRegular[Skin.IMPERIAL] = null;
            factionBold[Skin.IMPERIAL] = null;
        }
        if (f != Skin.COMMAND) {
            if (factionRegular[f] == null && Skin.fontRegular() == null) {
                // an Antonio variant at the interface's own weights (25th century: light and regular)
                factionRegular[f] = antonio(c, Skin.antonioWeight(false), false);
                factionBold[f] = antonio(c, Skin.antonioWeight(true), true);
            }
            if (factionRegular[f] == null) {
                try {
                    factionRegular[f] = Typeface.createFromAsset(c.getAssets(), Skin.fontRegular());
                    factionBold[f] = Typeface.createFromAsset(c.getAssets(), Skin.fontHeavy());
                } catch (Throwable t) {
                    factionRegular[f] = Typeface.create("sans-serif", Typeface.NORMAL);
                    factionBold[f] = Typeface.create("sans-serif", Typeface.BOLD);
                }
            }
            return heavy ? factionBold[f] : factionRegular[f];
        }
        if (regular == null) {
            try {
                regular = new Typeface.Builder(c.getAssets(), ANTONIO).setFontVariationSettings("'wght' 500").build();
                bold = new Typeface.Builder(c.getAssets(), ANTONIO).setFontVariationSettings("'wght' 700").build();
            } catch (Throwable t) {
                regular = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
                bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
            }
            if (regular == null) regular = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            if (bold == null) bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
        }
        return heavy ? bold : regular;
    }

    /** Antonio at a variable-font weight, falling back to condensed sans-serif. */
    private static Typeface antonio(Context c, int weight, boolean heavy) {
        Typeface t = null;
        try {
            t = new Typeface.Builder(c.getAssets(), ANTONIO).setFontVariationSettings("'wght' " + weight).build();
        } catch (Throwable ignored) {
            // fall through to the system face
        }
        return t != null ? t : Typeface.create("sans-serif-condensed", heavy ? Typeface.BOLD : Typeface.NORMAL);
    }

    /** A plain label in the interface typeface; {@code sp} is the design size before zoom. */
    static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        setTextSp(t, sp);
        t.setTextColor(color);
        t.setTypeface(font(c, false));
        t.setIncludeFontPadding(false);
        t.setLetterSpacing(Skin.letterSpacing());
        return t;
    }

    /** Like {@link #text} in the heavy weight. */
    static TextView bold(Context c, CharSequence s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(font(c, true));
        return t;
    }

    /** Makes a label single-line with a trailing ellipsis. */
    static TextView oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    // ---- colour and shapes ------------------------------------------------------------------------

    /** CLOAK engaged: cells draw as coloured outlines on black with orange lettering. */
    static boolean cloak;
    static final int CLOAK_INK = 0xFFFF9C00;

    /** Lettering colour on cells: black on console colour, orange while cloaked; light on faction plates. */
    static int ink() { return Skin.ink(); }

    /**
     * A cell background. Command draws flat console colour with the given corner style; other
     * factions delegate to {@link Skin#cell}. {@code Color.WHITE} is the "mask only" colour used for
     * ripple masks and is never skinned or cloaked.
     *
     * @param kind one of {@link #SQUARE}, {@link #PILL}, {@link #LEFT}, {@link #RIGHT}
     */
    static Drawable shape(Context c, int color, int kind, float radiusDp) {
        if (!Skin.command() && color != Color.WHITE) return Skin.cell(c, color, kind, radiusDp);
        if (Skin.era() && color != Color.WHITE) return EraDraw.cell(c, color, kind);
        GradientDrawable g = new GradientDrawable();
        if (cloak && color != Color.WHITE && Color.alpha(color) > 0x80) {
            g.setColor(Color.BLACK);
            g.setStroke(Math.max(1, dp(c, 1.5f)), alpha(color, 0.75f));
        } else {
            g.setColor(color);
        }
        float r = dp(c, radiusDp);
        switch (kind) {
            case PILL: g.setCornerRadius(dp(c, PILL_RADIUS_DP)); break;
            case LEFT: g.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r}); break;
            case RIGHT: g.setCornerRadii(new float[]{0, 0, r, r, r, r, 0, 0}); break;
            default: g.setCornerRadius(r);
        }
        return g;
    }

    /** Wraps a filled background in a white ripple. */
    static Drawable touch(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE_ON_FILL), content, null);
    }

    /** Ripple for transparent touch targets (needs an explicit mask or the ripple is invisible). */
    static Drawable touchArea(Context c, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE_ON_CLEAR), null, shape(c, Color.WHITE, SQUARE, radiusDp));
    }

    /** A rounded rectangle with a fill and a stroke (fields, dialogs, empty states). */
    static Drawable outline(Context c, int fill, int stroke, float radiusDp, float strokeDp) {
        if (Skin.era25()) return EraDraw.panel(c, fill, stroke, strokeDp);
        if (Skin.era23()) {
            // square console trays with a heavier edge
            radiusDp = Math.min(radiusDp, Skin.ERA23_RADIUS);
            strokeDp = Math.max(strokeDp, 2f);
        }
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        g.setStroke(dp(c, strokeDp), stroke);
        return g;
    }

    /**
     * Classic console button: a solid colour block with the label bottom-right. Pipe interfaces centre the
     * label and lift its colour on dark/translucent plates (except Ember Lounge, which letters in gold).
     */
    static TextView button(Context c, String label, int color, int kind, View.OnClickListener l) {
        TextView t = bold(c, label.toUpperCase(Locale.getDefault()), 16, ink());
        t.setGravity(Gravity.BOTTOM | Gravity.END);
        if (Skin.pipe()) {
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        } else if (Skin.imperial()) {
            t.setPadding(dp(c, 12), dp(c, 6), dp(c, 20), dp(c, 10)); // clear of the octagon's corner cuts
        } else if (Skin.era23()) {
            // console keys letter in the middle, clear of the indicator lamp on the left
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(c, 18), dp(c, 6), dp(c, 10), dp(c, 6));
        } else if (Skin.era25()) {
            t.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 7)); // clear of the cut corners
        } else {
            t.setPadding(dp(c, 12), dp(c, 6), dp(c, 14), dp(c, 7));
        }
        t.setBackground(touch(shape(c, color, kind, 20)));
        if (Skin.pipe() && color != Color.WHITE && (Color.alpha(color) <= 0x80 || PipeDraw.dim(color)) && !Skin.mLounge()) {
            t.setTextColor(PipeDraw.lifted(color));
        }
        t.setMinHeight(dp(c, MIN_TOUCH_DP));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (l != null) t.setOnClickListener(feedback(l));
        return t;
    }

    // ---- feedback ---------------------------------------------------------------------------------

    /** Long-press feedback: haptic (if enabled) and the long-press chirp. */
    static void longPress(View v) {
        if (TrekPrefs.get(v.getContext()).haptics()) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        Sfx.play(v.getContext(), Sfx.LONG_PRESS);
    }

    /** Click wrapper using the quieter tap, for frequently repeated controls. */
    static View.OnClickListener feedbackSoft(final View.OnClickListener l) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (TrekPrefs.get(v.getContext()).haptics()) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                Sfx.play(v.getContext(), Sfx.TAP_SOFT);
                l.onClick(v);
            }
        };
    }

    /** Wraps a click listener with haptic + optional chirp. */
    static View.OnClickListener feedback(final View.OnClickListener l) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                tap(v);
                l.onClick(v);
            }
        };
    }

    /** Tap feedback: haptic (if enabled) and the tap chirp. */
    static void tap(View v) {
        if (TrekPrefs.get(v.getContext()).haptics()) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        Sfx.play(v.getContext(), Sfx.TAP);
    }

    /** True when the user allows animations and the system animator scale is not zero. */
    static boolean animate(Context c) {
        return TrekPrefs.get(c).animations() && ValueAnimator.areAnimatorsEnabled();
    }

    // ---- colour maths (pure) ----------------------------------------------------------------------

    /** Replaces a colour's alpha with {@code a} (0..1). */
    static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(a * 255) << 24);
    }

    /** Saturated, bright version of a palette colour (for the VIVID icon options). */
    static int vivid(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        // more saturation but full brightness, so blues/violets don't sink into the black background
        hsv[1] = Math.max(hsv[1], Math.min(0.85f, hsv[1] * 1.5f + 0.1f));
        hsv[2] = 1f;
        return Color.HSVToColor(Color.alpha(color), hsv);
    }

    /** Scales a colour's RGB channels by {@code f}; the result is opaque. */
    static int darken(int color, float f) {
        return 0xFF000000
                | (Math.round(((color >> 16) & 0xFF) * f) << 16)
                | (Math.round(((color >> 8) & 0xFF) * f) << 8)
                | Math.round((color & 0xFF) * f);
    }

    /** A label in upper case when the "upper-case labels" option is on; null becomes "". */
    static String up(Context c, String s) {
        if (s == null) return "";
        return TrekPrefs.get(c).upperLabels() ? s.toUpperCase(Locale.getDefault()) : s;
    }
}
