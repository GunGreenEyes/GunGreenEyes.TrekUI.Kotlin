package com.gungreeneyes.trekui.keyboard

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Original line-art glyphs for the function keys (shift, delete, return, globe, emoji, microphone), drawn
 * with paths in the key's lettering colour so they match every interface. Each glyph fits a square of
 * side `s` centred on (`cx`, `cy`).
 */
internal class KeyIcons {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()
    private val r = RectF()

    private fun prepare(color: Int, s: Float) {
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = maxOf(1.5f, s * 0.085f)
    }

    /** Up arrow; filled when shifted; with a bar underneath when caps lock is on. */
    fun shift(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, on: Boolean, locked: Boolean) {
        prepare(color, s)
        val h = s * 0.5f
        val w = s * 0.42f
        val top = cy - h * 0.62f
        val mid = cy + h * 0.02f
        val bot = cy + h * 0.42f
        path.reset()
        path.moveTo(cx, top)
        path.lineTo(cx + w, mid)
        path.lineTo(cx + w * 0.45f, mid)
        path.lineTo(cx + w * 0.45f, bot)
        path.lineTo(cx - w * 0.45f, bot)
        path.lineTo(cx - w * 0.45f, mid)
        path.lineTo(cx - w, mid)
        path.close()
        c.drawPath(path, if (on) fill else stroke)
        if (on) c.drawPath(path, stroke)
        if (locked) c.drawLine(cx - w * 0.5f, bot + h * 0.3f, cx + w * 0.5f, bot + h * 0.3f, stroke)
    }

    /** A left-pointing tag with an x. */
    fun delete(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        prepare(color, s)
        val w = s * 0.62f
        val h = s * 0.42f
        val l = cx - w / 2f
        val rr = cx + w / 2f
        val t = cy - h / 2f
        val b = cy + h / 2f
        path.reset()
        path.moveTo(l, cy)
        path.lineTo(l + h * 0.55f, t)
        path.lineTo(rr, t)
        path.lineTo(rr, b)
        path.lineTo(l + h * 0.55f, b)
        path.close()
        c.drawPath(path, stroke)
        val xc = (l + h * 0.55f + rr) / 2f + w * 0.03f
        val d = h * 0.2f
        c.drawLine(xc - d, cy - d, xc + d, cy + d, stroke)
        c.drawLine(xc - d, cy + d, xc + d, cy - d, stroke)
    }

    /** A return arrow (down then left). */
    fun enter(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        prepare(color, s)
        val w = s * 0.56f
        val h = s * 0.34f
        val rr = cx + w / 2f
        val l = cx - w / 2f
        val t = cy - h * 0.7f
        val b = cy + h * 0.35f
        path.reset()
        path.moveTo(rr, t)
        path.lineTo(rr, b)
        path.lineTo(l, b)
        c.drawPath(path, stroke)
        val a = h * 0.42f
        c.drawLine(l, b, l + a, b - a, stroke)
        c.drawLine(l, b, l + a, b + a, stroke)
    }

    /** A globe: circle, equator, two meridian arcs. */
    fun globe(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        prepare(color, s)
        val rad = s * 0.3f
        c.drawCircle(cx, cy, rad, stroke)
        c.drawLine(cx - rad, cy, cx + rad, cy, stroke)
        r.set(cx - rad * 0.45f, cy - rad, cx + rad * 0.45f, cy + rad)
        c.drawOval(r, stroke)
    }

    /** A smiling face. */
    fun emoji(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        prepare(color, s)
        val rad = s * 0.3f
        c.drawCircle(cx, cy, rad, stroke)
        val e = rad * 0.13f
        c.drawCircle(cx - rad * 0.36f, cy - rad * 0.25f, e, fill)
        c.drawCircle(cx + rad * 0.36f, cy - rad * 0.25f, e, fill)
        r.set(cx - rad * 0.5f, cy - rad * 0.3f, cx + rad * 0.5f, cy + rad * 0.55f)
        c.drawArc(r, 20f, 140f, false, stroke)
    }

    /** A microphone. */
    fun mic(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        prepare(color, s)
        val w = s * 0.16f
        val h = s * 0.32f
        r.set(cx - w, cy - h, cx + w, cy + h * 0.25f)
        c.drawRoundRect(r, w, w, stroke)
        r.set(cx - w * 1.9f, cy - h * 0.55f, cx + w * 1.9f, cy + h * 0.6f)
        c.drawArc(r, 0f, 180f, false, stroke)
        c.drawLine(cx, cy + h * 0.6f, cx, cy + h, stroke)
    }
}
