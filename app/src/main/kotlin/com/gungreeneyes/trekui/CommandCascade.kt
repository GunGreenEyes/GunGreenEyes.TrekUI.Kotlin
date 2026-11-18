package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import java.util.Locale

/**
 * Command "CONSOLE CASCADE" home layout. Folded, it is one large console key in the middle of the
 * screen ("ACCESS" and the number of stations). Pressing it lifts the key to the top, a
 * segmented console spine grows down from it, and the tile keys extend sideways out of the spine
 * row by row: left keys with rounded left ends, right keys with rounded right ends, like console
 * panel rows sliding out. Always fits the space; never scrolls. The 23rd/25th-century eras unfold
 * the same way with their own keys and spine ([EraDraw.key]).
 */
class CommandCascade(c: Context, p: Palette, onMasterLong: View.OnLongClickListener?) : RevealLayout(c, p, onMasterLong) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var masterW = 0f
    private var masterH = 0f
    private var spineW = 0f
    private var gap = 0f
    private var rowH = 0f
    private var top0 = 0f
    private var rows = 0
    private var keyL = FloatArray(0)
    private var keyT = FloatArray(0)
    private var keyR = FloatArray(0)

    init {
        text.typeface = Ui.font(c, true)
    }

    /** Even tiles extend to the left of the spine, odd ones to the right. */
    private fun left(i: Int): Boolean = i % 2 == 0

    override fun addNode(content: View, label: String, color: Int, empty: Boolean): View {
        val i = nodes.size
        val lft = left(i)
        val key = FrameLayout(context)
        val kind = if (lft) Ui.LEFT else Ui.RIGHT
        key.background = Ui.touch(
            if (empty) {
                Ui.outline(context, 0x00000000, Ui.alpha(color, 0.6f), KEY_RADIUS_DP, 1.5f)
            } else {
                Ui.shape(context, color, kind, KEY_RADIUS_DP)
            },
        )
        if (!empty) {
            val iconSize = Math.round(dp(ICON_DP))
            val il = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER_VERTICAL or (if (lft) Gravity.START else Gravity.END))
            if (lft) il.leftMargin = Math.round(dp(ICON_INSET_DP)) else il.rightMargin = Math.round(dp(ICON_INSET_DP))
            key.addView(content, il)
        }
        val lab = Ui.oneLine(
            Ui.bold(
                context, (if (empty) "+ ASSIGN" else label).uppercase(Locale.getDefault()), 15f,
                if (empty) Ui.alpha(color, 0.8f) else Ui.ink(),
            ),
        )
        // console lettering sits low and toward the spine
        lab.gravity = if (lft) Gravity.END else Gravity.START
        val ll = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        ll.leftMargin = Math.round(if (lft) dp(LABEL_ICON_SIDE_DP) else dp(LABEL_SPINE_SIDE_DP))
        ll.rightMargin = Math.round(if (lft) dp(LABEL_SPINE_SIDE_DP) else dp(LABEL_ICON_SIDE_DP))
        ll.bottomMargin = Math.round(dp(5f))
        key.addView(lab, ll)
        key.contentDescription = label
        nodes.add(key)
        empties.add(empty)
        colors.add(color)
        addView(key)
        return key
    }

    /**
     * Sizes the keys so every row fits the height (shrinking rows on short landscape screens) and
     * the key set stays within [contentWidth] on wide screens.
     */
    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        var h = MeasureSpec.getSize(hs)
        if (MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED || h == 0) h = Math.round(w * 1.5f)
        val n = nodes.size
        val cw = contentWidth(w)
        masterH = dp(MASTER_H_DP)
        masterW = Math.min(cw * 0.66f, dp(MASTER_MAX_W_DP))
        spineW = dp(SPINE_DP)
        gap = dp(Ui.GUTTER.toFloat())
        top0 = masterH + gap * 2
        rows = Math.max(1, (n + 1) / 2)
        rowH = fitRowHeight(h - top0, rows, gap, dp(ROW_MIN_DP), dp(ROW_MAX_DP))
        val half = (cw - spineW) / 2f - gap
        for (i in 0 until n) {
            nodes[i].measure(
                MeasureSpec.makeMeasureSpec(Math.round(half), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(rowH), MeasureSpec.EXACTLY),
            )
        }
        master.measure(
            MeasureSpec.makeMeasureSpec(Math.round(masterW), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(Math.round(masterH), MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(w, h)
    }

    /** Places the keys in rows either side of the centred spine. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, rr: Int, b: Int) {
        val w = rr - l
        val n = nodes.size
        val cx = w / 2f
        val half = (contentWidth(w) - spineW) / 2f - gap
        keyL = FloatArray(n)
        keyT = FloatArray(n)
        keyR = FloatArray(n)
        for (i in 0 until n) {
            val row = i / 2
            val y = top0 + row * (rowH + gap)
            val x = if (left(i)) cx - spineW / 2f - gap - half else cx + spineW / 2f + gap
            val v = nodes[i]
            v.layout(Math.round(x), Math.round(y), Math.round(x + half), Math.round(y + rowH))
            keyL[i] = x
            keyT[i] = y
            keyR[i] = x + half
        }
        master.layout(Math.round(cx - masterW / 2f), 0, Math.round(cx + masterW / 2f), Math.round(masterH))
        apply()
    }

    /** Master key travels from the middle (folded) to the top. */
    private fun masterY(): Float {
        if (pinned) return 0f
        val folded = height / 2f - masterH / 2f
        return folded * (1 - ease(progress / 0.3f))
    }

    /** How far the spine has grown (0..1) at the current deploy progress. */
    private fun spineProgress(): Float = ease((progress - 0.15f) / 0.45f)

    /** How far tile i has extended (0..1); rows follow one another. */
    private fun keyProgress(i: Int): Float = ease((progress - 0.3f - (i / 2) * 0.05f) / 0.4f)

    override fun apply() {
        val my = masterY()
        master.translationY = my
        var i = 0
        while (i < nodes.size && i < keyL.size) {
            val v = nodes[i]
            val e = keyProgress(i)
            // keys extend out of the spine
            v.pivotX = if (left(i)) v.width.toFloat() else 0f
            v.pivotY = v.height / 2f
            v.scaleX = Math.max(0.01f, e)
            v.alpha = (0.3f + 0.7f * e) * dim
            show(v, e)
            i++
        }
        invalidate()
    }

    /** Draws the spine, the master key and (folded) its flanking pills, then the keys. */
    override fun dispatchDraw(cv: Canvas) {
        val w = width
        val cx = w / 2f
        val my = masterY()
        val cloak = Ui.cloak
        paint.style = if (cloak) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = dp(1.5f)
        // the spine: one console segment per row, growing downward
        val sp = spineProgress()
        if (sp > 0 && rows > 0) {
            val bottom = top0 + rows * (rowH + gap) - gap
            val reach = top0 + (bottom - top0) * sp
            val seg = intArrayOf(pal.c(1), pal.c(3), pal.c(5), pal.c(2))
            for (k in 0 until rows) {
                val st = top0 + k * (rowH + gap)
                val en = Math.min(st + rowH, reach)
                if (en <= st) break
                paint.color = Ui.alpha(seg[k % seg.size], dim)
                if (Skin.era()) {
                    r.set(cx - spineW / 2f, st, cx + spineW / 2f, en)
                    EraDraw.key(cv, r, paint.color, Ui.SQUARE, dp(1f), cloak, k)
                } else {
                    cv.drawRect(cx - spineW / 2f, st, cx + spineW / 2f, en, paint)
                }
            }
            // a neck joining the master key to the spine
            paint.color = Ui.alpha(pal.c(1), dim)
            if (Skin.era()) {
                r.set(cx - spineW / 2f, masterH + gap * 0.5f, cx + spineW / 2f, Math.min(top0 - gap * 0.5f, reach))
                if (r.height() > 1) EraDraw.key(cv, r, paint.color, Ui.SQUARE, dp(1f), cloak, -1)
            } else {
                cv.drawRect(cx - spineW / 2f, masterH + gap * 0.5f, cx + spineW / 2f, Math.min(top0 - gap * 0.5f, reach), paint)
            }
        }
        // the master key: a large console pill (a console key or a glass plate in the eras)
        r.set(cx - masterW / 2f, my, cx + masterW / 2f, my + masterH)
        paint.color = Ui.alpha(pal.c(0), Math.max(dim, 0.6f))
        if (Skin.era()) {
            EraDraw.key(cv, r, paint.color, Ui.SQUARE, dp(1f), cloak, 0x4D)
        } else {
            cv.drawRoundRect(r, masterH / 2f, masterH / 2f, paint)
        }
        text.color = if (cloak) Ui.CLOAK_INK else Ui.ink()
        text.textAlign = Paint.Align.RIGHT
        text.textSize = Ui.px(context, 22f)
        cv.drawText(masterTitle ?: if (isOpen()) "OPERATIONS" else "ACCESS", r.right - masterH * 0.45f, r.bottom - dp(12f), text)
        text.textAlign = Paint.Align.LEFT
        text.textSize = Ui.px(context, 14f)
        cv.drawText(String.format(Locale.US, "%02d", shownCount()), r.left + masterH * 0.45f, r.bottom - dp(13f), text)
        // folded: flanking pills and a stand-by line under the key
        val f = if (pinned) 0f else 1 - clamp(progress / 0.25f)
        if (f > 0) {
            paint.color = Ui.alpha(pal.c(3), f * dim)
            r.set(cx - masterW / 2f - dp(46f), my + masterH * 0.25f, cx - masterW / 2f - dp(8f), my + masterH * 0.75f)
            if (Skin.era()) {
                EraDraw.key(cv, r, paint.color, Ui.LEFT, dp(1f), cloak, 1)
            } else {
                cv.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paint)
            }
            paint.color = Ui.alpha(pal.c(4), f * dim)
            r.set(cx + masterW / 2f + dp(8f), my + masterH * 0.25f, cx + masterW / 2f + dp(46f), my + masterH * 0.75f)
            if (Skin.era()) {
                EraDraw.key(cv, r, paint.color, Ui.RIGHT, dp(1f), cloak, 2)
            } else {
                cv.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paint)
            }
            text.textAlign = Paint.Align.CENTER
            text.textSize = Ui.px(context, 13f)
            text.color = Ui.alpha(pal.c(2), f)
            cv.drawText(shownCount().toString() + " STATIONS STANDING BY", cx, my + masterH + dp(26f), text)
        }
        super.dispatchDraw(cv)
    }

    private companion object {
        /** Master key height, maximum width, spine width and the preferred row-height range, in dp. */
        const val MASTER_H_DP = 54f
        const val MASTER_MAX_W_DP = 260f
        const val SPINE_DP = 22f
        const val ROW_MIN_DP = 38f
        const val ROW_MAX_DP = 70f

        /** Key icon size, its inset from the rounded end, and the label inset on the icon side, in dp. */
        const val ICON_DP = 26f
        const val ICON_INSET_DP = 16f
        const val LABEL_ICON_SIDE_DP = 48f
        const val LABEL_SPINE_SIDE_DP = 10f

        /** Corner radius of the keys, in dp. */
        const val KEY_RADIUS_DP = 30f
    }
}
