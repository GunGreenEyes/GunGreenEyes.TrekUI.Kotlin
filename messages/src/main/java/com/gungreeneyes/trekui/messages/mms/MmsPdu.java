package com.gungreeneyes.trekui.messages.mms;

import java.util.ArrayList;
import java.util.List;

/**
 * The headers and parts of one MMS PDU (OMA MMS encapsulation 1.2) this app reads or writes:
 * m-send-req (outgoing), m-send-conf (the carrier's answer), m-notification-ind (WAP push announcing a
 * message) and m-retrieve-conf (the downloaded message). Mutable bag filled by {@link PduCodec}.
 */
public final class MmsPdu {
    public static final int TYPE_SEND_REQ = 0x80;
    public static final int TYPE_SEND_CONF = 0x81;
    public static final int TYPE_NOTIFICATION_IND = 0x82;
    public static final int TYPE_NOTIFYRESP_IND = 0x83;
    public static final int TYPE_RETRIEVE_CONF = 0x84;
    public static final int TYPE_ACKNOWLEDGE_IND = 0x85;
    public static final int TYPE_DELIVERY_IND = 0x86;

    /** X-Mms-Response-Status "Ok". */
    public static final int RESPONSE_OK = 0x80;

    public int messageType;
    public String transactionId;
    /** MMS version as (major << 4) | minor; 0x12 = 1.2. */
    public int version = 0x12;
    /** Sender; null on an outgoing message (the carrier inserts it). */
    public String from;
    public final List<String> to = new ArrayList<>();
    public final List<String> cc = new ArrayList<>();
    public String subject;
    /** Seconds since the epoch, 0 when absent. */
    public long dateSeconds;
    /** m-notification-ind: where to download the message. */
    public String contentLocation;
    /** m-notification-ind: announced size in bytes. */
    public long messageSize;
    public String messageId;
    /** m-send-conf: X-Mms-Response-Status; 0 when absent. */
    public int responseStatus;
    /** Content type of the body ({@code application/vnd.wap.multipart.related} etc.). */
    public String contentType;
    /** multipart/related: the Start parameter (Content-ID of the SMIL part, with brackets). */
    public String start;
    /** multipart/related: the Type parameter (the start part's type). */
    public String startType;
    public final List<MmsPart> parts = new ArrayList<>();

    /** All text parts joined by newlines. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (MmsPart p : parts) {
            if (!p.isText()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(p.text());
        }
        return sb.toString();
    }

    /** The image parts. */
    public List<MmsPart> images() {
        List<MmsPart> out = new ArrayList<>();
        for (MmsPart p : parts) if (p.isImage()) out.add(p);
        return out;
    }
}
