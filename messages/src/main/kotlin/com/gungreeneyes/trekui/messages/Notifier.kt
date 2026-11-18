package com.gungreeneyes.trekui.messages

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import com.gungreeneyes.trekui.messages.data.MessageItem

/**
 * Message notifications: one per conversation (id = thread id) in a messaging style with the unread
 * messages, an inline REPLY (RemoteInput) and MARK READ, both handled by
 * [NotificationActionReceiver]. Each conversation gets its own notification channel
 * (`conv_<threadId>`, in the "Conversations" group) the first time it notifies, so the user can
 * give a person their own sound or importance in system settings; failures use the "Send errors"
 * channel. Blocking (reads the provider); call off the main thread.
 */
internal object Notifier {
    const val GROUP_CONVERSATIONS = "conversations"
    const val CHANNEL_DEFAULT = "messages"
    const val CHANNEL_ERRORS = "send_errors"
    const val CHANNEL_PREFIX = "conv_"
    const val KEY_REPLY = "reply"

    /** Unread messages shown in one notification. */
    const val MAX_LINES = 6

    /** Notification id offset for send failures (thread ids stay below it in practice). */
    const val FAILURE_ID_OFFSET = 0x40000000

    /** Creates the shared group and channels (idempotent). */
    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP_CONVERSATIONS, c.getString(R.string.channel_group_conversations)),
        )
        val def = NotificationChannel(CHANNEL_DEFAULT, c.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH)
        def.group = GROUP_CONVERSATIONS
        nm.createNotificationChannel(def)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ERRORS, c.getString(R.string.channel_errors), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** The channel of a conversation, created on first use with the contact's name. */
    fun channelFor(c: Context, threadId: Long, title: String): String {
        val nm = c.getSystemService(NotificationManager::class.java)
        val id = CHANNEL_PREFIX + threadId
        if (nm == null) return CHANNEL_DEFAULT
        if (nm.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(id, title, NotificationManager.IMPORTANCE_HIGH)
            ch.group = GROUP_CONVERSATIONS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ch.setConversationId(CHANNEL_DEFAULT, threadId.toString())
            }
            nm.createNotificationChannel(ch)
        }
        return id
    }

    /** Shows (or updates) the notification of [threadId] with its unread messages. */
    fun showThread(c: Context, threadId: Long) {
        val unread = MessageStore.unread(c, threadId, MAX_LINES)
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (unread.isEmpty()) {
            nm.cancel(threadId.toInt())
            return
        }
        val last = unread.last()
        val title = Contacts.name(c, last.address)
        val imageMarker = c.getString(R.string.image_marker)
        val channel = channelFor(c, threadId, title)

        val b = Notification.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setColor(c.getColor(R.color.notification_accent))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentTitle(title)
            .setContentText(last.preview(imageMarker))
            .setWhen(last.dateMillis)
            .setShowWhen(true)
            .setNumber(unread.size)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openThread(c, threadId))
            .setDeleteIntent(null)

        val me = c.getString(R.string.notification_me)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val self = Person.Builder().setName(me).build()
            val them = Person.Builder().setName(title).setKey(last.address).build()
            val style = Notification.MessagingStyle(self)
            for (m in unread) style.addMessage(Notification.MessagingStyle.Message(m.preview(imageMarker), m.dateMillis, them))
            b.setStyle(style)
        } else {
            b.setStyle(legacyStyle(me, title, unread, imageMarker))
        }

        val input = RemoteInput.Builder(KEY_REPLY).setLabel(c.getString(R.string.reply_hint)).build()
        val reply = Notification.Action.Builder(
            Icon.createWithResource(c, R.drawable.ic_action_reply), c.getString(R.string.action_reply),
            NotificationActionReceiver.intent(c, NotificationActionReceiver.ACTION_REPLY, threadId, last.address, true),
        )
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
        val read = Notification.Action.Builder(
            Icon.createWithResource(c, R.drawable.ic_action_read), c.getString(R.string.action_mark_read),
            NotificationActionReceiver.intent(c, NotificationActionReceiver.ACTION_MARK_READ, threadId, last.address, false),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            reply.setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
            read.setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
        }
        b.addAction(reply.build())
        b.addAction(read.build())
        try {
            nm.notify(threadId.toInt(), b.build())
        } catch (ignored: SecurityException) {
            // POST_NOTIFICATIONS denied
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyStyle(me: String, title: String, unread: List<MessageItem>, marker: String): Notification.Style {
        val style = Notification.MessagingStyle(me)
        for (m in unread) style.addMessage(m.preview(marker), m.dateMillis, title)
        return style
    }

    /** Removes the notification of a conversation (opened or marked read). */
    fun cancel(c: Context, threadId: Long) {
        c.getSystemService(NotificationManager::class.java)?.cancel(threadId.toInt())
    }

    /** Tells the user a message to [address] could not be sent (or downloaded). */
    fun showFailure(c: Context, threadId: Long, address: String?, messageRes: Int) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val who = Contacts.name(c, address)
        val n = Notification.Builder(c, CHANNEL_ERRORS)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setColor(c.getColor(R.color.notification_accent))
            .setContentTitle(c.getString(messageRes))
            .setContentText(who)
            .setAutoCancel(true)
            .setContentIntent(openThread(c, threadId))
            .build()
        try {
            nm.notify(FAILURE_ID_OFFSET + (threadId and 0xFFFFFF).toInt(), n)
        } catch (ignored: SecurityException) {
            // POST_NOTIFICATIONS denied
        }
    }

    fun openThread(c: Context, threadId: Long): PendingIntent {
        val i = if (threadId > 0) ThreadActivity.intent(c, threadId, null) else Intent(c, ConversationListActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            c, threadId.toInt(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
