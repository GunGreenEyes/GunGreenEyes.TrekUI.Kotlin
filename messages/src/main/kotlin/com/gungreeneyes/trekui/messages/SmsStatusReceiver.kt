package com.gungreeneyes.trekui.messages

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage

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
                val tp = report.status
                val status = when {
                    tp >= TP_STATUS_FAILED_MIN -> Telephony.Sms.STATUS_FAILED
                    tp >= TP_STATUS_PENDING_MIN -> Telephony.Sms.STATUS_PENDING
                    else -> Telephony.Sms.STATUS_COMPLETE
                }
                // a multipart message counts as delivered when its last part is
                if (status != Telephony.Sms.STATUS_COMPLETE || part == parts - 1) {
                    MessageStore.setSmsStatus(app, row, status)
                }
            }
        }
    }

    internal companion object {
        /** TP-Status ranges (3GPP TS 23.040): 0x00-0x1F done, 0x20-0x3F still trying, 0x40+ failed. */
        const val TP_STATUS_PENDING_MIN = 0x20
        const val TP_STATUS_FAILED_MIN = 0x40
    }
}
