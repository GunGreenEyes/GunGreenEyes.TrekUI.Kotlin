package com.gungreeneyes.trekui

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.AbsListView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Checks that an activity's interactive and text views fit the screen.
 *
 * The launcher draws edge-to-edge, so backgrounds and frames may legitimately run under the status
 * and navigation bars. What must not happen is that a *key view* - a clickable leaf, a view with
 * a content description, or a text view with text - sticks out of the window (clipped off-screen) or
 * sits under a system bar / display cutout where it cannot be read or tapped.
 *
 * Scroll containers are checked as a whole (their window must fit) but their children are not, since
 * scrolled-away rows are off-screen by design. Views that are invisible, transparent or empty are
 * skipped. Must be called on the main thread.
 */
internal class LayoutAudit private constructor() {

    /** One problem found: which view, where it is and why it is wrong. */
    class Violation(val view: String, val bounds: Rect, val reason: String) {
        override fun toString(): String = reason + ": " + view + " at " + bounds.toShortString()
    }

    private val violations = ArrayList<Violation>()
    private lateinit var window: Rect
    private lateinit var safe: Rect
    private var keyViews = 0

    private fun walk(v: View) {
        if (v.visibility != View.VISIBLE || v.alpha < 0.05f || v.width <= 0 || v.height <= 0) {
            return
        }
        val r = boundsInWindow(v)
        val scroller = v is ScrollView || v is HorizontalScrollView || v is AbsListView ||
            v.javaClass.name.endsWith("RecyclerView")
        if (scroller) {
            checkInsideWindow(v, r)
            return // children scroll in and out of view by design
        }
        if (v is ViewGroup && v.childCount > 0) {
            if (v.contentDescription != null && v.isClickable) {
                check(v, r) // a tile: clickable container with its own label
            }
            for (i in 0 until v.childCount) {
                walk(v.getChildAt(i))
            }
            return
        }
        if (isKey(v)) {
            check(v, r)
        }
    }

    private fun check(v: View, r: Rect) {
        keyViews++
        if (!checkInsideWindow(v, r)) return
        val overlap = Rect(r)
        if (overlap.intersect(safe) && overlap == r) return
        // Some part is outside the safe area: report only a real overlap, not a hairline.
        val outTop = max(0, safe.top - r.top)
        val outBottom = max(0, r.bottom - safe.bottom)
        val outLeft = max(0, safe.left - r.left)
        val outRight = max(0, r.right - safe.right)
        val worst = max(max(outTop, outBottom), max(outLeft, outRight))
        if (worst > TOLERANCE_PX * 2) {
            val where = when (worst) {
                outTop -> "status bar/cutout (top)"
                outBottom -> "navigation bar (bottom)"
                outLeft -> "system bar/cutout (left)"
                else -> "system bar/cutout (right)"
            }
            violations.add(Violation(describe(v), r, String.format(Locale.US, "under %s by %dpx", where, worst)))
        }
    }

    private fun checkInsideWindow(v: View, r: Rect): Boolean {
        if (r.left < window.left - TOLERANCE_PX || r.top < window.top - TOLERANCE_PX ||
            r.right > window.right + TOLERANCE_PX || r.bottom > window.bottom + TOLERANCE_PX
        ) {
            // Something parked entirely off-screen (a slid-away panel) is hidden, not clipped.
            if (!Rect.intersects(r, window)) return false
            violations.add(Violation(describe(v), r, "clipped off-screen (window " + window.toShortString() + ")"))
            return false
        }
        return true
    }

    companion object {
        /** Pixels of overhang tolerated before a view counts as clipped (rounding, 1px strokes). */
        const val TOLERANCE_PX = 2

        /** Audits `activity`'s whole window and returns the problems found (empty when it fits). */
        @JvmStatic
        fun audit(activity: Activity): List<Violation> {
            val a = LayoutAudit()
            val decor = activity.window.decorView
            a.window = Rect(0, 0, decor.width, decor.height)
            a.safe = safeArea(decor)
            a.walk(decor)
            return a.violations
        }

        /** Counts the key views [audit] would check; used to assert a screen is not blank. */
        @JvmStatic
        fun countKeyViews(activity: Activity): Int {
            val a = LayoutAudit()
            val decor = activity.window.decorView
            a.window = Rect(0, 0, decor.width, decor.height)
            a.safe = Rect(a.window)
            a.walk(decor)
            return a.keyViews
        }

        /** The part of the window not covered by status/navigation bars or a display cutout. */
        @JvmStatic
        @Suppress("DEPRECATION") // the inset getters are the only API before 30
        fun safeArea(decor: View): Rect {
            val r = Rect(0, 0, decor.width, decor.height)
            val insets = decor.rootWindowInsets ?: return r
            var l: Int
            var t: Int
            var rt: Int
            var b: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                l = i.left
                t = i.top
                rt = i.right
                b = i.bottom
            } else {
                l = insets.systemWindowInsetLeft
                t = insets.systemWindowInsetTop
                rt = insets.systemWindowInsetRight
                b = insets.systemWindowInsetBottom
                val cutout = if (Build.VERSION.SDK_INT >= 28) insets.displayCutout else null
                if (cutout != null) {
                    l = max(l, cutout.safeInsetLeft)
                    t = max(t, cutout.safeInsetTop)
                    rt = max(rt, cutout.safeInsetRight)
                    b = max(b, cutout.safeInsetBottom)
                }
            }
            r.set(l, t, r.right - rt, r.bottom - b)
            return r
        }

        private fun isKey(v: View): Boolean {
            if (v.isClickable || v.isLongClickable) return true
            if (!v.contentDescription.isNullOrEmpty()) return true
            return v is TextView && v.text != null && v.text.toString().trim().isNotEmpty()
        }

        private fun boundsInWindow(v: View): Rect {
            val xy = IntArray(2)
            v.getLocationInWindow(xy)
            return Rect(
                xy[0], xy[1], xy[0] + (v.width * v.scaleX).roundToInt(),
                xy[1] + (v.height * v.scaleY).roundToInt(),
            )
        }

        /** Human-readable identity of a view for reports: class, id, description or text. */
        @JvmStatic
        fun describe(v: View): String {
            val sb = StringBuilder(v.javaClass.simpleName)
            if (v.id != View.NO_ID) {
                try {
                    sb.append(" #").append(v.resources.getResourceEntryName(v.id))
                } catch (e: RuntimeException) {
                    sb.append(" #").append(v.id)
                }
            }
            if (v.contentDescription != null) sb.append(" desc='").append(v.contentDescription).append('\'')
            if (v is TextView && v.text != null && v.text.isNotEmpty()) {
                val t = v.text.toString().replace('\n', ' ')
                sb.append(" text='").append(if (t.length > 40) t.substring(0, 40) + "…" else t).append('\'')
            }
            return sb.toString()
        }
    }
}
