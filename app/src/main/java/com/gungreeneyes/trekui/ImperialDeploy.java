package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Imperial "TACTICAL DEPLOY" home layout. Folded, it is one command plate: a long double-lined
 * octagon with triangle clusters, "COMMAND" and the number of stations. Pressing it lifts the
 * plate to the top, a spine of alternating triangles drops down the centre, and the tile plates
 * slide out from behind the spine row by row. The plates narrow toward the bottom, so the deployed
 * set forms an arrowhead. Always fits the space; never scrolls.
 */
final class ImperialDeploy extends RevealLayout {
    /** Command plate height and maximum width, spine width and the preferred row-height range, in dp. */
    private static final float PLATE_H_DP = 60, PLATE_MAX_W_DP = 300, SPINE_DP = 18, ROW_MIN_DP = 38, ROW_MAX_DP = 64;
    /** Most each row narrows relative to the one above (the arrowhead), in dp. */
    private static final float TAPER_MAX_DP = 10;
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outer = new Path(), inner = new Path();
    private final RectF r = new RectF();
    private float plateW, plateH, spineW, gap, rowH, top0, taper;
    private int rows;
    private float[] plL = new float[0], plW = new float[0];

    ImperialDeploy(Context c, Palette p, View.OnLongClickListener onMasterLong) {
        super(c, p, onMasterLong);
        text.setTypeface(Ui.font(c, true));
    }

    /** Even tiles slide out to the left of the spine, odd ones to the right. */
    private boolean left(int i) { return i % 2 == 0; }

    @Override public View addNode(View content, String label, int color, boolean empty) {
        final int i = nodes.size();
        boolean lft = left(i);
        FrameLayout plate = new FrameLayout(getContext());
        // outer side carries the corner cuts: left plates point left, right plates point right
        int kind = lft ? Ui.LEFT : Ui.RIGHT;
        plate.setBackground(Ui.touch(Ui.shape(getContext(), empty ? Ui.alpha(color, 0.3f) : color, kind, 12)));
        if (!empty) {
            int is = Math.round(dp(24));
            FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(is, is, Gravity.CENTER_VERTICAL | (lft ? Gravity.START : Gravity.END));
            if (lft) il.leftMargin = Math.round(dp(18)); else il.rightMargin = Math.round(dp(18));
            plate.addView(content, il);
        }
        TextView lab = Ui.oneLine(Ui.bold(getContext(), (empty ? "+ ASSIGN" : label).toUpperCase(Locale.getDefault()), 14,
                empty ? Ui.alpha(Skin.glow(color), 0.7f) : Skin.ink()));
        lab.setGravity((lft ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
        ll.leftMargin = Math.round(lft ? dp(48) : dp(12));
        ll.rightMargin = Math.round(lft ? dp(12) : dp(48));
        plate.addView(lab, ll);
        plate.setContentDescription(label);
        nodes.add(plate);
        empties.add(empty);
        colors.add(color);
        addView(plate);
        return plate;
    }

    /**
     * Sizes the plates so every row fits the height (shrinking rows on short landscape screens)
     * and the set stays within {@link #contentWidth} on wide screens.
     */
    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        if (MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED || h == 0) h = Math.round(w * 1.5f);
        int n = nodes.size();
        float cw = contentWidth(w);
        plateH = dp(PLATE_H_DP);
        plateW = Math.min(cw * 0.82f, dp(PLATE_MAX_W_DP));
        spineW = dp(SPINE_DP);
        gap = dp(Ui.GUTTER);
        top0 = plateH + gap * 2;
        rows = Math.max(1, (n + 1) / 2);
        rowH = fitRowHeight(h - top0, rows, gap, dp(ROW_MIN_DP), dp(ROW_MAX_DP));
        float half = (cw - spineW) / 2f - gap;
        // each row is a little narrower than the one above: the deployed set is an arrowhead
        taper = Math.min(dp(TAPER_MAX_DP), half * 0.3f / Math.max(1, rows - 1));
        plL = new float[n];
        plW = new float[n];
        for (int i = 0; i < n; i++) {
            float pw = half - (i / 2) * taper;
            plW[i] = pw;
            nodes.get(i).measure(MeasureSpec.makeMeasureSpec(Math.round(pw), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(rowH), MeasureSpec.EXACTLY));
        }
        master.measure(MeasureSpec.makeMeasureSpec(Math.round(plateW), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(plateH), MeasureSpec.EXACTLY));
        setMeasuredDimension(w, h);
    }

    /** Places the plates in rows either side of the centred spine. */
    @Override protected void onLayout(boolean changed, int l, int t, int rr, int b) {
        int w = rr - l, n = nodes.size();
        float cx = w / 2f;
        for (int i = 0; i < n; i++) {
            int row = i / 2;
            float y = top0 + row * (rowH + gap);
            float x = left(i) ? cx - spineW / 2f - gap - plW[i] : cx + spineW / 2f + gap;
            nodes.get(i).layout(Math.round(x), Math.round(y), Math.round(x + plW[i]), Math.round(y + rowH));
            plL[i] = x;
        }
        master.layout(Math.round(cx - plateW / 2f), 0, Math.round(cx + plateW / 2f), Math.round(plateH));
        apply();
    }

    /** Command plate travels from the middle (folded) to the top. */
    private float masterY() {
        if (pinned) return 0;
        float folded = getHeight() / 2f - plateH / 2f;
        return folded * (1 - ease(progress / 0.3f));
    }

    /** How far the triangle spine has grown (0..1). */
    private float spineProgress() { return ease((progress - 0.15f) / 0.45f); }

    /** How far plate i has slid out (0..1); rows follow one another, right after left. */
    private float plateProgress(int i) { return ease((progress - 0.3f - (i / 2) * 0.05f - (i % 2) * 0.02f) / 0.4f); }

    @Override protected void apply() {
        master.setTranslationY(masterY());
        for (int i = 0; i < nodes.size() && i < plW.length; i++) {
            View v = nodes.get(i);
            float e = plateProgress(i);
            // slide out from behind the spine
            float travel = plW[i] * 0.55f * (1 - e);
            v.setTranslationX(left(i) ? travel : -travel);
            v.setAlpha(e * dim);
            show(v, e);
        }
        invalidate();
    }

    /** Plates are clipped at the spine, so they emerge from behind it rather than crossing it. */
    @Override protected boolean drawChild(Canvas cv, View child, long time) {
        int i = nodes.indexOf(child);
        if (i < 0) return super.drawChild(cv, child, time);
        float cx = getWidth() / 2f;
        cv.save();
        if (left(i)) cv.clipRect(0, 0, cx - spineW / 2f, getHeight());
        else cv.clipRect(cx + spineW / 2f, 0, getWidth(), getHeight());
        boolean r = super.drawChild(cv, child, time);
        cv.restore();
        return r;
    }

    /** Draws the spine, the command plate and (folded) its triangle rows, then the plates. */
    @Override protected void dispatchDraw(Canvas cv) {
        int w = getWidth();
        float cx = w / 2f, my = masterY();
        // spine: a chain of alternating triangles down the centre
        float sp = spineProgress();
        if (sp > 0 && rows > 0) {
            float bottom = top0 + rows * (rowH + gap) - gap;
            float reach = plateH + gap + (bottom - plateH - gap) * sp;
            float s = spineW, step = s * 0.62f;
            int[] cols = {ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED};
            int k = 0;
            for (float y = plateH + gap + s / 2f; y < reach - s / 2f; y += step, k++) {
                ImperialDraw.tri(cv, cx, y, s, s * 0.8f, k % 2 == 1, cols[k % 3], (k % 2 == 1 ? 1f : 0.75f) * dim);
            }
            // a ▲▼ row under the last plates once everything is out
            if (progress > 0.9f && bottom + dp(14) < getHeight()) {
                float rw = Math.min(w * 0.4f, dp(120)), a = (progress - 0.9f) / 0.1f;
                ImperialDraw.row(cv, cx - rw / 2f, bottom + dp(6), rw, dp(8),
                        new int[]{ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED}, a * dim);
            }
        }
        // the command plate
        float l = cx - plateW / 2f, t = my, rgt = cx + plateW / 2f, b = my + plateH, k2 = plateH * 0.36f;
        ImperialDraw.octagon(outer, l, t, rgt, b, k2, true, true);
        ImperialDraw.octagon(inner, l + dp(4), t + dp(4), rgt - dp(4), b - dp(4), k2 - dp(2), true, true);
        ImperialDraw.fill(cv, outer, Ui.cloak ? 0xFF000000 : Skin.mix(Skin.bg(), ImperialDraw.RED, 0.12f));
        if (!Ui.cloak) {
            cv.save();
            cv.clipPath(inner);
            r.set(l, t, rgt, b);
            ImperialDraw.grid(cv, r, dp(14), ImperialDraw.RED, 0.16f, d);
            cv.restore();
        }
        ImperialDraw.frame(cv, outer, inner, ImperialDraw.RED, d, 1f);
        float cs = plateH * 0.24f;
        ImperialDraw.cluster(cv, l + k2 + cs * 0.6f, (t + b) / 2f, cs, 1f);
        text.setColor(Skin.ink());
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(Ui.px(getContext(), 24));
        cv.drawText(masterTitle != null ? masterTitle : isOpen() ? "COMMAND" : "DEPLOY", rgt - k2 - dp(4), b - plateH * 0.3f, text);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(Ui.px(getContext(), 13));
        text.setColor(ImperialDraw.ORANGE);
        cv.drawText(String.format(Locale.US, "%02d", shownCount()), l + k2 + cs * 1.9f, b - plateH * 0.32f, text);
        // folded: triangle rows above and below the plate, and a stand-by line
        float f = pinned ? 0 : 1 - clamp(progress / 0.25f);
        if (f > 0) {
            float rw = plateW * 0.6f;
            int[] cols = {ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED};
            ImperialDraw.row(cv, cx - rw / 2f, t - dp(16), rw, dp(9), cols, f);
            ImperialDraw.row(cv, cx - rw / 2f, b + dp(7), rw, dp(9), cols, f);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(Ui.px(getContext(), 13));
            text.setColor(Ui.alpha(ImperialDraw.ORANGE, f));
            cv.drawText(shownCount() + " STATIONS AWAIT ORDERS", cx, b + dp(38), text);
        }
        super.dispatchDraw(cv);
    }
}
