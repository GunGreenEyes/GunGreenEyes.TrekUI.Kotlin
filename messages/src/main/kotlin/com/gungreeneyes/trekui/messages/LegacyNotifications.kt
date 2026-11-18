package com.gungreeneyes.trekui.messages

import android.app.Notification
import android.content.Context
import android.os.Build
import com.gungreeneyes.trekui.messages.data.MessageItem

/**
 * The pre-channel notification pieces [Notifier] uses on Android 6.0-7.1 (API 23-25, TUK-T14): a builder that
 * carries the would-be channel's priority, and the inbox list that stands in for MessagingStyle on 6.0.
 */
internal object LegacyNotifications {
    /** Android 6.0 has no MessagingStyle: the unread messages as "sender: text" inbox lines, newest last. */
    fun inboxStyle(sender: String, unread: List<MessageItem>, marker: String): Notification.Style {
        val style = Notification.InboxStyle()
        for (m in unread) style.addLine(inboxLine(sender, m.preview(marker)))
        return style
    }

    /** One inbox line of the Android 6.0 notification: "sender: text". */
    fun inboxLine(sender: String?, text: String?): String = "${sender.orEmpty()}: ${text.orEmpty()}"

    /**
     * A notification builder for [channel]. Below Android 8.0 there are no channels, so the notification
     * gets what the channel's importance would give it: heads-up priority for messages ([urgent], channel
     * IMPORTANCE_HIGH), the default priority for send errors (IMPORTANCE_DEFAULT), the default sound and
     * vibration for both.
     */
    @Suppress("DEPRECATION")
    fun builder(c: Context, channel: String, urgent: Boolean): Notification.Builder {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return Notification.Builder(c, channel)
        return Notification.Builder(c)
            .setPriority(if (urgent) Notification.PRIORITY_HIGH else Notification.PRIORITY_DEFAULT)
            .setDefaults(Notification.DEFAULT_ALL)
    }
}
