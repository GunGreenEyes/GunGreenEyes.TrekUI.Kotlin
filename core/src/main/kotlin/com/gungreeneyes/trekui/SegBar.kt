package com.gungreeneyes.trekui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Segmented console level bar (battery, storage, memory). Segments fill left to right; the end segments
 * are pill-rounded. Optional pulse on the leading segment (e.g. while charging), only when animations
 * are allowed. Sizes are relative to the view, so it scales with whatever space it is given.
 */
open class SegBar(c: Context, private val segments: Int, private var color: Int, private val emptyColor: Int) : View(c) {
    private var fraction = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var pulse: ValueAnimator? = null
    private var pulseA = 1f

    /** Sets the fill fraction (clamped to 0..1) and its colour. */
    fun set(f: Float, color: Int) {
        fraction = max(0f, min(1f, f))
        this.color = color
        invalidate()
    }

    /** Starts or stops pulsing the leading segment. */
    fun setPulse(on: Boolean) {
        if (on && pulse == null && Ui.animate(context)) {
            val a = ValueAnimator.ofFloat(0.15f, 1f)
            a.setDuration(700)
            a.repeatMode = ValueAnimator.REVERSE
            a.repeatCount = ValueAnimator.INFINITE
            a.addUpdateListener { va ->
                pulseA = va.animatedValue as Float
                invalidate()
            }
            pulse = a
            a.start()
        } else if (!on && pulse != null) {
            pulse?.cancel()
            pulse = null
            pulseA = 1f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        setPulse(false)
        super.onDetachedFromWindow()
    }

    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = max(2f, h * 0.22f)
        val sw = (w - gap * (segments - 1)) / segments
        val filled = filledSegments(fraction, segments)
        for (i in 0 until segments) {
            val x = i * (sw + gap)
            r.set(x, 0f, x + sw, h)
            val on = i < filled
            val lead = pulse != null && i == min(filled, segments - 1)
            var col = if (on) color else emptyColor
            if (lead) col = Ui.alpha(color, pulseA)
            p.color = col
            val rad = if (i == 0 || i == segments - 1) h / 2f else h * 0.12f
            cv.drawRoundRect(r, rad, rad, p)
        }
    }

    companion object {
        /** Segments lit for `fraction`: rounded, but at least one for any non-zero fraction. */
        @JvmStatic
        fun filledSegments(fraction: Float, segments: Int): Int {
            var filled = Math.round(fraction * segments)
            if (fraction > 0 && filled == 0) filled = 1
            return filled
        }
    }
}
