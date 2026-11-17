package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Command "CONSOLE CASCADE" home layout. Folded, it is one large console key in the middle of the
 * screen ("ACCESS" and the number of stations). Pressing it lifts the key to the top, a
 * segmented console spine grows down from it, and the tile keys extend sideways out of the spine
 * row by row: left keys with rounded left ends, right keys with rounded right ends, like console
 * panel rows sliding out. Always fits the space; never scrolls. The 23rd/25th-century eras unfold
 * the same way with their own keys and spine ({@link EraDraw#key}).
 */
final class CommandCascade extends RevealLayout {
    /** Master key height, maximum width, spine width and the preferred row-height range, in dp. */
    private static final float MASTER_H_DP = 54, MASTER_MAX_W_DP = 260, SPINE_DP = 22, ROW_MIN_DP = 38, ROW_MAX_DP = 70;
    /** Key icon size, its inset from the rounded end, and the label inset on the icon side, in dp. */
    private static final float ICON_DP = 26, ICON_INSET_DP = 16, LABEL_ICON_SIDE_DP = 48, LABEL_SPINE_SIDE_DP = 10;
    /** Corner radius of the keys, in dp. */
    private static final float KEY_RADIUS_DP = 30;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float masterW, masterH, spineW, gap, rowH, top0;
    private int rows;
    private float[] keyL = new float[0], keyT = new float[0], keyR = new float[0];

    CommandCascade(Context c, Palette p, View.OnLongClickListener onMasterLong) {
        super(c, p, onMasterLong);
        text.setTypeface(Ui.font(c, true));
    }

    /** Even tiles extend to the left of the spine, odd ones to the right. */
    private boolean left(int i) { return i % 2 == 0; }

    @Override public View addNode(View content, String label, int color, boolean empty) {
        final int i = nodes.size();
        boolean lft = left(i);
        FrameLayout key = new FrameLayout(getContext());
        int kind = lft ? Ui.LEFT : Ui.RIGHT;
        key.setBackground(Ui.touch(empty ? Ui.outline(getContext(), 0x00000000, Ui.alpha(color, 0.6f), KEY_RADIUS_DP, 1.5f)
                : Ui.shape(getContext(), color, kind, KEY_RADIUS_DP)));
        if (!empty) {
            int is = Math.round(dp(ICON_DP));
            FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(is, is, Gravity.CENTER_VERTICAL | (lft ? Gravity.START : Gravity.END));
            if (lft) il.leftMargin = Math.round(dp(ICON_INSET_DP)); else il.rightMargin = Math.round(dp(ICON_INSET_DP));
            key.addView(content, il);
        }
        TextView lab = Ui.oneLine(Ui.bold(getContext(), (empty ? "+ ASSIGN" : label).toUpperCase(Locale.getDefault()), 15,
                empty ? Ui.alpha(color, 0.8f) : Ui.ink()));
        // console lettering sits low and toward the spine
        lab.setGravity(lft ? Gravity.END : Gravity.START);
        FrameLayout.LayoutParams ll = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        ll.leftMargin = Math.round(lft ? dp(LABEL_ICON_SIDE_DP) : dp(LABEL_SPINE_SIDE_DP));
        ll.rightMargin = Math.round(lft ? dp(LABEL_SPINE_SIDE_DP) : dp(LABEL_ICON_SIDE_DP));
        ll.bottomMargin = Math.round(dp(5));
        key.addView(lab, ll);
        key.setContentDescription(label);
        nodes.add(key);
        empties.add(empty);
        colors.add(color);
        addView(key);
        return key;
    }

    /**
     * Sizes the keys so every row fits the height (shrinking rows on short landscape screens) and
     * the key set stays within {@link #contentWidth} on wide screens.
     */
    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        if (MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED || h == 0) h = Math.round(w * 1.5f);
        int n = nodes.size();
        float cw = contentWidth(w);
        masterH = dp(MASTER_H_DP);
        masterW = Math.min(cw * 0.66f, dp(MASTER_MAX_W_DP));
        spineW = dp(SPINE_DP);
        gap = dp(Ui.GUTTER);
        top0 = masterH + gap * 2;
        rows = Math.max(1, (n + 1) / 2);
        rowH = fitRowHeight(h - top0, rows, gap, dp(ROW_MIN_DP), dp(ROW_MAX_DP));
        float half = (cw - spineW) / 2f - gap;
        for (int i = 0; i < n; i++) {
            nodes.get(i).measure(MeasureSpec.makeMeasureSpec(Math.round(half), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(rowH), MeasureSpec.EXACTLY));
        }
        master.measure(MeasureSpec.makeMeasureSpec(Math.round(masterW), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(masterH), MeasureSpec.EXACTLY));
        setMeasuredDimension(w, h);
    }

    /** Places the keys in rows either side of the centred spine. */
    @Override protected void onLayout(boolean changed, int l, int t, int rr, int b) {
        int w = rr - l, n = nodes.size();
        float cx = w / 2f, half = (contentWidth(w) - spineW) / 2f - gap;
        keyL = new float[n];
        keyT = new float[n];
        keyR = new float[n];
        for (int i = 0; i < n; i++) {
            int row = i / 2;
            float y = top0 + row * (rowH + gap);
            float x = left(i) ? cx - spineW / 2f - gap - half : cx + spineW / 2f + gap;
            View v = nodes.get(i);
            v.layout(Math.round(x), Math.round(y), Math.round(x + half), Math.round(y + rowH));
            keyL[i] = x;
            keyT[i] = y;
            keyR[i] = x + half;
        }
        master.layout(Math.round(cx - masterW / 2f), 0, Math.round(cx + masterW / 2f), Math.round(masterH));
        apply();
    }

    /** Master key travels from the middle (folded) to the top. */
    private float masterY() {
        if (pinned) return 0;
        float folded = getHeight() / 2f - masterH / 2f;
        return folded * (1 - ease(progress / 0.3f));
    }

    /** How far the spine has grown (0..1) at the current deploy progress. */
    private float spineProgress() { return ease((progress - 0.15f) / 0.45f); }

    /** How far tile i has extended (0..1); rows follow one another. */
    private float keyProgress(int i) { return ease((progress - 0.3f - (i / 2) * 0.05f) / 0.4f); }

    @Override protected void apply() {
        float my = masterY();
        master.setTranslationY(my);
        for (int i = 0; i < nodes.size() && i < keyL.length; i++) {
            View v = nodes.get(i);
            float e = keyProgress(i);
            // keys extend out of the spine
            v.setPivotX(left(i) ? v.getWidth() : 0);
            v.setPivotY(v.getHeight() / 2f);
            v.setScaleX(Math.max(0.01f, e));
            v.setAlpha((0.3f + 0.7f * e) * dim);
            show(v, e);
        }
        invalidate();
    }

    /** Draws the spine, the master key and (folded) its flanking pills, then the keys. */
    @Override protected void dispatchDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        float cx = w / 2f, my = masterY();
        boolean cloak = Ui.cloak;
        paint.setStyle(cloak ? Paint.Style.STROKE : Paint.Style.FILL);
        paint.setStrokeWidth(dp(1.5f));
        // the spine: one console segment per row, growing downward
        float sp = spineProgress();
        if (sp > 0 && rows > 0) {
            float bottom = top0 + rows * (rowH + gap) - gap;
            float reach = top0 + (bottom - top0) * sp;
            int[] seg = {pal.c(1), pal.c(3), pal.c(5), pal.c(2)};
            for (int k = 0; k < rows; k++) {
                float st = top0 + k * (rowH + gap), en = Math.min(st + rowH, reach);
                if (en <= st) break;
                paint.setColor(Ui.alpha(seg[k % seg.length], dim));
                if (Skin.era()) {
                    r.set(cx - spineW / 2f, st, cx + spineW / 2f, en);
                    EraDraw.key(cv, r, paint.getColor(), Ui.SQUARE, dp(1), cloak, k);
                } else {
                    cv.drawRect(cx - spineW / 2f, st, cx + spineW / 2f, en, paint);
                }
            }
            // a neck joining the master key to the spine
            paint.setColor(Ui.alpha(pal.c(1), dim));
            if (Skin.era()) {
                r.set(cx - spineW / 2f, masterH + gap * 0.5f, cx + spineW / 2f, Math.min(top0 - gap * 0.5f, reach));
                if (r.height() > 1) EraDraw.key(cv, r, paint.getColor(), Ui.SQUARE, dp(1), cloak, -1);
            } else {
                cv.drawRect(cx - spineW / 2f, masterH + gap * 0.5f, cx + spineW / 2f, Math.min(top0 - gap * 0.5f, reach), paint);
            }
        }
        // the master key: a large console pill (a console key or a glass plate in the eras)
        r.set(cx - masterW / 2f, my, cx + masterW / 2f, my + masterH);
        paint.setColor(Ui.alpha(pal.c(0), Math.max(dim, 0.6f)));
        if (Skin.era()) EraDraw.key(cv, r, paint.getColor(), Ui.SQUARE, dp(1), cloak, 0x4D);
        else cv.drawRoundRect(r, masterH / 2f, masterH / 2f, paint);
        text.setColor(cloak ? Ui.CLOAK_INK : Ui.ink());
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTextSize(Ui.px(getContext(), 22));
        cv.drawText(masterTitle != null ? masterTitle : isOpen() ? "OPERATIONS" : "ACCESS", r.right - masterH * 0.45f, r.bottom - dp(12), text);
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(Ui.px(getContext(), 14));
        cv.drawText(String.format(Locale.US, "%02d", shownCount()), r.left + masterH * 0.45f, r.bottom - dp(13), text);
        // folded: flanking pills and a stand-by line under the key
        float f = pinned ? 0 : 1 - clamp(progress / 0.25f);
        if (f > 0) {
            paint.setColor(Ui.alpha(pal.c(3), f * dim));
            r.set(cx - masterW / 2f - dp(46), my + masterH * 0.25f, cx - masterW / 2f - dp(8), my + masterH * 0.75f);
            if (Skin.era()) EraDraw.key(cv, r, paint.getColor(), Ui.LEFT, dp(1), cloak, 1);
            else cv.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paint);
            paint.setColor(Ui.alpha(pal.c(4), f * dim));
            r.set(cx + masterW / 2f + dp(8), my + masterH * 0.25f, cx + masterW / 2f + dp(46), my + masterH * 0.75f);
            if (Skin.era()) EraDraw.key(cv, r, paint.getColor(), Ui.RIGHT, dp(1), cloak, 2);
            else cv.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paint);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(Ui.px(getContext(), 13));
            text.setColor(Ui.alpha(pal.c(2), f));
            cv.drawText(shownCount() + " STATIONS STANDING BY", cx, my + masterH + dp(26), text);
        }
        super.dispatchDraw(cv);
    }
}
