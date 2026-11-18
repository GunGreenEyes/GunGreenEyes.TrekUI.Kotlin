package com.gungreeneyes.trekui.messages

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import com.gungreeneyes.trekui.messages.data.PhoneNumbers
import com.gungreeneyes.trekui.messages.data.javaTrim

/**
 * Required default-SMS component: "respond via message" from the incoming-call screen. The system sends
 * RESPOND_VIA_MESSAGE with the caller in the data URI and the chosen text in EXTRA_TEXT; the reply is
 * sent as SMS without any UI.
 */
class RespondViaMessageService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val to = PhoneNumbers.fromSchemePart(intent.data?.schemeSpecificPart)
        if (to.isEmpty() || text == null || text.javaTrim().isEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        Background.run {
            for (a in to) SmsSender.send(applicationContext, a, text, -1)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null
}
