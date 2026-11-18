package com.gungreeneyes.trekui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * The keyboard's window onto :core's look and sound (Skin, Palette, Ui, ConsoleFrame, ConsoleDialog, Sfx).
 * It lives in the same package as those helpers (inside the :keyboard module, a separate APK, so no class
 * is shared with the launcher) and exposes exactly what the keyboard view and its settings screen need, so
 * keys are drawn in whichever interface and sub-theme is active without copying any drawing code. Mirrors
 * `MessagesLook` in :messages.
 *
 * Call [load] before drawing or building a screen: it re-reads the interface (the launcher's choice while
 * following it, see [TrekPrefs.setThemeOverride]) and refreshes the palette.
 */
internal object KeyboardLook {
    /** Corner styles for [cell]: all corners, pill, left side round, right side round. */
    const val SQUARE = Ui.SQUARE
    const val PILL = Ui.PILL
    const val LEFT = Ui.LEFT
    const val RIGHT = Ui.RIGHT

    /** Default key-sound volume (0..100) for a fresh install. */
    const val DEFAULT_VOLUME = 45
    private const val KEY_SOUND_ON = "sfx"
    private const val KEY_VOLUME = "sfxVol"

    private var palette: Palette = Palette.ALL[0]

    /** Re-reads the active interface and palette; call before (re)drawing. */
    fun load(c: Context) {
        palette = TrekPrefs.get(c).palette()
    }

    // ---- colours ----------------------------------------------------------------------------------

    /** Accent [i] of the active palette (wraps around). */
    fun accent(i: Int): Int = palette.c(i)
    fun textHi(): Int = palette.textHi
    fun textLo(): Int = palette.textLo
    fun alert(): Int = palette.alert
    /** Window background of the active interface. */
    fun bg(): Int = Skin.bg()
    /** Raised surface colour (trays, the suggestion strip). */
    fun surface(): Int = Skin.surface()
    /** Hairline / grid colour. */
    fun grid(): Int = Skin.grid()
    /** Lettering colour on a filled key. */
    fun ink(): Int = Skin.ink()
    /** True for the Command family (flat solid keys with dark lettering). */
    fun command(): Boolean = Skin.command()
    /** [color] with alpha [a] (0..1). */
    fun alpha(color: Int, a: Float): Int = Ui.alpha(color, a)
    /** [color] darkened by [f] (0..1). */
    fun darken(color: Int, f: Float): Int = Ui.darken(color, f)
    /** [a] mixed toward [b] by [t]. */
    fun mix(a: Int, b: Int, t: Float): Int = Skin.mix(a, b, t)

    // ---- type and sizes ---------------------------------------------------------------------------

    fun font(c: Context, heavy: Boolean): Typeface? = Ui.font(c, heavy)
    /** The interface typeface's size scale (some faces run small). */
    fun textScale(): Float = Skin.textScale()
    /** Design dp to pixels including the window zoom. */
    fun dp(c: Context, v: Float): Int = Ui.dp(c, v)
    fun setTextSp(t: TextView, sp: Float) = Ui.setTextSp(t, sp)
    fun text(c: Context, s: CharSequence?, sp: Float, color: Int): TextView = Ui.text(c, s, sp, color)
    fun bold(c: Context, s: CharSequence?, sp: Float, color: Int): TextView = Ui.bold(c, s, sp, color)

    // ---- shapes and controls ----------------------------------------------------------------------

    /** A key / control background in the interface's geometry (Command, Imperial, Collective, eras). */
    fun cell(c: Context, color: Int, kind: Int, radiusDp: Float): Drawable = Ui.shape(c, color, kind, radiusDp)

    /** A filled cell wrapped in a ripple. */
    fun touchCell(c: Context, color: Int, kind: Int, radiusDp: Float): Drawable =
        Ui.touch(Ui.shape(c, color, kind, radiusDp))

    /** A rule (section underline) in the interface's style. */
    fun rule(c: Context, color: Int): Drawable = Skin.rule(c, color)

    /** A console button with tap feedback. */
    fun button(c: Context, label: String, color: Int, kind: Int, l: View.OnClickListener?): TextView =
        Ui.button(c, label, color, kind, l)

    /** The interface's console frame (sidebar + elbows + bars) around [content], like CONFIG. */
    fun frame(c: Context, title: View?, footer: View?, sidebar: View?, content: View, sidebarDp: Float): ViewGroup =
        ConsoleFrame(c).apply {
            sidebarW = Ui.dp(c, sidebarDp)
            colorTop = palette.c(3)
            colorBot = palette.c(1)
            topBlocks = intArrayOf(palette.c(0))
            botBlocks = intArrayOf(palette.c(2), palette.c(4))
            title?.let { setTitle(it) }
            footer?.let { setFooter(it) }
            sidebar?.let { setSidebar(it) }
            setContent(content)
        }

    /** A list of options in the interface's dialog style; [pick] gets the chosen index. */
    fun options(c: Context, title: String, items: Array<String>, pick: (Int) -> Unit): Dialog =
        ConsoleDialog.options(c, title, items) { pick(it) }

    // ---- the app's own interface choice (used only while the launcher is not installed) -----------

    /** Names of the offered interfaces, in picker order (see [interfaceAt]). */
    fun interfaceNames(): Array<String> = Skin.interfaceNames().map { it.orEmpty() }.toTypedArray()

    /** The interface id at picker position [which] of [interfaceNames]. */
    fun interfaceAt(which: Int): Int = Skin.INTERFACES[which]

    /** Picker position of interface [faction] in [interfaceNames]. */
    fun interfaceIndex(faction: Int): Int = Skin.interfaceIndex(faction)

    /** Display names of the sub-themes (colour themes) offered for [faction]. */
    fun subThemeNames(faction: Int): Array<String> = Palette.themesFor(faction).map { it.name }.toTypedArray()

    /** True if [faction] offers a choice of sub-themes its palette actually uses. */
    fun hasSubThemes(faction: Int): Boolean {
        val themes = Palette.themesFor(faction)
        return themes.size > 1 && (Skin.commandFamily(faction) || themes !== Palette.ALL)
    }

    // ---- sound --------------------------------------------------------------------------------------

    /**
     * First run: the keyboard's own sound set defaults to the synthesized clips (it never downloads the
     * sound library) and its own volume to [DEFAULT_VOLUME].
     */
    fun ensureSoundDefaults(c: Context) {
        val p = TrekPrefs.get(c)
        if (p.s(TrekPrefs.KEY_SOUND_SET, null) == null) p.put(TrekPrefs.KEY_SOUND_SET, Sfx.SET_SYNTH)
        if (!p.sp.contains(KEY_VOLUME)) p.put(KEY_VOLUME, DEFAULT_VOLUME)
    }

    fun soundOn(c: Context): Boolean = TrekPrefs.get(c).b(KEY_SOUND_ON, true)
    fun setSoundOn(c: Context, on: Boolean) = TrekPrefs.get(c).put(KEY_SOUND_ON, on)
    fun volume(c: Context): Int = TrekPrefs.get(c).i(KEY_VOLUME, DEFAULT_VOLUME)
    fun setVolume(c: Context, v: Int) = TrekPrefs.get(c).put(KEY_VOLUME, v.coerceIn(0, 100))

    /** True when Android's own "touch sounds" setting is on (key clicks follow it). */
    fun systemTouchSounds(c: Context): Boolean = try {
        Settings.System.getInt(c.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0
    } catch (e: RuntimeException) {
        true
    }

    /**
     * Plays the key click (the interface's synthesized tap) if the keyboard's KEY SOUNDS and Android's touch
     * sounds are on; Sfx itself stays silent in Silent / Vibrate and during calls.
     */
    fun click(c: Context) {
        if (!soundOn(c) || volume(c) <= 0 || !systemTouchSounds(c)) return
        Sfx.playEvent(c, Sfx.E_BUTTON, true)
    }

    /** Re-reads the sound set (after the interface changed). */
    fun reloadSounds(c: Context) = Sfx.reload(c)

    /** A settings-screen toggle sound. */
    fun toggleSound(c: Context) = Sfx.play(c, Sfx.TOGGLE_ON)
}
