package com.gungreeneyes.trekui.messages;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;

import com.gungreeneyes.trekui.MessagesLook;
import com.gungreeneyes.trekui.messages.data.PhoneNumbers;

import java.util.List;

/**
 * One row of the conversation list (or a search result), drawn on the Canvas in the active interface: a
 * coloured avatar cell with initials, the name, the time, a one-line preview (a saved draft shows first,
 * in the alert colour) and an unread / match-count badge.
 */
public final class ConversationRowView extends View {
    /** What a row shows. */
    public static final class Row {
        public long threadId;
        public List<String> addresses;
        public String title = "";
        public String snippet = "";
        public long dateMillis;
        public boolean unread;
        /** Saved draft text, or null. */
        public String draft;
        /** Badge text ("3 MATCHES"), or null. */
        public String badge;
    }

    private static final float HEIGHT_DP = 68f, PAD_DP = 8f;

    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint avatarPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final String draftLabel;
    private Row row;

    public ConversationRowView(Context c) {
        super(c);
        draftLabel = c.getString(R.string.draft_prefix);
        titlePaint.setTypeface(MessagesLook.font(c, true));
        titlePaint.setTextSize(MessagesLook.px(c, 19f));
        titlePaint.setLetterSpacing(MessagesLook.letterSpacing());
        textPaint.setTypeface(MessagesLook.font(c, false));
        textPaint.setTextSize(MessagesLook.px(c, 15f));
        smallPaint.setTypeface(MessagesLook.font(c, false));
        smallPaint.setTextSize(MessagesLook.px(c, 13f));
        avatarPaint.setTypeface(MessagesLook.font(c, true));
        avatarPaint.setTextSize(MessagesLook.px(c, 18f));
        avatarPaint.setTextAlign(Paint.Align.CENTER);
        setBackground(MessagesLook.touchArea(c, 8f));
        setFocusable(true);
    }

    public void bind(Row r) {
        row = r;
        StringBuilder d = new StringBuilder(r.title);
        if (r.unread) d.append(", ").append(getContext().getString(R.string.unread));
        if (r.draft != null) d.append(", ").append(draftLabel).append(' ').append(r.draft);
        else d.append(", ").append(r.snippet);
        setContentDescription(d);
        setTag("conversation_" + r.threadId);
        invalidate();
    }

    public Row row() {
        return row;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MessagesLook.dp(getContext(), HEIGHT_DP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (row == null) return;
        Context c = getContext();
        int w = getWidth(), h = getHeight();
        int pad = MessagesLook.dp(c, PAD_DP);
        int avatar = h - pad * 2;
        int accent = MessagesLook.accent(1 + (int) (Math.abs(row.threadId) % 5));

        Drawable cell = MessagesLook.cell(c, accent, MessagesLook.LEFT, 14f);
        cell.setBounds(0, pad, avatar, pad + avatar);
        cell.draw(canvas);
        avatarPaint.setColor(MessagesLook.ink());
        String ini = PhoneNumbers.initials(row.title);
        float cy = pad + avatar / 2f - (avatarPaint.descent() + avatarPaint.ascent()) / 2f;
        canvas.drawText(ini, avatar / 2f, cy, avatarPaint);

        float x = avatar + pad * 1.5f;
        String when = row.dateMillis > 0 ? DateUtils.getRelativeTimeSpanString(row.dateMillis,
                System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString() : "";
        smallPaint.setColor(MessagesLook.textLo());
        float whenW = smallPaint.measureText(when);
        float top = pad + titlePaint.getTextSize();
        canvas.drawText(when, w - whenW, top, smallPaint);

        titlePaint.setColor(row.unread ? MessagesLook.accent(0) : MessagesLook.textHi());
        CharSequence title = TextUtils.ellipsize(row.title, titlePaint, Math.max(0, w - x - whenW - pad), TextUtils.TruncateAt.END);
        canvas.drawText(title, 0, title.length(), x, top, titlePaint);

        float badgeW = 0;
        String badge = row.badge != null ? row.badge : row.unread ? "●" : null;
        float line2 = h - pad - textPaint.descent();
        if (badge != null) {
            badgeW = smallPaint.measureText(badge) + pad * 2;
            rect.set(w - badgeW, line2 - smallPaint.getTextSize() - pad / 2f, w, line2 + pad / 2f);
            fill.setColor(row.unread ? MessagesLook.alert() : MessagesLook.accent(2));
            canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, fill);
            smallPaint.setColor(MessagesLook.ink());
            canvas.drawText(badge, w - badgeW + pad, line2 - smallPaint.descent() / 2f, smallPaint);
        }

        float avail = Math.max(0, w - x - badgeW - pad);
        String prefix = row.draft != null ? draftLabel + " " : "";
        String body = row.draft != null ? row.draft : row.snippet;
        float px = x;
        if (!prefix.isEmpty()) {
            textPaint.setColor(MessagesLook.alert());
            canvas.drawText(prefix, px, line2, textPaint);
            float pw = textPaint.measureText(prefix);
            px += pw;
            avail -= pw;
        }
        textPaint.setColor(row.unread ? MessagesLook.textHi() : MessagesLook.textLo());
        CharSequence snip = TextUtils.ellipsize(body.replace('\n', ' '), textPaint, Math.max(0, avail), TextUtils.TruncateAt.END);
        canvas.drawText(snip, 0, snip.length(), px, line2, textPaint);

        fill.setColor(MessagesLook.alpha(MessagesLook.grid(), 0.8f));
        canvas.drawRect(x, h - 1, w, h, fill);
    }
}
