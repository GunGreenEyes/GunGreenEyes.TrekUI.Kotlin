package com.gungreeneyes.trekui;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AbsListView;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks that an activity's interactive and text views fit the screen.
 *
 * <p>The launcher draws edge-to-edge, so backgrounds and frames may legitimately run under the status
 * and navigation bars. What must not happen is that a <em>key view</em> - a clickable leaf, a view with
 * a content description, or a text view with text - sticks out of the window (clipped off-screen) or
 * sits under a system bar / display cutout where it cannot be read or tapped.</p>
 *
 * <p>Scroll containers are checked as a whole (their window must fit) but their children are not, since
 * scrolled-away rows are off-screen by design. Views that are invisible, transparent or empty are
 * skipped. Must be called on the main thread.</p>
 */
final class LayoutAudit {
    /** Pixels of overhang tolerated before a view counts as clipped (rounding, 1px strokes). */
    static final int TOLERANCE_PX = 2;

    /** One problem found: which view, where it is and why it is wrong. */
    static final class Violation {
        final String view;
        final Rect bounds;
        final String reason;

        Violation(String view, Rect bounds, String reason) {
            this.view = view;
            this.bounds = bounds;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return reason + ": " + view + " at " + bounds.toShortString();
        }
    }

    private final List<Violation> violations = new ArrayList<>();
    private Rect window;
    private Rect safe;
    private int keyViews;

    /** Audits {@code activity}'s whole window and returns the problems found (empty when it fits). */
    static List<Violation> audit(Activity activity) {
        LayoutAudit a = new LayoutAudit();
        View decor = activity.getWindow().getDecorView();
        a.window = new Rect(0, 0, decor.getWidth(), decor.getHeight());
        a.safe = safeArea(decor);
        a.walk(decor);
        return a.violations;
    }

    /** Counts the key views {@link #audit} would check; used to assert a screen is not blank. */
    static int countKeyViews(Activity activity) {
        LayoutAudit a = new LayoutAudit();
        View decor = activity.getWindow().getDecorView();
        a.window = new Rect(0, 0, decor.getWidth(), decor.getHeight());
        a.safe = new Rect(a.window);
        a.walk(decor);
        return a.keyViews;
    }

    /** The part of the window not covered by status/navigation bars or a display cutout. */
    static Rect safeArea(View decor) {
        Rect r = new Rect(0, 0, decor.getWidth(), decor.getHeight());
        WindowInsets in = decor.getRootWindowInsets();
        if (in == null) return r;
        int l, t, rt, b;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            l = i.left;
            t = i.top;
            rt = i.right;
            b = i.bottom;
        } else {
            //noinspection deprecation - the only inset API before 30
            l = in.getSystemWindowInsetLeft();
            //noinspection deprecation
            t = in.getSystemWindowInsetTop();
            //noinspection deprecation
            rt = in.getSystemWindowInsetRight();
            //noinspection deprecation
            b = in.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28 && in.getDisplayCutout() != null) {
                l = Math.max(l, in.getDisplayCutout().getSafeInsetLeft());
                t = Math.max(t, in.getDisplayCutout().getSafeInsetTop());
                rt = Math.max(rt, in.getDisplayCutout().getSafeInsetRight());
                b = Math.max(b, in.getDisplayCutout().getSafeInsetBottom());
            }
        }
        r.set(l, t, r.right - rt, r.bottom - b);
        return r;
    }

    private void walk(View v) {
        if (v.getVisibility() != View.VISIBLE || v.getAlpha() < 0.05f || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return;
        }
        Rect r = boundsInWindow(v);
        boolean scroller = v instanceof ScrollView || v instanceof HorizontalScrollView || v instanceof AbsListView
                || v.getClass().getName().endsWith("RecyclerView");
        if (scroller) {
            checkInsideWindow(v, r);
            return; // children scroll in and out of view by design
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            if (g.getChildCount() > 0) {
                if (g.getContentDescription() != null && g.isClickable()) {
                    check(v, r); // a tile: clickable container with its own label
                }
                for (int i = 0; i < g.getChildCount(); i++) {
                    walk(g.getChildAt(i));
                }
                return;
            }
        }
        if (isKey(v)) {
            check(v, r);
        }
    }

    private static boolean isKey(View v) {
        if (v.isClickable() || v.isLongClickable()) return true;
        if (v.getContentDescription() != null && v.getContentDescription().length() > 0) return true;
        return v instanceof TextView && ((TextView) v).getText() != null && ((TextView) v).getText().toString().trim().length() > 0;
    }

    private void check(View v, Rect r) {
        keyViews++;
        if (!checkInsideWindow(v, r)) return;
        Rect overlap = new Rect(r);
        if (overlap.intersect(safe) && overlap.equals(r)) return;
        // Some part is outside the safe area: report only a real overlap, not a hairline.
        int outTop = Math.max(0, safe.top - r.top);
        int outBottom = Math.max(0, r.bottom - safe.bottom);
        int outLeft = Math.max(0, safe.left - r.left);
        int outRight = Math.max(0, r.right - safe.right);
        int worst = Math.max(Math.max(outTop, outBottom), Math.max(outLeft, outRight));
        if (worst > TOLERANCE_PX * 2) {
            String where = outTop == worst ? "status bar/cutout (top)" : outBottom == worst ? "navigation bar (bottom)"
                    : outLeft == worst ? "system bar/cutout (left)" : "system bar/cutout (right)";
            violations.add(new Violation(describe(v), r, String.format(Locale.US, "under %s by %dpx", where, worst)));
        }
    }

    private boolean checkInsideWindow(View v, Rect r) {
        if (r.left < window.left - TOLERANCE_PX || r.top < window.top - TOLERANCE_PX
                || r.right > window.right + TOLERANCE_PX || r.bottom > window.bottom + TOLERANCE_PX) {
            // Something parked entirely off-screen (a slid-away panel) is hidden, not clipped.
            if (!Rect.intersects(r, window)) return false;
            violations.add(new Violation(describe(v), r, "clipped off-screen (window " + window.toShortString() + ")"));
            return false;
        }
        return true;
    }

    private static Rect boundsInWindow(View v) {
        int[] xy = new int[2];
        v.getLocationInWindow(xy);
        return new Rect(xy[0], xy[1], xy[0] + Math.round(v.getWidth() * v.getScaleX()),
                xy[1] + Math.round(v.getHeight() * v.getScaleY()));
    }

    /** Human-readable identity of a view for reports: class, id, description or text. */
    static String describe(View v) {
        StringBuilder sb = new StringBuilder(v.getClass().getSimpleName());
        if (v.getId() != View.NO_ID) {
            try {
                sb.append(" #").append(v.getResources().getResourceEntryName(v.getId()));
            } catch (RuntimeException e) {
                sb.append(" #").append(v.getId());
            }
        }
        if (v.getContentDescription() != null) sb.append(" desc='").append(v.getContentDescription()).append('\'');
        if (v instanceof TextView && ((TextView) v).getText() != null && ((TextView) v).getText().length() > 0) {
            String t = ((TextView) v).getText().toString().replace('\n', ' ');
            sb.append(" text='").append(t.length() > 40 ? t.substring(0, 40) + "…" : t).append('\'');
        }
        return sb.toString();
    }
}
