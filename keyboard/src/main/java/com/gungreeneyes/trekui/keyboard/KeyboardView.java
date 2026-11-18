package com.gungreeneyes.trekui.keyboard;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import com.gungreeneyes.trekui.KeyboardLook;
import com.gungreeneyes.trekui.keyboard.logic.Alternates;
import com.gungreeneyes.trekui.keyboard.logic.Key;
import com.gungreeneyes.trekui.keyboard.logic.KeyLayout;
import com.gungreeneyes.trekui.keyboard.logic.ShiftState;
import com.gungreeneyes.trekui.keyboard.logic.Sizing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The keyboard, drawn on one Canvas: a suggestion strip (completions, the UNLOCK chip when the trial
 * session is over, the voice key when a voice keyboard exists) above the rows of keys of the current
 * {@link KeyLayout}. Keys are painted with the active interface's own cell drawables
 * ({@link KeyboardLook#cell}), so they look like the launcher's controls in Command, Imperial, Collective,
 * the console eras and the pipe-lounge styles; function keys use {@link KeyIcons}.
 *
 * <p>Touch: keys type on release (with sound / haptic on press); a second finger commits the first key at
 * once (fast two-thumb typing). Long press opens the alternates popup (slide to choose, release to type),
 * repeats DELETE, and on SPACE / globe / emoji asks the service for its long-press action. Dragging the
 * space bar moves the cursor; swiping left from DELETE deletes whole words. All decisions about what to
 * type are made by the {@link Listener} (the service).
 */
@SuppressLint("ViewConstructor")
final class KeyboardView extends View {

    /** What the service does with touches. */
    interface Listener {
        /** A key went down (sound + haptic feedback). */
        void onPress(Key key);
        /** Type {@code text} (already shifted). */
        void onText(String text);
        void onDelete();
        void onDeleteWord();
        /** A function key was tapped (SHIFT, ENTER, SPACE, page switches, GLOBE, EMOJI). */
        void onFunction(int kind);
        /** A function key was held (SPACE, GLOBE, EMOJI). */
        void onLongFunction(int kind);
        /** Move the cursor one step left (-1) or right (+1). */
        void onCursor(int direction);
        void onSuggestion(String word);
        void onUnlock();
        void onVoice();
        /** The empty side in one-handed mode was tapped: move the keys to the other side. */
        void onSwapHand();
    }

    private static final long LONG_PRESS_MS = 380;
    private static final long REPEAT_START_MS = 400;
    private static final long REPEAT_MS = 55;

    private final Listener listener;
    private final KeyIcons icons = new KeyIcons();
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Drawable unlockCell, unlockCellPressed;
    private final RectF tmp = new RectF();
    private final float density;

    private KeyLayout layout;
    private ShiftState shift = new ShiftState();
    private Locale locale = Locale.ROOT;
    private String enterLabel;
    private String spaceLabel = "";
    private List<String> suggestions = Collections.emptyList();
    private String unlockLabel = "UNLOCK";
    private boolean unlockChip, voiceKey;
    private int rowPx = 1;
    private int oneHanded = Sizing.ONE_HANDED_OFF;
    private int bottomInset;
    private Typeface heavy, regular;

    /** One laid-out key. */
    private static final class Slot {
        final Key key;
        final RectF r = new RectF();
        Drawable normal, pressed;
        int corner;

        Slot(Key key) { this.key = key; }
    }

    private final List<Slot> slots = new ArrayList<>();
    private final RectF stripRect = new RectF(), unlockRect = new RectF(), voiceRect = new RectF(), swapRect = new RectF();
    private final List<RectF> suggestionRects = new ArrayList<>();
    private int stripPx;

    // ---- touch state ---------------------------------------------------------------------------------
    private Slot active;
    private int activePointer = -1;
    private float downX, downY, anchorX;
    private boolean longPressed, cursorMode, swiping, repeated;
    private int wordsDeleted;
    /** Strip target on down: suggestion index, or -2 unlock, -3 voice, -4 swap side; -1 none. */
    private int stripTarget = -1;

    // ---- popup -----------------------------------------------------------------------------------------
    private List<String> popup;
    private final List<RectF> popupCells = new ArrayList<>();
    private final RectF popupRect = new RectF();
    private int popupSel = -1;

    private final Runnable longPress = this::onLongPress;
    private final Runnable repeat = new Runnable() {
        @Override public void run() {
            if (active == null || active.key.kind != Key.DELETE || swiping) return;
            repeated = true;
            listener.onDelete();
            postDelayed(this, REPEAT_MS);
        }
    };

    KeyboardView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = context.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        edge.setStyle(Paint.Style.STROKE);
        setHapticFeedbackEnabled(true);
        setSoundEffectsEnabled(false); // clicks come from the TrekUI synth
        setOnApplyWindowInsetsListener((v, insets) -> {
            int b = navigationInset(insets);
            if (b != bottomInset) {
                bottomInset = b;
                requestLayout();
            }
            return insets;
        });
        themeChanged();
    }

    @SuppressWarnings("deprecation")
    private static int navigationInset(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= 30) return insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        return insets.getSystemWindowInsetBottom();
    }

    // ---- state from the service -------------------------------------------------------------------------

    void setLayout(KeyLayout layout, Locale locale) {
        this.layout = layout;
        this.locale = locale;
        cancelTouch();
        requestLayout();
        rebuild();
    }

    void setShift(ShiftState shift) {
        this.shift = shift;
        invalidate();
    }

    /** {@code label} null: the return-arrow glyph. */
    void setEnterLabel(String label) {
        enterLabel = label;
        invalidate();
    }

    void setSpaceLabel(String label) {
        spaceLabel = label == null ? "" : label;
        invalidate();
    }

    void setStrip(List<String> suggestions, boolean unlock, String unlockLabel, boolean voice) {
        this.suggestions = suggestions == null ? Collections.emptyList() : suggestions;
        boolean relayout = unlock != unlockChip || voice != voiceKey;
        unlockChip = unlock;
        this.unlockLabel = unlockLabel;
        voiceKey = voice;
        if (relayout) rebuild();
        invalidate();
    }

    void setSizes(int rowPx, int oneHanded) {
        if (rowPx == this.rowPx && oneHanded == this.oneHanded) return;
        this.rowPx = Math.max(1, rowPx);
        this.oneHanded = oneHanded;
        requestLayout();
        rebuild();
    }

    /** The interface or sub-theme changed: reload colours, fonts and key drawables. */
    void themeChanged() {
        KeyboardLook.load(getContext());
        heavy = KeyboardLook.font(getContext(), true);
        regular = KeyboardLook.font(getContext(), false);
        for (Slot s : slots) {
            s.normal = null;
            s.pressed = null;
        }
        unlockCell = null;
        unlockCellPressed = null;
        invalidate();
    }

    // ---- measuring and layout ----------------------------------------------------------------------------

    private int pad() { return Math.round(4 * density); }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int rows = layout == null ? 4 : layout.rows.size();
        stripPx = Math.max(Math.round(36 * density), Math.round(rowPx * 0.8f));
        int h = stripPx + rows * rowPx + 2 * pad() + bottomInset;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        rebuild();
    }

    /** Lays out the strip and every key for the current size. */
    private void rebuild() {
        slots.clear();
        suggestionRects.clear();
        int w = getWidth();
        if (w <= 0 || layout == null) {
            invalidate();
            return;
        }
        int pad = pad();
        stripRect.set(0, 0, w, stripPx);
        float right = w - pad;
        voiceRect.setEmpty();
        unlockRect.setEmpty();
        if (voiceKey) {
            voiceRect.set(right - stripPx, 0, right, stripPx);
            right -= stripPx;
        }
        if (unlockChip) {
            text.setTextSize(stripPx * 0.36f);
            text.setTypeface(heavy);
            float cw = text.measureText(unlockLabel) + 28 * density;
            unlockRect.set(right - cw, stripPx * 0.16f, right - 4 * density, stripPx * 0.84f);
            right -= cw + 4 * density;
        }
        int n = 3;
        float cell = (right - pad) / n;
        for (int i = 0; i < n; i++) suggestionRects.add(new RectF(pad + i * cell, 0, pad + (i + 1) * cell, stripPx));

        float areaW = w - 2f * pad;
        float x0 = pad;
        swapRect.setEmpty();
        if (oneHanded != Sizing.ONE_HANDED_OFF) {
            float kw = areaW * Sizing.ONE_HANDED_SHARE;
            if (oneHanded == Sizing.ONE_HANDED_RIGHT) {
                swapRect.set(pad, stripPx, pad + areaW - kw, stripPx + pad + layout.rows.size() * rowPx);
                x0 = pad + areaW - kw;
            } else {
                swapRect.set(pad + kw, stripPx, w - pad, stripPx + pad + layout.rows.size() * rowPx);
            }
            areaW = kw;
        }
        float unit = areaW / layout.maxRowWeight();
        float gap = Math.max(2f, 3 * density);
        float y = stripPx + pad;
        for (int r = 0; r < layout.rows.size(); r++) {
            List<Key> row = layout.rows.get(r);
            float rowW = layout.rowWeight(r) * unit;
            float x = x0 + (areaW - rowW) / 2f;
            for (int i = 0; i < row.size(); i++) {
                Key k = row.get(i);
                Slot s = new Slot(k);
                float kw = k.weight * unit;
                s.r.set(x + gap / 2f, y + gap / 2f, x + kw - gap / 2f, y + rowPx - gap / 2f);
                s.corner = corner(k, i, row.size());
                slots.add(s);
                x += kw;
            }
            y += rowPx;
        }
        invalidate();
    }

    /** Console-style ends: the first key of a function row rounds left, the last rounds right. */
    private static int corner(Key k, int index, int count) {
        if (k.kind == Key.SPACE) return KeyboardLook.PILL;
        if (k.kind == Key.CHAR) return KeyboardLook.SQUARE;
        if (index == 0) return KeyboardLook.LEFT;
        if (index == count - 1) return KeyboardLook.RIGHT;
        return KeyboardLook.SQUARE;
    }

    /** Accent slot per key kind: letters, function keys, space and enter each get their own colour. */
    private static int colorOf(Key k) {
        switch (k.kind) {
            case Key.CHAR: return KeyboardLook.accent(1);
            case Key.SPACE: return KeyboardLook.accent(4);
            case Key.ENTER: return KeyboardLook.accent(0);
            case Key.DELETE: return KeyboardLook.accent(3);
            default: return KeyboardLook.accent(2);
        }
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(KeyboardLook.bg());
        drawStrip(c);
        for (Slot s : slots) drawKey(c, s);
        if (!swapRect.isEmpty()) drawSwap(c);
        if (popup != null) drawPopup(c);
    }

    private void drawStrip(Canvas c) {
        fillPaint.setColor(KeyboardLook.surface());
        c.drawRect(stripRect, fillPaint);
        fillPaint.setColor(KeyboardLook.alpha(KeyboardLook.accent(0), 0.55f));
        c.drawRect(0, stripRect.bottom - Math.max(1f, density), getWidth(), stripRect.bottom, fillPaint);
        float size = stripPx * 0.42f * KeyboardLook.textScale();
        for (int i = 0; i < suggestionRects.size() && i < suggestions.size(); i++) {
            RectF r = suggestionRects.get(i);
            boolean pressed = stripTarget == i;
            if (pressed) {
                fillPaint.setColor(KeyboardLook.alpha(KeyboardLook.accent(1), 0.35f));
                c.drawRect(r, fillPaint);
            }
            text.setTypeface(i == 0 ? heavy : regular);
            text.setColor(i == 0 ? KeyboardLook.textHi() : KeyboardLook.textLo());
            drawFitted(c, suggestions.get(i), r.centerX(), r.centerY(), size, r.width() - 8 * density);
            if (i > 0) {
                fillPaint.setColor(KeyboardLook.grid());
                c.drawRect(r.left, r.top + stripPx * 0.25f, r.left + Math.max(1f, density), r.bottom - stripPx * 0.25f, fillPaint);
            }
        }
        if (!unlockRect.isEmpty()) {
            if (unlockCell == null) {
                unlockCell = KeyboardLook.cell(getContext(), KeyboardLook.accent(0), KeyboardLook.PILL, 999f);
                unlockCellPressed = KeyboardLook.cell(getContext(),
                        KeyboardLook.mix(KeyboardLook.accent(0), Color.WHITE, 0.3f), KeyboardLook.PILL, 999f);
            }
            Drawable d = stripTarget == -2 ? unlockCellPressed : unlockCell;
            d.setBounds(Math.round(unlockRect.left), Math.round(unlockRect.top), Math.round(unlockRect.right), Math.round(unlockRect.bottom));
            d.draw(c);
            text.setTypeface(heavy);
            text.setColor(KeyboardLook.ink());
            drawFitted(c, unlockLabel, unlockRect.centerX(), unlockRect.centerY(), stripPx * 0.36f, unlockRect.width());
        }
        if (!voiceRect.isEmpty()) {
            icons.mic(c, voiceRect.centerX(), voiceRect.centerY(), stripPx * 0.9f,
                    stripTarget == -3 ? KeyboardLook.accent(0) : KeyboardLook.textHi());
        }
    }

    private void drawKey(Canvas c, Slot s) {
        boolean down = s == active && popup == null;
        if (s.normal == null) {
            int color = colorOf(s.key);
            float radius = 6f;
            s.normal = KeyboardLook.cell(getContext(), color, s.corner, radius);
            s.pressed = KeyboardLook.cell(getContext(), KeyboardLook.mix(color, Color.WHITE, 0.35f), s.corner, radius);
        }
        Drawable d = down ? s.pressed : s.normal;
        d.setBounds(Math.round(s.r.left), Math.round(s.r.top), Math.round(s.r.right), Math.round(s.r.bottom));
        d.draw(c);

        int ink = KeyboardLook.ink();
        float cx = s.r.centerX(), cy = s.r.centerY();
        float iconSize = Math.min(s.r.height(), s.r.width()) * 0.95f;
        Key k = s.key;
        switch (k.kind) {
            case Key.CHAR: {
                String label = k.isLetter() && shift.upper() ? Alternates.shifted(k.text, locale) : k.text;
                text.setTypeface(k.isLetter() ? heavy : regular);
                text.setColor(ink);
                float size = s.r.height() * (layout.mode.textual() && k.isLetter() ? 0.5f : 0.44f) * KeyboardLook.textScale();
                if (!k.isLetter() && label.codePointCount(0, label.length()) == 1 && Character.isDigit(label.codePointAt(0))) size *= 1.05f;
                drawFitted(c, label, cx, cy, size, s.r.width() * 0.9f);
                if (k.hint != null) {
                    text.setTypeface(regular);
                    text.setColor(KeyboardLook.alpha(ink, 0.6f));
                    text.setTextSize(s.r.height() * 0.2f);
                    c.drawText(k.hint, s.r.right - s.r.height() * 0.16f, s.r.top + s.r.height() * 0.26f, text);
                }
                break;
            }
            case Key.SHIFT:
                icons.shift(c, cx, cy, iconSize, ink, shift.upper(), shift.locked());
                break;
            case Key.DELETE:
                icons.delete(c, cx, cy, iconSize, ink);
                break;
            case Key.ENTER:
                if (enterLabel == null) icons.enter(c, cx, cy, iconSize, ink);
                else label(c, enterLabel, s, ink, 0.32f);
                break;
            case Key.SPACE:
                text.setTypeface(regular);
                text.setColor(KeyboardLook.alpha(ink, 0.75f));
                drawFitted(c, spaceLabel, cx, cy, s.r.height() * 0.28f * KeyboardLook.textScale(), s.r.width() * 0.85f);
                break;
            case Key.GLOBE:
                icons.globe(c, cx, cy, iconSize, ink);
                break;
            case Key.EMOJI:
                icons.emoji(c, cx, cy, iconSize, ink);
                break;
            case Key.TO_SYMBOLS:
                label(c, getContext().getString(R.string.key_symbols), s, ink, 0.32f);
                break;
            case Key.TO_SYMBOLS_2:
                label(c, getContext().getString(R.string.key_symbols_more), s, ink, 0.32f);
                break;
            case Key.TO_LETTERS:
                label(c, getContext().getString(R.string.key_letters), s, ink, 0.32f);
                break;
            default:
                break;
        }
    }

    private void label(Canvas c, String s, Slot slot, int color, float share) {
        text.setTypeface(heavy);
        text.setColor(color);
        drawFitted(c, s, slot.r.centerX(), slot.r.centerY(), slot.r.height() * share * KeyboardLook.textScale(), slot.r.width() * 0.88f);
    }

    /** Draws {@code s} centred on (cx, cy), shrinking the size until it fits {@code maxW}. */
    private void drawFitted(Canvas c, String s, float cx, float cy, float size, float maxW) {
        if (s == null || s.isEmpty()) return;
        text.setTextSize(size);
        float w = text.measureText(s);
        if (w > maxW && w > 0) text.setTextSize(Math.max(size * 0.5f, size * maxW / w));
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text);
    }

    /** The empty side in one-handed mode: a chevron pointing where the keys can move. */
    private void drawSwap(Canvas c) {
        float cx = swapRect.centerX(), cy = swapRect.centerY(), s = Math.min(swapRect.width(), rowPx) * 0.25f;
        fillPaint.setColor(KeyboardLook.alpha(KeyboardLook.textLo(), 0.8f));
        fillPaint.setStrokeWidth(Math.max(2f, 2.5f * density));
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        float dir = oneHanded == Sizing.ONE_HANDED_LEFT ? 1f : -1f;
        c.drawLine(cx - dir * s / 2f, cy - s, cx + dir * s / 2f, cy, fillPaint);
        c.drawLine(cx + dir * s / 2f, cy, cx - dir * s / 2f, cy + s, fillPaint);
    }

    private void drawPopup(Canvas c) {
        fillPaint.setColor(KeyboardLook.surface());
        float r = 6 * density;
        c.drawRoundRect(popupRect, r, r, fillPaint);
        edge.setStrokeWidth(Math.max(1.5f, 2 * density));
        edge.setColor(KeyboardLook.accent(0));
        c.drawRoundRect(popupRect, r, r, edge);
        for (int i = 0; i < popup.size(); i++) {
            RectF cell = popupCells.get(i);
            boolean sel = i == popupSel;
            if (sel) {
                fillPaint.setColor(KeyboardLook.accent(0));
                tmp.set(cell);
                tmp.inset(2 * density, 2 * density);
                c.drawRoundRect(tmp, r, r, fillPaint);
            }
            text.setTypeface(heavy);
            text.setColor(sel ? KeyboardLook.ink() : KeyboardLook.textHi());
            drawFitted(c, popup.get(i), cell.centerX(), cell.centerY(), cell.height() * 0.48f * KeyboardLook.textScale(), cell.width() * 0.9f);
        }
    }

    // ---- touch ----------------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                down(e, 0);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                // rollover: a second finger finishes the first key immediately
                if (active != null && popup == null) release(true);
                down(e, e.getActionIndex());
                return true;
            case MotionEvent.ACTION_MOVE: {
                int idx = e.findPointerIndex(activePointer);
                if (idx >= 0) move(e.getX(idx), e.getY(idx));
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerId(e.getActionIndex()) == activePointer) release(true);
                return true;
            case MotionEvent.ACTION_UP:
                if (stripTarget != -1) stripUp(e.getX(), e.getY());
                else if (e.getPointerId(e.getActionIndex()) == activePointer) release(true);
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelTouch();
                return true;
            default:
                return true;
        }
    }

    private void down(MotionEvent e, int index) {
        float x = e.getX(index), y = e.getY(index);
        if (y < stripRect.bottom || swapRect.contains(x, y)) {
            stripTarget = stripHit(x, y);
            invalidate();
            return;
        }
        Slot s = hit(x, y);
        if (s == null) return;
        active = s;
        activePointer = e.getPointerId(index);
        downX = anchorX = x;
        downY = y;
        longPressed = cursorMode = swiping = repeated = false;
        wordsDeleted = 0;
        listener.onPress(s.key);
        if (s.key.kind == Key.DELETE) postDelayed(repeat, REPEAT_START_MS);
        else postDelayed(longPress, LONG_PRESS_MS);
        invalidate();
    }

    private void move(float x, float y) {
        if (active == null) return;
        if (popup != null) {
            int sel = popupHit(x, y);
            if (sel != popupSel) {
                popupSel = sel;
                invalidate();
            }
            return;
        }
        float slop = 12 * density;
        Key k = active.key;
        if (k.kind == Key.SPACE && !longPressed) {
            if (!cursorMode && Math.abs(x - downX) > slop) {
                cursorMode = true;
                removeCallbacks(longPress);
                anchorX = x;
            }
            if (cursorMode) {
                float step = 9 * density;
                while (x - anchorX >= step) { listener.onCursor(1); anchorX += step; }
                while (anchorX - x >= step) { listener.onCursor(-1); anchorX -= step; }
            }
            return;
        }
        if (k.kind == Key.DELETE) {
            float swipe = 28 * density, perWord = 36 * density;
            if (downX - x > swipe) {
                if (!swiping) {
                    swiping = true;
                    removeCallbacks(repeat);
                }
                int words = 1 + (int) ((downX - x - swipe) / perWord);
                while (wordsDeleted < words) {
                    listener.onDeleteWord();
                    wordsDeleted++;
                }
            }
            return;
        }
        // slid off a key onto another before the long press: follow the finger
        if (!longPressed && !active.r.contains(x, y)) {
            Slot s = hit(x, y);
            if (s != null && s != active && s.key.kind != Key.DELETE && s.key.kind != Key.SPACE
                    && Math.abs(y - downY) < rowPx * 1.5f) {
                active = s;
                removeCallbacks(longPress);
                postDelayed(longPress, LONG_PRESS_MS);
                invalidate();
            }
        }
    }

    /** The finger lifted (or a second finger took over): act on the active key. */
    private void release(boolean commit) {
        removeCallbacks(longPress);
        removeCallbacks(repeat);
        Slot s = active;
        active = null;
        activePointer = -1;
        if (s == null || !commit) {
            popup = null;
            invalidate();
            return;
        }
        if (popup != null) {
            if (popupSel >= 0 && popupSel < popup.size()) listener.onText(popup.get(popupSel));
            popup = null;
            invalidate();
            return;
        }
        Key k = s.key;
        switch (k.kind) {
            case Key.CHAR:
                listener.onText(k.isLetter() && shift.upper() ? Alternates.shifted(k.text, locale) : k.text);
                break;
            case Key.DELETE:
                if (!repeated && !swiping) listener.onDelete();
                break;
            case Key.SPACE:
                if (!cursorMode && !longPressed) listener.onFunction(Key.SPACE);
                break;
            default:
                if (!longPressed) listener.onFunction(k.kind);
        }
        invalidate();
    }

    private void onLongPress() {
        if (active == null) return;
        Key k = active.key;
        if (k.kind == Key.CHAR && !k.alternates.isEmpty()) {
            openPopup(active);
            longPressed = true;
        } else if (k.kind == Key.SPACE || k.kind == Key.GLOBE || k.kind == Key.EMOJI) {
            longPressed = true;
            listener.onLongFunction(k.kind);
        }
        invalidate();
    }

    private void openPopup(Slot s) {
        List<String> choices = new ArrayList<>();
        boolean up = shift.upper();
        for (String a : s.key.alternates) choices.add(up ? Alternates.shifted(a, locale) : a);
        popup = choices;
        popupCells.clear();
        float cw = Math.max(s.r.width(), 30 * density), ch = Math.min(rowPx, s.r.height() + 6 * density);
        int maxCols = Math.max(1, (int) ((getWidth() - 2 * pad()) / cw));
        int cols = Math.min(choices.size(), Math.min(maxCols, 8));
        int rows = (choices.size() + cols - 1) / cols;
        float w = cols * cw, h = rows * ch;
        float left = Math.max(pad(), Math.min(getWidth() - pad() - w, s.r.centerX() - cw / 2f));
        float top = Math.max(0, s.r.top - h - 2 * density);
        popupRect.set(left, top, left + w, top + h);
        for (int i = 0; i < choices.size(); i++) {
            int row = rows - 1 - i / cols; // first choices on the row nearest the finger
            int col = i % cols;
            popupCells.add(new RectF(left + col * cw, top + row * ch, left + (col + 1) * cw, top + (row + 1) * ch));
        }
        popupSel = 0;
    }

    private int popupHit(float x, float y) {
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < popupCells.size(); i++) {
            RectF r = popupCells.get(i);
            float dx = Math.max(0, Math.max(r.left - x, x - r.right));
            float dy = Math.max(0, Math.max(r.top - y, y - r.bottom));
            float d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        // sliding far below the popup (back onto the keys) cancels
        return y > popupRect.bottom + rowPx * 1.2f ? -1 : best;
    }

    /** The key under (x, y), or the nearest one (gaps between keys still type). */
    private Slot hit(float x, float y) {
        Slot best = null;
        float bestD = Float.MAX_VALUE;
        for (Slot s : slots) {
            float dx = Math.max(0, Math.max(s.r.left - x, x - s.r.right));
            float dy = Math.max(0, Math.max(s.r.top - y, y - s.r.bottom));
            float d = dx * dx + dy * dy;
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        float limit = rowPx * 0.6f;
        return bestD <= limit * limit ? best : null;
    }

    private int stripHit(float x, float y) {
        if (swapRect.contains(x, y)) return -4;
        if (!unlockRect.isEmpty() && x >= unlockRect.left - 4 * density && x <= unlockRect.right + 4 * density) return -2;
        if (!voiceRect.isEmpty() && voiceRect.contains(x, y)) return -3;
        for (int i = 0; i < suggestionRects.size() && i < suggestions.size(); i++) {
            if (suggestionRects.get(i).contains(x, Math.min(y, stripPx - 1))) return i;
        }
        return -1;
    }

    private void stripUp(float x, float y) {
        int target = stripTarget;
        stripTarget = -1;
        invalidate();
        if (target != stripHit(x, Math.min(y, stripPx - 1f)) && target != -4) return;
        if (target == -2) listener.onUnlock();
        else if (target == -3) listener.onVoice();
        else if (target == -4) listener.onSwapHand();
        else if (target >= 0 && target < suggestions.size()) listener.onSuggestion(suggestions.get(target));
    }

    /** Drops any touch in progress (layout change, keyboard hidden). */
    void cancelTouch() {
        removeCallbacks(longPress);
        removeCallbacks(repeat);
        active = null;
        activePointer = -1;
        popup = null;
        stripTarget = -1;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelTouch();
        super.onDetachedFromWindow();
    }
}
