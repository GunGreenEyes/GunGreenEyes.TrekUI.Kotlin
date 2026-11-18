package com.gungreeneyes.trekui.messages;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/**
 * Required default-SMS component: receives SMS_DELIVER (sent only to the default SMS app), joins the
 * parts of a long message, drops blocked senders, writes the message to the inbox (nobody else will) and
 * notifies.
 */
public final class SmsDeliverReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) return;
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0) return;
        StringBuilder body = new StringBuilder();
        String address = null;
        long sent = 0;
        for (SmsMessage p : parts) {
            if (p == null) continue;
            if (address == null) address = p.getDisplayOriginatingAddress();
            if (sent == 0) sent = p.getTimestampMillis();
            String b = p.getDisplayMessageBody();
            if (b != null) body.append(b);
        }
        if (address == null) return;
        final String from = address;
        final String text = body.toString();
        final long sentMillis = sent;
        final int subId = Sims.subIdFrom(intent);
        final Context app = context.getApplicationContext();
        Background.run(goAsync(), () -> {
            if (Blocking.isBlocked(app, from)) return;
            Uri row = MessageStore.insertIncoming(app, from, text, sentMillis, subId);
            long thread = MessageStore.threadOf(app, row, false);
            if (thread > 0 && !ThreadActivity.isShowing(thread)) Notifier.showThread(app, thread);
        });
    }
}
