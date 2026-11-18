package com.gungreeneyes.trekui.messages

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SmsManager
import com.gungreeneyes.trekui.messages.mms.MmsPdu
import com.gungreeneyes.trekui.messages.mms.PduCodec
import java.io.File
import java.io.IOException

/**
 * Results from the MMS service. Sent: the outbox row moves to SENT (with the carrier's message id from
 * the m-send-conf) or FAILED. Downloaded: the m-retrieve-conf file is parsed and stored in the inbox,
 * then the conversation notifies. The PDU file is deleted either way.
 */
class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val code = resultCode
        val row = intent.data
        val file = intent.getStringExtra(MmsSender.EXTRA_FILE)
        val subId = intent.getIntExtra(MmsSender.EXTRA_SUB, -1)
        val from = intent.getStringExtra(MmsSender.EXTRA_FROM)
        val response = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)
        val app = context.applicationContext
        Background.run(goAsync()) {
            val f = file?.let { MmsFileProvider.file(app, it) }
            try {
                when (action) {
                    MmsSender.ACTION_SENT -> onSent(app, row, code, response)
                    MmsSender.ACTION_DOWNLOADED -> onDownloaded(app, f, code, subId, from)
                }
            } finally {
                f?.delete()
            }
        }
    }

    private fun onSent(app: Context, row: Uri?, code: Int, response: ByteArray?) {
        val conf = PduCodec.decode(response)
        val ok = code == Activity.RESULT_OK &&
            (conf == null || conf.responseStatus == 0 || conf.responseStatus == MmsPdu.RESPONSE_OK)
        if (ok) {
            MmsStore.setBox(app, row, Telephony.Mms.MESSAGE_BOX_SENT, conf?.messageId)
        } else {
            MmsStore.setBox(app, row, Telephony.Mms.MESSAGE_BOX_FAILED, null)
            Notifier.showFailure(app, MessageStore.threadOf(app, row, true), "", R.string.error_send)
        }
    }

    private fun onDownloaded(app: Context, f: File?, code: Int, subId: Int, from: String?) {
        var pdu: MmsPdu? = null
        if (code == Activity.RESULT_OK && f != null && f.exists()) {
            try {
                pdu = PduCodec.decode(f.readBytes())
            } catch (ignored: IOException) {
                // treated as a failed download below
            }
        }
        if (pdu == null || pdu.messageType != MmsPdu.TYPE_RETRIEVE_CONF) {
            Notifier.showFailure(app, -1, from ?: "", R.string.error_mms_download)
            return
        }
        if (pdu.from == null) pdu.from = from
        val stored = MmsStore.insertIncoming(app, pdu, subId)
        val thread = MessageStore.threadOf(app, stored, true)
        if (thread > 0 && !ThreadActivity.isShowing(thread)) Notifier.showThread(app, thread)
    }
}
