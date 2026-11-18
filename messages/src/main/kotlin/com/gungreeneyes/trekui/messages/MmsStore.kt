package com.gungreeneyes.trekui.messages

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import androidx.core.net.toUri
import com.gungreeneyes.trekui.messages.data.MessageItem
import com.gungreeneyes.trekui.messages.data.PhoneNumbers
import com.gungreeneyes.trekui.messages.data.ThreadGrouper
import com.gungreeneyes.trekui.messages.mms.MmsPart
import com.gungreeneyes.trekui.messages.mms.MmsPdu
import com.gungreeneyes.trekui.messages.mms.PduCodec

/**
 * MMS rows in the Telephony provider: reading a thread's MMS (text + image parts, sender address),
 * storing downloaded and outgoing messages (message row, parts with their bytes, address rows) and
 * moving them between boxes as sending progresses. Blocking; call off the main thread. Writes need the
 * default SMS role.
 */
internal object MmsStore {
    val PARTS: Uri = "content://mms/part".toUri()

    /** PduHeaders address types: FROM 137, TO 151, CC 130. */
    const val ADDR_FROM = 0x89
    const val ADDR_TO = 0x97
    const val ADDR_CC = 0x82

    /** m-send-req / m-retrieve-conf message types as stored in `m_type`. */
    const val M_TYPE_SEND_REQ = 0x80
    const val M_TYPE_RETRIEVE_CONF = 0x84

    /** Address used for the FROM row of an outgoing message (the carrier inserts the real one). */
    const val INSERT_ADDRESS = "insert-address-token"

    // ---- reading --------------------------------------------------------------------------------

    /** The newest [limit] MMS of a thread, oldest first. */
    fun forThread(c: Context, threadId: Long, limit: Int): List<MessageItem> =
        where(c, Telephony.Mms.THREAD_ID + "=?", arrayOf(threadId.toString()), limit)

    /** MMS whose text part matches the LIKE pattern [like]. */
    fun search(c: Context, like: String, limit: Int): List<MessageItem> {
        val ids = LinkedHashSet<Long>()
        try {
            c.contentResolver.query(
                PARTS, arrayOf(Telephony.Mms.Part.MSG_ID),
                Telephony.Mms.Part.CONTENT_TYPE + "='text/plain' AND " + Telephony.Mms.Part.TEXT + " LIKE ? ESCAPE '\\'",
                arrayOf(like), null,
            )?.use { cur -> while (cur.moveToNext() && ids.size < limit) ids.add(cur.getLong(0)) }
        } catch (ignored: RuntimeException) {
            return ArrayList()
        }
        if (ids.isEmpty()) return ArrayList()
        return where(c, Telephony.Mms._ID + " IN (" + ids.joinToString(",") + ")", null, limit)
    }

    private fun where(c: Context, where: String, args: Array<String>?, limit: Int): List<MessageItem> {
        val out = ArrayList<MessageItem>()
        val cols = arrayOf(
            Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX,
            Telephony.Mms.READ, Telephony.Mms.SUBSCRIPTION_ID,
        )
        try {
            c.contentResolver.query(Telephony.Mms.CONTENT_URI, cols, where, args, Telephony.Mms.DATE + " DESC")?.use { cur ->
                while (cur.moveToNext() && out.size < limit) {
                    val id = cur.getLong(0)
                    val box = cur.getInt(3)
                    if (box == Telephony.Mms.MESSAGE_BOX_DRAFTS) continue
                    val outgoing = box != Telephony.Mms.MESSAGE_BOX_INBOX
                    val text = StringBuilder()
                    val images = ArrayList<String>()
                    readParts(c, id, text, images)
                    out.add(
                        MessageItem(
                            id = id,
                            threadId = cur.getLong(1),
                            address = address(c, id, if (outgoing) ADDR_TO else ADDR_FROM),
                            dateMillis = cur.getLong(2) * 1000L,
                            body = text.toString(),
                            read = cur.getInt(4) != 0,
                            outgoing = outgoing,
                            mms = true,
                            state = boxState(box),
                            subId = if (cur.isNull(5)) -1 else cur.getInt(5),
                            images = images,
                        ),
                    )
                }
            }
        } catch (ignored: RuntimeException) {
            // provider missing or permission revoked
        }
        out.sortWith(ThreadGrouper.CHRONOLOGICAL)
        return out
    }

    fun boxState(box: Int): MessageItem.State = when (box) {
        Telephony.Mms.MESSAGE_BOX_INBOX -> MessageItem.State.RECEIVED
        Telephony.Mms.MESSAGE_BOX_OUTBOX -> MessageItem.State.SENDING
        Telephony.Mms.MESSAGE_BOX_FAILED -> MessageItem.State.FAILED
        else -> MessageItem.State.SENT
    }

    private fun readParts(c: Context, mmsId: Long, text: StringBuilder, images: MutableList<String>) {
        val cols = arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT)
        try {
            c.contentResolver.query(PARTS, cols, Telephony.Mms.Part.MSG_ID + "=?", arrayOf(mmsId.toString()), null)
                ?.use { cur ->
                    while (cur.moveToNext()) {
                        val ct = cur.getString(1) ?: continue
                        if (ct.startsWith("text/plain")) {
                            val t = cur.getString(2)
                            if (!t.isNullOrEmpty()) {
                                if (text.isNotEmpty()) text.append('\n')
                                text.append(t)
                            }
                        } else if (ct.startsWith("image/")) {
                            images.add(ContentUris.withAppendedId(PARTS, cur.getLong(0)).toString())
                        }
                    }
                }
        } catch (ignored: RuntimeException) {
            // unreadable parts: show the message without them
        }
    }

    private fun address(c: Context, mmsId: Long, type: Int): String {
        val uri = "content://mms/$mmsId/addr".toUri()
        try {
            c.contentResolver.query(
                uri, arrayOf(Telephony.Mms.Addr.ADDRESS), Telephony.Mms.Addr.TYPE + "=?", arrayOf(type.toString()), null,
            )?.use { cur ->
                if (cur.moveToFirst()) return PhoneNumbers.normalize(cur.getString(0))
            }
        } catch (ignored: RuntimeException) {
            // fall through
        }
        return ""
    }

    fun markRead(c: Context, threadId: Long) {
        val v = ContentValues().apply {
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
        }
        try {
            c.contentResolver.update(
                Telephony.Mms.CONTENT_URI, v,
                Telephony.Mms.THREAD_ID + "=? AND (" + Telephony.Mms.READ + "=0 OR " + Telephony.Mms.SEEN + "=0)",
                arrayOf(threadId.toString()),
            )
        } catch (ignored: RuntimeException) {
            // not default
        }
    }

    // ---- writing --------------------------------------------------------------------------------

    /**
     * Stores a downloaded m-retrieve-conf in the inbox (thread of its sender); returns the row URI or null.
     */
    fun insertIncoming(c: Context, pdu: MmsPdu, subId: Int): Uri? {
        val from = PhoneNumbers.normalize(pdu.from)
        if (from.isEmpty()) return null
        val thread = MessageStore.threadFor(c, listOf(from))
        val date = if (pdu.dateSeconds > 0) pdu.dateSeconds else System.currentTimeMillis() / 1000L
        val v = ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, thread)
            put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L)
            put(Telephony.Mms.DATE_SENT, date)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX)
            put(Telephony.Mms.READ, 0)
            put(Telephony.Mms.SEEN, 0)
            put(Telephony.Mms.MESSAGE_TYPE, M_TYPE_RETRIEVE_CONF)
            put(Telephony.Mms.MMS_VERSION, pdu.version)
            put(Telephony.Mms.CONTENT_TYPE, pdu.contentType)
            pdu.messageId?.let { put(Telephony.Mms.MESSAGE_ID, it) }
            pdu.transactionId?.let { put(Telephony.Mms.TRANSACTION_ID, it) }
            pdu.subject?.let {
                put(Telephony.Mms.SUBJECT, it)
                put(Telephony.Mms.SUBJECT_CHARSET, PduCodec.CHARSET_UTF8)
            }
            if (subId >= 0) put(Telephony.Mms.SUBSCRIPTION_ID, subId)
        }
        val row = MessageStore.insert(c, Telephony.Mms.Inbox.CONTENT_URI, v) ?: return null
        val id = ContentUris.parseId(row)
        writeParts(c, id, pdu.parts)
        writeAddress(c, id, pdu.from, ADDR_FROM)
        for (t in pdu.to) writeAddress(c, id, t, ADDR_TO)
        for (t in pdu.cc) writeAddress(c, id, t, ADDR_CC)
        return row
    }

    /** Stores an outgoing message in the outbox before it is handed to the MMS service. */
    fun insertOutgoing(c: Context, pdu: MmsPdu, threadId: Long, subId: Int): Uri? {
        val v = ContentValues().apply {
            put(Telephony.Mms.THREAD_ID, threadId)
            put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L)
            put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX)
            put(Telephony.Mms.READ, 1)
            put(Telephony.Mms.SEEN, 1)
            put(Telephony.Mms.MESSAGE_TYPE, M_TYPE_SEND_REQ)
            put(Telephony.Mms.MMS_VERSION, pdu.version)
            put(Telephony.Mms.CONTENT_TYPE, pdu.contentType)
            put(Telephony.Mms.TRANSACTION_ID, pdu.transactionId)
            if (subId >= 0) put(Telephony.Mms.SUBSCRIPTION_ID, subId)
        }
        val row = MessageStore.insert(c, Telephony.Mms.Outbox.CONTENT_URI, v) ?: return null
        val id = ContentUris.parseId(row)
        writeParts(c, id, pdu.parts)
        writeAddress(c, id, INSERT_ADDRESS, ADDR_FROM)
        for (t in pdu.to) writeAddress(c, id, PhoneNumbers.normalize(t), ADDR_TO)
        return row
    }

    /** Moves a stored MMS to [box]; records the carrier's message id when given. */
    fun setBox(c: Context, row: Uri?, box: Int, messageId: String?) {
        if (row == null) return
        val v = ContentValues().apply {
            put(Telephony.Mms.MESSAGE_BOX, box)
            messageId?.let { put(Telephony.Mms.MESSAGE_ID, it) }
        }
        try {
            c.contentResolver.update(row, v, null, null)
        } catch (ignored: RuntimeException) {
            // row gone
        }
    }

    private fun writeParts(c: Context, mmsId: Long, parts: List<MmsPart>) {
        val partsUri = "content://mms/$mmsId/part".toUri()
        var seq = 0
        for (p in parts) {
            val textual = p.isText || p.isSmil
            val v = ContentValues().apply {
                put(Telephony.Mms.Part.MSG_ID, mmsId)
                put(Telephony.Mms.Part.SEQ, if (p.isSmil) -1 else seq++)
                put(Telephony.Mms.Part.CONTENT_TYPE, p.contentType)
                p.contentId?.let { put(Telephony.Mms.Part.CONTENT_ID, "<$it>") }
                p.location?.let {
                    put(Telephony.Mms.Part.CONTENT_LOCATION, it)
                    put(Telephony.Mms.Part.NAME, it)
                }
                if (textual) {
                    put(Telephony.Mms.Part.CHARSET, PduCodec.CHARSET_UTF8)
                    put(Telephony.Mms.Part.TEXT, p.text())
                }
            }
            val part = MessageStore.insert(c, partsUri, v)
            if (part == null || textual) continue
            try {
                c.contentResolver.openOutputStream(part)?.use { it.write(p.data) }
            } catch (ignored: Exception) {
                // part row stays without data; the bubble shows no image for it
            }
        }
    }

    private fun writeAddress(c: Context, mmsId: Long, address: String?, type: Int) {
        if (address.isNullOrEmpty()) return
        val v = ContentValues().apply {
            put(Telephony.Mms.Addr.MSG_ID, mmsId)
            put(Telephony.Mms.Addr.ADDRESS, if (address == INSERT_ADDRESS) address else PhoneNumbers.normalize(address))
            put(Telephony.Mms.Addr.TYPE, type)
            put(Telephony.Mms.Addr.CHARSET, PduCodec.CHARSET_UTF8)
        }
        MessageStore.insert(c, "content://mms/$mmsId/addr".toUri(), v)
    }
}
