package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.max
import kotlin.math.min

/**
 * Drawing vocabulary for the Collective interface, after the on-screen Collective displays: glowing circular
 * nodes with concentric rings, joined by thin organic traces that end in small ring vias, with
 * strings of tiny dots for data. No hard edges, no rectangles, no elbows.
 * All calls happen on the UI thread, so one shared Paint is enough.
 *
 * Java interop: every function is [JvmStatic], so Java keeps calling `CollectiveDraw.node(...)`.
 */
object CollectiveDraw {
    private val P = Paint(Paint.ANTI_ALIAS_FLAG)
    private val TMP = Path()

    /** A trace: soft glow underneath, crisp hairline on top. */
    @JvmStatic
    fun trace(cv: Canvas, path: Path, color: Int, d: Float, alpha: Float) {
        P.shader = null
        P.style = Paint.Style.STROKE
        P.strokeCap = Paint.Cap.ROUND
        P.strokeJoin = Paint.Join.ROUND
        if (!Ui.cloak) {
            P.strokeWidth = 4.5f * d
            P.color = Ui.alpha(color, 0.16f * alpha)
            cv.drawPath(path, P)
        }
        P.strokeWidth = 1.3f * d
        P.color = Ui.alpha(color, (if (Ui.cloak) 0.55f else 0.9f) * alpha)
        cv.drawPath(path, P)
    }

    /** A trace with a chosen core weight (dp): spokes of different importance. */
    @JvmStatic
    fun traceW(cv: Canvas, path: Path, color: Int, d: Float, alpha: Float, widthDp: Float) {
        P.shader = null
        P.style = Paint.Style.STROKE
        P.strokeCap = Paint.Cap.ROUND
        P.strokeJoin = Paint.Join.ROUND
        if (!Ui.cloak) {
            P.strokeWidth = (widthDp + 3.2f) * d
            P.color = Ui.alpha(color, 0.15f * alpha)
            cv.drawPath(path, P)
        }
        P.strokeWidth = widthDp * d
        P.color = Ui.alpha(color, (if (Ui.cloak) 0.55f else 0.9f) * alpha)
        cv.drawPath(path, P)
    }

    /** Gentle S-curve from (x0,y0) to (x1,y1); bend sets which way it bows. */
    @JvmStatic
    fun curve(x0: Float, y0: Float, x1: Float, y1: Float, bend: Float): Path {
        val p = Path()
        val dx = x1 - x0
        val dy = y1 - y0
        p.moveTo(x0, y0)
        p.cubicTo(x0 + dx * 0.35f - dy * bend, y0 + dy * 0.35f + dx * bend,
            x0 + dx * 0.65f + dy * bend, y0 + dy * 0.65f - dx * bend, x1, y1)
        return p
    }

    /** [trace] along [curve]. */
    @JvmStatic
    fun curveTrace(cv: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, bend: Float, color: Int, d: Float, alpha: Float) {
        trace(cv, curve(x0, y0, x1, y1, bend), color, d, alpha)
    }

    /** A via: small ring with a dark core. */
    @JvmStatic
    fun via(cv: Canvas, x: Float, y: Float, r: Float, color: Int, d: Float, alpha: Float) {
        P.shader = null
        P.style = Paint.Style.FILL
        P.color = Skin.bg()
        cv.drawCircle(x, y, r, P)
        P.style = Paint.Style.STROKE
        P.strokeWidth = 1.2f * d
        P.color = Ui.alpha(color, (if (Ui.cloak) 0.55f else 0.95f) * alpha)
        cv.drawCircle(x, y, r, P)
    }

    /**
     * A node: luminous sphere with a soft halo and concentric rings. While cloaked only the rings
     * are drawn.
     */
    @JvmStatic
    fun node(cv: Canvas, x: Float, y: Float, r: Float, color: Int, d: Float, alpha: Float) {
        if (r < 1) return
        P.style = Paint.Style.FILL
        if (!Ui.cloak) {
            val core = Skin.mix(color, Color.WHITE, 0.45f)
            val edge = color
            P.shader = RadialGradient(x, y, r * 1.45f,
                intArrayOf(Ui.alpha(core, alpha), Ui.alpha(edge, 0.92f * alpha), Ui.alpha(edge, 0.85f * alpha),
                    Ui.alpha(edge, 0.18f * alpha), Ui.alpha(edge, 0f)),
                floatArrayOf(0f, 0.45f, 0.68f, 0.72f, 1f), Shader.TileMode.CLAMP)
            cv.drawCircle(x, y, r * 1.45f, P)
            P.shader = null
        } else {
            P.color = Color.BLACK
            cv.drawCircle(x, y, r, P)
        }
        P.style = Paint.Style.STROKE
        P.strokeWidth = max(1f, 1.2f * d)
        P.color = if (Ui.cloak) Ui.alpha(color, 0.6f * alpha) else Ui.alpha(Skin.mix(color, Color.WHITE, 0.3f), 0.9f * alpha)
        cv.drawCircle(x, y, r, P)
        if (r > 6 * d) {
            P.color = Ui.alpha(if (Ui.cloak) color else Skin.mix(color, Color.BLACK, 0.35f),
                (if (Ui.cloak) 0.45f else 0.55f) * alpha)
            cv.drawCircle(x, y, r * 0.64f, P)
        }
    }

    /** A hollow target node: rings only (used for hubs and satellites that must not hide text). */
    @JvmStatic
    fun ringNode(cv: Canvas, x: Float, y: Float, r: Float, color: Int, d: Float, alpha: Float) {
        P.shader = null
        P.style = Paint.Style.STROKE
        P.strokeWidth = 1.4f * d
        val a = if (Ui.cloak) 0.5f else 1f
        P.color = Ui.alpha(color, 0.85f * alpha * a)
        cv.drawCircle(x, y, r, P)
        P.strokeWidth = d
        P.color = Ui.alpha(color, 0.5f * alpha * a)
        cv.drawCircle(x, y, r * 0.7f, P)
        cv.drawCircle(x, y, r * 0.38f, P)
        P.style = Paint.Style.FILL
        P.color = Ui.alpha(color, 0.8f * alpha * a)
        cv.drawCircle(x, y, max(1.5f * d, r * 0.12f), P)
    }

    /** A row of tiny data dots; how many are bright depends on seed. */
    @JvmStatic
    fun dots(cv: Canvas, x: Float, y: Float, n: Int, color: Int, d: Float, seed: Int, alpha: Float) {
        P.shader = null
        P.style = Paint.Style.FILL
        val r = 1.5f * d
        val step = 4.6f * d
        for (i in 0 until n) {
            val lit = ((seed shr (i % 8)) and 1) == 1 || i == 0
            P.color = Ui.alpha(color, (if (lit) 0.9f else 0.3f) * alpha * (if (Ui.cloak) 0.6f else 1f))
            cv.drawCircle(x + i * step, y, r, P)
        }
    }

    /** Soft rounded plate for small controls (dialog buttons, chips): no corners, faint edge. */
    @JvmStatic
    fun plate(cv: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, d: Float) {
        val rad = min((b - t) / 2f, 22 * d)
        P.shader = null
        P.style = Paint.Style.FILL
        P.color = if (Ui.cloak) Color.BLACK else Skin.mix(Skin.surface(), color, 0.14f)
        cv.drawRoundRect(l, t, r, b, rad, rad, P)
        P.style = Paint.Style.STROKE
        P.strokeWidth = 1.2f * d
        P.color = Ui.alpha(Skin.glow(color), if (Ui.cloak) 0.5f else 0.45f)
        val h = 0.6f * d
        cv.drawRoundRect(l + h, t + h, r - h, b - h, rad, rad, P)
    }

    /** A shared scratch path, reset; valid until the next call (UI thread only). */
    @JvmStatic
    fun tmp(): Path {
        TMP.reset()
        return TMP
    }

    /**
     * The home tile column for Collective: draws S-curved traces linking each tile's node to the next
     * (behind the tiles, which have no background), so the list reads as one connected network.
     * Node position matches NodeCell: 32 dp in from the tile's left, vertically centred.
     */
    class TraceColumn(c: Context, private val color: Int) : LinearLayout(c) {
        private val d: Float = c.resources.displayMetrics.density

        init {
            setWillNotDraw(false)
        }

        override fun dispatchDraw(cv: Canvas) {
            var px = -1f
            var py = -1f
            val nr = 18 * d
            val n = childCount
            for (i in 0 until n) {
                val row = getChildAt(i)
                if (row !is ViewGroup || row.childCount == 0) continue
                val tile = row.getChildAt(0)
                val x = row.left + tile.left + 32 * d
                val y = row.top + row.height / 2f
                if (px < 0) {
                    val p = tmp()
                    p.moveTo(x, y - nr)
                    p.lineTo(x, max(3 * d, y - nr - 14 * d))
                    trace(cv, p, color, d, 0.6f)
                    via(cv, x, max(3 * d, y - nr - 14 * d), 3 * d, color, d, 0.8f)
                } else {
                    val p = tmp()
                    val dy = (y - nr) - (py + nr)
                    p.moveTo(px, py + nr)
                    p.cubicTo(px, py + nr + dy * 0.6f, x, y - nr - dy * 0.6f, x, y - nr)
                    trace(cv, p, color, d, 0.6f)
                }
                px = x
                py = y
            }
            if (px >= 0) {
                val p = tmp()
                p.moveTo(px, py + nr)
                p.quadTo(px, py + nr + 18 * d, px + 22 * d, py + nr + 22 * d)
                trace(cv, p, color, d, 0.5f)
                via(cv, px + 22 * d, py + nr + 22 * d, 3 * d, color, d, 0.7f)
            }
            super.dispatchDraw(cv)
        }
    }
}
