package com.gungreeneyes.trekui.lockscreen

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.view.animation.DecelerateInterpolator
import com.gungreeneyes.trekui.LockLook
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The wake screen, drawn entirely on a Canvas in the active interface: a console frame (two elbows and a
 * sidebar in the interface's geometry: rounded rails, chamfered plates, a square lattice or paper cards),
 * the big clock with date, decimal date, next alarm and battery, media controls, the redacted notification
 * list, and the CAMERA / TORCH / UNLOCK row.
 *
 * Layout: one column in portrait (clock, media, list, actions); in landscape the clock, media and actions
 * take the left column and the list the right. Sizes come from the interface's dp/sp scale, so phones and
 * tablets get the same proportions. The safe area (system bars, cutouts) is set by [setSafeInsets].
 *
 * Input: a swipe up anywhere (or a tap on the UNLOCK bar) asks to unlock; every control is a hit [Region].
 * The same regions are exposed to TalkBack as virtual views through an [AccessibilityNodeProvider], so the
 * Canvas-drawn controls are announced and clickable. The view never decides anything about security: it
 * only reports gestures to [Actions].
 */
class WakeView(context: Context, private val actions: Actions) : View(context) {
    /** What the wake screen's controls do (implemented by [WakeActivity]). */
    interface Actions {
        fun unlock()
        fun camera()
        fun torch()

        /** -1 previous, 0 play/pause, 1 next. */
        fun media(command: Int)
        fun openItem(item: LockItem)
        fun purchase()
    }

    /** Everything the view draws; filled by [WakeActivity]. */
    class Model {
        var time = ""
        var amPm = ""
        var date = ""
        var decimalDate = ""
        var alarm = ""
        var batteryPercent = -1
        var charging = false
        var hasMedia = false
        var mediaTitle = ""
        var mediaArtist = ""
        var mediaPlaying = false
        var torchAvailable = false
        var torchOn = false
        var trialChip = false
        var listenerOn = true
        var items: List<LockItem> = emptyList()
    }

    /** A tappable area with its spoken label. */
    internal class Region(val id: Int, r: RectF, val label: String, val action: () -> Unit) {
        val rect = RectF(r)
    }

    private var model = Model()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = RectF()
    private val safe = Rect()
    private val regions = ArrayList<Region>()
    private var heavy: Typeface? = null
    private var regular: Typeface? = null
    private var geometry = 0

    private var downX = 0f
    private var downY = 0f
    private var downRegion: Region? = null
    private var dragging = false
    private var dragOffset = 0f
    private var settle: ValueAnimator? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val provider = object : AccessibilityNodeProvider() {
        override fun createAccessibilityNodeInfo(virtualViewId: Int): AccessibilityNodeInfo? {
            if (virtualViewId == View.NO_ID) {
                @Suppress("DEPRECATION")
                val host = AccessibilityNodeInfo.obtain(this@WakeView)
                onInitializeAccessibilityNodeInfo(host)
                for (r in regions) host.addChild(this@WakeView, r.id)
                return host
            }
            val r = region(virtualViewId) ?: return null
            @Suppress("DEPRECATION")
            val n = AccessibilityNodeInfo.obtain(this@WakeView, virtualViewId)
            n.packageName = context.packageName
            n.className = "android.widget.Button"
            n.setParent(this@WakeView)
            n.contentDescription = r.label
            n.isClickable = true
            n.isEnabled = true
            n.isFocusable = true
            n.isVisibleToUser = true
            n.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
            val parent = Rect()
            r.rect.roundOut(parent)
            parent.offset(0, dragOffset.roundToInt())
            @Suppress("DEPRECATION")
            n.setBoundsInParent(parent)
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val screen = Rect(parent)
            screen.offset(loc[0], loc[1])
            n.setBoundsInScreen(screen)
            return n
        }

        override fun performAction(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (virtualViewId == View.NO_ID) return performAccessibilityAction(action, arguments)
            val r = region(virtualViewId)
            if (r == null || action != AccessibilityNodeInfo.ACTION_CLICK) return false
            r.action()
            return true
        }
    }

    init {
        stroke.style = Paint.Style.STROKE
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.wake_description)
        tag = "wake_view"
        reloadLook()
    }

    /** Re-reads the interface (fonts, geometry, colours) and redraws. */
    fun reloadLook() {
        val c = context
        LockLook.load(c)
        heavy = LockLook.font(c, true)
        regular = LockLook.font(c, false)
        geometry = LockLook.geometry()
        invalidate()
    }

    /** Replaces the model and redraws. */
    fun setModel(m: Model?) {
        model = m ?: Model()
        invalidate()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }

    /** The system bar / cutout insets to keep the content clear of. */
    fun setSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        safe.set(left, top, right, bottom)
        invalidate()
    }

    private fun dp(v: Float): Float = LockLook.dp(context, v).toFloat()

    private fun sp(v: Float): Float = LockLook.px(context, v)

    private fun s(id: Int): String = context.getString(id)

    // ---- drawing ----------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        LockLook.load(context)
        regions.clear()
        canvas.drawColor(LockLook.bg())
        val w = width
        val h = height
        if (w == 0 || h == 0) return

        val margin = dp(10f)
        val area = RectF(safe.left + margin, safe.top + margin, w - safe.right - margin, h - safe.bottom - margin)
        val landscape = area.width() > area.height() * 1.15f
        val side = min(dp(if (landscape) 56f else 64f), area.width() * 0.16f)
        val bar = dp(18f)
        val gap = dp(6f)

        canvas.save()
        canvas.translate(0f, dragOffset)

        // frame: top elbow down to 40% of the height, bottom elbow from just below to the bottom
        val split = area.top + area.height() * (if (landscape) 0.48f else 0.40f)
        drawElbow(canvas, area.left, area.top, area.right, split - gap / 2f, side, bar, LockLook.accent(0), true)
        drawElbow(canvas, area.left, split + gap / 2f, area.right, area.bottom, side, bar, LockLook.accent(2), false)
        sidebarLabel(
            canvas, if (model.batteryPercent >= 0) model.batteryPercent.toString() else "--",
            area.left, split - gap / 2f, side,
        )
        sidebarLabel(canvas, "%02d".format(Locale.ROOT, min(99, model.items.size)), area.left, area.bottom, side)

        val content = RectF(area.left + side + dp(12f), area.top + bar + dp(10f), area.right, area.bottom - bar - dp(10f))
        val actionsH = max(dp(52f), LockLook.MIN_TOUCH_DP * resources.displayMetrics.density)

        if (landscape) {
            val colW = content.width() * 0.46f
            val left = RectF(content.left, content.top, content.left + colW, content.bottom - actionsH - gap)
            val right = RectF(left.right + dp(14f), content.top, content.right, content.bottom)
            val y = drawClock(canvas, left)
            if (model.hasMedia) {
                drawMedia(canvas, RectF(left.left, y + gap, left.right, min(left.bottom, y + gap + dp(64f))))
            }
            drawActions(canvas, RectF(content.left, content.bottom - actionsH, left.right, content.bottom))
            drawList(canvas, right)
        } else {
            var y = drawClock(canvas, content)
            if (model.hasMedia) {
                drawMedia(canvas, RectF(content.left, y + gap, content.right, y + gap + dp(64f)))
                y += gap + dp(64f)
            }
            val actionsRect = RectF(content.left, content.bottom - actionsH, content.right, content.bottom)
            drawList(canvas, RectF(content.left, y + dp(12f), content.right, actionsRect.top - gap * 2))
            drawActions(canvas, actionsRect)
        }

        if (model.trialChip) drawTrialChip(canvas, area, bar)
        canvas.restore()
    }

    /**
     * One frame elbow: a sidebar of width [side] joined to a bar of height [bar] by a curve, chamfer or
     * square corner. [topCorner] true draws the corner at the top-left, false mirrors it to the bottom-left.
     */
    private fun drawElbow(
        c: Canvas, left: Float, top: Float, right: Float, bottom: Float, side: Float, bar: Float,
        color: Int, topCorner: Boolean,
    ) {
        val end = dp(34f)
        val gap = dp(6f)
        val elbowRight = right - end - gap
        c.save()
        if (!topCorner) c.scale(1f, -1f, 0f, (top + bottom) / 2f)
        val outer = min(side, (bottom - top) / 2f)
        val inner = min(bar, side / 2f)
        path.reset()
        path.moveTo(left, bottom)
        when (geometry) {
            LockLook.GEOMETRY_CHAMFER -> {
                path.lineTo(left, top + outer * 0.6f)
                path.lineTo(left + outer * 0.6f, top)
                path.lineTo(elbowRight, top)
                path.lineTo(elbowRight, top + bar)
                path.lineTo(left + side + inner, top + bar)
                path.lineTo(left + side, top + bar + inner)
            }
            LockLook.GEOMETRY_SQUARE -> {
                path.lineTo(left, top)
                path.lineTo(elbowRight, top)
                path.lineTo(elbowRight, top + bar)
                path.lineTo(left + side, top + bar)
            }
            else -> {
                val r = outer
                path.lineTo(left, top + r)
                path.arcTo(RectF(left, top, left + 2 * r, top + 2 * r), 180f, 90f, false)
                path.lineTo(elbowRight, top)
                path.lineTo(elbowRight, top + bar)
                path.lineTo(left + side + inner, top + bar)
                path.arcTo(RectF(left + side, top + bar, left + side + 2 * inner, top + bar + 2 * inner), 270f, -90f, false)
            }
        }
        path.lineTo(left + side, bottom)
        path.close()
        paintShape(c, path, color)

        // the bar's end cap
        tmp.set(right - end, top, right, top + bar)
        path.reset()
        when (geometry) {
            LockLook.GEOMETRY_ROUND -> path.addRoundRect(
                tmp, floatArrayOf(0f, 0f, bar / 2, bar / 2, bar / 2, bar / 2, 0f, 0f), Path.Direction.CW,
            )
            LockLook.GEOMETRY_CHAMFER -> {
                path.moveTo(tmp.left, tmp.top)
                path.lineTo(tmp.right - bar * 0.6f, tmp.top)
                path.lineTo(tmp.right, tmp.bottom)
                path.lineTo(tmp.left, tmp.bottom)
                path.close()
            }
            else -> path.addRect(tmp, Path.Direction.CW)
        }
        paintShape(c, path, LockLook.accent(if (topCorner) 1 else 3))
        c.restore()
    }

    /** Fills (rails, plates) or strokes (lattice) a frame shape in the interface's manner. */
    private fun paintShape(c: Canvas, p: Path, color: Int) {
        if (geometry == LockLook.GEOMETRY_SQUARE) {
            fill.color = LockLook.alpha(color, 0.18f)
            c.drawPath(p, fill)
            stroke.color = color
            stroke.strokeWidth = dp(1.5f)
            c.drawPath(p, stroke)
        } else {
            fill.color = color
            c.drawPath(p, fill)
        }
    }

    /** A small number at the bottom of a sidebar segment (battery percent, notification count). */
    private fun sidebarLabel(c: Canvas, label: String, left: Float, bottom: Float, side: Float) {
        text.typeface = heavy
        text.textSize = sp(15f)
        text.textAlign = Paint.Align.RIGHT
        text.color = if (geometry == LockLook.GEOMETRY_ROUND || geometry == LockLook.GEOMETRY_CHAMFER) {
            LockLook.ink()
        } else {
            LockLook.textHi()
        }
        c.drawText(label, left + side - dp(6f), bottom - dp(6f), text)
        text.textAlign = Paint.Align.LEFT
    }

    /** Clock, date, decimal date, alarm and battery; returns the y below the block. */
    private fun drawClock(c: Canvas, r: RectF): Float {
        var clockSize = min(r.width() * 0.30f, max(sp(56f), r.height() * 0.30f))
        clockSize = min(clockSize, sp(132f))
        text.typeface = heavy
        text.letterSpacing = LockLook.letterSpacing()
        text.textSize = clockSize
        text.color = LockLook.accent(0)
        val fm = text.fontMetrics
        val baseline = r.top - fm.ascent * 0.92f
        c.drawText(model.time, r.left, baseline, text)
        if (model.amPm.isNotEmpty()) {
            val tw = text.measureText(model.time)
            text.textSize = clockSize * 0.28f
            c.drawText(model.amPm, r.left + tw + dp(8f), baseline, text)
        }
        var y = baseline + fm.descent * 0.6f

        y = line(c, model.date, r, y, 22f, LockLook.textHi(), true)
        y = line(c, s(R.string.wake_decimal_label) + "  " + model.decimalDate, r, y, 15f, LockLook.accent(1), false)
        val power = if (model.batteryPercent < 0) {
            ""
        } else {
            s(R.string.wake_power) + " " + model.batteryPercent + "%" +
                (if (model.charging) "  •  " + s(R.string.wake_charging) else "")
        }
        val status = if (model.alarm.isEmpty()) {
            power
        } else {
            power + (if (power.isEmpty()) "" else "   ") + s(R.string.wake_alarm) + " " + model.alarm
        }
        if (status.isNotEmpty()) y = line(c, status, r, y, 15f, LockLook.textLo(), false)
        return y
    }

    /** One ellipsized text line under [y]; returns the new y. */
    private fun line(c: Canvas, s: String, r: RectF, y: Float, sizeSp: Float, color: Int, bold: Boolean): Float {
        text.typeface = if (bold) heavy else regular
        text.textSize = sp(sizeSp)
        text.color = color
        val fm = text.fontMetrics
        val base = y - fm.ascent + dp(2f)
        val fit = TextUtils.ellipsize(s, text, r.width(), TextUtils.TruncateAt.END)
        c.drawText(fit, 0, fit.length, r.left, base, text)
        return base + fm.descent
    }

    /** The media card: title, artist and PREVIOUS / PLAY-PAUSE / NEXT keys. */
    private fun drawMedia(c: Canvas, r: RectF) {
        cell(c, r, LockLook.surface(), LockLook.accent(4), true)
        val key = min(r.height() - dp(12f), dp(48f))
        val keysW = key * 3 + dp(8f) * 2
        val textArea = RectF(r.left + dp(12f), r.top + dp(4f), r.right - keysW - dp(16f), r.bottom)
        val y = line(c, model.mediaTitle, textArea, r.top + dp(2f), 16f, LockLook.textHi(), true)
        line(c, model.mediaArtist, textArea, y, 13f, LockLook.textLo(), false)
        var x = r.right - dp(8f) - keysW
        val top = r.centerY() - key / 2f
        val cmds = intArrayOf(-1, 0, 1)
        val labels = arrayOf(
            s(R.string.wake_media_previous),
            s(if (model.mediaPlaying) R.string.wake_media_pause else R.string.wake_media_play),
            s(R.string.wake_media_next),
        )
        for (i in 0 until 3) {
            val k = RectF(x, top, x + key, top + key)
            val color = LockLook.accent(if (i == 1) 0 else 3)
            cell(c, k, color, color, false)
            glyph(c, k, cmds[i], LockLook.ink())
            val cmd = cmds[i]
            regions.add(Region(100 + i, k, labels[i]) { actions.media(cmd) })
            x += key + dp(8f)
        }
    }

    /** Previous / play-pause / next symbols drawn as shapes (no glyph font needed). */
    private fun glyph(c: Canvas, k: RectF, cmd: Int, color: Int) {
        fill.color = color
        val s = k.width() * 0.22f
        val cx = k.centerX()
        val cy = k.centerY()
        path.reset()
        if (cmd == 0 && model.mediaPlaying) {
            c.drawRect(cx - s, cy - s, cx - s * 0.3f, cy + s, fill)
            c.drawRect(cx + s * 0.3f, cy - s, cx + s, cy + s, fill)
            return
        }
        if (cmd == 0) {
            path.moveTo(cx - s * 0.7f, cy - s)
            path.lineTo(cx + s, cy)
            path.lineTo(cx - s * 0.7f, cy + s)
        } else {
            val dir = cmd.toFloat()
            path.moveTo(cx - dir * s, cy - s)
            path.lineTo(cx + dir * s * 0.4f, cy)
            path.lineTo(cx - dir * s, cy + s)
            path.close()
            c.drawPath(path, fill)
            val bx = cx + dir * s * 0.55f
            c.drawRect(min(bx, bx + dir * s * 0.3f), cy - s, max(bx, bx + dir * s * 0.3f), cy + s, fill)
            return
        }
        path.close()
        c.drawPath(path, fill)
    }

    /** The notification list: one row per item until the space is used, then a "+N MORE" line. */
    private fun drawList(c: Canvas, r: RectF) {
        if (r.height() < dp(40f)) return
        text.typeface = heavy
        text.textSize = sp(13f)
        text.color = LockLook.accent(1)
        val hm = text.fontMetrics
        var y = r.top - hm.ascent
        c.drawText(s(R.string.wake_notifications_header), r.left, y, text)
        stroke.color = LockLook.grid()
        stroke.strokeWidth = dp(1f)
        y += hm.descent + dp(4f)
        c.drawLine(r.left, y, r.right, y, stroke)
        y += dp(6f)

        val items = model.items
        if (items.isEmpty()) {
            line(
                c, s(if (model.listenerOn) R.string.wake_no_notifications else R.string.wake_access_off),
                r, y, 15f, LockLook.textLo(), false,
            )
            return
        }
        val rowH = dp(58f)
        val fit = max(0f, floor((r.bottom - y) / rowH)).toInt()
        val shown = if (items.size > fit) max(0, fit - 1) else items.size
        for (i in 0 until shown) {
            val row = RectF(r.left, y, r.right, y + rowH - dp(6f))
            drawItem(c, row, items[i], i)
            y += rowH
        }
        if (shown < items.size && fit > 0) {
            line(c, context.getString(R.string.wake_more, items.size - shown), r, y, 14f, LockLook.textLo(), true)
        }
    }

    private fun drawItem(c: Canvas, row: RectF, item: LockItem, index: Int) {
        val accent = if (item.color != 0) (OPAQUE or item.color) else LockLook.accent(index + 1)
        tmp.set(row.left, row.top, row.left + dp(10f), row.bottom)
        cell(c, tmp, accent, accent, false)
        val t = RectF(row.left + dp(18f), row.top, row.right - dp(4f), row.bottom)
        val whenText = android.text.format.DateFormat.getTimeFormat(context).format(Date(item.postTime))
        text.typeface = regular
        text.textSize = sp(12f)
        val whenW = text.measureText(whenText)
        text.color = LockLook.textLo()
        c.drawText(whenText, t.right - whenW, t.top - text.fontMetrics.ascent, text)
        val head = RectF(t.left, t.top, t.right - whenW - dp(8f), t.bottom)
        var y = line(c, LockLook.up(context, item.appName), head, t.top - dp(2f), 12f, accent, true)
        var title = item.title
        val body = item.text
        if (item.redacted() && title.isEmpty() && body.isEmpty()) title = s(R.string.wake_contents_hidden)
        if (title.isNotEmpty()) y = line(c, title, t, y, 15f, LockLook.textHi(), false)
        if (body.isNotEmpty()) line(c, body, t, y, 13f, LockLook.textLo(), false)
        val spoken = item.appName + ". " + title + (if (body.isEmpty()) "" else ". $body")
        regions.add(Region(200 + index, row, spoken) { actions.openItem(item) })
    }

    /** CAMERA, TORCH and the UNLOCK bar. */
    private fun drawActions(c: Canvas, r: RectF) {
        val g = dp(8f)
        val small = min(r.width() * 0.24f, dp(104f))
        val cam = RectF(r.left, r.top, r.left + small, r.bottom)
        val torch = RectF(cam.right + g, r.top, cam.right + g + small, r.bottom)
        val unlock = RectF((if (model.torchAvailable) torch.right else cam.right) + g, r.top, r.right, r.bottom)

        actionKey(c, cam, s(R.string.wake_camera), LockLook.accent(3), false)
        regions.add(Region(1, cam, s(R.string.wake_camera_spoken), actions::camera))
        if (model.torchAvailable) {
            actionKey(
                c, torch, s(if (model.torchOn) R.string.wake_torch_on else R.string.wake_torch),
                if (model.torchOn) LockLook.alert() else LockLook.accent(4), model.torchOn,
            )
            regions.add(
                Region(
                    2, torch, s(if (model.torchOn) R.string.wake_torch_off_spoken else R.string.wake_torch_on_spoken),
                    actions::torch,
                ),
            )
        }
        actionKey(c, unlock, s(R.string.wake_unlock_hint), LockLook.accent(0), true)
        regions.add(Region(3, unlock, s(R.string.wake_unlock_spoken), actions::unlock))
    }

    private fun actionKey(c: Canvas, r: RectF, label: String, color: Int, wide: Boolean) {
        cell(c, r, color, color, wide)
        text.typeface = heavy
        text.textSize = sp(15f)
        text.color = LockLook.ink()
        text.textAlign = Paint.Align.CENTER
        val fit = TextUtils.ellipsize(LockLook.up(context, label), text, r.width() - dp(12f), TextUtils.TruncateAt.END)
        val fm = text.fontMetrics
        c.drawText(fit, 0, fit.length, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, text)
        text.textAlign = Paint.Align.LEFT
    }

    /** The trial-ended chip: opens the purchase screen, but only after the device is unlocked. */
    private fun drawTrialChip(c: Canvas, area: RectF, bar: Float) {
        text.typeface = heavy
        text.textSize = sp(12f)
        val label = s(R.string.wake_trial_chip)
        val w = text.measureText(label) + dp(20f)
        val h = max(dp(28f), bar + dp(8f))
        val chip = RectF(area.right - w, area.top + bar + dp(8f), area.right, area.top + bar + dp(8f) + h)
        cell(c, chip, LockLook.alert(), LockLook.alert(), true)
        text.color = LockLook.ink()
        text.textAlign = Paint.Align.CENTER
        val fm = text.fontMetrics
        c.drawText(label, chip.centerX(), chip.centerY() - (fm.ascent + fm.descent) / 2f, text)
        text.textAlign = Paint.Align.LEFT
        // a bigger touch target than the chip itself
        val hit = RectF(chip)
        hit.inset(0f, -dp(8f))
        regions.add(Region(4, hit, s(R.string.wake_trial_spoken), actions::purchase))
    }

    /** A cell in the interface's geometry: pill or rounded (rails), chamfered, square lattice or paper card. */
    private fun cell(c: Canvas, r: RectF, fillColor: Int, edge: Int, pill: Boolean) {
        path.reset()
        when (geometry) {
            LockLook.GEOMETRY_CHAMFER -> {
                val k = min(r.height(), r.width()) * 0.28f
                path.moveTo(r.left + k, r.top)
                path.lineTo(r.right, r.top)
                path.lineTo(r.right, r.bottom - k)
                path.lineTo(r.right - k, r.bottom)
                path.lineTo(r.left, r.bottom)
                path.lineTo(r.left, r.top + k)
                path.close()
                fill.color = fillColor
                c.drawPath(path, fill)
            }
            LockLook.GEOMETRY_SQUARE -> {
                fill.color = LockLook.alpha(fillColor, if (fillColor == LockLook.surface()) 1f else 0.22f)
                c.drawRect(r, fill)
                stroke.color = edge
                stroke.strokeWidth = dp(1.5f)
                c.drawRect(r, stroke)
            }
            else -> {
                val rad = if (pill) r.height() / 2f else min(dp(6f), r.height() / 2f)
                fill.color = fillColor
                c.drawRoundRect(r, rad, rad, fill)
            }
        }
    }

    // ---- touch ------------------------------------------------------------------------------------

    private fun hit(x: Float, y: Float): Region? = regions.lastOrNull { it.rect.contains(x, y - dragOffset) }

    /** Distance a swipe up has to travel to unlock. */
    private fun unlockDistance(): Float = min(dp(140f), height * 0.2f)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settle?.cancel()
                downX = e.x
                downY = e.y
                downRegion = hit(downX, downY)
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.y - downY
                if (!dragging && dy < -touchSlop && abs(dy) > abs(e.x - downX)) dragging = true
                if (dragging) {
                    dragOffset = max(-height.toFloat(), min(0f, dy))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val down = downRegion
                if (dragging) {
                    val unlock = -dragOffset >= unlockDistance()
                    settleBack()
                    if (unlock) actions.unlock()
                } else if (down != null && down === hit(e.x, e.y)) {
                    performClick()
                    LockLook.tap(this)
                    down.action()
                }
                downRegion = null
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                settleBack()
                downRegion = null
                dragging = false
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun settleBack() {
        if (dragOffset == 0f) return
        val anim = ValueAnimator.ofFloat(dragOffset, 0f)
        settle = anim
        anim.duration = 220
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { a ->
            dragOffset = a.animatedValue as Float
            invalidate()
        }
        anim.start()
    }

    // ---- accessibility: the drawn controls as virtual views ---------------------------------------

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider? = provider

    private fun region(id: Int): Region? = regions.firstOrNull { it.id == id }

    private companion object {
        /** Opaque alpha forced onto a notification's own accent colour. */
        const val OPAQUE = 0xFF000000.toInt()
    }
}
