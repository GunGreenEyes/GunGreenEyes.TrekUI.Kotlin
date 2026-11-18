package com.gungreeneyes.trekui.messages

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.gungreeneyes.trekui.messages.data.javaTrim

/**
 * Notification actions: REPLY (inline RemoteInput text sent as SMS from the conversation's SIM, then the
 * conversation is marked read) and MARK READ.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val thread = intent.getLongExtra(EXTRA_THREAD, -1)
        val address = intent.getStringExtra(EXTRA_ADDRESS)
        val app = context.applicationContext
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)
                if (text == null || text.toString().javaTrim().isEmpty() || address == null) return
                val body = text.toString()
                Background.run(goAsync()) {
                    val sub = MessagesSettings.subFor(app, thread.toString())
                    SmsSender.send(app, address, body, sub)
                    MessageStore.markRead(app, thread)
                    Notifier.cancel(app, thread)
                }
            }
            ACTION_MARK_READ -> Background.run(goAsync()) {
                MessageStore.markRead(app, thread)
                Notifier.cancel(app, thread)
            }
        }
    }

    internal companion object {
        const val ACTION_REPLY = "com.gungreeneyes.trekui.messages.REPLY"
        const val ACTION_MARK_READ = "com.gungreeneyes.trekui.messages.MARK_READ"
        const val EXTRA_THREAD = "thread"
        const val EXTRA_ADDRESS = "address"

        /** The action's PendingIntent; reply intents must be mutable so the system can add the typed text. */
        fun intent(c: Context, action: String, threadId: Long, address: String, mutable: Boolean): PendingIntent {
            val i = Intent(action, null, c, NotificationActionReceiver::class.java)
                .putExtra(EXTRA_THREAD, threadId)
                .putExtra(EXTRA_ADDRESS, address)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (!mutable) {
                flags = flags or PendingIntent.FLAG_IMMUTABLE
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val request = (threadId * 2 + (if (ACTION_REPLY == action) 0 else 1)).toInt()
            return PendingIntent.getBroadcast(c, request, i, flags)
        }
    }
}
