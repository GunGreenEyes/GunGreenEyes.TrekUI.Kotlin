package com.gungreeneyes.trekui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

/**
 * Base for the "press one control, the rest deploys" home layouts (Command console CASCADE,
 * Imperial TACTICAL DEPLOY; the Collective hub works the same way). The layout always fits the space it
 * is given (no scrolling). It starts folded down to its master control; tapping the master
 * deploys the tiles, tapping again folds them, and it folds by itself when the launcher goes to
 * the background. Long-pressing the master opens the full app list.
 *
 * The state the subclasses read is [JvmField] so the fields do not clash with the [RevealHost]
 * setters of the same name (`setDim`, `setPinned`, `setCount`, ...).
 */
abstract class RevealLayout(c: Context, p: Palette, onMasterLong: View.OnLongClickListener?) :
    ViewGroup(c), RevealHost {

    @JvmField protected val d: Float = c.resources.displayMetrics.density
    @JvmField protected val pal: Palette = p
    @JvmField protected val master: View = View(c)
    @JvmField protected val nodes: MutableList<View> = ArrayList()
    @JvmField protected val empties: MutableList<Boolean> = ArrayList()
    @JvmField protected val colors: MutableList<Int> = ArrayList()

    @JvmField protected var progress: Float = if (open) 1f else 0f
    @JvmField protected var dim: Float = 1f
    private var anim: ValueAnimator? = null
    @JvmField protected var pinned: Boolean = false
    @JvmField protected var masterAction: Runnable? = null
    @JvmField protected var masterTitle: String? = null
    @JvmField protected var count: Int = -1

    init {
        setWillNotDraw(false)
        clipChildren = false
        master.background = Ui.touchArea(c, 12f)
        master.setOnClickListener { v ->
            Ui.tap(v)
            val action = masterAction
            if (action != null) {
                action.run()
                return@setOnClickListener
            }
            Sfx.play(context, if (open) Sfx.CLOSE else Sfx.OPEN)
            setOpen(!open, true)
        }
        master.setOnLongClickListener(onMasterLong)
        addView(master)
        describe()
    }

    private fun describe() {
        master.contentDescription = (if (open) "Hide" else "Show") + " home tiles. Long-press for all apps."
    }

    /**
     * Width the tile set may use inside a view of width `w`: all of it on phones, capped
     * on wide screens (landscape, tablets) so keys do not stretch into long bars.
     */
    protected fun contentWidth(w: Int): Float {
        val cap = ScreenMetrics.of(context).scaledPx(MAX_CONTENT_WIDTH_DP).toFloat()
        return min(w.toFloat(), max(cap, dp(320f)))
    }

    /**
     * Row height that fits `rows` rows (with gaps) into `avail` px: within lo..hi
     * when there is room, shrinking below lo (to 26dp at most) rather than overflowing.
     */
    protected fun fitRowHeight(avail: Float, rows: Int, gap: Float, lo: Float, hi: Float): Float {
        val each = (avail - gap * (rows - 1)) / max(1, rows)
        val min = min(lo, max(dp(26f), each))
        return max(min, min(hi, each))
    }

    protected fun dp(v: Float): Float = v * d

    fun setOpen(o: Boolean, animated: Boolean) = setOpen(o, animated, null)

    override fun setPinned(pinned: Boolean) {
        this.pinned = pinned
        apply()
    }

    override fun setMasterAction(action: Runnable?) {
        masterAction = action
    }

    override fun setMasterTitle(title: String?) {
        masterTitle = title
        invalidate()
    }

    override fun setCount(n: Int) {
        count = n
        invalidate()
    }

    protected fun shownCount(): Int = if (count >= 0) count else nodes.size

    override fun setOpen(open: Boolean, animated: Boolean, onEnd: Runnable?) {
        Companion.open = open
        describe()
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
        a.interpolator = if (open) DecelerateInterpolator(1.2f) else AccelerateInterpolator(1.2f)
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

    override fun setDim(v: Float) {
        dim = v
        apply()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE && open) setOpen(false, false)
    }

    /** Position, scale and fade the tiles for the current progress, then redraw. */
    protected abstract fun apply()

    companion object {
        /** Deploy and fold animation lengths. */
        private const val OPEN_MS = 760L
        private const val CLOSE_MS = 380L

        /** Widest the deployed tile set grows at the design scale; wider screens centre it. */
        const val MAX_CONTENT_WIDTH_DP = 640f

        /** Folded/deployed state; static so it survives the rebuilds on theme or size changes. */
        private var open = false

        @JvmStatic
        fun isOpen(): Boolean = open

        /** Clamps to 0..1. */
        @JvmStatic
        protected fun clamp(v: Float): Float = if (v < 0) 0f else if (v > 1) 1f else v

        /** Quadratic ease-out of a clamped 0..1 value. */
        @JvmStatic
        protected fun ease(v: Float): Float {
            val c = clamp(v)
            return 1 - (1 - c) * (1 - c)
        }

        /** Hides a tile while it is fully folded (so it cannot be tapped), shows it otherwise. */
        @JvmStatic
        protected fun show(v: View, e: Float) {
            val vis = if (e <= 0.02f) INVISIBLE else VISIBLE
            if (v.visibility != vis) v.visibility = vis
        }
    }
}
