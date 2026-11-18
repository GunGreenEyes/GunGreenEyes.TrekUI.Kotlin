package com.gungreeneyes.trekui.messages.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One SMS or MMS as the UI shows it (immutable apart from the attachment list built while loading).
 * Plain Java so the grouping and merging logic ({@link ThreadGrouper}) is unit-testable; the Android
 * {@code MessageStore} fills it from the Telephony provider.
 */
public final class MessageItem {
    /** Delivery state shown under an outgoing bubble. */
    public enum State { RECEIVED, SENDING, SENT, DELIVERED, FAILED }

    /** Provider row id (unique within {@link #mms} = false or true). */
    public final long id;
    public final long threadId;
    /** The other party: sender of an incoming message, first recipient of an outgoing one. */
    public final String address;
    public final long dateMillis;
    /** Text body ("" for an image-only MMS). */
    public final String body;
    public final boolean read;
    public final boolean outgoing;
    public final boolean mms;
    public final State state;
    /** Subscription (SIM) id it was sent or received on, or -1. */
    public final int subId;
    /** MMS image parts as content URI strings. */
    public final List<String> images;

    public MessageItem(long id, long threadId, String address, long dateMillis, String body, boolean read,
                       boolean outgoing, boolean mms, State state, int subId, List<String> images) {
        this.id = id;
        this.threadId = threadId;
        this.address = address == null ? "" : address;
        this.dateMillis = dateMillis;
        this.body = body == null ? "" : body;
        this.read = read;
        this.outgoing = outgoing;
        this.mms = mms;
        this.state = state == null ? (outgoing ? State.SENT : State.RECEIVED) : state;
        this.subId = subId;
        this.images = images == null ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(images));
    }

    /** Shorthand for an SMS with no attachments. */
    public static MessageItem sms(long id, long threadId, String address, long dateMillis, String body,
                                  boolean read, boolean outgoing) {
        return new MessageItem(id, threadId, address, dateMillis, body, read, outgoing, false,
                null, -1, null);
    }

    /** True for an incoming message the user has not read yet. */
    public boolean unread() {
        return !outgoing && !read;
    }

    /** One-line preview: the body, or a marker for an attachment-only message. */
    public String preview(String imageMarker) {
        if (!body.trim().isEmpty()) return body.trim().replace('\n', ' ');
        return images.isEmpty() ? "" : imageMarker;
    }

    /** A key unique across SMS and MMS rows. */
    public String uid() {
        return (mms ? "m" : "s") + id;
    }
}
