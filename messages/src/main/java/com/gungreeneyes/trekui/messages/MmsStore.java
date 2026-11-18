package com.gungreeneyes.trekui.messages;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

import com.gungreeneyes.trekui.messages.data.MessageItem;
import com.gungreeneyes.trekui.messages.data.PhoneNumbers;
import com.gungreeneyes.trekui.messages.data.ThreadGrouper;
import com.gungreeneyes.trekui.messages.mms.MmsPart;
import com.gungreeneyes.trekui.messages.mms.MmsPdu;
import com.gungreeneyes.trekui.messages.mms.PduCodec;

import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * MMS rows in the Telephony provider: reading a thread's MMS (text + image parts, sender address),
 * storing downloaded and outgoing messages (message row, parts with their bytes, address rows) and
 * moving them between boxes as sending progresses. Blocking; call off the main thread. Writes need the
 * default SMS role.
 */
public final class MmsStore {
    static final Uri PARTS = Uri.parse("content://mms/part");
    /** PduHeaders address types: FROM 137, TO 151, CC 130. */
    static final int ADDR_FROM = 0x89, ADDR_TO = 0x97, ADDR_CC = 0x82;
    /** m-send-req / m-retrieve-conf message types as stored in {@code m_type}. */
    static final int M_TYPE_SEND_REQ = 0x80, M_TYPE_RETRIEVE_CONF = 0x84;
    /** Address used for the FROM row of an outgoing message (the carrier inserts the real one). */
    static final String INSERT_ADDRESS = "insert-address-token";

    private MmsStore() {}

    // ---- reading --------------------------------------------------------------------------------

    /** The newest {@code limit} MMS of a thread, oldest first. */
    static List<MessageItem> forThread(Context c, long threadId, int limit) {
        return where(c, Telephony.Mms.THREAD_ID + "=?", new String[]{String.valueOf(threadId)}, limit);
    }

    /** MMS whose text part matches the LIKE pattern {@code like}. */
    static List<MessageItem> search(Context c, String like, int limit) {
        Set<Long> ids = new LinkedHashSet<>();
        try (Cursor cur = c.getContentResolver().query(PARTS, new String[]{Telephony.Mms.Part.MSG_ID},
                Telephony.Mms.Part.CONTENT_TYPE + "='text/plain' AND " + Telephony.Mms.Part.TEXT + " LIKE ? ESCAPE '\\'",
                new String[]{like}, null)) {
            if (cur != null) while (cur.moveToNext() && ids.size() < limit) ids.add(cur.getLong(0));
        } catch (RuntimeException ignored) {
            return new ArrayList<>();
        }
        if (ids.isEmpty()) return new ArrayList<>();
        StringBuilder in = new StringBuilder();
        for (Long id : ids) {
            if (in.length() > 0) in.append(',');
            in.append(id);
        }
        return where(c, Telephony.Mms._ID + " IN (" + in + ")", null, limit);
    }

    private static List<MessageItem> where(Context c, String where, String[] args, int limit) {
        List<MessageItem> out = new ArrayList<>();
        String[] cols = {Telephony.Mms._ID, Telephony.Mms.THREAD_ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.READ, Telephony.Mms.SUBSCRIPTION_ID};
        try (Cursor cur = c.getContentResolver().query(Telephony.Mms.CONTENT_URI, cols, where, args,
                Telephony.Mms.DATE + " DESC")) {
            if (cur == null) return out;
            while (cur.moveToNext() && out.size() < limit) {
                long id = cur.getLong(0);
                int box = cur.getInt(3);
                if (box == Telephony.Mms.MESSAGE_BOX_DRAFTS) continue;
                boolean outgoing = box != Telephony.Mms.MESSAGE_BOX_INBOX;
                StringBuilder text = new StringBuilder();
                List<String> images = new ArrayList<>();
                readParts(c, id, text, images);
                String address = address(c, id, outgoing ? ADDR_TO : ADDR_FROM);
                out.add(new MessageItem(id, cur.getLong(1), address, cur.getLong(2) * 1000L, text.toString(),
                        cur.getInt(4) != 0, outgoing, true, boxState(box),
                        cur.isNull(5) ? -1 : cur.getInt(5), images));
            }
        } catch (RuntimeException ignored) {
            // provider missing or permission revoked
        }
        out.sort(ThreadGrouper.CHRONOLOGICAL);
        return out;
    }

    static MessageItem.State boxState(int box) {
        switch (box) {
            case Telephony.Mms.MESSAGE_BOX_INBOX: return MessageItem.State.RECEIVED;
            case Telephony.Mms.MESSAGE_BOX_OUTBOX: return MessageItem.State.SENDING;
            case Telephony.Mms.MESSAGE_BOX_FAILED: return MessageItem.State.FAILED;
            default: return MessageItem.State.SENT;
        }
    }

    private static void readParts(Context c, long mmsId, StringBuilder text, List<String> images) {
        String[] cols = {Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT};
        try (Cursor cur = c.getContentResolver().query(PARTS, cols, Telephony.Mms.Part.MSG_ID + "=?",
                new String[]{String.valueOf(mmsId)}, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                String ct = cur.getString(1);
                if (ct == null) continue;
                if (ct.startsWith("text/plain")) {
                    String t = cur.getString(2);
                    if (t != null && !t.isEmpty()) {
                        if (text.length() > 0) text.append('\n');
                        text.append(t);
                    }
                } else if (ct.startsWith("image/")) {
                    images.add(ContentUris.withAppendedId(PARTS, cur.getLong(0)).toString());
                }
            }
        } catch (RuntimeException ignored) {
            // unreadable parts: show the message without them
        }
    }

    private static String address(Context c, long mmsId, int type) {
        Uri uri = Uri.parse("content://mms/" + mmsId + "/addr");
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{Telephony.Mms.Addr.ADDRESS},
                Telephony.Mms.Addr.TYPE + "=?", new String[]{String.valueOf(type)}, null)) {
            if (cur != null && cur.moveToFirst()) return PhoneNumbers.normalize(cur.getString(0));
        } catch (RuntimeException ignored) {
            // fall through
        }
        return "";
    }

    static void markRead(Context c, long threadId) {
        ContentValues v = new ContentValues();
        v.put(Telephony.Mms.READ, 1);
        v.put(Telephony.Mms.SEEN, 1);
        try {
            c.getContentResolver().update(Telephony.Mms.CONTENT_URI, v, Telephony.Mms.THREAD_ID + "=? AND ("
                    + Telephony.Mms.READ + "=0 OR " + Telephony.Mms.SEEN + "=0)", new String[]{String.valueOf(threadId)});
        } catch (RuntimeException ignored) {
            // not default
        }
    }

    // ---- writing --------------------------------------------------------------------------------

    /**
     * Stores a downloaded m-retrieve-conf in the inbox (thread of its sender); returns the row URI or null.
     */
    static Uri insertIncoming(Context c, MmsPdu pdu, int subId) {
        String from = PhoneNumbers.normalize(pdu.from);
        if (from.isEmpty()) return null;
        List<String> who = new ArrayList<>();
        who.add(from);
        long thread = MessageStore.threadFor(c, who);
        long date = pdu.dateSeconds > 0 ? pdu.dateSeconds : System.currentTimeMillis() / 1000L;
        ContentValues v = new ContentValues();
        v.put(Telephony.Mms.THREAD_ID, thread);
        v.put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L);
        v.put(Telephony.Mms.DATE_SENT, date);
        v.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_INBOX);
        v.put(Telephony.Mms.READ, 0);
        v.put(Telephony.Mms.SEEN, 0);
        v.put(Telephony.Mms.MESSAGE_TYPE, M_TYPE_RETRIEVE_CONF);
        v.put(Telephony.Mms.MMS_VERSION, pdu.version);
        v.put(Telephony.Mms.CONTENT_TYPE, pdu.contentType);
        if (pdu.messageId != null) v.put(Telephony.Mms.MESSAGE_ID, pdu.messageId);
        if (pdu.transactionId != null) v.put(Telephony.Mms.TRANSACTION_ID, pdu.transactionId);
        if (pdu.subject != null) {
            v.put(Telephony.Mms.SUBJECT, pdu.subject);
            v.put(Telephony.Mms.SUBJECT_CHARSET, PduCodec.CHARSET_UTF8);
        }
        if (subId >= 0) v.put(Telephony.Mms.SUBSCRIPTION_ID, subId);
        Uri row = MessageStore.insert(c, Telephony.Mms.Inbox.CONTENT_URI, v);
        if (row == null) return null;
        long id = ContentUris.parseId(row);
        writeParts(c, id, pdu.parts);
        writeAddress(c, id, pdu.from, ADDR_FROM);
        for (String t : pdu.to) writeAddress(c, id, t, ADDR_TO);
        for (String t : pdu.cc) writeAddress(c, id, t, ADDR_CC);
        return row;
    }

    /** Stores an outgoing message in the outbox before it is handed to the MMS service. */
    static Uri insertOutgoing(Context c, MmsPdu pdu, long threadId, int subId) {
        ContentValues v = new ContentValues();
        v.put(Telephony.Mms.THREAD_ID, threadId);
        v.put(Telephony.Mms.DATE, System.currentTimeMillis() / 1000L);
        v.put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_OUTBOX);
        v.put(Telephony.Mms.READ, 1);
        v.put(Telephony.Mms.SEEN, 1);
        v.put(Telephony.Mms.MESSAGE_TYPE, M_TYPE_SEND_REQ);
        v.put(Telephony.Mms.MMS_VERSION, pdu.version);
        v.put(Telephony.Mms.CONTENT_TYPE, pdu.contentType);
        v.put(Telephony.Mms.TRANSACTION_ID, pdu.transactionId);
        if (subId >= 0) v.put(Telephony.Mms.SUBSCRIPTION_ID, subId);
        Uri row = MessageStore.insert(c, Telephony.Mms.Outbox.CONTENT_URI, v);
        if (row == null) return null;
        long id = ContentUris.parseId(row);
        writeParts(c, id, pdu.parts);
        writeAddress(c, id, INSERT_ADDRESS, ADDR_FROM);
        for (String t : pdu.to) writeAddress(c, id, PhoneNumbers.normalize(t), ADDR_TO);
        return row;
    }

    /** Moves a stored MMS to {@code box}; records the carrier's message id when given. */
    static void setBox(Context c, Uri row, int box, String messageId) {
        if (row == null) return;
        ContentValues v = new ContentValues();
        v.put(Telephony.Mms.MESSAGE_BOX, box);
        if (messageId != null) v.put(Telephony.Mms.MESSAGE_ID, messageId);
        try {
            c.getContentResolver().update(row, v, null, null);
        } catch (RuntimeException ignored) {
            // row gone
        }
    }

    private static void writeParts(Context c, long mmsId, List<MmsPart> parts) {
        Uri partsUri = Uri.parse("content://mms/" + mmsId + "/part");
        int seq = 0;
        for (MmsPart p : parts) {
            ContentValues v = new ContentValues();
            v.put(Telephony.Mms.Part.MSG_ID, mmsId);
            v.put(Telephony.Mms.Part.SEQ, p.isSmil() ? -1 : seq++);
            v.put(Telephony.Mms.Part.CONTENT_TYPE, p.contentType);
            if (p.contentId != null) v.put(Telephony.Mms.Part.CONTENT_ID, "<" + p.contentId + ">");
            if (p.location != null) {
                v.put(Telephony.Mms.Part.CONTENT_LOCATION, p.location);
                v.put(Telephony.Mms.Part.NAME, p.location);
            }
            boolean textual = p.isText() || p.isSmil();
            if (textual) {
                v.put(Telephony.Mms.Part.CHARSET, PduCodec.CHARSET_UTF8);
                v.put(Telephony.Mms.Part.TEXT, p.text());
            }
            Uri part = MessageStore.insert(c, partsUri, v);
            if (part == null || textual) continue;
            try (OutputStream out = c.getContentResolver().openOutputStream(part)) {
                if (out != null) out.write(p.data);
            } catch (Exception ignored) {
                // part row stays without data; the bubble shows no image for it
            }
        }
    }

    private static void writeAddress(Context c, long mmsId, String address, int type) {
        if (address == null || address.isEmpty()) return;
        ContentValues v = new ContentValues();
        v.put(Telephony.Mms.Addr.MSG_ID, mmsId);
        v.put(Telephony.Mms.Addr.ADDRESS, address.equals(INSERT_ADDRESS) ? address : PhoneNumbers.normalize(address));
        v.put(Telephony.Mms.Addr.TYPE, type);
        v.put(Telephony.Mms.Addr.CHARSET, PduCodec.CHARSET_UTF8);
        MessageStore.insert(c, Uri.parse("content://mms/" + mmsId + "/addr"), v);
    }
}
