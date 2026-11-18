package com.gungreeneyes.trekui.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.format.DateUtils
import android.view.View
import androidx.core.graphics.withTranslation
import com.gungreeneyes.trekui.MessagesLook
import com.gungreeneyes.trekui.messages.data.MessageItem
import kotlin.math.ceil

/**
 * One message in a conversation, drawn on the Canvas: outgoing messages as filled cells on the right
 * (interface geometry, lettered in ink), incoming ones as outlined trays on the left, image attachments
 * above the text (thumbnails decoded in the background by [Images]), and a status line with the time,
 * SIM and delivery state. Widths follow the window: at most 78% of the row (62% on two-pane widths).
 */
internal class BubbleView(c: Context, private val wide: Boolean) : View(c) {
    private val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = Rect()
    private val bubble = RectF()

    /** The message drawn, or null before the first [bind]. */
    var item: MessageItem? = null
        private set
    private var simLabel = ""
    private var layout: StaticLayout? = null
    private val thumbs = ArrayList<Bitmap?>()
    private var bubbleW = 0
    private var bubbleH = 0

    init {
        bodyPaint.typeface = MessagesLook.font(c, false)
        bodyPaint.textSize = MessagesLook.px(c, 17f)
        statusPaint.typeface = MessagesLook.font(c, false)
        statusPaint.textSize = MessagesLook.px(c, 12f)
        background = MessagesLook.touchArea(c, 12f)
    }

    fun bind(m: MessageItem, sim: String?) {
        item = m
        simLabel = sim.orEmpty()
        thumbs.clear()
        val side = MessagesLook.dp(context, THUMB_DP)
        for (uri in m.images) {
            val b = Images.cached(uri, side)
            thumbs.add(b)
            if (b == null) {
                val index = thumbs.size - 1
                Images.load(context, uri, side) { bmp ->
                    if (item === m && index < thumbs.size) {
                        thumbs[index] = bmp
                        requestLayout()
                        invalidate()
                    }
                }
            }
        }
        contentDescription = (if (m.outgoing) context.getString(R.string.you) + ": " else "") + m.body
        tag = "message_" + m.uid
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val c = context
        val w = MeasureSpec.getSize(widthSpec)
        val pad = MessagesLook.dp(c, PAD_DP)
        val maxBubble = (w * (if (wide) 0.62f else 0.78f)).toInt()
        val textMax = maxOf(1, maxBubble - pad * 2)
        var contentW = 0
        var contentH = 0
        layout = null
        val body = item?.body.orEmpty()
        if (body.isNotEmpty()) {
            val natural = body.split("\n").maxOf { bodyPaint.measureText(it) }
            val lw = minOf(textMax, ceil(natural.toDouble()).toInt() + 1)
            val l = StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, maxOf(1, lw))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
            layout = l
            contentW = lw
            contentH = l.height
        }
        val gap = MessagesLook.dp(c, GAP_DP)
        for (b in thumbs) {
            val (tw, th) = thumbSize(c, b, textMax)
            contentW = maxOf(contentW, tw)
            contentH += th + gap
        }
        bubbleW = contentW + pad * 2
        bubbleH = contentH + pad * 2
        val status = (statusPaint.fontSpacing + gap).toInt()
        setMeasuredDimension(w, bubbleH + status + gap)
    }

    /** Drawn width and height of a thumbnail (or its placeholder while it loads). */
    private fun thumbSize(c: Context, b: Bitmap?, textMax: Int): Pair<Int, Int> =
        if (b != null) {
            val tw = minOf(textMax, MessagesLook.dp(c, THUMB_DP))
            tw to Math.round(tw * (b.height / b.width.toFloat()))
        } else {
            val tw = minOf(textMax, MessagesLook.dp(c, PLACEHOLDER_DP))
            tw to tw
        }

    override fun onDraw(canvas: Canvas) {
        val m = item ?: return
        val c = context
        val w = width
        val pad = MessagesLook.dp(c, PAD_DP)
        val gap = MessagesLook.dp(c, GAP_DP)
        val left = if (m.outgoing) (w - bubbleW).toFloat() else 0f
        bubble.set(left, gap / 2f, left + bubbleW, gap / 2f + bubbleH)

        val bg: Drawable
        val textColor: Int
        if (m.outgoing) {
            val color = if (m.state == MessageItem.State.FAILED) MessagesLook.alert() else MessagesLook.accent(0)
            bg = MessagesLook.cell(c, color, MessagesLook.RIGHT, 16f)
            textColor = MessagesLook.ink()
        } else {
            bg = MessagesLook.outline(c, MessagesLook.panel(), MessagesLook.accent(2), 16f, 1.5f)
            textColor = MessagesLook.textHi()
        }
        bg.setBounds(bubble.left.toInt(), bubble.top.toInt(), bubble.right.toInt(), bubble.bottom.toInt())
        bg.draw(canvas)

        var y = bubble.top + pad
        val textMax = bubbleW - pad * 2
        for (b in thumbs) {
            val (tw, th) = thumbSize(c, b, textMax)
            if (b != null) {
                dst.set((bubble.left + pad).toInt(), y.toInt(), (bubble.left + pad + tw).toInt(), y.toInt() + th)
                canvas.drawBitmap(b, null, dst, null)
            } else {
                placeholder.color = MessagesLook.alpha(MessagesLook.grid(), 0.9f)
                canvas.drawRect(bubble.left + pad, y, bubble.left + pad + tw, y + th, placeholder)
            }
            y += th + gap
        }
        layout?.let { l ->
            bodyPaint.color = textColor
            canvas.withTranslation(bubble.left + pad, y) { l.draw(this) }
        }

        val status = statusText(c, m)
        statusPaint.color = if (m.state == MessageItem.State.FAILED) MessagesLook.alert() else MessagesLook.textLo()
        val sw = statusPaint.measureText(status)
        val sx = if (m.outgoing) w - sw - pad / 2f else pad / 2f
        canvas.drawText(status, sx, bubble.bottom + gap + statusPaint.textSize, statusPaint)
    }

    private fun statusText(c: Context, m: MessageItem): String = buildString {
        var flags = DateUtils.FORMAT_SHOW_TIME
        if (!DateUtils.isToday(m.dateMillis)) flags = flags or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
        append(DateUtils.formatDateTime(c, m.dateMillis, flags))
        if (simLabel.isNotEmpty()) append("  •  ").append(simLabel)
        if (m.outgoing) {
            val res = when (m.state) {
                MessageItem.State.SENDING -> R.string.state_sending
                MessageItem.State.DELIVERED -> R.string.state_delivered
                MessageItem.State.FAILED -> R.string.state_failed
                else -> R.string.state_sent
            }
            append("  •  ").append(c.getString(res))
        }
    }

    private companion object {
        const val PAD_DP = 10f
        const val GAP_DP = 6f
        const val THUMB_DP = 220f
        const val PLACEHOLDER_DP = 140f
    }
}
