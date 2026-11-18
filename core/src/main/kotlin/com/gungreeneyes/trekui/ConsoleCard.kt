package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * The container for every home-screen widget panel (`Panel.create()`): a bracket down the left
 * side, a title strip (title, accent rule, status text) and a vertical [body] the panel fills.
 * The bracket is drawn in the current interface's style (console "[", Imperial bevel, Collective trace, the
 * 23rd/25th-century brackets from [EraDraw.bracket]).
 *
 * Java interop: the parts the panels fill ([body], [titleView], [status], [bracket], [header]) are
 * [JvmField]s, so Java keeps reading `card.body`.
 */
open class ConsoleCard(c: Context, title: String?, accent: Int, p: Palette) : LinearLayout(c) {
    @JvmField val body: LinearLayout
    @JvmField val titleView: TextView
    @JvmField val status: TextView
    @JvmField val bracket: Bracket
    @JvmField val header: LinearLayout

    init {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        bracket = Bracket(c, accent)
        addView(bracket, LinearLayout.LayoutParams(Ui.dp(c, BRACKET_DP), LinearLayout.LayoutParams.MATCH_PARENT))

        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp.leftMargin = Ui.dp(c, Ui.S.toFloat())
        addView(col, lp)

        header = LinearLayout(c)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.minimumHeight = Ui.dp(c, 30f)
        titleView = Ui.oneLine(Ui.bold(c, title, 17f, accent))
        header.addView(titleView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val bar = View(c)
        bar.background = Ui.shape(c, Ui.alpha(accent, 0.55f), Ui.PILL, 0f)
        val bl = LinearLayout.LayoutParams(0, Ui.dp(c, 3f), 1f)
        bl.leftMargin = Ui.dp(c, Ui.S.toFloat())
        bl.rightMargin = Ui.dp(c, Ui.S.toFloat())
        header.addView(bar, bl)
        status = Ui.oneLine(Ui.text(c, "", 13f, p.textLo))
        header.addView(status)
        col.addView(header)

        body = LinearLayout(c)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(0, Ui.dp(c, Ui.XS.toFloat()), 0, Ui.dp(c, Ui.GUTTER.toFloat()))
        col.addView(body)
    }

    /** The side bracket: "[" with rounded outer corners and short stubs reaching toward the content. */
    open class Bracket(c: Context, color: Int) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val arc = RectF()

        init {
            paint.color = color
        }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val d = Ui.density(context)
            // bar: inner edge of the upright; stub: thickness of the top/bottom arms; reach: how far the arms extend
            val bar = w * 0.5f
            val stub = Ui.dp(context, 7f).toFloat()
            val r = w
            val reach = w + 14 * d
            if (Ui.cloak) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = max(1f, 1.2f * d)
            } else {
                paint.style = Paint.Style.FILL
            }
            path.reset()
            if (Skin.era()) {
                EraDraw.bracket(cv, d, w, h, paint.color, Ui.cloak)
                return
            }
            if (Skin.collective()) {
                // a trace down the side of the card: node at the top, via at the bottom,
                // a short branch toward the content
                val col = paint.color
                val cx = w * 0.4f
                val nr = min(w * 0.42f, 6 * d)
                val t = CollectiveDraw.tmp()
                t.moveTo(cx, nr * 2 + d)
                t.cubicTo(cx, h * 0.4f, cx + w * 0.2f, h * 0.6f, cx, h - 5 * d)
                CollectiveDraw.trace(cv, t, col, d, 0.85f)
                CollectiveDraw.curveTrace(cv, cx + nr, nr + d, reach, d * 2, -0.1f, col, d, 0.7f)
                CollectiveDraw.node(cv, cx, nr + d, nr, col, d, 1f)
                CollectiveDraw.via(cv, cx, h - 4 * d, 3 * d, col, d, 1f)
                return
            }
            if (Skin.imperial()) {
                // angular bracket: bevelled corners, inner diagonals
                val bevel = w * 0.8f
                path.moveTo(reach, 0f)
                path.lineTo(bevel, 0f)
                path.lineTo(0f, bevel)
                path.lineTo(0f, h - bevel)
                path.lineTo(bevel, h)
                path.lineTo(reach, h)
                path.lineTo(reach - stub, h - stub)
                path.lineTo(bar + r * 0.35f, h - stub)
                path.lineTo(bar, h - stub - r * 0.35f)
                path.lineTo(bar, stub + r * 0.35f)
                path.lineTo(bar + r * 0.35f, stub)
                path.lineTo(reach - stub, stub)
                path.close()
                cv.drawPath(path, paint)
                return
            }
            path.moveTo(reach, 0f)
            path.lineTo(r, 0f)
            arc.set(0f, 0f, r * 2, r * 2)
            path.arcTo(arc, 270f, -90f, false)
            path.lineTo(0f, h - r)
            arc.set(0f, h - r * 2, r * 2, h)
            path.arcTo(arc, 180f, -90f, false)
            path.lineTo(reach, h)
            path.lineTo(reach, h - stub)
            path.lineTo(bar + r * 0.35f, h - stub)
            path.lineTo(bar, h - stub - r * 0.35f)
            path.lineTo(bar, stub + r * 0.35f)
            path.lineTo(bar + r * 0.35f, stub)
            path.lineTo(reach, stub)
            path.close()
            cv.drawPath(path, paint)
        }
    }

    companion object {
        /** Width of the side bracket, design dp. */
        const val BRACKET_DP = 16f
    }
}
