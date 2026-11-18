package com.gungreeneyes.trekui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import java.util.Random

/**
 * Static procedural starfield background: a near-black sky, two faint nebula glows in palette
 * colours and stars at a density proportional to the area. Rendered once per size to an RGB_565
 * bitmap (no ongoing redraw cost); the fixed seed keeps the sky identical across rebuilds.
 */
class Starfield(private val pal: Palette) : Drawable() {
    private var cache: Bitmap? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val old = cache
        if (old != null && old.width == bounds.width() && old.height == bounds.height()) return
        old?.recycle() // size changed (rotation, resize): free the old sky
        val sky = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.RGB_565)
        cache = sky
        val c = Canvas(sky)
        c.drawColor(Color.rgb(3, 3, 8))
        val w = bounds.width()
        val h = bounds.height()
        p.shader = RadialGradient(w * 0.8f, h * 0.3f, w * 0.9f, Ui.alpha(pal.c(5), 0.16f), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.15f, h * 0.78f, w * 0.8f, Ui.alpha(pal.c(2), 0.12f), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        val r = Random(SEED)
        val n = (w * h) / PIXELS_PER_STAR
        for (i in 0 until n) {
            val x = r.nextFloat() * w
            val y = r.nextFloat() * h
            val s = r.nextFloat()
            val rad = if (s < 0.92f) 0.6f + s * 1.1f else 1.8f + r.nextFloat() * 1.4f
            val a = 90 + r.nextInt(165)
            val tint = if (r.nextInt(10) == 0) pal.c(r.nextInt(4)) else Color.WHITE
            p.color = Ui.alpha(tint, a / 255f)
            c.drawCircle(x, y, rad, p)
        }
    }

    override fun draw(canvas: Canvas) {
        if (cache == null) onBoundsChange(bounds)
        cache?.let { canvas.drawBitmap(it, bounds.left.toFloat(), bounds.top.toFloat(), null) }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java") // still abstract in Drawable; required by every subclass
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        /** Seed for the star positions (the same sky every time). */
        const val SEED = 1701L
        /** One star per this many square pixels. */
        const val PIXELS_PER_STAR = 5200
    }
}
