package com.gungreeneyes.trekui.messages.mms;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * A small, self-contained MMS PDU encoder/decoder (OMA-TS-MMS-ENC 1.2 over WAP WSP encoding), written
 * for this app and unit-tested by round trip. It covers what a default SMS app exchanges with the
 * platform MMS service:
 * <ul>
 *   <li>encode m-send-req (text + images + SMIL, multipart/related) for {@code SmsManager.sendMultimediaMessage};</li>
 *   <li>decode m-notification-ind (WAP push: where to download), m-retrieve-conf (the downloaded message)
 *       and m-send-conf (message id / status after sending).</li>
 * </ul>
 * Header values it does not need are skipped by their generic WSP length rules, so unknown or vendor
 * headers never break parsing. Not covered: DRM parts, nested multiparts (flattened as opaque parts),
 * delivery/read-report PDUs.
 */
public final class PduCodec {
    private PduCodec() {}

    // ---- header field codes (well-known, high bit set) ------------------------------------------
    static final int H_BCC = 0x81, H_CC = 0x82, H_CONTENT_LOCATION = 0x83, H_CONTENT_TYPE = 0x84,
            H_DATE = 0x85, H_DELIVERY_REPORT = 0x86, H_EXPIRY = 0x88, H_FROM = 0x89, H_MESSAGE_CLASS = 0x8A,
            H_MESSAGE_ID = 0x8B, H_MESSAGE_TYPE = 0x8C, H_MMS_VERSION = 0x8D, H_MESSAGE_SIZE = 0x8E,
            H_READ_REPORT = 0x90, H_RESPONSE_STATUS = 0x92, H_SUBJECT = 0x96, H_TO = 0x97,
            H_TRANSACTION_ID = 0x98;

    /** Part header fields (WSP well-known). */
    static final int P_CONTENT_LOCATION = 0x8E, P_CONTENT_ID = 0xC0;

    /** Content-type parameters (WSP well-known). */
    static final int PARAM_CHARSET = 0x81, PARAM_NAME = 0x85, PARAM_FILENAME = 0x86, PARAM_TYPE = 0x89,
            PARAM_START = 0x8A, PARAM_NAME_14 = 0x97, PARAM_FILENAME_14 = 0x98, PARAM_START_14 = 0x99;

    static final int FROM_ADDRESS_PRESENT = 0x80, FROM_INSERT_ADDRESS = 0x81;
    static final int YES = 0x80, NO = 0x81;
    static final int CLASS_PERSONAL = 0x80;
    static final int EXPIRY_RELATIVE = 0x81;
    /** Text-string quote (a string whose first byte is 0x80 or higher starts with it). */
    static final int QUOTE = 0x7F;
    /** Quoted-string marker. */
    static final int QUOTED_STRING = 0x22;
    /** Value-length escape: the length follows as a uintvar. */
    static final int LENGTH_QUOTE = 31;

    /** IANA MIBenum of UTF-8. */
    public static final int CHARSET_UTF8 = 106;

    public static final String TEXT_PLAIN = "text/plain";
    public static final String IMAGE_JPEG = "image/jpeg";
    public static final String SMIL = "application/smil";
    public static final String MULTIPART_MIXED = "application/vnd.wap.multipart.mixed";
    public static final String MULTIPART_RELATED = "application/vnd.wap.multipart.related";

    /** WSP well-known content types we read or write, by assigned number (null = unassigned here). */
    private static final String[] CONTENT_TYPES = new String[0x34];

    static {
        CONTENT_TYPES[0x00] = "*/*";
        CONTENT_TYPES[0x01] = "text/*";
        CONTENT_TYPES[0x02] = "text/html";
        CONTENT_TYPES[0x03] = TEXT_PLAIN;
        CONTENT_TYPES[0x06] = "text/x-vcalendar";
        CONTENT_TYPES[0x07] = "text/x-vcard";
        CONTENT_TYPES[0x0B] = "multipart/*";
        CONTENT_TYPES[0x0C] = "multipart/mixed";
        CONTENT_TYPES[0x0F] = "multipart/alternative";
        CONTENT_TYPES[0x10] = "application/*";
        CONTENT_TYPES[0x1C] = "image/*";
        CONTENT_TYPES[0x1D] = "image/gif";
        CONTENT_TYPES[0x1E] = IMAGE_JPEG;
        CONTENT_TYPES[0x1F] = "image/tiff";
        CONTENT_TYPES[0x20] = "image/png";
        CONTENT_TYPES[0x21] = "image/vnd.wap.wbmp";
        CONTENT_TYPES[0x22] = "application/vnd.wap.multipart.*";
        CONTENT_TYPES[0x23] = MULTIPART_MIXED;
        CONTENT_TYPES[0x26] = "application/vnd.wap.multipart.alternative";
        CONTENT_TYPES[0x27] = "application/xml";
        CONTENT_TYPES[0x28] = "text/xml";
        CONTENT_TYPES[0x33] = MULTIPART_RELATED;
    }

    // =============================================================================================
    // Encoding
    // =============================================================================================

    /**
     * Encodes {@code pdu}. Headers are written in the order the spec requires (type, transaction id,
     * version first; content type last, followed by the body for send-req / retrieve-conf).
     */
    public static byte[] encode(MmsPdu pdu) {
        Out o = new Out();
        o.write(H_MESSAGE_TYPE);
        o.write(pdu.messageType);
        if (pdu.transactionId != null) {
            o.write(H_TRANSACTION_ID);
            o.text(pdu.transactionId);
        }
        o.write(H_MMS_VERSION);
        o.write(0x80 | (pdu.version & 0x7F));
        if (pdu.messageType == MmsPdu.TYPE_SEND_CONF) {
            o.write(H_RESPONSE_STATUS);
            o.write(pdu.responseStatus == 0 ? MmsPdu.RESPONSE_OK : pdu.responseStatus);
            if (pdu.messageId != null) {
                o.write(H_MESSAGE_ID);
                o.text(pdu.messageId);
            }
            return o.bytes();
        }
        if (pdu.messageId != null && pdu.messageType == MmsPdu.TYPE_RETRIEVE_CONF) {
            o.write(H_MESSAGE_ID);
            o.text(pdu.messageId);
        }
        if (pdu.dateSeconds > 0) {
            o.write(H_DATE);
            o.longInteger(pdu.dateSeconds);
        }
        o.write(H_FROM);
        if (pdu.from == null) {
            o.valueLength(1);
            o.write(FROM_INSERT_ADDRESS);
        } else {
            Out a = new Out();
            a.write(FROM_ADDRESS_PRESENT);
            a.encodedString(pdu.from);
            o.valueLength(a.size());
            o.write(a.bytes());
        }
        for (String t : pdu.to) {
            o.write(H_TO);
            o.encodedString(t);
        }
        for (String c : pdu.cc) {
            o.write(H_CC);
            o.encodedString(c);
        }
        if (pdu.subject != null && !pdu.subject.isEmpty()) {
            o.write(H_SUBJECT);
            o.encodedString(pdu.subject);
        }
        if (pdu.messageType == MmsPdu.TYPE_NOTIFICATION_IND) {
            o.write(H_MESSAGE_CLASS);
            o.write(CLASS_PERSONAL);
            o.write(H_MESSAGE_SIZE);
            o.longInteger(pdu.messageSize);
            // Expiry is mandatory here: relative, 7 days
            Out e = new Out();
            e.write(EXPIRY_RELATIVE);
            e.longInteger(7L * 24 * 3600);
            o.write(H_EXPIRY);
            o.valueLength(e.size());
            o.write(e.bytes());
            o.write(H_CONTENT_LOCATION);
            o.text(pdu.contentLocation == null ? "" : pdu.contentLocation);
            return o.bytes();
        }
        if (pdu.messageType == MmsPdu.TYPE_SEND_REQ) {
            o.write(H_MESSAGE_CLASS);
            o.write(CLASS_PERSONAL);
            o.write(H_DELIVERY_REPORT);
            o.write(NO);
            o.write(H_READ_REPORT);
            o.write(NO);
        }
        o.write(H_CONTENT_TYPE);
        String type = pdu.contentType != null ? pdu.contentType
                : pdu.start != null ? MULTIPART_RELATED : MULTIPART_MIXED;
        o.contentType(type, 0, null, pdu.start, pdu.startType);
        o.multipart(pdu.parts);
        return o.bytes();
    }

    // =============================================================================================
    // Decoding
    // =============================================================================================

    /** Decodes a PDU; null when it is too malformed to read (never throws on bad input). */
    public static MmsPdu decode(byte[] data) {
        if (data == null || data.length < 2) return null;
        try {
            return decodeOrThrow(new In(data, 0, data.length));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static MmsPdu decodeOrThrow(In in) {
        MmsPdu pdu = new MmsPdu();
        while (in.pos < in.end) {
            int h = in.u8();
            if (h < 0x80) { // application header: token-text + text value
                in.pos--;
                in.text();
                in.text();
                continue;
            }
            switch (h) {
                case H_MESSAGE_TYPE: pdu.messageType = in.u8(); break;
                case H_TRANSACTION_ID: pdu.transactionId = in.text(); break;
                case H_MMS_VERSION: {
                    if (in.peek() >= 0x80) pdu.version = in.u8() & 0x7F;
                    else in.skipValue();
                    break;
                }
                case H_DATE: pdu.dateSeconds = in.longInteger(); break;
                case H_FROM: {
                    int len = in.valueLength();
                    int stop = in.pos + len;
                    int token = in.u8();
                    if (token == FROM_ADDRESS_PRESENT && in.pos < stop) pdu.from = in.encodedString();
                    in.pos = stop;
                    break;
                }
                case H_TO: pdu.to.add(in.encodedString()); break;
                case H_CC: pdu.cc.add(in.encodedString()); break;
                case H_SUBJECT: pdu.subject = in.encodedString(); break;
                case H_CONTENT_LOCATION: pdu.contentLocation = in.text(); break;
                case H_MESSAGE_SIZE: pdu.messageSize = in.longInteger(); break;
                case H_MESSAGE_ID: pdu.messageId = in.text(); break;
                case H_RESPONSE_STATUS: pdu.responseStatus = in.u8(); break;
                case H_CONTENT_TYPE: {
                    ContentType ct = in.contentType();
                    pdu.contentType = ct.type;
                    pdu.start = ct.start;
                    pdu.startType = ct.startType;
                    if (ct.type.toLowerCase(Locale.ROOT).startsWith("application/vnd.wap.multipart")) {
                        in.multipart(pdu.parts);
                    } else {
                        pdu.parts.add(new MmsPart(ct.type, ct.name, null, ct.charset,
                                Arrays.copyOfRange(in.data, in.pos, in.end)));
                    }
                    in.pos = in.end;
                    break;
                }
                default: in.skipValue();
            }
        }
        return pdu;
    }

    /** Decodes {@code data} in IANA charset {@code mib} (UTF-8 when unknown or 0). */
    public static String decodeText(byte[] data, int mib) {
        Charset cs;
        switch (mib) {
            case 3: cs = StandardCharsets.US_ASCII; break;
            case 4: cs = StandardCharsets.ISO_8859_1; break;
            case 1000: cs = StandardCharsets.UTF_16BE; break;
            case 1013: cs = StandardCharsets.UTF_16BE; break;
            case 1014: cs = StandardCharsets.UTF_16LE; break;
            case 1015: cs = StandardCharsets.UTF_16; break;
            default: cs = StandardCharsets.UTF_8;
        }
        return new String(data, cs);
    }

    /** The well-known content type with assigned number {@code n}, or a placeholder for unknown ones. */
    static String wellKnownType(int n) {
        if (n >= 0 && n < CONTENT_TYPES.length && CONTENT_TYPES[n] != null) return CONTENT_TYPES[n];
        return "application/x-wsp-" + Integer.toHexString(n);
    }

    /** The assigned number of a well-known content type, or -1. */
    static int wellKnownNumber(String type) {
        if (type == null) return -1;
        String t = type.toLowerCase(Locale.ROOT);
        for (int i = 0; i < CONTENT_TYPES.length; i++) if (t.equals(CONTENT_TYPES[i])) return i;
        return -1;
    }

    /** A parsed Content-Type value. */
    static final class ContentType {
        String type;
        int charset;
        String name;
        String start;
        String startType;
    }

    // =============================================================================================
    // Byte-level writer / reader
    // =============================================================================================

    /** Growing output buffer with the WSP primitive encoders. */
    static final class Out {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        void write(int b) { buf.write(b & 0xFF); }
        void write(byte[] b) { buf.write(b, 0, b.length); }
        int size() { return buf.size(); }
        byte[] bytes() { return buf.toByteArray(); }

        /** Unsigned variable-length integer: 7 bits per byte, high bit = more follow. */
        void uintvar(long v) {
            int n = 1;
            for (long t = v >>> 7; t != 0; t >>>= 7) n++;
            for (int i = n - 1; i >= 0; i--) {
                int b = (int) ((v >>> (7 * i)) & 0x7F);
                write(i > 0 ? b | 0x80 : b);
            }
        }

        /** Value-length: one byte up to 30, else 31 + uintvar. */
        void valueLength(int len) {
            if (len < LENGTH_QUOTE) write(len);
            else {
                write(LENGTH_QUOTE);
                uintvar(len);
            }
        }

        /** Long-integer: a length byte then the big-endian value without leading zero bytes. */
        void longInteger(long v) {
            int n = 0;
            for (long t = v; t != 0; t >>>= 8) n++;
            if (n == 0) n = 1;
            write(n);
            for (int i = n - 1; i >= 0; i--) write((int) (v >>> (8 * i)));
        }

        /** Integer-value: short-integer below 128, else long-integer. */
        void integerValue(long v) {
            if (v >= 0 && v < 0x80) write(0x80 | (int) v);
            else longInteger(v);
        }

        /** Text-string: UTF-8 bytes + NUL, quoted with 0x7F when the first byte has the high bit. */
        void text(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            if (b.length > 0 && (b[0] & 0x80) != 0) write(QUOTE);
            write(b);
            write(0);
        }

        /** Quoted-string: '"' + bytes + NUL. */
        void quoted(String s) {
            write(QUOTED_STRING);
            write(s.getBytes(StandardCharsets.UTF_8));
            write(0);
        }

        /** Encoded-string-value: a plain text-string when ASCII, else value-length + UTF-8 charset + text. */
        void encodedString(String s) {
            if (isAscii(s)) {
                text(s);
                return;
            }
            Out inner = new Out();
            inner.integerValue(CHARSET_UTF8);
            inner.text(s);
            valueLength(inner.size());
            write(inner.bytes());
        }

        /** Content-type: short form for a parameterless well-known type, else the general form. */
        void contentType(String type, int charset, String name, String start, String startType) {
            int known = wellKnownNumber(type);
            boolean params = charset > 0 || name != null || start != null || startType != null;
            if (!params && known >= 0) {
                write(0x80 | known);
                return;
            }
            Out v = new Out();
            if (known >= 0) v.write(0x80 | known);
            else v.text(type);
            if (charset > 0) {
                v.write(PARAM_CHARSET);
                v.integerValue(charset);
            }
            if (startType != null) {
                v.write(PARAM_TYPE);
                v.text(startType);
            }
            if (start != null) {
                v.write(PARAM_START);
                v.text(start);
            }
            if (name != null) {
                v.write(PARAM_NAME);
                v.text(name);
            }
            valueLength(v.size());
            write(v.bytes());
        }

        /** Multipart body: entry count, then per entry header length, data length, headers, data. */
        void multipart(java.util.List<MmsPart> parts) {
            uintvar(parts.size());
            for (MmsPart p : parts) {
                Out h = new Out();
                h.contentType(p.contentType, p.charset, p.location, null, null);
                if (p.location != null) {
                    h.write(P_CONTENT_LOCATION);
                    h.text(p.location);
                }
                if (p.contentId != null) {
                    h.write(P_CONTENT_ID);
                    h.quoted("<" + p.contentId + ">");
                }
                uintvar(h.size());
                uintvar(p.data.length);
                write(h.bytes());
                write(p.data);
            }
        }
    }

    /** Cursor over a PDU byte array with the WSP primitive decoders; throws on truncated input. */
    static final class In {
        final byte[] data;
        int pos;
        final int end;

        In(byte[] data, int pos, int end) {
            this.data = data;
            this.pos = pos;
            this.end = end;
        }

        int peek() {
            if (pos >= end) throw new IllegalArgumentException("truncated PDU");
            return data[pos] & 0xFF;
        }

        int u8() {
            int b = peek();
            pos++;
            return b;
        }

        long uintvar() {
            long v = 0;
            for (int i = 0; i < 5; i++) {
                int b = u8();
                v = (v << 7) | (b & 0x7F);
                if ((b & 0x80) == 0) return v;
            }
            throw new IllegalArgumentException("uintvar too long");
        }

        int valueLength() {
            int b = u8();
            if (b < LENGTH_QUOTE) return b;
            if (b == LENGTH_QUOTE) return checkedLength(uintvar());
            throw new IllegalArgumentException("not a value-length");
        }

        private int checkedLength(long len) {
            if (len < 0 || pos + len > end) throw new IllegalArgumentException("length past end");
            return (int) len;
        }

        long longInteger() {
            int n = u8();
            if (n > 8) throw new IllegalArgumentException("long-integer too long");
            long v = 0;
            for (int i = 0; i < n; i++) v = (v << 8) | u8();
            return v;
        }

        long integerValue() {
            int b = peek();
            if (b >= 0x80) {
                pos++;
                return b & 0x7F;
            }
            return longInteger();
        }

        /** Text-string or quoted-string (marker dropped), UTF-8 decoded, up to the NUL. */
        String text() {
            int b = peek();
            if (b == QUOTE || b == QUOTED_STRING) pos++;
            int start = pos;
            while (pos < end && data[pos] != 0) pos++;
            if (pos >= end) throw new IllegalArgumentException("unterminated text");
            String s = new String(data, start, pos - start, StandardCharsets.UTF_8);
            pos++; // NUL
            return s;
        }

        /** Encoded-string-value: text-string, or value-length + charset + text in that charset. */
        String encodedString() {
            int b = peek();
            if (b > LENGTH_QUOTE) return text();
            int len = valueLength();
            int stop = pos + len;
            int charset = (int) integerValue();
            if (pos < stop && (data[pos] & 0xFF) == QUOTE) pos++;
            int start = pos;
            while (pos < stop && data[pos] != 0) pos++;
            String s = decodeText(Arrays.copyOfRange(data, start, pos), charset);
            pos = stop;
            return s;
        }

        /** Skips any header value by the generic WSP rules (length-prefixed, text or one byte). */
        void skipValue() {
            int b = peek();
            if (b <= LENGTH_QUOTE) {
                int len = valueLength();
                pos += len;
            } else if (b < 0x80) {
                text();
            } else {
                pos++;
            }
        }

        ContentType contentType() {
            ContentType ct = new ContentType();
            int b = peek();
            if (b >= 0x80) {
                pos++;
                ct.type = wellKnownType(b & 0x7F);
                return ct;
            }
            if (b > LENGTH_QUOTE) {
                ct.type = text();
                return ct;
            }
            int len = valueLength();
            int stop = pos + len;
            int m = peek();
            if (m >= 0x80) {
                pos++;
                ct.type = wellKnownType(m & 0x7F);
            } else if (m > LENGTH_QUOTE) {
                ct.type = text();
            } else {
                ct.type = wellKnownType((int) longInteger());
            }
            while (pos < stop) {
                int p = peek();
                if (p >= 0x80) {
                    pos++;
                    switch (p) {
                        case PARAM_CHARSET: ct.charset = (int) integerValue(); break;
                        case PARAM_TYPE: {
                            if (peek() >= 0x80) ct.startType = wellKnownType(u8() & 0x7F);
                            else ct.startType = text();
                            break;
                        }
                        case PARAM_START:
                        case PARAM_START_14: ct.start = text(); break;
                        case PARAM_NAME:
                        case PARAM_NAME_14:
                        case PARAM_FILENAME:
                        case PARAM_FILENAME_14: {
                            String n = text();
                            if (ct.name == null) ct.name = n;
                            break;
                        }
                        default: skipValue();
                    }
                } else {
                    text();      // untyped parameter name
                    skipValue(); // and its value
                }
            }
            pos = stop;
            return ct;
        }

        void multipart(java.util.List<MmsPart> out) {
            long count = uintvar();
            for (long i = 0; i < count && pos < end; i++) {
                int headersLen = checkedLength(uintvar());
                int dataLen = (int) uintvar();
                int headersStart = pos;
                int headersEnd = headersStart + headersLen;
                ContentType ct = contentType();
                String location = null, cid = null;
                while (pos < headersEnd) {
                    int h = u8();
                    if (h == P_CONTENT_LOCATION) location = text();
                    else if (h == P_CONTENT_ID) cid = stripBrackets(text());
                    else if (h >= 0x80) skipValue();
                    else {
                        pos--;
                        text();
                        text();
                    }
                }
                pos = headersEnd;
                if (dataLen < 0 || pos + dataLen > end) throw new IllegalArgumentException("part past end");
                byte[] body = Arrays.copyOfRange(data, pos, pos + dataLen);
                pos += dataLen;
                out.add(new MmsPart(ct.type, location != null ? location : ct.name, cid, ct.charset, body));
            }
        }
    }

    static String stripBrackets(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("<") && t.endsWith(">") && t.length() >= 2) t = t.substring(1, t.length() - 1);
        return t;
    }

    static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) >= 0x80) return false;
        return true;
    }
}
