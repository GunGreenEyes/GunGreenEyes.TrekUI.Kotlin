package com.gungreeneyes.trekui;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;

/**
 * Watches the home window's touches for the assignable gestures in {@link HomeGestures} and runs
 * whatever the user assigned. {@code MainActivity.GestureRoot} offers every event here first:
 *
 * <ul>
 *   <li>{@link Result#PASS}: not ours; GestureRoot carries on (its swipe up / down still works).</li>
 *   <li>{@link Result#CANCEL_CHILDREN}: a gesture just started or finished; the children get an
 *       ACTION_CANCEL in place of this event so no tile also fires.</li>
 *   <li>{@link Result#CONSUME}: the rest of a two-finger touch we already took over.</li>
 * </ul>
 *
 * <p>Gestures only count while the plain home screen is in front (no drawer, shade, dialer, group
 * panel or other overlay), a double-tap only on empty space (not on a key, tile, frame part or other
 * control), and a horizontal swipe never starts over something that scrolls sideways. A gesture with
 * nothing assigned is never taken, so the default home behaves exactly as before.
 */
final class HomeGestureDetector {

    /** What GestureRoot should do with the event it offered. */
    enum Result { PASS, CANCEL_CHILDREN, CONSUME }

    /**
     * Marker for home views that handle raw touches themselves (an {@code OnTouchListener} or an
     * overridden {@code onTouchEvent}, e.g. the station consoles' displays). Taps on them never count
     * toward the DOUBLE TAP gesture, exactly like taps on clickable controls.
     */
    interface OwnsTouches {}

    /** The home window the detector serves. */
    interface Host {
        /** True when no drawer, shade or dialer is open. */
        boolean homeIdle();

        /** The home content view (the first child of the root). */
        View homeView();

        /** The root view (the window content). */
        ViewGroup rootView();
    }

    private final MainActivity activity;
    private final Host host;
    private final float density;
    private final HomeGestures.TapTracker taps = new HomeGestures.TapTracker();
    private final Rect hit = new Rect();

    private VelocityTracker velocity;
    /** True while this touch is on the bare home screen. */
    private boolean idle;
    /** True when the first finger went down on a control (no double-tap). */
    private boolean onControl;
    /** True when the first finger went down over something that scrolls sideways (no swipe). */
    private boolean onHorizontalScroller;
    /** True when this press is the second tap of a possible double-tap. */
    private boolean secondTap;
    /** True from the moment a two-finger touch is taken over until all fingers lift. */
    private boolean consuming;
    /** True while two fingers are down in a taken-over touch. */
    private boolean twoFingers;
    private float downX, downY;
    private long downTime;
    private float startCx, startCy, startSpan, lastCx, lastCy, lastSpan;

    HomeGestureDetector(MainActivity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    /** Offers one touch event; see {@link Result}. */
    Result onEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onDown(e);
                return Result.PASS;
            case MotionEvent.ACTION_POINTER_DOWN:
                return onPointerDown(e);
            case MotionEvent.ACTION_MOVE:
                if (consuming) {
                    if (twoFingers && e.getPointerCount() >= 2) track(e, false);
                    return Result.CONSUME;
                }
                if (velocity != null) velocity.addMovement(e);
                return Result.PASS;
            case MotionEvent.ACTION_POINTER_UP:
                if (consuming) {
                    if (twoFingers) {
                        twoFingers = false;
                        String g = HomeGestures.classifyTwoFinger(dp(lastCx - startCx), dp(lastCy - startCy), dp(startSpan), dp(lastSpan));
                        if (g != null) fire(g);
                    }
                    return Result.CONSUME;
                }
                return Result.PASS;
            case MotionEvent.ACTION_UP:
                return onUp(e);
            case MotionEvent.ACTION_CANCEL:
                boolean wasConsuming = consuming;
                reset();
                taps.reset();
                return wasConsuming ? Result.CONSUME : Result.PASS;
            default:
                return consuming ? Result.CONSUME : Result.PASS;
        }
    }

    private void onDown(MotionEvent e) {
        reset();
        downX = e.getRawX();
        downY = e.getRawY();
        downTime = e.getEventTime();
        idle = host.homeIdle() && topChildAt(downX, downY) == host.homeView();
        if (!idle) {
            taps.reset();
            return;
        }
        onControl = isControlAt(host.homeView(), downX, downY);
        onHorizontalScroller = scrollsSidewaysAt(host.homeView(), downX, downY);
        secondTap = !onControl && taps.isSecondTap(downTime, dp(downX), dp(downY));
        velocity = VelocityTracker.obtain();
        velocity.addMovement(e);
    }

    /** A second finger: take the touch over when it lands on the bare home and a two-finger gesture is assigned. */
    private Result onPointerDown(MotionEvent e) {
        if (consuming) return Result.CONSUME;
        taps.reset();
        secondTap = false;
        if (!idle || e.getPointerCount() != 2 || !HomeGestures.anyTwoFinger(activity.prefs)) return Result.PASS;
        consuming = true;
        twoFingers = true;
        recycleVelocity();
        track(e, true);
        return Result.CANCEL_CHILDREN;
    }

    private Result onUp(MotionEvent e) {
        if (consuming) {
            reset();
            return Result.CONSUME;
        }
        Result result = Result.PASS;
        if (idle && velocity != null) {
            velocity.addMovement(e);
            velocity.computeCurrentVelocity(1000);
            float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
            String swipe = onHorizontalScroller ? null : HomeGestures.classifySwipe(dp(dx), dp(dy), dp(velocity.getXVelocity()));
            if (swipe != null && fire(swipe)) {
                taps.reset();
                result = Result.CANCEL_CHILDREN;
            } else if (!onControl && HomeGestures.isTap(e.getEventTime() - downTime, dp((float) Math.hypot(dx, dy)))) {
                if (secondTap && fire(HomeGestures.DOUBLE_TAP)) {
                    taps.reset();
                } else {
                    taps.tapped(e.getEventTime(), dp(e.getRawX()), dp(e.getRawY()));
                }
            } else {
                taps.reset();
            }
        }
        reset();
        return result;
    }

    /** Records the two fingers' midpoint and distance (at the start when {@code start}). */
    private void track(MotionEvent e, boolean start) {
        float x0 = e.getX(0), y0 = e.getY(0), x1 = e.getX(1), y1 = e.getY(1);
        float cx = (x0 + x1) / 2f, cy = (y0 + y1) / 2f, span = (float) Math.hypot(x1 - x0, y1 - y0);
        if (start) {
            startCx = cx;
            startCy = cy;
            startSpan = span;
        }
        lastCx = cx;
        lastCy = cy;
        lastSpan = span;
    }

    /** Runs the gesture's assignment after this event finishes dispatching; false when none is assigned. */
    private boolean fire(String gesture) {
        final Tile tile = HomeGestures.assigned(activity.prefs, gesture);
        if (tile == null) return false;
        final View anchor = host.rootView();
        anchor.post(() -> activity.launchTile(tile, anchor));
        return true;
    }

    private void reset() {
        consuming = false;
        twoFingers = false;
        secondTap = false;
        recycleVelocity();
    }

    private void recycleVelocity() {
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    private float dp(float px) {
        return px / density;
    }

    // ---- hit testing (raw screen coordinates) --------------------------------------------------

    /** The topmost visible child of the root under the point, or null. */
    private View topChildAt(float rawX, float rawY) {
        ViewGroup root = host.rootView();
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View child = root.getChildAt(i);
            if (child.getVisibility() == View.VISIBLE && contains(child, rawX, rawY)) return child;
        }
        return null;
    }

    /** True when a tappable view (or a drawn console frame part) lies under the point inside {@code v}. */
    private boolean isControlAt(View v, float rawX, float rawY) {
        if (v.getVisibility() != View.VISIBLE || !contains(v, rawX, rawY)) return false;
        if (v != host.homeView() && (v.isClickable() || v.isLongClickable() || v instanceof OwnsTouches)) return true;
        if (v instanceof ConsoleFrame) {
            int[] at = new int[2];
            v.getLocationOnScreen(at);
            if (((ConsoleFrame) v).partAt(rawX - at[0], rawY - at[1]) != null) return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                if (isControlAt(g.getChildAt(i), rawX, rawY)) return true;
            }
        }
        return false;
    }

    /** True when something under the point can scroll horizontally (its own swipes win). */
    private boolean scrollsSidewaysAt(View v, float rawX, float rawY) {
        if (v.getVisibility() != View.VISIBLE || !contains(v, rawX, rawY)) return false;
        if (v.canScrollHorizontally(1) || v.canScrollHorizontally(-1)) return true;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                if (scrollsSidewaysAt(g.getChildAt(i), rawX, rawY)) return true;
            }
        }
        return false;
    }

    private boolean contains(View v, float rawX, float rawY) {
        if (!v.getGlobalVisibleRect(hit)) return false;
        // getGlobalVisibleRect is window-relative; the home window fills the screen (edge to edge).
        int[] window = new int[2];
        host.rootView().getLocationOnScreen(window);
        int[] inWindow = new int[2];
        host.rootView().getLocationInWindow(inWindow);
        hit.offset(window[0] - inWindow[0], window[1] - inWindow[1]);
        return hit.contains(Math.round(rawX), Math.round(rawY));
    }
}
