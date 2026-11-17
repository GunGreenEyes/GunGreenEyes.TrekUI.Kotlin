package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.Locale;
import java.util.Random;

/**
 * console start-up sequence shown over the home screen once after the phone restarts:
 * the frame sweeps in, "CENTRAL COMPUTER ACCESS / CONTROL SYSTEM" comes up, subsystems
 * report online one by one while the progress strip fills, then it fades to the home screen.
 * About three seconds; tap anywhere to skip. Draws only while visible, then removes itself.
 * Imperial and Collective interfaces get their own sequences ({@link #imperial}, {@link #collective}); the
 * 23rd- and 25th-century Command eras play theirs from {@link EraBoot}.
 *
 * <p>Responsive: the sequence is composed on a portrait "stage" at least
 * {@link #MIN_STAGE_W_DP} x {@link #MIN_STAGE_H_DP} dp. On short screens (landscape phones,
 * split screen) the whole stage is scaled down to fit; on tablets it is scaled up by the screen's
 * UI scale ({@link #stageScale}).
 */
final class BootSequence extends View {
    /** Command sequence length and when it starts fading out, in ms. */
    private static final long DURATION = 3150, FADE_AT = 2700;
    /** Smallest stage the sequences are designed for, in dp. */
    static final float MIN_STAGE_W_DP = 360, MIN_STAGE_H_DP = 640;
    /** Fade-out after a tap to skip, in ms. */
    private static final float SKIP_FADE_MS = 250;
    private static final String[][] LINES = {
        {"MEMORY CORE", "OPTICAL DATA NETWORK", "SUBSPACE TRANSCEIVER", "SENSOR ARRAY", "COMMUNICATIONS", "PERSONAL ACCESS TERMINAL"},
        {"TACTICAL ARRAY", "TARGETING SENSORS", "SUBSPACE RADIO", "SHIELD GRID", "COMMUNICATIONS", "WARRIOR CONTROL"},
        {"NODE 01 / CORE NEXUS LINK", "NODE 02 / SUBSPACE NETWORK", "NODE 03 / SENSOR MATRIX", "NODE 04 / INTERFACE CELLS",
            "NODE 05 / COMMUNICATIONS", "NODE 06 / LOCAL TERMINAL"}};
    private static final String[] OK = {"ONLINE", "READY", "LINKED"};
    private static final String[] DONE = {"ALL SYSTEMS ONLINE", "ALL STATIONS READY", "LINK ESTABLISHED"};
    private final int faction = Skin.faction;
    private android.graphics.drawable.Drawable ornament;
    /** The era sequence (23rd / 25th century), or null for every other interface. */
    private final EraBoot era;

    private final Palette pal;
    private final String header;
    private final Runnable onDone;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final Random rnd = new Random();
    private final int[][] cascade = new int[14][2];
    private long start, skipAt = -1, lastCascade;
    private boolean onlineChirp, finished;

    BootSequence(Context c, Palette pal, String header, Runnable onDone) {
        super(c);
        this.pal = pal;
        this.header = header.toUpperCase(Locale.getDefault());
        this.onDone = onDone;
        tp.setTypeface(Ui.font(c, true));
        ornament = Skin.headerOrnament(c, pal);
        era = Skin.eraFaction(faction) ? new EraBoot(c, pal, this.header, faction) : null;
        for (int[] row : cascade) { row[0] = rnd.nextInt(9999); row[1] = rnd.nextInt(99999); }
        setClickable(true);
        setContentDescription("TrekUI start-up sequence. Tap to skip.");
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    /**
     * Scale applied to the design stage: the screen's UI scale, reduced so the stage is never
     * shorter than {@link #MIN_STAGE_H_DP} or narrower than {@link #MIN_STAGE_W_DP}.
     *
     * @param wPx     view width in pixels
     * @param hPx     view height in pixels
     * @param density pixels per dp
     * @param uiScale {@link ScreenMetrics#uiScale}
     */
    static float stageScale(float wPx, float hPx, float density, float uiScale) {
        if (wPx <= 0 || hPx <= 0 || density <= 0) return 1f;
        float s = Math.min(uiScale, Math.min(hPx / (MIN_STAGE_H_DP * density), wPx / (MIN_STAGE_W_DP * density)));
        return Math.max(0.3f, s);
    }

    /** Text size, scaled for the faction's (wider) typeface. */
    private float ts(float v) { return dp(v) * Skin.textScale(); }

    private static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    /** Ease-out progress of a step that runs from a to b milliseconds. */
    private static float step(long t, long a, long b) {
        float x = clamp((t - a) / (float) (b - a));
        return 1 - (1 - x) * (1 - x);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start = SystemClock.uptimeMillis();
        // the ship's computer announces itself: "Accessing library computer data"
        if (!Skin.command()) Stations.play(getContext(), "boot_start");
        else if (SoundLibrary.exists(getContext(), "voice_accessing_library")) Sfx.playClip(getContext(), "voice_accessing_library", true);
        else if (SoundLibrary.exists(getContext(), Sfx.synthBootFor(Skin.faction))) Sfx.playClip(getContext(), Sfx.synthBootFor(Skin.faction), true);
        else Sfx.play(getContext(), Sfx.LAUNCH);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && skipAt < 0) {
            long t = SystemClock.uptimeMillis() - start;
            skipAt = Math.max(0, t);
            invalidate();
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas cv) {
        long t = SystemClock.uptimeMillis() - start;
        float alpha = 1f;
        long dur = era != null ? era.duration() : faction == Skin.IMPERIAL ? K_DURATION : faction == Skin.COLLECTIVE ? B_DURATION : DURATION;
        long fadeAt = era != null ? era.fadeAt() : faction == Skin.IMPERIAL ? K_FADE : faction == Skin.COLLECTIVE ? B_FADE : FADE_AT;
        if (skipAt >= 0) alpha = 1 - clamp((t - skipAt) / SKIP_FADE_MS);
        else if (t > fadeAt) alpha = 1 - clamp((t - fadeAt) / (float) (dur - fadeAt));
        if (alpha <= 0) { finish(); return; }

        cv.drawColor((Math.round(alpha * 255) << 24));
        float s = stageScale(getWidth(), getHeight(), getResources().getDisplayMetrics().density,
                ScreenMetrics.of(getContext()).uiScale);
        int w = Math.round(getWidth() / s), h = Math.round(getHeight() / s);
        cv.save();
        cv.scale(s, s);
        try {
            if (era != null) {
                if (era.draw(cv, t, w, h, alpha) && skipAt < 0) Sfx.play(getContext(), Sfx.STATUS_OK);
            } else if (faction == Skin.IMPERIAL) imperial(cv, t, w, h, alpha);
            else if (faction == Skin.COLLECTIVE) collective(cv, t, w, h, alpha);
            else console(cv, t, w, h, Math.round(alpha * 255));
        } finally {
            cv.restore();
        }
        postInvalidateOnAnimation();
    }

    /**
     * Command (and fallback) sequence on a w x h stage: elbows sweep out, the title block, the
     * subsystem report, the progress strip, a number cascade and "ALL SYSTEMS ONLINE".
     */
    private void console(Canvas cv, long t, int w, int h, int a) {
        float top = dp(54), bottom = h - dp(40), side = dp(64), bar = dp(22), ro = dp(52), ri = dp(20);

        if (faction == Skin.COMMAND) {
            // frame: top elbow (c0) and bottom elbow (c1) sweep out from the corner
            float g1 = step(t, 0, 520), g2 = step(t, 180, 700);
            elbow(cv, top, side, bar, ro, ri, w * 0.68f * g1, dp(170) * g1, pal.c(0), a, false);
            elbow(cv, bottom, side, bar, ro, ri, w * 0.55f * g2, dp(150) * g2, pal.c(1), a, true);
            // sidebar segments between the elbows
            float segTop = top + dp(176), segBot = bottom - dp(156);
            int[] segCols = {pal.c(3), pal.c(5), pal.c(2), pal.c(4)};
            float segH = (segBot - segTop) / segCols.length;
            for (int i = 0; i < segCols.length; i++) {
                float sh = step(t, 300 + i * 90, 600 + i * 90);
                if (sh <= 0) continue;
                p.setColor(withAlpha(segCols[i], a));
                cv.drawRect(0, segTop + i * segH + dp(3), side, segTop + i * segH + dp(3) + (segH - dp(6)) * sh, p);
            }
            // small pills after the bars
            float pill = step(t, 520, 700);
            if (pill > 0) {
                p.setColor(withAlpha(pal.c(3), Math.round(a * pill)));
                r.set(w * 0.68f + dp(8), top, w * 0.68f + dp(60), top + bar);
                cv.drawRoundRect(r, bar / 2, bar / 2, p);
                p.setColor(withAlpha(pal.c(4), Math.round(a * pill)));
                r.set(w * 0.68f + dp(68), top, w - dp(12), top + bar);
                cv.drawRoundRect(r, bar / 2, bar / 2, p);
            }
        } else {
            factionFrame(cv, t, w, h, top, bottom, side, bar, a);
        }

        float x0 = side + dp(26), right = w - dp(18);
        // title block
        float tf = step(t, 450, 800);
        if (tf > 0) {
            tp.setTextAlign(Paint.Align.RIGHT);
            tp.setColor(withAlpha(pal.c(0), Math.round(a * tf)));
            tp.setTextSize(ts(64));
            cv.drawText(Skin.title(), right, top + bar + dp(70), tp);
            tp.setTextSize(ts(15));
            tp.setColor(withAlpha(pal.c(1), Math.round(a * tf)));
            cv.drawText(Skin.library(), right, top + bar + dp(96), tp);
            tp.setColor(withAlpha(pal.c(2), Math.round(a * tf)));
            cv.drawText(header, right, top + bar + dp(118), tp);
        }

        // subsystem report
        tp.setTextSize(ts(17));
        float ly = top + dp(250);
        String[] lines = LINES[faction];
        for (int i = 0; i < lines.length; i++) {
            long at = 800 + i * 210L;
            float lf = step(t, at, at + 120);
            if (lf <= 0) break;
            boolean ok = t > at + 170;
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setColor(withAlpha(pal.textHi, Math.round(a * lf)));
            cv.drawText(lines[i], x0, ly + i * dp(30), tp);
            tp.setTextAlign(Paint.Align.RIGHT);
            tp.setColor(withAlpha(ok ? pal.c(2) : pal.c(4), Math.round(a * lf)));
            cv.drawText(ok ? OK[faction] : "…", right, ly + i * dp(30), tp);
        }

        // progress strip
        float pb = clamp((t - 800) / 1500f);
        int blocks = 12;
        float by = ly + lines.length * dp(30) + dp(22), bw = (right - x0) / blocks;
        for (int k = 0; k < blocks; k++) {
            boolean lit = k < Math.round(pb * blocks);
            p.setColor(withAlpha(lit ? pal.c(k % 3 == 2 ? 4 : 0) : Ui.darken(pal.c(2), 0.3f), a));
            r.set(x0 + k * bw, by, x0 + (k + 1) * bw - dp(6), by + dp(14));
            if (faction == Skin.IMPERIAL) {
                // slanted plates
                path.reset();
                path.moveTo(r.left + dp(6), r.top);
                path.lineTo(r.right, r.top);
                path.lineTo(r.right - dp(6), r.bottom);
                path.lineTo(r.left, r.bottom);
                path.close();
                cv.drawPath(path, p);
            } else if (faction == Skin.COLLECTIVE) {
                float d1 = getResources().getDisplayMetrics().density;
                if (k > 0) CollectiveDraw.curveTrace(cv, r.centerX() - bw + dp(6), r.centerY(), r.centerX() - dp(6), r.centerY(), 0f, pal.c(2), d1, a / 255f);
                if (lit) CollectiveDraw.node(cv, r.centerX(), r.centerY(), dp(6), p.getColor(), d1, a / 255f);
                else CollectiveDraw.via(cv, r.centerX(), r.centerY(), dp(4), pal.c(2), d1, a / 255f);
            } else {
                cv.drawRoundRect(r, dp(7), dp(7), p);
            }
        }

        // data cascade under the strip
        if (t - lastCascade > 90 && t < FADE_AT) {
            lastCascade = t;
            System.arraycopy(cascade, 0, cascade, 1, cascade.length - 1);
            cascade[0] = new int[]{rnd.nextInt(9999), rnd.nextInt(99999)};
        }
        float cf = step(t, 900, 1200);
        if (cf > 0) {
            tp.setTextSize(ts(13));
            float cy = by + dp(48);
            int rows = (int) Math.max(0, Math.min(cascade.length, (bottom - dp(170) - cy) / dp(18)));
            for (int i = 0; i < rows; i++) {
                float fade = 1f - i / (float) Math.max(1, rows);
                tp.setTextAlign(Paint.Align.LEFT);
                tp.setColor(withAlpha(pal.c(1), Math.round(a * cf * (0.25f + 0.6f * fade))));
                cv.drawText(String.format(Locale.US, "%04d", cascade[i][0]), x0, cy + i * dp(18), tp);
                tp.setColor(withAlpha(pal.c(2), Math.round(a * cf * (0.2f + 0.5f * fade))));
                cv.drawText(String.format(Locale.US, "%05d", cascade[i][1]), x0 + dp(64), cy + i * dp(18), tp);
                tp.setTextAlign(Paint.Align.RIGHT);
                tp.setColor(withAlpha(pal.c(3), Math.round(a * cf * (0.2f + 0.5f * fade))));
                cv.drawText(String.format(Locale.US, "%02d-%04d", (cascade[i][0] % 90) + 10, cascade[i][1] % 10000), right, cy + i * dp(18), tp);
            }
        }

        // "ONLINE"
        float on = step(t, 2250, 2450);
        if (on > 0) {
            if (!onlineChirp && skipAt < 0) { onlineChirp = true; Sfx.play(getContext(), Sfx.STATUS_OK); }
            tp.setTextAlign(Paint.Align.RIGHT);
            tp.setTextSize(ts(40));
            tp.setColor(withAlpha(pal.c(2), Math.round(a * on)));
            cv.drawText(DONE[faction], right, bottom - bar - dp(34), tp);
        }
    }

    /**
     * Imperial: a chamfered header plate slides in and the targeting reticle opens. Collective: conduit
     * modules light from the left and the node network assembles. Neither uses an console elbow.
     */
    private void factionFrame(Canvas cv, long t, int w, int h, float top, float bottom, float side, float bar, int a) {
        float g1 = step(t, 0, 520), g2 = step(t, 180, 700);
        if (faction == Skin.IMPERIAL) {
            float d1 = getResources().getDisplayMetrics().density, al = a / 255f;
            // a long double-lined octagon frame opens out from the centre line
            float fw = (w - dp(12)) * g1, fl = (w - fw) / 2f, ft = top, fb = bottom - bar;
            float fh = (fb - ft) * g2;
            if (fw > dp(40) && fh > dp(20)) {
                float my = (ft + fb) / 2f;
                Path o = ImperialDraw.octagon(new Path(), fl, my - fh / 2, fl + fw, my + fh / 2, Math.min(dp(34), fh * 0.2f), true, true);
                Path in = ImperialDraw.octagon(new Path(), fl + dp(5), my - fh / 2 + dp(5), fl + fw - dp(5), my + fh / 2 - dp(5),
                        Math.min(dp(30), fh * 0.18f), true, true);
                cv.save();
                cv.clipPath(in);
                ImperialDraw.grid(cv, new RectF(0, 0, w, h), dp(26), ImperialDraw.RED, 0.12f * al, d1);
                cv.restore();
                ImperialDraw.frame(cv, o, in, pal.c(0), d1, al);
            }
            // triangle rows sweep in along the bottom, clusters appear at the top corners
            float sl = step(t, 350, 750);
            if (sl > 0) {
                float rw = (w - dp(80)) * sl;
                ImperialDraw.row(cv, dp(40), bottom - bar * 0.9f, rw, bar * 0.7f,
                        new int[]{ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED}, al);
                ImperialDraw.cluster(cv, dp(44), top + dp(46), dp(16), al * sl);
                ImperialDraw.cluster(cv, w - dp(44), top + dp(46), dp(16), al * sl);
            }
        } else {
            // hub node in the corner, a trace growing along the top through vias and nodes,
            // and another down the left side with nodes appearing as it passes
            float d1 = getResources().getDisplayMetrics().density;
            float hx = dp(6) + side * 0.5f, hy = top + bar * 0.5f, hr = dp(16) * g1;
            float lim = hx + (w - dp(18) - hx) * g1;
            float x = hx + dp(16);
            float[] runs = {0.18f, 0.24f, 0.16f, 0.27f, 0.15f};
            for (int i = 0; x < lim; i++) {
                float e = Math.min(lim, x + (w - hx) * runs[i % runs.length]);
                CollectiveDraw.curveTrace(cv, x, hy, e, hy, i % 2 == 0 ? 0.07f : -0.06f, pal.c(0), d1, a / 255f);
                if (e < lim) {
                    if (i % 2 == 0) CollectiveDraw.via(cv, e, hy, dp(3), pal.c(0), d1, a / 255f);
                    else CollectiveDraw.node(cv, e, hy, dp(6), pal.c(1), d1, a / 255f);
                }
                x = e;
            }
            float lineB = hy + hr + (bottom - hy - hr) * g2;
            if (lineB > hy + hr + 1) {
                android.graphics.Path rail = new android.graphics.Path();
                rail.moveTo(hx, hy + hr);
                rail.lineTo(hx, lineB);
                CollectiveDraw.trace(cv, rail, pal.c(0), d1, 0.8f * a / 255f);
                for (float y = hy + dp(120); y < lineB; y += dp(140)) {
                    CollectiveDraw.curveTrace(cv, hx, y, hx + dp(40), y + dp(30), 0.2f, pal.c(0), d1, 0.6f * a / 255f);
                    CollectiveDraw.node(cv, hx, y, dp(7), pal.c(1), d1, a / 255f);
                    CollectiveDraw.via(cv, hx + dp(40), y + dp(30), dp(3), pal.c(0), d1, a / 255f);
                }
            }
            if (hr > 1) CollectiveDraw.node(cv, hx, hy, hr, pal.c(0), d1, a / 255f);
        }
        // the faction ornament opens behind the title
        if (ornament != null) {
            float o = step(t, 250, 900);
            if (o > 0) {
                int cw = Math.round(dp(150) * (0.6f + 0.4f * o)), ch = Math.round(dp(96) * (0.6f + 0.4f * o));
                int cx = Math.round(side + dp(80)), cy = Math.round(top + bar + dp(58));
                ornament.setBounds(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2);
                ornament.setAlpha(Math.round(a * o));
                ornament.draw(cv);
            }
        }
    }

    // ==== Imperial: the triangles assemble ================================================================

    private static final long K_DURATION = 3500, K_FADE = 3050;
    private static final int K_ROWS = 6;
    private boolean readyChirp, midChirp;

    private float[] kStart; // per small triangle: start x, start y (fractions of the screen), spin

    /**
     * Three blade slashes; small triangles fly in from the edges and lock into one large triangle
     * formation; a double-lined octagon traces itself around it; "TACTICAL" stamps down; a ▲▼
     * strip charges; readouts snap in; the formation flashes on ALL STATIONS READY.
     */
    private void imperial(Canvas cv, long t, int w, int h, float al) {
        float d = getResources().getDisplayMetrics().density;
        // 1. slashes
        for (int i = 0; i < 3; i++) {
            float e = step(t, i * 90L, 380 + i * 90L), f = 1 - clamp((t - 450 - i * 90) / 400f);
            if (e <= 0 || f <= 0) continue;
            float y = h * (0.18f + i * 0.08f), len = w * 1.4f * e, th = dp(7 - i * 2);
            path.reset();
            path.moveTo(-dp(20), y + dp(40));
            path.lineTo(-dp(20) + len, y - len * 0.25f + dp(40));
            path.lineTo(-dp(20) + len, y - len * 0.25f + dp(40) + th);
            path.lineTo(-dp(20), y + dp(40) + th);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.alpha(i == 1 ? ImperialDraw.ORANGE : ImperialDraw.RED, al * f));
            cv.drawPath(path, p);
        }
        if (!midChirp && t >= 350 && skipAt < 0) { midChirp = true; Stations.play(getContext(), "boot_mid"); }
        // 2. formation: a big triangle tiled by small ones (up and down), flying in
        float S = Math.min(w * 0.72f, h * 0.36f), s = S / K_ROWS, hs = s * 0.866f;
        float cx = w / 2f, top = h * 0.2f;
        int total = K_ROWS * K_ROWS;
        if (kStart == null) {
            kStart = new float[total * 3];
            for (int k = 0; k < total; k++) {
                double ang = rnd.nextDouble() * Math.PI * 2;
                kStart[k * 3] = 0.5f + (float) Math.cos(ang) * 0.9f;
                kStart[k * 3 + 1] = 0.5f + (float) Math.sin(ang) * 0.9f;
                kStart[k * 3 + 2] = (rnd.nextFloat() - 0.5f) * 540;
            }
        }
        float flash = clamp(1 - Math.abs(t - 2650) / 160f);
        int k = 0;
        for (int r = 0; r < K_ROWS; r++) {
            int rowColor = r < 2 ? ImperialDraw.YELLOW : r < 4 ? ImperialDraw.ORANGE : ImperialDraw.RED;
            for (int j = 0; j <= 2 * r; j++, k++) {
                boolean up = j % 2 == 0;
                float tx = cx + (j - r) * s / 2f, ty = top + r * hs + hs / 2f;
                long a0 = 350 + k * 17L;
                float e = step(t, a0, a0 + 460);
                if (e <= 0) continue;
                float sx = kStart[k * 3] * w, sy = kStart[k * 3 + 1] * h;
                float x = sx + (tx - sx) * e, y = sy + (ty - sy) * e;
                cv.save();
                cv.rotate(kStart[k * 3 + 2] * (1 - e), x, y);
                int c = up ? rowColor : ImperialDraw.DEEP;
                float bright = up ? 1f : 0.85f;
                ImperialDraw.tri(cv, x, y, s * 0.94f, hs * 0.94f, up, flash > 0 ? Skin.mix(c, 0xFFFFE08A, flash * 0.45f) : c, al * bright);
                cv.restore();
            }
        }
        // 3. octagon frame traces around the formation
        float half = Math.min(S * 0.72f, w / 2f - dp(12));
        float fl = cx - half, fr = cx + half, ft = top - dp(22), fb = top + K_ROWS * hs + dp(22);
        float fe = step(t, 1050, 1650);
        if (fe > 0) {
            Path o = ImperialDraw.octagon(new Path(), fl, ft, fr, fb, dp(30), true, true);
            Path in = ImperialDraw.octagon(new Path(), fl + dp(6), ft + dp(6), fr - dp(6), fb - dp(6), dp(26), true, true);
            partial(cv, o, fe, ImperialDraw.RED, dp(2.6f), al);
            partial(cv, in, fe, ImperialDraw.RED, dp(1), al * 0.5f);
        }
        // 4. title stamps down, subtitle, charging ▲▼ strip
        float ty0 = fb + dp(70);
        float st = clamp((t - 1500) / 180f);
        if (st > 0) {
            float sc = 1.5f - 0.5f * (1 - (1 - st) * (1 - st));
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(ts(60) * sc);
            tp.setColor(Ui.alpha(ImperialDraw.RED, al * st));
            cv.drawText("TACTICAL", cx, ty0, tp);
            tp.setTextSize(ts(15));
            tp.setColor(Ui.alpha(ImperialDraw.ORANGE, al * st));
            cv.drawText("IMPERIAL TACTICAL / WARRIOR CONTROL", cx, ty0 + dp(26), tp);
        }
        float rowY = ty0 + dp(44), rowW = Math.min(w * 0.8f, dp(320)), tri = dp(16);
        int n = (int) (rowW / (tri / 2)) - 1;
        float charged = clamp((t - 1700) / 700f) * n;
        for (int i = 0; i < n; i++) {
            boolean lit = i < charged;
            int c = new int[]{ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED}[i % 3];
            ImperialDraw.tri(cv, cx - rowW / 2 + tri / 2 + i * tri / 2, rowY + tri / 2, tri * 0.92f, tri * 0.8f, i % 2 == 0,
                    lit ? c : Ui.darken(c, 0.25f), al * (t > 1650 ? 1 : 0));
        }
        // 5. readouts snap in, then ALL STATIONS READY
        String[][] lines = {{"WEAPONS", "ARMED"}, {"SHIELDS", "READY"}, {"CLOAK", "STANDING BY"}};
        tp.setTextSize(ts(17));
        for (int i = 0; i < lines.length; i++) {
            long at = 1950 + i * 160L;
            if (t < at) break;
            float y = rowY + dp(56) + i * dp(28);
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setColor(Ui.alpha(pal.textHi, al));
            cv.drawText(lines[i][0], cx - rowW / 2, y, tp);
            tp.setTextAlign(Paint.Align.RIGHT);
            tp.setColor(Ui.alpha(ImperialDraw.YELLOW, al));
            cv.drawText(lines[i][1], cx + rowW / 2, y, tp);
        }
        if (t > 2550) {
            if (!readyChirp && skipAt < 0) { readyChirp = true; Stations.play(getContext(), "boot_ready"); }
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(ts(34));
            tp.setColor(Ui.alpha(ImperialDraw.YELLOW, al * step(t, 2550, 2700)));
            cv.drawText("ALL STATIONS READY", cx, h - dp(70), tp);
        }
    }

    /** Draw the first fraction of a path's outline (the frame "traces itself"). */
    private void partial(Canvas cv, Path src, float fraction, int color, float width, float al) {
        android.graphics.PathMeasure pm = new android.graphics.PathMeasure(src, true);
        Path part = new Path();
        pm.getSegment(0, pm.getLength() * clamp(fraction), part, true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(Ui.alpha(color, al));
        cv.drawPath(part, p);
        p.setStyle(Paint.Style.FILL);
    }

    // ==== Collective: the collective links up ==================================================================

    private static final long B_DURATION = 3700, B_FADE = 3250;
    private android.graphics.drawable.Drawable collectiveBack;
    private float[] bx, by, br;
    private int[] bParent;

    /**
     * A faint trace network fades in; the hub ignites with rings rippling outward; traces grow to
     * ten nodes one after another, each lighting and naming itself as its trace arrives; the
     * subunit's designation types out; every node pulses together on LINK ESTABLISHED.
     */
    private void collective(Canvas cv, long t, int w, int h, float al) {
        float d = getResources().getDisplayMetrics().density;
        int line = pal.c(0), hi = pal.c(1);
        // 1. background network
        if (collectiveBack == null) collectiveBack = Skin.backdrop(getContext());
        float bf = step(t, 0, 600) * al;
        if (bf > 0) {
            int save = cv.saveLayerAlpha(0, 0, w, h, Math.round(bf * 170));
            collectiveBack.setBounds(0, 0, w, h);
            collectiveBack.draw(cv);
            cv.restoreToCount(save);
        }
        // radial geometry follows the narrower of width and (portrait-shaped) height, so the
        // network stays on screen when the stage is wider than tall
        float span = Math.min(w, h * 0.62f);
        float cx = w / 2f, cy = h * 0.4f, hubR = Math.min(w, h) * 0.075f;
        // node layout: four inner, six outer, outer ones branching from the nearest inner
        if (bx == null) {
            bx = new float[10]; by = new float[10]; br = new float[10]; bParent = new int[10];
            float[] ia = {-100, -10, 80, 170}, oa = {-140, -70, -20, 40, 115, 200};
            float[] rs = {9, 7, 8, 6, 6, 8, 5, 7, 6, 9};
            for (int i = 0; i < 4; i++) {
                double a = Math.toRadians(ia[i]);
                bx[i] = cx + (float) Math.cos(a) * span * 0.22f;
                by[i] = cy + (float) Math.sin(a) * span * 0.26f;
                bParent[i] = -1;
            }
            for (int i = 0; i < 6; i++) {
                double a = Math.toRadians(oa[i]);
                bx[4 + i] = cx + (float) Math.cos(a) * span * 0.41f;
                by[4 + i] = cy + (float) Math.sin(a) * span * 0.5f;
                int best = 0;
                float bd = Float.MAX_VALUE;
                for (int j = 0; j < 4; j++) {
                    float dd = (bx[j] - bx[4 + i]) * (bx[j] - bx[4 + i]) + (by[j] - by[4 + i]) * (by[j] - by[4 + i]);
                    if (dd < bd) { bd = dd; best = j; }
                }
                bParent[4 + i] = best;
            }
            for (int i = 0; i < 10; i++) br[i] = rs[i] * d;
        }
        // 2. hub ignites, rings ripple outward
        float ig = step(t, 250, 700);
        for (int k = 0; k < 3; k++) {
            float rp = ((t - 300 - k * 260) % 1100) / 1100f;
            if (t < 300 + k * 260 || t > 2900) continue;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(Ui.alpha(hi, al * (1 - rp) * 0.6f));
            cv.drawCircle(cx, cy, hubR + rp * span * 0.45f, p);
        }
        p.setStyle(Paint.Style.FILL);
        // crosshair once linked
        float ch = step(t, 2600, 2950);
        if (ch > 0) {
            Path cr = new Path();
            cr.moveTo(cx - w * 0.47f * ch, cy);
            cr.lineTo(cx + w * 0.47f * ch, cy);
            cr.moveTo(cx, cy - h * 0.3f * ch);
            cr.lineTo(cx, cy + h * 0.3f * ch);
            CollectiveDraw.traceW(cv, cr, hi, d, 0.35f * al, 1f);
        }
        if (!midChirp && t >= 700 && skipAt < 0) { midChirp = true; Stations.play(getContext(), "boot_mid"); }
        // 3. traces grow and nodes light, one after another
        float pulse = 1 + 0.35f * clamp(1 - Math.abs(t - 2900) / 180f);
        for (int i = 0; i < 10; i++) {
            long t0 = 700 + i * 120L;
            float g = step(t, t0, t0 + 340);
            if (g <= 0) continue;
            float px = bParent[i] < 0 ? cx : bx[bParent[i]], py = bParent[i] < 0 ? cy : by[bParent[i]];
            float ex = px + (bx[i] - px) * g, ey = py + (by[i] - py) * g;
            CollectiveDraw.traceW(cv, CollectiveDraw.curve(px, py, ex, ey, (i % 3 - 1) * 0.12f), line, d, al, bParent[i] < 0 ? 2.4f : 1.5f);
            if (g >= 1) {
                float lit = step(t, t0 + 340, t0 + 520);
                CollectiveDraw.node(cv, bx[i], by[i], br[i] * pulse, i % 2 == 0 ? hi : line, d, al * lit);
                tp.setTextAlign(bx[i] < cx ? Paint.Align.RIGHT : Paint.Align.LEFT);
                tp.setTextSize(ts(11));
                tp.setColor(Ui.alpha(hi, al * lit * 0.85f));
                cv.drawText(String.format(Locale.US, "NODE %02d", i + 1), bx[i] + (bx[i] < cx ? -1 : 1) * (br[i] + dp(6)), by[i] + dp(4), tp);
            } else {
                CollectiveDraw.via(cv, ex, ey, dp(2.5f), line, d, al);
            }
        }
        if (ig > 0) {
            CollectiveDraw.ringNode(cv, cx, cy, hubR * (0.6f + 0.4f * ig) * pulse, hi, d, al);
            CollectiveDraw.node(cv, cx, cy, hubR * 0.55f * ig * pulse, line, d, al);
        }
        // 4. designation types out under the network
        float ty = cy + span * 0.5f + dp(64);
        tp.setTextAlign(Paint.Align.CENTER);
        String l1 = "NODE CLUSTER 01", l2 = "SUBUNIT LINKED TO THE COLLECTIVE";
        int c1 = (int) Math.min(l1.length(), Math.max(0, (t - 1500) / 45));
        int c2 = (int) Math.min(l2.length(), Math.max(0, (t - 2100) / 22));
        tp.setTextSize(ts(30));
        tp.setColor(Ui.alpha(hi, al));
        if (c1 > 0) cv.drawText(l1.substring(0, c1) + (c1 < l1.length() && (t / 120) % 2 == 0 ? "_" : ""), cx, ty, tp);
        tp.setTextSize(ts(14));
        tp.setColor(Ui.alpha(line, al));
        if (c2 > 0) cv.drawText(l2.substring(0, c2), cx, ty + dp(26), tp);
        if (t > 2850) {
            if (!readyChirp && skipAt < 0) { readyChirp = true; Stations.play(getContext(), "boot_ready"); }
            tp.setTextSize(ts(32));
            tp.setColor(Ui.alpha(hi, al * step(t, 2850, 3000)));
            cv.drawText("LINK ESTABLISHED", cx, h - dp(70), tp);
        }
    }

    /** An console elbow: bar of height {@code bar} along the top, sidebar down the left, rounded joint. */
    private void elbow(Canvas cv, float y, float side, float bar, float ro, float ri, float len, float depth, int color, int a, boolean flip) {
        if (len <= 1 || depth <= 1) return;
        len = Math.max(len, ro);
        depth = Math.max(depth, ro);
        float ri2 = Math.min(ri, Math.max(1, len - side));
        path.reset();
        path.addRoundRect(new RectF(0, 0, len + ro * 2, depth + ro * 2), ro, ro, Path.Direction.CW);
        Path cut = new Path();
        cut.addRoundRect(new RectF(side, bar, len + ri2 * 4, depth + ri2 * 4), ri2, ri2, Path.Direction.CW);
        path.op(cut, Path.Op.DIFFERENCE);
        Path clip = new Path();
        clip.addRect(0, 0, len, depth, Path.Direction.CW);
        path.op(clip, Path.Op.INTERSECT);
        cv.save();
        if (flip) { cv.translate(0, y); cv.scale(1, -1); } else cv.translate(0, y);
        p.setColor(withAlpha(color, a));
        cv.drawPath(path, p);
        cv.restore();
    }

    /** Scales a colour's own alpha by a (0..255). */
    private static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | (Math.round(((color >>> 24) / 255f) * a) << 24);
    }

    /** Removes the overlay (once) and runs the completion callback. */
    private void finish() {
        if (finished) return;
        finished = true;
        post(new Runnable() {
            @Override public void run() {
                ViewGroup parent = (ViewGroup) getParent();
                if (parent != null) parent.removeView(BootSequence.this);
                if (onDone != null) onDone.run();
            }
        });
    }
}
