package com.gungreeneyes.trekui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max

/**
 * Drawing vocabulary for the Imperial interface, after the Imperial screens: bright blood-red
 * outlines on black, long octagonal and hexagonal frames with big 45° corner cuts (often
 * double-lined), rows of alternating triangles and stacked triangle clusters in yellow, orange
 * and red, and triangle-lattice fills. No curves anywhere. (Original geometry; no emblems or
 * alien script.) UI thread only.
 *
 * Java interop: the sub-theme colours are [JvmField]s and every function is [JvmStatic], so Java
 * keeps reading `ImperialDraw.RED` and calling `ImperialDraw.tri(...)`.
 */
object ImperialDraw {
    /*
     * Line red, orange, highlight yellow and deep red of the active Imperial sub-theme (accent slots
     * 0-3, see SubThemes.IMPERIAL); the initial values are the original tactical look. Set by
     * use (through Skin.useSubTheme), read on the UI thread only.
     */
    @JvmField
    var RED = 0xFFD8231E.toInt()
    @JvmField
    var ORANGE = 0xFFF0821E.toInt()
    @JvmField
    var YELLOW = 0xFFF5C830.toInt()
    @JvmField
    var DEEP = 0xFFA3161A.toInt()

    private val P = Paint(Paint.ANTI_ALIAS_FLAG)
    private val T = Path()

    /** Takes the line / orange / highlight / deep colours from an Imperial sub-theme palette. */
    @JvmStatic
    fun use(p: Palette) {
        RED = p.c[0]
        ORANGE = p.c[1]
        YELLOW = p.c[2]
        DEEP = p.c[3]
    }

    /** Colour with alpha, dimmed further while cloaked. */
    private fun cl(c: Int, a: Float): Int = Ui.alpha(c, a * (if (Ui.cloak) 0.55f else 1f))

    /** Isoceles triangle of width s and height h, centred on (cx, cy), pointing up or down. */
    @JvmStatic
    fun tri(cv: Canvas, cx: Float, cy: Float, s: Float, h: Float, up: Boolean, color: Int, alpha: Float) {
        T.reset()
        if (up) {
            T.moveTo(cx, cy - h / 2)
            T.lineTo(cx + s / 2, cy + h / 2)
            T.lineTo(cx - s / 2, cy + h / 2)
        } else {
            T.moveTo(cx - s / 2, cy - h / 2)
            T.lineTo(cx + s / 2, cy - h / 2)
            T.lineTo(cx, cy + h / 2)
        }
        T.close()
        P.style = if (Ui.cloak) Paint.Style.STROKE else Paint.Style.FILL
        P.strokeWidth = 1.2f
        P.color = cl(color, alpha)
        cv.drawPath(T, P)
    }

    /** ▲▼▲▼… packed edge to edge across (x, y, w, h), colours cycling. */
    @JvmStatic
    fun row(cv: Canvas, x: Float, y: Float, w: Float, h: Float, colors: IntArray, alpha: Float) {
        val s = h * 1.15f
        val n = max(1, ((w - s / 2) / (s / 2)).toInt())
        for (i in 0 until n) {
            val cx = x + s / 2 + i * s / 2
            tri(cv, cx, y + h / 2, s * 0.92f, h * 0.92f, i % 2 == 0, colors[i % colors.size], alpha)
        }
    }

    /** A stacked cluster: one triangle over two, with an inverted one between (yellow/orange/red). */
    @JvmStatic
    fun cluster(cv: Canvas, cx: Float, cy: Float, s: Float, alpha: Float) {
        val h = s * 0.87f
        tri(cv, cx, cy - h / 2, s, h, true, YELLOW, alpha)
        tri(cv, cx - s / 2, cy + h / 2, s, h, true, ORANGE, alpha)
        tri(cv, cx + s / 2, cy + h / 2, s, h, true, RED, alpha)
        tri(cv, cx, cy + h / 2, s * 0.8f, h * 0.8f, false, DEEP, alpha * 0.9f)
    }

    /** Triangle lattice (three line directions) over r. */
    @JvmStatic
    fun grid(cv: Canvas, r: RectF, step: Float, color: Int, alpha: Float, d: Float) {
        P.style = Paint.Style.STROKE
        P.strokeWidth = max(1f, 0.8f * d)
        P.color = cl(color, alpha)
        val hgt = step * 0.866f
        var y = r.top
        while (y <= r.bottom) {
            cv.drawLine(r.left, y, r.right, y, P)
            y += hgt
        }
        val span = r.height() / 1.732f
        var x = r.left - span
        while (x <= r.right + span) {
            cv.drawLine(x, r.bottom, x + span, r.top, P)
            cv.drawLine(x, r.top, x + span, r.bottom, P)
            x += step
        }
    }

    /** Long octagon with 45° cuts of size k on the chosen corners. */
    @JvmStatic
    fun octagon(p: Path, l: Float, t: Float, r: Float, b: Float, k: Float, leftCuts: Boolean, rightCuts: Boolean): Path {
        p.reset()
        val kl = if (leftCuts) k else 0f
        val kr = if (rightCuts) k else 0f
        p.moveTo(l + kl, t)
        p.lineTo(r - kr, t)
        p.lineTo(r, t + kr)
        p.lineTo(r, b - kr)
        p.lineTo(r - kr, b)
        p.lineTo(l + kl, b)
        p.lineTo(l, b - kl)
        p.lineTo(l, t + kl)
        p.close()
        return p
    }

    /** A frame outline: heavy outer line, optional thin inner line (the double-lined look). */
    @JvmStatic
    fun frame(cv: Canvas, outer: Path, inner: Path?, color: Int, d: Float, alpha: Float) {
        P.style = Paint.Style.STROKE
        P.strokeJoin = Paint.Join.MITER
        P.strokeWidth = 2.4f * d
        P.color = cl(color, alpha)
        cv.drawPath(outer, P)
        if (inner != null) {
            P.strokeWidth = 1f * d
            P.color = cl(color, 0.45f * alpha)
            cv.drawPath(inner, P)
        }
    }

    /** Solid fill of a path (plate backgrounds). */
    @JvmStatic
    fun fill(cv: Canvas, p: Path, color: Int) {
        P.style = Paint.Style.FILL
        P.color = color
        cv.drawPath(p, P)
    }
}
