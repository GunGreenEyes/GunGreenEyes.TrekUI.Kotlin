package com.gungreeneyes.trekui.keyboard;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Original line-art glyphs for the function keys (shift, delete, return, globe, emoji, microphone), drawn
 * with paths in the key's lettering colour so they match every interface. Each glyph fits a square of
 * side {@code s} centred on ({@code cx}, {@code cy}).
 */
final class KeyIcons {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    KeyIcons() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
    }

    private void prepare(int color, float s) {
        stroke.setColor(color);
        fill.setColor(color);
        stroke.setStrokeWidth(Math.max(1.5f, s * 0.085f));
    }

    /** Up arrow; filled when shifted; with a bar underneath when caps lock is on. */
    void shift(Canvas c, float cx, float cy, float s, int color, boolean on, boolean locked) {
        prepare(color, s);
        float h = s * 0.5f, w = s * 0.42f;
        float top = cy - h * 0.62f, mid = cy + h * 0.02f, bot = cy + h * 0.42f;
        path.reset();
        path.moveTo(cx, top);
        path.lineTo(cx + w, mid);
        path.lineTo(cx + w * 0.45f, mid);
        path.lineTo(cx + w * 0.45f, bot);
        path.lineTo(cx - w * 0.45f, bot);
        path.lineTo(cx - w * 0.45f, mid);
        path.lineTo(cx - w, mid);
        path.close();
        c.drawPath(path, on ? fill : stroke);
        if (on) c.drawPath(path, stroke);
        if (locked) c.drawLine(cx - w * 0.5f, bot + h * 0.3f, cx + w * 0.5f, bot + h * 0.3f, stroke);
    }

    /** A left-pointing tag with an x. */
    void delete(Canvas c, float cx, float cy, float s, int color) {
        prepare(color, s);
        float w = s * 0.62f, h = s * 0.42f;
        float l = cx - w / 2f, rr = cx + w / 2f, t = cy - h / 2f, b = cy + h / 2f;
        path.reset();
        path.moveTo(l, cy);
        path.lineTo(l + h * 0.55f, t);
        path.lineTo(rr, t);
        path.lineTo(rr, b);
        path.lineTo(l + h * 0.55f, b);
        path.close();
        c.drawPath(path, stroke);
        float xc = (l + h * 0.55f + rr) / 2f + w * 0.03f, d = h * 0.2f;
        c.drawLine(xc - d, cy - d, xc + d, cy + d, stroke);
        c.drawLine(xc - d, cy + d, xc + d, cy - d, stroke);
    }

    /** A return arrow (down then left). */
    void enter(Canvas c, float cx, float cy, float s, int color) {
        prepare(color, s);
        float w = s * 0.56f, h = s * 0.34f;
        float rr = cx + w / 2f, l = cx - w / 2f, t = cy - h * 0.7f, b = cy + h * 0.35f;
        path.reset();
        path.moveTo(rr, t);
        path.lineTo(rr, b);
        path.lineTo(l, b);
        c.drawPath(path, stroke);
        float a = h * 0.42f;
        c.drawLine(l, b, l + a, b - a, stroke);
        c.drawLine(l, b, l + a, b + a, stroke);
    }

    /** A globe: circle, equator, two meridian arcs. */
    void globe(Canvas c, float cx, float cy, float s, int color) {
        prepare(color, s);
        float rad = s * 0.3f;
        c.drawCircle(cx, cy, rad, stroke);
        c.drawLine(cx - rad, cy, cx + rad, cy, stroke);
        r.set(cx - rad * 0.45f, cy - rad, cx + rad * 0.45f, cy + rad);
        c.drawOval(r, stroke);
    }

    /** A smiling face. */
    void emoji(Canvas c, float cx, float cy, float s, int color) {
        prepare(color, s);
        float rad = s * 0.3f;
        c.drawCircle(cx, cy, rad, stroke);
        float e = rad * 0.13f;
        c.drawCircle(cx - rad * 0.36f, cy - rad * 0.25f, e, fill);
        c.drawCircle(cx + rad * 0.36f, cy - rad * 0.25f, e, fill);
        r.set(cx - rad * 0.5f, cy - rad * 0.3f, cx + rad * 0.5f, cy + rad * 0.55f);
        c.drawArc(r, 20f, 140f, false, stroke);
    }

    /** A microphone. */
    void mic(Canvas c, float cx, float cy, float s, int color) {
        prepare(color, s);
        float w = s * 0.16f, h = s * 0.32f;
        r.set(cx - w, cy - h, cx + w, cy + h * 0.25f);
        c.drawRoundRect(r, w, w, stroke);
        r.set(cx - w * 1.9f, cy - h * 0.55f, cx + w * 1.9f, cy + h * 0.6f);
        c.drawArc(r, 0f, 180f, false, stroke);
        c.drawLine(cx, cy + h * 0.6f, cx, cy + h, stroke);
    }
}
