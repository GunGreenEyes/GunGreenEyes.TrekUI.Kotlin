package com.gungreeneyes.trekui.messages

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.View
import com.gungreeneyes.trekui.MessagesLook
import com.gungreeneyes.trekui.messages.data.PhoneNumbers
import kotlin.math.abs

/**
 * One row of the conversation list (or a search result), drawn on the Canvas in the active interface: a
 * coloured avatar cell with initials, the name, the time, a one-line preview (a saved draft shows first,
 * in the alert colour) and an unread / match-count badge.
 */
internal class ConversationRowView(c: Context) : View(c) {
    /**
     * What a row shows.
     *
     * @property draft saved draft text, or null
     * @property badge badge text ("3 MATCHES"), or null
     */
    data class Row(
        val threadId: Long,
        val addresses: List<String>,
        val title: String = "",
        val snippet: String = "",
        val dateMillis: Long = 0,
        val unread: Boolean = false,
        val draft: String? = null,
        val badge: String? = null,
    )

    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val avatarPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val draftLabel: String = c.getString(R.string.draft_prefix)

    /** The row drawn, or null before the first [bind]. */
    var row: Row? = null
        private set

    init {
        titlePaint.typeface = MessagesLook.font(c, true)
        titlePaint.textSize = MessagesLook.px(c, 19f)
        titlePaint.letterSpacing = MessagesLook.letterSpacing()
        textPaint.typeface = MessagesLook.font(c, false)
        textPaint.textSize = MessagesLook.px(c, 15f)
        smallPaint.typeface = MessagesLook.font(c, false)
        smallPaint.textSize = MessagesLook.px(c, 13f)
        avatarPaint.typeface = MessagesLook.font(c, true)
        avatarPaint.textSize = MessagesLook.px(c, 18f)
        avatarPaint.textAlign = Paint.Align.CENTER
        background = MessagesLook.touchArea(c, 8f)
        isFocusable = true
    }

    fun bind(r: Row) {
        row = r
        contentDescription = buildString {
            append(r.title)
            if (r.unread) append(", ").append(context.getString(R.string.unread))
            if (r.draft != null) append(", ").append(draftLabel).append(' ').append(r.draft)
            else append(", ").append(r.snippet)
        }
        tag = "conversation_" + r.threadId
        invalidate()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MessagesLook.dp(context, HEIGHT_DP))
    }

    override fun onDraw(canvas: Canvas) {
        val row = row ?: return
        val c = context
        val w = width
        val h = height
        val pad = MessagesLook.dp(c, PAD_DP)
        val avatar = h - pad * 2
        val accent = MessagesLook.accent(1 + (abs(row.threadId) % 5).toInt())

        val cell = MessagesLook.cell(c, accent, MessagesLook.LEFT, 14f)
        cell.setBounds(0, pad, avatar, pad + avatar)
        cell.draw(canvas)
        avatarPaint.color = MessagesLook.ink()
        val ini = PhoneNumbers.initials(row.title)
        val cy = pad + avatar / 2f - (avatarPaint.descent() + avatarPaint.ascent()) / 2f
        canvas.drawText(ini, avatar / 2f, cy, avatarPaint)

        val x = avatar + pad * 1.5f
        val whenText = if (row.dateMillis > 0) {
            DateUtils.getRelativeTimeSpanString(
                row.dateMillis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
            ).toString()
        } else {
            ""
        }
        smallPaint.color = MessagesLook.textLo()
        val whenW = smallPaint.measureText(whenText)
        val top = pad + titlePaint.textSize
        canvas.drawText(whenText, w - whenW, top, smallPaint)

        titlePaint.color = if (row.unread) MessagesLook.accent(0) else MessagesLook.textHi()
        val title = TextUtils.ellipsize(row.title, titlePaint, maxOf(0f, w - x - whenW - pad), TextUtils.TruncateAt.END)
        canvas.drawText(title, 0, title.length, x, top, titlePaint)

        var badgeW = 0f
        val badge = row.badge ?: if (row.unread) "●" else null
        val line2 = h - pad - textPaint.descent()
        if (badge != null) {
            badgeW = smallPaint.measureText(badge) + pad * 2
            rect.set(w - badgeW, line2 - smallPaint.textSize - pad / 2f, w.toFloat(), line2 + pad / 2f)
            fill.color = if (row.unread) MessagesLook.alert() else MessagesLook.accent(2)
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill)
            smallPaint.color = MessagesLook.ink()
            canvas.drawText(badge, w - badgeW + pad, line2 - smallPaint.descent() / 2f, smallPaint)
        }

        var avail = maxOf(0f, w - x - badgeW - pad)
        val prefix = if (row.draft != null) "$draftLabel " else ""
        val body = row.draft ?: row.snippet
        var px = x
        if (prefix.isNotEmpty()) {
            textPaint.color = MessagesLook.alert()
            canvas.drawText(prefix, px, line2, textPaint)
            val pw = textPaint.measureText(prefix)
            px += pw
            avail -= pw
        }
        textPaint.color = if (row.unread) MessagesLook.textHi() else MessagesLook.textLo()
        val snip = TextUtils.ellipsize(body.replace('\n', ' '), textPaint, maxOf(0f, avail), TextUtils.TruncateAt.END)
        canvas.drawText(snip, 0, snip.length, px, line2, textPaint)

        fill.color = MessagesLook.alpha(MessagesLook.grid(), 0.8f)
        canvas.drawRect(x, (h - 1).toFloat(), w.toFloat(), h.toFloat(), fill)
    }

    private companion object {
        const val HEIGHT_DP = 68f
        const val PAD_DP = 8f
    }
}
