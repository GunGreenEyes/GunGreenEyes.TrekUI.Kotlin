package com.gungreeneyes.trekui.messages

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import androidx.core.content.edit
import com.gungreeneyes.trekui.messages.data.DeliveryStatus

/**
 * Sent and delivery results of outgoing SMS parts ([SmsSender]'s PendingIntents): the row becomes
 * SENT when the last part is accepted, FAILED as soon as any part fails (with a notification), and gets
 * the delivery status (complete / pending / failed) from the status report PDU.
 */
class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val row = intent.data
        val code = resultCode
        val part = intent.getIntExtra(SmsSender.EXTRA_PART, 0)
        val parts = intent.getIntExtra(SmsSender.EXTRA_PARTS, 1)
        val address = intent.getStringExtra(SmsSender.EXTRA_ADDRESS)
        val error = intent.getIntExtra("errorCode", code)
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format")
        val app = context.applicationContext
        Background.run(goAsync()) {
            if (action == SmsSender.ACTION_SENT) {
                if (code == Activity.RESULT_OK) {
                    if (part == parts - 1) MessageStore.setSmsType(app, row, Telephony.Sms.MESSAGE_TYPE_SENT, 0)
                } else {
                    MessageStore.setSmsType(app, row, Telephony.Sms.MESSAGE_TYPE_FAILED, error)
                    Notifier.showFailure(app, MessageStore.threadOf(app, row, false), address, R.string.error_send)
                }
            } else if (action == SmsSender.ACTION_DELIVERED && pdu != null) {
                val report = SmsMessage.createFromPdu(pdu, format) ?: return@run
                val status = DeliveryStatus.fromTp(report.status)
                if (row == null || parts <= 1) {
                    MessageStore.setSmsStatus(app, row, status)
                    return@run
                }
                // multipart: the worst part wins (failed > pending > complete), so remember each part's status
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val key = row.toString()
                val byPart = DeliveryStatus.decode(prefs.getString(key, null))
                if (byPart[part] != DeliveryStatus.FAILED) byPart[part] = status
                if (DeliveryStatus.settled(byPart, parts)) {
                    prefs.edit { remove(key) }
                } else {
                    prefs.edit { putString(key, DeliveryStatus.encode(byPart)) }
                }
                MessageStore.setSmsStatus(app, row, DeliveryStatus.merge(byPart, parts))
            }
        }
    }

    internal companion object {
        /** Per-part delivery statuses of multipart messages still awaiting reports, keyed by row URI. */
        private const val PREFS = "messages_delivery_parts"
    }
}
