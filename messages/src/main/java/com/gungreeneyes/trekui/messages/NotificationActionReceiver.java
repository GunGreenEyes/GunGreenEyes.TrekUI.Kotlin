package com.gungreeneyes.trekui.messages;

import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

/**
 * Notification actions: REPLY (inline RemoteInput text sent as SMS from the conversation's SIM, then the
 * conversation is marked read) and MARK READ.
 */
public final class NotificationActionReceiver extends BroadcastReceiver {
    static final String ACTION_REPLY = "com.gungreeneyes.trekui.messages.REPLY";
    static final String ACTION_MARK_READ = "com.gungreeneyes.trekui.messages.MARK_READ";
    static final String EXTRA_THREAD = "thread";
    static final String EXTRA_ADDRESS = "address";

    /** The action's PendingIntent; reply intents must be mutable so the system can add the typed text. */
    static PendingIntent intent(Context c, String action, long threadId, String address, boolean mutable) {
        Intent i = new Intent(action, null, c, NotificationActionReceiver.class)
                .putExtra(EXTRA_THREAD, threadId)
                .putExtra(EXTRA_ADDRESS, address);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (!mutable) flags |= PendingIntent.FLAG_IMMUTABLE;
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        int request = (int) (threadId * 2 + (ACTION_REPLY.equals(action) ? 0 : 1));
        return PendingIntent.getBroadcast(c, request, i, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final long thread = intent.getLongExtra(EXTRA_THREAD, -1);
        final String address = intent.getStringExtra(EXTRA_ADDRESS);
        final Context app = context.getApplicationContext();
        if (ACTION_REPLY.equals(intent.getAction())) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence text = results == null ? null : results.getCharSequence(Notifier.KEY_REPLY);
            if (text == null || text.toString().trim().isEmpty() || address == null) return;
            final String body = text.toString();
            Background.run(goAsync(), () -> {
                int sub = MessagesSettings.subFor(app, String.valueOf(thread));
                SmsSender.send(app, address, body, sub);
                MessageStore.markRead(app, thread);
                Notifier.cancel(app, thread);
            });
        } else if (ACTION_MARK_READ.equals(intent.getAction())) {
            Background.run(goAsync(), () -> {
                MessageStore.markRead(app, thread);
                Notifier.cancel(app, thread);
            });
        }
    }
}
