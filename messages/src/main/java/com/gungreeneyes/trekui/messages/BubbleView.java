package com.gungreeneyes.trekui.messages;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.format.DateUtils;
import android.view.View;

import com.gungreeneyes.trekui.MessagesLook;
import com.gungreeneyes.trekui.messages.data.MessageItem;

import java.util.ArrayList;
import java.util.List;

/**
 * One message in a conversation, drawn on the Canvas: outgoing messages as filled cells on the right
 * (interface geometry, lettered in ink), incoming ones as outlined trays on the left, image attachments
 * above the text (thumbnails decoded in the background by {@link Images}), and a status line with the time,
 * SIM and delivery state. Widths follow the window: at most 78% of the row (62% on two-pane widths).
 */
public final class BubbleView extends View {
    private static final float PAD_DP = 10f, GAP_DP = 6f, THUMB_DP = 220f, PLACEHOLDER_DP = 140f;

    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint statusPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private final RectF bubble = new RectF();
    private final boolean wide;
    private MessageItem item;
    private String simLabel = "";
    private StaticLayout layout;
    private final List<Bitmap> thumbs = new ArrayList<>();
    private int bubbleW, bubbleH;

    public BubbleView(Context c, boolean wide) {
        super(c);
        this.wide = wide;
        bodyPaint.setTypeface(MessagesLook.font(c, false));
        bodyPaint.setTextSize(MessagesLook.px(c, 17f));
        statusPaint.setTypeface(MessagesLook.font(c, false));
        statusPaint.setTextSize(MessagesLook.px(c, 12f));
        setBackground(MessagesLook.touchArea(c, 12f));
    }

    public void bind(MessageItem m, String sim) {
        item = m;
        simLabel = sim == null ? "" : sim;
        thumbs.clear();
        int side = MessagesLook.dp(getContext(), THUMB_DP);
        for (String uri : m.images) {
            Bitmap b = Images.cached(uri, side);
            thumbs.add(b);
            if (b == null) {
                final MessageItem bound = m;
                final int index = thumbs.size() - 1;
                Images.load(getContext(), uri, side, bmp -> {
                    if (item == bound && index < thumbs.size()) {
                        thumbs.set(index, bmp);
                        requestLayout();
                        invalidate();
                    }
                });
            }
        }
        setContentDescription((m.outgoing ? getContext().getString(R.string.you) + ": " : "") + m.body);
        setTag("message_" + m.uid());
        requestLayout();
        invalidate();
    }

    public MessageItem item() {
        return item;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        Context c = getContext();
        int w = MeasureSpec.getSize(widthSpec);
        int pad = MessagesLook.dp(c, PAD_DP);
        int maxBubble = (int) (w * (wide ? 0.62f : 0.78f));
        int textMax = Math.max(1, maxBubble - pad * 2);
        int contentW = 0, contentH = 0;
        layout = null;
        if (item != null && !item.body.isEmpty()) {
            float natural = 0;
            for (String line : item.body.split("\n")) natural = Math.max(natural, bodyPaint.measureText(line));
            int lw = Math.min(textMax, (int) Math.ceil(natural) + 1);
            layout = StaticLayout.Builder.obtain(item.body, 0, item.body.length(), bodyPaint, Math.max(1, lw))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build();
            contentW = lw;
            contentH = layout.getHeight();
        }
        int gap = MessagesLook.dp(c, GAP_DP);
        for (Bitmap b : thumbs) {
            int tw = textMax, th;
            if (b != null) {
                tw = Math.min(textMax, MessagesLook.dp(c, THUMB_DP));
                th = Math.round(tw * (b.getHeight() / (float) b.getWidth()));
            } else {
                tw = Math.min(textMax, MessagesLook.dp(c, PLACEHOLDER_DP));
                th = tw;
            }
            contentW = Math.max(contentW, tw);
            contentH += th + gap;
        }
        bubbleW = contentW + pad * 2;
        bubbleH = contentH + pad * 2;
        int status = (int) (statusPaint.getFontSpacing() + gap);
        setMeasuredDimension(w, bubbleH + status + gap);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (item == null) return;
        Context c = getContext();
        int w = getWidth();
        int pad = MessagesLook.dp(c, PAD_DP);
        int gap = MessagesLook.dp(c, GAP_DP);
        float left = item.outgoing ? w - bubbleW : 0;
        bubble.set(left, gap / 2f, left + bubbleW, gap / 2f + bubbleH);

        Drawable bg;
        int textColor;
        if (item.outgoing) {
            int color = item.state == MessageItem.State.FAILED ? MessagesLook.alert() : MessagesLook.accent(0);
            bg = MessagesLook.cell(c, color, MessagesLook.RIGHT, 16f);
            textColor = MessagesLook.ink();
        } else {
            bg = MessagesLook.outline(c, MessagesLook.panel(), MessagesLook.accent(2), 16f, 1.5f);
            textColor = MessagesLook.textHi();
        }
        bg.setBounds((int) bubble.left, (int) bubble.top, (int) bubble.right, (int) bubble.bottom);
        bg.draw(canvas);

        float y = bubble.top + pad;
        int textMax = bubbleW - pad * 2;
        for (Bitmap b : thumbs) {
            int tw, th;
            if (b != null) {
                tw = Math.min(textMax, MessagesLook.dp(c, THUMB_DP));
                th = Math.round(tw * (b.getHeight() / (float) b.getWidth()));
                dst.set((int) (bubble.left + pad), (int) y, (int) (bubble.left + pad + tw), (int) y + th);
                canvas.drawBitmap(b, null, dst, null);
            } else {
                tw = Math.min(textMax, MessagesLook.dp(c, PLACEHOLDER_DP));
                th = tw;
                placeholder.setColor(MessagesLook.alpha(MessagesLook.grid(), 0.9f));
                canvas.drawRect(bubble.left + pad, y, bubble.left + pad + tw, y + th, placeholder);
            }
            y += th + gap;
        }
        if (layout != null) {
            bodyPaint.setColor(textColor);
            canvas.save();
            canvas.translate(bubble.left + pad, y);
            layout.draw(canvas);
            canvas.restore();
        }

        String status = statusText(c);
        statusPaint.setColor(item.state == MessageItem.State.FAILED ? MessagesLook.alert() : MessagesLook.textLo());
        float sw = statusPaint.measureText(status);
        float sx = item.outgoing ? w - sw - pad / 2f : pad / 2f;
        canvas.drawText(status, sx, bubble.bottom + gap + statusPaint.getTextSize(), statusPaint);
    }

    private String statusText(Context c) {
        StringBuilder sb = new StringBuilder();
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (!DateUtils.isToday(item.dateMillis)) flags |= DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH;
        sb.append(DateUtils.formatDateTime(c, item.dateMillis, flags));
        if (!simLabel.isEmpty()) sb.append("  •  ").append(simLabel);
        if (item.outgoing) {
            int res;
            switch (item.state) {
                case SENDING: res = R.string.state_sending; break;
                case DELIVERED: res = R.string.state_delivered; break;
                case FAILED: res = R.string.state_failed; break;
                default: res = R.string.state_sent;
            }
            sb.append("  •  ").append(c.getString(res));
        }
        return sb.toString();
    }
}
