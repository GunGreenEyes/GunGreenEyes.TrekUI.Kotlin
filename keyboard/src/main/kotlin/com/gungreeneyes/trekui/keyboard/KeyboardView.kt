package com.gungreeneyes.trekui.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.gungreeneyes.trekui.KeyboardLook
import com.gungreeneyes.trekui.keyboard.logic.Alternates
import com.gungreeneyes.trekui.keyboard.logic.Key
import com.gungreeneyes.trekui.keyboard.logic.KeyLayout
import com.gungreeneyes.trekui.keyboard.logic.ShiftState
import com.gungreeneyes.trekui.keyboard.logic.Sizing
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The keyboard, drawn on one Canvas: a suggestion strip (completions, the UNLOCK chip when the trial
 * session is over, the voice key when a voice keyboard exists) above the rows of keys of the current
 * [KeyLayout]. Keys are painted with the active interface's own cell drawables ([KeyboardLook.cell]),
 * so they look like the launcher's controls in Command, Imperial, Collective, and the console eras;
 * function keys use [KeyIcons].
 *
 * Touch: keys type on release (with sound / haptic on press); a second finger commits the first key at
 * once (fast two-thumb typing). Long press opens the alternates popup (slide to choose, release to type),
 * repeats DELETE, and on SPACE / globe / emoji asks the service for its long-press action. Dragging the
 * space bar moves the cursor; swiping left from DELETE deletes whole words. All decisions about what to
 * type are made by the [Listener] (the service).
 */
@SuppressLint("ViewConstructor")
internal class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    /** What the service does with touches. */
    interface Listener {
        /** A key went down (sound + haptic feedback). */
        fun onPress(key: Key)
        /** Type [text] (already shifted). */
        fun onText(text: String)
        fun onDelete()
        fun onDeleteWord()
        /** A function key was tapped (SHIFT, ENTER, SPACE, page switches, GLOBE, EMOJI). */
        fun onFunction(kind: Int)
        /** A function key was held (SPACE, GLOBE, EMOJI). */
        fun onLongFunction(kind: Int)
        /** Move the cursor one step left (-1) or right (+1). */
        fun onCursor(direction: Int)
        fun onSuggestion(word: String)
        fun onUnlock()
        fun onVoice()
        /** The empty side in one-handed mode was tapped: move the keys to the other side. */
        fun onSwapHand()
    }

    /** One laid-out key. */
    private class Slot(val key: Key) {
        val r = RectF()
        var normal: Drawable? = null
        var pressed: Drawable? = null
        var corner = 0
    }

    private val icons = KeyIcons()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var unlockCell: Drawable? = null
    private var unlockCellPressed: Drawable? = null
    private val tmp = RectF()
    private val density: Float = context.resources.displayMetrics.density

    private var layout: KeyLayout? = null
    private var shift = ShiftState()
    private var locale: Locale = Locale.ROOT
    private var enterLabel: String? = null
    private var spaceLabel = ""
    private var suggestions: List<String> = emptyList()
    private var unlockLabel = "UNLOCK"
    private var unlockChip = false
    private var voiceKey = false
    private var rowPx = 1
    private var oneHanded = Sizing.ONE_HANDED_OFF
    private var bottomInset = 0
    private var heavy: Typeface? = null
    private var regular: Typeface? = null

    private val slots = mutableListOf<Slot>()
    private val stripRect = RectF()
    private val unlockRect = RectF()
    private val voiceRect = RectF()
    private val swapRect = RectF()
    private val suggestionRects = mutableListOf<RectF>()
    private var stripPx = 0

    // ---- touch state ---------------------------------------------------------------------------------
    private var active: Slot? = null
    private var activePointer = -1
    private var downX = 0f
    private var downY = 0f
    private var anchorX = 0f
    private var longPressed = false
    private var cursorMode = false
    private var swiping = false
    private var repeated = false
    private var wordsDeleted = 0
    /** Strip target on down: suggestion index, or [TARGET_UNLOCK], [TARGET_VOICE], [TARGET_SWAP]; [TARGET_NONE]. */
    private var stripTarget = TARGET_NONE

    // ---- popup -----------------------------------------------------------------------------------------
    private var popup: List<String>? = null
    private val popupCells = mutableListOf<RectF>()
    private val popupRect = RectF()
    private var popupSel = -1

    private val longPress = Runnable { onLongPress() }
    private val repeat: Runnable = object : Runnable {
        override fun run() {
            if (active?.key?.kind != Key.DELETE || swiping) return
            repeated = true
            listener.onDelete()
            postDelayed(this, REPEAT_MS)
        }
    }

    init {
        isHapticFeedbackEnabled = true
        isSoundEffectsEnabled = false // clicks come from the TrekUI synth
        setOnApplyWindowInsetsListener { _, insets ->
            val b = navigationInset(insets)
            if (b != bottomInset) {
                bottomInset = b
                requestLayout()
            }
            insets
        }
        themeChanged()
    }

    // ---- state from the service -------------------------------------------------------------------------

    fun setLayout(layout: KeyLayout, locale: Locale) {
        this.layout = layout
        this.locale = locale
        cancelTouch()
        requestLayout()
        rebuild()
    }

    fun setShift(shift: ShiftState) {
        this.shift = shift
        invalidate()
    }

    /** [label] null: the return-arrow glyph. */
    fun setEnterLabel(label: String?) {
        enterLabel = label
        invalidate()
    }

    fun setSpaceLabel(label: String?) {
        spaceLabel = label.orEmpty()
        invalidate()
    }

    fun setStrip(suggestions: List<String>?, unlock: Boolean, unlockLabel: String, voice: Boolean) {
        this.suggestions = suggestions.orEmpty()
        val relayout = unlock != unlockChip || voice != voiceKey
        unlockChip = unlock
        this.unlockLabel = unlockLabel
        voiceKey = voice
        if (relayout) rebuild()
        invalidate()
    }

    fun setSizes(rowPx: Int, oneHanded: Int) {
        if (rowPx == this.rowPx && oneHanded == this.oneHanded) return
        this.rowPx = maxOf(1, rowPx)
        this.oneHanded = oneHanded
        requestLayout()
        rebuild()
    }

    /** The interface or sub-theme changed: reload colours, fonts and key drawables. */
    fun themeChanged() {
        KeyboardLook.load(context)
        heavy = KeyboardLook.font(context, true)
        regular = KeyboardLook.font(context, false)
        for (s in slots) {
            s.normal = null
            s.pressed = null
        }
        unlockCell = null
        unlockCellPressed = null
        invalidate()
    }

    // ---- measuring and layout ----------------------------------------------------------------------------

    private fun pad(): Int = (4 * density).roundToInt()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val rows = layout?.rows?.size ?: 4
        stripPx = maxOf((36 * density).roundToInt(), (rowPx * 0.8f).roundToInt())
        val h = stripPx + rows * rowPx + 2 * pad() + bottomInset
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuild()
    }

    /** Lays out the strip and every key for the current size. */
    private fun rebuild() {
        slots.clear()
        suggestionRects.clear()
        val w = width
        val layout = layout
        if (w <= 0 || layout == null) {
            invalidate()
            return
        }
        val pad = pad()
        stripRect.set(0f, 0f, w.toFloat(), stripPx.toFloat())
        var right = (w - pad).toFloat()
        voiceRect.setEmpty()
        unlockRect.setEmpty()
        if (voiceKey) {
            voiceRect.set(right - stripPx, 0f, right, stripPx.toFloat())
            right -= stripPx
        }
        if (unlockChip) {
            text.textSize = stripPx * 0.36f
            text.typeface = heavy
            val cw = text.measureText(unlockLabel) + 28 * density
            unlockRect.set(right - cw, stripPx * 0.16f, right - 4 * density, stripPx * 0.84f)
            right -= cw + 4 * density
        }
        val n = 3
        val cell = (right - pad) / n
        for (i in 0 until n) suggestionRects += RectF(pad + i * cell, 0f, pad + (i + 1) * cell, stripPx.toFloat())

        var areaW = w - 2f * pad
        var x0 = pad.toFloat()
        swapRect.setEmpty()
        if (oneHanded != Sizing.ONE_HANDED_OFF) {
            val kw = areaW * Sizing.ONE_HANDED_SHARE
            val bottom = (stripPx + pad + layout.rows.size * rowPx).toFloat()
            if (oneHanded == Sizing.ONE_HANDED_RIGHT) {
                swapRect.set(pad.toFloat(), stripPx.toFloat(), pad + areaW - kw, bottom)
                x0 = pad + areaW - kw
            } else {
                swapRect.set(pad + kw, stripPx.toFloat(), (w - pad).toFloat(), bottom)
            }
            areaW = kw
        }
        val unit = areaW / layout.maxRowWeight()
        val gap = maxOf(2f, 3 * density)
        var y = (stripPx + pad).toFloat()
        layout.rows.forEachIndexed { r, row ->
            val rowW = layout.rowWeight(r) * unit
            var x = x0 + (areaW - rowW) / 2f
            row.forEachIndexed { i, k ->
                val kw = k.weight * unit
                slots += Slot(k).apply {
                    this.r.set(x + gap / 2f, y + gap / 2f, x + kw - gap / 2f, y + rowPx - gap / 2f)
                    corner = cornerOf(k, i, row.size)
                }
                x += kw
            }
            y += rowPx
        }
        invalidate()
    }

    // ---- drawing ------------------------------------------------------------------------------------------

    override fun onDraw(c: Canvas) {
        c.drawColor(KeyboardLook.bg())
        drawStrip(c)
        for (s in slots) drawKey(c, s)
        if (!swapRect.isEmpty) drawSwap(c)
        popup?.let { drawPopup(c, it) }
    }

    private fun drawStrip(c: Canvas) {
        fillPaint.color = KeyboardLook.surface()
        c.drawRect(stripRect, fillPaint)
        fillPaint.color = KeyboardLook.alpha(KeyboardLook.accent(0), 0.55f)
        c.drawRect(0f, stripRect.bottom - maxOf(1f, density), width.toFloat(), stripRect.bottom, fillPaint)
        val size = stripPx * 0.42f * KeyboardLook.textScale()
        for (i in 0 until minOf(suggestionRects.size, suggestions.size)) {
            val r = suggestionRects[i]
            if (stripTarget == i) {
                fillPaint.color = KeyboardLook.alpha(KeyboardLook.accent(1), 0.35f)
                c.drawRect(r, fillPaint)
            }
            text.typeface = if (i == 0) heavy else regular
            text.color = if (i == 0) KeyboardLook.textHi() else KeyboardLook.textLo()
            drawFitted(c, suggestions[i], r.centerX(), r.centerY(), size, r.width() - 8 * density)
            if (i > 0) {
                fillPaint.color = KeyboardLook.grid()
                c.drawRect(r.left, r.top + stripPx * 0.25f, r.left + maxOf(1f, density), r.bottom - stripPx * 0.25f, fillPaint)
            }
        }
        if (!unlockRect.isEmpty) {
            val normal = unlockCell ?: KeyboardLook.cell(context, KeyboardLook.accent(0), KeyboardLook.PILL, 999f)
                .also { unlockCell = it }
            val pressed = unlockCellPressed ?: KeyboardLook.cell(
                context, KeyboardLook.mix(KeyboardLook.accent(0), Color.WHITE, 0.3f), KeyboardLook.PILL, 999f,
            ).also { unlockCellPressed = it }
            val d = if (stripTarget == TARGET_UNLOCK) pressed else normal
            d.setBounds(unlockRect.left.roundToInt(), unlockRect.top.roundToInt(),
                unlockRect.right.roundToInt(), unlockRect.bottom.roundToInt())
            d.draw(c)
            text.typeface = heavy
            text.color = KeyboardLook.ink()
            drawFitted(c, unlockLabel, unlockRect.centerX(), unlockRect.centerY(), stripPx * 0.36f, unlockRect.width())
        }
        if (!voiceRect.isEmpty) {
            icons.mic(c, voiceRect.centerX(), voiceRect.centerY(), stripPx * 0.9f,
                if (stripTarget == TARGET_VOICE) KeyboardLook.accent(0) else KeyboardLook.textHi())
        }
    }

    private fun drawKey(c: Canvas, s: Slot) {
        val down = s === active && popup == null
        if (s.normal == null) {
            val color = colorOf(s.key)
            val radius = 6f
            s.normal = KeyboardLook.cell(context, color, s.corner, radius)
            s.pressed = KeyboardLook.cell(context, KeyboardLook.mix(color, Color.WHITE, 0.35f), s.corner, radius)
        }
        val d = (if (down) s.pressed else s.normal)!!
        d.setBounds(s.r.left.roundToInt(), s.r.top.roundToInt(), s.r.right.roundToInt(), s.r.bottom.roundToInt())
        d.draw(c)

        val ink = KeyboardLook.ink()
        val cx = s.r.centerX()
        val cy = s.r.centerY()
        val iconSize = minOf(s.r.height(), s.r.width()) * 0.95f
        val k = s.key
        when (k.kind) {
            Key.CHAR -> {
                val keyText = k.text.orEmpty()
                val label = if (k.isLetter && shift.upper) Alternates.shifted(keyText, locale) else keyText
                text.typeface = if (k.isLetter) heavy else regular
                text.color = ink
                val textual = layout?.mode?.textual == true
                var size = s.r.height() * (if (textual && k.isLetter) 0.5f else 0.44f) * KeyboardLook.textScale()
                if (!k.isLetter && label.codePointCount(0, label.length) == 1 && Character.isDigit(label.codePointAt(0))) {
                    size *= 1.05f
                }
                drawFitted(c, label, cx, cy, size, s.r.width() * 0.9f)
                k.hint?.let { hint ->
                    text.typeface = regular
                    text.color = KeyboardLook.alpha(ink, 0.6f)
                    text.textSize = s.r.height() * 0.2f
                    c.drawText(hint, s.r.right - s.r.height() * 0.16f, s.r.top + s.r.height() * 0.26f, text)
                }
            }
            Key.SHIFT -> icons.shift(c, cx, cy, iconSize, ink, shift.upper, shift.locked)
            Key.DELETE -> icons.delete(c, cx, cy, iconSize, ink)
            Key.ENTER -> enterLabel?.let { label(c, it, s, ink, 0.32f) } ?: icons.enter(c, cx, cy, iconSize, ink)
            Key.SPACE -> {
                text.typeface = regular
                text.color = KeyboardLook.alpha(ink, 0.75f)
                drawFitted(c, spaceLabel, cx, cy, s.r.height() * 0.28f * KeyboardLook.textScale(), s.r.width() * 0.85f)
            }
            Key.GLOBE -> icons.globe(c, cx, cy, iconSize, ink)
            Key.EMOJI -> icons.emoji(c, cx, cy, iconSize, ink)
            Key.TO_SYMBOLS -> label(c, context.getString(R.string.key_symbols), s, ink, 0.32f)
            Key.TO_SYMBOLS_2 -> label(c, context.getString(R.string.key_symbols_more), s, ink, 0.32f)
            Key.TO_LETTERS -> label(c, context.getString(R.string.key_letters), s, ink, 0.32f)
            else -> Unit
        }
    }

    private fun label(c: Canvas, s: String, slot: Slot, color: Int, share: Float) {
        text.typeface = heavy
        text.color = color
        drawFitted(c, s, slot.r.centerX(), slot.r.centerY(), slot.r.height() * share * KeyboardLook.textScale(), slot.r.width() * 0.88f)
    }

    /** Draws [s] centred on ([cx], [cy]), shrinking the size until it fits [maxW]. */
    private fun drawFitted(c: Canvas, s: String?, cx: Float, cy: Float, size: Float, maxW: Float) {
        if (s.isNullOrEmpty()) return
        text.textSize = size
        val w = text.measureText(s)
        if (w > maxW && w > 0) text.textSize = maxOf(size * 0.5f, size * maxW / w)
        val fm = text.fontMetrics
        c.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, text)
    }

    /** The empty side in one-handed mode: a chevron pointing where the keys can move. */
    private fun drawSwap(c: Canvas) {
        val cx = swapRect.centerX()
        val cy = swapRect.centerY()
        val s = minOf(swapRect.width(), rowPx.toFloat()) * 0.25f
        fillPaint.color = KeyboardLook.alpha(KeyboardLook.textLo(), 0.8f)
        fillPaint.strokeWidth = maxOf(2f, 2.5f * density)
        fillPaint.strokeCap = Paint.Cap.ROUND
        val dir = if (oneHanded == Sizing.ONE_HANDED_LEFT) 1f else -1f
        c.drawLine(cx - dir * s / 2f, cy - s, cx + dir * s / 2f, cy, fillPaint)
        c.drawLine(cx + dir * s / 2f, cy, cx - dir * s / 2f, cy + s, fillPaint)
    }

    private fun drawPopup(c: Canvas, choices: List<String>) {
        fillPaint.color = KeyboardLook.surface()
        val r = 6 * density
        c.drawRoundRect(popupRect, r, r, fillPaint)
        edge.strokeWidth = maxOf(1.5f, 2 * density)
        edge.color = KeyboardLook.accent(0)
        c.drawRoundRect(popupRect, r, r, edge)
        choices.forEachIndexed { i, choice ->
            val cell = popupCells[i]
            val sel = i == popupSel
            if (sel) {
                fillPaint.color = KeyboardLook.accent(0)
                tmp.set(cell)
                tmp.inset(2 * density, 2 * density)
                c.drawRoundRect(tmp, r, r, fillPaint)
            }
            text.typeface = heavy
            text.color = if (sel) KeyboardLook.ink() else KeyboardLook.textHi()
            drawFitted(c, choice, cell.centerX(), cell.centerY(), cell.height() * 0.48f * KeyboardLook.textScale(), cell.width() * 0.9f)
        }
    }

    // ---- touch ----------------------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e, 0)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // rollover: a second finger finishes the first key immediately
                if (active != null && popup == null) release(true)
                down(e, e.actionIndex)
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(activePointer)
                if (idx >= 0) move(e.getX(idx), e.getY(idx))
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == activePointer) release(true)
            MotionEvent.ACTION_UP -> when {
                stripTarget != TARGET_NONE -> stripUp(e.x, e.y)
                e.getPointerId(e.actionIndex) == activePointer -> release(true)
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    private fun down(e: MotionEvent, index: Int) {
        val x = e.getX(index)
        val y = e.getY(index)
        if (y < stripRect.bottom || swapRect.contains(x, y)) {
            stripTarget = stripHit(x, y)
            invalidate()
            return
        }
        val s = hit(x, y) ?: return
        active = s
        activePointer = e.getPointerId(index)
        downX = x
        anchorX = x
        downY = y
        longPressed = false
        cursorMode = false
        swiping = false
        repeated = false
        wordsDeleted = 0
        listener.onPress(s.key)
        if (s.key.kind == Key.DELETE) postDelayed(repeat, REPEAT_START_MS) else postDelayed(longPress, LONG_PRESS_MS)
        invalidate()
    }

    private fun move(x: Float, y: Float) {
        val current = active ?: return
        if (popup != null) {
            val sel = popupHit(x, y)
            if (sel != popupSel) {
                popupSel = sel
                invalidate()
            }
            return
        }
        val slop = 12 * density
        val k = current.key
        if (k.kind == Key.SPACE && !longPressed) {
            if (!cursorMode && abs(x - downX) > slop) {
                cursorMode = true
                removeCallbacks(longPress)
                anchorX = x
            }
            if (cursorMode) {
                val step = 9 * density
                while (x - anchorX >= step) {
                    listener.onCursor(1)
                    anchorX += step
                }
                while (anchorX - x >= step) {
                    listener.onCursor(-1)
                    anchorX -= step
                }
            }
            return
        }
        if (k.kind == Key.DELETE) {
            val swipe = 28 * density
            val perWord = 36 * density
            if (downX - x > swipe) {
                if (!swiping) {
                    swiping = true
                    removeCallbacks(repeat)
                }
                val words = 1 + ((downX - x - swipe) / perWord).toInt()
                while (wordsDeleted < words) {
                    listener.onDeleteWord()
                    wordsDeleted++
                }
            }
            return
        }
        // slid off a key onto another before the long press: follow the finger
        if (!longPressed && !current.r.contains(x, y)) {
            val s = hit(x, y)
            if (s != null && s !== current && s.key.kind != Key.DELETE && s.key.kind != Key.SPACE &&
                abs(y - downY) < rowPx * 1.5f
            ) {
                active = s
                removeCallbacks(longPress)
                postDelayed(longPress, LONG_PRESS_MS)
                invalidate()
            }
        }
    }

    /** The finger lifted (or a second finger took over): act on the active key. */
    private fun release(commit: Boolean) {
        removeCallbacks(longPress)
        removeCallbacks(repeat)
        val s = active
        active = null
        activePointer = -1
        if (s == null || !commit) {
            popup = null
            invalidate()
            return
        }
        popup?.let { choices ->
            if (popupSel in choices.indices) listener.onText(choices[popupSel])
            popup = null
            invalidate()
            return
        }
        val k = s.key
        when (k.kind) {
            Key.CHAR -> {
                val keyText = k.text.orEmpty()
                listener.onText(if (k.isLetter && shift.upper) Alternates.shifted(keyText, locale) else keyText)
            }
            Key.DELETE -> if (!repeated && !swiping) listener.onDelete()
            Key.SPACE -> if (!cursorMode && !longPressed) listener.onFunction(Key.SPACE)
            else -> if (!longPressed) listener.onFunction(k.kind)
        }
        invalidate()
    }

    private fun onLongPress() {
        val current = active ?: return
        val k = current.key
        if (k.kind == Key.CHAR && k.alternates.isNotEmpty()) {
            openPopup(current)
            longPressed = true
        } else if (k.kind == Key.SPACE || k.kind == Key.GLOBE || k.kind == Key.EMOJI) {
            longPressed = true
            listener.onLongFunction(k.kind)
        }
        invalidate()
    }

    private fun openPopup(s: Slot) {
        val up = shift.upper
        val choices = s.key.alternates.map { if (up) Alternates.shifted(it, locale) else it }
        popup = choices
        popupCells.clear()
        val cw = maxOf(s.r.width(), 30 * density)
        val ch = minOf(rowPx.toFloat(), s.r.height() + 6 * density)
        val maxCols = maxOf(1, ((width - 2 * pad()) / cw).toInt())
        val cols = minOf(choices.size, minOf(maxCols, 8))
        val rows = (choices.size + cols - 1) / cols
        val w = cols * cw
        val h = rows * ch
        val left = maxOf(pad().toFloat(), minOf(width - pad() - w, s.r.centerX() - cw / 2f))
        val top = maxOf(0f, s.r.top - h - 2 * density)
        popupRect.set(left, top, left + w, top + h)
        for (i in choices.indices) {
            val row = rows - 1 - i / cols // first choices on the row nearest the finger
            val col = i % cols
            popupCells += RectF(left + col * cw, top + row * ch, left + (col + 1) * cw, top + (row + 1) * ch)
        }
        popupSel = 0
    }

    private fun popupHit(x: Float, y: Float): Int {
        // sliding far below the popup (back onto the keys) cancels
        if (y > popupRect.bottom + rowPx * 1.2f) return -1
        return nearest(popupCells, x, y) { it }?.let { popupCells.indexOf(it) } ?: -1
    }

    /** The key under (x, y), or the nearest one (gaps between keys still type). */
    private fun hit(x: Float, y: Float): Slot? {
        val limit = rowPx * 0.6f
        return nearest(slots, x, y, limit * limit) { it.r }
    }

    private fun stripHit(x: Float, y: Float): Int {
        if (swapRect.contains(x, y)) return TARGET_SWAP
        if (!unlockRect.isEmpty && x >= unlockRect.left - 4 * density && x <= unlockRect.right + 4 * density) {
            return TARGET_UNLOCK
        }
        if (!voiceRect.isEmpty && voiceRect.contains(x, y)) return TARGET_VOICE
        for (i in 0 until minOf(suggestionRects.size, suggestions.size)) {
            if (suggestionRects[i].contains(x, minOf(y, stripPx - 1f))) return i
        }
        return TARGET_NONE
    }

    private fun stripUp(x: Float, y: Float) {
        val target = stripTarget
        stripTarget = TARGET_NONE
        invalidate()
        if (target != stripHit(x, minOf(y, stripPx - 1f)) && target != TARGET_SWAP) return
        when {
            target == TARGET_UNLOCK -> listener.onUnlock()
            target == TARGET_VOICE -> listener.onVoice()
            target == TARGET_SWAP -> listener.onSwapHand()
            target in suggestions.indices -> listener.onSuggestion(suggestions[target])
        }
    }

    /** Drops any touch in progress (layout change, keyboard hidden). */
    fun cancelTouch() {
        removeCallbacks(longPress)
        removeCallbacks(repeat)
        active = null
        activePointer = -1
        popup = null
        stripTarget = TARGET_NONE
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelTouch()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val LONG_PRESS_MS = 380L
        const val REPEAT_START_MS = 400L
        const val REPEAT_MS = 55L

        const val TARGET_NONE = -1
        const val TARGET_UNLOCK = -2
        const val TARGET_VOICE = -3
        const val TARGET_SWAP = -4

        /** Console-style ends: the first key of a function row rounds left, the last rounds right. */
        fun cornerOf(k: Key, index: Int, count: Int): Int = when {
            k.kind == Key.SPACE -> KeyboardLook.PILL
            k.kind == Key.CHAR -> KeyboardLook.SQUARE
            index == 0 -> KeyboardLook.LEFT
            index == count - 1 -> KeyboardLook.RIGHT
            else -> KeyboardLook.SQUARE
        }

        /** Accent slot per key kind: letters, function keys, space and enter each get their own colour. */
        fun colorOf(k: Key): Int = when (k.kind) {
            Key.CHAR -> KeyboardLook.accent(1)
            Key.SPACE -> KeyboardLook.accent(4)
            Key.ENTER -> KeyboardLook.accent(0)
            Key.DELETE -> KeyboardLook.accent(3)
            else -> KeyboardLook.accent(2)
        }

        @Suppress("DEPRECATION")
        fun navigationInset(insets: WindowInsets): Int =
            if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                insets.systemWindowInsetBottom
            }

        /**
         * The item whose rectangle is nearest to (x, y) (distance 0 inside it; the first wins a tie), or
         * null when the list is empty or the nearest is farther than `sqrt(limitSq)`.
         */
        fun <T> nearest(items: List<T>, x: Float, y: Float, limitSq: Float = Float.MAX_VALUE, rect: (T) -> RectF): T? {
            var best: T? = null
            var bestD = Float.MAX_VALUE
            for (item in items) {
                val r = rect(item)
                val dx = maxOf(0f, maxOf(r.left - x, x - r.right))
                val dy = maxOf(0f, maxOf(r.top - y, y - r.bottom))
                val d = dx * dx + dy * dy
                if (d < bestD) {
                    bestD = d
                    best = item
                }
            }
            return if (bestD <= limitSq) best else null
        }
    }
}
