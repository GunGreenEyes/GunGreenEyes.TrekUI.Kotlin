package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Start-up sequences of the two Command eras, drawn by [BootSequence] on its scaled
 * portrait stage (so they are responsive the same way: shrunk on short screens, grown on tablets).
 *
 * - **23rd century**: the console powers up. The bar and sidebar blocks slam in, a bank of
 *   chunky keys switches on one by one with their lamps, a chaser runs along the lamp row,
 *   circuits report ON, and ALL CIRCUITS GREEN.
 * - **25th century**: fine lines draw themselves. The thin elbows trace their outlines, a
 *   tick rule grows along the bar, layered glass panels slide in, a reticle turns while the
 *   subsystems report NOMINAL, then SYSTEMS NOMINAL.
 *
 * Timings are in ms from the start of the sequence; [durationFor] / [fadeAtFor] give
 * the overall length and fade point (unit-tested).
 */
class EraBoot(c: Context, private val pal: Palette, private val header: String, private val faction: Int) {
    private val d: Float = c.resources.displayMetrics.density
    private val textScale: Float = Skin.textScale()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tl = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val part = Path()
    private val r = RectF()

    /** Order in which the 23rd-century keys switch on (fixed scramble). */
    private val keyOrder = IntArray(KEY_COLS * KEY_ROWS) { it }

    private var shownDone = false

    init {
        tp.typeface = Ui.font(c, true)
        tl.typeface = Ui.font(c, false)
        for (i in keyOrder.size - 1 downTo 1) {
            val j = Era.hash(i * 977) % (i + 1)
            val s = keyOrder[i]
            keyOrder[i] = keyOrder[j]
            keyOrder[j] = s
        }
    }

    fun duration(): Long = durationFor(faction)

    fun fadeAt(): Long = fadeAtFor(faction)

    private fun dp(v: Float): Float = v * d

    private fun ts(v: Float): Float = v * d * textScale

    /**
     * Draws the sequence at `t` ms on a `w` x `h` stage.
     *
     * @param al overall alpha (0..1) while fading out
     * @return true the moment the final status line first appears (the caller plays its chirp)
     */
    fun draw(cv: Canvas, t: Long, w: Int, h: Int, al: Float): Boolean =
        if (faction == Skin.ERA23) console(cv, t, w, h, al) else glassBoot(cv, t, w, h, al)

    // ==== 23rd century: the console powers up ==========================================================

    private fun console(cv: Canvas, t: Long, w: Int, h: Int, al: Float): Boolean {
        val side = dp(56f)
        val bar = dp(20f)
        val top = dp(46f)
        val gap = dp(6f)
        // 1. bar and sidebar blocks slam in
        val g = step(t, 0, 280)
        if (g > 0) {
            EraDraw.block23(cv, 0f, top, w * 0.62f * g, top + bar, Ui.alpha(pal.c(0), al), d, false)
            val g2 = step(t, 140, 380)
            if (g2 > 0) {
                EraDraw.block23(
                    cv, w * 0.62f + gap, top, w * 0.62f + gap + (w - w * 0.62f - gap - dp(10f)) * g2, top + bar,
                    Ui.alpha(pal.c(1), al), d, false,
                )
            }
        }
        val sideCols = intArrayOf(pal.c(1), pal.c(2), pal.c(3), pal.c(4), pal.c(5))
        val sTop = top + bar + gap
        val sBot = h - dp(40f)
        val sh = (sBot - sTop) / sideCols.size
        for (i in sideCols.indices) {
            val e = step(t, 120 + i * 70L, 300 + i * 70L)
            if (e <= 0) continue
            val y = sTop + i * sh
            EraDraw.block23(cv, -side * (1 - e), y, side - side * (1 - e), y + sh - gap, Ui.alpha(sideCols[i], al), d, false)
        }
        val x0 = side + dp(20f)
        val right = w - dp(16f)
        // 2. title block
        val tf = step(t, 420, 760)
        if (tf > 0) {
            tp.textAlign = Paint.Align.RIGHT
            tp.textSize = ts(52f)
            tp.color = Ui.alpha(pal.c(0), al * tf)
            cv.drawText(Skin.title(), right, top + bar + dp(64f), tp)
            tl.textAlign = Paint.Align.RIGHT
            tl.textSize = ts(15f)
            tl.color = Ui.alpha(pal.textLo, al * tf)
            cv.drawText(Skin.library(), right, top + bar + dp(90f), tl)
            tl.color = Ui.alpha(pal.c(2), al * tf)
            cv.drawText(header, right, top + bar + dp(110f), tl)
        }
        // 3. the key bank switches on, key by key
        val gy = top + bar + dp(132f)
        val gh = min(dp(150f), (h - gy) * 0.34f)
        val kw = (right - x0 - (KEY_COLS - 1) * gap) / KEY_COLS
        val kh = (gh - (KEY_ROWS - 1) * gap) / KEY_ROWS
        for (n in keyOrder.indices) {
            val k = keyOrder[n]
            val at = 600 + n * 70L
            if (t < at) continue
            val l = x0 + (k % KEY_COLS) * (kw + gap)
            val tt = gy + (k / KEY_COLS) * (kh + gap)
            val on = t > at + 90
            val col = if (on) pal.c(k) else Ui.darken(pal.c(k), 0.25f)
            EraDraw.key23(cv, l, tt, l + kw, tt + kh, Ui.alpha(col, al), Ui.SQUARE, d, k + 1, if (on) t else -1L, false)
        }
        // 4. lamp chaser under the bank
        val ly = gy + gh + dp(20f)
        if (t > 900) {
            val ls = min(dp(10f), (right - x0) / (CHASER_LAMPS * 1.6f))
            val stepX = (right - x0 - ls) / (CHASER_LAMPS - 1)
            val lit = ((t / 90) % CHASER_LAMPS).toInt()
            for (i in 0 until CHASER_LAMPS) {
                val on = t > 2300 || i == lit || i == (lit + CHASER_LAMPS / 2) % CHASER_LAMPS
                EraDraw.lamp(cv, x0 + ls / 2 + i * stepX, ly, ls, Era.LAMPS[i % Era.LAMPS.size], on, d, al)
            }
        }
        // 5. circuits report ON
        tp.textSize = ts(17f)
        val ry = ly + dp(40f)
        for (i in ERA23_LINES.indices) {
            val at = 1150 + i * 200L
            if (t < at) break
            val y = ry + i * dp(28f)
            if (y > h - dp(90f)) break
            tl.textAlign = Paint.Align.LEFT
            tl.textSize = ts(17f)
            tl.color = Ui.alpha(pal.textHi, al)
            cv.drawText(ERA23_LINES[i], x0, y, tl)
            val ok = t > at + 150
            EraDraw.lamp(cv, right - dp(44f), y - dp(6f), dp(9f), if (ok) Era.LAMPS[2] else Era.LAMPS[1], true, d, al)
            tp.textAlign = Paint.Align.RIGHT
            tp.color = Ui.alpha(if (ok) Era.LAMPS[2] else Era.LAMPS[1], al)
            cv.drawText(if (ok) "ON" else "…", right, y, tp)
        }
        // 6. all green
        val on = step(t, 2300, 2480)
        var first = false
        if (on > 0) {
            if (!shownDone) {
                shownDone = true
                first = true
            }
            tp.textAlign = Paint.Align.RIGHT
            tp.textSize = ts(34f)
            tp.color = Ui.alpha(Era.LAMPS[2], al * on)
            cv.drawText("ALL CIRCUITS GREEN", right, h - dp(56f), tp)
        }
        return first
    }

    // ==== 25th century: fine lines draw themselves =====================================================

    private fun glassBoot(cv: Canvas, t: Long, w: Int, h: Int, al: Float): Boolean {
        val side = dp(48f)
        val bar = dp(12f)
        val top = dp(48f)
        val bottom = h - dp(34f)
        val depth = dp(120f)
        // 1. the thin elbows trace their outlines
        val tr = step(t, 0, 700)
        val tr2 = step(t, 150, 850)
        traced(cv, top, side, bar, depth, w * 0.9f, pal.c(2), tr, al, false)
        traced(cv, bottom, side, bar, depth, w * 0.7f, pal.c(1), tr2, al, true)
        // once traced, the glass fills in
        val fill = step(t, 650, 950)
        if (fill > 0) {
            cv.save()
            cv.translate(0f, top)
            EraDraw.frameCorner25(cv, d, Ui.alpha(pal.c(2), al * fill), side, bar, depth, w * 0.9f, dp(14f), dp(40f), false)
            cv.restore()
            cv.save()
            cv.translate(0f, bottom)
            cv.scale(1f, -1f)
            EraDraw.frameCorner25(cv, d, Ui.alpha(pal.c(1), al * fill), side, bar, depth, w * 0.7f, dp(14f), dp(40f), false)
            cv.restore()
        }
        val x0 = side + dp(22f)
        val right = w - dp(16f)
        // 2. reticle turning behind the title
        val rc = step(t, 300, 900)
        if (rc > 0) {
            val cx = x0 + dp(70f)
            val cy = top + bar + dp(78f)
            val rad = dp(52f) * (0.7f + 0.3f * rc)
            p.style = Paint.Style.STROKE
            p.strokeWidth = max(1f, d)
            val spin = t * 0.05f
            for (i in 0 until 3) {
                val rr = rad - i * dp(12f)
                r.set(cx - rr, cy - rr, cx + rr, cy + rr)
                p.color = Ui.alpha(pal.c(i), al * rc * (0.75f - i * 0.15f))
                val dir = if (i % 2 == 0) 1f else -1f
                for (s in 0 until 3) cv.drawArc(r, dir * spin + s * 120 + i * 25, (70 - i * 12).toFloat(), false, p)
            }
            p.color = Ui.alpha(pal.textLo, al * rc * 0.5f)
            cv.drawLine(cx - rad - dp(10f), cy, cx + rad + dp(10f), cy, p)
            cv.drawLine(cx, cy - rad - dp(10f), cx, cy + rad + dp(10f), p)
            p.style = Paint.Style.FILL
        }
        // 3. title, thin and wide
        val tf = step(t, 500, 900)
        if (tf > 0) {
            tl.textAlign = Paint.Align.RIGHT
            tl.textSize = ts(60f)
            tl.color = Ui.alpha(pal.textHi, al * tf)
            cv.drawText(Skin.title(), right, top + bar + dp(78f), tl)
            tl.textSize = ts(14f)
            tl.color = Ui.alpha(pal.c(0), al * tf)
            cv.drawText(Skin.library(), right, top + bar + dp(102f), tl)
            tl.color = Ui.alpha(pal.textLo, al * tf)
            cv.drawText(header, right, top + bar + dp(122f), tl)
        }
        // 4. layered glass panels slide in from the right, each a step behind the last
        val py = top + bar + dp(150f)
        val ph = dp(44f)
        for (i in 0 until 3) {
            val e = step(t, 700 + i * 120L, 1150 + i * 120L)
            if (e <= 0) continue
            val off = (1 - e) * (w * 0.6f)
            val l = x0 + i * dp(14f) + off
            val tt = py + i * dp(10f)
            EraDraw.glass(cv, l, tt, right - (2 - i) * dp(8f) + off, tt + ph, Ui.alpha(pal.c(i + 1), al * e), Ui.SQUARE, d, false)
        }
        // 5. subsystems with fine rules, NOMINAL
        val ry = py + ph + dp(70f)
        for (i in ERA25_LINES.indices) {
            val at = 1250 + i * 190L
            if (t < at) break
            val y = ry + i * dp(30f)
            if (y > bottom - dp(90f)) break
            val lf = step(t, at, at + 160)
            tl.textAlign = Paint.Align.LEFT
            tl.textSize = ts(16f)
            tl.color = Ui.alpha(pal.textHi, al * lf)
            cv.drawText(ERA25_LINES[i], x0, y, tl)
            val ok = t > at + 170
            tl.textAlign = Paint.Align.RIGHT
            tl.color = Ui.alpha(if (ok) pal.c(0) else pal.c(4), al * lf)
            cv.drawText(if (ok) String.format(Locale.US, "NOMINAL %02d", 98 - i * 3) else "…", right, y, tl)
            p.style = Paint.Style.STROKE
            p.strokeWidth = max(1f, 0.7f * d)
            p.color = Ui.alpha(pal.c(2), al * lf * 0.45f)
            cv.drawLine(x0, y + dp(8f), x0 + (right - x0) * lf, y + dp(8f), p)
            p.style = Paint.Style.FILL
        }
        // 6. systems nominal
        val on = step(t, 2400, 2600)
        var first = false
        if (on > 0) {
            if (!shownDone) {
                shownDone = true
                first = true
            }
            tl.textAlign = Paint.Align.RIGHT
            tl.textSize = ts(34f)
            tl.color = Ui.alpha(pal.c(0), al * on)
            cv.drawText("SYSTEMS NOMINAL", right, bottom - bar - dp(30f), tl)
        }
        return first
    }

    /**
     * The outline of a thin elbow at `y`, drawn up to `fraction` of its length (the
     * frame "draws itself"), plus a tick rule that grows along the bar.
     */
    private fun traced(
        cv: Canvas, y: Float, side: Float, bar: Float, depth: Float, barEnd: Float, color: Int, fraction: Float, al: Float,
        flip: Boolean,
    ) {
        if (fraction <= 0) return
        val cut = min(dp(22f), side * 0.5f)
        val inner = dp(11f)
        path.reset()
        path.moveTo(0f, depth)
        path.lineTo(0f, cut)
        path.lineTo(cut, 0f)
        path.lineTo(barEnd, 0f)
        path.lineTo(barEnd, bar)
        path.lineTo(side + inner, bar)
        path.lineTo(side, bar + inner)
        path.lineTo(side, depth)
        val pm = PathMeasure(path, false)
        part.reset()
        pm.getSegment(0f, pm.length * clamp(fraction), part, true)
        cv.save()
        cv.translate(0f, y)
        if (flip) cv.scale(1f, -1f)
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, 1.2f * d)
        p.color = Ui.alpha(color, al)
        cv.drawPath(part, p)
        p.style = Paint.Style.FILL
        EraDraw.ticks(
            cv, side + inner + dp(6f), side + inner + dp(6f) + (barEnd - side - inner - dp(12f)) * fraction, bar + dp(4f), false,
            color, d, 0.6f * al,
        )
        cv.restore()
    }

    companion object {
        /** Sequence length and fade start per era, in ms. */
        const val ERA23_DURATION = 3300L
        const val ERA23_FADE = 2850L
        const val ERA25_DURATION = 3400L
        const val ERA25_FADE = 2950L
        private val ERA23_LINES = arrayOf("MAIN POWER", "SENSOR CIRCUITS", "COMMUNICATIONS", "COMPUTER CORE", "HELM CONTROL")
        private val ERA25_LINES = arrayOf("QUANTUM DATA CORE", "SENSOR LATTICE", "SUBSPACE RELAY", "FLEET UPLINK", "PERSONAL TERMINAL")

        /** Key grid of the 23rd-century sequence. */
        private const val KEY_COLS = 4
        private const val KEY_ROWS = 3

        /** Lamps in the chaser row. */
        private const val CHASER_LAMPS = 12

        /** Total length of the sequence for an era faction, in ms. */
        @JvmStatic
        fun durationFor(faction: Int): Long = if (faction == Skin.ERA23) ERA23_DURATION else ERA25_DURATION

        /** When the sequence starts fading to the home screen, in ms. */
        @JvmStatic
        fun fadeAtFor(faction: Int): Long = if (faction == Skin.ERA23) ERA23_FADE else ERA25_FADE

        private fun clamp(v: Float): Float = if (v < 0) 0f else if (v > 1) 1f else v

        /** Ease-out progress of a step running from `a` to `b` ms. */
        @JvmStatic
        fun step(t: Long, a: Long, b: Long): Float {
            val x = clamp((t - a) / (b - a).toFloat())
            return 1 - (1 - x) * (1 - x)
        }
    }
}
