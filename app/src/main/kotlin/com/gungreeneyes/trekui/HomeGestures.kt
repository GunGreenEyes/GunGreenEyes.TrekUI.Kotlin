package com.gungreeneyes.trekui

import org.json.JSONObject

/**
 * Assignable home-screen gestures (gap A9): double-tap, two-finger swipe up / down, pinch in / out
 * and one-finger swipe left / right. Each can run any console function or app, stored as a
 * [Tile] so it shares the tiles' launch code (`MainActivity.launchTile`).
 *
 * The one-finger swipe up (app drawer) and swipe down (console shade) stay fixed and are handled by
 * `MainActivity.GestureRoot` as before; an unassigned gesture changes nothing, so the default
 * home behaves exactly like earlier versions.
 *
 * This object holds the pure rules (classification thresholds, double-tap timing, the preference
 * keys) and is tested on the JVM in `HomeGesturesTest`; [HomeGestureDetector] feeds it
 * real touch events.
 */
internal object HomeGestures {

    const val DOUBLE_TAP = "doubleTap"
    const val TWO_FINGER_UP = "twoFingerUp"
    const val TWO_FINGER_DOWN = "twoFingerDown"
    const val PINCH_IN = "pinchIn"
    const val PINCH_OUT = "pinchOut"
    const val SWIPE_LEFT = "swipeLeft"
    const val SWIPE_RIGHT = "swipeRight"

    /** Every assignable gesture, in CONFIG order. */
    @JvmField
    val ALL: Array<String> = arrayOf(DOUBLE_TAP, TWO_FINGER_UP, TWO_FINGER_DOWN, PINCH_IN, PINCH_OUT, SWIPE_LEFT, SWIPE_RIGHT)

    /** The gestures that need two fingers (the detector only takes over a two-finger touch when one is assigned). */
    @JvmField
    val TWO_FINGER: Array<String> = arrayOf(TWO_FINGER_UP, TWO_FINGER_DOWN, PINCH_IN, PINCH_OUT)

    /** Prefix of every preference key of this feature (included in layout exports). */
    const val KEY_PREFIX = "gesture."

    // ---- thresholds (dp, dp/s, ms) -------------------------------------------------------------

    /** Shortest one-finger horizontal swipe, matching the existing vertical swipe's 70dp. */
    const val SWIPE_MIN_TRAVEL_DP = 70f

    /** A swipe must be this many times longer along its axis than across it. */
    const val AXIS_DOMINANCE = 1.5f

    /** Slowest one-finger swipe, in dp per second (slower drags are treated as nothing). */
    const val SWIPE_MIN_VELOCITY_DP = 300f

    /** Shortest two-finger swipe (movement of the fingers' midpoint). */
    const val TWO_FINGER_MIN_TRAVEL_DP = 60f

    /** Smallest change of the distance between two fingers that counts as a pinch. */
    const val PINCH_MIN_SPAN_CHANGE_DP = 60f

    /** A pinch in must end at or below this fraction of the starting finger distance… */
    const val PINCH_IN_RATIO = 0.75f

    /** …and a pinch out at or above this multiple of it. */
    const val PINCH_OUT_RATIO = 1.33f

    /** Longest press that still counts as a tap. */
    const val TAP_MAX_MS = 300L

    /** Farthest a finger may move during a tap. */
    const val TAP_SLOP_DP = 12f

    /** Longest gap between the first tap's release and the second tap's press. */
    const val DOUBLE_TAP_TIMEOUT_MS = 300L

    /** Farthest apart the two taps of a double-tap may be. */
    const val DOUBLE_TAP_SLOP_DP = 48f

    /** Preference key of a gesture's assignment. */
    @JvmStatic
    fun key(gesture: String): String = KEY_PREFIX + gesture

    /** True for the gestures that need two fingers. */
    @JvmStatic
    fun twoFinger(gesture: String?): Boolean = TWO_FINGER.any { it == gesture }

    /**
     * Classifies a finished two-finger touch.
     *
     * @param dxDp        horizontal movement of the fingers' midpoint
     * @param dyDp        vertical movement of the midpoint (negative = up)
     * @param startSpanDp distance between the fingers when the second one went down
     * @param endSpanDp   distance between them when the first one lifted
     * @return [PINCH_IN], [PINCH_OUT], [TWO_FINGER_UP], [TWO_FINGER_DOWN] or null
     */
    @JvmStatic
    fun classifyTwoFinger(dxDp: Float, dyDp: Float, startSpanDp: Float, endSpanDp: Float): String? {
        val spanChange = endSpanDp - startSpanDp
        val travel = Math.hypot(dxDp.toDouble(), dyDp.toDouble()).toFloat()
        if (Math.abs(spanChange) >= PINCH_MIN_SPAN_CHANGE_DP && Math.abs(spanChange) >= travel && startSpanDp > 0) {
            val ratio = endSpanDp / startSpanDp
            if (ratio <= PINCH_IN_RATIO) return PINCH_IN
            if (ratio >= PINCH_OUT_RATIO) return PINCH_OUT
        }
        if (Math.abs(dyDp) >= TWO_FINGER_MIN_TRAVEL_DP && Math.abs(dyDp) >= Math.abs(dxDp) * AXIS_DOMINANCE) {
            return if (dyDp < 0) TWO_FINGER_UP else TWO_FINGER_DOWN
        }
        return null
    }

    /**
     * Classifies a finished one-finger touch as a horizontal swipe.
     *
     * @param dxDp horizontal travel (negative = left)
     * @param dyDp vertical travel
     * @param vxDp horizontal release velocity in dp/s
     * @return [SWIPE_LEFT], [SWIPE_RIGHT] or null (vertical swipes stay with GestureRoot)
     */
    @JvmStatic
    fun classifySwipe(dxDp: Float, dyDp: Float, vxDp: Float): String? {
        val travel = Math.abs(dxDp)
        if (travel < SWIPE_MIN_TRAVEL_DP || travel < Math.abs(dyDp) * AXIS_DOMINANCE) return null
        if (Math.abs(vxDp) < SWIPE_MIN_VELOCITY_DP || Math.signum(vxDp) != Math.signum(dxDp)) return null
        return if (dxDp < 0) SWIPE_LEFT else SWIPE_RIGHT
    }

    /** True when a press of `durationMs` that moved `moveDp` is a tap. */
    @JvmStatic
    fun isTap(durationMs: Long, moveDp: Float): Boolean = durationMs <= TAP_MAX_MS && moveDp <= TAP_SLOP_DP

    /**
     * Remembers the last tap on empty home space and recognises a second one close by, soon after.
     * Times are in ms and positions in dp; the class holds no Android state.
     */
    class TapTracker {
        private var lastUpMs = Long.MIN_VALUE
        private var lastX = 0f
        private var lastY = 0f

        /** Records a completed tap on empty space. */
        fun tapped(upMs: Long, xDp: Float, yDp: Float) {
            lastUpMs = upMs
            lastX = xDp
            lastY = yDp
        }

        /** True when a press at `downMs` and (`xDp`, `yDp`) is the second tap of a double-tap. */
        fun isSecondTap(downMs: Long, xDp: Float, yDp: Float): Boolean {
            if (lastUpMs == Long.MIN_VALUE) return false
            val gap = downMs - lastUpMs
            return gap >= 0 && gap <= DOUBLE_TAP_TIMEOUT_MS &&
                Math.hypot((xDp - lastX).toDouble(), (yDp - lastY).toDouble()) <= DOUBLE_TAP_SLOP_DP
        }

        /** Forgets the last tap (after a double-tap fired or any non-tap touch). */
        fun reset() {
            lastUpMs = Long.MIN_VALUE
        }
    }

    // ---- assignments ---------------------------------------------------------------------------

    /** The app or function assigned to a gesture, or null when none. */
    @JvmStatic
    fun assigned(prefs: Prefs, gesture: String): Tile? = decode(prefs.s(key(gesture), null))

    /** Assigns (or with null / an empty tile, clears) a gesture. Takes effect on the next touch. */
    @JvmStatic
    fun assign(prefs: Prefs, gesture: String, tile: Tile?) {
        if (tile == null || tile.isEmpty()) prefs.remove(key(gesture)) else prefs.put(key(gesture), tile.toJson().toString())
    }

    /** True when at least one two-finger gesture is assigned. */
    @JvmStatic
    fun anyTwoFinger(prefs: Prefs): Boolean = TWO_FINGER.any { assigned(prefs, it) != null }

    /** Reads a stored assignment; null for missing, corrupt or empty values. */
    @JvmStatic
    fun decode(raw: String?): Tile? {
        if (raw.isNullOrEmpty()) return null
        return try {
            val t = Tile.fromJson(JSONObject(raw))
            if (t.isEmpty()) null else t
        } catch (e: Exception) {
            null
        }
    }
}
