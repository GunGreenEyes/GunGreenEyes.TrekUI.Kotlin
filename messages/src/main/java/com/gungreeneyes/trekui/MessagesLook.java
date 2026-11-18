package com.gungreeneyes.trekui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * The Messages app's window onto :core's look (Skin, Palette, Ui, ConsoleFrame, ConsoleDialog). Those
 * helpers are package-private in {@code com.gungreeneyes.trekui}; this public facade lives in the same
 * package (inside the :messages module, which is a separate APK, so no class is shared with the launcher)
 * and exposes exactly what the Messages screens need, so the app draws in whichever interface and
 * sub-theme is active without copying any drawing code.
 *
 * <p>Call {@link #load(Context)} before building a screen: it re-reads the interface (the launcher's
 * choice while following it, see {@link TrekPrefs#setThemeOverride}) and refreshes the palette.
 */
public final class MessagesLook {
    /** Corner styles for {@link #cell}: all corners, pill, left side round, right side round. */
    public static final int SQUARE = Ui.SQUARE, PILL = Ui.PILL, LEFT = Ui.LEFT, RIGHT = Ui.RIGHT;
    /** Spacing scale (dp), the same as every launcher screen. */
    public static final int XS = Ui.XS, GUTTER = Ui.GUTTER, S = Ui.S, M = Ui.M, L = Ui.L, XL = Ui.XL;
    /** Minimum touch target (dp). */
    public static final float MIN_TOUCH_DP = Ui.MIN_TOUCH_DP;

    /** Option pick callback for {@link #options} and {@link #message}. */
    public interface Pick { void pick(int which); }
    /** Text callback for {@link #prompt}. */
    public interface Text { void text(String value); }

    private static Palette palette = Palette.ALL[0];

    private MessagesLook() {}

    /** Re-reads the active interface and palette; call before (re)building a screen. */
    public static void load(Context c) {
        palette = TrekPrefs.get(c).palette();
    }

    // ---- colours ------------------------------------------------------------------------------

    /** Accent {@code i} of the active palette (wraps around). */
    public static int accent(int i) { return palette.c(i); }
    public static int textHi() { return palette.textHi; }
    public static int textLo() { return palette.textLo; }
    public static int alert() { return palette.alert; }
    public static int panel() { return palette.panel; }
    /** Window background of the active interface. */
    public static int bg() { return Skin.bg(); }
    /** Raised surface colour (trays, fields). */
    public static int surface() { return Skin.surface(); }
    /** Hairline / grid colour. */
    public static int grid() { return Skin.grid(); }
    /** Lettering colour on a filled cell. */
    public static int ink() { return Skin.ink(); }
    /** {@code color} with alpha {@code a} (0..1). */
    public static int alpha(int color, float a) { return Ui.alpha(color, a); }

    // ---- sizes and type -----------------------------------------------------------------------

    /** Design dp to pixels including the window zoom. */
    public static int dp(Context c, float v) { return Ui.dp(c, v); }
    /** Design sp to pixels for Canvas text (interface typeface scale and zoom applied). */
    public static float px(Context c, float sp) { return Ui.px(c, sp); }
    public static void setTextSp(TextView t, float sp) { Ui.setTextSp(t, sp); }
    public static Typeface font(Context c, boolean heavy) { return Ui.font(c, heavy); }
    /** Letter spacing of the interface typeface. */
    public static float letterSpacing() { return Skin.letterSpacing(); }

    public static TextView text(Context c, CharSequence s, float sp, int color) { return Ui.text(c, s, sp, color); }
    public static TextView bold(Context c, CharSequence s, float sp, int color) { return Ui.bold(c, s, sp, color); }
    public static TextView oneLine(TextView t) { return Ui.oneLine(t); }
    /** A label in upper case when the user's "upper-case labels" option is on. */
    public static String up(Context c, String s) { return Ui.up(c, s); }

    // ---- shapes and controls ------------------------------------------------------------------

    /** A console button in {@code color} (corner style {@code kind}) with tap feedback. */
    public static TextView button(Context c, String label, int color, int kind, View.OnClickListener l) {
        return Ui.button(c, label, color, kind, l);
    }

    /** A filled cell in the interface's geometry. */
    public static Drawable cell(Context c, int color, int kind, float radiusDp) { return Ui.shape(c, color, kind, radiusDp); }
    /** A filled cell wrapped in a ripple (for tappable rows). */
    public static Drawable touchCell(Context c, int color, int kind, float radiusDp) {
        return Ui.touch(Ui.shape(c, color, kind, radiusDp));
    }
    /** Ripple for a transparent tappable area. */
    public static Drawable touchArea(Context c, float radiusDp) { return Ui.touchArea(c, radiusDp); }
    /** An outlined tray (fields, incoming bubbles, empty states). */
    public static Drawable outline(Context c, int fill, int stroke, float radiusDp, float strokeDp) {
        return Ui.outline(c, fill, stroke, radiusDp, strokeDp);
    }

    /** Wraps a listener with the interface's haptic + sound tap feedback. */
    public static View.OnClickListener feedback(View.OnClickListener l) { return Ui.feedback(l); }
    /** Quieter tap feedback for repeated controls (list rows). */
    public static View.OnClickListener feedbackSoft(View.OnClickListener l) { return Ui.feedbackSoft(l); }
    /** Plays tap feedback on {@code v}. */
    public static void tap(View v) { Ui.tap(v); }
    /** Long-press feedback on {@code v}. */
    public static void longPress(View v) { Ui.longPress(v); }

    // ---- the console frame ----------------------------------------------------------------------

    /**
     * The interface's console frame (sidebar + elbows + bars) around {@code content}, coloured from the
     * active palette like the launcher's CONFIG screen.
     *
     * @param sidebarDp sidebar width in design dp
     */
    public static ViewGroup frame(Context c, View title, View footer, View sidebar, View content, float sidebarDp) {
        ConsoleFrame f = new ConsoleFrame(c);
        f.sidebarW = Ui.dp(c, sidebarDp);
        f.colorTop = palette.c(3);
        f.colorBot = palette.c(1);
        f.topBlocks = new int[]{palette.c(0)};
        f.botBlocks = new int[]{palette.c(2), palette.c(4)};
        if (title != null) f.setTitle(title);
        if (footer != null) f.setFooter(footer);
        if (sidebar != null) f.setSidebar(sidebar);
        f.setContent(content);
        return f;
    }

    // ---- dialogs ------------------------------------------------------------------------------

    /** A list of options in the interface's dialog style. */
    public static Dialog options(Context c, String title, String[] items, Pick pick) {
        return ConsoleDialog.options(c, title, items, pick::pick);
    }

    /** A message with buttons (the first in the primary colour); {@code pick} may be null. */
    public static Dialog message(Context c, String title, String msg, String[] buttons, Pick pick) {
        return ConsoleDialog.message(c, title, msg, buttons, pick == null ? null : pick::pick);
    }

    /** A one-line text prompt. */
    public static Dialog prompt(Context c, String title, String initial, String hint, Text cb) {
        return ConsoleDialog.prompt(c, title, initial, hint, cb::text);
    }

    // ---- the app's own interface choice (used only while the launcher is not installed) --------

    /** Names of the offered interfaces, in picker order (see {@link #interfaceAt}). */
    public static String[] interfaceNames() { return Skin.interfaceNames(); }

    /** The interface id at picker position {@code which} of {@link #interfaceNames}. */
    public static int interfaceAt(int which) { return Skin.INTERFACES[which]; }

    /** Display names of the sub-themes {@link SubThemes} offers for {@code faction}. */
    public static String[] subThemeNames(int faction) {
        int n = SubThemes.count(faction);
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = SubThemes.name(faction, i);
        return out;
    }

    /** True if {@code faction} offers a choice of sub-themes ({@link SubThemes#selectable}). */
    public static boolean hasSubThemes(int faction) {
        return SubThemes.selectable(faction);
    }

    /** Swatch colour of sub-theme {@code index} for {@code faction} (its primary accent). */
    public static int subThemeSwatch(int faction, int index) {
        int[] a = SubThemes.accents(faction, index);
        return a.length == 0 ? Color.GRAY : a[0];
    }
}
