package com.gungreeneyes.trekui;

import org.json.JSONObject;

/**
 * Assignable home-screen gestures (gap A9): double-tap, two-finger swipe up / down, pinch in / out
 * and one-finger swipe left / right. Each can run any console function or app, stored as a
 * {@link Tile} so it shares the tiles' launch code ({@code MainActivity.launchTile}).
 *
 * <p>The one-finger swipe up (app drawer) and swipe down (console shade) stay fixed and are handled by
 * {@code MainActivity.GestureRoot} as before; an unassigned gesture changes nothing, so the default
 * home behaves exactly like earlier versions.
 *
 * <p>This class holds the pure rules (classification thresholds, double-tap timing, the preference
 * keys) and is tested on the JVM in {@code HomeGesturesTest}; {@link HomeGestureDetector} feeds it
 * real touch events.
 */
final class HomeGestures {

    static final String DOUBLE_TAP = "doubleTap";
    static final String TWO_FINGER_UP = "twoFingerUp";
    static final String TWO_FINGER_DOWN = "twoFingerDown";
    static final String PINCH_IN = "pinchIn";
    static final String PINCH_OUT = "pinchOut";
    static final String SWIPE_LEFT = "swipeLeft";
    static final String SWIPE_RIGHT = "swipeRight";

    /** Every assignable gesture, in CONFIG order. */
    static final String[] ALL = {DOUBLE_TAP, TWO_FINGER_UP, TWO_FINGER_DOWN, PINCH_IN, PINCH_OUT, SWIPE_LEFT, SWIPE_RIGHT};
    /** The gestures that need two fingers (the detector only takes over a two-finger touch when one is assigned). */
    static final String[] TWO_FINGER = {TWO_FINGER_UP, TWO_FINGER_DOWN, PINCH_IN, PINCH_OUT};

    /** Prefix of every preference key of this feature (included in layout exports). */
    static final String KEY_PREFIX = "gesture.";

    // ---- thresholds (dp, dp/s, ms) -------------------------------------------------------------

    /** Shortest one-finger horizontal swipe, matching the existing vertical swipe's 70dp. */
    static final float SWIPE_MIN_TRAVEL_DP = 70f;
    /** A swipe must be this many times longer along its axis than across it. */
    static final float AXIS_DOMINANCE = 1.5f;
    /** Slowest one-finger swipe, in dp per second (slower drags are treated as nothing). */
    static final float SWIPE_MIN_VELOCITY_DP = 300f;
    /** Shortest two-finger swipe (movement of the fingers' midpoint). */
    static final float TWO_FINGER_MIN_TRAVEL_DP = 60f;
    /** Smallest change of the distance between two fingers that counts as a pinch. */
    static final float PINCH_MIN_SPAN_CHANGE_DP = 60f;
    /** A pinch in must end at or below this fraction of the starting finger distance… */
    static final float PINCH_IN_RATIO = 0.75f;
    /** …and a pinch out at or above this multiple of it. */
    static final float PINCH_OUT_RATIO = 1.33f;
    /** Longest press that still counts as a tap. */
    static final long TAP_MAX_MS = 300L;
    /** Farthest a finger may move during a tap. */
    static final float TAP_SLOP_DP = 12f;
    /** Longest gap between the first tap's release and the second tap's press. */
    static final long DOUBLE_TAP_TIMEOUT_MS = 300L;
    /** Farthest apart the two taps of a double-tap may be. */
    static final float DOUBLE_TAP_SLOP_DP = 48f;

    private HomeGestures() {}

    /** Preference key of a gesture's assignment. */
    static String key(String gesture) {
        return KEY_PREFIX + gesture;
    }

    /** True for the gestures that need two fingers. */
    static boolean twoFinger(String gesture) {
        for (String g : TWO_FINGER) if (g.equals(gesture)) return true;
        return false;
    }

    /**
     * Classifies a finished two-finger touch.
     *
     * @param dxDp        horizontal movement of the fingers' midpoint
     * @param dyDp        vertical movement of the midpoint (negative = up)
     * @param startSpanDp distance between the fingers when the second one went down
     * @param endSpanDp   distance between them when the first one lifted
     * @return {@link #PINCH_IN}, {@link #PINCH_OUT}, {@link #TWO_FINGER_UP}, {@link #TWO_FINGER_DOWN} or null
     */
    static String classifyTwoFinger(float dxDp, float dyDp, float startSpanDp, float endSpanDp) {
        float spanChange = endSpanDp - startSpanDp;
        float travel = (float) Math.hypot(dxDp, dyDp);
        if (Math.abs(spanChange) >= PINCH_MIN_SPAN_CHANGE_DP && Math.abs(spanChange) >= travel && startSpanDp > 0) {
            float ratio = endSpanDp / startSpanDp;
            if (ratio <= PINCH_IN_RATIO) return PINCH_IN;
            if (ratio >= PINCH_OUT_RATIO) return PINCH_OUT;
        }
        if (Math.abs(dyDp) >= TWO_FINGER_MIN_TRAVEL_DP && Math.abs(dyDp) >= Math.abs(dxDp) * AXIS_DOMINANCE) {
            return dyDp < 0 ? TWO_FINGER_UP : TWO_FINGER_DOWN;
        }
        return null;
    }

    /**
     * Classifies a finished one-finger touch as a horizontal swipe.
     *
     * @param dxDp horizontal travel (negative = left)
     * @param dyDp vertical travel
     * @param vxDp horizontal release velocity in dp/s
     * @return {@link #SWIPE_LEFT}, {@link #SWIPE_RIGHT} or null (vertical swipes stay with GestureRoot)
     */
    static String classifySwipe(float dxDp, float dyDp, float vxDp) {
        float travel = Math.abs(dxDp);
        if (travel < SWIPE_MIN_TRAVEL_DP || travel < Math.abs(dyDp) * AXIS_DOMINANCE) return null;
        if (Math.abs(vxDp) < SWIPE_MIN_VELOCITY_DP || Math.signum(vxDp) != Math.signum(dxDp)) return null;
        return dxDp < 0 ? SWIPE_LEFT : SWIPE_RIGHT;
    }

    /** True when a press of {@code durationMs} that moved {@code moveDp} is a tap. */
    static boolean isTap(long durationMs, float moveDp) {
        return durationMs <= TAP_MAX_MS && moveDp <= TAP_SLOP_DP;
    }

    /**
     * Remembers the last tap on empty home space and recognises a second one close by, soon after.
     * Times are in ms and positions in dp; the class holds no Android state.
     */
    static final class TapTracker {
        private long lastUpMs = Long.MIN_VALUE;
        private float lastX, lastY;

        /** Records a completed tap on empty space. */
        void tapped(long upMs, float xDp, float yDp) {
            lastUpMs = upMs;
            lastX = xDp;
            lastY = yDp;
        }

        /** True when a press at {@code downMs} and ({@code xDp}, {@code yDp}) is the second tap of a double-tap. */
        boolean isSecondTap(long downMs, float xDp, float yDp) {
            if (lastUpMs == Long.MIN_VALUE) return false;
            long gap = downMs - lastUpMs;
            return gap >= 0 && gap <= DOUBLE_TAP_TIMEOUT_MS
                    && Math.hypot(xDp - lastX, yDp - lastY) <= DOUBLE_TAP_SLOP_DP;
        }

        /** Forgets the last tap (after a double-tap fired or any non-tap touch). */
        void reset() {
            lastUpMs = Long.MIN_VALUE;
        }
    }

    // ---- assignments ---------------------------------------------------------------------------

    /** The app or function assigned to a gesture, or null when none. */
    static Tile assigned(Prefs prefs, String gesture) {
        return decode(prefs.s(key(gesture), null));
    }

    /** Assigns (or with null / an empty tile, clears) a gesture. Takes effect on the next touch. */
    static void assign(Prefs prefs, String gesture, Tile tile) {
        if (tile == null || tile.isEmpty()) prefs.remove(key(gesture));
        else prefs.put(key(gesture), tile.toJson().toString());
    }

    /** True when at least one two-finger gesture is assigned. */
    static boolean anyTwoFinger(Prefs prefs) {
        for (String g : TWO_FINGER) if (assigned(prefs, g) != null) return true;
        return false;
    }

    /** Reads a stored assignment; null for missing, corrupt or empty values. */
    static Tile decode(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        try {
            Tile t = Tile.fromJson(new JSONObject(raw));
            return t.isEmpty() ? null : t;
        } catch (Exception e) {
            return null;
        }
    }
}
