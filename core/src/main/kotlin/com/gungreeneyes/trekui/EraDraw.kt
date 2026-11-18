package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min

/**
 * Drawing for the two extra Command eras ([Skin.ERA23], [Skin.ERA25]). Every
 * Command screen keeps its structure (frames, cascade, drawer, dialogs); the pieces it asks
 * `Ui`, [Skin], `ConsoleFrame`, `ConsoleCard`, `CommandCascade` and
 * `IconFactory` for are drawn here instead:
 *
 * - **23rd century**: square, chunky keys in flat primary colours with a bevel (light top
 *   edge, dark bottom edge, darker end cap on LEFT/RIGHT keys) and small square indicator
 *   lamps, about a third of which blink. Frames have no elbows: the bar and the sidebar are
 *   separate blocks with lamps in the bar.
 * - **25th century**: thin translucent glass. 1dp outlines with 45° cut corners, a narrow
 *   solid accent strip on the leading edge, a faint inset second layer and fine tick rules.
 *
 * Sizes come from `Ui.density` (so they follow the window zoom); the geometry and lamp
 * timing rules are the pure functions in [Era]. Lamps blink only while the view is drawn
 * and animations are on (`Ui.animate`); each blinking drawable reschedules itself for its
 * next toggle, so a static console costs nothing.
 *
 * Java interop: every entry point is [JvmStatic], so Java keeps calling `EraDraw.preview(...)`.
 */
object EraDraw {
    /** Shared paint and path for the static helpers (UI thread only). */
    private val P = Paint(Paint.ANTI_ALIAS_FLAG)
    private val PATH = Path()
    private val PATH2 = Path()
    private val RR = RectF()
    /** A little slack after a lamp's toggle time before redrawing. */
    private const val BLINK_SLACK_MS = 16L
    /** Translucency of a glass fill, a faint (empty-slot) glass fill and a 25th-century frame fill. */
    private const val GLASS_ALPHA = Era.GLASS_TINT
    private const val GLASS_FAINT_ALPHA = 0.06f
    private const val FRAME_GLASS_ALPHA = 0.22f
    private const val OPAQUE = 0xFF000000.toInt()

    // ---- shared helpers ----------------------------------------------------------------------------

    /** Loads an [Era.cutPolygon] point list into `out` as a closed path. */
    @JvmStatic
    fun poly(out: Path, pts: FloatArray): Path {
        out.reset()
        out.moveTo(pts[0], pts[1])
        var i = 2
        while (i + 1 < pts.size) {
            out.lineTo(pts[i], pts[i + 1])
            i += 2
        }
        out.close()
        return out
    }

    /** True when a colour is a faint (empty slot / disabled) variant rather than a solid control colour. */
    private fun faint(color: Int): Boolean = Color.alpha(color) <= 0x80

    /**
     * A fine tick rule from `x0` to `x1` at `y`: short ticks every
     * [Era.TICK_SPACING_DP], every [Era.TICK_MAJOR_EVERY]th one longer. Ticks rise
     * (`up`) or hang from the line.
     */
    @JvmStatic
    fun ticks(cv: Canvas, x0: Float, x1: Float, y: Float, up: Boolean, color: Int, d: Float, alpha: Float) {
        val n = Era.tickCount(x1 - x0, d)
        if (n < 2) return
        val step = (x1 - x0) / (n - 1)
        P.style = Paint.Style.STROKE
        P.strokeWidth = max(1f, 0.8f * d)
        P.color = Ui.alpha(color or OPAQUE, alpha)
        val dir = if (up) -1f else 1f
        for (i in 0 until n) {
            val len = (if (i % Era.TICK_MAJOR_EVERY == 0) 4f else 2f) * d
            val x = x0 + i * step
            cv.drawLine(x, y, x, y + dir * len, P)
        }
        P.style = Paint.Style.FILL
    }

    // ---- 23rd century: keys, blocks, lamps ---------------------------------------------------------

    /**
     * One indicator lamp: a square coloured cap in a dark bezel; lit lamps get a soft halo and a
     * highlight, unlit ones a dark cap.
     */
    @JvmStatic
    fun lamp(cv: Canvas, cx: Float, cy: Float, s: Float, color: Int, on: Boolean, d: Float, alpha: Float) {
        val h = s / 2f
        P.style = Paint.Style.FILL
        P.color = Ui.alpha(Color.BLACK, 0.6f * alpha)
        cv.drawRect(cx - h - d, cy - h - d, cx + h + d, cy + h + d, P)
        if (on) {
            P.color = Ui.alpha(color, 0.30f * alpha)
            cv.drawCircle(cx, cy, s * 0.95f, P)
            P.color = Ui.alpha(color, alpha)
            cv.drawRect(cx - h, cy - h, cx + h, cy + h, P)
            P.color = Ui.alpha(Color.WHITE, 0.55f * alpha)
            cv.drawRect(cx - h, cy - h, cx - h * 0.1f, cy - h * 0.1f, P)
        } else {
            P.color = Ui.alpha(Ui.darken(color, 0.3f), alpha)
            cv.drawRect(cx - h, cy - h, cx + h, cy + h, P)
        }
    }

    /**
     * A flat 23rd-century block with a bevel: light top edge and dark bottom edge (or a plain
     * outline when cloaked). Used for frame bars, sidebar pieces and the body of every key.
     */
    @JvmStatic
    fun block23(cv: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, d: Float, cloak: Boolean) {
        if (r - l < 1 || b - t < 1) return
        val rad = min(Skin.ERA23_RADIUS * d, min(r - l, b - t) / 4f)
        RR.set(l, t, r, b)
        if (cloak) {
            val sw = max(1f, 1.5f * d)
            RR.inset(sw / 2f, sw / 2f)
            P.style = Paint.Style.FILL
            P.color = Color.BLACK
            cv.drawRoundRect(RR, rad, rad, P)
            P.style = Paint.Style.STROKE
            P.strokeWidth = sw
            P.color = Ui.alpha(color or OPAQUE, 0.75f)
            cv.drawRoundRect(RR, rad, rad, P)
            P.style = Paint.Style.FILL
            return
        }
        P.style = Paint.Style.FILL
        P.color = color
        cv.drawRoundRect(RR, rad, rad, P)
        if (faint(color)) return
        val bev = max(1f, min(3f * d, (b - t) * 0.09f))
        cv.save()
        cv.clipRect(l, b - bev, r, b)
        P.color = Ui.darken(color, 0.62f)
        cv.drawRoundRect(RR, rad, rad, P)
        cv.restore()
        cv.save()
        cv.clipRect(l, t, r, t + max(1f, bev * 0.55f))
        P.color = Skin.mix(color, Color.WHITE, 0.38f)
        cv.drawRoundRect(RR, rad, rad, P)
        cv.restore()
    }

    /**
     * A 23rd-century key: a [block23] with a darker end cap on the rounded side of LEFT/RIGHT
     * keys (so segmented rows still read as one bar) and up to three indicator lamps.
     *
     * @param now  uptime in ms for blinking, or -1 for steady lamps
     * @return ms until the next lamp toggle, or -1 when nothing blinks
     */
    @JvmStatic
    fun key23(
        cv: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, kind: Int, d: Float, seed: Int, now: Long,
        cloak: Boolean,
    ): Long {
        val w = r - l
        val h = b - t
        if (w < 2 || h < 2) return -1
        block23(cv, l, t, r, b, color, d, cloak)
        val faint = faint(color)
        if (!cloak && !faint && (kind == Ui.LEFT || kind == Ui.RIGHT)) {
            val band = min(8f * d, w * 0.15f)
            cv.save()
            if (kind == Ui.LEFT) cv.clipRect(l, t, l + band, b) else cv.clipRect(r - band, t, r, b)
            P.color = Ui.darken(color, 0.74f)
            val rad = min(Skin.ERA23_RADIUS * d, min(w, h) / 4f)
            RR.set(l, t, r, b)
            cv.drawRoundRect(RR, rad, rad, P)
            cv.restore()
        }
        if (faint) return -1
        val n = Era.lampCount(w, h, d)
        if (n == 0) return -1
        val ls = Era.LAMP_DP * d
        val lg = Era.LAMP_GAP_DP * d
        val inset = Era.LAMP_INSET_DP * d
        val y: Float
        val x0: Float
        if (h < 40 * d) {
            // button: one lamp at the leading end, centred vertically
            y = (t + b) / 2f
            x0 = if (kind == Ui.RIGHT) r - inset - ls / 2f else l + inset + ls / 2f
        } else if (w >= 120 * d) {
            // tile: a row along the top, clear of the icon in the top-left corner
            y = t + inset + ls / 2f
            x0 = l + 46 * d + ls / 2f
        } else {
            // narrow key (sidebar block): a row in the top-right corner
            y = t + inset + ls / 2f
            x0 = r - inset - ls / 2f - (n - 1) * (ls + lg)
        }
        var next = -1L
        for (i in 0 until n) {
            val ls2 = seed * 7 + i
            val on = cloak || now < 0 || Era.lampOn(now, ls2)
            lamp(cv, x0 + i * (ls + lg), y, ls, Era.lampColor(seed, i), on, d, if (cloak) 0.5f else 1f)
            if (!cloak && now >= 0) {
                val nt = Era.nextToggleIn(now, ls2)
                if (nt > 0 && (next < 0 || nt < next)) next = nt
            }
        }
        return next
    }

    // ---- 25th century: glass -----------------------------------------------------------------------

    /**
     * A 25th-century glass control: translucent fill, 1dp outline with cut corners
     * ([Era.cutsFor]), a solid accent strip on the leading edge and, on large controls, a faint
     * inset second outline and a tick rule along the bottom. Thin PILL bars become pointed glass rules.
     */
    @JvmStatic
    fun glass(cv: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, kind: Int, d: Float, cloak: Boolean) {
        val w = r - l
        val h = b - t
        if (w < 2 || h < 2) return
        val faint = faint(color)
        val col = color or OPAQUE
        val thin = kind == Ui.PILL && h < 16 * d
        val cuts = Era.cutsFor(kind)
        val k = if (thin) h / 2f else Era.cutSize(w, h, d)
        val sw = max(1f, d)
        val hs = sw / 2f
        poly(PATH, Era.cutPolygon(l + hs, t + hs, r - hs, b - hs, k, cuts))
        P.style = Paint.Style.FILL
        if (!cloak) {
            P.color = Ui.alpha(col, if (thin && !faint) 0.55f else if (faint) GLASS_FAINT_ALPHA else GLASS_ALPHA)
            cv.drawPath(PATH, P)
            if (!faint && !thin) {
                cv.save()
                cv.clipPath(PATH)
                val a = max(1.5f * d, min(3f * d, w * 0.06f))
                P.color = Ui.alpha(col, 0.85f)
                if (kind == Ui.RIGHT) cv.drawRect(r - a, t, r, b, P) else cv.drawRect(l, t, l + a, b, P)
                cv.restore()
            }
        }
        P.style = Paint.Style.STROKE
        P.strokeWidth = sw
        P.color = Ui.alpha(col, if (cloak) 0.6f else if (faint) 0.45f else 0.92f)
        cv.drawPath(PATH, P)
        if (!faint && !cloak && h >= 40 * d && w >= 100 * d) {
            val g = 4f * d
            poly(PATH2, Era.cutPolygon(l + g, t + g, r - g, b - g, max(0f, k - g * 0.4f), cuts))
            P.strokeWidth = max(1f, 0.7f * d)
            P.color = Ui.alpha(col, 0.28f)
            cv.drawPath(PATH2, P)
            ticks(cv, l + k + 8 * d, r - k - 8 * d, b - g - 2 * d, true, col, d, 0.45f)
        }
        P.style = Paint.Style.FILL
    }

    // ---- control drawables -------------------------------------------------------------------------

    /** The control background for the current era (called from `Ui.shape`). */
    @JvmStatic
    fun cell(c: Context, color: Int, kind: Int): Drawable =
        if (Skin.era23()) Key23(c, color, kind) else GlassCell(c, color, kind)

    /** A drawable that redraws itself for its next lamp toggle while it is being drawn. */
    abstract class Blinker(c: Context) : Drawable() {
        protected val d: Float = Ui.density(c)
        protected val animate: Boolean = Ui.animate(c)
        private val tick = Runnable { invalidateSelf() }

        /** Uptime for lamp phases, or -1 when animations are off (lamps then burn steadily). */
        protected fun now(): Long = if (animate) SystemClock.uptimeMillis() else -1

        /** Schedules a redraw `delay` ms from now (nothing when `delay` is negative). */
        protected fun redrawIn(delay: Long) {
            unscheduleSelf(tick)
            if (delay > 0) scheduleSelf(tick, SystemClock.uptimeMillis() + delay + BLINK_SLACK_MS)
        }

        override fun setAlpha(a: Int) {}

        override fun setColorFilter(cf: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** 23rd-century key drawable ([key23]); the lamps vary per key. */
    class Key23(c: Context, private val color: Int, private val kind: Int) : Blinker(c) {
        private val seed = System.identityHashCode(this)
        private val cloak = Ui.cloak

        override fun draw(cv: Canvas) {
            val b = bounds
            redrawIn(key23(cv, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                color, kind, d, seed, now(), cloak))
        }
    }

    /** 25th-century glass control drawable ([glass]). */
    class GlassCell(c: Context, private val color: Int, private val kind: Int) : Drawable() {
        private val d: Float = Ui.density(c)
        private val cloak = Ui.cloak

        override fun draw(cv: Canvas) {
            val b = bounds
            glass(cv, b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), color, kind, d, cloak)
        }

        override fun setAlpha(a: Int) {}

        override fun setColorFilter(cf: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * 25th-century tray / dialog / field outline (called from `Ui.outline`): fill, a thin
     * outline with the top-left and bottom-right corners cut, and short heavier segments running
     * off each cut, like layered glass edges.
     */
    @JvmStatic
    fun panel(c: Context, fill: Int, stroke: Int, strokeDp: Float): Drawable {
        val d = Ui.density(c)
        return object : Drawable() {
            val pt = Paint(Paint.ANTI_ALIAS_FLAG)
            val path = Path()

            override fun draw(cv: Canvas) {
                val b = bounds
                val w = b.width().toFloat()
                val h = b.height().toFloat()
                if (w < 2 || h < 2) return
                val sw = max(1f, strokeDp * 0.6f * d)
                val hs = sw / 2f
                val k = Era.cutSize(w, h, d)
                poly(path, Era.cutPolygon(b.left + hs, b.top + hs, b.right - hs, b.bottom - hs, k, Era.TL or Era.BR))
                pt.style = Paint.Style.FILL
                pt.color = fill
                cv.drawPath(path, pt)
                pt.style = Paint.Style.STROKE
                pt.strokeWidth = sw
                pt.color = stroke
                cv.drawPath(path, pt)
                if (Color.alpha(stroke) < 0x20 || w < 60 * d) return
                // heavier edge segments leaving each cut
                pt.strokeWidth = max(2f, 2.5f * d)
                val run = min(28 * d, w * 0.2f)
                cv.drawLine(b.left + k + hs, b.top + hs, b.left + k + run, b.top + hs, pt)
                cv.drawLine(b.right - k - run, b.bottom - hs, b.right - k - hs, b.bottom - hs, pt)
            }

            override fun setAlpha(a: Int) {
                pt.alpha = a
            }

            override fun setColorFilter(cf: ColorFilter?) {}

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    /** Header / dialog rule: a solid console bar (23rd) or a glass band with ticks (25th). */
    @JvmStatic
    fun rule(c: Context, color: Int): Drawable {
        if (Skin.era23()) return ColorDrawable(color)
        val d = Ui.density(c)
        val cloak = Ui.cloak
        return object : Drawable() {
            override fun draw(cv: Canvas) {
                val b = bounds
                val band = min(b.height().toFloat(), 10 * d)
                val cy = b.exactCenterY()
                glass(cv, b.left.toFloat(), cy - band / 2f, b.right.toFloat(), cy + band / 2f, color, Ui.PILL, d, cloak)
                if (!cloak && b.height() > band + 8 * d) {
                    ticks(cv, b.left + band, b.right - band, cy + band / 2f + 2 * d, false, color, d, 0.5f)
                }
            }

            override fun setAlpha(a: Int) {}

            override fun setColorFilter(cf: ColorFilter?) {}

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    // ---- frame pieces (ConsoleFrame) -----------------------------------------------------------------

    /**
     * 23rd-century frame corner: no elbow. The bar and the sidebar piece are separate square
     * blocks with a gap between them, and a short row of indicator lamps sits in the bar above the
     * sidebar column.
     *
     * @param now uptime for blinking lamps, or -1 for steady ones
     * @return ms until the next lamp toggle, or -1
     */
    @JvmStatic
    fun frameCorner23(
        cv: Canvas, d: Float, color: Int, sidebarW: Float, barH: Float, depth: Float, barEnd: Float,
        gap: Float, cloak: Boolean, seed: Int, now: Long,
    ): Long {
        block23(cv, 0f, 0f, barEnd, barH, color, d, cloak)
        if (depth - barH - gap > 2) block23(cv, 0f, barH + gap, sidebarW, depth, color, d, cloak)
        if (barH < 9 * d || barEnd < sidebarW) return -1
        val ls = min(barH * 0.48f, Era.LAMP_DP * d)
        val step = ls + Era.LAMP_GAP_DP * d
        var next = -1L
        var i = 0
        var x = sidebarW * 0.22f + ls / 2f
        while (x + ls / 2f < sidebarW - 2 * d && i < Era.MAX_LAMPS) {
            val s = seed * 13 + i
            val on = cloak || now < 0 || Era.lampOn(now, s)
            lamp(cv, x, barH / 2f, ls, Era.lampColor(seed, i), on, d, if (cloak) 0.5f else 1f)
            if (!cloak && now >= 0) {
                val nt = Era.nextToggleIn(now, s)
                if (nt > 0 && (next < 0 || nt < next)) next = nt
            }
            x += step
            i++
        }
        return next
    }

    /**
     * 25th-century frame corner: a thin glass elbow. A 45° cut where console has its outer curve, a
     * 45° chamfer at the inner joint, translucent fill, accent strips along the outer edges, a tick
     * rule inside the bar and a hairline second layer just under it.
     */
    @JvmStatic
    fun frameCorner25(
        cv: Canvas, d: Float, color: Int, sidebarW: Float, barH: Float, depth: Float, barEnd: Float,
        rIn: Float, rOut: Float, cloak: Boolean,
    ) {
        val col = color or OPAQUE
        val w = sidebarW
        val h = barH
        val cut = min(rOut * 0.55f, min(w * 0.5f, depth * 0.5f))
        val inner = min(rIn * 0.8f, w * 0.5f)
        val sw = max(1f, 1.2f * d)
        PATH.reset()
        PATH.moveTo(0f, depth)
        PATH.lineTo(0f, cut)
        PATH.lineTo(cut, 0f)
        PATH.lineTo(barEnd, 0f)
        PATH.lineTo(barEnd, h)
        PATH.lineTo(w + inner, h)
        PATH.lineTo(w, h + inner)
        PATH.lineTo(w, depth)
        PATH.close()
        P.style = Paint.Style.FILL
        if (!cloak) {
            P.color = Ui.alpha(col, if (faint(color)) GLASS_FAINT_ALPHA else FRAME_GLASS_ALPHA)
            cv.drawPath(PATH, P)
            cv.save()
            cv.clipPath(PATH)
            P.color = Ui.alpha(col, 0.9f)
            cv.drawRect(0f, 0f, barEnd, max(1.5f * d, min(3f * d, h * 0.28f)), P)
            cv.drawRect(0f, 0f, max(1.5f * d, min(3f * d, w * 0.08f)), depth, P)
            cv.restore()
        }
        P.style = Paint.Style.STROKE
        P.strokeWidth = sw
        P.color = Ui.alpha(col, if (cloak) 0.6f else 0.95f)
        cv.drawPath(PATH, P)
        P.style = Paint.Style.FILL
        if (cloak) return
        if (h >= 8 * d) ticks(cv, w + inner + 6 * d, barEnd - 6 * d, h - 1.5f * d, true, col, d, 0.55f)
        // the hairline second layer under the bar
        val ly = h + 3 * d
        val lx1 = w + inner + (barEnd - w - inner) * 0.62f
        if (lx1 > w + inner + 20 * d) {
            P.style = Paint.Style.STROKE
            P.strokeWidth = max(1f, 0.7f * d)
            P.color = Ui.alpha(col, 0.35f)
            cv.drawLine(w + inner + 4 * d, ly, lx1, ly, P)
            P.style = Paint.Style.FILL
        }
    }

    /** One bar-end block of a frame in the current era (square console block or glass). */
    @JvmStatic
    fun frameBlock(cv: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, d: Float, cloak: Boolean) {
        if (Skin.era23()) block23(cv, l, t, r, b, color, d, cloak)
        else glass(cv, l, t, r, b, color, Ui.SQUARE, d, cloak)
    }

    // ---- card bracket (ConsoleCard) ------------------------------------------------------------------

    /**
     * Side bracket of a panel card: a heavy square "[" with a lamp in the top arm (23rd), or a thin
     * cut-corner bracket line with a glass strip and ticks down the upright (25th).
     */
    @JvmStatic
    fun bracket(cv: Canvas, d: Float, w: Float, h: Float, color: Int, cloak: Boolean) {
        val reach = w + 14 * d
        if (Skin.era23()) {
            val bar = w * 0.55f
            val stub = 7 * d
            block23(cv, 0f, 0f, bar, h, color, d, cloak)
            block23(cv, 0f, 0f, reach, stub, color, d, cloak)
            block23(cv, 0f, h - stub, reach, h, color, d, cloak)
            if (!cloak && h > 40 * d) lamp(cv, reach - 6 * d, stub / 2f, min(stub * 0.6f, 5 * d), Era.LAMPS[1], true, d, 1f)
            return
        }
        val col = color or OPAQUE
        val k = min(w * 0.7f, 10 * d)
        val sw = max(1f, 1.2f * d)
        val hs = sw / 2f
        PATH.reset()
        PATH.moveTo(reach, hs)
        PATH.lineTo(k, hs)
        PATH.lineTo(hs, k)
        PATH.lineTo(hs, h - k)
        PATH.lineTo(k, h - hs)
        PATH.lineTo(reach, h - hs)
        if (!cloak) {
            P.style = Paint.Style.FILL
            P.color = Ui.alpha(col, GLASS_ALPHA)
            cv.drawRect(hs, k, w * 0.4f, h - k, P)
            P.color = Ui.alpha(col, 0.85f)
            cv.drawRect(hs, k + 2 * d, hs + max(1.5f * d, 2.5f * d), h - k - 2 * d, P)
        }
        P.style = Paint.Style.STROKE
        P.strokeWidth = sw
        P.color = Ui.alpha(col, if (cloak) 0.6f else 0.95f)
        cv.drawPath(PATH, P)
        if (!cloak && h > 48 * d) {
            val n = Era.tickCount(h - 2 * k, d)
            val step = if (n > 1) (h - 2 * k) / (n - 1) else 0f
            P.strokeWidth = max(1f, 0.8f * d)
            P.color = Ui.alpha(col, 0.5f)
            for (i in 0 until n) {
                val y = k + i * step
                val len = (if (i % Era.TICK_MAJOR_EVERY == 0) 6f else 3f) * d
                cv.drawLine(w * 0.45f, y, w * 0.45f + len, y, P)
            }
        }
        P.style = Paint.Style.FILL
    }

    // ---- cascade keys (CommandCascade) -----------------------------------------------------------------

    /** A key or spine segment of the console cascade drawn in the current era (lamps steady on the canvas). */
    @JvmStatic
    fun key(cv: Canvas, r: RectF, color: Int, kind: Int, d: Float, cloak: Boolean, seed: Int) {
        if (Skin.era23()) key23(cv, r.left, r.top, r.right, r.bottom, color, kind, d, seed, -1, cloak)
        else glass(cv, r.left, r.top, r.right, r.bottom, color, kind, d, cloak)
    }

    // ---- icon frames (IconFactory.Framed) ----------------------------------------------------------

    /**
     * Frame behind an app icon or pictogram: a square console key with a bevel and one lamp
     * (23rd), or a cut-corner glass tile with an inset layer and a tick rule (25th).
     *
     * @param r    frame rectangle (already inset by the stroke)
     * @param s    icon size in px
     * @param mark colour of the small marks (lamp / accent strip)
     */
    @JvmStatic
    fun iconFrame(cv: Canvas, r: RectF, s: Float, sw: Float, color: Int, fill: Int, mark: Int, faction: Int) {
        if (faction == Skin.ERA23) {
            val rad = s * 0.06f
            P.style = Paint.Style.FILL
            P.color = fill
            cv.drawRoundRect(r, rad, rad, P)
            val bev = s * 0.07f
            cv.save()
            cv.clipRect(r.left, r.bottom - bev, r.right, r.bottom)
            P.color = Ui.alpha(Ui.darken(color, 0.55f), Color.alpha(fill) / 255f)
            cv.drawRoundRect(r, rad, rad, P)
            cv.restore()
            P.style = Paint.Style.STROKE
            P.strokeWidth = sw * 1.3f
            P.color = color
            cv.drawRoundRect(r, rad, rad, P)
            P.style = Paint.Style.FILL
            val ls = s * 0.09f
            P.color = Ui.alpha(Color.BLACK, 0.6f)
            cv.drawRect(r.left + s * 0.08f - sw * 0.4f, r.top + s * 0.08f - sw * 0.4f,
                r.left + s * 0.08f + ls + sw * 0.4f, r.top + s * 0.08f + ls + sw * 0.4f, P)
            P.color = mark
            cv.drawRect(r.left + s * 0.08f, r.top + s * 0.08f, r.left + s * 0.08f + ls, r.top + s * 0.08f + ls, P)
            return
        }
        val k = s * 0.18f
        poly(PATH, Era.cutPolygon(r.left, r.top, r.right, r.bottom, k, Era.TL or Era.BR))
        P.style = Paint.Style.FILL
        P.color = fill
        cv.drawPath(PATH, P)
        cv.save()
        cv.clipPath(PATH)
        P.color = mark
        cv.drawRect(r.left, r.top, r.left + s * 0.05f, r.bottom, P)
        cv.restore()
        P.style = Paint.Style.STROKE
        P.strokeWidth = sw * 0.7f
        P.color = color
        cv.drawPath(PATH, P)
        val g = s * 0.07f
        poly(PATH2, Era.cutPolygon(r.left + g, r.top + g, r.right - g, r.bottom - g, k * 0.7f, Era.TL or Era.BR))
        P.strokeWidth = max(1f, sw * 0.4f)
        P.color = Ui.alpha(color, 0.35f)
        cv.drawPath(PATH2, P)
        P.style = Paint.Style.FILL
    }

    // ---- header ornaments ----------------------------------------------------------------------------

    /** Ornament behind the home clock: a bank of indicator lamps (23rd) or a fine-line reticle (25th). */
    @JvmStatic
    fun ornament(c: Context, p: Palette): Drawable = if (Skin.era23()) LampBank(c) else Reticle(c, p)

    /**
     * 23rd-century lamp bank: two short columns of lamps at each end of the clock row, some
     * blinking. Kept clear of the middle so the time stays readable.
     */
    class LampBank(c: Context) : Blinker(c) {
        private val seed = 0x2301
        private val cloak = Ui.cloak

        override fun draw(cv: Canvas) {
            val b = bounds
            val ls = 7 * d
            val step = ls + 5 * d
            val rows = max(0f, min(3f, (b.height() - 8 * d) / step)).toInt()
            val cols = max(0f, min(3f, (b.width() * 0.16f) / step)).toInt()
            if (rows == 0 || cols == 0) return
            val now = now()
            var next = -1L
            val top = b.exactCenterY() - (rows - 1) * step / 2f
            for (side in 0 until 2) {
                for (i in 0 until cols) {
                    val x = if (side == 0) b.left + 6 * d + ls / 2f + i * step else b.right - 6 * d - ls / 2f - i * step
                    for (j in 0 until rows) {
                        val s = seed + side * 97 + i * 11 + j
                        val on = cloak || now < 0 || Era.lampOn(now, s)
                        lamp(cv, x, top + j * step, ls, Era.lampColor(s, j), on, d, if (cloak) 0.35f else 0.75f)
                        if (!cloak && now >= 0) {
                            val nt = Era.nextToggleIn(now, s)
                            if (nt > 0 && (next < 0 || nt < next)) next = nt
                        }
                    }
                }
            }
            redrawIn(next)
        }
    }

    /**
     * 25th-century reticle: cut corner brackets at the four corners of the clock row, a fine tick
     * rule along its bottom and short arc segments at each end, all dim.
     */
    class Reticle(c: Context, p: Palette) : Drawable() {
        private val d: Float = Ui.density(c)
        private val line: Int = p.c(2)
        private val hi: Int = p.c(0)
        private val cloak = Ui.cloak
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arc = RectF()
        private var am = 1f

        override fun draw(cv: Canvas) {
            val b = bounds
            val a = (if (cloak) 0.3f else 0.6f) * am
            val l = b.left + 3 * d
            val t = b.top + 3 * d
            val r = b.right - 3 * d
            val bt = b.bottom - 3 * d
            val arm = min(22 * d, (r - l) * 0.12f)
            val k = 5 * d
            pt.style = Paint.Style.STROKE
            pt.strokeWidth = max(1f, d)
            pt.color = Ui.alpha(line or OPAQUE, a)
            // corner brackets, each with a small 45° cut
            corner(cv, l, t, arm, k, 1, 1)
            corner(cv, r, t, arm, k, -1, 1)
            corner(cv, l, bt, arm, k, 1, -1)
            corner(cv, r, bt, arm, k, -1, -1)
            // arc segments at the ends
            val rr = min((bt - t) * 0.42f, 34 * d)
            val cy = (t + bt) / 2f
            pt.color = Ui.alpha(hi or OPAQUE, a * 0.8f)
            arc.set(l + arm * 0.6f, cy - rr, l + arm * 0.6f + rr * 2, cy + rr)
            cv.drawArc(arc, 140f, 80f, false, pt)
            arc.set(r - arm * 0.6f - rr * 2, cy - rr, r - arm * 0.6f, cy + rr)
            cv.drawArc(arc, -40f, 80f, false, pt)
            ticks(cv, l + arm + 8 * d, r - arm - 8 * d, bt, true, line, d, a * 0.8f)
        }

        /** One L-shaped corner bracket at (x, y) opening toward (sx, sy), its joint cut by `k`. */
        private fun corner(cv: Canvas, x: Float, y: Float, arm: Float, k: Float, sx: Int, sy: Int) {
            val pth = PATH
            pth.reset()
            pth.moveTo(x + sx * arm, y)
            pth.lineTo(x + sx * k, y)
            pth.lineTo(x, y + sy * k)
            pth.lineTo(x, y + sy * arm * 0.7f)
            cv.drawPath(pth, pt)
        }

        override fun setAlpha(al: Int) {
            am = al / 255f
        }

        override fun setColorFilter(cf: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    // ---- CONFIG previews -----------------------------------------------------------------------------

    /** Height of the interface preview strip on a CONFIG interface card, in dp. */
    const val PREVIEW_DP = 64f

    /**
     * A miniature of an era's home screen for its CONFIG interface card: frame corner, bar and a
     * few keys in the given palette. Draws as `faction` regardless of the active interface.
     */
    @JvmStatic
    fun preview(c: Context, faction: Int, p: Palette): Drawable = object : Blinker(c) {
        private val seed = 0x7E57 + faction

        override fun draw(cv: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w < 40 * d || h < 20 * d) return
            cv.save()
            cv.translate(b.left.toFloat(), b.top.toFloat())
            cv.drawColor(if (faction == Skin.ERA23) Era.ERA23_BG else Era.ERA25_BG)
            val side = min(40 * d, w * 0.14f)
            val bar = max(6 * d, h * 0.14f)
            val x0 = side + 8 * d
            val top = bar + 8 * d
            val rowH = (h - top - 4 * d) / 2f - 2 * d
            val cols = max(2, min(5, ((w - x0) / (70 * d)).toInt()))
            val colW = (w - x0 - (cols - 1) * 4 * d) / cols
            var next = -1L
            if (faction == Skin.ERA23) {
                next = frameCorner23(cv, d, p.c(1), side, bar, h, w * 0.7f, 4 * d, false, seed, now())
                block23(cv, w * 0.7f + 4 * d, 0f, w, bar, p.c(0), d, false)
                for (i in 0 until cols * 2) {
                    val l = x0 + (i % cols) * (colW + 4 * d)
                    val t = top + (i / cols) * (rowH + 4 * d)
                    val nt = key23(cv, l, t, l + colW, t + rowH, p.c(i), Ui.SQUARE, d, seed + i, now(), false)
                    if (nt > 0 && (next < 0 || nt < next)) next = nt
                }
            } else {
                frameCorner25(cv, d, p.c(2), side, bar, h, w * 0.7f, 10 * d, 24 * d, false)
                glass(cv, w * 0.7f + 4 * d, 0f, w, bar, p.c(4), Ui.PILL, d, false)
                for (i in 0 until cols * 2) {
                    val l = x0 + (i % cols) * (colW + 4 * d)
                    val t = top + (i / cols) * (rowH + 4 * d)
                    // keys overlap a step to show the layered glass
                    glass(cv, l + 3 * d, t - 2 * d, l + colW + 3 * d, t + rowH - 2 * d, p.c(i + 1), Ui.SQUARE, d, false)
                    glass(cv, l, t, l + colW, t + rowH, p.c(i), if (i % cols == 0) Ui.LEFT else Ui.SQUARE, d, false)
                }
            }
            cv.restore()
            redrawIn(next)
        }
    }
}
