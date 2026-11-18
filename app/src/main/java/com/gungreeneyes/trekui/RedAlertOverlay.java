package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

/**
 * The RED ALERT frame drawn over the home screen of every interface while {@link RedAlert} is on:
 * <ul>
 *   <li>Command console: a red console frame (top and bottom bars joined by a left sidebar through
 *       rounded elbows) with the RED ALERT caption in a notch of the top bar;</li>
 *   <li>Imperial: angular blades in the corners and chamfered bars in Imperial red;</li>
 *   <li>Collective: red corner brackets and a threat grid along the edges.</li>
 * </ul>
 * Everything pulses (unless animations are off) and the whole screen gets a faint red wash. The view
 * never blocks the phone: it is not clickable, so touches fall through to the home screen below; only
 * its MUTE and STAND DOWN buttons take taps.
 *
 * <p>MainActivity adds one on top of each rebuilt view tree ({@link #attach}); it listens to
 * {@link RedAlert} while attached and hides itself when the alert stands down.
 */
final class RedAlertOverlay extends FrameLayout implements RedAlert.Listener {

    /** Frame redraw interval while pulsing (~30 fps is plenty for a slow pulse). */
    private static final long FRAME_MS = 33L;
    /** How often the overlay re-checks timeout and sound rules. */
    private static final long CHECK_MS = 1000L;
    /** Strength of the full-screen red wash at the pulse peak (0..1). */
    private static final float WASH_ALPHA = 0.10f;

    /** Alarm colours per interface family. */
    private static final int COMMAND_RED = 0xFFE03030;
    private static final int COLLECTIVE_RED = 0xFFFF3B30;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final TextView mute;
    private final TextView standDown;
    private final LinearLayout controls;
    private int insetTop, insetBottom, insetLeft, insetRight;
    private long lastCheck;
    private boolean ticking;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            if (getVisibility() != VISIBLE || !isAttachedToWindow()) return;
            long now = System.currentTimeMillis();
            if (now - lastCheck >= CHECK_MS) {
                lastCheck = now;
                RedAlert.check(getContext()); // may stand down → onRedAlertChanged hides us
                if (!RedAlert.active(getContext())) return;
            }
            invalidate();
            schedule(Ui.animate(getContext()) ? FRAME_MS : CHECK_MS);
        }
    };

    RedAlertOverlay(Context c) {
        super(c);
        setWillNotDraw(false);
        setClickable(false);
        setFocusable(false);
        setTag("red_alert_overlay");
        setId(R.id.red_alert_overlay);
        text.setTypeface(Ui.font(c, true));
        text.setTextAlign(Paint.Align.LEFT);

        int color = alarmColor();
        controls = new LinearLayout(c);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        mute = Ui.button(c, c.getString(R.string.red_alert_mute), Ui.darken(color, 0.25f), Ui.PILL,
                v -> RedAlert.toggleMute(getContext()));
        mute.setId(R.id.red_alert_mute);
        mute.setTag("red_alert_mute");
        standDown = Ui.button(c, c.getString(R.string.red_alert_stand_down), color, Ui.PILL,
                v -> RedAlert.standDown(getContext()));
        standDown.setId(R.id.red_alert_stand_down);
        standDown.setTag("red_alert_stand_down");
        int gap = Ui.dp(c, 8);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(gap, 0, gap, 0);
        controls.addView(mute, lp);
        controls.addView(standDown, new LinearLayout.LayoutParams(lp));
        addView(controls, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = WindowInsetsCompat.toWindowInsetsCompat(insets, view)
                    .getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            insetTop = bars.top;
            insetBottom = bars.bottom;
            insetLeft = bars.left;
            insetRight = bars.right;
            positionControls();
            return insets;
        });
        update();
    }

    /** Adds a RED ALERT overlay on top of {@code root} (the home screen's GestureRoot). */
    static RedAlertOverlay attach(Context c, ViewGroup root) {
        RedAlertOverlay overlay = new RedAlertOverlay(c);
        root.addView(overlay, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return overlay;
    }

    /** Alarm colour of the current interface. */
    private static int alarmColor() {
        if (Skin.imperial()) return ImperialDraw.RED;
        if (Skin.collective()) return COLLECTIVE_RED;
        return COMMAND_RED;
    }

    /** Keeps the buttons clear of the navigation bar and the thick bottom bar. */
    private void positionControls() {
        LayoutParams lp = (LayoutParams) controls.getLayoutParams();
        lp.bottomMargin = insetBottom + barThickness() + Ui.dp(getContext(), 12);
        lp.leftMargin = insetLeft;
        lp.rightMargin = insetRight;
        controls.setLayoutParams(lp);
    }

    private int barThickness() {
        return Ui.dp(getContext(), 18);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        RedAlert.addListener(this);
        RedAlert.check(getContext());
        update();
        requestApplyInsets();
    }

    @Override
    protected void onDetachedFromWindow() {
        RedAlert.removeListener(this);
        removeCallbacks(frame);
        ticking = false;
        super.onDetachedFromWindow();
    }

    @Override
    public void onRedAlertChanged() {
        post(this::update);
    }

    /** Shows or hides the frame and relabels MUTE / UNMUTE. */
    private void update() {
        boolean on = RedAlert.active(getContext());
        setVisibility(on ? VISIBLE : GONE);
        mute.setText(getContext().getString(RedAlert.muted(getContext()) ? R.string.red_alert_unmute : R.string.red_alert_mute));
        positionControls();
        if (on) {
            announceForAccessibility(getContext().getString(R.string.red_alert));
            schedule(0);
        }
    }

    private void schedule(long delay) {
        if (ticking) return;
        ticking = true;
        postDelayed(frame, delay);
    }

    // ---- drawing ----------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RedAlertLogic.State s = RedAlert.state(getContext());
        long now = System.currentTimeMillis();
        if (!s.active(now)) return;
        float pulse = RedAlertLogic.pulse(s.since, now, Ui.animate(getContext()));
        int w = getWidth(), h = getHeight();
        int color = alarmColor();

        canvas.drawColor(Ui.alpha(color, WASH_ALPHA * pulse));
        if (Skin.imperial()) drawImperial(canvas, w, h, color, pulse);
        else if (Skin.collective()) drawCollective(canvas, w, h, color, pulse);
        else drawConsole(canvas, w, h, color, pulse);
        drawCaption(canvas, w, color, pulse, s.remainingMs(now));
    }

    /** Command: an console frame (bars + left sidebar with rounded elbows) in red. */
    private void drawConsole(Canvas cv, int w, int h, int color, float pulse) {
        float bar = barThickness();
        float side = Ui.dp(getContext(), 36);
        float outer = Ui.dp(getContext(), 44);
        float inner = Ui.dp(getContext(), 18);
        float top = insetTop, bottom = h - insetBottom, left = insetLeft, right = w - insetRight;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.alpha(color, pulse));
        // top elbow + bar
        path.reset();
        path.moveTo(right, top);
        path.lineTo(left + outer, top);
        path.quadTo(left, top, left, top + outer);
        path.lineTo(left, bottom - outer);
        path.quadTo(left, bottom, left + outer, bottom);
        path.lineTo(right, bottom);
        path.lineTo(right, bottom - bar);
        path.lineTo(left + side + inner, bottom - bar);
        path.quadTo(left + side, bottom - bar, left + side, bottom - bar - inner);
        path.lineTo(left + side, top + bar + inner);
        path.quadTo(left + side, top + bar, left + side + inner, top + bar);
        path.lineTo(right, top + bar);
        path.close();
        cv.drawPath(path, paint);
        // segment gaps in the sidebar, console style
        paint.setColor(Color.BLACK);
        float gap = Ui.dp(getContext(), 4);
        for (int i = 1; i <= 2; i++) {
            float y = top + (bottom - top) * i / 3f;
            cv.drawRect(left, y - gap / 2, left + side, y + gap / 2, paint);
        }
    }

    /** Imperial: chamfered bars and angular corner blades. */
    private void drawImperial(Canvas cv, int w, int h, int color, float pulse) {
        float bar = barThickness();
        float blade = Ui.dp(getContext(), 56);
        float cut = Ui.dp(getContext(), 14);
        float top = insetTop, bottom = h - insetBottom, left = insetLeft, right = w - insetRight;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Ui.alpha(color, pulse));
        for (int edge = 0; edge < 2; edge++) {
            float y0 = edge == 0 ? top : bottom - bar;
            path.reset();
            path.moveTo(left + blade + cut, y0);
            path.lineTo(right - blade - cut, y0);
            path.lineTo(right - blade, y0 + bar / 2);
            path.lineTo(right - blade - cut, y0 + bar);
            path.lineTo(left + blade + cut, y0 + bar);
            path.lineTo(left + blade, y0 + bar / 2);
            path.close();
            cv.drawPath(path, paint);
        }
        paint.setColor(Ui.alpha(ImperialDraw.ORANGE, pulse));
        for (int corner = 0; corner < 4; corner++) {
            float cx = corner % 2 == 0 ? left : right;
            float cy = corner < 2 ? top : bottom;
            float dx = corner % 2 == 0 ? 1 : -1;
            float dy = corner < 2 ? 1 : -1;
            path.reset();
            path.moveTo(cx, cy);
            path.lineTo(cx + dx * blade, cy);
            path.lineTo(cx + dx * bar, cy + dy * bar);
            path.lineTo(cx, cy + dy * blade * 1.6f);
            path.close();
            cv.drawPath(path, paint);
        }
    }

    /** Collective: corner brackets and a fine threat grid along the edges. */
    private void drawCollective(Canvas cv, int w, int h, int color, float pulse) {
        float stroke = Ui.dp(getContext(), 3);
        float arm = Ui.dp(getContext(), 64);
        float cell = Ui.dp(getContext(), 16);
        float top = insetTop, bottom = h - insetBottom, left = insetLeft, right = w - insetRight;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Ui.dp(getContext(), 1));
        paint.setColor(Ui.alpha(color, 0.45f * pulse));
        for (float x = left; x <= right; x += cell) {
            cv.drawLine(x, top, x, top + cell * 2, paint);
            cv.drawLine(x, bottom - cell * 2, x, bottom, paint);
        }
        for (int row = 0; row <= 2; row++) {
            cv.drawLine(left, top + row * cell, right, top + row * cell, paint);
            cv.drawLine(left, bottom - row * cell, right, bottom - row * cell, paint);
        }
        paint.setStrokeWidth(stroke);
        paint.setColor(Ui.alpha(color, pulse));
        float in = stroke / 2;
        rect.set(left + in, top + in, right - in, bottom - in);
        cv.drawLine(rect.left, rect.top, rect.left + arm, rect.top, paint);
        cv.drawLine(rect.left, rect.top, rect.left, rect.top + arm, paint);
        cv.drawLine(rect.right, rect.top, rect.right - arm, rect.top, paint);
        cv.drawLine(rect.right, rect.top, rect.right, rect.top + arm, paint);
        cv.drawLine(rect.left, rect.bottom, rect.left + arm, rect.bottom, paint);
        cv.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - arm, paint);
        cv.drawLine(rect.right, rect.bottom, rect.right - arm, rect.bottom, paint);
        cv.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - arm, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** "RED ALERT • 1:45" (Collective: "THREAT DETECTED") in a black notch under the top edge. */
    private void drawCaption(Canvas cv, int w, int color, float pulse, long remainingMs) {
        String caption = getContext().getString(Skin.collective() ? R.string.red_alert_collective : R.string.red_alert)
                + "  " + RedAlertLogic.countdown(remainingMs);
        text.setTextSize(Ui.px(getContext(), 18));
        float pad = Ui.dp(getContext(), 8);
        float tw = text.measureText(caption);
        float x = Skin.command() ? insetLeft + Ui.dp(getContext(), 36) + Ui.dp(getContext(), 30) : (w - tw) / 2f;
        Paint.FontMetrics fm = text.getFontMetrics();
        float th = fm.descent - fm.ascent;
        // Command: a notch cut into the top bar; others: just below their top edge
        float boxTop = Skin.command() ? insetTop : insetTop + barThickness() + Ui.dp(getContext(), 6);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.BLACK);
        rect.set(x - pad, boxTop, x + tw + pad, boxTop + Math.max(th, barThickness()));
        cv.drawRect(rect, paint);
        text.setColor(Ui.alpha(Skin.command() ? color : Color.WHITE, Math.max(0.6f, pulse)));
        cv.drawText(caption, x, rect.top - fm.ascent + (rect.height() - th) / 2f, text);
    }
}
