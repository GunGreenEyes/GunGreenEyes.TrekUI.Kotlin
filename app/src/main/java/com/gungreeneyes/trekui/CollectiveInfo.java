package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * The info nodes under the Collective hub: one small node per home panel (weather, media, calendar,
 * power, systems), strung on a trace that rises into the hub's crosshair. Tapping a node opens
 * that panel over the network; tapping it again (or anywhere outside the panel) closes it. This
 * replaces scrolling down to the panels in the hub layout.
 */
final class CollectiveInfo extends ViewGroup implements RevealHost.InfoBar {
    /** Told which panel key was tapped (also used by {@link RevealInfo}). */
    interface OnSelect { void select(int index); }

    /** Touch target size, node row height, odd-node stagger and label offset, in dp. */
    private static final float TOUCH_DP = 40, ROW_Y_DP = 22, STAGGER_DP = 7, LABEL_GAP_DP = 12;

    private final float d;
    private final int line, hi;
    private final int[] colors;
    private final View[] touch;
    private final TextView[] labels;
    private final float[] x, y;
    private int active = -1;

    CollectiveInfo(Context c, Palette p, String[] names, int[] colors, final OnSelect cb) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        line = p.c(0);
        hi = p.c(1);
        this.colors = colors;
        int n = names.length;
        touch = new View[n];
        labels = new TextView[n];
        x = new float[n];
        y = new float[n];
        setWillNotDraw(false);
        for (int i = 0; i < n; i++) {
            final int k = i;
            View t = new View(c);
            t.setBackground(Ui.touchArea(c, 30));
            t.setContentDescription(names[i] + " panel");
            t.setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { Ui.tap(v); cb.select(k); }
            });
            touch[i] = t;
            addView(t);
            TextView lab = Ui.oneLine(Ui.text(c, names[i], 10, Ui.alpha(Skin.ink(), 0.8f)));
            lab.setGravity(android.view.Gravity.CENTER);
            lab.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            labels[i] = lab;
            addView(lab);
        }
    }

    @Override public void setActive(int i) {
        active = i;
        for (int k = 0; k < labels.length; k++) labels[k].setTextColor(k == i ? Skin.glow(colors[k]) : Ui.alpha(Skin.ink(), 0.8f));
        invalidate();
    }

    @Override protected void onMeasure(int ws, int hs) {
        int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
        int n = touch.length;
        int cell = n == 0 ? w : w / n;
        int tz = Math.round(TOUCH_DP * d);
        for (int i = 0; i < n; i++) {
            touch[i].measure(MeasureSpec.makeMeasureSpec(tz, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tz, MeasureSpec.EXACTLY));
            labels[i].measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        setMeasuredDimension(w, h);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l, n = touch.length;
        for (int i = 0; i < n; i++) {
            x[i] = w * (i + 0.5f) / n;
            y[i] = ROW_Y_DP * d + (i % 2 == 1 ? STAGGER_DP * d : 0); // slight stagger: an assembly, not a toolbar
            int tz = touch[i].getMeasuredWidth();
            touch[i].layout(Math.round(x[i] - tz / 2f), Math.round(y[i] - tz / 2f), Math.round(x[i] + tz / 2f), Math.round(y[i] + tz / 2f));
            TextView lab = labels[i];
            int lw = lab.getMeasuredWidth(), lh = lab.getMeasuredHeight();
            int top = Math.round(y[i] + LABEL_GAP_DP * d);
            lab.layout(Math.round(x[i] - lw / 2f), top, Math.round(x[i] - lw / 2f) + lw, top + lh);
        }
    }

    @Override protected void dispatchDraw(Canvas cv) {
        int n = touch.length, w = getWidth();
        if (n > 0) {
            // trace up into the hub's crosshair, then along the row through every node
            int mid = n / 2;
            CollectiveDraw.curveTrace(cv, w / 2f, 0, x[mid], y[mid] - 8 * d, 0.1f, line, d, 0.6f);
            for (int i = 0; i + 1 < n; i++) CollectiveDraw.curveTrace(cv, x[i] + 8 * d, y[i], x[i + 1] - 8 * d, y[i + 1], i % 2 == 0 ? 0.12f : -0.12f, line, d, 0.7f);
            CollectiveDraw.via(cv, 6 * d, y[0], 2.5f * d, line, d, 0.8f);
            CollectiveDraw.curveTrace(cv, 9 * d, y[0], x[0] - 8 * d, y[0], 0f, line, d, 0.6f);
            CollectiveDraw.via(cv, w - 6 * d, y[n - 1], 2.5f * d, line, d, 0.8f);
            CollectiveDraw.curveTrace(cv, x[n - 1] + 8 * d, y[n - 1], w - 9 * d, y[n - 1], 0f, line, d, 0.6f);
            for (int i = 0; i < n; i++) {
                int col = Skin.glow(colors[i]);
                if (i == active) CollectiveDraw.node(cv, x[i], y[i], 9 * d, col, d, 1f);
                else CollectiveDraw.ringNode(cv, x[i], y[i], 8 * d, col, d, 0.85f);
            }
        }
        super.dispatchDraw(cv);
    }
}
