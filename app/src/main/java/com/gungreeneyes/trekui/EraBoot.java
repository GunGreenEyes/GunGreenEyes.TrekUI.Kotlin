package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.util.Locale;

/**
 * Start-up sequences of the two Command eras, drawn by {@link BootSequence} on its scaled
 * portrait stage (so they are responsive the same way: shrunk on short screens, grown on tablets).
 *
 * <ul>
 *   <li><b>23rd century</b>: the console powers up. The bar and sidebar blocks slam in, a bank of
 *       chunky keys switches on one by one with their lamps, a chaser runs along the lamp row,
 *       circuits report ON, and ALL CIRCUITS GREEN.</li>
 *   <li><b>25th century</b>: fine lines draw themselves. The thin elbows trace their outlines, a
 *       tick rule grows along the bar, layered glass panels slide in, a reticle turns while the
 *       subsystems report NOMINAL, then SYSTEMS NOMINAL.</li>
 * </ul>
 *
 * Timings are in ms from the start of the sequence; {@link #durationFor} / {@link #fadeAtFor} give
 * the overall length and fade point (unit-tested).
 */
final class EraBoot {
    /** Sequence length and fade start per era, in ms. */
    static final long ERA23_DURATION = 3300, ERA23_FADE = 2850, ERA25_DURATION = 3400, ERA25_FADE = 2950;
    private static final String[] ERA23_LINES = {"MAIN POWER", "SENSOR CIRCUITS", "COMMUNICATIONS", "COMPUTER CORE", "HELM CONTROL"};
    private static final String[] ERA25_LINES = {"QUANTUM DATA CORE", "SENSOR LATTICE", "SUBSPACE RELAY", "FLEET UPLINK", "PERSONAL TERMINAL"};
    /** Key grid of the 23rd-century sequence. */
    private static final int KEY_COLS = 4, KEY_ROWS = 3;
    /** Lamps in the chaser row. */
    private static final int CHASER_LAMPS = 12;

    private final int faction;
    private final Palette pal;
    private final String header;
    private final float d, textScale;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), tp = new Paint(Paint.ANTI_ALIAS_FLAG), tl = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), part = new Path();
    private final RectF r = new RectF();
    /** Order in which the 23rd-century keys switch on (fixed scramble). */
    private final int[] keyOrder = new int[KEY_COLS * KEY_ROWS];

    EraBoot(Context c, Palette pal, String header, int faction) {
        this.faction = faction;
        this.pal = pal;
        this.header = header;
        this.d = c.getResources().getDisplayMetrics().density;
        this.textScale = Skin.textScale();
        Typeface heavy = Ui.font(c, true), light = Ui.font(c, false);
        tp.setTypeface(heavy);
        tl.setTypeface(light);
        for (int i = 0; i < keyOrder.length; i++) keyOrder[i] = i;
        for (int i = keyOrder.length - 1; i > 0; i--) {
            int j = Era.hash(i * 977) % (i + 1);
            int s = keyOrder[i];
            keyOrder[i] = keyOrder[j];
            keyOrder[j] = s;
        }
    }

    /** Total length of the sequence for an era faction, in ms. */
    static long durationFor(int faction) { return faction == Skin.ERA23 ? ERA23_DURATION : ERA25_DURATION; }

    /** When the sequence starts fading to the home screen, in ms. */
    static long fadeAtFor(int faction) { return faction == Skin.ERA23 ? ERA23_FADE : ERA25_FADE; }

    long duration() { return durationFor(faction); }
    long fadeAt() { return fadeAtFor(faction); }

    private float dp(float v) { return v * d; }
    private float ts(float v) { return v * d * textScale; }

    private static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    /** Ease-out progress of a step running from {@code a} to {@code b} ms. */
    static float step(long t, long a, long b) {
        float x = clamp((t - a) / (float) (b - a));
        return 1 - (1 - x) * (1 - x);
    }

    /**
     * Draws the sequence at {@code t} ms on a {@code w} x {@code h} stage.
     *
     * @param al  overall alpha (0..1) while fading out
     * @return true the moment the final status line first appears (the caller plays its chirp)
     */
    boolean draw(Canvas cv, long t, int w, int h, float al) {
        return faction == Skin.ERA23 ? console(cv, t, w, h, al) : glassBoot(cv, t, w, h, al);
    }

    // ==== 23rd century: the console powers up ==========================================================

    private boolean shownDone;

    private boolean console(Canvas cv, long t, int w, int h, float al) {
        float side = dp(56), bar = dp(20), top = dp(46), gap = dp(6);
        // 1. bar and sidebar blocks slam in
        float g = step(t, 0, 280);
        if (g > 0) {
            EraDraw.block23(cv, 0, top, w * 0.62f * g, top + bar, Ui.alpha(pal.c(0), al), d, false);
            float g2 = step(t, 140, 380);
            if (g2 > 0) EraDraw.block23(cv, w * 0.62f + gap, top, w * 0.62f + gap + (w - w * 0.62f - gap - dp(10)) * g2, top + bar, Ui.alpha(pal.c(1), al), d, false);
        }
        int[] sideCols = {pal.c(1), pal.c(2), pal.c(3), pal.c(4), pal.c(5)};
        float sTop = top + bar + gap, sBot = h - dp(40);
        float sh = (sBot - sTop) / sideCols.length;
        for (int i = 0; i < sideCols.length; i++) {
            float e = step(t, 120 + i * 70L, 300 + i * 70L);
            if (e <= 0) continue;
            float y = sTop + i * sh;
            EraDraw.block23(cv, -side * (1 - e), y, side - side * (1 - e), y + sh - gap, Ui.alpha(sideCols[i], al), d, false);
        }
        float x0 = side + dp(20), right = w - dp(16);
        // 2. title block
        float tf = step(t, 420, 760);
        if (tf > 0) {
            tp.setTextAlign(Paint.Align.RIGHT);
            tp.setTextSize(ts(52));
            tp.setColor(Ui.alpha(pal.c(0), al * tf));
            cv.drawText(Skin.title(), right, top + bar + dp(64), tp);
            tl.setTextAlign(Paint.Align.RIGHT);
            tl.setTextSize(ts(15));
            tl.setColor(Ui.alpha(pal.textLo, al * tf));
            cv.drawText(Skin.library(), right, top + bar + dp(90), tl);
            tl.setColor(Ui.alpha(pal.c(2), al * tf));
            cv.drawText(header, right, top + bar + dp(110), tl);
        }
        // 3. the key bank switches on, key by key
        float gy = top + bar + dp(132), gh = Math.min(dp(150), (h - gy) * 0.34f);
        float kw = (right - x0 - (KEY_COLS - 1) * gap) / KEY_COLS, kh = (gh - (KEY_ROWS - 1) * gap) / KEY_ROWS;
        for (int n = 0; n < keyOrder.length; n++) {
            int k = keyOrder[n];
            long at = 600 + n * 70L;
            if (t < at) continue;
            float l = x0 + (k % KEY_COLS) * (kw + gap), tt = gy + (k / KEY_COLS) * (kh + gap);
            boolean on = t > at + 90;
            int col = on ? pal.c(k) : Ui.darken(pal.c(k), 0.25f);
            EraDraw.key23(cv, l, tt, l + kw, tt + kh, Ui.alpha(col, al), Ui.SQUARE, d, k + 1, on ? t : -1, false);
        }
        // 4. lamp chaser under the bank
        float ly = gy + gh + dp(20);
        if (t > 900) {
            float ls = Math.min(dp(10), (right - x0) / (CHASER_LAMPS * 1.6f));
            float stepX = (right - x0 - ls) / (CHASER_LAMPS - 1);
            int lit = (int) ((t / 90) % CHASER_LAMPS);
            for (int i = 0; i < CHASER_LAMPS; i++) {
                boolean on = t > 2300 || i == lit || i == (lit + CHASER_LAMPS / 2) % CHASER_LAMPS;
                EraDraw.lamp(cv, x0 + ls / 2 + i * stepX, ly, ls, Era.LAMPS[i % Era.LAMPS.length], on, d, al);
            }
        }
        // 5. circuits report ON
        tp.setTextSize(ts(17));
        float ry = ly + dp(40);
        for (int i = 0; i < ERA23_LINES.length; i++) {
            long at = 1150 + i * 200L;
            if (t < at) break;
            float y = ry + i * dp(28);
            if (y > h - dp(90)) break;
            tl.setTextAlign(Paint.Align.LEFT);
            tl.setTextSize(ts(17));
            tl.setColor(Ui.alpha(pal.textHi, al));
            cv.drawText(ERA23_LINES[i], x0, y, tl);
            boolean ok = t > at + 150;
            EraDraw.lamp(cv, right - dp(44), y - dp(6), dp(9), ok ? Era.LAMPS[2] : Era.LAMPS[1], true, d, al);
            tp.setTextAlign(Paint.Align.RIGHT);
            tp.setColor(Ui.alpha(ok ? Era.LAMPS[2] : Era.LAMPS[1], al));
            cv.drawText(ok ? "ON" : "…", right, y, tp);
        }
        // 6. all green
        float on = step(t, 2300, 2480);
        boolean first = false;
        if (on > 0) {
            if (!shownDone) { shownDone = true; first = true; }
            tp.setTextAlign(Paint.Align.RIGHT);
            tp.setTextSize(ts(34));
            tp.setColor(Ui.alpha(Era.LAMPS[2], al * on));
            cv.drawText("ALL CIRCUITS GREEN", right, h - dp(56), tp);
        }
        return first;
    }

    // ==== 25th century: fine lines draw themselves =====================================================

    private boolean glassBoot(Canvas cv, long t, int w, int h, float al) {
        float side = dp(48), bar = dp(12), top = dp(48), bottom = h - dp(34), depth = dp(120);
        // 1. the thin elbows trace their outlines
        float tr = step(t, 0, 700), tr2 = step(t, 150, 850);
        traced(cv, top, side, bar, depth, w * 0.9f, pal.c(2), tr, al, false);
        traced(cv, bottom, side, bar, depth, w * 0.7f, pal.c(1), tr2, al, true);
        // once traced, the glass fills in
        float fill = step(t, 650, 950);
        if (fill > 0) {
            cv.save();
            cv.translate(0, top);
            EraDraw.frameCorner25(cv, d, Ui.alpha(pal.c(2), al * fill), side, bar, depth, w * 0.9f, dp(14), dp(40), false);
            cv.restore();
            cv.save();
            cv.translate(0, bottom);
            cv.scale(1, -1);
            EraDraw.frameCorner25(cv, d, Ui.alpha(pal.c(1), al * fill), side, bar, depth, w * 0.7f, dp(14), dp(40), false);
            cv.restore();
        }
        float x0 = side + dp(22), right = w - dp(16);
        // 2. reticle turning behind the title
        float rc = step(t, 300, 900);
        if (rc > 0) {
            float cx = x0 + dp(70), cy = top + bar + dp(78), rad = dp(52) * (0.7f + 0.3f * rc);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, d));
            float spin = t * 0.05f;
            for (int i = 0; i < 3; i++) {
                float rr = rad - i * dp(12);
                r.set(cx - rr, cy - rr, cx + rr, cy + rr);
                p.setColor(Ui.alpha(pal.c(i), al * rc * (0.75f - i * 0.15f)));
                float dir = i % 2 == 0 ? 1 : -1;
                for (int s = 0; s < 3; s++) cv.drawArc(r, dir * spin + s * 120 + i * 25, 70 - i * 12, false, p);
            }
            p.setColor(Ui.alpha(pal.textLo, al * rc * 0.5f));
            cv.drawLine(cx - rad - dp(10), cy, cx + rad + dp(10), cy, p);
            cv.drawLine(cx, cy - rad - dp(10), cx, cy + rad + dp(10), p);
            p.setStyle(Paint.Style.FILL);
        }
        // 3. title, thin and wide
        float tf = step(t, 500, 900);
        if (tf > 0) {
            tl.setTextAlign(Paint.Align.RIGHT);
            tl.setTextSize(ts(60));
            tl.setColor(Ui.alpha(pal.textHi, al * tf));
            cv.drawText(Skin.title(), right, top + bar + dp(78), tl);
            tl.setTextSize(ts(14));
            tl.setColor(Ui.alpha(pal.c(0), al * tf));
            cv.drawText(Skin.library(), right, top + bar + dp(102), tl);
            tl.setColor(Ui.alpha(pal.textLo, al * tf));
            cv.drawText(header, right, top + bar + dp(122), tl);
        }
        // 4. layered glass panels slide in from the right, each a step behind the last
        float py = top + bar + dp(150), ph = dp(44);
        for (int i = 0; i < 3; i++) {
            float e = step(t, 700 + i * 120L, 1150 + i * 120L);
            if (e <= 0) continue;
            float off = (1 - e) * (w * 0.6f);
            float l = x0 + i * dp(14) + off, tt = py + i * dp(10);
            EraDraw.glass(cv, l, tt, right - (2 - i) * dp(8) + off, tt + ph, Ui.alpha(pal.c(i + 1), al * e), Ui.SQUARE, d, false);
        }
        // 5. subsystems with fine rules, NOMINAL
        float ry = py + ph + dp(70);
        for (int i = 0; i < ERA25_LINES.length; i++) {
            long at = 1250 + i * 190L;
            if (t < at) break;
            float y = ry + i * dp(30);
            if (y > bottom - dp(90)) break;
            float lf = step(t, at, at + 160);
            tl.setTextAlign(Paint.Align.LEFT);
            tl.setTextSize(ts(16));
            tl.setColor(Ui.alpha(pal.textHi, al * lf));
            cv.drawText(ERA25_LINES[i], x0, y, tl);
            boolean ok = t > at + 170;
            tl.setTextAlign(Paint.Align.RIGHT);
            tl.setColor(Ui.alpha(ok ? pal.c(0) : pal.c(4), al * lf));
            cv.drawText(ok ? String.format(Locale.US, "NOMINAL %02d", 98 - i * 3) : "…", right, y, tl);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, 0.7f * d));
            p.setColor(Ui.alpha(pal.c(2), al * lf * 0.45f));
            cv.drawLine(x0, y + dp(8), x0 + (right - x0) * lf, y + dp(8), p);
            p.setStyle(Paint.Style.FILL);
        }
        // 6. systems nominal
        float on = step(t, 2400, 2600);
        boolean first = false;
        if (on > 0) {
            if (!shownDone) { shownDone = true; first = true; }
            tl.setTextAlign(Paint.Align.RIGHT);
            tl.setTextSize(ts(34));
            tl.setColor(Ui.alpha(pal.c(0), al * on));
            cv.drawText("SYSTEMS NOMINAL", right, bottom - bar - dp(30), tl);
        }
        return first;
    }

    /**
     * The outline of a thin elbow at {@code y}, drawn up to {@code fraction} of its length (the
     * frame "draws itself"), plus a tick rule that grows along the bar.
     */
    private void traced(Canvas cv, float y, float side, float bar, float depth, float barEnd, int color, float fraction, float al, boolean flip) {
        if (fraction <= 0) return;
        float cut = Math.min(dp(22), side * 0.5f), inner = dp(11);
        path.reset();
        path.moveTo(0, depth);
        path.lineTo(0, cut);
        path.lineTo(cut, 0);
        path.lineTo(barEnd, 0);
        path.lineTo(barEnd, bar);
        path.lineTo(side + inner, bar);
        path.lineTo(side, bar + inner);
        path.lineTo(side, depth);
        PathMeasure pm = new PathMeasure(path, false);
        part.reset();
        pm.getSegment(0, pm.getLength() * clamp(fraction), part, true);
        cv.save();
        cv.translate(0, y);
        if (flip) cv.scale(1, -1);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1f, 1.2f * d));
        p.setColor(Ui.alpha(color, al));
        cv.drawPath(part, p);
        p.setStyle(Paint.Style.FILL);
        EraDraw.ticks(cv, side + inner + dp(6), side + inner + dp(6) + (barEnd - side - inner - dp(12)) * fraction, bar + dp(4), false, color, d, 0.6f * al);
        cv.restore();
    }
}
