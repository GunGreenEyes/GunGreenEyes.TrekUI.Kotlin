package com.gungreeneyes.trekui.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.content.edit
import com.gungreeneyes.trekui.messages.data.PhoneNumbers
import com.gungreeneyes.trekui.messages.mms.MmsPdu
import com.gungreeneyes.trekui.messages.mms.PduCodec

/**
 * Required default-SMS component: receives WAP_PUSH_DELIVER for MMS. The push carries an
 * m-notification-ind (parsed by [PduCodec]); unless the sender is blocked, the message itself is
 * downloaded at once through the MMS service ([MmsSender.download]), which also sends the
 * acknowledgement to the carrier.
 */
class MmsPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pdu = PduCodec.decode(intent.getByteArrayExtra("data")) ?: return
        if (pdu.messageType != MmsPdu.TYPE_NOTIFICATION_IND) return
        val location = pdu.contentLocation ?: return
        val from = PhoneNumbers.normalize(pdu.from)
        val subId = Sims.subIdFrom(intent)
        val keys = listOfNotNull(pdu.transactionId?.let { "t:$it" }, "l:$location")
        val app = context.applicationContext
        Background.run(goAsync()) {
            if (!firstNotice(app, keys)) return@run // a duplicate push for a message already handled
            if (Blocking.isBlocked(app, from)) return@run
            try {
                MmsSender.download(app, location, subId, from)
            } catch (e: RuntimeException) {
                forget(app, keys) // let a re-sent notice try again
                Notifier.showFailure(app, -1, from, R.string.error_mms_download)
            }
        }
    }

    private companion object {
        /** Recently seen notices (transaction id / content location) with the time they arrived. */
        const val PREFS = "messages_mms_push_seen"

        /** How long a notice is remembered. */
        const val TTL_MILLIS = 3L * 24 * 60 * 60 * 1000

        /** True (and remembered) unless one of [keys] was seen within [TTL_MILLIS]; prunes expired entries. */
        @Synchronized
        fun firstNotice(c: Context, keys: List<String>): Boolean {
            val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val all = prefs.all
            val expired = all.filter { (_, v) -> v !is Long || (now - v) !in 0..TTL_MILLIS }.keys
            val seen = keys.any { it in all && it !in expired }
            prefs.edit {
                for (k in expired) remove(k)
                if (!seen) for (k in keys) putLong(k, now)
            }
            return !seen
        }

        @Synchronized
        fun forget(c: Context, keys: List<String>) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { for (k in keys) remove(k) }
        }
    }
}
