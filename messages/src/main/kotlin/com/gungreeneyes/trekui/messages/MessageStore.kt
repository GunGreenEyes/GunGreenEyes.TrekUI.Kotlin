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
import java.util.Collections

/**
 * Reads and writes the system SMS/MMS store (Telephony provider): the conversation list, one thread's
 * messages (SMS and MMS merged by [ThreadGrouper]), search, read state, deletion and the SMS rows
 * the default SMS app must write itself (received messages, outgoing messages and their send/delivery
 * state). MMS rows are handled by [MmsStore]. Every method blocks: call off the main thread.
 * Writes fail quietly (return null / 0) while Messages is not the default SMS app.
 */
internal object MessageStore {
    /** The simple conversation list: one row per thread with snippet, date and recipient ids. */
    val THREADS: Uri = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build()

    /** Recipient id → address table behind [Telephony.Threads.RECIPIENT_IDS]. */
    val CANONICAL: Uri = "content://mms-sms/canonical-addresses".toUri()

    /** Thread deletion URI prefix (`content://mms-sms/conversations/<id>`). */
    val CONVERSATIONS: Uri = Telephony.Threads.CONTENT_URI

    /** Watch this for any SMS/MMS change. */
    val CHANGES: Uri = "content://mms-sms/".toUri()

    /** Most messages a thread view loads (newest kept). */
    const val THREAD_LIMIT = 500

    /** Most search hits per kind (SMS / MMS). */
    const val SEARCH_LIMIT = 200

    private val WHITESPACE = Regex("\\s+")

    /** One conversation row of the list. */
    data class Conversation(
        val threadId: Long,
        val addresses: List<String>,
        val snippet: String,
        val dateMillis: Long,
        val unread: Boolean,
        val attachment: Boolean,
    )

    // =============================================================================================
    // Conversations
    // =============================================================================================

    /** Every conversation, newest first (empty without READ_SMS). */
    fun conversations(c: Context): List<Conversation> {
        val out = ArrayList<Conversation>()
        if (!SmsRole.canRead(c)) return out
        val canonical = canonicalAddresses(c)
        try {
            c.contentResolver.query(THREADS, null, null, null, Telephony.Threads.DATE + " DESC")?.use { cur ->
                val iId = cur.getColumnIndex(Telephony.Threads._ID)
                val iDate = cur.getColumnIndex(Telephony.Threads.DATE)
                val iRecipients = cur.getColumnIndex(Telephony.Threads.RECIPIENT_IDS)
                val iSnippet = cur.getColumnIndex(Telephony.Threads.SNIPPET)
                val iRead = cur.getColumnIndex(Telephony.Threads.READ)
                val iAttach = cur.getColumnIndex(Telephony.Threads.HAS_ATTACHMENT)
                val iCount = cur.getColumnIndex(Telephony.Threads.MESSAGE_COUNT)
                while (cur.moveToNext()) {
                    if (iCount >= 0 && cur.getInt(iCount) == 0) continue // empty thread shells
                    val who = ArrayList<String>()
                    val ids = if (iRecipients >= 0) cur.getString(iRecipients) else null
                    ids?.trim { it <= ' ' }?.split(WHITESPACE)?.forEach { id ->
                        // a malformed id list skips the entry
                        id.toLongOrNull()?.let(canonical::get)?.let { who.add(PhoneNumbers.normalize(it)) }
                    }
                    out.add(
                        Conversation(
                            threadId = cur.getLong(iId),
                            addresses = who,
                            snippet = (if (iSnippet >= 0) cur.getString(iSnippet) else "").orEmpty(),
                            dateMillis = if (iDate >= 0) cur.getLong(iDate) else 0L,
                            unread = iRead >= 0 && cur.getInt(iRead) == 0,
                            attachment = iAttach >= 0 && cur.getInt(iAttach) != 0,
                        ),
                    )
                }
            }
        } catch (e: RuntimeException) {
            // provider missing (no telephony) or permission revoked mid-query
        }
        return out
    }

    private fun canonicalAddresses(c: Context): Map<Long, String?> {
        val map = HashMap<Long, String?>()
        try {
            c.contentResolver.query(CANONICAL, arrayOf("_id", "address"), null, null, null)?.use { cur ->
                while (cur.moveToNext()) map[cur.getLong(0)] = cur.getString(1)
            }
        } catch (ignored: RuntimeException) {
            // no canonical table: conversations show without names
        }
        return map
    }

    /** The participants of [threadId] (empty when unknown). */
    fun recipients(c: Context, threadId: Long): List<String> =
        conversations(c).firstOrNull { it.threadId == threadId }?.addresses ?: ArrayList()

    /** The existing thread with exactly these participants, or -1. */
    fun findThread(c: Context, addresses: List<String>): Long =
        conversations(c).firstOrNull { ThreadGrouper.sameParticipants(it.addresses, addresses) }?.threadId ?: -1

    /** The thread for these participants, created when missing (needs the default SMS role to create). */
    fun threadFor(c: Context, addresses: List<String>): Long = try {
        Telephony.Threads.getOrCreateThreadId(c, HashSet(addresses))
    } catch (e: RuntimeException) {
        findThread(c, addresses)
    }

    // =============================================================================================
    // Messages
    // =============================================================================================

    /** The newest [THREAD_LIMIT] messages of a thread, oldest first. */
    fun thread(c: Context, threadId: Long): List<MessageItem> {
        if (threadId <= 0 || !SmsRole.canRead(c)) return ArrayList()
        val sms = smsWhere(c, Telephony.Sms.THREAD_ID + "=?", arrayOf(threadId.toString()), THREAD_LIMIT)
        val mms = MmsStore.forThread(c, threadId, THREAD_LIMIT)
        return ThreadGrouper.newest(ThreadGrouper.merge(sms, mms), THREAD_LIMIT)
    }

    /** Unread incoming SMS/MMS of a thread, oldest first (for the notification). */
    fun unread(c: Context, threadId: Long, max: Int): List<MessageItem> =
        ThreadGrouper.newest(thread(c, threadId).filter { it.unread }, max)

    /** Messages whose text contains [query] (case-insensitive), newest first. */
    fun search(c: Context, query: String?): List<MessageItem> {
        val q = query?.trim { it <= ' ' }
        if (q.isNullOrEmpty() || !SmsRole.canRead(c)) return ArrayList()
        val like = "%" + escapeLike(q) + "%"
        val out = ArrayList<MessageItem>()
        out.addAll(smsWhere(c, Telephony.Sms.BODY + " LIKE ? ESCAPE '\\'", arrayOf(like), SEARCH_LIMIT))
        out.addAll(MmsStore.search(c, like, SEARCH_LIMIT))
        out.sortWith(Collections.reverseOrder(ThreadGrouper.CHRONOLOGICAL)) // Comparator.reversed is API 24
        return out
    }

    /** Escapes LIKE wildcards (`% _ \`) in user text. */
    fun escapeLike(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** SMS rows matching [where], newest [limit] kept, sorted oldest first. */
    private fun smsWhere(c: Context, where: String, args: Array<String>, limit: Int): List<MessageItem> {
        val out = ArrayList<MessageItem>()
        val cols = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.DATE,
            Telephony.Sms.BODY, Telephony.Sms.READ, Telephony.Sms.TYPE, Telephony.Sms.STATUS,
            Telephony.Sms.SUBSCRIPTION_ID,
        )
        try {
            c.contentResolver.query(Telephony.Sms.CONTENT_URI, cols, where, args, Telephony.Sms.DATE + " DESC")?.use { cur ->
                while (cur.moveToNext() && out.size < limit) {
                    val type = cur.getInt(6)
                    if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue
                    val outgoing = type != Telephony.Sms.MESSAGE_TYPE_INBOX
                    out.add(
                        MessageItem(
                            id = cur.getLong(0),
                            threadId = cur.getLong(1),
                            address = cur.getString(2).orEmpty(),
                            dateMillis = cur.getLong(3),
                            body = cur.getString(4).orEmpty(),
                            read = cur.getInt(5) != 0,
                            outgoing = outgoing,
                            mms = false,
                            state = smsState(type, cur.getInt(7)),
                            subId = if (cur.isNull(8)) -1 else cur.getInt(8),
                        ),
                    )
                }
            }
        } catch (ignored: RuntimeException) {
            // permission revoked mid-query: show what we have
        }
        out.sortWith(ThreadGrouper.CHRONOLOGICAL)
        return out
    }

    /** Bubble state of an SMS row from its type and delivery status. */
    fun smsState(type: Int, status: Int): MessageItem.State = when (type) {
        Telephony.Sms.MESSAGE_TYPE_INBOX -> MessageItem.State.RECEIVED
        Telephony.Sms.MESSAGE_TYPE_OUTBOX, Telephony.Sms.MESSAGE_TYPE_QUEUED -> MessageItem.State.SENDING
        Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageItem.State.FAILED
        else -> when (status) {
            Telephony.Sms.STATUS_COMPLETE -> MessageItem.State.DELIVERED
            Telephony.Sms.STATUS_FAILED -> MessageItem.State.FAILED
            else -> MessageItem.State.SENT
        }
    }

    // =============================================================================================
    // Read state and deletion
    // =============================================================================================

    /** Marks every message of a thread read and seen. */
    fun markRead(c: Context, threadId: Long) {
        val v = ContentValues().apply {
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
        }
        try {
            c.contentResolver.update(
                Telephony.Sms.CONTENT_URI, v,
                Telephony.Sms.THREAD_ID + "=? AND (" + Telephony.Sms.READ + "=0 OR " + Telephony.Sms.SEEN + "=0)",
                arrayOf(threadId.toString()),
            )
        } catch (ignored: RuntimeException) {
            // not the default SMS app
        }
        MmsStore.markRead(c, threadId)
    }

    /** Deletes a whole conversation; returns rows removed. */
    fun deleteThread(c: Context, threadId: Long): Int = try {
        c.contentResolver.delete(ContentUris.withAppendedId(CONVERSATIONS, threadId), null, null)
    } catch (e: RuntimeException) {
        0
    }

    /** Deletes one message. */
    fun deleteMessage(c: Context, m: MessageItem): Int {
        val base = if (m.mms) Telephony.Mms.CONTENT_URI else Telephony.Sms.CONTENT_URI
        return try {
            c.contentResolver.delete(ContentUris.withAppendedId(base, m.id), null, null)
        } catch (e: RuntimeException) {
            0
        }
    }

    // =============================================================================================
    // SMS writes (default SMS app only)
    // =============================================================================================

    /** Stores a received SMS in the inbox; returns its URI or null. */
    fun insertIncoming(c: Context, address: String, body: String, sentMillis: Long, subId: Int): Uri? {
        val v = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, sentMillis)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        }
        return insert(c, Telephony.Sms.Inbox.CONTENT_URI, v)
    }

    /** Stores an outgoing SMS in the outbox before sending; returns its URI or null. */
    fun insertOutgoing(c: Context, address: String, body: String, subId: Int): Uri? {
        val v = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        }
        return insert(c, Telephony.Sms.Outbox.CONTENT_URI, v)
    }

    /** Moves an SMS row to [type] (SENT / FAILED) unless it already failed. */
    fun setSmsType(c: Context, uri: Uri?, type: Int, errorCode: Int) {
        if (uri == null) return
        val v = ContentValues().apply {
            put(Telephony.Sms.TYPE, type)
            if (type == Telephony.Sms.MESSAGE_TYPE_FAILED) put(Telephony.Sms.ERROR_CODE, errorCode)
        }
        try {
            c.contentResolver.update(
                uri, v, Telephony.Sms.TYPE + "!=?",
                arrayOf(Telephony.Sms.MESSAGE_TYPE_FAILED.toString()),
            )
        } catch (ignored: RuntimeException) {
            // row gone or not default
        }
    }

    /** Records a delivery report status on an SMS row. */
    fun setSmsStatus(c: Context, uri: Uri?, status: Int) {
        if (uri == null) return
        val v = ContentValues().apply { put(Telephony.Sms.STATUS, status) }
        try {
            c.contentResolver.update(uri, v, null, null)
        } catch (ignored: RuntimeException) {
            // row gone or not default
        }
    }

    /** Thread id of a stored SMS/MMS row, or -1. */
    fun threadOf(c: Context, row: Uri?, mms: Boolean): Long {
        if (row == null) return -1
        val col = if (mms) Telephony.Mms.THREAD_ID else Telephony.Sms.THREAD_ID
        try {
            c.contentResolver.query(row, arrayOf(col), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) return cur.getLong(0)
            }
        } catch (ignored: RuntimeException) {
            // fall through
        }
        return -1
    }

    fun insert(c: Context, table: Uri, v: ContentValues): Uri? = try {
        c.contentResolver.insert(table, v)
    } catch (e: RuntimeException) {
        null // not the default SMS app (SecurityException) or provider error
    }
}
