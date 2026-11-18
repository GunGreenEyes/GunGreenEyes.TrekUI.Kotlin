package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/**
 * The info nodes under the Collective hub: one small node per home panel (weather, media, calendar,
 * power, systems), strung on a trace that rises into the hub's crosshair. Tapping a node opens
 * that panel over the network; tapping it again (or anywhere outside the panel) closes it. This
 * replaces scrolling down to the panels in the hub layout.
 */
internal class CollectiveInfo(
    c: Context,
    p: Palette,
    names: Array<String>,
    private val colors: IntArray,
    cb: OnSelect,
) : ViewGroup(c), RevealHost.InfoBar {

    /** Told which panel key was tapped (also used by [RevealInfo]). */
    fun interface OnSelect {
        fun select(index: Int)
    }

    private val d: Float = c.resources.displayMetrics.density
    private val line: Int = p.c(0)
    private val touch: Array<View>
    private val labels: Array<TextView>
    private val x = FloatArray(names.size)
    private val y = FloatArray(names.size)
    private var active = -1

    init {
        setWillNotDraw(false)
        touch = Array(names.size) { i ->
            View(c).apply {
                background = Ui.touchArea(c, 30f)
                contentDescription = names[i] + " panel"
                setOnClickListener { v ->
                    Ui.tap(v)
                    cb.select(i)
                }
            }
        }
        labels = Array(names.size) { i ->
            Ui.oneLine(Ui.text(c, names[i], 10f, Ui.alpha(Skin.ink(), 0.8f))).apply {
                gravity = Gravity.CENTER
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        // same child order as the Java edition: touch target, then its label, per node
        for (i in names.indices) {
            addView(touch[i])
            addView(labels[i])
        }
    }

    override fun setActive(index: Int) {
        active = index
        for (k in labels.indices) labels[k].setTextColor(if (k == index) Skin.glow(colors[k]) else Ui.alpha(Skin.ink(), 0.8f))
        invalidate()
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        val h = MeasureSpec.getSize(hs)
        val n = touch.size
        val cell = if (n == 0) w else w / n
        val tz = Math.round(TOUCH_DP * d)
        for (i in 0 until n) {
            touch[i].measure(MeasureSpec.makeMeasureSpec(tz, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tz, MeasureSpec.EXACTLY))
            labels[i].measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val n = touch.size
        for (i in 0 until n) {
            x[i] = w * (i + 0.5f) / n
            y[i] = ROW_Y_DP * d + (if (i % 2 == 1) STAGGER_DP * d else 0f) // slight stagger: an assembly, not a toolbar
            val tz = touch[i].measuredWidth
            touch[i].layout(
                Math.round(x[i] - tz / 2f), Math.round(y[i] - tz / 2f),
                Math.round(x[i] + tz / 2f), Math.round(y[i] + tz / 2f),
            )
            val lab = labels[i]
            val lw = lab.measuredWidth
            val lh = lab.measuredHeight
            val top = Math.round(y[i] + LABEL_GAP_DP * d)
            lab.layout(Math.round(x[i] - lw / 2f), top, Math.round(x[i] - lw / 2f) + lw, top + lh)
        }
    }

    override fun dispatchDraw(cv: Canvas) {
        val n = touch.size
        val w = width
        if (n > 0) {
            // trace up into the hub's crosshair, then along the row through every node
            val mid = n / 2
            CollectiveDraw.curveTrace(cv, w / 2f, 0f, x[mid], y[mid] - 8 * d, 0.1f, line, d, 0.6f)
            for (i in 0 until n - 1) {
                CollectiveDraw.curveTrace(cv, x[i] + 8 * d, y[i], x[i + 1] - 8 * d, y[i + 1], if (i % 2 == 0) 0.12f else -0.12f, line, d, 0.7f)
            }
            CollectiveDraw.via(cv, 6 * d, y[0], 2.5f * d, line, d, 0.8f)
            CollectiveDraw.curveTrace(cv, 9 * d, y[0], x[0] - 8 * d, y[0], 0f, line, d, 0.6f)
            CollectiveDraw.via(cv, w - 6 * d, y[n - 1], 2.5f * d, line, d, 0.8f)
            CollectiveDraw.curveTrace(cv, x[n - 1] + 8 * d, y[n - 1], w - 9 * d, y[n - 1], 0f, line, d, 0.6f)
            for (i in 0 until n) {
                val col = Skin.glow(colors[i])
                if (i == active) {
                    CollectiveDraw.node(cv, x[i], y[i], 9 * d, col, d, 1f)
                } else {
                    CollectiveDraw.ringNode(cv, x[i], y[i], 8 * d, col, d, 0.85f)
                }
            }
        }
        super.dispatchDraw(cv)
    }

    private companion object {
        /** Touch target size, node row height, odd-node stagger and label offset, in dp. */
        const val TOUCH_DP = 40f
        const val ROW_Y_DP = 22f
        const val STAGGER_DP = 7f
        const val LABEL_GAP_DP = 12f
    }
}
