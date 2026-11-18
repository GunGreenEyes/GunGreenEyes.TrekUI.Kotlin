package com.gungreeneyes.trekui.messages

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import com.gungreeneyes.trekui.messages.mms.MmsBuilder
import com.gungreeneyes.trekui.messages.mms.PduCodec
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Sends and downloads MMS through the platform MMS service (`SmsManager.sendMultimediaMessage` /
 * `downloadMultimediaMessage`), with PDUs built and parsed by this app's own codec
 * ([MmsBuilder], [PduCodec]) and exchanged as files through [MmsFileProvider].
 * Results arrive at [MmsResultReceiver]. Blocking; call off the main thread.
 */
internal object MmsSender {
    const val ACTION_SENT = "com.gungreeneyes.trekui.messages.MMS_SENT"
    const val ACTION_DOWNLOADED = "com.gungreeneyes.trekui.messages.MMS_DOWNLOADED"
    const val EXTRA_FILE = "file"
    const val EXTRA_SUB = "sub"
    const val EXTRA_FROM = "from"

    /**
     * Sends [text] and the JPEG [images] (cache files, deleted afterwards) to every recipient
     * in one MMS (a group message when there are several).
     *
     * @return true if the message was handed to the MMS service
     */
    fun send(c: Context, recipients: List<String>, text: String?, images: List<File>?, subId: Int): Boolean {
        val body = text ?: ""
        val textBytes = body.toByteArray(Charsets.UTF_8).size
        val count = images?.size ?: 0
        val jpegs = ArrayList<ByteArray>()
        if (images != null && count > 0) {
            val budget = maxOf(24 * 1024, (Images.MMS_BUDGET_BYTES - Images.MMS_OVERHEAD_BYTES - textBytes) / count)
            for (f in images) {
                Images.jpegWithin(f, budget)?.let(jpegs::add)
                f.delete()
            }
        }
        val now = System.currentTimeMillis()
        val pdu = MmsBuilder.sendRequest(recipients, body, jpegs, "T" + java.lang.Long.toHexString(now), now / 1000L)
        val bytes = PduCodec.encode(pdu)
        val name = "send-$now.pdu"
        if (!write(MmsFileProvider.file(c, name), bytes)) return false

        val thread = MessageStore.threadFor(c, recipients)
        val row = MmsStore.insertOutgoing(c, pdu, thread, subId)
        val result = Intent(ACTION_SENT, row, c, MmsResultReceiver::class.java)
            .putExtra(EXTRA_FILE, name)
            .putExtra(EXTRA_SUB, subId)
        val pi = PendingIntent.getBroadcast(c, now.toInt(), result, mutableFlags())
        return try {
            Sims.manager(c, subId).sendMultimediaMessage(c, MmsFileProvider.uriFor(c, name), null, null, pi)
            true
        } catch (e: RuntimeException) {
            MmsStore.setBox(c, row, Telephony.Mms.MESSAGE_BOX_FAILED, null)
            MmsFileProvider.file(c, name).delete()
            false
        }
    }

    /** Asks the MMS service to download the message announced at [contentLocation]. */
    fun download(c: Context, contentLocation: String, subId: Int, from: String) {
        val now = System.currentTimeMillis()
        val name = "recv-$now.pdu"
        MmsFileProvider.file(c, name) // ensures the directory exists
        val result = Intent(ACTION_DOWNLOADED, null, c, MmsResultReceiver::class.java)
            .putExtra(EXTRA_FILE, name)
            .putExtra(EXTRA_SUB, subId)
            .putExtra(EXTRA_FROM, from)
        val pi = PendingIntent.getBroadcast(c, now.toInt(), result, mutableFlags())
        Sims.manager(c, subId).downloadMultimediaMessage(c, contentLocation, MmsFileProvider.uriFor(c, name), null, pi)
    }

    /** Result PendingIntents are filled in by the MMS service (status bytes), so mutable on API 31+. */
    private fun mutableFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return flags
    }

    private fun write(f: File, bytes: ByteArray): Boolean = try {
        FileOutputStream(f).use { it.write(bytes) }
        true
    } catch (e: IOException) {
        false
    }
}
