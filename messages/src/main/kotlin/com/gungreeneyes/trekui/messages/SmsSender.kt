package com.gungreeneyes.trekui.messages

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager

/**
 * Sends text messages: stores the message in the outbox (so it shows at once and survives a crash), splits
 * it with [SmsManager.divideMessage], and sends every part with a sent and (optionally) a delivery
 * [PendingIntent] to [SmsStatusReceiver], which moves the row to SENT / FAILED and records
 * the delivery report. Blocking (provider write); call off the main thread.
 */
internal object SmsSender {
    const val ACTION_SENT = "com.gungreeneyes.trekui.messages.SMS_SENT"
    const val ACTION_DELIVERED = "com.gungreeneyes.trekui.messages.SMS_DELIVERED"
    const val EXTRA_PART = "part"
    const val EXTRA_PARTS = "parts"
    const val EXTRA_ADDRESS = "address"

    /**
     * Sends [body] to [address] from SIM [subId] (-1: default SIM).
     *
     * @return the outbox row (null when not the default SMS app: the message is still sent, untracked)
     */
    fun send(c: Context, address: String, body: String, subId: Int): Uri? {
        val row = MessageStore.insertOutgoing(c, address, body, subId)
        val sms = Sims.manager(c, subId)
        val parts: ArrayList<String> = sms.divideMessage(body)?.takeIf { it.isNotEmpty() } ?: arrayListOf(body)
        val reports = MessagesSettings.deliveryReports(c)
        val base = if (row != null) ContentUris.parseId(row) else System.currentTimeMillis()
        val sent = ArrayList<PendingIntent>(parts.size)
        val delivered: ArrayList<PendingIntent>? = if (reports) ArrayList(parts.size) else null
        for (i in parts.indices) {
            sent.add(status(c, ACTION_SENT, row, address, i, parts.size, base))
            delivered?.add(status(c, ACTION_DELIVERED, row, address, i, parts.size, base))
        }
        try {
            if (parts.size == 1) {
                sms.sendTextMessage(address, null, parts[0], sent[0], delivered?.get(0))
            } else {
                sms.sendMultipartTextMessage(address, null, parts, sent, delivered)
            }
        } catch (e: RuntimeException) {
            MessageStore.setSmsType(c, row, Telephony.Sms.MESSAGE_TYPE_FAILED, -1)
        }
        return row
    }

    /**
     * A status PendingIntent for part [part]. Mutable on API 31+: SmsManager fills in the error
     * code and, for delivery reports, the status PDU. The intent is explicit to our own receiver.
     */
    private fun status(c: Context, action: String, row: Uri?, address: String, part: Int, parts: Int, base: Long): PendingIntent {
        val i = Intent(action, row, c, SmsStatusReceiver::class.java)
            .putExtra(EXTRA_PART, part)
            .putExtra(EXTRA_PARTS, parts)
            .putExtra(EXTRA_ADDRESS, address)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        val request = ((base * 31 + part) * 2 + (if (ACTION_SENT == action) 0 else 1)).toInt()
        return PendingIntent.getBroadcast(c, request, i, flags)
    }
}
