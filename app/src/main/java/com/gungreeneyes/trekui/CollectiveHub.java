package com.gungreeneyes.trekui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

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
 */
final class CollectiveHub extends ViewGroup implements RevealHost {
    /** Node radius pattern in dp: deliberately uneven, like the Collective displays. */
    private static final int[] SIZES = {27, 20, 24, 18, 30, 21, 19, 25, 17, 23, 28, 18, 22, 20, 26, 19, 24, 21};
    /** Most nodes per orbit, innermost first (the last value repeats for any further orbits). */
    private static final int[] CAP = {5, 7, 9, 99};
    /** Deploy and fold animation lengths. */
    private static final long OPEN_MS = 620, CLOSE_MS = 340;
    /** Smallest node radius, widest label, and the smallest node scale when space is tight. */
    private static final float MIN_NODE_DP = 13, LABEL_MAX_DP = 104, MIN_SCALE = 0.55f;
    /** Share of the view the whole network (nodes plus labels) should occupy. */
    private static final float FILL_FRACTION = 0.5f;

    private final float d;
    private final int line, hi;
    private final View hubTouch;
    private final List<View> nodes = new ArrayList<>(), contents = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private final List<Boolean> empties = new ArrayList<>();
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG), hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmpR = new RectF();

    /** Node size multiplier: below 1 when crowded, above 1 on large screens (up to the UI scale). */
    private float scale = 1f;
    private float hubX, hubY, hubR;
    private float[] nx = new float[0], ny = new float[0], nr = new float[0];
    private int[] orbit = new int[0], parent = new int[0];
    private boolean[] longSpoke = new boolean[0];
    /** Label offsets from the node centre (left/top of the label), chosen during layout. */
    private float[] lx = new float[0], ly = new float[0];

    /** Folded/extended state survives rebuilds while the launcher runs. */
    private static boolean open;
    private float progress = open ? 1f : 0f;
    private ValueAnimator anim;
    private float dim = 1f;
    private Runnable masterAction;
    private String masterTitle;
    private int count = -1;

    CollectiveHub(Context c, Palette p, View.OnLongClickListener onHubLong) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line = p.c(0);
        hi = p.c(1);
        setWillNotDraw(false);
        setClipChildren(false);
        hubTouch = new View(c);
        hubTouch.setBackground(Ui.touchArea(c, 60));
        hubTouch.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Ui.tap(v);
                if (masterAction != null) { masterAction.run(); return; }
                Sfx.play(getContext(), open ? Sfx.CLOSE : Sfx.CONFIRM);
                setOpen(!open, true);
            }
        });
        hubTouch.setOnLongClickListener(onHubLong);
        addView(hubTouch);
        hint.setTypeface(Ui.font(c, false));
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(11 * d * Skin.textScale());
        hint.setLetterSpacing(0.25f);
        arc.setStyle(Paint.Style.STROKE);
        updateDescription();
    }

    /**
     * Adds a node. content is centred in the node (the icon, or a "+" for an empty tile); the
     * returned view is the node's touch target, for the caller's tap / long-press handlers.
     */
    @Override public View addNode(View content, String label, int color, boolean empty) {
        final int i = nodes.size();
        final FrameLayout node = new FrameLayout(getContext());
        node.setBackground(Ui.touch(nodeBackground(color, empty)));
        node.addView(content, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        node.setContentDescription(label);
        TextView lab = Ui.oneLine(Ui.text(getContext(), label.toUpperCase(java.util.Locale.getDefault()), 11,
                empty ? Ui.alpha(Skin.ink(), 0.5f) : Skin.ink()));
        lab.setMaxWidth(Math.round(LABEL_MAX_DP * d));
        lab.setPadding(Math.round(2 * d), 0, Math.round(2 * d), 0);
        // the label is part of the node: tapping it does what tapping the node does
        lab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { node.performClick(); }
        });
        lab.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { return node.performLongClick(); }
        });
        lab.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        nodes.add(node);
        contents.add(content);
        labels.add(lab);
        empties.add(empty);
        addView(node);
        addView(lab);
        return node;
    }

    /** Accessibility text for the hub, matching what tapping it will do. */
    private void updateDescription() {
        hubTouch.setContentDescription(open ? "Collective hub: hide interface nodes. Long-press for all apps."
                : "Collective hub: show interface nodes. Long-press for all apps.");
    }

    static boolean isOpen() { return open; }

    void setOpen(boolean o, boolean animated) { setOpen(o, animated, null); }

    @Override public void setPinned(boolean p) { }

    @Override public void setMasterAction(Runnable a) { masterAction = a; }

    @Override public void setMasterTitle(String t) { masterTitle = t; invalidate(); }

    @Override public void setCount(int c) { count = c; invalidate(); }

    @Override public void setOpen(boolean o, boolean animated, final Runnable onEnd) {
        open = o;
        updateDescription();
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
        anim.setInterpolator(o ? new DecelerateInterpolator(1.4f) : new AccelerateInterpolator(1.2f));
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

    /** Dim the network while an info panel is open over it. */
    @Override public void setDim(float v) {
        dim = v;
        apply();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != VISIBLE && open) setOpen(false, false);
    }

    /** Deploy progress of node i: inner orbits first, slightly staggered within an orbit. */
    private float nodeProgress(int i) {
        if (i >= orbit.length) return progress;
        float delay = Math.min(0.45f, orbit[i] * 0.2f + (i % 5) * 0.03f);
        float e = (progress - delay) / (1f - delay);
        return e < 0 ? 0 : e > 1 ? 1 : e;
    }

    /** Moves, scales and fades every node and label for the current progress, then redraws. */
    private void apply() {
        for (int i = 0; i < nodes.size() && i < nx.length; i++) {
            View v = nodes.get(i);
            TextView lab = labels.get(i);
            float e = nodeProgress(i);
            float tx = (hubX - nx[i]) * (1 - e), ty = (hubY - ny[i]) * (1 - e);
            v.setPivotX(v.getWidth() / 2f);
            v.setPivotY(v.getHeight() / 2f);
            v.setTranslationX(tx);
            v.setTranslationY(ty);
            v.setScaleX(0.25f + 0.75f * e);
            v.setScaleY(0.25f + 0.75f * e);
            v.setAlpha(e * dim);
            lab.setTranslationX(tx);
            lab.setTranslationY(ty);
            float la = Math.max(0, (e - 0.6f) / 0.4f);
            lab.setAlpha(la * dim);
            int vis = e <= 0.02f ? INVISIBLE : VISIBLE;
            if (v.getVisibility() != vis) v.setVisibility(vis);
            int lvis = la <= 0.02f ? INVISIBLE : VISIBLE;
            if (lab.getVisibility() != lvis) lab.setVisibility(lvis);
        }
        invalidate();
    }

    // ---- measuring and layout ----------------------------------------------------------------------

    private float baseRadius(int i) { return SIZES[i % SIZES.length] * d; }

    private float radius(int i) { return Math.max(MIN_NODE_DP * d, baseRadius(i) * scale); }

    /**
     * Chooses the node scale so the network takes about {@link #FILL_FRACTION} of the area
     * (never below {@link #MIN_SCALE}, never above the screen's UI scale), then measures nodes,
     * labels and the hub.
     */
    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        if (MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED || h == 0) h = Math.round(w * 1.5f);
        int n = nodes.size();
        // shrink nodes so the whole network (nodes + labels) takes about half the space
        float need = 0;
        for (int i = 0; i < n; i++) {
            float r = baseRadius(i) + 8 * d;
            need += (float) (Math.PI * r * r) + 70 * d * 16 * d;
        }
        float avail = w * (float) h * FILL_FRACTION - (float) (Math.PI * Math.pow(42 * d, 2));
        float maxScale = Math.max(1f, ScreenMetrics.of(getContext()).uiScale);
        scale = need <= 0 ? 1f : Math.max(MIN_SCALE, Math.min(maxScale, (float) Math.sqrt(Math.max(1, avail) / need)));
        for (int i = 0; i < n; i++) {
            int side = Math.round(2 * radius(i) + 4 * d);
            View content = contents.get(i);
            int icon = Math.round(empties.get(i) ? side : radius(i) * 1.05f);
            ViewGroup.LayoutParams lp = content.getLayoutParams();
            if (lp.width != icon) { lp.width = icon; lp.height = icon; content.setLayoutParams(lp); }
            nodes.get(i).measure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY));
            labels.get(i).measure(MeasureSpec.makeMeasureSpec(Math.round(LABEL_MAX_DP * d), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        int hub = Math.round(2 * hubRadius(w, h));
        hubTouch.measure(MeasureSpec.makeMeasureSpec(hub, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(hub, MeasureSpec.EXACTLY));
        setMeasuredDimension(w, h);
    }

    /** Hub radius: 38dp (less on small views), shrinking a little with the node scale. */
    private float hubRadius(int w, int h) { return Math.min(38 * d, Math.min(w, h) * 0.12f) * (0.8f + 0.2f * scale); }

    /**
     * Lays the nodes on elliptical orbits around the centred hub (the ellipse follows the view's
     * aspect, so landscape spreads sideways), separates them, routes the traces and places labels.
     */
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, h = b - t, n = nodes.size();
        hubX = w * 0.5f;
        hubY = h * 0.5f;
        hubR = hubRadius(w, h);
        nx = new float[n];
        ny = new float[n];
        nr = new float[n];
        orbit = new int[n];
        parent = new int[n];
        longSpoke = new boolean[n];
        lx = new float[n];
        ly = new float[n];
        int orbits = 0;
        for (int total = 0; total < n; orbits++) total += CAP[Math.min(orbits, CAP.length - 1)];
        orbits = Math.max(1, orbits);
        // orbit radii: fractions of the space around the hub, so the network fills the view
        float ax = w / 2f - 34 * d * scale, ay = h / 2f - 34 * d * scale - 10 * d;
        int start = 0;
        for (int o = 0; start < n; o++) {
            int count = Math.min(CAP[Math.min(o, CAP.length - 1)], n - start);
            float f = orbits == 1 ? 0.62f : 0.5f + 0.48f * o / (orbits - 1);
            double base = -Math.PI / 2 + (o % 2 == 1 ? Math.PI / count : 0) + o * 0.21;
            for (int k = 0; k < count; k++) {
                int i = start + k;
                double jitter = ((i * 37) % 11 - 5) * 0.035;
                float stretch = 1f + (((i * 53) % 9) - 4) * 0.035f;
                double a = base + k * 2 * Math.PI / count + jitter;
                nx[i] = hubX + (float) Math.cos(a) * ax * f * stretch;
                ny[i] = hubY + (float) Math.sin(a) * ay * f * stretch;
                nr[i] = radius(i);
                orbit[i] = o;
            }
            start += count;
        }
        relax(w, h);
        // routing: inner orbit → hub; outer → nearest inner (and every third also → hub, long and thin)
        for (int i = 0; i < n; i++) {
            parent[i] = orbit[i] == 0 ? -1 : nearestInner(i);
            longSpoke[i] = orbit[i] > 0 && (parent[i] < 0 || i % 3 == 0);
        }
        placeLabels(w, h);
        for (int i = 0; i < n; i++) {
            View v = nodes.get(i);
            int s = v.getMeasuredWidth();
            v.layout(Math.round(nx[i] - s / 2f), Math.round(ny[i] - s / 2f), Math.round(nx[i] - s / 2f) + s, Math.round(ny[i] - s / 2f) + s);
            TextView lab = labels.get(i);
            int lw = lab.getMeasuredWidth(), lh = lab.getMeasuredHeight();
            int left = Math.round(nx[i] + lx[i]), top = Math.round(ny[i] + ly[i]);
            lab.layout(left, top, left + lw, top + lh);
        }
        int hs = hubTouch.getMeasuredWidth();
        hubTouch.layout(Math.round(hubX - hs / 2f), Math.round(hubY - hs / 2f), Math.round(hubX + hs / 2f), Math.round(hubY + hs / 2f));
        apply();
    }

    /** Push apart nodes that would overlap; keep them clear of the hub and inside the view. */
    private void relax(int w, int h) {
        int n = nx.length;
        float margin = 6 * d;
        for (int it = 0; it < 80; it++) {
            boolean moved = false;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    float dx = nx[j] - nx[i], dy = ny[j] - ny[i];
                    float need = nr[i] + nr[j] + 26 * d * scale + 8 * d; // room for a label between
                    float dist = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dist >= need) continue;
                    if (dist < 0.5f) { dx = 1; dy = 0.3f; dist = 1.04f; }
                    float push = (need - dist) / 2f + 0.5f;
                    nx[i] -= dx / dist * push; ny[i] -= dy / dist * push;
                    nx[j] += dx / dist * push; ny[j] += dy / dist * push;
                    moved = true;
                }
                float hx = nx[i] - hubX, hy = ny[i] - hubY, hd = (float) Math.sqrt(hx * hx + hy * hy);
                float hn = hubR + nr[i] + 20 * d;
                if (hd < hn && hd > 0.5f) { nx[i] = hubX + hx / hd * hn; ny[i] = hubY + hy / hd * hn; moved = true; }
                nx[i] = Math.max(nr[i] + margin, Math.min(w - nr[i] - margin, nx[i]));
                ny[i] = Math.max(nr[i] + margin, Math.min(h - nr[i] - margin, ny[i]));
            }
            if (!moved) break;
        }
    }

    /** The closest node on the next orbit in, or -1. */
    private int nearestInner(int i) {
        int best = -1;
        float bd = Float.MAX_VALUE;
        for (int j = 0; j < nx.length; j++) {
            if (orbit[j] != orbit[i] - 1) continue;
            float dx = nx[j] - nx[i], dy = ny[j] - ny[i], dd = dx * dx + dy * dy;
            if (dd < bd) { bd = dd; best = j; }
        }
        return best;
    }

    /**
     * Each label goes on the side of its node facing away from the hub: beside it (left or right)
     * when the node is off to the side, above or below when it's mostly above or below. If that
     * spot hits another node, label or trace, the other sides are tried in turn.
     */
    private void placeLabels(int w, int h) {
        int n = nx.length;
        RectF[] placed = new RectF[n];
        float gap = 5 * d;
        for (int i = 0; i < n; i++) {
            TextView lab = labels.get(i);
            float lw = lab.getMeasuredWidth(), lh = lab.getMeasuredHeight();
            double a = Math.atan2(ny[i] - hubY, nx[i] - hubX);
            float c = (float) Math.cos(a), s = (float) Math.sin(a);
            // candidate offsets (label left/top relative to node centre): right, left, below, above
            float[][] cand = {
                {nr[i] + gap, -lh / 2f}, {-nr[i] - gap - lw, -lh / 2f},
                {-lw / 2f, nr[i] + gap * 0.6f}, {-lw / 2f, -nr[i] - gap * 0.6f - lh}};
            int[] order;
            if (Math.abs(c) > 0.42f) order = c > 0 ? new int[]{0, 2, 3, 1} : new int[]{1, 2, 3, 0};
            else order = s > 0 ? new int[]{2, c >= 0 ? 0 : 1, c >= 0 ? 1 : 0, 3} : new int[]{3, c >= 0 ? 0 : 1, c >= 0 ? 1 : 0, 2};
            int best = order[0];
            float bestCost = Float.MAX_VALUE;
            for (int k : order) {
                RectF rr = new RectF(nx[i] + cand[k][0], ny[i] + cand[k][1], nx[i] + cand[k][0] + lw, ny[i] + cand[k][1] + lh);
                float cost = labelCost(rr, i, placed, w, h);
                if (cost < bestCost - 0.01f) { bestCost = cost; best = k; }
                if (cost == 0) break;
            }
            lx[i] = cand[best][0];
            ly[i] = cand[best][1];
            // keep inside the view
            float left = Math.max(2 * d, Math.min(w - lw - 2 * d, nx[i] + lx[i]));
            float top = Math.max(0, Math.min(h - lh, ny[i] + ly[i]));
            lx[i] = left - nx[i];
            ly[i] = top - ny[i];
            placed[i] = new RectF(left, top, left + lw, top + lh);
        }
    }

    /** How bad a label position is: 0 = clear; more for each node, label or trace it covers. */
    private float labelCost(RectF rr, int self, RectF[] placed, int w, int h) {
        float cost = 0;
        if (rr.left < 0 || rr.right > w || rr.top < 0 || rr.bottom > h) cost += 3;
        RectF in = new RectF(rr);
        in.inset(-2 * d, -1 * d);
        // other nodes and the hub
        for (int j = 0; j < nx.length; j++) {
            if (j == self) continue;
            if (circleHits(in, nx[j], ny[j], nr[j])) cost += 4;
        }
        if (circleHits(in, hubX, hubY, hubR + 4 * d)) cost += 4;
        for (RectF p : placed) if (p != null && RectF.intersects(in, p)) cost += 4;
        // traces
        for (int j = 0; j < nx.length; j++) {
            if (orbit[j] == 0 || longSpoke[j]) if (segmentHits(in, hubX, hubY, nx[j], ny[j])) cost += 1.5f;
            if (parent[j] >= 0) if (segmentHits(in, nx[parent[j]], ny[parent[j]], nx[j], ny[j])) cost += 1.5f;
        }
        return cost;
    }

    /** True when a circle overlaps a rectangle. */
    private static boolean circleHits(RectF r, float cx, float cy, float rad) {
        float qx = Math.max(r.left, Math.min(cx, r.right)), qy = Math.max(r.top, Math.min(cy, r.bottom));
        float dx = cx - qx, dy = cy - qy;
        return dx * dx + dy * dy < rad * rad;
    }

    /** True when a straight trace passes through a rectangle (sampled at 11 points). */
    private static boolean segmentHits(RectF r, float x0, float y0, float x1, float y1) {
        for (int k = 1; k < 12; k++) {
            float t = k / 12f;
            if (r.contains(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)) return true;
        }
        return false;
    }

    // ---- drawing ----------------------------------------------------------------------------------------

    /** A trace between two nodes, starting and ending at their rims. */
    private void spoke(Canvas cv, float x0, float y0, float r0, float x1, float y1, float r1, float bend, float wDp, float alpha) {
        double a = Math.atan2(y1 - y0, x1 - x0);
        float sx = x0 + (float) Math.cos(a) * r0, sy = y0 + (float) Math.sin(a) * r0;
        float ex = x1 - (float) Math.cos(a) * r1, ey = y1 - (float) Math.sin(a) * r1;
        CollectiveDraw.traceW(cv, CollectiveDraw.curve(sx, sy, ex, ey, bend), line, d, alpha * dim, wDp);
    }

    /** Draws the crosshair, hub arcs, traces and vias under the nodes, then the hub and its hint. */
    @Override protected void dispatchDraw(Canvas cv) {
        int n = nx.length;
        int w = getWidth(), h = getHeight();
        float[] cx = new float[n], cy = new float[n], ep = new float[n];
        for (int i = 0; i < n; i++) {
            ep[i] = nodeProgress(i);
            cx[i] = hubX + (nx[i] - hubX) * ep[i];
            cy[i] = hubY + (ny[i] - hubY) * ep[i];
        }
        float reach = 0.35f + 0.65f * progress;
        Path cross = CollectiveDraw.tmp();
        cross.moveTo(hubX - (hubX - w * 0.03f) * reach, hubY);
        cross.lineTo(hubX + (w * 0.97f - hubX) * reach, hubY);
        cross.moveTo(hubX, hubY - (hubY - 6 * d) * reach);
        cross.lineTo(hubX, hubY + (h - hubY) * reach);
        CollectiveDraw.traceW(cv, cross, hi, d, 0.3f * dim, 1f);
        arc.setStrokeWidth(d);
        arc.setColor(Ui.alpha(line, (Ui.cloak ? 0.12f : 0.22f) * dim));
        float ar = hubR * (1.35f + 0.55f * progress);
        for (int k = 0; k < 6; k++) cv.drawArc(hubX - ar, hubY - ar, hubX + ar, hubY + ar, k * 60 + 8 + 30 * progress, 38, false, arc);
        for (int i = 0; i < n; i++) {
            if (ep[i] <= 0.01f) continue;
            float bend = ((i % 3) - 1) * 0.12f * ep[i];
            float ri = nr[i] * (0.25f + 0.75f * ep[i]);
            if (orbit[i] == 0) spoke(cv, hubX, hubY, hubR, cx[i], cy[i], ri, bend, 2.8f * (0.7f + 0.3f * scale), ep[i]);
            if (longSpoke[i]) spoke(cv, hubX, hubY, hubR, cx[i], cy[i], ri, bend * 1.6f, 1.1f, 0.7f * ep[i]);
            int j = parent[i];
            if (j >= 0) spoke(cv, cx[j], cy[j], nr[j] * (0.25f + 0.75f * ep[j]), cx[i], cy[i], ri, -bend, orbit[i] == 1 ? 1.9f : 1.4f, 0.9f * ep[i]);
        }
        for (int i = 0; i < n; i++) {
            float settle = Math.max(0, (ep[i] - 0.7f) / 0.3f) * dim;
            if (settle <= 0) continue;
            if (orbit[i] == 0 && i % 2 == 0) CollectiveDraw.via(cv, (hubX + nx[i]) / 2f, (hubY + ny[i]) / 2f, 3 * d, line, d, 0.9f * settle);
        }
        float hr = hubR * (0.9f + 0.1f * progress);
        CollectiveDraw.ringNode(cv, hubX, hubY, hr, hi, d, 0.9f);
        CollectiveDraw.node(cv, hubX, hubY, hr * 0.55f, line, d, 1f);
        if (progress < 0.6f) {
            hint.setColor(Ui.alpha(hi, (Ui.cloak ? 0.4f : 0.75f) * (1 - progress / 0.6f)));
            cv.drawText(masterTitle != null ? masterTitle : (count >= 0 ? count : n) > 0 ? "ACCESS " + (count >= 0 ? count : n) + " NODES" : "NO NODES", hubX, hubY + hubR + 20 * d, hint);
        }
        super.dispatchDraw(cv);
    }

    /** Node background: drawn to fit whatever size the node is laid out at. */
    private Drawable nodeBackground(final int color, final boolean empty) {
        return new Drawable() {
            @Override public void draw(Canvas cv) {
                Rect b = getBounds();
                float rr = Math.min(b.width(), b.height()) / 2f - 2 * d;
                if (empty) CollectiveDraw.ringNode(cv, b.exactCenterX(), b.exactCenterY(), rr, Skin.glow(color), d, 0.55f);
                else CollectiveDraw.node(cv, b.exactCenterX(), b.exactCenterY(), rr, Skin.glow(color), d, 1f);
            }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(ColorFilter cf) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
}
