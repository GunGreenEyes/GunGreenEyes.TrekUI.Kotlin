package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.util.Locale;

/**
 * Start-up for the pipe interfaces (about four seconds, tap to skip): a match is struck, the bowl
 * takes the light and glows, smoke curls up out of it, and the interface's name settles in, then the
 * home screen frames reveal. Tobacconist: brass engraving over walnut. Ember Lounge: a sunburst
 * opens behind the pipe. Blend Journal: the title is stamped in red over leather.
 *
 * <p>MainActivity adds it full-screen over the home layout and passes a callback that reveals the
 * frames once the view has faded out and removed itself. Everything is drawn in {@link #onDraw}
 * from the elapsed time; there is no animator.
 *
 * <p>The composition (pipe, match, smoke) lives in a portrait-shaped column centred on the screen
 * ({@link #stageWidth}), so on landscape phones, tablets and unfolded foldables the pipe stays
 * centred over the title instead of drifting towards the left edge.
 */
final class PipeBoot extends View {
    // timeline, in milliseconds since attach
    /** The match flares. */
    private static final long STRIKE = 500;
    /** The bowl has taken the light. */
    private static final long LIGHT = 1300;
    /** Smoke starts to rise. */
    private static final long SMOKE = 1100;
    /** The interface name starts to settle in. */
    private static final long TITLE = 1900;
    /** Everything is on screen; fade-out starts (a tap jumps here). */
    private static final long HOLD = 3500;
    /** Fully faded; the view removes itself. */
    private static final long END = 4200;

    /** Pipe size as a share of the composition's shorter side. */
    private static final float PIPE_SCALE = 0.26f;
    /** Composition column aspect (width / height) used when the screen is wider than that. */
    private static final float STAGE_ASPECT = 0.8f;
    private static final int SUNBURST_RAYS = 36;
    private static final int SMOKE_CURLS = 3;

    private static final int GLOW_HOT = 0xFFFF7A2A, GLOW_COOL = 0xFFB3262A, COAL = 0xFFFF6A20;
    private static final int MATCH_STICK = 0xFFD9B77E, MATCH_HEAD = 0xFF7A1E14;
    private static final int FLAME_CORE = 0xFFFFF2B0, FLAME_EDGE = 0xFFFF8A20, SMOKE_COLOR = 0xFFE8DCC8;
    private static final int BODY_TOBACCONIST = 0xFF4A2814, BODY_LOUNGE = 0xFF120B09, BODY_JOURNAL = 0xFF3A2010;
    private static final int STAMP_RED = 0xFFC0392B;

    private final Palette pal;
    private final String header;
    private final Runnable onDone;
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private final Drawable backdrop;
    /** Material family ({@link Skin#mat()}): TOBACCONIST, LOUNGE or JOURNAL. */
    private final int theme;
    private long start;
    private boolean done;

    /**
     * @param header the ship/header line shown under the title
     * @param onDone run on the UI thread after the view has removed itself
     */
    PipeBoot(Context c, Palette pal, String header, Runnable onDone) {
        super(c);
        this.pal = pal;
        this.header = header.toUpperCase(Locale.getDefault());
        this.onDone = onDone;
        this.theme = Skin.mat();
        this.backdrop = Skin.backdrop(c);
        titlePaint.setTypeface(Ui.font(c, true));
        titlePaint.setTextAlign(Paint.Align.CENTER);
        subPaint.setTypeface(Ui.font(c, false));
        subPaint.setTextAlign(Paint.Align.CENTER);
        setClickable(true);
        setContentDescription("Start-up. Tap to skip.");
    }

    /** Zoomed dp to pixels (see {@link Ui#density}). */
    private float dp(float v) { return v * Ui.density(getContext()); }

    /** Clamps to 0..1. */
    static float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    /** Ease-out (quadratic) progress of {@code t} through the window {@code [from, to]}, 0..1. */
    static float ease(long t, long from, long to) {
        float k = clamp01((t - from) / (float) (to - from));
        return 1 - (1 - k) * (1 - k);
    }

    /** Width of the centred composition column: the full width in portrait, narrower on wide screens. */
    static float stageWidth(float w, float h) {
        return Math.min(w, h * STAGE_ASPECT);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start = SystemClock.uptimeMillis();
        Sfx.play(getContext(), Sfx.LAUNCH);
    }

    /** A tap skips straight to the fade-out. */
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && !done) {
            start = Math.min(start, SystemClock.uptimeMillis() - HOLD);
            invalidate();
        }
        return true;
    }

    /** A billiard pipe, bowl centred at (bowlX, bowlY), stem running right, sized by {@code size}. */
    private Path pipe(float bowlX, float bowlY, float size) {
        path.reset();
        float bowlW = 0.62f * size, bowlH = 0.78f * size;
        float l = bowlX - bowlW / 2f, r = bowlX + bowlW / 2f, t = bowlY - bowlH / 2f, b = bowlY + bowlH / 2f;
        path.moveTo(l, t);
        path.lineTo(r, t);
        path.cubicTo(r + 0.02f * size, t + 0.35f * bowlH, r + 0.04f * size, b - 0.25f * bowlH, r - 0.12f * bowlW, b);
        path.lineTo(l + 0.18f * bowlW, b);
        path.cubicTo(l - 0.06f * size, b - 0.2f * bowlH, l - 0.02f * size, t + 0.3f * bowlH, l, t);
        path.close();
        // shank and stem running right
        float shankY = bowlY + 0.18f * bowlH, shankH = 0.2f * size;
        path.moveTo(r - 0.05f * size, shankY - shankH / 2f);
        path.lineTo(r + 0.55f * size, shankY - shankH * 0.42f);
        path.lineTo(r + 0.55f * size, shankY + shankH * 0.42f);
        path.lineTo(r - 0.03f * size, shankY + shankH / 2f);
        path.close();
        path.moveTo(r + 0.57f * size, shankY - shankH * 0.36f);
        path.lineTo(r + 1.25f * size, shankY - shankH * 0.16f);
        path.quadTo(r + 1.32f * size, shankY, r + 1.25f * size, shankY + shankH * 0.14f);
        path.lineTo(r + 0.57f * size, shankY + shankH * 0.36f);
        path.close();
        return path;
    }

    @Override protected void onDraw(Canvas cv) {
        long t = SystemClock.uptimeMillis() - start;
        int w = getWidth(), h = getHeight();
        float d = Ui.density(getContext());
        float fade = 1f - ease(t, HOLD, END);
        cv.saveLayerAlpha(0, 0, w, h, Math.round(255 * fade));
        backdrop.setBounds(0, 0, w, h);
        backdrop.draw(cv);
        Paint p = shapePaint;
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.alpha(0xFF000000, 0.45f));
        cv.drawRect(0, 0, w, h, p);

        // composition column, centred
        float stageW = stageWidth(w, h);
        float stageX = (w - stageW) / 2f;
        float size = Math.min(stageW, h) * PIPE_SCALE;
        float bowlX = stageX + stageW * 0.40f, bowlY = h * 0.40f;
        float rimY = bowlY - 0.39f * size;
        float lit = ease(t, STRIKE + 300, LIGHT);

        // Ember Lounge: a sunburst opens behind the pipe
        if (theme == Skin.LOUNGE) {
            float k = ease(t, 0, 1400);
            p.setStyle(Paint.Style.STROKE);
            float reach = Math.max(w, h) * 0.7f * k;
            for (int i = 0; i < SUNBURST_RAYS; i++) {
                double a = Math.PI * 2 * i / SUNBURST_RAYS;
                p.setStrokeWidth(Math.max(1, (i % 2 == 0 ? 1.2f : 0.6f) * d));
                p.setColor(Ui.alpha(pal.c(0), (i % 2 == 0 ? 0.32f : 0.16f) * k));
                cv.drawLine(bowlX, bowlY, bowlX + (float) Math.cos(a) * reach, bowlY + (float) Math.sin(a) * reach, p);
            }
            p.setStyle(Paint.Style.FILL);
        }

        // ember glow in the bowl
        if (lit > 0) {
            float flicker = 0.85f + 0.15f * (float) Math.sin(t / 70.0);
            p.setShader(new RadialGradient(bowlX, rimY, size * 0.9f,
                    new int[]{Ui.alpha(GLOW_HOT, 0.55f * lit * flicker), Ui.alpha(GLOW_COOL, 0.18f * lit), 0},
                    new float[]{0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(bowlX, rimY, size * 0.9f, p);
            p.setShader(null);
        }

        // the pipe
        float show = ease(t, 0, 700);
        Path pipePath = pipe(bowlX, bowlY, size);
        int body = theme == Skin.TOBACCONIST ? BODY_TOBACCONIST : theme == Skin.LOUNGE ? BODY_LOUNGE : BODY_JOURNAL;
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.alpha(body, show));
        cv.drawPath(pipePath, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2 * d);
        p.setColor(Ui.alpha(theme == Skin.JOURNAL ? PipeDraw.STITCH : pal.c(0), show));
        cv.drawPath(pipePath, p);
        // the coal at the rim
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.alpha(COAL, lit * (0.7f + 0.3f * (float) Math.sin(t / 55.0))));
        oval.set(bowlX - 0.27f * size, rimY - 0.05f * size, bowlX + 0.27f * size, rimY + 0.05f * size);
        cv.drawOval(oval, p);

        // the match: struck at the lower left, it travels to the bowl and goes out
        if (t > STRIKE * 0.3f && t < LIGHT + 500) {
            float k = ease(t, STRIKE, LIGHT - 200);
            float startX = stageX + stageW * 0.12f, startY = h * 0.62f;
            float mx = startX + (bowlX - startX) * k, my = startY + (rimY - size * 0.18f - startY) * k;
            float out = 1f - ease(t, LIGHT, LIGHT + 500);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3 * d);
            p.setColor(Ui.alpha(MATCH_STICK, out));
            cv.drawLine(mx, my, mx - 0.5f * size, my + 0.42f * size, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Ui.alpha(MATCH_HEAD, out));
            cv.drawCircle(mx, my, 4 * d, p);
            float flame = (t > STRIKE ? 1f : 0f) * out * (0.85f + 0.15f * (float) Math.sin(t / 40.0));
            p.setShader(new RadialGradient(mx, my - 9 * d, 16 * d,
                    new int[]{Ui.alpha(FLAME_CORE, flame), Ui.alpha(FLAME_EDGE, 0.8f * flame), 0}, null, Shader.TileMode.CLAMP));
            oval.set(mx - 8 * d, my - 26 * d, mx + 8 * d, my + 2 * d);
            cv.drawOval(oval, p);
            p.setShader(null);
        }

        // smoke: three curls rising out of the bowl
        if (t > SMOKE) {
            smokePaint.setStyle(Paint.Style.STROKE);
            smokePaint.setStrokeCap(Paint.Cap.ROUND);
            for (int k = 0; k < SMOKE_CURLS; k++) {
                float rise = ease(t, SMOKE + k * 220, SMOKE + 1500 + k * 220);
                if (rise <= 0) continue;
                float sway = (float) Math.sin(t / 600.0 + k) * size * 0.12f;
                float x0 = bowlX + (k - 1) * size * 0.08f, y0 = rimY - 2 * d;
                float y1 = y0 - h * 0.30f * rise;
                path.reset();
                path.moveTo(x0, y0);
                path.cubicTo(x0 + size * 0.35f + sway, y0 - h * 0.08f * rise, x0 - size * 0.35f - sway, y0 - h * 0.18f * rise,
                        x0 + sway * 1.5f + (k - 1) * size * 0.2f, y1);
                smokePaint.setStrokeWidth((5 - k) * d);
                smokePaint.setColor(Ui.alpha(SMOKE_COLOR, 0.28f * (1 - 0.25f * k) * Math.min(1f, rise * 1.5f)));
                cv.drawPath(path, smokePaint);
            }
        }

        drawTitle(cv, t, w, h);
        cv.restore();
        if (t >= END) {
            finish();
        } else {
            postInvalidateOnAnimation();
        }
    }

    /** The interface name (stamped in on Blend Journal), a gilt rule, the library line and the header line. */
    private void drawTitle(Canvas cv, long t, int w, int h) {
        float in = ease(t, TITLE, TITLE + 600);
        if (in <= 0) return;
        float ty = h * 0.66f;
        float maxW = w * 0.9f;
        String title = Skin.title();
        titlePaint.setTextSize(Ui.px(getContext(), 36));
        float stamp = theme == Skin.JOURNAL ? 1f + 0.35f * (1f - in) : 1f;
        cv.save();
        cv.scale(stamp, stamp, w / 2f, ty);
        titlePaint.setColor(Ui.alpha(theme == Skin.JOURNAL ? STAMP_RED : pal.c(0), in));
        titlePaint.setLetterSpacing(0.06f);
        if (titlePaint.measureText(title) > maxW) titlePaint.setTextSize(titlePaint.getTextSize() * maxW / titlePaint.measureText(title));
        cv.drawText(title, w / 2f, ty, titlePaint);
        cv.restore();
        float ruleL = w * 0.2f, ruleR = w * 0.8f, ruleT = Math.round(ty + dp(14)), ruleB = Math.round(ty + dp(16));
        PipeDraw.rule(cv, ruleL, ruleT, ruleL + (ruleR - ruleL) * in, ruleB, pal.c(2), in);
        subPaint.setTextSize(Ui.px(getContext(), 14));
        subPaint.setLetterSpacing(0.1f);
        subPaint.setColor(Ui.alpha(pal.textLo, ease(t, TITLE + 300, TITLE + 900)));
        cv.drawText(Skin.library(), w / 2f, ty + dp(40), subPaint);
        subPaint.setColor(Ui.alpha(pal.c(1), ease(t, TITLE + 600, TITLE + 1200)));
        cv.drawText(header, w / 2f, ty + dp(64), subPaint);
    }

    /** Removes the view (next frame) and runs the completion callback, once. */
    private void finish() {
        if (done) return;
        done = true;
        post(new Runnable() {
            @Override public void run() {
                ViewGroup g = (ViewGroup) getParent();
                if (g != null) g.removeView(PipeBoot.this);
                if (onDone != null) onDone.run();
            }
        });
    }
}
