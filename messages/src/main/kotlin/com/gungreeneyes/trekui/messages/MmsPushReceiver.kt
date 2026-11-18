package com.gungreeneyes.trekui.messages

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
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
        val app = context.applicationContext
        Background.run(goAsync()) {
            if (Blocking.isBlocked(app, from)) return@run
            try {
                MmsSender.download(app, location, subId, from)
            } catch (e: RuntimeException) {
                Notifier.showFailure(app, -1, from, R.string.error_mms_download)
            }
        }
    }
}
