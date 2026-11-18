package com.gungreeneyes.trekui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * Modal panels in the current interface's style: option lists, text prompts and messages.
 *
 * Every dialog is built by [base]: a framed panel (console outline or faction tray) with a title
 * strip and a scrolling body. Sizing is responsive: the panel is [MAX_WIDTH_DP] wide at most (times
 * the window's [Ui.zoom]), never wider than the window minus [SIDE_MARGIN_DP], so dialogs stay a
 * readable column on tablets and desktop windows instead of stretching across a 1280dp screen; the
 * body scrolls once it would exceed [MAX_BODY_HEIGHT_FRACTION] of the window height (landscape
 * phones included).
 *
 * Java interop: the constants are `const`, every function is [JvmStatic] and [Built] exposes its
 * parts as [JvmField]s, so Java keeps calling `ConsoleDialog.options(...)` and reading `b.body`.
 */
object ConsoleDialog {
    /** Callback with the index of the chosen option. */
    fun interface OnPick {
        fun pick(which: Int)
    }

    /** Callback with the trimmed text the user accepted. */
    fun interface OnText {
        fun text(value: String)
    }

    /** Widest a dialog may be, in design dp (before zoom). */
    const val MAX_WIDTH_DP = 460f
    /** Space kept between the dialog and the window edges, in total (both sides), dp. */
    const val SIDE_MARGIN_DP = 32f
    /** Largest share of the window height the scrolling body may take. */
    const val MAX_BODY_HEIGHT_FRACTION = 0.62f
    /** Dim behind the dialog. */
    const val DIM_AMOUNT = 0.7f

    /** A dialog under construction: add views to [body], then show [dialog]. */
    class Built(@JvmField val dialog: Dialog, @JvmField val body: LinearLayout)

    /**
     * Dialog width in dp for a window `windowWidthDp` wide: the design width scaled by
     * `zoom`, but always leaving [SIDE_MARGIN_DP] free (and never below 1dp).
     */
    @JvmStatic
    fun widthDpFor(windowWidthDp: Float, zoom: Float): Float =
        max(1f, min(windowWidthDp - SIDE_MARGIN_DP * zoom, MAX_WIDTH_DP * zoom))

    /** Maximum body height in px for a window `windowHeightPx` tall. */
    @JvmStatic
    fun maxBodyHeightFor(windowHeightPx: Int): Int = (windowHeightPx * MAX_BODY_HEIGHT_FRACTION).toInt()

    /** Builds the panel, title strip and scrolling body; the caller fills the body and shows it. */
    @JvmStatic
    fun base(c: Context, title: String?): Built {
        val p = TrekPrefs.get(c).palette()
        val d = Dialog(c)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(c)
        root.orientation = LinearLayout.VERTICAL
        val frame: Drawable = if (Skin.command()) {
            Ui.outline(c, Color.BLACK, p.c(1), 26f, 2f)
        } else {
            Ui.outline(c, Skin.surface(), p.c(if (Skin.imperial()) 0 else 1), Skin.trayRadius(26f), 1.5f)
        }
        root.background = frame
        val pad = Ui.dp(c, 14f)
        root.setPadding(pad, pad, pad, pad)

        // title strip: left cap, rule, title
        val head = LinearLayout(c)
        head.gravity = Gravity.CENTER_VERTICAL
        val cap = View(c)
        cap.background = Ui.shape(c, p.c(0), Ui.LEFT, 16f)
        head.addView(cap, LinearLayout.LayoutParams(Ui.dp(c, 34f), Ui.dp(c, 30f)))
        val bar = View(c)
        bar.background = Skin.rule(c, p.c(0))
        val blp = LinearLayout.LayoutParams(0, Ui.dp(c, 30f), 1f)
        blp.leftMargin = Ui.dp(c, Ui.XS.toFloat())
        head.addView(bar, blp)
        val t = Ui.oneLine(Ui.bold(c, title, 22f, p.c(0)))
        t.setPadding(Ui.dp(c, Ui.M.toFloat()), 0, Ui.dp(c, Ui.XS.toFloat()), 0)
        head.addView(t)
        root.addView(head)

        val m = ScreenMetrics.of(c)
        val sv = MaxHeightScrollView(c, maxBodyHeightFor(Math.round(m.heightDp * m.density)))
        sv.isVerticalScrollBarEnabled = true
        val body = LinearLayout(c)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(0, Ui.dp(c, Ui.M.toFloat()), 0, 0)
        sv.addView(body)
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        d.setContentView(root)
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val widthPx = Math.round(widthDpFor(m.widthDp, Ui.zoom(c)) * m.density)
            w.setLayout(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT)
            val lp = w.attributes
            lp.dimAmount = DIM_AMOUNT
            w.attributes = lp
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        return Built(d, body)
    }

    /** A full-width pill option button in `color`. */
    @JvmStatic
    fun option(c: Context, label: String, color: Int, l: View.OnClickListener?): TextView {
        val b = Ui.button(c, label, color, Ui.PILL, l)
        b.minHeight = Ui.dp(c, 52f)
        Ui.setTextSp(b, 17f)
        return b
    }

    /** Appends an option with the standard spacing below it. */
    @JvmStatic
    fun addOption(b: Built, v: TextView) {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = Ui.dp(v.context, Ui.S.toFloat())
        b.body.addView(v, lp)
    }

    /** Shows a list of options; tapping one dismisses the dialog and reports its index. */
    @JvmStatic
    fun options(c: Context, title: String?, items: Array<String>, cb: OnPick): Dialog {
        val p = TrekPrefs.get(c).palette()
        val b = base(c, title)
        for (i in items.indices) {
            addOption(b, option(c, items[i], p.c(i + 1)) {
                b.dialog.dismiss()
                cb.pick(i)
            })
        }
        b.dialog.show()
        return b.dialog
    }

    /** Shows a message with buttons (the first in the primary colour); `cb` may be null. */
    @JvmStatic
    fun message(c: Context, title: String?, msg: String?, buttons: Array<String>, cb: OnPick?): Dialog {
        val p = TrekPrefs.get(c).palette()
        val b = base(c, title)
        val m = Ui.text(c, msg, 16f, p.textHi)
        m.setLineSpacing(0f, 1.15f)
        m.setPadding(Ui.dp(c, Ui.XS.toFloat()), 0, Ui.dp(c, Ui.XS.toFloat()), Ui.dp(c, Ui.L.toFloat()))
        b.body.addView(m)
        for (i in buttons.indices) {
            addOption(b, option(c, buttons[i], p.c(if (i == 0) 0 else i + 1)) {
                b.dialog.dismiss()
                cb?.pick(i)
            })
        }
        b.dialog.show()
        return b.dialog
    }

    /** Asks for one line of text (upper-case keyboard); ACCEPT or the IME action reports it trimmed. */
    @JvmStatic
    fun prompt(c: Context, title: String?, initial: String?, hint: String?, cb: OnText): Dialog {
        val p = TrekPrefs.get(c).palette()
        val b = base(c, title)
        val e = EditText(c)
        e.setText(initial)
        e.hint = hint
        e.setSingleLine(true)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        e.imeOptions = EditorInfo.IME_ACTION_DONE
        e.setTextColor(p.textHi)
        e.setHintTextColor(Ui.alpha(p.textLo, 0.6f))
        e.typeface = Ui.font(c, false)
        Ui.setTextSp(e, 19f)
        e.background = Ui.outline(c, p.panel, p.c(2), 18f, 2f)
        e.setPadding(Ui.dp(c, Ui.L.toFloat()), Ui.dp(c, Ui.M.toFloat()), Ui.dp(c, Ui.L.toFloat()), Ui.dp(c, Ui.M.toFloat()))
        e.setSelection(e.text.length)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = Ui.dp(c, Ui.M.toFloat())
        b.body.addView(e, lp)
        val ok = Runnable {
            b.dialog.dismiss()
            cb.text(e.text.toString().trim())
        }
        e.setOnEditorActionListener { _, _, _ ->
            ok.run()
            true
        }
        val row = LinearLayout(c)
        val cancel = option(c, "CANCEL", p.c(3)) {
            Sfx.play(c, Sfx.CANCEL)
            b.dialog.dismiss()
        }
        val accept = option(c, "ACCEPT", p.c(0)) {
            Sfx.play(c, Sfx.CONFIRM)
            ok.run()
        }
        val h = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        h.rightMargin = Ui.dp(c, Ui.S.toFloat())
        row.addView(cancel, h)
        row.addView(accept, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        b.body.addView(row)
        b.dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        b.dialog.show()
        e.requestFocus()
        return b.dialog
    }

    /**
     * A ScrollView that wraps its content up to a maximum height, then scrolls. Replaces the old
     * global-layout listener, which only ever shrank the view and stayed registered for the dialog's life.
     */
    class MaxHeightScrollView(c: Context, private val maxHeightPx: Int) : ScrollView(c) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val mode = MeasureSpec.getMode(heightSpec)
            val size = MeasureSpec.getSize(heightSpec)
            val capped = if (mode == MeasureSpec.UNSPECIFIED) maxHeightPx else min(size, maxHeightPx)
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(capped, MeasureSpec.AT_MOST))
        }
    }
}
