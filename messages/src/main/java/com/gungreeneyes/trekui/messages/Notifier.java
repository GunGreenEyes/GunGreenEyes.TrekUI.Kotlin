package com.gungreeneyes.trekui.messages;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;

import com.gungreeneyes.trekui.messages.data.MessageItem;

import java.util.List;

/**
 * Message notifications: one per conversation (id = thread id) in a messaging style with the unread
 * messages, an inline REPLY (RemoteInput) and MARK READ, both handled by
 * {@link NotificationActionReceiver}. Each conversation gets its own notification channel
 * ({@code conv_<threadId>}, in the "Conversations" group) the first time it notifies, so the user can
 * give a person their own sound or importance in system settings; failures use the "Send errors"
 * channel. Blocking (reads the provider); call off the main thread.
 */
public final class Notifier {
    static final String GROUP_CONVERSATIONS = "conversations";
    static final String CHANNEL_DEFAULT = "messages";
    static final String CHANNEL_ERRORS = "send_errors";
    static final String CHANNEL_PREFIX = "conv_";
    static final String KEY_REPLY = "reply";
    /** Unread messages shown in one notification. */
    static final int MAX_LINES = 6;
    /** Notification id offset for send failures (thread ids stay below it in practice). */
    static final int FAILURE_ID_OFFSET = 0x40000000;

    private Notifier() {}

    /** Creates the shared group and channels (idempotent). */
    public static void ensureChannels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.createNotificationChannelGroup(new NotificationChannelGroup(GROUP_CONVERSATIONS,
                c.getString(R.string.channel_group_conversations)));
        NotificationChannel def = new NotificationChannel(CHANNEL_DEFAULT, c.getString(R.string.channel_messages),
                NotificationManager.IMPORTANCE_HIGH);
        def.setGroup(GROUP_CONVERSATIONS);
        nm.createNotificationChannel(def);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ERRORS, c.getString(R.string.channel_errors),
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    /** The channel of a conversation, created on first use with the contact's name. */
    static String channelFor(Context c, long threadId, String title) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        String id = CHANNEL_PREFIX + threadId;
        if (nm == null) return CHANNEL_DEFAULT;
        if (nm.getNotificationChannel(id) == null) {
            NotificationChannel ch = new NotificationChannel(id, title, NotificationManager.IMPORTANCE_HIGH);
            ch.setGroup(GROUP_CONVERSATIONS);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ch.setConversationId(CHANNEL_DEFAULT, String.valueOf(threadId));
            }
            nm.createNotificationChannel(ch);
        }
        return id;
    }

    /** Shows (or updates) the notification of {@code threadId} with its unread messages. */
    public static void showThread(Context c, long threadId) {
        List<MessageItem> unread = MessageStore.unread(c, threadId, MAX_LINES);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (unread.isEmpty()) {
            nm.cancel((int) threadId);
            return;
        }
        MessageItem last = unread.get(unread.size() - 1);
        String title = Contacts.name(c, last.address);
        String imageMarker = c.getString(R.string.image_marker);
        String channel = channelFor(c, threadId, title);

        Notification.Builder b = new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_stat_message)
                .setColor(c.getColor(R.color.notification_accent))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentTitle(title)
                .setContentText(last.preview(imageMarker))
                .setWhen(last.dateMillis)
                .setShowWhen(true)
                .setNumber(unread.size())
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setContentIntent(openThread(c, threadId))
                .setDeleteIntent(null);

        String me = c.getString(R.string.notification_me);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Person self = new Person.Builder().setName(me).build();
            Person them = new Person.Builder().setName(title).setKey(last.address).build();
            Notification.MessagingStyle style = new Notification.MessagingStyle(self);
            for (MessageItem m : unread) style.addMessage(new Notification.MessagingStyle.Message(
                    m.preview(imageMarker), m.dateMillis, them));
            b.setStyle(style);
        } else {
            b.setStyle(legacyStyle(me, title, unread, imageMarker));
        }

        RemoteInput input = new RemoteInput.Builder(KEY_REPLY).setLabel(c.getString(R.string.reply_hint)).build();
        Notification.Action.Builder reply = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_action_reply), c.getString(R.string.action_reply),
                NotificationActionReceiver.intent(c, NotificationActionReceiver.ACTION_REPLY, threadId, last.address, true))
                .addRemoteInput(input)
                .setAllowGeneratedReplies(true);
        Notification.Action.Builder read = new Notification.Action.Builder(
                Icon.createWithResource(c, R.drawable.ic_action_read), c.getString(R.string.action_mark_read),
                NotificationActionReceiver.intent(c, NotificationActionReceiver.ACTION_MARK_READ, threadId, last.address, false));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            reply.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY);
            read.setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ);
        }
        b.addAction(reply.build());
        b.addAction(read.build());
        try {
            nm.notify((int) threadId, b.build());
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS denied
        }
    }

    @SuppressWarnings("deprecation")
    private static Notification.Style legacyStyle(String me, String title, List<MessageItem> unread, String marker) {
        Notification.MessagingStyle style = new Notification.MessagingStyle(me);
        for (MessageItem m : unread) style.addMessage(m.preview(marker), m.dateMillis, title);
        return style;
    }

    /** Removes the notification of a conversation (opened or marked read). */
    public static void cancel(Context c, long threadId) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel((int) threadId);
    }

    /** Tells the user a message to {@code address} could not be sent (or downloaded). */
    public static void showFailure(Context c, long threadId, String address, int messageRes) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        String who = Contacts.name(c, address);
        Notification n = new Notification.Builder(c, CHANNEL_ERRORS)
                .setSmallIcon(R.drawable.ic_stat_message)
                .setColor(c.getColor(R.color.notification_accent))
                .setContentTitle(c.getString(messageRes))
                .setContentText(who)
                .setAutoCancel(true)
                .setContentIntent(openThread(c, threadId))
                .build();
        try {
            nm.notify(FAILURE_ID_OFFSET + (int) (threadId & 0xFFFFFF), n);
        } catch (SecurityException ignored) {
            // POST_NOTIFICATIONS denied
        }
    }

    static PendingIntent openThread(Context c, long threadId) {
        Intent i = threadId > 0 ? ThreadActivity.intent(c, threadId, null)
                : new Intent(c, ConversationListActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, (int) threadId, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
