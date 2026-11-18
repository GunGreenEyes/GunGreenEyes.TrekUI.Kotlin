package com.gungreeneyes.trekui

import android.graphics.Rect
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup

/**
 * Watches the home window's touches for the assignable gestures in [HomeGestures] and runs
 * whatever the user assigned. `MainActivity.GestureRoot` offers every event here first:
 *
 * - [Result.PASS]: not ours; GestureRoot carries on (its swipe up / down still works).
 * - [Result.CANCEL_CHILDREN]: a gesture just started or finished; the children get an
 *   ACTION_CANCEL in place of this event so no tile also fires.
 * - [Result.CONSUME]: the rest of a two-finger touch we already took over.
 *
 * Gestures only count while the plain home screen is in front (no drawer, shade, dialer, group
 * panel or other overlay), a double-tap only on empty space (not on a key, tile, frame part or other
 * control), and a horizontal swipe never starts over something that scrolls sideways. A gesture with
 * nothing assigned is never taken, so the default home behaves exactly as before.
 */
internal class HomeGestureDetector(private val activity: MainActivity, private val host: Host) {

    /** What GestureRoot should do with the event it offered. */
    enum class Result { PASS, CANCEL_CHILDREN, CONSUME }

    /**
     * Marker for home views that handle raw touches themselves (an `OnTouchListener` or an
     * overridden `onTouchEvent`, e.g. the station consoles' displays). Taps on them never count
     * toward the DOUBLE TAP gesture, exactly like taps on clickable controls.
     */
    interface OwnsTouches

    /** The home window the detector serves. */
    interface Host {
        /** True when no drawer, shade or dialer is open. */
        fun homeIdle(): Boolean

        /** The home content view (the first child of the root). */
        fun homeView(): View

        /** The root view (the window content). */
        fun rootView(): ViewGroup
    }

    private val density: Float = activity.resources.displayMetrics.density
    private val taps = HomeGestures.TapTracker()
    private val hit = Rect()

    private var velocity: VelocityTracker? = null

    /** True while this touch is on the bare home screen. */
    private var idle = false

    /** True when the first finger went down on a control (no double-tap). */
    private var onControl = false

    /** True when the first finger went down over something that scrolls sideways (no swipe). */
    private var onHorizontalScroller = false

    /** True when this press is the second tap of a possible double-tap. */
    private var secondTap = false

    /** True from the moment a two-finger touch is taken over until all fingers lift. */
    private var consuming = false

    /** True while two fingers are down in a taken-over touch. */
    private var twoFingers = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var startCx = 0f
    private var startCy = 0f
    private var startSpan = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var lastSpan = 0f

    /** Offers one touch event; see [Result]. */
    fun onEvent(e: MotionEvent): Result {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onDown(e)
                return Result.PASS
            }
            MotionEvent.ACTION_POINTER_DOWN -> return onPointerDown(e)
            MotionEvent.ACTION_MOVE -> {
                if (consuming) {
                    if (twoFingers && e.pointerCount >= 2) track(e, false)
                    return Result.CONSUME
                }
                velocity?.addMovement(e)
                return Result.PASS
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (consuming) {
                    if (twoFingers) {
                        twoFingers = false
                        val g = HomeGestures.classifyTwoFinger(dp(lastCx - startCx), dp(lastCy - startCy), dp(startSpan), dp(lastSpan))
                        if (g != null) fire(g)
                    }
                    return Result.CONSUME
                }
                return Result.PASS
            }
            MotionEvent.ACTION_UP -> return onUp(e)
            MotionEvent.ACTION_CANCEL -> {
                val wasConsuming = consuming
                reset()
                taps.reset()
                return if (wasConsuming) Result.CONSUME else Result.PASS
            }
            else -> return if (consuming) Result.CONSUME else Result.PASS
        }
    }

    private fun onDown(e: MotionEvent) {
        reset()
        downX = e.rawX
        downY = e.rawY
        downTime = e.eventTime
        idle = host.homeIdle() && topChildAt(downX, downY) === host.homeView()
        if (!idle) {
            taps.reset()
            return
        }
        onControl = isControlAt(host.homeView(), downX, downY)
        onHorizontalScroller = scrollsSidewaysAt(host.homeView(), downX, downY)
        secondTap = !onControl && taps.isSecondTap(downTime, dp(downX), dp(downY))
        velocity = VelocityTracker.obtain().also { it.addMovement(e) }
    }

    /** A second finger: take the touch over when it lands on the bare home and a two-finger gesture is assigned. */
    private fun onPointerDown(e: MotionEvent): Result {
        if (consuming) return Result.CONSUME
        taps.reset()
        secondTap = false
        if (!idle || e.pointerCount != 2 || !HomeGestures.anyTwoFinger(activity.prefs)) return Result.PASS
        consuming = true
        twoFingers = true
        recycleVelocity()
        track(e, true)
        return Result.CANCEL_CHILDREN
    }

    private fun onUp(e: MotionEvent): Result {
        if (consuming) {
            reset()
            return Result.CONSUME
        }
        var result = Result.PASS
        val v = velocity
        if (idle && v != null) {
            v.addMovement(e)
            v.computeCurrentVelocity(1000)
            val dx = e.rawX - downX
            val dy = e.rawY - downY
            val swipe = if (onHorizontalScroller) null else HomeGestures.classifySwipe(dp(dx), dp(dy), dp(v.xVelocity))
            if (swipe != null && fire(swipe)) {
                taps.reset()
                result = Result.CANCEL_CHILDREN
            } else if (!onControl && HomeGestures.isTap(e.eventTime - downTime, dp(Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()))) {
                if (secondTap && fire(HomeGestures.DOUBLE_TAP)) {
                    taps.reset()
                } else {
                    taps.tapped(e.eventTime, dp(e.rawX), dp(e.rawY))
                }
            } else {
                taps.reset()
            }
        }
        reset()
        return result
    }

    /** Records the two fingers' midpoint and distance (at the start when `start`). */
    private fun track(e: MotionEvent, start: Boolean) {
        val x0 = e.getX(0)
        val y0 = e.getY(0)
        val x1 = e.getX(1)
        val y1 = e.getY(1)
        val cx = (x0 + x1) / 2f
        val cy = (y0 + y1) / 2f
        val span = Math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble()).toFloat()
        if (start) {
            startCx = cx
            startCy = cy
            startSpan = span
        }
        lastCx = cx
        lastCy = cy
        lastSpan = span
    }

    /** Runs the gesture's assignment after this event finishes dispatching; false when none is assigned. */
    private fun fire(gesture: String): Boolean {
        val tile = HomeGestures.assigned(activity.prefs, gesture) ?: return false
        val anchor = host.rootView()
        anchor.post { activity.launchTile(tile, anchor) }
        return true
    }

    private fun reset() {
        consuming = false
        twoFingers = false
        secondTap = false
        recycleVelocity()
    }

    private fun recycleVelocity() {
        velocity?.recycle()
        velocity = null
    }

    private fun dp(px: Float): Float = px / density

    // ---- hit testing (raw screen coordinates) --------------------------------------------------

    /** The topmost visible child of the root under the point, or null. */
    private fun topChildAt(rawX: Float, rawY: Float): View? {
        val root = host.rootView()
        for (i in root.childCount - 1 downTo 0) {
            val child = root.getChildAt(i)
            if (child.visibility == View.VISIBLE && contains(child, rawX, rawY)) return child
        }
        return null
    }

    /** True when a tappable view (or a drawn console frame part) lies under the point inside `v`. */
    private fun isControlAt(v: View, rawX: Float, rawY: Float): Boolean {
        if (v.visibility != View.VISIBLE || !contains(v, rawX, rawY)) return false
        if (v !== host.homeView() && (v.isClickable || v.isLongClickable || v is OwnsTouches)) return true
        if (v is ConsoleFrame) {
            val at = IntArray(2)
            v.getLocationOnScreen(at)
            if (v.partAt(rawX - at[0], rawY - at[1]) != null) return true
        }
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                if (isControlAt(v.getChildAt(i), rawX, rawY)) return true
            }
        }
        return false
    }

    /** True when something under the point can scroll horizontally (its own swipes win). */
    private fun scrollsSidewaysAt(v: View, rawX: Float, rawY: Float): Boolean {
        if (v.visibility != View.VISIBLE || !contains(v, rawX, rawY)) return false
        if (v.canScrollHorizontally(1) || v.canScrollHorizontally(-1)) return true
        if (v is ViewGroup) {
            for (i in v.childCount - 1 downTo 0) {
                if (scrollsSidewaysAt(v.getChildAt(i), rawX, rawY)) return true
            }
        }
        return false
    }

    private fun contains(v: View, rawX: Float, rawY: Float): Boolean {
        if (!v.getGlobalVisibleRect(hit)) return false
        // getGlobalVisibleRect is window-relative; the home window fills the screen (edge to edge).
        val window = IntArray(2)
        host.rootView().getLocationOnScreen(window)
        val inWindow = IntArray(2)
        host.rootView().getLocationInWindow(inWindow)
        hit.offset(window[0] - inWindow[0], window[1] - inWindow[1])
        return hit.contains(Math.round(rawX), Math.round(rawY))
    }
}
