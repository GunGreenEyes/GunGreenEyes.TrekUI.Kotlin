package com.gungreeneyes.trekui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * Base for the "press one control, the rest deploys" home layouts (Command console CASCADE,
 * Imperial TACTICAL DEPLOY; the Collective hub works the same way). The layout always fits the space it
 * is given (no scrolling). It starts folded down to its master control; tapping the master
 * deploys the tiles, tapping again folds them, and it folds by itself when the launcher goes to
 * the background. Long-pressing the master opens the full app list.
 */
abstract class RevealLayout extends ViewGroup implements RevealHost {
    /** Deploy and fold animation lengths. */
    private static final long OPEN_MS = 760, CLOSE_MS = 380;
    /** Widest the deployed tile set grows at the design scale; wider screens centre it. */
    static final float MAX_CONTENT_WIDTH_DP = 640;

    protected final float d;
    protected final Palette pal;
    protected final View master;
    protected final List<View> nodes = new ArrayList<>();
    protected final List<Boolean> empties = new ArrayList<>();
    protected final List<Integer> colors = new ArrayList<>();

    /** Folded/deployed state; static so it survives the rebuilds on theme or size changes. */
    private static boolean open;
    protected float progress = open ? 1f : 0f;
    protected float dim = 1f;
    private ValueAnimator anim;
    protected boolean pinned;
    protected Runnable masterAction;
    protected String masterTitle;
    protected int count = -1;

    RevealLayout(Context c, Palette p, View.OnLongClickListener onMasterLong) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        pal = p;
        setWillNotDraw(false);
        setClipChildren(false);
        master = new View(c);
        master.setBackground(Ui.touchArea(c, 12));
        master.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                if (masterAction != null) { masterAction.run(); return; }
                Sfx.play(getContext(), open ? Sfx.CLOSE : Sfx.OPEN);
                setOpen(!open, true);
            }
        });
        master.setOnLongClickListener(onMasterLong);
        addView(master);
        describe();
    }

    private void describe() {
        master.setContentDescription((open ? "Hide" : "Show") + " home tiles. Long-press for all apps.");
    }

    static boolean isOpen() { return open; }

    /**
     * Width the tile set may use inside a view of width {@code w}: all of it on phones, capped
     * on wide screens (landscape, tablets) so keys do not stretch into long bars.
     */
    protected float contentWidth(int w) {
        float cap = ScreenMetrics.of(getContext()).scaledPx(MAX_CONTENT_WIDTH_DP);
        return Math.min(w, Math.max(cap, dp(320)));
    }

    /**
     * Row height that fits {@code rows} rows (with gaps) into {@code avail} px: within lo..hi
     * when there is room, shrinking below lo (to 26dp at most) rather than overflowing.
     */
    protected float fitRowHeight(float avail, int rows, float gap, float lo, float hi) {
        float each = (avail - gap * (rows - 1)) / Math.max(1, rows);
        float min = Math.min(lo, Math.max(dp(26), each));
        return Math.max(min, Math.min(hi, each));
    }

    protected float dp(float v) { return v * d; }

    /** Clamps to 0..1. */
    protected static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    /** Quadratic ease-out of a clamped 0..1 value. */
    protected static float ease(float v) { v = clamp(v); return 1 - (1 - v) * (1 - v); }

    void setOpen(boolean o, boolean animated) { setOpen(o, animated, null); }

    @Override public void setPinned(boolean p) { pinned = p; apply(); }

    @Override public void setMasterAction(Runnable a) { masterAction = a; }

    @Override public void setMasterTitle(String t) { masterTitle = t; invalidate(); }

    @Override public void setCount(int n) { count = n; invalidate(); }

    protected int shownCount() { return count >= 0 ? count : nodes.size(); }

    @Override public void setOpen(boolean o, boolean animated, final Runnable onEnd) {
        open = o;
        describe();
        if (anim != null) anim.cancel();
        float target = o ? 1f : 0f;
        if (!animated || !Ui.animate(getContext())) {
            progress = target;
            apply();
            if (onEnd != null) onEnd.run();
            return;
        }
        anim = ValueAnimator.ofFloat(progress, target);
        anim.setDuration(o ? OPEN_MS : CLOSE_MS);
        anim.setInterpolator(o ? new DecelerateInterpolator(1.2f) : new AccelerateInterpolator(1.2f));
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                apply();
            }
        });
        if (onEnd != null) {
            anim.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator a) { if (!cancelled) onEnd.run(); }
            });
        }
        anim.start();
    }

    @Override public void setDim(float v) {
        dim = v;
        apply();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE && open) setOpen(false, false);
    }

    /** Position, scale and fade the tiles for the current progress, then redraw. */
    protected abstract void apply();

    /** Hides a tile while it is fully folded (so it cannot be tapped), shows it otherwise. */
    protected static void show(View v, float e) {
        int vis = e <= 0.02f ? INVISIBLE : VISIBLE;
        if (v.getVisibility() != vis) v.setVisibility(vis);
    }
}
