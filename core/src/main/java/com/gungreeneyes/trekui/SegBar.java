package com.gungreeneyes.trekui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Segmented console level bar (battery, storage, memory). Segments fill left to right; the end segments
 * are pill-rounded. Optional pulse on the leading segment (e.g. while charging), only when animations
 * are allowed. Sizes are relative to the view, so it scales with whatever space it is given.
 */
class SegBar extends View {
    private float fraction;
    private int color, emptyColor;
    private final int segments;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private ValueAnimator pulse;
    private float pulseA = 1f;

    SegBar(Context c, int segments, int color, int emptyColor) {
        super(c);
        this.segments = segments;
        this.color = color;
        this.emptyColor = emptyColor;
    }

    /** Sets the fill fraction (clamped to 0..1) and its colour. */
    void set(float f, int color) {
        fraction = Math.max(0f, Math.min(1f, f));
        this.color = color;
        invalidate();
    }

    /** Starts or stops pulsing the leading segment. */
    void setPulse(boolean on) {
        if (on && pulse == null && Ui.animate(getContext())) {
            pulse = ValueAnimator.ofFloat(0.15f, 1f);
            pulse.setDuration(700);
            pulse.setRepeatMode(ValueAnimator.REVERSE);
            pulse.setRepeatCount(ValueAnimator.INFINITE);
            pulse.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    pulseA = (Float) a.getAnimatedValue();
                    invalidate();
                }
            });
            pulse.start();
        } else if (!on && pulse != null) {
            pulse.cancel();
            pulse = null;
            pulseA = 1f;
            invalidate();
        }
    }

    /** Segments lit for {@code fraction}: rounded, but at least one for any non-zero fraction. */
    static int filledSegments(float fraction, int segments) {
        int filled = Math.round(fraction * segments);
        if (fraction > 0 && filled == 0) filled = 1;
        return filled;
    }

    @Override
    protected void onDetachedFromWindow() {
        setPulse(false);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float gap = Math.max(2f, h * 0.22f);
        float sw = (w - gap * (segments - 1)) / segments;
        int filled = filledSegments(fraction, segments);
        for (int i = 0; i < segments; i++) {
            float x = i * (sw + gap);
            r.set(x, 0, x + sw, h);
            boolean on = i < filled;
            boolean lead = pulse != null && i == Math.min(filled, segments - 1);
            int col = on ? color : emptyColor;
            if (lead) col = Ui.alpha(color, pulseA);
            p.setColor(col);
            float rad = (i == 0 || i == segments - 1) ? h / 2f : h * 0.12f;
            cv.drawRoundRect(r, rad, rad, p);
        }
    }
}
