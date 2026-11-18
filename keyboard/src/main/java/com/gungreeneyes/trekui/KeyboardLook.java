package com.gungreeneyes.trekui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * The keyboard's window onto :core's look and sound (Skin, Palette, Ui, ConsoleFrame, ConsoleDialog, Sfx).
 * Those helpers are package-private in {@code com.gungreeneyes.trekui}; this public facade lives in the same
 * package (inside the :keyboard module, a separate APK, so no class is shared with the launcher) and exposes
 * exactly what the keyboard view and its settings screen need, so keys are drawn in whichever interface and
 * sub-theme is active without copying any drawing code. Mirrors {@code MessagesLook} in :messages.
 *
 * <p>Call {@link #load(Context)} before drawing or building a screen: it re-reads the interface (the
 * launcher's choice while following it, see {@link TrekPrefs#setThemeOverride}) and refreshes the palette.
 */
public final class KeyboardLook {
    /** Corner styles for {@link #cell}: all corners, pill, left side round, right side round. */
    public static final int SQUARE = Ui.SQUARE, PILL = Ui.PILL, LEFT = Ui.LEFT, RIGHT = Ui.RIGHT;

    /** Default key-sound volume (0..100) for a fresh install. */
    public static final int DEFAULT_VOLUME = 45;
    private static final String KEY_SOUND_ON = "sfx", KEY_VOLUME = "sfxVol";

    /** Option pick callback for {@link #options}. */
    public interface Pick { void pick(int which); }

    private static Palette palette = Palette.ALL[0];

    private KeyboardLook() {}

    /** Re-reads the active interface and palette; call before (re)drawing. */
    public static void load(Context c) {
        palette = TrekPrefs.get(c).palette();
    }

    // ---- colours ----------------------------------------------------------------------------------

    /** Accent {@code i} of the active palette (wraps around). */
    public static int accent(int i) { return palette.c(i); }
    public static int textHi() { return palette.textHi; }
    public static int textLo() { return palette.textLo; }
    public static int alert() { return palette.alert; }
    /** Window background of the active interface. */
    public static int bg() { return Skin.bg(); }
    /** Raised surface colour (trays, the suggestion strip). */
    public static int surface() { return Skin.surface(); }
    /** Hairline / grid colour. */
    public static int grid() { return Skin.grid(); }
    /** Lettering colour on a filled key. */
    public static int ink() { return Skin.ink(); }
    /** True for the Command family (flat solid keys with dark lettering). */
    public static boolean command() { return Skin.command(); }
    /** {@code color} with alpha {@code a} (0..1). */
    public static int alpha(int color, float a) { return Ui.alpha(color, a); }
    /** {@code color} darkened by {@code f} (0..1). */
    public static int darken(int color, float f) { return Ui.darken(color, f); }
    /** {@code a} mixed toward {@code b} by {@code t}. */
    public static int mix(int a, int b, float t) { return Skin.mix(a, b, t); }

    // ---- type and sizes ---------------------------------------------------------------------------

    public static Typeface font(Context c, boolean heavy) { return Ui.font(c, heavy); }
    /** The interface typeface's size scale (some faces run small). */
    public static float textScale() { return Skin.textScale(); }
    /** Design dp to pixels including the window zoom. */
    public static int dp(Context c, float v) { return Ui.dp(c, v); }
    public static void setTextSp(TextView t, float sp) { Ui.setTextSp(t, sp); }
    public static TextView text(Context c, CharSequence s, float sp, int color) { return Ui.text(c, s, sp, color); }
    public static TextView bold(Context c, CharSequence s, float sp, int color) { return Ui.bold(c, s, sp, color); }

    // ---- shapes and controls ----------------------------------------------------------------------

    /** A key / control background in the interface's geometry (Command, Imperial, Collective, eras). */
    public static Drawable cell(Context c, int color, int kind, float radiusDp) { return Ui.shape(c, color, kind, radiusDp); }
    /** A filled cell wrapped in a ripple. */
    public static Drawable touchCell(Context c, int color, int kind, float radiusDp) {
        return Ui.touch(Ui.shape(c, color, kind, radiusDp));
    }
    /** A rule (section underline) in the interface's style. */
    public static Drawable rule(Context c, int color) { return Skin.rule(c, color); }
    /** A console button with tap feedback. */
    public static TextView button(Context c, String label, int color, int kind, View.OnClickListener l) {
        return Ui.button(c, label, color, kind, l);
    }

    /** The interface's console frame (sidebar + elbows + bars) around {@code content}, like CONFIG. */
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

    /** A list of options in the interface's dialog style. */
    public static Dialog options(Context c, String title, String[] items, Pick pick) {
        return ConsoleDialog.options(c, title, items, pick::pick);
    }

    // ---- the app's own interface choice (used only while the launcher is not installed) -----------

    /** Names of the offered interfaces, in picker order (see {@link #interfaceAt}). */
    public static String[] interfaceNames() { return Skin.interfaceNames(); }

    /** The interface id at picker position {@code which} of {@link #interfaceNames}. */
    public static int interfaceAt(int which) { return Skin.INTERFACES[which]; }

    /** Picker position of interface {@code faction} in {@link #interfaceNames}. */
    public static int interfaceIndex(int faction) { return Skin.interfaceIndex(faction); }

    /** Display names of the sub-themes (colour themes) offered for {@code faction}. */
    public static String[] subThemeNames(int faction) {
        Palette[] themes = Palette.themesFor(faction);
        String[] out = new String[themes.length];
        for (int i = 0; i < themes.length; i++) out[i] = themes[i].name;
        return out;
    }

    /** True if {@code faction} offers a choice of sub-themes its palette actually uses. */
    public static boolean hasSubThemes(int faction) {
        Palette[] themes = Palette.themesFor(faction);
        return themes.length > 1 && (Skin.commandFamily(faction) || themes != Palette.ALL);
    }

    // ---- sound --------------------------------------------------------------------------------------

    /**
     * First run: the keyboard's own sound set defaults to the synthesized clips (it never downloads the
     * sound library) and its own volume to {@link #DEFAULT_VOLUME}.
     */
    public static void ensureSoundDefaults(Context c) {
        TrekPrefs p = TrekPrefs.get(c);
        if (p.s(TrekPrefs.KEY_SOUND_SET, null) == null) p.put(TrekPrefs.KEY_SOUND_SET, Sfx.SET_SYNTH);
        if (!p.sp.contains(KEY_VOLUME)) p.put(KEY_VOLUME, DEFAULT_VOLUME);
    }

    public static boolean soundOn(Context c) { return TrekPrefs.get(c).b(KEY_SOUND_ON, true); }
    public static void setSoundOn(Context c, boolean on) { TrekPrefs.get(c).put(KEY_SOUND_ON, on); }
    public static int volume(Context c) { return TrekPrefs.get(c).i(KEY_VOLUME, DEFAULT_VOLUME); }
    public static void setVolume(Context c, int v) { TrekPrefs.get(c).put(KEY_VOLUME, Math.max(0, Math.min(100, v))); }

    /** True when Android's own "touch sounds" setting is on (key clicks follow it). */
    public static boolean systemTouchSounds(Context c) {
        try {
            return Settings.System.getInt(c.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Plays the key click (the interface's synthesized tap) if the keyboard's KEY SOUNDS and Android's touch
     * sounds are on; Sfx itself stays silent in Silent / Vibrate and during calls.
     */
    public static void click(Context c) {
        if (!soundOn(c) || volume(c) <= 0 || !systemTouchSounds(c)) return;
        Sfx.playEvent(c, Sfx.E_BUTTON, true);
    }

    /** Re-reads the sound set (after the interface changed). */
    public static void reloadSounds(Context c) { Sfx.reload(c); }

    /** A settings-screen toggle sound. */
    public static void toggleSound(Context c) { Sfx.play(c, Sfx.TOGGLE_ON); }
}
