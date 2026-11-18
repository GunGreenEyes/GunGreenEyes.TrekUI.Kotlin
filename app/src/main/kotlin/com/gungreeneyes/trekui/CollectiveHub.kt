package com.gungreeneyes.trekui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Collective "HUB AND SPOKE" home layout. The tiles become nodes of different sizes arranged in orbits
 * around a central hub, linked by traces of different lengths and weights. Inner nodes run
 * straight to the hub; most outer nodes branch off their nearest inner node, a few run all the
 * way in on long traces.
 *
 * It always fits the space it is given: no scrolling. Node sizes shrink when there are many
 * tiles, a spacing pass keeps nodes apart, and each label is placed on the side of its node away
 * from the hub (beside it, or above/below), choosing a spot that doesn't cover another node,
 * another label or a trace.
 *
 * The network starts folded into the hub. Tapping the hub extends it (inner orbit first); tapping
 * again folds it back, and it folds by itself when the launcher goes to the background. Long-press
 * the hub for the full app list.
 *
 * A zero-sized or not-yet-laid-out view draws nothing of its own: no geometry derived from an
 * empty size or a non-finite scale ever reaches [CollectiveDraw] (software rasterizers on some
 * emulator images hang on NaN or infinite circles and gradients).
 */
class CollectiveHub(c: Context, p: Palette, onHubLong: View.OnLongClickListener?) : ViewGroup(c), RevealHost {
    private val d: Float = c.resources.displayMetrics.density
    private val line: Int = p.c(0)
    private val hi: Int = p.c(1)
    private val hubTouch: View = View(c)
    private val nodes = ArrayList<View>()
    private val contents = ArrayList<View>()
    private val labels = ArrayList<TextView>()
    private val empties = ArrayList<Boolean>()
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Node size multiplier: below 1 when crowded, above 1 on large screens (up to the UI scale). */
    private var scale = 1f
    private var hubX = 0f
    private var hubY = 0f
    private var hubR = 0f
    private var nx = FloatArray(0)
    private var ny = FloatArray(0)
    private var nr = FloatArray(0)
    private var orbit = IntArray(0)
    private var parent = IntArray(0)
    private var longSpoke = BooleanArray(0)

    /** Label offsets from the node centre (left/top of the label), chosen during layout. */
    private var lx = FloatArray(0)
    private var ly = FloatArray(0)

    private var progress = if (open) 1f else 0f
    private var anim: ValueAnimator? = null
    private var dimLevel = 1f
    private var masterRun: Runnable? = null
    private var masterText: String? = null
    private var shown = -1

    init {
        setWillNotDraw(false)
        clipChildren = false
        hubTouch.background = Ui.touchArea(c, 60f)
        hubTouch.setOnClickListener { v ->
            Ui.tap(v)
            val action = masterRun
            if (action != null) {
                action.run()
                return@setOnClickListener
            }
            Sfx.play(context, if (open) Sfx.CLOSE else Sfx.CONFIRM)
            setOpen(!open, true)
        }
        hubTouch.setOnLongClickListener(onHubLong)
        addView(hubTouch)
        hint.typeface = Ui.font(c, false)
        hint.textAlign = Paint.Align.CENTER
        hint.textSize = 11 * d * Skin.textScale()
        hint.letterSpacing = 0.25f
        arc.style = Paint.Style.STROKE
        updateDescription()
    }

    /**
     * Adds a node. content is centred in the node (the icon, or a "+" for an empty tile); the
     * returned view is the node's touch target, for the caller's tap / long-press handlers.
     */
    override fun addNode(content: View, label: String, color: Int, empty: Boolean): View {
        val node = FrameLayout(context)
        node.background = Ui.touch(nodeBackground(color, empty))
        node.addView(content, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        node.contentDescription = label
        val lab = Ui.oneLine(Ui.text(context, label.uppercase(Locale.getDefault()), 11f,
            if (empty) Ui.alpha(Skin.ink(), 0.5f) else Skin.ink()))
        lab.maxWidth = Math.round(LABEL_MAX_DP * d)
        lab.setPadding(Math.round(2 * d), 0, Math.round(2 * d), 0)
        // the label is part of the node: tapping it does what tapping the node does
        lab.setOnClickListener { node.performClick() }
        lab.setOnLongClickListener { node.performLongClick() }
        lab.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        nodes.add(node)
        contents.add(content)
        labels.add(lab)
        empties.add(empty)
        addView(node)
        addView(lab)
        return node
    }

    /** Accessibility text for the hub, matching what tapping it will do. */
    private fun updateDescription() {
        hubTouch.contentDescription = if (open) "Collective hub: hide interface nodes. Long-press for all apps."
        else "Collective hub: show interface nodes. Long-press for all apps."
    }

    fun setOpen(o: Boolean, animated: Boolean) = setOpen(o, animated, null)

    override fun setPinned(pinned: Boolean) {}

    override fun setMasterAction(action: Runnable?) {
        masterRun = action
    }

    override fun setMasterTitle(title: String?) {
        masterText = title
        invalidate()
    }

    override fun setCount(n: Int) {
        shown = n
        invalidate()
    }

    override fun setOpen(open: Boolean, animated: Boolean, onEnd: Runnable?) {
        Companion.open = open
        updateDescription()
        anim?.cancel()
        val target = if (open) 1f else 0f
        if (!animated || !Ui.animate(context)) {
            progress = target
            apply()
            onEnd?.run()
            return
        }
        val a = ValueAnimator.ofFloat(progress, target)
        anim = a
        a.duration = if (open) OPEN_MS else CLOSE_MS
        a.interpolator = if (open) DecelerateInterpolator(1.4f) else AccelerateInterpolator(1.2f)
        a.addUpdateListener { va ->
            progress = va.animatedValue as Float
            apply()
        }
        if (onEnd != null) {
            a.addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (!cancelled) onEnd.run()
                }
            })
        }
        a.start()
    }

    /** Dim the network while an info panel is open over it. */
    override fun setDim(v: Float) {
        dimLevel = v
        apply()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE && open) setOpen(false, false)
    }

    /** Deploy progress of node i: inner orbits first, slightly staggered within an orbit. */
    private fun nodeProgress(i: Int): Float {
        if (i >= orbit.size) return progress
        val delay = min(0.45f, orbit[i] * 0.2f + (i % 5) * 0.03f)
        val e = (progress - delay) / (1f - delay)
        return if (e < 0) 0f else if (e > 1) 1f else e
    }

    /** Moves, scales and fades every node and label for the current progress, then redraws. */
    private fun apply() {
        var i = 0
        while (i < nodes.size && i < nx.size) {
            val v = nodes[i]
            val lab = labels[i]
            val e = nodeProgress(i)
            val tx = (hubX - nx[i]) * (1 - e)
            val ty = (hubY - ny[i]) * (1 - e)
            v.pivotX = v.width / 2f
            v.pivotY = v.height / 2f
            v.translationX = tx
            v.translationY = ty
            v.scaleX = 0.25f + 0.75f * e
            v.scaleY = 0.25f + 0.75f * e
            v.alpha = e * dimLevel
            lab.translationX = tx
            lab.translationY = ty
            val la = max(0f, (e - 0.6f) / 0.4f)
            lab.alpha = la * dimLevel
            val vis = if (e <= 0.02f) INVISIBLE else VISIBLE
            if (v.visibility != vis) v.visibility = vis
            val lvis = if (la <= 0.02f) INVISIBLE else VISIBLE
            if (lab.visibility != lvis) lab.visibility = lvis
            i++
        }
        invalidate()
    }

    // ---- measuring and layout ----------------------------------------------------------------------

    private fun baseRadius(i: Int): Float = SIZES[i % SIZES.size] * d

    private fun radius(i: Int): Float = max(MIN_NODE_DP * d, baseRadius(i) * scale)

    /**
     * Chooses the node scale so the network takes about [FILL_FRACTION] of the area
     * (never below [MIN_SCALE], never above the screen's UI scale), then measures nodes,
     * labels and the hub.
     */
    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws)
        var h = MeasureSpec.getSize(hs)
        if (MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED || h == 0) h = Math.round(w * 1.5f)
        val n = nodes.size
        // shrink nodes so the whole network (nodes + labels) takes about half the space
        var need = 0f
        for (i in 0 until n) {
            val r = baseRadius(i) + 8 * d
            need += (PI * r * r).toFloat() + 70 * d * 16 * d
        }
        val avail = w * h.toFloat() * FILL_FRACTION - (PI * (42 * d).toDouble().pow(2)).toFloat()
        // Guard: a UI scale read from a zero-sized display could be NaN or infinite, and NaN
        // survives max/min, so it would turn every radius below into NaN.
        val uiScale = ScreenMetrics.of(context).uiScale
        val maxScale = if (uiScale.isFinite()) max(1f, uiScale) else 1f
        // need > 0 guards the division; the result is checked too, so scale is always finite
        val fit = if (need <= 0) 1f else max(MIN_SCALE, min(maxScale, sqrt(max(1f, avail) / need)))
        scale = if (fit.isFinite()) fit else 1f
        for (i in 0 until n) {
            val side = Math.round(2 * radius(i) + 4 * d)
            val content = contents[i]
            val icon = Math.round(if (empties[i]) side.toFloat() else radius(i) * 1.05f)
            val lp = content.layoutParams
            if (lp.width != icon) {
                lp.width = icon
                lp.height = icon
                content.layoutParams = lp
            }
            nodes[i].measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
            labels[i].measure(MeasureSpec.makeMeasureSpec(Math.round(LABEL_MAX_DP * d), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        }
        val hub = Math.round(2 * hubRadius(w, h))
        hubTouch.measure(MeasureSpec.makeMeasureSpec(hub, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hub, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    /** Hub radius: 38dp (less on small views), shrinking a little with the node scale. */
    private fun hubRadius(w: Int, h: Int): Float = min(38 * d, min(w, h) * 0.12f) * (0.8f + 0.2f * scale)

    /**
     * Lays the nodes on elliptical orbits around the centred hub (the ellipse follows the view's
     * aspect, so landscape spreads sideways), separates them, routes the traces and places labels.
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        val n = nodes.size
        hubX = w * 0.5f
        hubY = h * 0.5f
        hubR = hubRadius(w, h)
        nx = FloatArray(n)
        ny = FloatArray(n)
        nr = FloatArray(n)
        orbit = IntArray(n)
        parent = IntArray(n)
        longSpoke = BooleanArray(n)
        lx = FloatArray(n)
        ly = FloatArray(n)
        var orbits = 0
        var total = 0
        while (total < n) {
            total += CAP[min(orbits, CAP.size - 1)]
            orbits++
        }
        orbits = max(1, orbits)
        // orbit radii: fractions of the space around the hub, so the network fills the view
        val ax = w / 2f - 34 * d * scale
        val ay = h / 2f - 34 * d * scale - 10 * d
        var start = 0
        var o = 0
        while (start < n) {
            // count >= 1 here (start < n), so the angle step never divides by zero
            val count = min(CAP[min(o, CAP.size - 1)], n - start)
            val f = if (orbits == 1) 0.62f else 0.5f + 0.48f * o / (orbits - 1)
            val base = -PI / 2 + (if (o % 2 == 1) PI / count else 0.0) + o * 0.21
            for (k in 0 until count) {
                val i = start + k
                val jitter = ((i * 37) % 11 - 5) * 0.035
                val stretch = 1f + (((i * 53) % 9) - 4) * 0.035f
                val a = base + k * 2 * PI / count + jitter
                nx[i] = hubX + cos(a).toFloat() * ax * f * stretch
                ny[i] = hubY + sin(a).toFloat() * ay * f * stretch
                nr[i] = radius(i)
                orbit[i] = o
            }
            start += count
            o++
        }
        relax(w, h)
        // routing: inner orbit → hub; outer → nearest inner (and every third also → hub, long and thin)
        for (i in 0 until n) {
            parent[i] = if (orbit[i] == 0) -1 else nearestInner(i)
            longSpoke[i] = orbit[i] > 0 && (parent[i] < 0 || i % 3 == 0)
        }
        placeLabels(w, h)
        for (i in 0 until n) {
            val v = nodes[i]
            val s = v.measuredWidth
            v.layout(Math.round(nx[i] - s / 2f), Math.round(ny[i] - s / 2f), Math.round(nx[i] - s / 2f) + s, Math.round(ny[i] - s / 2f) + s)
            val lab = labels[i]
            val lw = lab.measuredWidth
            val lh = lab.measuredHeight
            val left = Math.round(nx[i] + lx[i])
            val top = Math.round(ny[i] + ly[i])
            lab.layout(left, top, left + lw, top + lh)
        }
        val hs = hubTouch.measuredWidth
        hubTouch.layout(Math.round(hubX - hs / 2f), Math.round(hubY - hs / 2f), Math.round(hubX + hs / 2f), Math.round(hubY + hs / 2f))
        apply()
    }

    /** Push apart nodes that would overlap; keep them clear of the hub and inside the view. */
    private fun relax(w: Int, h: Int) {
        val n = nx.size
        val margin = 6 * d
        for (it in 0 until 80) {
            var moved = false
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    var dx = nx[j] - nx[i]
                    var dy = ny[j] - ny[i]
                    val need = nr[i] + nr[j] + 26 * d * scale + 8 * d // room for a label between
                    var dist = sqrt(dx * dx + dy * dy)
                    if (dist >= need) continue
                    // dist is kept >= 0.5 so the unit vector below never divides by zero
                    if (dist < 0.5f) {
                        dx = 1f
                        dy = 0.3f
                        dist = 1.04f
                    }
                    val push = (need - dist) / 2f + 0.5f
                    nx[i] -= dx / dist * push
                    ny[i] -= dy / dist * push
                    nx[j] += dx / dist * push
                    ny[j] += dy / dist * push
                    moved = true
                }
                val hx = nx[i] - hubX
                val hy = ny[i] - hubY
                val hd = sqrt(hx * hx + hy * hy)
                val hn = hubR + nr[i] + 20 * d
                if (hd < hn && hd > 0.5f) {
                    nx[i] = hubX + hx / hd * hn
                    ny[i] = hubY + hy / hd * hn
                    moved = true
                }
                nx[i] = max(nr[i] + margin, min(w - nr[i] - margin, nx[i]))
                ny[i] = max(nr[i] + margin, min(h - nr[i] - margin, ny[i]))
            }
            if (!moved) break
        }
    }

    /** The closest node on the next orbit in, or -1. */
    private fun nearestInner(i: Int): Int {
        var best = -1
        var bd = Float.MAX_VALUE
        for (j in nx.indices) {
            if (orbit[j] != orbit[i] - 1) continue
            val dx = nx[j] - nx[i]
            val dy = ny[j] - ny[i]
            val dd = dx * dx + dy * dy
            if (dd < bd) {
                bd = dd
                best = j
            }
        }
        return best
    }

    /**
     * Each label goes on the side of its node facing away from the hub: beside it (left or right)
     * when the node is off to the side, above or below when it's mostly above or below. If that
     * spot hits another node, label or trace, the other sides are tried in turn.
     */
    private fun placeLabels(w: Int, h: Int) {
        val n = nx.size
        val placed = arrayOfNulls<RectF>(n)
        val gap = 5 * d
        for (i in 0 until n) {
            val lab = labels[i]
            val lw = lab.measuredWidth.toFloat()
            val lh = lab.measuredHeight.toFloat()
            val a = atan2((ny[i] - hubY).toDouble(), (nx[i] - hubX).toDouble())
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            // candidate offsets (label left/top relative to node centre): right, left, below, above
            val cand = arrayOf(
                floatArrayOf(nr[i] + gap, -lh / 2f), floatArrayOf(-nr[i] - gap - lw, -lh / 2f),
                floatArrayOf(-lw / 2f, nr[i] + gap * 0.6f), floatArrayOf(-lw / 2f, -nr[i] - gap * 0.6f - lh))
            val order = if (abs(c) > 0.42f) {
                if (c > 0) intArrayOf(0, 2, 3, 1) else intArrayOf(1, 2, 3, 0)
            } else if (s > 0) {
                intArrayOf(2, if (c >= 0) 0 else 1, if (c >= 0) 1 else 0, 3)
            } else {
                intArrayOf(3, if (c >= 0) 0 else 1, if (c >= 0) 1 else 0, 2)
            }
            var best = order[0]
            var bestCost = Float.MAX_VALUE
            for (k in order) {
                val rr = RectF(nx[i] + cand[k][0], ny[i] + cand[k][1], nx[i] + cand[k][0] + lw, ny[i] + cand[k][1] + lh)
                val cost = labelCost(rr, i, placed, w, h)
                if (cost < bestCost - 0.01f) {
                    bestCost = cost
                    best = k
                }
                if (cost == 0f) break
            }
            lx[i] = cand[best][0]
            ly[i] = cand[best][1]
            // keep inside the view
            val left = max(2 * d, min(w - lw - 2 * d, nx[i] + lx[i]))
            val top = max(0f, min(h - lh, ny[i] + ly[i]))
            lx[i] = left - nx[i]
            ly[i] = top - ny[i]
            placed[i] = RectF(left, top, left + lw, top + lh)
        }
    }

    /** How bad a label position is: 0 = clear; more for each node, label or trace it covers. */
    private fun labelCost(rr: RectF, self: Int, placed: Array<RectF?>, w: Int, h: Int): Float {
        var cost = 0f
        if (rr.left < 0 || rr.right > w || rr.top < 0 || rr.bottom > h) cost += 3f
        val inner = RectF(rr)
        inner.inset(-2 * d, -1 * d)
        // other nodes and the hub
        for (j in nx.indices) {
            if (j == self) continue
            if (circleHits(inner, nx[j], ny[j], nr[j])) cost += 4f
        }
        if (circleHits(inner, hubX, hubY, hubR + 4 * d)) cost += 4f
        for (p in placed) if (p != null && RectF.intersects(inner, p)) cost += 4f
        // traces
        for (j in nx.indices) {
            if (orbit[j] == 0 || longSpoke[j]) if (segmentHits(inner, hubX, hubY, nx[j], ny[j])) cost += 1.5f
            if (parent[j] >= 0) if (segmentHits(inner, nx[parent[j]], ny[parent[j]], nx[j], ny[j])) cost += 1.5f
        }
        return cost
    }

    // ---- drawing ----------------------------------------------------------------------------------------

    /** A trace between two nodes, starting and ending at their rims. */
    private fun spoke(cv: Canvas, x0: Float, y0: Float, r0: Float, x1: Float, y1: Float, r1: Float, bend: Float, wDp: Float, alpha: Float) {
        val a = atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())
        val sx = x0 + cos(a).toFloat() * r0
        val sy = y0 + sin(a).toFloat() * r0
        val ex = x1 - cos(a).toFloat() * r1
        val ey = y1 - sin(a).toFloat() * r1
        CollectiveDraw.traceW(cv, CollectiveDraw.curve(sx, sy, ex, ey, bend), line, d, alpha * dimLevel, wDp)
    }

    /** Draws the crosshair, hub arcs, traces and vias under the nodes, then the hub and its hint. */
    override fun dispatchDraw(cv: Canvas) {
        val w = width
        val h = height
        // Guard: with no size there is no hub geometry (hubR 0, every node clamped onto one spot),
        // so draw none of the network; the children still draw (they guard their own size).
        if (w <= 0 || h <= 0) {
            super.dispatchDraw(cv)
            return
        }
        val n = nx.size
        val cx = FloatArray(n)
        val cy = FloatArray(n)
        val ep = FloatArray(n)
        for (i in 0 until n) {
            ep[i] = nodeProgress(i)
            cx[i] = hubX + (nx[i] - hubX) * ep[i]
            cy[i] = hubY + (ny[i] - hubY) * ep[i]
        }
        val reach = 0.35f + 0.65f * progress
        val cross = CollectiveDraw.tmp()
        cross.moveTo(hubX - (hubX - w * 0.03f) * reach, hubY)
        cross.lineTo(hubX + (w * 0.97f - hubX) * reach, hubY)
        cross.moveTo(hubX, hubY - (hubY - 6 * d) * reach)
        cross.lineTo(hubX, hubY + (h - hubY) * reach)
        CollectiveDraw.traceW(cv, cross, hi, d, 0.3f * dimLevel, 1f)
        arc.strokeWidth = d
        arc.color = Ui.alpha(line, (if (Ui.cloak) 0.12f else 0.22f) * dimLevel)
        val ar = hubR * (1.35f + 0.55f * progress)
        for (k in 0 until 6) cv.drawArc(hubX - ar, hubY - ar, hubX + ar, hubY + ar, k * 60 + 8 + 30 * progress, 38f, false, arc)
        for (i in 0 until n) {
            if (ep[i] <= 0.01f) continue
            val bend = ((i % 3) - 1) * 0.12f * ep[i]
            val ri = nr[i] * (0.25f + 0.75f * ep[i])
            if (orbit[i] == 0) spoke(cv, hubX, hubY, hubR, cx[i], cy[i], ri, bend, 2.8f * (0.7f + 0.3f * scale), ep[i])
            if (longSpoke[i]) spoke(cv, hubX, hubY, hubR, cx[i], cy[i], ri, bend * 1.6f, 1.1f, 0.7f * ep[i])
            val j = parent[i]
            if (j >= 0) spoke(cv, cx[j], cy[j], nr[j] * (0.25f + 0.75f * ep[j]), cx[i], cy[i], ri, -bend, if (orbit[i] == 1) 1.9f else 1.4f, 0.9f * ep[i])
        }
        for (i in 0 until n) {
            val settle = max(0f, (ep[i] - 0.7f) / 0.3f) * dimLevel
            if (settle <= 0) continue
            if (orbit[i] == 0 && i % 2 == 0) CollectiveDraw.via(cv, (hubX + nx[i]) / 2f, (hubY + ny[i]) / 2f, 3 * d, line, d, 0.9f * settle)
        }
        val hr = hubR * (0.9f + 0.1f * progress)
        CollectiveDraw.ringNode(cv, hubX, hubY, hr, hi, d, 0.9f)
        CollectiveDraw.node(cv, hubX, hubY, hr * 0.55f, line, d, 1f)
        if (progress < 0.6f) {
            hint.color = Ui.alpha(hi, (if (Ui.cloak) 0.4f else 0.75f) * (1 - progress / 0.6f))
            val total = if (shown >= 0) shown else n
            val text = masterText ?: if (total > 0) "ACCESS $total NODES" else "NO NODES"
            cv.drawText(text, hubX, hubY + hubR + 20 * d, hint)
        }
        super.dispatchDraw(cv)
    }

    /** Node background: drawn to fit whatever size the node is laid out at. */
    private fun nodeBackground(color: Int, empty: Boolean): Drawable =
        object : Drawable() {
            override fun draw(cv: Canvas) {
                val b = bounds
                val rr = min(b.width(), b.height()) / 2f - 2 * d
                // Guard: empty or tiny bounds give a zero or negative radius; draw nothing then.
                if (rr <= 0f) return
                if (empty) CollectiveDraw.ringNode(cv, b.exactCenterX(), b.exactCenterY(), rr, Skin.glow(color), d, 0.55f)
                else CollectiveDraw.node(cv, b.exactCenterX(), b.exactCenterY(), rr, Skin.glow(color), d, 1f)
            }

            override fun setAlpha(alpha: Int) {}

            override fun setColorFilter(colorFilter: ColorFilter?) {}

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }

    companion object {
        /** Node radius pattern in dp: deliberately uneven, like the Collective displays. */
        private val SIZES = intArrayOf(27, 20, 24, 18, 30, 21, 19, 25, 17, 23, 28, 18, 22, 20, 26, 19, 24, 21)

        /** Most nodes per orbit, innermost first (the last value repeats for any further orbits). */
        private val CAP = intArrayOf(5, 7, 9, 99)

        /** Deploy and fold animation lengths. */
        private const val OPEN_MS = 620L
        private const val CLOSE_MS = 340L

        /** Smallest node radius, widest label, and the smallest node scale when space is tight. */
        private const val MIN_NODE_DP = 13f
        private const val LABEL_MAX_DP = 104f
        private const val MIN_SCALE = 0.55f

        /** Share of the view the whole network (nodes plus labels) should occupy. */
        private const val FILL_FRACTION = 0.5f

        /** Folded/extended state survives rebuilds while the launcher runs. */
        private var open = false

        @JvmStatic
        fun isOpen(): Boolean = open

        /** True when a circle overlaps a rectangle. */
        private fun circleHits(r: RectF, cx: Float, cy: Float, rad: Float): Boolean {
            val qx = max(r.left, min(cx, r.right))
            val qy = max(r.top, min(cy, r.bottom))
            val dx = cx - qx
            val dy = cy - qy
            return dx * dx + dy * dy < rad * rad
        }

        /** True when a straight trace passes through a rectangle (sampled at 11 points). */
        private fun segmentHits(r: RectF, x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
            for (k in 1 until 12) {
                val t = k / 12f
                if (r.contains(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)) return true
            }
            return false
        }
    }
}
