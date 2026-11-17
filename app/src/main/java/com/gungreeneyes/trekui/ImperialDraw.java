package com.gungreeneyes.trekui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Drawing vocabulary for the Imperial interface, after the Imperial screens: bright blood-red
 * outlines on black, long octagonal and hexagonal frames with big 45° corner cuts (often
 * double-lined), rows of alternating triangles and stacked triangle clusters in yellow, orange
 * and red, and triangle-lattice fills. No curves anywhere. (Original geometry; no emblems or
 * alien script.) UI thread only.
 */
final class ImperialDraw {
    static final int RED = 0xFFD8231E, ORANGE = 0xFFF0821E, YELLOW = 0xFFF5C830, DEEP = 0xFFA3161A;
    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path T = new Path();

    private ImperialDraw() {}

    /** Colour with alpha, dimmed further while cloaked. */
    private static int cl(int c, float a) { return Ui.alpha(c, a * (Ui.cloak ? 0.55f : 1f)); }

    /** Isoceles triangle of width s and height h, centred on (cx, cy), pointing up or down. */
    static void tri(Canvas cv, float cx, float cy, float s, float h, boolean up, int color, float alpha) {
        T.reset();
        if (up) {
            T.moveTo(cx, cy - h / 2);
            T.lineTo(cx + s / 2, cy + h / 2);
            T.lineTo(cx - s / 2, cy + h / 2);
        } else {
            T.moveTo(cx - s / 2, cy - h / 2);
            T.lineTo(cx + s / 2, cy - h / 2);
            T.lineTo(cx, cy + h / 2);
        }
        T.close();
        P.setStyle(Ui.cloak ? Paint.Style.STROKE : Paint.Style.FILL);
        P.setStrokeWidth(1.2f);
        P.setColor(cl(color, alpha));
        cv.drawPath(T, P);
    }

    /** ▲▼▲▼… packed edge to edge across (x, y, w, h), colours cycling. */
    static void row(Canvas cv, float x, float y, float w, float h, int[] colors, float alpha) {
        float s = h * 1.15f;
        int n = Math.max(1, (int) ((w - s / 2) / (s / 2)));
        for (int i = 0; i < n; i++) {
            float cx = x + s / 2 + i * s / 2;
            tri(cv, cx, y + h / 2, s * 0.92f, h * 0.92f, i % 2 == 0, colors[i % colors.length], alpha);
        }
    }

    /** A stacked cluster: one triangle over two, with an inverted one between (yellow/orange/red). */
    static void cluster(Canvas cv, float cx, float cy, float s, float alpha) {
        float h = s * 0.87f;
        tri(cv, cx, cy - h / 2, s, h, true, YELLOW, alpha);
        tri(cv, cx - s / 2, cy + h / 2, s, h, true, ORANGE, alpha);
        tri(cv, cx + s / 2, cy + h / 2, s, h, true, RED, alpha);
        tri(cv, cx, cy + h / 2, s * 0.8f, h * 0.8f, false, DEEP, alpha * 0.9f);
    }

    /** Triangle lattice (three line directions) over r. */
    static void grid(Canvas cv, RectF r, float step, int color, float alpha, float d) {
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(Math.max(1f, 0.8f * d));
        P.setColor(cl(color, alpha));
        float hgt = step * 0.866f;
        for (float y = r.top; y <= r.bottom; y += hgt) cv.drawLine(r.left, y, r.right, y, P);
        float span = r.height() / 1.732f;
        for (float x = r.left - span; x <= r.right + span; x += step) {
            cv.drawLine(x, r.bottom, x + span, r.top, P);
            cv.drawLine(x, r.top, x + span, r.bottom, P);
        }
    }

    /** Long octagon with 45° cuts of size k on the chosen corners. */
    static Path octagon(Path p, float l, float t, float r, float b, float k, boolean leftCuts, boolean rightCuts) {
        p.reset();
        float kl = leftCuts ? k : 0, kr = rightCuts ? k : 0;
        p.moveTo(l + kl, t);
        p.lineTo(r - kr, t);
        p.lineTo(r, t + kr);
        p.lineTo(r, b - kr);
        p.lineTo(r - kr, b);
        p.lineTo(l + kl, b);
        p.lineTo(l, b - kl);
        p.lineTo(l, t + kl);
        p.close();
        return p;
    }

    /** A frame outline: heavy outer line, optional thin inner line (the double-lined look). */
    static void frame(Canvas cv, Path outer, Path inner, int color, float d, float alpha) {
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeJoin(Paint.Join.MITER);
        P.setStrokeWidth(2.4f * d);
        P.setColor(cl(color, alpha));
        cv.drawPath(outer, P);
        if (inner != null) {
            P.setStrokeWidth(1f * d);
            P.setColor(cl(color, 0.45f * alpha));
            cv.drawPath(inner, P);
        }
    }

    /** Solid fill of a path (plate backgrounds). */
    static void fill(Canvas cv, Path p, int color) {
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        cv.drawPath(p, P);
    }
}
