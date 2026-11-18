package com.gungreeneyes.trekui.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Required default-SMS component: receives SMS_DELIVER (sent only to the default SMS app), joins the
 * parts of a long message, drops blocked senders, writes the message to the inbox (nobody else will) and
 * notifies.
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return
        val body = StringBuilder()
        var address: String? = null
        var sent = 0L
        for (p in parts) {
            if (p == null) continue
            if (address == null) address = p.displayOriginatingAddress
            if (sent == 0L) sent = p.timestampMillis
            p.displayMessageBody?.let { body.append(it) }
        }
        val from = address ?: return
        val text = body.toString()
        val sentMillis = sent
        val subId = Sims.subIdFrom(intent)
        val app = context.applicationContext
        Background.run(goAsync()) {
            if (Blocking.isBlocked(app, from)) return@run
            val row = MessageStore.insertIncoming(app, from, text, sentMillis, subId)
            val thread = MessageStore.threadOf(app, row, false)
            if (thread > 0 && !ThreadActivity.isShowing(thread)) Notifier.showThread(app, thread)
        }
    }
}
