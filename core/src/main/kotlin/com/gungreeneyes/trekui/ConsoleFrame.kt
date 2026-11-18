package com.gungreeneyes.trekui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * The signature console frame: a vertical sidebar joined to horizontal bars by rounded "elbows"
 * (outer convex corner, inner concave corner). Children: sidebar (fills the column between the
 * elbows), content, a title at the top-right end of the top bar and an optional footer at the
 * bottom-right end of the bottom bar. Coloured blocks sit at the outer end of each bar.
 *
 * Each faction draws the same structure in its own geometry: Command elbows, Imperial angular
 * brackets with triangle blocks, Collective conduit traces with nodes, the 23rd-century console's square
 * blocks with blinking lamps and the 25th century's thin glass elbow ([EraDraw]).
 *
 * The drawn parts are touch targets: a [PartListener] receives taps and long-presses on
 * "elbowT", "elbowB", "blockT0".., "blockB0".. so the home screen can customise them.
 *
 * All sizes come from [Ui.dp] and therefore follow the window's zoom; the frame itself
 * is orientation-agnostic (content gets whatever is left of the width after the sidebar).
 *
 * Java interop: the sizes and colours the screens assign (`topH`, `colorTop`, `topBlocks`…) are
 * [JvmField]s, the design constants are `const` and the pure geometry is [JvmStatic].
 */
open class ConsoleFrame(c: Context) : ViewGroup(c) {

    /** Sidebar width. */
    @JvmField var sidebarW: Int = Ui.dp(c, SIDEBAR_DP)
    /** Top bar height. */
    @JvmField var topH: Int = Ui.dp(c, BAR_DP)
    /** Bottom bar height. */
    @JvmField var botH: Int = Ui.dp(c, BAR_DP)
    /** Outer elbow radius. */
    @JvmField var rOut: Int = Ui.dp(c, OUTER_RADIUS_DP)
    /** Inner elbow radius. */
    @JvmField var rIn: Int = Ui.dp(c, INNER_RADIUS_DP)
    /** Gap between pieces. */
    @JvmField var gap: Int = Ui.dp(c, Ui.GUTTER.toFloat())
    /** Elbow extension below the bar. */
    @JvmField var ext: Int = Ui.dp(c, ELBOW_EXTENSION_DP)
    /** Elbow colours. */
    @JvmField var colorTop: Int = 0
    @JvmField var colorBot: Int = 0
    /** Colours of the blocks at the outer end of each bar (right to left). */
    @JvmField var topBlocks: IntArray = IntArray(0)
    @JvmField var botBlocks: IntArray = IntArray(0)
    /** Width of one bar block. */
    @JvmField var blockW: Int = Ui.dp(c, BLOCK_DP)

    private var sidebar: View? = null
    private var content: View? = null
    private var title: View? = null
    private var footer: View? = null
    /** Reveal animation progress, 0 (bars retracted) .. 1. */
    private var progress = 1f

    /** Taps / long-presses on the drawn parts: "elbowT", "elbowB", "blockT0".., "blockB0".. */
    fun interface PartListener {
        fun onPart(part: String, frame: View, longPress: Boolean)
    }

    private var partListener: PartListener? = null
    private val topRects = ArrayList<RectF>()
    private val botRects = ArrayList<RectF>()
    private var topBarEnd = 0f
    private var botBarEnd = 0f
    private var pressed: String? = null
    private var longFired = false
    private var downX = 0f
    private var downY = 0f
    private val longPress = Runnable {
        val p = pressed
        val l = partListener
        if (p != null && l != null) {
            longFired = true
            pressed = null
            invalidate()
            l.onPart(p, this, true)
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()

    init {
        setWillNotDraw(false)
    }

    fun setPartListener(l: PartListener?) {
        partListener = l
    }

    private fun swap(old: View?, v: View?): View? {
        if (old != null) removeView(old)
        if (v != null) addView(v)
        return v
    }

    fun setSidebar(v: View?) {
        sidebar = swap(sidebar, v)
    }

    fun setContent(v: View?) {
        content = swap(content, v)
    }

    fun setTitle(v: View?) {
        title = swap(title, v)
    }

    fun setFooter(v: View?) {
        footer = swap(footer, v)
    }

    // ---- layout ----------------------------------------------------------------------------------

    private fun topRegion(): Int = max(topH, title?.measuredHeight ?: 0)
    private fun botRegion(): Int = max(botH, footer?.measuredHeight ?: 0)
    private fun contentLeft(): Int = sidebarW + gap * 2
    private fun contentTop(): Int {
        val t = topRegion()
        return if (t > 0) t + gap * 2 else 0
    }
    private fun contentBottomInset(): Int {
        val b = botRegion()
        return if (b > 0) b + gap * 2 else 0
    }
    private fun elbowSpan(barH: Int): Int = elbowSpanFor(barH, rIn, ext)
    private fun sideTop(): Int = if (topH > 0) elbowSpan(topH) + gap else 0
    private fun sideBottom(h: Int): Int = if (botH > 0) h - elbowSpan(botH) - gap else h

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val hMode = MeasureSpec.getMode(hSpec)
        val hSize = MeasureSpec.getSize(hSpec)
        val unspec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        val labelMax = MeasureSpec.makeMeasureSpec(
            max(0, w - sidebarW - rIn - Ui.dp(context, LABEL_CLEARANCE_DP)), MeasureSpec.AT_MOST)
        title?.measure(labelMax, unspec)
        footer?.measure(labelMax, unspec)

        val cw = max(0, w - contentLeft())
        val vert = contentTop() + contentBottomInset()
        var h: Int
        val content = content
        if (hMode == MeasureSpec.EXACTLY) {
            h = hSize
            content?.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(max(0, h - vert), MeasureSpec.EXACTLY))
        } else {
            var ch = 0
            if (content != null) {
                val spec = if (hMode == MeasureSpec.AT_MOST) {
                    MeasureSpec.makeMeasureSpec(max(0, hSize - vert), MeasureSpec.AT_MOST)
                } else {
                    unspec
                }
                content.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), spec)
                ch = content.measuredHeight
            }
            h = vert + ch
            h = max(h, elbowSpan(topH) + elbowSpan(botH) + gap * 2)
            if (hMode == MeasureSpec.AT_MOST) h = min(h, hSize)
        }
        sidebar?.let {
            val sh = max(0, sideBottom(h) - sideTop())
            it.measure(MeasureSpec.makeMeasureSpec(sidebarW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        title?.let { it.layout(w - it.measuredWidth, 0, w, it.measuredHeight) }
        footer?.let { it.layout(w - it.measuredWidth, h - it.measuredHeight, w, h) }
        sidebar?.let { it.layout(0, sideTop(), sidebarW, sideTop() + it.measuredHeight) }
        content?.let {
            val ct = contentTop()
            it.layout(contentLeft(), ct, contentLeft() + it.measuredWidth, ct + it.measuredHeight)
        }
    }

    // ---- drawing ---------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (Ui.cloak) {
            // cloaked: the frame becomes a dim outline
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(1, Ui.dp(context, 1.5f)).toFloat()
        } else {
            paint.style = Paint.Style.FILL
        }
        if (topH > 0) {
            var end = title?.let { it.left - gap * 2 } ?: w
            end = drawBlocks(canvas, topBlocks, end, 0, topH, topRects, "blockT")
            topBarEnd = end.toFloat()
            paint.color = lit("elbowT", colorTop)
            drawCorner(canvas, topH, end)
        }
        if (botH > 0) {
            var end = footer?.let { it.left - gap * 2 } ?: w
            end = drawBlocks(canvas, botBlocks, end, h - botH, h, botRects, "blockB")
            botBarEnd = end.toFloat()
            paint.color = lit("elbowB", colorBot)
            // the bottom elbow is the top one mirrored vertically
            canvas.save()
            canvas.translate(0f, h.toFloat())
            canvas.scale(1f, -1f)
            drawCorner(canvas, botH, end)
            canvas.restore()
        }
    }

    /** The corner piece joining sidebar and bar, in the current faction's geometry. */
    private fun drawCorner(canvas: Canvas, barH: Int, end: Int) {
        if (Skin.imperial()) {
            // hollow, double-lined bracket in a heavy red line, ▲▼ row near its end
            val col = paint.color
            val dd = Ui.density(context)
            buildBracket(barH, end)
            ImperialDraw.fill(canvas, path, if (Ui.cloak) Color.BLACK else Skin.mix(Skin.bg(), col, 0.10f))
            ImperialDraw.frame(canvas, path, null, col, dd, 1f)
            triRow(canvas, barH, end, dd)
            paint.color = col
        } else if (Skin.collective()) {
            conduit(canvas, barH, end)
        } else if (Skin.era()) {
            eraCorner(canvas, barH, end)
        } else {
            buildElbow(barH, end)
            canvas.drawPath(path, paint)
        }
    }

    /**
     * 23rd / 25th century corner ([EraDraw.frameCorner23], [EraDraw.frameCorner25]). The
     * console's lamps blink: the frame redraws itself for the next toggle while animations are on.
     */
    private fun eraCorner(canvas: Canvas, barH: Int, end: Int) {
        val col = paint.color
        val d = Ui.density(context)
        val depth = elbowSpan(barH).toFloat()
        val barEnd = sweptBarEnd(sidebarW.toFloat(), end.toFloat(), progress)
        if (Skin.era23()) {
            val now = if (Ui.animate(context)) SystemClock.uptimeMillis() else -1L
            val next = EraDraw.frameCorner23(canvas, d, col, sidebarW.toFloat(), barH.toFloat(), depth, barEnd,
                gap.toFloat(), Ui.cloak, barH * 31 + sidebarW, now)
            if (next > 0) postInvalidateDelayed(next + 16)
        } else {
            EraDraw.frameCorner25(canvas, d, col, sidebarW.toFloat(), barH.toFloat(), depth, barEnd,
                rIn.toFloat(), rOut.toFloat(), Ui.cloak)
        }
        paint.color = col
    }

    /**
     * Imperial: an angular bracket. Bevelled outer corner, a straight diagonal
     * where console has its concave curve, a narrower rail down the side and an oblique cut at the end
     * of the bar.
     */
    private fun buildBracket(barH: Int, barEndFull: Int) {
        val sw = sidebarW.toFloat()
        val bh = barH.toFloat()
        val ri = min(rIn.toFloat(), sw)
        val depth = bh + ri + ext
        val bevel = min(rOut * 0.75f, min(sw * 0.6f, depth * 0.7f))
        val rail = sw * 0.62f
        val barEnd = sweptBarEnd(sw + ri, barEndFull.toFloat(), progress)
        val cut = bh * 0.9f
        path.reset()
        path.moveTo(0f, depth)
        path.lineTo(0f, bevel)
        path.lineTo(bevel, 0f)
        path.lineTo(barEnd, 0f)
        path.lineTo(barEnd - cut, bh)
        path.lineTo(rail + ri, bh)
        path.lineTo(rail, bh + ri)
        path.lineTo(rail, depth)
        path.close()
    }

    /** A short row of alternating triangles inside the bar, just before its oblique end. */
    private fun triRow(canvas: Canvas, barH: Int, barEnd: Int, dd: Float) {
        if (progress < 0.95f || barEnd - sidebarW < blockW * 3 || barH < 8 * dd) return
        val bh = barH.toFloat()
        val cut = bh * 0.9f
        val rw = min(64 * dd, (barEnd - sidebarW) * 0.3f)
        val x = barEnd - cut - rw - gap
        ImperialDraw.row(canvas, x, bh * 0.22f, rw, bh * 0.56f,
            intArrayOf(ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED), 1f)
    }

    /**
     * Collective: no corner piece. A hub node sits where console has its elbow; a trace runs from it along
     * the bar line through vias and small nodes to the bar's end, and another drops from the hub
     * down the sidebar.
     */
    private fun conduit(canvas: Canvas, barH: Int, barEndFull: Int) {
        val sw = sidebarW.toFloat()
        val bh = barH.toFloat()
        val ri = min(rIn.toFloat(), sw)
        val depth = bh + ri + ext
        val barEnd = sweptBarEnd(sw, barEndFull.toFloat(), progress)
        val col = paint.color
        val d = Ui.density(context)
        val hx = sw * 0.5f
        val hr = min(sw * 0.3f, 15 * d)
        val hy = max(hr + 2 * d, bh * 0.5f + 2 * d)
        // trace along the bar line, bowing gently, with vias and small nodes
        val ly = bh * 0.5f
        var x = hx + hr
        var i = 0
        val runs = floatArrayOf(0.22f, 0.18f, 0.27f, 0.14f, 0.19f)
        val span = barEnd - x
        while (x < barEnd - 6 * d && span > 0) {
            val seg = min(barEnd - x, span * runs[i % runs.size])
            val bow = if (i % 2 == 0) 0.06f else -0.05f
            CollectiveDraw.curveTrace(canvas, x, ly, x + seg, ly, bow, col, d, 0.95f)
            x += seg
            if (x < barEnd - 6 * d) {
                if (i % 2 == 0) {
                    CollectiveDraw.via(canvas, x, ly, 3 * d, col, d, 1f)
                } else {
                    CollectiveDraw.node(canvas, x, ly, min(bh * 0.42f, 6 * d), col, d, 1f)
                }
            }
            i++
        }
        CollectiveDraw.via(canvas, barEnd - 4 * d, ly, 3.5f * d, col, d, 1f)
        CollectiveDraw.dots(canvas, hx + hr + 10 * d, ly + max(5 * d, bh * 0.5f + 3 * d), 5, col, d, barH * 13, 0.9f)
        // drop down the sidebar
        CollectiveDraw.curveTrace(canvas, hx, hy + hr, hx - sw * 0.12f, depth, 0.08f, col, d, 0.9f)
        CollectiveDraw.via(canvas, hx - sw * 0.12f, depth - 3 * d, 3 * d, col, d, 1f)
        // the hub itself
        CollectiveDraw.node(canvas, hx, hy, hr, col, d, 1f)
        paint.color = col
    }

    /**
     * Draws the blocks at a bar's outer end, right to left starting at `right`, stopping before
     * they would run into the elbow. Records their hit rectangles and returns where the bar proper
     * now ends.
     */
    private fun drawBlocks(
        canvas: Canvas, colors: IntArray, right: Int, top: Int, bottom: Int, rects: MutableList<RectF>, prefix: String,
    ): Int {
        var x = right
        rects.clear()
        val d = Ui.density(context)
        for (i in colors.indices) {
            if (x - blockW < sidebarW + rIn + gap) break
            paint.color = lit(prefix + i, colors[i])
            val p = progress
            if (Skin.imperial()) {
                // blocks become solid triangles, alternately pointing up and down
                val up = (x / max(1, blockW + gap)) % 2 == 0
                ImperialDraw.tri(canvas, x - blockW * p / 2f, (top + bottom) / 2f, blockW * p,
                    (bottom - top) * 1.0f, up, paint.color, 1f)
            } else if (Skin.era()) {
                EraDraw.frameBlock(canvas, x - blockW * p, top.toFloat(), x.toFloat(), bottom.toFloat(),
                    paint.color, d, Ui.cloak)
            } else if (Skin.collective()) {
                val cy = (top + bottom) / 2f
                val cx = x - blockW * 0.5f
                CollectiveDraw.curveTrace(canvas, x - blockW * p, cy, x.toFloat(), cy, 0.0f, paint.color, d, 0.7f)
                CollectiveDraw.node(canvas, cx, cy, min((bottom - top) * 0.55f, 8 * d) * p, paint.color, d, 1f)
            } else {
                canvas.drawRect(x - blockW * p, top.toFloat(), x.toFloat(), bottom.toFloat(), paint)
            }
            rects.add(RectF((x - blockW).toFloat(), top.toFloat(), x.toFloat(), bottom.toFloat()))
            x -= blockW + gap
        }
        return x
    }

    /** Pressed parts glow briefly (console buttons brighten when touched); cloaked parts are dimmed. */
    private fun lit(part: String, color: Int): Int {
        var c = color
        if (Ui.cloak) c = Ui.alpha(c, if (part == pressed) 1f else 0.7f)
        if (part != pressed) return c
        return pressedTint(c)
    }

    // ---- touch -----------------------------------------------------------------------------------

    /** The drawn part under a touch point, or null. HomeGestureDetector skips double-taps on frame parts. */
    fun partAt(x: Float, y: Float): String? {
        val pad = Ui.dp(context, PART_PAD_DP)
        val h = height
        for (i in topRects.indices) {
            val r = topRects[i]
            if (x >= r.left - pad / 2f && x <= r.right + pad / 2f && y <= r.bottom + pad) return "blockT$i"
        }
        for (i in botRects.indices) {
            val r = botRects[i]
            if (x >= r.left - pad / 2f && x <= r.right + pad / 2f && y >= r.top - pad) return "blockB$i"
        }
        if (topH > 0) {
            val side = x <= sidebarW && y <= topH + rIn + ext
            val bar = y <= topH + pad && x <= topBarEnd
            if (side || bar) return "elbowT"
        }
        if (botH > 0) {
            val side = x <= sidebarW && y >= h - (botH + rIn + ext)
            val bar = y >= h - botH - pad && x <= botBarEnd
            if (side || bar) return "elbowB"
        }
        return null
    }

    /** Tap / long-press handling for the drawn parts; touches elsewhere fall through to children. */
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val l = partListener ?: return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = partAt(e.x, e.y)
                if (pressed == null) return false
                longFired = false
                downX = e.x
                downY = e.y
                invalidate()
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (pressed != null && Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) >
                    ViewConfiguration.get(context).scaledTouchSlop
                ) {
                    removeCallbacks(longPress)
                    pressed = null
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                val p = pressed
                if (p != null && !longFired) {
                    pressed = null
                    invalidate()
                    l.onPart(p, this, false)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                pressed = null
                invalidate()
                return true
            }
            else -> return true
        }
    }

    /** Command elbow: convex outer arc, bar, concave inner arc, sidebar edge. */
    private fun buildElbow(barH: Int, barEndFull: Int) {
        val sw = sidebarW.toFloat()
        val bh = barH.toFloat()
        val ri = min(rIn.toFloat(), sw)
        val depth = bh + ri + ext
        val ro = min(rOut.toFloat(), min(sw, depth))
        val barEnd = sweptBarEnd(sw + ri, barEndFull.toFloat(), progress)
        path.reset()
        path.moveTo(0f, depth)
        path.lineTo(0f, ro)
        oval.set(0f, 0f, ro * 2, ro * 2)
        path.arcTo(oval, 180f, 90f, false)
        path.lineTo(barEnd, 0f)
        path.lineTo(barEnd, bh)
        path.lineTo(sw + ri, bh)
        oval.set(sw, bh, sw + ri * 2, bh + ri * 2)
        path.arcTo(oval, 270f, -90f, false)
        path.lineTo(sw, depth)
        path.close()
    }

    /** Bars sweep out from the elbows (skipped when animations are off). */
    fun reveal() {
        if (!Ui.animate(context)) {
            progress = 1f
            invalidate()
            return
        }
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.setDuration(REVEAL_MS)
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener { va ->
            progress = va.animatedValue as Float
            invalidate()
        }
        a.start()
    }

    companion object {
        /** Design sizes in dp (before zoom). */
        const val SIDEBAR_DP = 64f
        const val BAR_DP = 18f
        const val OUTER_RADIUS_DP = 42f
        const val INNER_RADIUS_DP = 18f
        const val ELBOW_EXTENSION_DP = 22f
        const val BLOCK_DP = 26f
        /** Room kept free next to the title/footer label. */
        private const val LABEL_CLEARANCE_DP = 48f
        /** Touch slop around drawn parts. */
        private const val PART_PAD_DP = 10f
        /** How much a pressed part brightens (per RGB channel). */
        const val PRESS_BRIGHTEN = 70
        private const val REVEAL_MS = 420L

        // ---- pure geometry (unit-tested) -------------------------------------------------------

        /** Vertical extent of an elbow: bar + inner radius + extension (0 when there is no bar). */
        @JvmStatic
        fun elbowSpanFor(barH: Int, rIn: Int, ext: Int): Int = if (barH > 0) barH + rIn + ext else 0

        /** Where a bar currently ends while sweeping out from `minEnd` to `fullEnd`. */
        @JvmStatic
        fun sweptBarEnd(minEnd: Float, fullEnd: Float, progress: Float): Float =
            max(minEnd, minEnd + (fullEnd - minEnd) * progress)

        /** A pressed part's colour: each RGB channel brightened by [PRESS_BRIGHTEN], opaque. */
        @JvmStatic
        fun pressedTint(color: Int): Int {
            val r = min(255, ((color shr 16) and 0xFF) + PRESS_BRIGHTEN)
            val g = min(255, ((color shr 8) and 0xFF) + PRESS_BRIGHTEN)
            val b = min(255, (color and 0xFF) + PRESS_BRIGHTEN)
            return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }
    }
}
