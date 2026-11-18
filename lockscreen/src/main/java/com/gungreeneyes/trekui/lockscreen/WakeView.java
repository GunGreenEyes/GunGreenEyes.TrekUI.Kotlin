package com.gungreeneyes.trekui.lockscreen;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.animation.DecelerateInterpolator;

import com.gungreeneyes.trekui.LockLook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The wake screen, drawn entirely on a Canvas in the active interface: a console frame (two elbows and a
 * sidebar in the interface's geometry: rounded rails, chamfered plates, a square lattice or paper cards),
 * the big clock with date, decimal date, next alarm and battery, media controls, the redacted notification
 * list, and the CAMERA / TORCH / UNLOCK row.
 *
 * <p>Layout: one column in portrait (clock, media, list, actions); in landscape the clock, media and actions
 * take the left column and the list the right. Sizes come from the interface's dp/sp scale, so phones and
 * tablets get the same proportions. The safe area (system bars, cutouts) is set by {@link #setSafeInsets}.
 *
 * <p>Input: a swipe up anywhere (or a tap on the UNLOCK bar) asks to unlock; every control is a hit
 * {@link Region}. The same regions are exposed to TalkBack as virtual views through an
 * {@link AccessibilityNodeProvider}, so the Canvas-drawn controls are announced and clickable. The view
 * never decides anything about security: it only reports gestures to {@link Actions}.
 */
public final class WakeView extends View {
    /** What the wake screen's controls do (implemented by {@link WakeActivity}). */
    public interface Actions {
        void unlock();
        void camera();
        void torch();
        /** -1 previous, 0 play/pause, 1 next. */
        void media(int command);
        void openItem(LockItem item);
        void purchase();
    }

    /** Everything the view draws; filled by {@link WakeActivity}. */
    public static final class Model {
        public String time = "";
        public String amPm = "";
        public String date = "";
        public String decimalDate = "";
        public String alarm = "";
        public int batteryPercent = -1;
        public boolean charging;
        public boolean hasMedia;
        public String mediaTitle = "";
        public String mediaArtist = "";
        public boolean mediaPlaying;
        public boolean torchAvailable;
        public boolean torchOn;
        public boolean trialChip;
        public boolean listenerOn = true;
        public List<LockItem> items = Collections.emptyList();
    }

    /** A tappable area with its spoken label. */
    static final class Region {
        final int id;
        final RectF rect = new RectF();
        final String label;
        final Runnable action;

        Region(int id, RectF r, String label, Runnable action) {
            this.id = id;
            this.rect.set(r);
            this.label = label;
            this.action = action;
        }
    }

    private final Actions actions;
    private Model model = new Model();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF tmp = new RectF();
    private final Rect safe = new Rect();
    private final List<Region> regions = new ArrayList<>();
    private Typeface heavy;
    private Typeface regular;
    private int geometry;

    private float downX;
    private float downY;
    private Region downRegion;
    private boolean dragging;
    private float dragOffset;
    private ValueAnimator settle;
    private final int touchSlop;

    public WakeView(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        stroke.setStyle(Paint.Style.STROKE);
        setFocusable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(context.getString(R.string.wake_description));
        setTag("wake_view");
        reloadLook();
    }

    /** Re-reads the interface (fonts, geometry, colours) and redraws. */
    public void reloadLook() {
        Context c = getContext();
        LockLook.load(c);
        heavy = LockLook.font(c, true);
        regular = LockLook.font(c, false);
        geometry = LockLook.geometry();
        invalidate();
    }

    /** Replaces the model and redraws. */
    public void setModel(Model m) {
        model = m == null ? new Model() : m;
        invalidate();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
    }

    /** The system bar / cutout insets to keep the content clear of. */
    public void setSafeInsets(int left, int top, int right, int bottom) {
        safe.set(left, top, right, bottom);
        invalidate();
    }

    private float dp(float v) {
        return LockLook.dp(getContext(), v);
    }

    private float sp(float v) {
        return LockLook.px(getContext(), v);
    }

    private String s(int id) {
        return getContext().getString(id);
    }

    // ---- drawing --------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas canvas) {
        LockLook.load(getContext());
        regions.clear();
        canvas.drawColor(LockLook.bg());
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;

        float margin = dp(10);
        RectF area = new RectF(safe.left + margin, safe.top + margin, w - safe.right - margin, h - safe.bottom - margin);
        boolean landscape = area.width() > area.height() * 1.15f;
        float side = Math.min(dp(landscape ? 56 : 64), area.width() * 0.16f);
        float bar = dp(18);
        float gap = dp(6);

        canvas.save();
        canvas.translate(0, dragOffset);

        // frame: top elbow down to 40% of the height, bottom elbow from just below to the bottom
        float split = area.top + area.height() * (landscape ? 0.48f : 0.40f);
        drawElbow(canvas, area.left, area.top, area.right, split - gap / 2f, side, bar, LockLook.accent(0), true);
        drawElbow(canvas, area.left, split + gap / 2f, area.right, area.bottom, side, bar, LockLook.accent(2), false);
        sidebarLabel(canvas, model.batteryPercent >= 0 ? String.valueOf(model.batteryPercent) : "--",
                area.left, split - gap / 2f, side);
        sidebarLabel(canvas, String.format(java.util.Locale.ROOT, "%02d", Math.min(99, model.items.size())),
                area.left, area.bottom, side);

        RectF content = new RectF(area.left + side + dp(12), area.top + bar + dp(10), area.right,
                area.bottom - bar - dp(10));
        float actionsH = Math.max(dp(52), LockLook.MIN_TOUCH_DP * getResources().getDisplayMetrics().density);

        if (landscape) {
            float colW = content.width() * 0.46f;
            RectF left = new RectF(content.left, content.top, content.left + colW, content.bottom - actionsH - gap);
            RectF right = new RectF(left.right + dp(14), content.top, content.right, content.bottom);
            float y = drawClock(canvas, left);
            if (model.hasMedia) drawMedia(canvas, new RectF(left.left, y + gap, left.right, Math.min(left.bottom, y + gap + dp(64))));
            drawActions(canvas, new RectF(content.left, content.bottom - actionsH, left.right, content.bottom));
            drawList(canvas, right);
        } else {
            float y = drawClock(canvas, content);
            if (model.hasMedia) {
                drawMedia(canvas, new RectF(content.left, y + gap, content.right, y + gap + dp(64)));
                y += gap + dp(64);
            }
            RectF actionsRect = new RectF(content.left, content.bottom - actionsH, content.right, content.bottom);
            drawList(canvas, new RectF(content.left, y + dp(12), content.right, actionsRect.top - gap * 2));
            drawActions(canvas, actionsRect);
        }

        if (model.trialChip) drawTrialChip(canvas, area, bar);
        canvas.restore();
    }

    /**
     * One frame elbow: a sidebar of width {@code side} joined to a bar of height {@code bar} by a curve,
     * chamfer or square corner. {@code top} true draws the corner at the top-left, false mirrors it to the
     * bottom-left.
     */
    private void drawElbow(Canvas c, float left, float top, float right, float bottom, float side, float bar,
                           int color, boolean topCorner) {
        float end = dp(34);
        float gap = dp(6);
        float elbowRight = right - end - gap;
        c.save();
        if (!topCorner) c.scale(1f, -1f, 0f, (top + bottom) / 2f);
        float outer = Math.min(side, (bottom - top) / 2f);
        float inner = Math.min(bar, side / 2f);
        path.reset();
        path.moveTo(left, bottom);
        if (geometry == LockLook.GEOMETRY_CHAMFER) {
            path.lineTo(left, top + outer * 0.6f);
            path.lineTo(left + outer * 0.6f, top);
            path.lineTo(elbowRight, top);
            path.lineTo(elbowRight, top + bar);
            path.lineTo(left + side + inner, top + bar);
            path.lineTo(left + side, top + bar + inner);
        } else if (geometry == LockLook.GEOMETRY_SQUARE) {
            path.lineTo(left, top);
            path.lineTo(elbowRight, top);
            path.lineTo(elbowRight, top + bar);
            path.lineTo(left + side, top + bar);
        } else {
            float r = outer;
            path.lineTo(left, top + r);
            path.arcTo(new RectF(left, top, left + 2 * r, top + 2 * r), 180f, 90f, false);
            path.lineTo(elbowRight, top);
            path.lineTo(elbowRight, top + bar);
            path.lineTo(left + side + inner, top + bar);
            path.arcTo(new RectF(left + side, top + bar, left + side + 2 * inner, top + bar + 2 * inner), 270f, -90f, false);
        }
        path.lineTo(left + side, bottom);
        path.close();
        paintShape(c, path, color);

        // the bar's end cap
        tmp.set(right - end, top, right, top + bar);
        path.reset();
        if (geometry == LockLook.GEOMETRY_ROUND) {
            path.addRoundRect(tmp, new float[]{0, 0, bar / 2, bar / 2, bar / 2, bar / 2, 0, 0}, Path.Direction.CW);
        } else if (geometry == LockLook.GEOMETRY_CHAMFER) {
            path.moveTo(tmp.left, tmp.top);
            path.lineTo(tmp.right - bar * 0.6f, tmp.top);
            path.lineTo(tmp.right, tmp.bottom);
            path.lineTo(tmp.left, tmp.bottom);
            path.close();
        } else {
            path.addRect(tmp, Path.Direction.CW);
        }
        paintShape(c, path, LockLook.accent(topCorner ? 1 : 3));
        c.restore();
    }

    /** Fills (rails, plates) or strokes (lattice) a frame shape in the interface's manner. */
    private void paintShape(Canvas c, Path p, int color) {
        if (geometry == LockLook.GEOMETRY_SQUARE) {
            fill.setColor(LockLook.alpha(color, 0.18f));
            c.drawPath(p, fill);
            stroke.setColor(color);
            stroke.setStrokeWidth(dp(1.5f));
            c.drawPath(p, stroke);
        } else {
            fill.setColor(color);
            c.drawPath(p, fill);
        }
    }

    /** A small number at the bottom of a sidebar segment (battery percent, notification count). */
    private void sidebarLabel(Canvas c, String label, float left, float bottom, float side) {
        text.setTypeface(heavy);
        text.setTextSize(sp(15));
        text.setTextAlign(Paint.Align.RIGHT);
        text.setColor(geometry == LockLook.GEOMETRY_ROUND || geometry == LockLook.GEOMETRY_CHAMFER
                ? LockLook.ink() : LockLook.textHi());
        c.drawText(label, left + side - dp(6), bottom - dp(6), text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** Clock, date, decimal date, alarm and battery; returns the y below the block. */
    private float drawClock(Canvas c, RectF r) {
        float clockSize = Math.min(r.width() * 0.30f, Math.max(sp(56), r.height() * 0.30f));
        clockSize = Math.min(clockSize, sp(132));
        text.setTypeface(heavy);
        text.setLetterSpacing(LockLook.letterSpacing());
        text.setTextSize(clockSize);
        text.setColor(LockLook.accent(0));
        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = r.top - fm.ascent * 0.92f;
        c.drawText(model.time, r.left, baseline, text);
        if (!model.amPm.isEmpty()) {
            float tw = text.measureText(model.time);
            text.setTextSize(clockSize * 0.28f);
            c.drawText(model.amPm, r.left + tw + dp(8), baseline, text);
        }
        float y = baseline + fm.descent * 0.6f;

        y = line(c, model.date, r, y, 22, LockLook.textHi(), true);
        y = line(c, s(R.string.wake_decimal_label) + "  " + model.decimalDate, r, y, 15, LockLook.accent(1), false);
        String power = model.batteryPercent < 0 ? "" : s(R.string.wake_power) + " " + model.batteryPercent + "%"
                + (model.charging ? "  •  " + s(R.string.wake_charging) : "");
        String status = model.alarm.isEmpty() ? power : power + (power.isEmpty() ? "" : "   ") + s(R.string.wake_alarm) + " " + model.alarm;
        if (!status.isEmpty()) y = line(c, status, r, y, 15, LockLook.textLo(), false);
        return y;
    }

    /** One ellipsized text line under {@code y}; returns the new y. */
    private float line(Canvas c, String s, RectF r, float y, float sizeSp, int color, boolean bold) {
        text.setTypeface(bold ? heavy : regular);
        text.setTextSize(sp(sizeSp));
        text.setColor(color);
        Paint.FontMetrics fm = text.getFontMetrics();
        float base = y - fm.ascent + dp(2);
        CharSequence fit = TextUtils.ellipsize(s, text, r.width(), TextUtils.TruncateAt.END);
        c.drawText(fit, 0, fit.length(), r.left, base, text);
        return base + fm.descent;
    }

    /** The media card: title, artist and PREVIOUS / PLAY-PAUSE / NEXT keys. */
    private void drawMedia(Canvas c, RectF r) {
        cell(c, r, LockLook.surface(), LockLook.accent(4), true);
        float key = Math.min(r.height() - dp(12), dp(48));
        float keysW = key * 3 + dp(8) * 2;
        RectF textArea = new RectF(r.left + dp(12), r.top + dp(4), r.right - keysW - dp(16), r.bottom);
        float y = line(c, model.mediaTitle, textArea, r.top + dp(2), 16, LockLook.textHi(), true);
        line(c, model.mediaArtist, textArea, y, 13, LockLook.textLo(), false);
        float x = r.right - dp(8) - keysW;
        float top = r.centerY() - key / 2f;
        int[] cmds = {-1, 0, 1};
        String[] labels = {s(R.string.wake_media_previous),
                s(model.mediaPlaying ? R.string.wake_media_pause : R.string.wake_media_play), s(R.string.wake_media_next)};
        for (int i = 0; i < 3; i++) {
            RectF k = new RectF(x, top, x + key, top + key);
            int color = LockLook.accent(i == 1 ? 0 : 3);
            cell(c, k, color, color, false);
            glyph(c, k, cmds[i], LockLook.ink());
            final int cmd = cmds[i];
            regions.add(new Region(100 + i, k, labels[i], () -> actions.media(cmd)));
            x += key + dp(8);
        }
    }

    /** Previous / play-pause / next symbols drawn as shapes (no glyph font needed). */
    private void glyph(Canvas c, RectF k, int cmd, int color) {
        fill.setColor(color);
        float s = k.width() * 0.22f;
        float cx = k.centerX();
        float cy = k.centerY();
        path.reset();
        if (cmd == 0 && model.mediaPlaying) {
            c.drawRect(cx - s, cy - s, cx - s * 0.3f, cy + s, fill);
            c.drawRect(cx + s * 0.3f, cy - s, cx + s, cy + s, fill);
            return;
        }
        if (cmd == 0) {
            path.moveTo(cx - s * 0.7f, cy - s);
            path.lineTo(cx + s, cy);
            path.lineTo(cx - s * 0.7f, cy + s);
        } else {
            float dir = cmd;
            path.moveTo(cx - dir * s, cy - s);
            path.lineTo(cx + dir * s * 0.4f, cy);
            path.lineTo(cx - dir * s, cy + s);
            path.close();
            c.drawPath(path, fill);
            float bx = cx + dir * s * 0.55f;
            c.drawRect(Math.min(bx, bx + dir * s * 0.3f), cy - s, Math.max(bx, bx + dir * s * 0.3f), cy + s, fill);
            return;
        }
        path.close();
        c.drawPath(path, fill);
    }

    /** The notification list: one row per item until the space is used, then a "+N MORE" line. */
    private void drawList(Canvas c, RectF r) {
        if (r.height() < dp(40)) return;
        text.setTypeface(heavy);
        text.setTextSize(sp(13));
        text.setColor(LockLook.accent(1));
        Paint.FontMetrics hm = text.getFontMetrics();
        float y = r.top - hm.ascent;
        c.drawText(s(R.string.wake_notifications_header), r.left, y, text);
        stroke.setColor(LockLook.grid());
        stroke.setStrokeWidth(dp(1));
        y += hm.descent + dp(4);
        c.drawLine(r.left, y, r.right, y, stroke);
        y += dp(6);

        List<LockItem> items = model.items;
        if (items.isEmpty()) {
            line(c, s(model.listenerOn ? R.string.wake_no_notifications : R.string.wake_access_off),
                    r, y, 15, LockLook.textLo(), false);
            return;
        }
        float rowH = dp(58);
        int fit = (int) Math.max(0, Math.floor((r.bottom - y) / rowH));
        int shown = items.size() > fit ? Math.max(0, fit - 1) : items.size();
        for (int i = 0; i < shown; i++) {
            RectF row = new RectF(r.left, y, r.right, y + rowH - dp(6));
            drawItem(c, row, items.get(i), i);
            y += rowH;
        }
        if (shown < items.size() && fit > 0) {
            line(c, getContext().getString(R.string.wake_more, items.size() - shown), r, y, 14, LockLook.textLo(), true);
        }
    }

    private void drawItem(Canvas c, RectF row, LockItem item, int index) {
        int accent = item.color != 0
                ? (0xFF000000 | item.color) : LockLook.accent(index + 1);
        tmp.set(row.left, row.top, row.left + dp(10), row.bottom);
        cell(c, tmp, accent, accent, false);
        RectF t = new RectF(row.left + dp(18), row.top, row.right - dp(4), row.bottom);
        String when = android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date(item.postTime));
        text.setTypeface(regular);
        text.setTextSize(sp(12));
        float whenW = text.measureText(when);
        text.setColor(LockLook.textLo());
        c.drawText(when, t.right - whenW, t.top - text.getFontMetrics().ascent, text);
        RectF head = new RectF(t.left, t.top, t.right - whenW - dp(8), t.bottom);
        float y = line(c, LockLook.up(getContext(), item.appName), head, t.top - dp(2), 12, accent, true);
        String title = item.title;
        String body = item.text;
        if (item.redacted() && title.isEmpty() && body.isEmpty()) title = s(R.string.wake_contents_hidden);
        if (!title.isEmpty()) y = line(c, title, t, y, 15, LockLook.textHi(), false);
        if (!body.isEmpty()) line(c, body, t, y, 13, LockLook.textLo(), false);
        String spoken = item.appName + ". " + title + (body.isEmpty() ? "" : ". " + body);
        regions.add(new Region(200 + index, row, spoken, () -> actions.openItem(item)));
    }

    /** CAMERA, TORCH and the UNLOCK bar. */
    private void drawActions(Canvas c, RectF r) {
        float g = dp(8);
        float small = Math.min(r.width() * 0.24f, dp(104));
        RectF cam = new RectF(r.left, r.top, r.left + small, r.bottom);
        RectF torch = new RectF(cam.right + g, r.top, cam.right + g + small, r.bottom);
        RectF unlock = new RectF((model.torchAvailable ? torch.right : cam.right) + g, r.top, r.right, r.bottom);

        actionKey(c, cam, s(R.string.wake_camera), LockLook.accent(3), false);
        regions.add(new Region(1, cam, s(R.string.wake_camera_spoken), actions::camera));
        if (model.torchAvailable) {
            actionKey(c, torch, s(model.torchOn ? R.string.wake_torch_on : R.string.wake_torch), model.torchOn
                    ? LockLook.alert() : LockLook.accent(4), model.torchOn);
            regions.add(new Region(2, torch, s(model.torchOn ? R.string.wake_torch_off_spoken : R.string.wake_torch_on_spoken),
                    actions::torch));
        }
        actionKey(c, unlock, s(R.string.wake_unlock_hint), LockLook.accent(0), true);
        regions.add(new Region(3, unlock, s(R.string.wake_unlock_spoken), actions::unlock));
    }

    private void actionKey(Canvas c, RectF r, String label, int color, boolean wide) {
        cell(c, r, color, color, wide);
        text.setTypeface(heavy);
        text.setTextSize(sp(15));
        text.setColor(LockLook.ink());
        text.setTextAlign(Paint.Align.CENTER);
        CharSequence fit = TextUtils.ellipsize(LockLook.up(getContext(), label), text, r.width() - dp(12), TextUtils.TruncateAt.END);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(fit, 0, fit.length(), r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    /** The trial-ended chip: opens the purchase screen, but only after the device is unlocked. */
    private void drawTrialChip(Canvas c, RectF area, float bar) {
        text.setTypeface(heavy);
        text.setTextSize(sp(12));
        String label = s(R.string.wake_trial_chip);
        float w = text.measureText(label) + dp(20);
        float h = Math.max(dp(28), bar + dp(8));
        RectF chip = new RectF(area.right - w, area.top + bar + dp(8), area.right, area.top + bar + dp(8) + h);
        cell(c, chip, LockLook.alert(), LockLook.alert(), true);
        text.setColor(LockLook.ink());
        text.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(label, chip.centerX(), chip.centerY() - (fm.ascent + fm.descent) / 2f, text);
        text.setTextAlign(Paint.Align.LEFT);
        // a bigger touch target than the chip itself
        RectF hit = new RectF(chip);
        hit.inset(0, -dp(8));
        regions.add(new Region(4, hit, s(R.string.wake_trial_spoken), actions::purchase));
    }

    /** A cell in the interface's geometry: pill or rounded (rails), chamfered, square lattice or paper card. */
    private void cell(Canvas c, RectF r, int fillColor, int edge, boolean pill) {
        path.reset();
        switch (geometry) {
            case LockLook.GEOMETRY_CHAMFER: {
                float k = Math.min(r.height(), r.width()) * 0.28f;
                path.moveTo(r.left + k, r.top);
                path.lineTo(r.right, r.top);
                path.lineTo(r.right, r.bottom - k);
                path.lineTo(r.right - k, r.bottom);
                path.lineTo(r.left, r.bottom);
                path.lineTo(r.left, r.top + k);
                path.close();
                fill.setColor(fillColor);
                c.drawPath(path, fill);
                return;
            }
            case LockLook.GEOMETRY_SQUARE:
                fill.setColor(LockLook.alpha(fillColor, fillColor == LockLook.surface() ? 1f : 0.22f));
                c.drawRect(r, fill);
                stroke.setColor(edge);
                stroke.setStrokeWidth(dp(1.5f));
                c.drawRect(r, stroke);
                return;
            default: {
                float rad = pill ? r.height() / 2f : Math.min(dp(6), r.height() / 2f);
                fill.setColor(fillColor);
                c.drawRoundRect(r, rad, rad, fill);
            }
        }
    }

    // ---- touch --------------------------------------------------------------------------------

    private Region hit(float x, float y) {
        for (int i = regions.size() - 1; i >= 0; i--) {
            Region r = regions.get(i);
            if (r.rect.contains(x, y - dragOffset)) return r;
        }
        return null;
    }

    /** Distance a swipe up has to travel to unlock. */
    private float unlockDistance() {
        return Math.min(dp(140), getHeight() * 0.2f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (settle != null) settle.cancel();
                downX = e.getX();
                downY = e.getY();
                downRegion = hit(downX, downY);
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getY() - downY;
                if (!dragging && dy < -touchSlop && Math.abs(dy) > Math.abs(e.getX() - downX)) dragging = true;
                if (dragging) {
                    dragOffset = Math.max(-getHeight(), Math.min(0f, dy));
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    boolean unlock = -dragOffset >= unlockDistance();
                    settleBack();
                    if (unlock) actions.unlock();
                } else if (downRegion != null && downRegion == hit(e.getX(), e.getY())) {
                    performClick();
                    LockLook.tap(this);
                    downRegion.action.run();
                }
                downRegion = null;
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                settleBack();
                downRegion = null;
                dragging = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void settleBack() {
        if (dragOffset == 0f) return;
        settle = ValueAnimator.ofFloat(dragOffset, 0f);
        settle.setDuration(220);
        settle.setInterpolator(new DecelerateInterpolator());
        settle.addUpdateListener(a -> {
            dragOffset = (float) a.getAnimatedValue();
            invalidate();
        });
        settle.start();
    }

    // ---- accessibility: the drawn controls as virtual views -----------------------------------

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        return provider;
    }

    private Region region(int id) {
        for (Region r : regions) if (r.id == id) return r;
        return null;
    }

    private final AccessibilityNodeProvider provider = new AccessibilityNodeProvider() {
        @Override
        public AccessibilityNodeInfo createAccessibilityNodeInfo(int virtualViewId) {
            if (virtualViewId == View.NO_ID) {
                AccessibilityNodeInfo host = AccessibilityNodeInfo.obtain(WakeView.this);
                onInitializeAccessibilityNodeInfo(host);
                for (Region r : regions) host.addChild(WakeView.this, r.id);
                return host;
            }
            Region r = region(virtualViewId);
            if (r == null) return null;
            AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(WakeView.this, virtualViewId);
            n.setPackageName(getContext().getPackageName());
            n.setClassName("android.widget.Button");
            n.setParent(WakeView.this);
            n.setContentDescription(r.label);
            n.setClickable(true);
            n.setEnabled(true);
            n.setFocusable(true);
            n.setVisibleToUser(true);
            n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            Rect parent = new Rect();
            r.rect.roundOut(parent);
            parent.offset(0, Math.round(dragOffset));
            n.setBoundsInParent(parent);
            int[] loc = new int[2];
            getLocationOnScreen(loc);
            Rect screen = new Rect(parent);
            screen.offset(loc[0], loc[1]);
            n.setBoundsInScreen(screen);
            return n;
        }

        @Override
        public boolean performAction(int virtualViewId, int action, Bundle arguments) {
            if (virtualViewId == View.NO_ID) return performAccessibilityAction(action, arguments);
            Region r = region(virtualViewId);
            if (r == null || action != AccessibilityNodeInfo.ACTION_CLICK) return false;
            r.action.run();
            return true;
        }
    };
}
