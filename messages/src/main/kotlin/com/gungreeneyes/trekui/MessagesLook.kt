package com.gungreeneyes.trekui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * The Messages app's window onto :core's look (Skin, Palette, Ui, ConsoleFrame, ConsoleDialog). In the
 * Java edition those helpers were package-private in `com.gungreeneyes.trekui`, so this facade lives in the
 * same package (inside the :messages module, which is a separate APK, so no class is shared with the
 * launcher) and exposes exactly what the Messages screens need, so the app draws in whichever interface
 * and sub-theme is active without copying any drawing code.
 *
 * Call [load] before building a screen: it re-reads the interface (the launcher's choice while following
 * it, see [TrekPrefs.setThemeOverride]) and refreshes the palette.
 */
internal object MessagesLook {
    /** Corner styles for [cell]: all corners, pill, left side round, right side round. */
    const val SQUARE = Ui.SQUARE
    const val PILL = Ui.PILL
    const val LEFT = Ui.LEFT
    const val RIGHT = Ui.RIGHT

    /** Spacing scale (dp), the same as every launcher screen. */
    const val XS = Ui.XS
    const val GUTTER = Ui.GUTTER
    const val S = Ui.S
    const val M = Ui.M
    const val L = Ui.L
    const val XL = Ui.XL

    /** Minimum touch target (dp). */
    const val MIN_TOUCH_DP = Ui.MIN_TOUCH_DP

    private var palette: Palette = Palette.ALL[0]

    /** Re-reads the active interface and palette; call before (re)building a screen. */
    fun load(c: Context) {
        palette = TrekPrefs.get(c).palette()
    }

    // ---- colours ------------------------------------------------------------------------------

    /** Accent [i] of the active palette (wraps around). */
    fun accent(i: Int): Int = palette.c(i)
    fun textHi(): Int = palette.textHi
    fun textLo(): Int = palette.textLo
    fun alert(): Int = palette.alert
    fun panel(): Int = palette.panel

    /** Window background of the active interface. */
    fun bg(): Int = Skin.bg()

    /** Raised surface colour (trays, fields). */
    fun surface(): Int = Skin.surface()

    /** Hairline / grid colour. */
    fun grid(): Int = Skin.grid()

    /** Lettering colour on a filled cell. */
    fun ink(): Int = Skin.ink()

    /** [color] with alpha [a] (0..1). */
    fun alpha(color: Int, a: Float): Int = Ui.alpha(color, a)

    // ---- sizes and type -----------------------------------------------------------------------

    /** Design dp to pixels including the window zoom. */
    fun dp(c: Context, v: Float): Int = Ui.dp(c, v)

    /** Design dp (an integer spacing step) to pixels including the window zoom. */
    fun dp(c: Context, v: Int): Int = Ui.dp(c, v.toFloat())

    /** Design sp to pixels for Canvas text (interface typeface scale and zoom applied). */
    fun px(c: Context, sp: Float): Float = Ui.px(c, sp)
    fun setTextSp(t: TextView, sp: Float) = Ui.setTextSp(t, sp)
    fun font(c: Context, heavy: Boolean): Typeface? = Ui.font(c, heavy)

    /** Letter spacing of the interface typeface. */
    fun letterSpacing(): Float = Skin.letterSpacing()

    fun text(c: Context, s: CharSequence?, sp: Float, color: Int): TextView = Ui.text(c, s, sp, color)
    fun bold(c: Context, s: CharSequence?, sp: Float, color: Int): TextView = Ui.bold(c, s, sp, color)
    fun oneLine(t: TextView): TextView = Ui.oneLine(t)

    /** A label in upper case when the user's "upper-case labels" option is on. */
    fun up(c: Context, s: String?): String = Ui.up(c, s)

    // ---- shapes and controls ------------------------------------------------------------------

    /** A console button in [color] (corner style [kind]) with tap feedback. */
    fun button(c: Context, label: String, color: Int, kind: Int, l: View.OnClickListener?): TextView =
        Ui.button(c, label, color, kind, l)

    /** A filled cell in the interface's geometry. */
    fun cell(c: Context, color: Int, kind: Int, radiusDp: Float): Drawable = Ui.shape(c, color, kind, radiusDp)

    /** A filled cell wrapped in a ripple (for tappable rows). */
    fun touchCell(c: Context, color: Int, kind: Int, radiusDp: Float): Drawable =
        Ui.touch(Ui.shape(c, color, kind, radiusDp))

    /** Ripple for a transparent tappable area. */
    fun touchArea(c: Context, radiusDp: Float): Drawable = Ui.touchArea(c, radiusDp)

    /** An outlined tray (fields, incoming bubbles, empty states). */
    fun outline(c: Context, fill: Int, stroke: Int, radiusDp: Float, strokeDp: Float): Drawable =
        Ui.outline(c, fill, stroke, radiusDp, strokeDp)

    /** Wraps a listener with the interface's haptic + sound tap feedback. */
    fun feedback(l: View.OnClickListener): View.OnClickListener = Ui.feedback(l)

    /** Quieter tap feedback for repeated controls (list rows). */
    fun feedbackSoft(l: View.OnClickListener): View.OnClickListener = Ui.feedbackSoft(l)

    /** Plays tap feedback on [v]. */
    fun tap(v: View) = Ui.tap(v)

    /** Long-press feedback on [v]. */
    fun longPress(v: View) = Ui.longPress(v)

    // ---- the console frame ----------------------------------------------------------------------

    /**
     * The interface's console frame (sidebar + elbows + bars) around [content], coloured from the
     * active palette like the launcher's CONFIG screen.
     *
     * @param sidebarDp sidebar width in design dp
     */
    fun frame(c: Context, title: View?, footer: View?, sidebar: View?, content: View, sidebarDp: Float): ViewGroup =
        ConsoleFrame(c).apply {
            sidebarW = Ui.dp(c, sidebarDp)
            colorTop = palette.c(3)
            colorBot = palette.c(1)
            topBlocks = intArrayOf(palette.c(0))
            botBlocks = intArrayOf(palette.c(2), palette.c(4))
            if (title != null) setTitle(title)
            if (footer != null) setFooter(footer)
            if (sidebar != null) setSidebar(sidebar)
            setContent(content)
        }

    // ---- dialogs ------------------------------------------------------------------------------

    /** A list of options in the interface's dialog style. */
    fun options(c: Context, title: String?, items: Array<String>, pick: (Int) -> Unit): Dialog =
        ConsoleDialog.options(c, title, items) { pick(it) }

    /** A message with buttons (the first in the primary colour); [pick] may be null. */
    fun message(c: Context, title: String?, msg: String?, buttons: Array<String>, pick: ((Int) -> Unit)?): Dialog =
        ConsoleDialog.message(c, title, msg, buttons, pick?.let { p -> ConsoleDialog.OnPick { p(it) } })

    /** A one-line text prompt. */
    fun prompt(c: Context, title: String?, initial: String?, hint: String?, cb: (String) -> Unit): Dialog =
        ConsoleDialog.prompt(c, title, initial, hint) { cb(it) }

    // ---- the app's own interface choice (used only while the launcher is not installed) --------

    /** Names of the offered interfaces, in picker order (see [interfaceAt]). */
    fun interfaceNames(): Array<String> = Skin.interfaceNames().map { it.orEmpty() }.toTypedArray()

    /** The interface id at picker position [which] of [interfaceNames]. */
    fun interfaceAt(which: Int): Int = Skin.INTERFACES[which]

    /** Display names of the sub-themes [SubThemes] offers for [faction]. */
    fun subThemeNames(faction: Int): Array<String> = Array(SubThemes.count(faction)) { SubThemes.name(faction, it) }

    /** True if [faction] offers a choice of sub-themes ([SubThemes.selectable]). */
    fun hasSubThemes(faction: Int): Boolean = SubThemes.selectable(faction)

    /** Swatch colour of sub-theme [index] for [faction] (its primary accent). */
    fun subThemeSwatch(faction: Int, index: Int): Int = SubThemes.accents(faction, index).firstOrNull() ?: Color.GRAY
}
