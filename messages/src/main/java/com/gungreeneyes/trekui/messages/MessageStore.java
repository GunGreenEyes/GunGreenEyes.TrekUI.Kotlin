package com.gungreeneyes.trekui.messages;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

import com.gungreeneyes.trekui.messages.data.MessageItem;
import com.gungreeneyes.trekui.messages.data.PhoneNumbers;
import com.gungreeneyes.trekui.messages.data.ThreadGrouper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes the system SMS/MMS store (Telephony provider): the conversation list, one thread's
 * messages (SMS and MMS merged by {@link ThreadGrouper}), search, read state, deletion and the SMS rows
 * the default SMS app must write itself (received messages, outgoing messages and their send/delivery
 * state). MMS rows are handled by {@link MmsStore}. Every method blocks: call off the main thread.
 * Writes fail quietly (return null / 0) while Messages is not the default SMS app.
 */
public final class MessageStore {
    /** The simple conversation list: one row per thread with snippet, date and recipient ids. */
    static final Uri THREADS = Telephony.Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build();
    /** Recipient id → address table behind {@link Telephony.Threads#RECIPIENT_IDS}. */
    static final Uri CANONICAL = Uri.parse("content://mms-sms/canonical-addresses");
    /** Thread deletion URI prefix ({@code content://mms-sms/conversations/<id>}). */
    static final Uri CONVERSATIONS = Telephony.Threads.CONTENT_URI;
    /** Watch this for any SMS/MMS change. */
    public static final Uri CHANGES = Uri.parse("content://mms-sms/");
    /** Most messages a thread view loads (newest kept). */
    public static final int THREAD_LIMIT = 500;
    /** Most search hits per kind (SMS / MMS). */
    public static final int SEARCH_LIMIT = 200;

    /** One conversation row of the list. */
    public static final class Conversation {
        public final long threadId;
        public final List<String> addresses;
        public final String snippet;
        public final long dateMillis;
        public final boolean unread;
        public final boolean attachment;
        /** Contact names (filled by the loader off the main thread). */
        public String title = "";

        Conversation(long threadId, List<String> addresses, String snippet, long dateMillis, boolean unread,
                     boolean attachment) {
            this.threadId = threadId;
            this.addresses = addresses;
            this.snippet = snippet == null ? "" : snippet;
            this.dateMillis = dateMillis;
            this.unread = unread;
            this.attachment = attachment;
        }
    }

    private MessageStore() {}

    // =============================================================================================
    // Conversations
    // =============================================================================================

    /** Every conversation, newest first (empty without READ_SMS). */
    public static List<Conversation> conversations(Context c) {
        List<Conversation> out = new ArrayList<>();
        if (!SmsRole.canRead(c)) return out;
        Map<Long, String> canonical = canonicalAddresses(c);
        try (Cursor cur = c.getContentResolver().query(THREADS, null, null, null, Telephony.Threads.DATE + " DESC")) {
            if (cur == null) return out;
            int iId = cur.getColumnIndex(Telephony.Threads._ID);
            int iDate = cur.getColumnIndex(Telephony.Threads.DATE);
            int iRecipients = cur.getColumnIndex(Telephony.Threads.RECIPIENT_IDS);
            int iSnippet = cur.getColumnIndex(Telephony.Threads.SNIPPET);
            int iRead = cur.getColumnIndex(Telephony.Threads.READ);
            int iAttach = cur.getColumnIndex(Telephony.Threads.HAS_ATTACHMENT);
            int iCount = cur.getColumnIndex(Telephony.Threads.MESSAGE_COUNT);
            while (cur.moveToNext()) {
                if (iCount >= 0 && cur.getInt(iCount) == 0) continue; // empty thread shells
                List<String> who = new ArrayList<>();
                String ids = iRecipients >= 0 ? cur.getString(iRecipients) : null;
                if (ids != null) for (String id : ids.trim().split("\\s+")) {
                    try {
                        String a = canonical.get(Long.parseLong(id));
                        if (a != null) who.add(PhoneNumbers.normalize(a));
                    } catch (NumberFormatException ignored) {
                        // malformed id list: skip the entry
                    }
                }
                out.add(new Conversation(cur.getLong(iId), who,
                        iSnippet >= 0 ? cur.getString(iSnippet) : "",
                        iDate >= 0 ? cur.getLong(iDate) : 0L,
                        iRead >= 0 && cur.getInt(iRead) == 0,
                        iAttach >= 0 && cur.getInt(iAttach) != 0));
            }
        } catch (RuntimeException e) {
            // provider missing (no telephony) or permission revoked mid-query
        }
        return out;
    }

    private static Map<Long, String> canonicalAddresses(Context c) {
        Map<Long, String> map = new HashMap<>();
        try (Cursor cur = c.getContentResolver().query(CANONICAL, new String[]{"_id", "address"}, null, null, null)) {
            if (cur != null) while (cur.moveToNext()) map.put(cur.getLong(0), cur.getString(1));
        } catch (RuntimeException ignored) {
            // no canonical table: conversations show without names
        }
        return map;
    }

    /** The participants of {@code threadId} (empty when unknown). */
    public static List<String> recipients(Context c, long threadId) {
        for (Conversation conv : conversations(c)) if (conv.threadId == threadId) return conv.addresses;
        return new ArrayList<>();
    }

    /** The existing thread with exactly these participants, or -1. */
    public static long findThread(Context c, List<String> addresses) {
        for (Conversation conv : conversations(c)) {
            if (ThreadGrouper.sameParticipants(conv.addresses, addresses)) return conv.threadId;
        }
        return -1;
    }

    /** The thread for these participants, created when missing (needs the default SMS role to create). */
    public static long threadFor(Context c, List<String> addresses) {
        try {
            return Telephony.Threads.getOrCreateThreadId(c, new java.util.HashSet<>(addresses));
        } catch (RuntimeException e) {
            return findThread(c, addresses);
        }
    }

    // =============================================================================================
    // Messages
    // =============================================================================================

    /** The newest {@link #THREAD_LIMIT} messages of a thread, oldest first. */
    public static List<MessageItem> thread(Context c, long threadId) {
        if (threadId <= 0 || !SmsRole.canRead(c)) return new ArrayList<>();
        List<MessageItem> sms = smsWhere(c, Telephony.Sms.THREAD_ID + "=?", new String[]{String.valueOf(threadId)},
                THREAD_LIMIT);
        List<MessageItem> mms = MmsStore.forThread(c, threadId, THREAD_LIMIT);
        return ThreadGrouper.newest(ThreadGrouper.merge(sms, mms), THREAD_LIMIT);
    }

    /** Unread incoming SMS/MMS of a thread, oldest first (for the notification). */
    public static List<MessageItem> unread(Context c, long threadId, int max) {
        List<MessageItem> all = thread(c, threadId);
        List<MessageItem> out = new ArrayList<>();
        for (MessageItem m : all) if (m.unread()) out.add(m);
        return ThreadGrouper.newest(out, max);
    }

    /** Messages whose text contains {@code query} (case-insensitive), newest first. */
    public static List<MessageItem> search(Context c, String query) {
        List<MessageItem> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty() || !SmsRole.canRead(c)) return out;
        String like = "%" + escapeLike(query.trim()) + "%";
        out.addAll(smsWhere(c, Telephony.Sms.BODY + " LIKE ? ESCAPE '\\'", new String[]{like}, SEARCH_LIMIT));
        out.addAll(MmsStore.search(c, like, SEARCH_LIMIT));
        out.sort((a, b) -> ThreadGrouper.CHRONOLOGICAL.compare(b, a));
        return out;
    }

    /** Escapes LIKE wildcards ({@code % _ \}) in user text. */
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** SMS rows matching {@code where}, newest {@code limit} kept, sorted oldest first. */
    private static List<MessageItem> smsWhere(Context c, String where, String[] args, int limit) {
        List<MessageItem> out = new ArrayList<>();
        String[] cols = {Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS, Telephony.Sms.DATE,
                Telephony.Sms.BODY, Telephony.Sms.READ, Telephony.Sms.TYPE, Telephony.Sms.STATUS,
                Telephony.Sms.SUBSCRIPTION_ID};
        try (Cursor cur = c.getContentResolver().query(Telephony.Sms.CONTENT_URI, cols, where, args,
                Telephony.Sms.DATE + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < limit) {
                int type = cur.getInt(6);
                if (type == Telephony.Sms.MESSAGE_TYPE_DRAFT) continue;
                boolean outgoing = type != Telephony.Sms.MESSAGE_TYPE_INBOX;
                out.add(new MessageItem(cur.getLong(0), cur.getLong(1), cur.getString(2), cur.getLong(3),
                        cur.getString(4), cur.getInt(5) != 0, outgoing, false, smsState(type, cur.getInt(7)),
                        cur.isNull(8) ? -1 : cur.getInt(8), null));
            }
        } catch (RuntimeException ignored) {
            // permission revoked mid-query: show what we have
        }
        out.sort(ThreadGrouper.CHRONOLOGICAL);
        return out;
    }

    /** Bubble state of an SMS row from its type and delivery status. */
    static MessageItem.State smsState(int type, int status) {
        switch (type) {
            case Telephony.Sms.MESSAGE_TYPE_INBOX: return MessageItem.State.RECEIVED;
            case Telephony.Sms.MESSAGE_TYPE_OUTBOX:
            case Telephony.Sms.MESSAGE_TYPE_QUEUED: return MessageItem.State.SENDING;
            case Telephony.Sms.MESSAGE_TYPE_FAILED: return MessageItem.State.FAILED;
            default:
                if (status == Telephony.Sms.STATUS_COMPLETE) return MessageItem.State.DELIVERED;
                if (status == Telephony.Sms.STATUS_FAILED) return MessageItem.State.FAILED;
                return MessageItem.State.SENT;
        }
    }

    // =============================================================================================
    // Read state and deletion
    // =============================================================================================

    /** Marks every message of a thread read and seen. */
    public static void markRead(Context c, long threadId) {
        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.READ, 1);
        v.put(Telephony.Sms.SEEN, 1);
        String[] args = {String.valueOf(threadId)};
        ContentResolver cr = c.getContentResolver();
        try {
            cr.update(Telephony.Sms.CONTENT_URI, v, Telephony.Sms.THREAD_ID + "=? AND (" + Telephony.Sms.READ
                    + "=0 OR " + Telephony.Sms.SEEN + "=0)", args);
        } catch (RuntimeException ignored) {
            // not the default SMS app
        }
        MmsStore.markRead(c, threadId);
    }

    /** Deletes a whole conversation; returns rows removed. */
    public static int deleteThread(Context c, long threadId) {
        try {
            return c.getContentResolver().delete(ContentUris.withAppendedId(CONVERSATIONS, threadId), null, null);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Deletes one message. */
    public static int deleteMessage(Context c, MessageItem m) {
        Uri base = m.mms ? Telephony.Mms.CONTENT_URI : Telephony.Sms.CONTENT_URI;
        try {
            return c.getContentResolver().delete(ContentUris.withAppendedId(base, m.id), null, null);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // =============================================================================================
    // SMS writes (default SMS app only)
    // =============================================================================================

    /** Stores a received SMS in the inbox; returns its URI or null. */
    public static Uri insertIncoming(Context c, String address, String body, long sentMillis, int subId) {
        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.ADDRESS, address);
        v.put(Telephony.Sms.BODY, body);
        v.put(Telephony.Sms.DATE, System.currentTimeMillis());
        v.put(Telephony.Sms.DATE_SENT, sentMillis);
        v.put(Telephony.Sms.READ, 0);
        v.put(Telephony.Sms.SEEN, 0);
        v.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);
        if (subId >= 0) v.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        return insert(c, Telephony.Sms.Inbox.CONTENT_URI, v);
    }

    /** Stores an outgoing SMS in the outbox before sending; returns its URI or null. */
    public static Uri insertOutgoing(Context c, String address, String body, int subId) {
        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.ADDRESS, address);
        v.put(Telephony.Sms.BODY, body);
        v.put(Telephony.Sms.DATE, System.currentTimeMillis());
        v.put(Telephony.Sms.READ, 1);
        v.put(Telephony.Sms.SEEN, 1);
        v.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX);
        if (subId >= 0) v.put(Telephony.Sms.SUBSCRIPTION_ID, subId);
        return insert(c, Telephony.Sms.Outbox.CONTENT_URI, v);
    }

    /** Moves an SMS row to {@code type} (SENT / FAILED) unless it already failed. */
    public static void setSmsType(Context c, Uri uri, int type, int errorCode) {
        if (uri == null) return;
        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.TYPE, type);
        if (type == Telephony.Sms.MESSAGE_TYPE_FAILED) v.put(Telephony.Sms.ERROR_CODE, errorCode);
        try {
            c.getContentResolver().update(uri, v, Telephony.Sms.TYPE + "!=?",
                    new String[]{String.valueOf(Telephony.Sms.MESSAGE_TYPE_FAILED)});
        } catch (RuntimeException ignored) {
            // row gone or not default
        }
    }

    /** Records a delivery report status on an SMS row. */
    public static void setSmsStatus(Context c, Uri uri, int status) {
        if (uri == null) return;
        ContentValues v = new ContentValues();
        v.put(Telephony.Sms.STATUS, status);
        try {
            c.getContentResolver().update(uri, v, null, null);
        } catch (RuntimeException ignored) {
            // row gone or not default
        }
    }

    /** Thread id of a stored SMS/MMS row, or -1. */
    public static long threadOf(Context c, Uri row, boolean mms) {
        if (row == null) return -1;
        String col = mms ? Telephony.Mms.THREAD_ID : Telephony.Sms.THREAD_ID;
        try (Cursor cur = c.getContentResolver().query(row, new String[]{col}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getLong(0);
        } catch (RuntimeException ignored) {
            // fall through
        }
        return -1;
    }

    static Uri insert(Context c, Uri table, ContentValues v) {
        try {
            return c.getContentResolver().insert(table, v);
        } catch (RuntimeException e) {
            return null; // not the default SMS app (SecurityException) or provider error
        }
    }
}
