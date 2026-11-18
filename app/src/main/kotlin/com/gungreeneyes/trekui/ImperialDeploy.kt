package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import java.util.Locale

/**
 * Imperial "TACTICAL DEPLOY" home layout. Folded, it is one command plate: a long double-lined
 * octagon with triangle clusters, "COMMAND" and the number of stations. Pressing it lifts the
 * plate to the top, a spine of alternating triangles drops down the centre, and the tile plates
 * slide out from behind the spine row by row. The plates narrow toward the bottom, so the deployed
 * set forms an arrowhead. Always fits the space; never scrolls.
 */
class ImperialDeploy(c: Context, p: Palette, onMasterLong: View.OnLongClickListener?) : RevealLayout(c, p, onMasterLong) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outer = Path()
    private val inner = Path()
    private val r = RectF()
    private var plateW = 0f
    private var plateH = 0f
    private var spineW = 0f
    private var gap = 0f
    private var rowH = 0f
    private var top0 = 0f
    private var taper = 0f
    private var rows = 0
    private var plL = FloatArray(0)
    private var plW = FloatArray(0)

    init {
        text.typeface = Ui.font(c, true)
    }

    /** Even tiles slide out to the left of the spine, odd ones to the right. */
    private fun left(i: Int): Boolean = i % 2 == 0

    override fun addNode(content: View, label: String, color: Int, empty: Boolean): View {
        val i = nodes.size
        val lft = left(i)
        val plate = FrameLayout(context)
        // outer side carries the corner cuts: left plates point left, right plates point right
        val kind = if (lft) Ui.LEFT else Ui.RIGHT
        plate.background = Ui.touch(Ui.shape(context, if (empty) Ui.alpha(color, 0.3f) else color, kind, 12f))
        if (!empty) {
            val iconSize = Math.round(dp(24f))
            val il = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER_VERTICAL or (if (lft) Gravity.START else Gravity.END))
            if (lft) il.leftMargin = Math.round(dp(18f)) else il.rightMargin = Math.round(dp(18f))
            plate.addView(content, il)
        }
        val lab = Ui.oneLine(
            Ui.bold(
                context, (if (empty) "+ ASSIGN" else label).uppercase(Locale.getDefault()), 14f,
                if (empty) Ui.alpha(Skin.glow(color), 0.7f) else Skin.ink(),
            ),
        )
        lab.gravity = (if (lft) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
        val ll = FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL)
        ll.leftMargin = Math.round(if (lft) dp(48f) else dp(12f))
        ll.rightMargin = Math.round(if (lft) dp(12f) else dp(48f))
        plate.addView(lab, ll)
        plate.contentDescription = label
        nodes.add(plate)
        empties.add(empty)
        colors.add(color)
        addView(plate)
        return plate
    }

    /**
     * Sizes the plates so every row fits the height (shrinking rows on short landscape screens)
     * and the set stays within [contentWidth] on wide screens.
     */
    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        var h = MeasureSpec.getSize(hs)
        if (MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED || h == 0) h = Math.round(w * 1.5f)
        val n = nodes.size
        val cw = contentWidth(w)
        plateH = dp(PLATE_H_DP)
        plateW = Math.min(cw * 0.82f, dp(PLATE_MAX_W_DP))
        spineW = dp(SPINE_DP)
        gap = dp(Ui.GUTTER.toFloat())
        top0 = plateH + gap * 2
        rows = Math.max(1, (n + 1) / 2)
        rowH = fitRowHeight(h - top0, rows, gap, dp(ROW_MIN_DP), dp(ROW_MAX_DP))
        val half = (cw - spineW) / 2f - gap
        // each row is a little narrower than the one above: the deployed set is an arrowhead
        taper = Math.min(dp(TAPER_MAX_DP), half * 0.3f / Math.max(1, rows - 1))
        plL = FloatArray(n)
        plW = FloatArray(n)
        for (i in 0 until n) {
            val pw = half - (i / 2) * taper
            plW[i] = pw
            nodes[i].measure(
                MeasureSpec.makeMeasureSpec(Math.round(pw), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(rowH), MeasureSpec.EXACTLY),
            )
        }
        master.measure(
            MeasureSpec.makeMeasureSpec(Math.round(plateW), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(Math.round(plateH), MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(w, h)
    }

    /** Places the plates in rows either side of the centred spine. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, rr: Int, b: Int) {
        val w = rr - l
        val n = nodes.size
        val cx = w / 2f
        for (i in 0 until n) {
            val row = i / 2
            val y = top0 + row * (rowH + gap)
            val x = if (left(i)) cx - spineW / 2f - gap - plW[i] else cx + spineW / 2f + gap
            nodes[i].layout(Math.round(x), Math.round(y), Math.round(x + plW[i]), Math.round(y + rowH))
            plL[i] = x
        }
        master.layout(Math.round(cx - plateW / 2f), 0, Math.round(cx + plateW / 2f), Math.round(plateH))
        apply()
    }

    /** Command plate travels from the middle (folded) to the top. */
    private fun masterY(): Float {
        if (pinned) return 0f
        val folded = height / 2f - plateH / 2f
        return folded * (1 - ease(progress / 0.3f))
    }

    /** How far the triangle spine has grown (0..1). */
    private fun spineProgress(): Float = ease((progress - 0.15f) / 0.45f)

    /** How far plate i has slid out (0..1); rows follow one another, right after left. */
    private fun plateProgress(i: Int): Float = ease((progress - 0.3f - (i / 2) * 0.05f - (i % 2) * 0.02f) / 0.4f)

    override fun apply() {
        master.translationY = masterY()
        var i = 0
        while (i < nodes.size && i < plW.size) {
            val v = nodes[i]
            val e = plateProgress(i)
            // slide out from behind the spine
            val travel = plW[i] * 0.55f * (1 - e)
            v.translationX = if (left(i)) travel else -travel
            v.alpha = e * dim
            show(v, e)
            i++
        }
        invalidate()
    }

    /** Plates are clipped at the spine, so they emerge from behind it rather than crossing it. */
    override fun drawChild(cv: Canvas, child: View, time: Long): Boolean {
        val i = nodes.indexOf(child)
        if (i < 0) return super.drawChild(cv, child, time)
        val cx = width / 2f
        cv.save()
        if (left(i)) {
            cv.clipRect(0f, 0f, cx - spineW / 2f, height.toFloat())
        } else {
            cv.clipRect(cx + spineW / 2f, 0f, width.toFloat(), height.toFloat())
        }
        val drawn = super.drawChild(cv, child, time)
        cv.restore()
        return drawn
    }

    /** Draws the spine, the command plate and (folded) its triangle rows, then the plates. */
    override fun dispatchDraw(cv: Canvas) {
        val w = width
        val cx = w / 2f
        val my = masterY()
        // spine: a chain of alternating triangles down the centre
        val sp = spineProgress()
        if (sp > 0 && rows > 0) {
            val bottom = top0 + rows * (rowH + gap) - gap
            val reach = plateH + gap + (bottom - plateH - gap) * sp
            val s = spineW
            val step = s * 0.62f
            val cols = intArrayOf(ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED)
            var k = 0
            var y = plateH + gap + s / 2f
            while (y < reach - s / 2f) {
                ImperialDraw.tri(cv, cx, y, s, s * 0.8f, k % 2 == 1, cols[k % 3], (if (k % 2 == 1) 1f else 0.75f) * dim)
                y += step
                k++
            }
            // a ▲▼ row under the last plates once everything is out
            if (progress > 0.9f && bottom + dp(14f) < height) {
                val rw = Math.min(w * 0.4f, dp(120f))
                val a = (progress - 0.9f) / 0.1f
                ImperialDraw.row(
                    cv, cx - rw / 2f, bottom + dp(6f), rw, dp(8f),
                    intArrayOf(ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED), a * dim,
                )
            }
        }
        // the command plate
        val l = cx - plateW / 2f
        val t = my
        val rgt = cx + plateW / 2f
        val b = my + plateH
        val k2 = plateH * 0.36f
        ImperialDraw.octagon(outer, l, t, rgt, b, k2, true, true)
        ImperialDraw.octagon(inner, l + dp(4f), t + dp(4f), rgt - dp(4f), b - dp(4f), k2 - dp(2f), true, true)
        ImperialDraw.fill(cv, outer, if (Ui.cloak) 0xFF000000.toInt() else Skin.mix(Skin.bg(), ImperialDraw.RED, 0.12f))
        if (!Ui.cloak) {
            cv.save()
            cv.clipPath(inner)
            r.set(l, t, rgt, b)
            ImperialDraw.grid(cv, r, dp(14f), ImperialDraw.RED, 0.16f, d)
            cv.restore()
        }
        ImperialDraw.frame(cv, outer, inner, ImperialDraw.RED, d, 1f)
        val cs = plateH * 0.24f
        ImperialDraw.cluster(cv, l + k2 + cs * 0.6f, (t + b) / 2f, cs, 1f)
        text.color = Skin.ink()
        text.textAlign = Paint.Align.RIGHT
        text.textSize = Ui.px(context, 24f)
        cv.drawText(masterTitle ?: if (isOpen()) "COMMAND" else "DEPLOY", rgt - k2 - dp(4f), b - plateH * 0.3f, text)
        text.textAlign = Paint.Align.LEFT
        text.textSize = Ui.px(context, 13f)
        text.color = ImperialDraw.ORANGE
        cv.drawText(String.format(Locale.US, "%02d", shownCount()), l + k2 + cs * 1.9f, b - plateH * 0.32f, text)
        // folded: triangle rows above and below the plate, and a stand-by line
        val f = if (pinned) 0f else 1 - clamp(progress / 0.25f)
        if (f > 0) {
            val rw = plateW * 0.6f
            val cols = intArrayOf(ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED)
            ImperialDraw.row(cv, cx - rw / 2f, t - dp(16f), rw, dp(9f), cols, f)
            ImperialDraw.row(cv, cx - rw / 2f, b + dp(7f), rw, dp(9f), cols, f)
            text.textAlign = Paint.Align.CENTER
            text.textSize = Ui.px(context, 13f)
            text.color = Ui.alpha(ImperialDraw.ORANGE, f)
            cv.drawText(shownCount().toString() + " STATIONS AWAIT ORDERS", cx, b + dp(38f), text)
        }
        super.dispatchDraw(cv)
    }

    private companion object {
        /** Command plate height and maximum width, spine width and the preferred row-height range, in dp. */
        const val PLATE_H_DP = 60f
        const val PLATE_MAX_W_DP = 300f
        const val SPINE_DP = 18f
        const val ROW_MIN_DP = 38f
        const val ROW_MAX_DP = 64f

        /** Most each row narrows relative to the one above (the arrowhead), in dp. */
        const val TAPER_MAX_DP = 10f
    }
}
