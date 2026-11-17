package com.gungreeneyes.trekui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * Drawing vocabulary for the Collective interface, after the on-screen Collective displays: glowing circular
 * nodes with concentric rings, joined by thin organic traces that end in small ring vias, with
 * strings of tiny dots for data. No hard edges, no rectangles, no elbows.
 * All calls happen on the UI thread, so one shared Paint is enough.
 */
final class CollectiveDraw {
    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path TMP = new Path();

    private CollectiveDraw() {}

    /** A trace: soft glow underneath, crisp hairline on top. */
    static void trace(Canvas cv, Path path, int color, float d, float alpha) {
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setStrokeJoin(Paint.Join.ROUND);
        if (!Ui.cloak) {
            P.setStrokeWidth(4.5f * d);
            P.setColor(Ui.alpha(color, 0.16f * alpha));
            cv.drawPath(path, P);
        }
        P.setStrokeWidth(1.3f * d);
        P.setColor(Ui.alpha(color, (Ui.cloak ? 0.55f : 0.9f) * alpha));
        cv.drawPath(path, P);
    }

    /** A trace with a chosen core weight (dp): spokes of different importance. */
    static void traceW(Canvas cv, Path path, int color, float d, float alpha, float widthDp) {
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setStrokeJoin(Paint.Join.ROUND);
        if (!Ui.cloak) {
            P.setStrokeWidth((widthDp + 3.2f) * d);
            P.setColor(Ui.alpha(color, 0.15f * alpha));
            cv.drawPath(path, P);
        }
        P.setStrokeWidth(widthDp * d);
        P.setColor(Ui.alpha(color, (Ui.cloak ? 0.55f : 0.9f) * alpha));
        cv.drawPath(path, P);
    }

    /** Gentle S-curve from (x0,y0) to (x1,y1); bend sets which way it bows. */
    static Path curve(float x0, float y0, float x1, float y1, float bend) {
        Path p = new Path();
        float dx = x1 - x0, dy = y1 - y0;
        p.moveTo(x0, y0);
        p.cubicTo(x0 + dx * 0.35f - dy * bend, y0 + dy * 0.35f + dx * bend,
                x0 + dx * 0.65f + dy * bend, y0 + dy * 0.65f - dx * bend, x1, y1);
        return p;
    }

    /** {@link #trace} along {@link #curve}. */
    static void curveTrace(Canvas cv, float x0, float y0, float x1, float y1, float bend, int color, float d, float alpha) {
        trace(cv, curve(x0, y0, x1, y1, bend), color, d, alpha);
    }

    /** A via: small ring with a dark core. */
    static void via(Canvas cv, float x, float y, float r, int color, float d, float alpha) {
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(Skin.bg());
        cv.drawCircle(x, y, r, P);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1.2f * d);
        P.setColor(Ui.alpha(color, (Ui.cloak ? 0.55f : 0.95f) * alpha));
        cv.drawCircle(x, y, r, P);
    }

    /**
     * A node: luminous sphere with a soft halo and concentric rings. While cloaked only the rings
     * are drawn.
     */
    static void node(Canvas cv, float x, float y, float r, int color, float d, float alpha) {
        if (r < 1) return;
        P.setStyle(Paint.Style.FILL);
        if (!Ui.cloak) {
            int core = Skin.mix(color, Color.WHITE, 0.45f), edge = color;
            P.setShader(new RadialGradient(x, y, r * 1.45f,
                    new int[]{Ui.alpha(core, alpha), Ui.alpha(edge, 0.92f * alpha), Ui.alpha(edge, 0.85f * alpha),
                            Ui.alpha(edge, 0.18f * alpha), Ui.alpha(edge, 0f)},
                    new float[]{0f, 0.45f, 0.68f, 0.72f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(x, y, r * 1.45f, P);
            P.setShader(null);
        } else {
            P.setColor(Color.BLACK);
            cv.drawCircle(x, y, r, P);
        }
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(Math.max(1f, 1.2f * d));
        P.setColor(Ui.cloak ? Ui.alpha(color, 0.6f * alpha) : Ui.alpha(Skin.mix(color, Color.WHITE, 0.3f), 0.9f * alpha));
        cv.drawCircle(x, y, r, P);
        if (r > 6 * d) {
            P.setColor(Ui.alpha(Ui.cloak ? color : Skin.mix(color, Color.BLACK, 0.35f), (Ui.cloak ? 0.45f : 0.55f) * alpha));
            cv.drawCircle(x, y, r * 0.64f, P);
        }
    }

    /** A hollow target node: rings only (used for hubs and satellites that must not hide text). */
    static void ringNode(Canvas cv, float x, float y, float r, int color, float d, float alpha) {
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1.4f * d);
        float a = Ui.cloak ? 0.5f : 1f;
        P.setColor(Ui.alpha(color, 0.85f * alpha * a));
        cv.drawCircle(x, y, r, P);
        P.setStrokeWidth(d);
        P.setColor(Ui.alpha(color, 0.5f * alpha * a));
        cv.drawCircle(x, y, r * 0.7f, P);
        cv.drawCircle(x, y, r * 0.38f, P);
        P.setStyle(Paint.Style.FILL);
        P.setColor(Ui.alpha(color, 0.8f * alpha * a));
        cv.drawCircle(x, y, Math.max(1.5f * d, r * 0.12f), P);
    }

    /** A row of tiny data dots; how many are bright depends on seed. */
    static void dots(Canvas cv, float x, float y, int n, int color, float d, int seed, float alpha) {
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        float r = 1.5f * d, step = 4.6f * d;
        for (int i = 0; i < n; i++) {
            boolean lit = ((seed >> (i % 8)) & 1) == 1 || i == 0;
            P.setColor(Ui.alpha(color, (lit ? 0.9f : 0.3f) * alpha * (Ui.cloak ? 0.6f : 1f)));
            cv.drawCircle(x + i * step, y, r, P);
        }
    }

    /** Soft rounded plate for small controls (dialog buttons, chips): no corners, faint edge. */
    static void plate(Canvas cv, float l, float t, float r, float b, int color, float d) {
        float rad = Math.min((b - t) / 2f, 22 * d);
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(Ui.cloak ? Color.BLACK : Skin.mix(Skin.surface(), color, 0.14f));
        cv.drawRoundRect(l, t, r, b, rad, rad, P);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1.2f * d);
        P.setColor(Ui.alpha(Skin.glow(color), Ui.cloak ? 0.5f : 0.45f));
        float h = 0.6f * d;
        cv.drawRoundRect(l + h, t + h, r - h, b - h, rad, rad, P);
    }

    /** A shared scratch path, reset; valid until the next call (UI thread only). */
    static Path tmp() { TMP.reset(); return TMP; }

    /**
     * The home tile column for Collective: draws S-curved traces linking each tile's node to the next
     * (behind the tiles, which have no background), so the list reads as one connected network.
     * Node position matches NodeCell: 32 dp in from the tile's left, vertically centred.
     */
    static final class TraceColumn extends android.widget.LinearLayout {
        private final float d;
        private final int color;

        TraceColumn(android.content.Context c, int color) {
            super(c);
            this.color = color;
            d = c.getResources().getDisplayMetrics().density;
            setWillNotDraw(false);
        }

        @Override protected void dispatchDraw(Canvas cv) {
            float px = -1, py = -1, nr = 18 * d;
            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                android.view.View row = getChildAt(i);
                if (!(row instanceof android.view.ViewGroup) || ((android.view.ViewGroup) row).getChildCount() == 0) continue;
                android.view.View tile = ((android.view.ViewGroup) row).getChildAt(0);
                float x = row.getLeft() + tile.getLeft() + 32 * d, y = row.getTop() + row.getHeight() / 2f;
                if (px < 0) {
                    Path p = tmp();
                    p.moveTo(x, y - nr);
                    p.lineTo(x, Math.max(3 * d, y - nr - 14 * d));
                    trace(cv, p, color, d, 0.6f);
                    via(cv, x, Math.max(3 * d, y - nr - 14 * d), 3 * d, color, d, 0.8f);
                } else {
                    Path p = tmp();
                    float dy = (y - nr) - (py + nr);
                    p.moveTo(px, py + nr);
                    p.cubicTo(px, py + nr + dy * 0.6f, x, y - nr - dy * 0.6f, x, y - nr);
                    trace(cv, p, color, d, 0.6f);
                }
                px = x;
                py = y;
            }
            if (px >= 0) {
                Path p = tmp();
                p.moveTo(px, py + nr);
                p.quadTo(px, py + nr + 18 * d, px + 22 * d, py + nr + 22 * d);
                trace(cv, p, color, d, 0.5f);
                via(cv, px + 22 * d, py + nr + 22 * d, 3 * d, color, d, 0.7f);
            }
            super.dispatchDraw(cv);
        }
    }
}
