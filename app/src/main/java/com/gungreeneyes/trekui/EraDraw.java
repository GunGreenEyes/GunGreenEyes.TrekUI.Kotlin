package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Drawing for the two extra Command eras ({@link Skin#ERA23}, {@link Skin#ERA25}). Every
 * Command screen keeps its structure (frames, cascade, drawer, dialogs); the pieces it asks
 * {@link Ui}, {@link Skin}, {@link ConsoleFrame}, {@link ConsoleCard}, {@link CommandCascade} and
 * {@link IconFactory} for are drawn here instead:
 *
 * <ul>
 *   <li><b>23rd century</b>: square, chunky keys in flat primary colours with a bevel (light top
 *       edge, dark bottom edge, darker end cap on LEFT/RIGHT keys) and small square indicator
 *       lamps, about a third of which blink. Frames have no elbows: the bar and the sidebar are
 *       separate blocks with lamps in the bar.</li>
 *   <li><b>25th century</b>: thin translucent glass. 1dp outlines with 45° cut corners, a narrow
 *       solid accent strip on the leading edge, a faint inset second layer and fine tick rules.</li>
 * </ul>
 *
 * Sizes come from {@link Ui#density} (so they follow the window zoom); the geometry and lamp
 * timing rules are the pure functions in {@link Era}. Lamps blink only while the view is drawn
 * and animations are on ({@link Ui#animate}); each blinking drawable reschedules itself for its
 * next toggle, so a static console costs nothing.
 */
final class EraDraw {
    private EraDraw() {}

    /** Shared paint and path for the static helpers (UI thread only). */
    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path PATH = new Path(), PATH2 = new Path();
    private static final RectF RR = new RectF();
    /** A little slack after a lamp's toggle time before redrawing. */
    private static final long BLINK_SLACK_MS = 16;
    /** Translucency of a glass fill, a faint (empty-slot) glass fill and a 25th-century frame fill. */
    private static final float GLASS_ALPHA = Era.GLASS_TINT, GLASS_FAINT_ALPHA = 0.06f, FRAME_GLASS_ALPHA = 0.22f;

    // ---- shared helpers ----------------------------------------------------------------------------

    /** Loads an {@link Era#cutPolygon} point list into {@code out} as a closed path. */
    static Path poly(Path out, float[] pts) {
        out.reset();
        out.moveTo(pts[0], pts[1]);
        for (int i = 2; i + 1 < pts.length; i += 2) out.lineTo(pts[i], pts[i + 1]);
        out.close();
        return out;
    }

    /** True when a colour is a faint (empty slot / disabled) variant rather than a solid control colour. */
    private static boolean faint(int color) {
        return Color.alpha(color) <= 0x80;
    }

    /**
     * A fine tick rule from {@code x0} to {@code x1} at {@code y}: short ticks every
     * {@link Era#TICK_SPACING_DP}, every {@link Era#TICK_MAJOR_EVERY}th one longer. Ticks rise
     * ({@code up}) or hang from the line.
     */
    static void ticks(Canvas cv, float x0, float x1, float y, boolean up, int color, float d, float alpha) {
        int n = Era.tickCount(x1 - x0, d);
        if (n < 2) return;
        float step = (x1 - x0) / (n - 1);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(Math.max(1f, 0.8f * d));
        P.setColor(Ui.alpha(color | 0xFF000000, alpha));
        float dir = up ? -1 : 1;
        for (int i = 0; i < n; i++) {
            float len = (i % Era.TICK_MAJOR_EVERY == 0 ? 4f : 2f) * d;
            float x = x0 + i * step;
            cv.drawLine(x, y, x, y + dir * len, P);
        }
        P.setStyle(Paint.Style.FILL);
    }

    // ---- 23rd century: keys, blocks, lamps ---------------------------------------------------------

    /**
     * One indicator lamp: a square coloured cap in a dark bezel; lit lamps get a soft halo and a
     * highlight, unlit ones a dark cap.
     */
    static void lamp(Canvas cv, float cx, float cy, float s, int color, boolean on, float d, float alpha) {
        float h = s / 2f;
        P.setStyle(Paint.Style.FILL);
        P.setColor(Ui.alpha(Color.BLACK, 0.6f * alpha));
        cv.drawRect(cx - h - d, cy - h - d, cx + h + d, cy + h + d, P);
        if (on) {
            P.setColor(Ui.alpha(color, 0.30f * alpha));
            cv.drawCircle(cx, cy, s * 0.95f, P);
            P.setColor(Ui.alpha(color, alpha));
            cv.drawRect(cx - h, cy - h, cx + h, cy + h, P);
            P.setColor(Ui.alpha(Color.WHITE, 0.55f * alpha));
            cv.drawRect(cx - h, cy - h, cx - h * 0.1f, cy - h * 0.1f, P);
        } else {
            P.setColor(Ui.alpha(Ui.darken(color, 0.3f), alpha));
            cv.drawRect(cx - h, cy - h, cx + h, cy + h, P);
        }
    }

    /**
     * A flat 23rd-century block with a bevel: light top edge and dark bottom edge (or a plain
     * outline when cloaked). Used for frame bars, sidebar pieces and the body of every key.
     */
    static void block23(Canvas cv, float l, float t, float r, float b, int color, float d, boolean cloak) {
        if (r - l < 1 || b - t < 1) return;
        float rad = Math.min(Skin.ERA23_RADIUS * d, Math.min(r - l, b - t) / 4f);
        RR.set(l, t, r, b);
        if (cloak) {
            float sw = Math.max(1f, 1.5f * d);
            RR.inset(sw / 2f, sw / 2f);
            P.setStyle(Paint.Style.FILL);
            P.setColor(Color.BLACK);
            cv.drawRoundRect(RR, rad, rad, P);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(sw);
            P.setColor(Ui.alpha(color | 0xFF000000, 0.75f));
            cv.drawRoundRect(RR, rad, rad, P);
            P.setStyle(Paint.Style.FILL);
            return;
        }
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        cv.drawRoundRect(RR, rad, rad, P);
        if (faint(color)) return;
        float bev = Math.max(1f, Math.min(3f * d, (b - t) * 0.09f));
        cv.save();
        cv.clipRect(l, b - bev, r, b);
        P.setColor(Ui.darken(color, 0.62f));
        cv.drawRoundRect(RR, rad, rad, P);
        cv.restore();
        cv.save();
        cv.clipRect(l, t, r, t + Math.max(1f, bev * 0.55f));
        P.setColor(Skin.mix(color, Color.WHITE, 0.38f));
        cv.drawRoundRect(RR, rad, rad, P);
        cv.restore();
    }

    /**
     * A 23rd-century key: a {@link #block23} with a darker end cap on the rounded side of LEFT/RIGHT
     * keys (so segmented rows still read as one bar) and up to three indicator lamps.
     *
     * @param now  uptime in ms for blinking, or -1 for steady lamps
     * @return ms until the next lamp toggle, or -1 when nothing blinks
     */
    static long key23(Canvas cv, float l, float t, float r, float b, int color, int kind, float d, int seed, long now, boolean cloak) {
        float w = r - l, h = b - t;
        if (w < 2 || h < 2) return -1;
        block23(cv, l, t, r, b, color, d, cloak);
        boolean faint = faint(color);
        if (!cloak && !faint && (kind == Ui.LEFT || kind == Ui.RIGHT)) {
            float band = Math.min(8f * d, w * 0.15f);
            cv.save();
            if (kind == Ui.LEFT) cv.clipRect(l, t, l + band, b); else cv.clipRect(r - band, t, r, b);
            P.setColor(Ui.darken(color, 0.74f));
            float rad = Math.min(Skin.ERA23_RADIUS * d, Math.min(w, h) / 4f);
            RR.set(l, t, r, b);
            cv.drawRoundRect(RR, rad, rad, P);
            cv.restore();
        }
        if (faint) return -1;
        int n = Era.lampCount(w, h, d);
        if (n == 0) return -1;
        float ls = Era.LAMP_DP * d, lg = Era.LAMP_GAP_DP * d, inset = Era.LAMP_INSET_DP * d;
        float y, x0;
        if (h < 40 * d) {
            // button: one lamp at the leading end, centred vertically
            y = (t + b) / 2f;
            x0 = kind == Ui.RIGHT ? r - inset - ls / 2f : l + inset + ls / 2f;
        } else if (w >= 120 * d) {
            // tile: a row along the top, clear of the icon in the top-left corner
            y = t + inset + ls / 2f;
            x0 = l + 46 * d + ls / 2f;
        } else {
            // narrow key (sidebar block): a row in the top-right corner
            y = t + inset + ls / 2f;
            x0 = r - inset - ls / 2f - (n - 1) * (ls + lg);
        }
        long next = -1;
        for (int i = 0; i < n; i++) {
            int ls2 = seed * 7 + i;
            boolean on = cloak || now < 0 || Era.lampOn(now, ls2);
            lamp(cv, x0 + i * (ls + lg), y, ls, Era.lampColor(seed, i), on, d, cloak ? 0.5f : 1f);
            if (!cloak && now >= 0) {
                long nt = Era.nextToggleIn(now, ls2);
                if (nt > 0 && (next < 0 || nt < next)) next = nt;
            }
        }
        return next;
    }

    // ---- 25th century: glass -----------------------------------------------------------------------

    /**
     * A 25th-century glass control: translucent fill, 1dp outline with cut corners
     * ({@link Era#cutsFor}), a solid accent strip on the leading edge and, on large controls, a faint
     * inset second outline and a tick rule along the bottom. Thin PILL bars become pointed glass rules.
     */
    static void glass(Canvas cv, float l, float t, float r, float b, int color, int kind, float d, boolean cloak) {
        float w = r - l, h = b - t;
        if (w < 2 || h < 2) return;
        boolean faint = faint(color);
        int col = color | 0xFF000000;
        boolean thin = kind == Ui.PILL && h < 16 * d;
        int cuts = Era.cutsFor(kind);
        float k = thin ? h / 2f : Era.cutSize(w, h, d);
        float sw = Math.max(1f, d), hs = sw / 2f;
        poly(PATH, Era.cutPolygon(l + hs, t + hs, r - hs, b - hs, k, cuts));
        P.setStyle(Paint.Style.FILL);
        if (!cloak) {
            P.setColor(Ui.alpha(col, thin && !faint ? 0.55f : faint ? GLASS_FAINT_ALPHA : GLASS_ALPHA));
            cv.drawPath(PATH, P);
            if (!faint && !thin) {
                cv.save();
                cv.clipPath(PATH);
                float a = Math.max(1.5f * d, Math.min(3f * d, w * 0.06f));
                P.setColor(Ui.alpha(col, 0.85f));
                if (kind == Ui.RIGHT) cv.drawRect(r - a, t, r, b, P); else cv.drawRect(l, t, l + a, b, P);
                cv.restore();
            }
        }
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(sw);
        P.setColor(Ui.alpha(col, cloak ? 0.6f : faint ? 0.45f : 0.92f));
        cv.drawPath(PATH, P);
        if (!faint && !cloak && h >= 40 * d && w >= 100 * d) {
            float g = 4f * d;
            poly(PATH2, Era.cutPolygon(l + g, t + g, r - g, b - g, Math.max(0f, k - g * 0.4f), cuts));
            P.setStrokeWidth(Math.max(1f, 0.7f * d));
            P.setColor(Ui.alpha(col, 0.28f));
            cv.drawPath(PATH2, P);
            ticks(cv, l + k + 8 * d, r - k - 8 * d, b - g - 2 * d, true, col, d, 0.45f);
        }
        P.setStyle(Paint.Style.FILL);
    }

    // ---- control drawables -------------------------------------------------------------------------

    /** The control background for the current era (called from {@link Ui#shape}). */
    static Drawable cell(Context c, int color, int kind) {
        return Skin.era23() ? new Key23(c, color, kind) : new GlassCell(c, color, kind);
    }

    /** A drawable that redraws itself for its next lamp toggle while it is being drawn. */
    abstract static class Blinker extends Drawable {
        final float d;
        final boolean animate;
        private final Runnable tick = this::invalidateSelf;

        Blinker(Context c) {
            d = Ui.density(c);
            animate = Ui.animate(c);
        }

        /** Uptime for lamp phases, or -1 when animations are off (lamps then burn steadily). */
        long now() { return animate ? SystemClock.uptimeMillis() : -1; }

        /** Schedules a redraw {@code delay} ms from now (nothing when {@code delay} is negative). */
        void redrawIn(long delay) {
            unscheduleSelf(tick);
            if (delay > 0) scheduleSelf(tick, SystemClock.uptimeMillis() + delay + BLINK_SLACK_MS);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 23rd-century key drawable ({@link #key23}); the lamps vary per key. */
    static final class Key23 extends Blinker {
        private final int color, kind, seed;
        private final boolean cloak;

        Key23(Context c, int color, int kind) {
            super(c);
            this.color = color;
            this.kind = kind;
            this.seed = System.identityHashCode(this);
            this.cloak = Ui.cloak;
        }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            redrawIn(key23(cv, b.left, b.top, b.right, b.bottom, color, kind, d, seed, now(), cloak));
        }
    }

    /** 25th-century glass control drawable ({@link #glass}). */
    static final class GlassCell extends Drawable {
        private final int color, kind;
        private final float d;
        private final boolean cloak;

        GlassCell(Context c, int color, int kind) {
            this.color = color;
            this.kind = kind;
            this.d = Ui.density(c);
            this.cloak = Ui.cloak;
        }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            glass(cv, b.left, b.top, b.right, b.bottom, color, kind, d, cloak);
        }

        @Override public void setAlpha(int a) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * 25th-century tray / dialog / field outline (called from {@link Ui#outline}): fill, a thin
     * outline with the top-left and bottom-right corners cut, and short heavier segments running
     * off each cut, like layered glass edges.
     */
    static Drawable panel(Context c, final int fill, final int stroke, final float strokeDp) {
        final float d = Ui.density(c);
        return new Drawable() {
            final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path path = new Path();

            @Override public void draw(Canvas cv) {
                Rect b = getBounds();
                float w = b.width(), h = b.height();
                if (w < 2 || h < 2) return;
                float sw = Math.max(1f, strokeDp * 0.6f * d), hs = sw / 2f;
                float k = Era.cutSize(w, h, d);
                poly(path, Era.cutPolygon(b.left + hs, b.top + hs, b.right - hs, b.bottom - hs, k, Era.TL | Era.BR));
                pt.setStyle(Paint.Style.FILL);
                pt.setColor(fill);
                cv.drawPath(path, pt);
                pt.setStyle(Paint.Style.STROKE);
                pt.setStrokeWidth(sw);
                pt.setColor(stroke);
                cv.drawPath(path, pt);
                if (Color.alpha(stroke) < 0x20 || w < 60 * d) return;
                // heavier edge segments leaving each cut
                pt.setStrokeWidth(Math.max(2f, 2.5f * d));
                float run = Math.min(28 * d, w * 0.2f);
                cv.drawLine(b.left + k + hs, b.top + hs, b.left + k + run, b.top + hs, pt);
                cv.drawLine(b.right - k - run, b.bottom - hs, b.right - k - hs, b.bottom - hs, pt);
            }

            @Override public void setAlpha(int a) { pt.setAlpha(a); }
            @Override public void setColorFilter(ColorFilter cf) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /** Header / dialog rule: a solid console bar (23rd) or a glass band with ticks (25th). */
    static Drawable rule(Context c, final int color) {
        if (Skin.era23()) return new ColorDrawable(color);
        final float d = Ui.density(c);
        final boolean cloak = Ui.cloak;
        return new Drawable() {
            @Override public void draw(Canvas cv) {
                Rect b = getBounds();
                float band = Math.min(b.height(), 10 * d), cy = b.exactCenterY();
                glass(cv, b.left, cy - band / 2f, b.right, cy + band / 2f, color, Ui.PILL, d, cloak);
                if (!cloak && b.height() > band + 8 * d) {
                    ticks(cv, b.left + band, b.right - band, cy + band / 2f + 2 * d, false, color, d, 0.5f);
                }
            }

            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(ColorFilter cf) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    // ---- frame pieces (ConsoleFrame) -----------------------------------------------------------------

    /**
     * 23rd-century frame corner: no elbow. The bar and the sidebar piece are separate square
     * blocks with a gap between them, and a short row of indicator lamps sits in the bar above the
     * sidebar column.
     *
     * @param now uptime for blinking lamps, or -1 for steady ones
     * @return ms until the next lamp toggle, or -1
     */
    static long frameCorner23(Canvas cv, float d, int color, float sidebarW, float barH, float depth, float barEnd,
                              float gap, boolean cloak, int seed, long now) {
        block23(cv, 0, 0, barEnd, barH, color, d, cloak);
        if (depth - barH - gap > 2) block23(cv, 0, barH + gap, sidebarW, depth, color, d, cloak);
        if (barH < 9 * d || barEnd < sidebarW) return -1;
        float ls = Math.min(barH * 0.48f, Era.LAMP_DP * d), step = ls + Era.LAMP_GAP_DP * d;
        long next = -1;
        int i = 0;
        for (float x = sidebarW * 0.22f + ls / 2f; x + ls / 2f < sidebarW - 2 * d && i < Era.MAX_LAMPS; x += step, i++) {
            int s = seed * 13 + i;
            boolean on = cloak || now < 0 || Era.lampOn(now, s);
            lamp(cv, x, barH / 2f, ls, Era.lampColor(seed, i), on, d, cloak ? 0.5f : 1f);
            if (!cloak && now >= 0) {
                long nt = Era.nextToggleIn(now, s);
                if (nt > 0 && (next < 0 || nt < next)) next = nt;
            }
        }
        return next;
    }

    /**
     * 25th-century frame corner: a thin glass elbow. A 45° cut where console has its outer curve, a
     * 45° chamfer at the inner joint, translucent fill, accent strips along the outer edges, a tick
     * rule inside the bar and a hairline second layer just under it.
     */
    static void frameCorner25(Canvas cv, float d, int color, float sidebarW, float barH, float depth, float barEnd,
                              float rIn, float rOut, boolean cloak) {
        int col = color | 0xFF000000;
        float W = sidebarW, H = barH;
        float cut = Math.min(rOut * 0.55f, Math.min(W * 0.5f, depth * 0.5f));
        float inner = Math.min(rIn * 0.8f, W * 0.5f);
        float sw = Math.max(1f, 1.2f * d);
        PATH.reset();
        PATH.moveTo(0, depth);
        PATH.lineTo(0, cut);
        PATH.lineTo(cut, 0);
        PATH.lineTo(barEnd, 0);
        PATH.lineTo(barEnd, H);
        PATH.lineTo(W + inner, H);
        PATH.lineTo(W, H + inner);
        PATH.lineTo(W, depth);
        PATH.close();
        P.setStyle(Paint.Style.FILL);
        if (!cloak) {
            P.setColor(Ui.alpha(col, faint(color) ? GLASS_FAINT_ALPHA : FRAME_GLASS_ALPHA));
            cv.drawPath(PATH, P);
            cv.save();
            cv.clipPath(PATH);
            P.setColor(Ui.alpha(col, 0.9f));
            cv.drawRect(0, 0, barEnd, Math.max(1.5f * d, Math.min(3f * d, H * 0.28f)), P);
            cv.drawRect(0, 0, Math.max(1.5f * d, Math.min(3f * d, W * 0.08f)), depth, P);
            cv.restore();
        }
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(sw);
        P.setColor(Ui.alpha(col, cloak ? 0.6f : 0.95f));
        cv.drawPath(PATH, P);
        P.setStyle(Paint.Style.FILL);
        if (cloak) return;
        if (H >= 8 * d) ticks(cv, W + inner + 6 * d, barEnd - 6 * d, H - 1.5f * d, true, col, d, 0.55f);
        // the hairline second layer under the bar
        float ly = H + 3 * d, lx1 = W + inner + (barEnd - W - inner) * 0.62f;
        if (lx1 > W + inner + 20 * d) {
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(Math.max(1f, 0.7f * d));
            P.setColor(Ui.alpha(col, 0.35f));
            cv.drawLine(W + inner + 4 * d, ly, lx1, ly, P);
            P.setStyle(Paint.Style.FILL);
        }
    }

    /** One bar-end block of a frame in the current era (square console block or glass). */
    static void frameBlock(Canvas cv, float l, float t, float r, float b, int color, float d, boolean cloak) {
        if (Skin.era23()) block23(cv, l, t, r, b, color, d, cloak);
        else glass(cv, l, t, r, b, color, Ui.SQUARE, d, cloak);
    }

    // ---- card bracket (ConsoleCard) ------------------------------------------------------------------

    /**
     * Side bracket of a panel card: a heavy square "[" with a lamp in the top arm (23rd), or a thin
     * cut-corner bracket line with a glass strip and ticks down the upright (25th).
     */
    static void bracket(Canvas cv, float d, float w, float h, int color, boolean cloak) {
        float reach = w + 14 * d;
        if (Skin.era23()) {
            float bar = w * 0.55f, stub = 7 * d;
            block23(cv, 0, 0, bar, h, color, d, cloak);
            block23(cv, 0, 0, reach, stub, color, d, cloak);
            block23(cv, 0, h - stub, reach, h, color, d, cloak);
            if (!cloak && h > 40 * d) lamp(cv, reach - 6 * d, stub / 2f, Math.min(stub * 0.6f, 5 * d), Era.LAMPS[1], true, d, 1f);
            return;
        }
        int col = color | 0xFF000000;
        float k = Math.min(w * 0.7f, 10 * d), sw = Math.max(1f, 1.2f * d), hs = sw / 2f;
        PATH.reset();
        PATH.moveTo(reach, hs);
        PATH.lineTo(k, hs);
        PATH.lineTo(hs, k);
        PATH.lineTo(hs, h - k);
        PATH.lineTo(k, h - hs);
        PATH.lineTo(reach, h - hs);
        if (!cloak) {
            P.setStyle(Paint.Style.FILL);
            P.setColor(Ui.alpha(col, GLASS_ALPHA));
            cv.drawRect(hs, k, w * 0.4f, h - k, P);
            P.setColor(Ui.alpha(col, 0.85f));
            cv.drawRect(hs, k + 2 * d, hs + Math.max(1.5f * d, 2.5f * d), h - k - 2 * d, P);
        }
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(sw);
        P.setColor(Ui.alpha(col, cloak ? 0.6f : 0.95f));
        cv.drawPath(PATH, P);
        if (!cloak && h > 48 * d) {
            int n = Era.tickCount(h - 2 * k, d);
            float step = n > 1 ? (h - 2 * k) / (n - 1) : 0;
            P.setStrokeWidth(Math.max(1f, 0.8f * d));
            P.setColor(Ui.alpha(col, 0.5f));
            for (int i = 0; i < n; i++) {
                float y = k + i * step, len = (i % Era.TICK_MAJOR_EVERY == 0 ? 6f : 3f) * d;
                cv.drawLine(w * 0.45f, y, w * 0.45f + len, y, P);
            }
        }
        P.setStyle(Paint.Style.FILL);
    }

    // ---- cascade keys (CommandCascade) -----------------------------------------------------------------

    /** A key or spine segment of the console cascade drawn in the current era (lamps steady on the canvas). */
    static void key(Canvas cv, RectF r, int color, int kind, float d, boolean cloak, int seed) {
        if (Skin.era23()) key23(cv, r.left, r.top, r.right, r.bottom, color, kind, d, seed, -1, cloak);
        else glass(cv, r.left, r.top, r.right, r.bottom, color, kind, d, cloak);
    }

    // ---- icon frames (IconFactory.Framed) ----------------------------------------------------------

    /**
     * Frame behind an app icon or pictogram: a square console key with a bevel and one lamp
     * (23rd), or a cut-corner glass tile with an inset layer and a tick rule (25th).
     *
     * @param r    frame rectangle (already inset by the stroke)
     * @param s    icon size in px
     * @param mark colour of the small marks (lamp / accent strip)
     */
    static void iconFrame(Canvas cv, RectF r, float s, float sw, int color, int fill, int mark, int faction) {
        if (faction == Skin.ERA23) {
            float rad = s * 0.06f;
            P.setStyle(Paint.Style.FILL);
            P.setColor(fill);
            cv.drawRoundRect(r, rad, rad, P);
            float bev = s * 0.07f;
            cv.save();
            cv.clipRect(r.left, r.bottom - bev, r.right, r.bottom);
            P.setColor(Ui.alpha(Ui.darken(color, 0.55f), Color.alpha(fill) / 255f));
            cv.drawRoundRect(r, rad, rad, P);
            cv.restore();
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(sw * 1.3f);
            P.setColor(color);
            cv.drawRoundRect(r, rad, rad, P);
            P.setStyle(Paint.Style.FILL);
            float ls = s * 0.09f;
            P.setColor(Ui.alpha(Color.BLACK, 0.6f));
            cv.drawRect(r.left + s * 0.08f - sw * 0.4f, r.top + s * 0.08f - sw * 0.4f, r.left + s * 0.08f + ls + sw * 0.4f, r.top + s * 0.08f + ls + sw * 0.4f, P);
            P.setColor(mark);
            cv.drawRect(r.left + s * 0.08f, r.top + s * 0.08f, r.left + s * 0.08f + ls, r.top + s * 0.08f + ls, P);
            return;
        }
        float k = s * 0.18f;
        poly(PATH, Era.cutPolygon(r.left, r.top, r.right, r.bottom, k, Era.TL | Era.BR));
        P.setStyle(Paint.Style.FILL);
        P.setColor(fill);
        cv.drawPath(PATH, P);
        cv.save();
        cv.clipPath(PATH);
        P.setColor(mark);
        cv.drawRect(r.left, r.top, r.left + s * 0.05f, r.bottom, P);
        cv.restore();
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(sw * 0.7f);
        P.setColor(color);
        cv.drawPath(PATH, P);
        float g = s * 0.07f;
        poly(PATH2, Era.cutPolygon(r.left + g, r.top + g, r.right - g, r.bottom - g, k * 0.7f, Era.TL | Era.BR));
        P.setStrokeWidth(Math.max(1f, sw * 0.4f));
        P.setColor(Ui.alpha(color, 0.35f));
        cv.drawPath(PATH2, P);
        P.setStyle(Paint.Style.FILL);
    }

    // ---- header ornaments ----------------------------------------------------------------------------

    /** Ornament behind the home clock: a bank of indicator lamps (23rd) or a fine-line reticle (25th). */
    static Drawable ornament(Context c, Palette p) {
        return Skin.era23() ? new LampBank(c) : new Reticle(c, p);
    }

    /**
     * 23rd-century lamp bank: two short columns of lamps at each end of the clock row, some
     * blinking. Kept clear of the middle so the time stays readable.
     */
    static final class LampBank extends Blinker {
        private final int seed = 0x2301;
        private final boolean cloak = Ui.cloak;

        LampBank(Context c) { super(c); }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            float ls = 7 * d, step = ls + 5 * d;
            int rows = (int) Math.max(0, Math.min(3, (b.height() - 8 * d) / step));
            int cols = (int) Math.max(0, Math.min(3, (b.width() * 0.16f) / step));
            if (rows == 0 || cols == 0) return;
            long now = now(), next = -1;
            float top = b.exactCenterY() - (rows - 1) * step / 2f;
            for (int side = 0; side < 2; side++) {
                for (int i = 0; i < cols; i++) {
                    float x = side == 0 ? b.left + 6 * d + ls / 2f + i * step : b.right - 6 * d - ls / 2f - i * step;
                    for (int j = 0; j < rows; j++) {
                        int s = seed + side * 97 + i * 11 + j;
                        boolean on = cloak || now < 0 || Era.lampOn(now, s);
                        lamp(cv, x, top + j * step, ls, Era.lampColor(s, j), on, d, cloak ? 0.35f : 0.75f);
                        if (!cloak && now >= 0) {
                            long nt = Era.nextToggleIn(now, s);
                            if (nt > 0 && (next < 0 || nt < next)) next = nt;
                        }
                    }
                }
            }
            redrawIn(next);
        }
    }

    /**
     * 25th-century reticle: cut corner brackets at the four corners of the clock row, a fine tick
     * rule along its bottom and short arc segments at each end, all dim.
     */
    static final class Reticle extends Drawable {
        private final float d;
        private final int line, hi;
        private final boolean cloak = Ui.cloak;
        private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private float am = 1f;

        Reticle(Context c, Palette p) {
            d = Ui.density(c);
            line = p.c(2);
            hi = p.c(0);
        }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            float a = (cloak ? 0.3f : 0.6f) * am;
            float l = b.left + 3 * d, t = b.top + 3 * d, r = b.right - 3 * d, bt = b.bottom - 3 * d;
            float arm = Math.min(22 * d, (r - l) * 0.12f), k = 5 * d;
            pt.setStyle(Paint.Style.STROKE);
            pt.setStrokeWidth(Math.max(1f, d));
            pt.setColor(Ui.alpha(line | 0xFF000000, a));
            // corner brackets, each with a small 45° cut
            corner(cv, l, t, arm, k, 1, 1);
            corner(cv, r, t, arm, k, -1, 1);
            corner(cv, l, bt, arm, k, 1, -1);
            corner(cv, r, bt, arm, k, -1, -1);
            // arc segments at the ends
            float rr = Math.min((bt - t) * 0.42f, 34 * d), cy = (t + bt) / 2f;
            pt.setColor(Ui.alpha(hi | 0xFF000000, a * 0.8f));
            arc.set(l + arm * 0.6f, cy - rr, l + arm * 0.6f + rr * 2, cy + rr);
            cv.drawArc(arc, 140, 80, false, pt);
            arc.set(r - arm * 0.6f - rr * 2, cy - rr, r - arm * 0.6f, cy + rr);
            cv.drawArc(arc, -40, 80, false, pt);
            ticks(cv, l + arm + 8 * d, r - arm - 8 * d, bt, true, line, d, a * 0.8f);
        }

        /** One L-shaped corner bracket at (x, y) opening toward (sx, sy), its joint cut by {@code k}. */
        private void corner(Canvas cv, float x, float y, float arm, float k, int sx, int sy) {
            Path pth = PATH;
            pth.reset();
            pth.moveTo(x + sx * arm, y);
            pth.lineTo(x + sx * k, y);
            pth.lineTo(x, y + sy * k);
            pth.lineTo(x, y + sy * arm * 0.7f);
            cv.drawPath(pth, pt);
        }

        @Override public void setAlpha(int al) { am = al / 255f; }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ---- CONFIG previews -----------------------------------------------------------------------------

    /** Height of the interface preview strip on a CONFIG interface card, in dp. */
    static final float PREVIEW_DP = 64f;

    /**
     * A miniature of an era's home screen for its CONFIG interface card: frame corner, bar and a
     * few keys in the given palette. Draws as {@code faction} regardless of the active interface.
     */
    static Drawable preview(Context c, final int faction, final Palette p) {
        return new Blinker(c) {
            private final int seed = 0x7E57 + faction;

            @Override public void draw(Canvas cv) {
                Rect b = getBounds();
                float w = b.width(), h = b.height();
                if (w < 40 * d || h < 20 * d) return;
                cv.save();
                cv.translate(b.left, b.top);
                cv.drawColor(faction == Skin.ERA23 ? Era.ERA23_BG : Era.ERA25_BG);
                float side = Math.min(40 * d, w * 0.14f), bar = Math.max(6 * d, h * 0.14f);
                float x0 = side + 8 * d, top = bar + 8 * d;
                float rowH = (h - top - 4 * d) / 2f - 2 * d;
                int cols = Math.max(2, Math.min(5, (int) ((w - x0) / (70 * d))));
                float colW = (w - x0 - (cols - 1) * 4 * d) / cols;
                long next = -1;
                if (faction == Skin.ERA23) {
                    next = frameCorner23(cv, d, p.c(1), side, bar, h, w * 0.7f, 4 * d, false, seed, now());
                    block23(cv, w * 0.7f + 4 * d, 0, w, bar, p.c(0), d, false);
                    for (int i = 0; i < cols * 2; i++) {
                        float l = x0 + (i % cols) * (colW + 4 * d), t = top + (i / cols) * (rowH + 4 * d);
                        long nt = key23(cv, l, t, l + colW, t + rowH, p.c(i), Ui.SQUARE, d, seed + i, now(), false);
                        if (nt > 0 && (next < 0 || nt < next)) next = nt;
                    }
                } else {
                    frameCorner25(cv, d, p.c(2), side, bar, h, w * 0.7f, 10 * d, 24 * d, false);
                    glass(cv, w * 0.7f + 4 * d, 0, w, bar, p.c(4), Ui.PILL, d, false);
                    for (int i = 0; i < cols * 2; i++) {
                        float l = x0 + (i % cols) * (colW + 4 * d), t = top + (i / cols) * (rowH + 4 * d);
                        // keys overlap a step to show the layered glass
                        glass(cv, l + 3 * d, t - 2 * d, l + colW + 3 * d, t + rowH - 2 * d, p.c(i + 1), Ui.SQUARE, d, false);
                        glass(cv, l, t, l + colW, t + rowH, p.c(i), i % cols == 0 ? Ui.LEFT : Ui.SQUARE, d, false);
                    }
                }
                cv.restore();
                redrawIn(next);
            }
        };
    }
}
