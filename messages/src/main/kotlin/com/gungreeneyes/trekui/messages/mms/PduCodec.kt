package com.gungreeneyes.trekui.messages.mms

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Locale

/**
 * A small, self-contained MMS PDU encoder/decoder (OMA-TS-MMS-ENC 1.2 over WAP WSP encoding), written
 * for this app and unit-tested by round trip. It covers what a default SMS app exchanges with the
 * platform MMS service:
 * - encode m-send-req (text + images + SMIL, multipart/related) for `SmsManager.sendMultimediaMessage`;
 * - decode m-notification-ind (WAP push: where to download), m-retrieve-conf (the downloaded message)
 *   and m-send-conf (message id / status after sending).
 *
 * Header values it does not need are skipped by their generic WSP length rules, so unknown or vendor
 * headers never break parsing. Not covered: DRM parts, nested multiparts (flattened as opaque parts),
 * delivery/read-report PDUs.
 */
internal object PduCodec {
    // ---- header field codes (well-known, high bit set) ------------------------------------------
    const val H_BCC = 0x81
    const val H_CC = 0x82
    const val H_CONTENT_LOCATION = 0x83
    const val H_CONTENT_TYPE = 0x84
    const val H_DATE = 0x85
    const val H_DELIVERY_REPORT = 0x86
    const val H_EXPIRY = 0x88
    const val H_FROM = 0x89
    const val H_MESSAGE_CLASS = 0x8A
    const val H_MESSAGE_ID = 0x8B
    const val H_MESSAGE_TYPE = 0x8C
    const val H_MMS_VERSION = 0x8D
    const val H_MESSAGE_SIZE = 0x8E
    const val H_READ_REPORT = 0x90
    const val H_RESPONSE_STATUS = 0x92
    const val H_SUBJECT = 0x96
    const val H_TO = 0x97
    const val H_TRANSACTION_ID = 0x98

    /** Part header fields (WSP well-known). */
    const val P_CONTENT_LOCATION = 0x8E
    const val P_CONTENT_ID = 0xC0

    /** Content-type parameters (WSP well-known). */
    const val PARAM_CHARSET = 0x81
    const val PARAM_NAME = 0x85
    const val PARAM_FILENAME = 0x86
    const val PARAM_TYPE = 0x89
    const val PARAM_START = 0x8A
    const val PARAM_NAME_14 = 0x97
    const val PARAM_FILENAME_14 = 0x98
    const val PARAM_START_14 = 0x99

    const val FROM_ADDRESS_PRESENT = 0x80
    const val FROM_INSERT_ADDRESS = 0x81
    const val YES = 0x80
    const val NO = 0x81
    const val CLASS_PERSONAL = 0x80
    const val EXPIRY_RELATIVE = 0x81

    /** Text-string quote (a string whose first byte is 0x80 or higher starts with it). */
    const val QUOTE = 0x7F

    /** Quoted-string marker. */
    const val QUOTED_STRING = 0x22

    /** Value-length escape: the length follows as a uintvar. */
    const val LENGTH_QUOTE = 31

    /** IANA MIBenum of UTF-8. */
    const val CHARSET_UTF8 = 106

    const val TEXT_PLAIN = "text/plain"
    const val IMAGE_JPEG = "image/jpeg"
    const val SMIL = "application/smil"
    const val MULTIPART_MIXED = "application/vnd.wap.multipart.mixed"
    const val MULTIPART_RELATED = "application/vnd.wap.multipart.related"

    /** WSP well-known content types we read or write, by assigned number (null = unassigned here). */
    private val CONTENT_TYPES: Array<String?> = arrayOfNulls<String>(0x34).apply {
        this[0x00] = "*/*"
        this[0x01] = "text/*"
        this[0x02] = "text/html"
        this[0x03] = TEXT_PLAIN
        this[0x06] = "text/x-vcalendar"
        this[0x07] = "text/x-vcard"
        this[0x0B] = "multipart/*"
        this[0x0C] = "multipart/mixed"
        this[0x0F] = "multipart/alternative"
        this[0x10] = "application/*"
        this[0x1C] = "image/*"
        this[0x1D] = "image/gif"
        this[0x1E] = IMAGE_JPEG
        this[0x1F] = "image/tiff"
        this[0x20] = "image/png"
        this[0x21] = "image/vnd.wap.wbmp"
        this[0x22] = "application/vnd.wap.multipart.*"
        this[0x23] = MULTIPART_MIXED
        this[0x26] = "application/vnd.wap.multipart.alternative"
        this[0x27] = "application/xml"
        this[0x28] = "text/xml"
        this[0x33] = MULTIPART_RELATED
    }

    // =============================================================================================
    // Encoding
    // =============================================================================================

    /**
     * Encodes [pdu]. Headers are written in the order the spec requires (type, transaction id,
     * version first; content type last, followed by the body for send-req / retrieve-conf).
     */
    fun encode(pdu: MmsPdu): ByteArray {
        val o = Out()
        o.write(H_MESSAGE_TYPE)
        o.write(pdu.messageType)
        pdu.transactionId?.let {
            o.write(H_TRANSACTION_ID)
            o.text(it)
        }
        o.write(H_MMS_VERSION)
        o.write(0x80 or (pdu.version and 0x7F))
        if (pdu.messageType == MmsPdu.TYPE_SEND_CONF) {
            o.write(H_RESPONSE_STATUS)
            o.write(if (pdu.responseStatus == 0) MmsPdu.RESPONSE_OK else pdu.responseStatus)
            pdu.messageId?.let {
                o.write(H_MESSAGE_ID)
                o.text(it)
            }
            return o.bytes()
        }
        val messageId = pdu.messageId
        if (messageId != null && pdu.messageType == MmsPdu.TYPE_RETRIEVE_CONF) {
            o.write(H_MESSAGE_ID)
            o.text(messageId)
        }
        if (pdu.dateSeconds > 0) {
            o.write(H_DATE)
            o.longInteger(pdu.dateSeconds)
        }
        o.write(H_FROM)
        val from = pdu.from
        if (from == null) {
            o.valueLength(1)
            o.write(FROM_INSERT_ADDRESS)
        } else {
            val a = Out()
            a.write(FROM_ADDRESS_PRESENT)
            a.encodedString(from)
            o.valueLength(a.size())
            o.write(a.bytes())
        }
        for (t in pdu.to) {
            o.write(H_TO)
            o.encodedString(t)
        }
        for (c in pdu.cc) {
            o.write(H_CC)
            o.encodedString(c)
        }
        val subject = pdu.subject
        if (!subject.isNullOrEmpty()) {
            o.write(H_SUBJECT)
            o.encodedString(subject)
        }
        if (pdu.messageType == MmsPdu.TYPE_NOTIFICATION_IND) {
            o.write(H_MESSAGE_CLASS)
            o.write(CLASS_PERSONAL)
            o.write(H_MESSAGE_SIZE)
            o.longInteger(pdu.messageSize)
            // Expiry is mandatory here: relative, 7 days
            val e = Out()
            e.write(EXPIRY_RELATIVE)
            e.longInteger(7L * 24 * 3600)
            o.write(H_EXPIRY)
            o.valueLength(e.size())
            o.write(e.bytes())
            o.write(H_CONTENT_LOCATION)
            o.text(pdu.contentLocation ?: "")
            return o.bytes()
        }
        if (pdu.messageType == MmsPdu.TYPE_SEND_REQ) {
            o.write(H_MESSAGE_CLASS)
            o.write(CLASS_PERSONAL)
            o.write(H_DELIVERY_REPORT)
            o.write(NO)
            o.write(H_READ_REPORT)
            o.write(NO)
        }
        o.write(H_CONTENT_TYPE)
        val type = pdu.contentType ?: if (pdu.start != null) MULTIPART_RELATED else MULTIPART_MIXED
        o.contentType(type, 0, null, pdu.start, pdu.startType)
        o.multipart(pdu.parts)
        return o.bytes()
    }

    // =============================================================================================
    // Decoding
    // =============================================================================================

    /** Decodes a PDU; null when it is too malformed to read (never throws on bad input). */
    fun decode(data: ByteArray?): MmsPdu? {
        if (data == null || data.size < 2) return null
        return try {
            decodeOrThrow(In(data, 0, data.size))
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun decodeOrThrow(input: In): MmsPdu {
        val pdu = MmsPdu()
        while (input.pos < input.end) {
            val h = input.u8()
            if (h < 0x80) { // application header: token-text + text value
                input.pos--
                input.text()
                input.text()
                continue
            }
            when (h) {
                H_MESSAGE_TYPE -> pdu.messageType = input.u8()
                H_TRANSACTION_ID -> pdu.transactionId = input.text()
                H_MMS_VERSION -> if (input.peek() >= 0x80) pdu.version = input.u8() and 0x7F else input.skipValue()
                H_DATE -> pdu.dateSeconds = input.longInteger()
                H_FROM -> {
                    val len = input.valueLength()
                    val stop = input.pos + len
                    val token = input.u8()
                    if (token == FROM_ADDRESS_PRESENT && input.pos < stop) pdu.from = input.encodedString()
                    input.pos = stop
                }
                H_TO -> pdu.to.add(input.encodedString())
                H_CC -> pdu.cc.add(input.encodedString())
                H_SUBJECT -> pdu.subject = input.encodedString()
                H_CONTENT_LOCATION -> pdu.contentLocation = input.text()
                H_MESSAGE_SIZE -> pdu.messageSize = input.longInteger()
                H_MESSAGE_ID -> pdu.messageId = input.text()
                H_RESPONSE_STATUS -> pdu.responseStatus = input.u8()
                H_CONTENT_TYPE -> {
                    val ct = input.contentType()
                    pdu.contentType = ct.type
                    pdu.start = ct.start
                    pdu.startType = ct.startType
                    if (ct.type.lowercase(Locale.ROOT).startsWith("application/vnd.wap.multipart")) {
                        input.multipart(pdu.parts)
                    } else {
                        pdu.parts.add(MmsPart(ct.type, ct.name, null, ct.charset, input.data.copyOfRange(input.pos, input.end)))
                    }
                    input.pos = input.end
                }
                else -> input.skipValue()
            }
        }
        return pdu
    }

    /** Decodes [data] in IANA charset [mib] (UTF-8 when unknown or 0). */
    fun decodeText(data: ByteArray, mib: Int): String {
        val cs: Charset = when (mib) {
            3 -> Charsets.US_ASCII
            4 -> Charsets.ISO_8859_1
            1000, 1013 -> Charsets.UTF_16BE
            1014 -> Charsets.UTF_16LE
            1015 -> Charsets.UTF_16
            else -> Charsets.UTF_8
        }
        return String(data, cs)
    }

    /** The well-known content type with assigned number [n], or a placeholder for unknown ones. */
    fun wellKnownType(n: Int): String = CONTENT_TYPES.getOrNull(n) ?: ("application/x-wsp-" + Integer.toHexString(n))

    /** The assigned number of a well-known content type, or -1. */
    fun wellKnownNumber(type: String?): Int {
        if (type == null) return -1
        return CONTENT_TYPES.indexOf(type.lowercase(Locale.ROOT))
    }

    /** A parsed Content-Type value. */
    class ContentType {
        var type: String = ""
        var charset: Int = 0
        var name: String? = null
        var start: String? = null
        var startType: String? = null
    }

    // =============================================================================================
    // Byte-level writer / reader
    // =============================================================================================

    /** Growing output buffer with the WSP primitive encoders. */
    class Out {
        private val buf = ByteArrayOutputStream()

        fun write(b: Int) = buf.write(b and 0xFF)
        fun write(b: ByteArray) = buf.write(b, 0, b.size)
        fun size(): Int = buf.size()
        fun bytes(): ByteArray = buf.toByteArray()

        /** Unsigned variable-length integer: 7 bits per byte, high bit = more follow. */
        fun uintvar(v: Long) {
            var n = 1
            var t = v ushr 7
            while (t != 0L) {
                n++
                t = t ushr 7
            }
            for (i in n - 1 downTo 0) {
                val b = ((v ushr (7 * i)) and 0x7F).toInt()
                write(if (i > 0) b or 0x80 else b)
            }
        }

        /** Value-length: one byte up to 30, else 31 + uintvar. */
        fun valueLength(len: Int) {
            if (len < LENGTH_QUOTE) {
                write(len)
            } else {
                write(LENGTH_QUOTE)
                uintvar(len.toLong())
            }
        }

        /** Long-integer: a length byte then the big-endian value without leading zero bytes. */
        fun longInteger(v: Long) {
            var n = 0
            var t = v
            while (t != 0L) {
                n++
                t = t ushr 8
            }
            if (n == 0) n = 1
            write(n)
            for (i in n - 1 downTo 0) write((v ushr (8 * i)).toInt())
        }

        /** Integer-value: short-integer below 128, else long-integer. */
        fun integerValue(v: Long) {
            if (v in 0 until 0x80) write(0x80 or v.toInt()) else longInteger(v)
        }

        /** Text-string: UTF-8 bytes + NUL, quoted with 0x7F when the first byte has the high bit. */
        fun text(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            if (b.isNotEmpty() && (b[0].toInt() and 0x80) != 0) write(QUOTE)
            write(b)
            write(0)
        }

        /** Quoted-string: '"' + bytes + NUL. */
        fun quoted(s: String) {
            write(QUOTED_STRING)
            write(s.toByteArray(Charsets.UTF_8))
            write(0)
        }

        /** Encoded-string-value: a plain text-string when ASCII, else value-length + UTF-8 charset + text. */
        fun encodedString(s: String) {
            if (isAscii(s)) {
                text(s)
                return
            }
            val inner = Out()
            inner.integerValue(CHARSET_UTF8.toLong())
            inner.text(s)
            valueLength(inner.size())
            write(inner.bytes())
        }

        /** Content-type: short form for a parameterless well-known type, else the general form. */
        fun contentType(type: String, charset: Int, name: String?, start: String?, startType: String?) {
            val known = wellKnownNumber(type)
            val params = charset > 0 || name != null || start != null || startType != null
            if (!params && known >= 0) {
                write(0x80 or known)
                return
            }
            val v = Out()
            if (known >= 0) v.write(0x80 or known) else v.text(type)
            if (charset > 0) {
                v.write(PARAM_CHARSET)
                v.integerValue(charset.toLong())
            }
            if (startType != null) {
                v.write(PARAM_TYPE)
                v.text(startType)
            }
            if (start != null) {
                v.write(PARAM_START)
                v.text(start)
            }
            if (name != null) {
                v.write(PARAM_NAME)
                v.text(name)
            }
            valueLength(v.size())
            write(v.bytes())
        }

        /** Multipart body: entry count, then per entry header length, data length, headers, data. */
        fun multipart(parts: List<MmsPart>) {
            uintvar(parts.size.toLong())
            for (p in parts) {
                val h = Out()
                h.contentType(p.contentType, p.charset, p.location, null, null)
                p.location?.let {
                    h.write(P_CONTENT_LOCATION)
                    h.text(it)
                }
                p.contentId?.let {
                    h.write(P_CONTENT_ID)
                    h.quoted("<$it>")
                }
                uintvar(h.size().toLong())
                uintvar(p.data.size.toLong())
                write(h.bytes())
                write(p.data)
            }
        }
    }

    /** Cursor over a PDU byte array with the WSP primitive decoders; throws on truncated input. */
    class In(val data: ByteArray, var pos: Int, val end: Int) {
        fun peek(): Int {
            if (pos >= end) throw IllegalArgumentException("truncated PDU")
            return data[pos].toInt() and 0xFF
        }

        fun u8(): Int {
            val b = peek()
            pos++
            return b
        }

        fun uintvar(): Long {
            var v = 0L
            repeat(5) {
                val b = u8()
                v = (v shl 7) or (b and 0x7F).toLong()
                if ((b and 0x80) == 0) return v
            }
            throw IllegalArgumentException("uintvar too long")
        }

        fun valueLength(): Int {
            val b = u8()
            if (b < LENGTH_QUOTE) return b
            if (b == LENGTH_QUOTE) return checkedLength(uintvar())
            throw IllegalArgumentException("not a value-length")
        }

        fun checkedLength(len: Long): Int {
            if (len < 0 || pos + len > end) throw IllegalArgumentException("length past end")
            return len.toInt()
        }

        fun longInteger(): Long {
            val n = u8()
            if (n > 8) throw IllegalArgumentException("long-integer too long")
            var v = 0L
            repeat(n) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun integerValue(): Long {
            val b = peek()
            if (b >= 0x80) {
                pos++
                return (b and 0x7F).toLong()
            }
            return longInteger()
        }

        /** Text-string or quoted-string (marker dropped), UTF-8 decoded, up to the NUL. */
        fun text(): String {
            val b = peek()
            if (b == QUOTE || b == QUOTED_STRING) pos++
            val start = pos
            while (pos < end && data[pos].toInt() != 0) pos++
            if (pos >= end) throw IllegalArgumentException("unterminated text")
            val s = String(data, start, pos - start, Charsets.UTF_8)
            pos++ // NUL
            return s
        }

        /** Encoded-string-value: text-string, or value-length + charset + text in that charset. */
        fun encodedString(): String {
            val b = peek()
            if (b > LENGTH_QUOTE) return text()
            val len = valueLength()
            val stop = pos + len
            val charset = integerValue().toInt()
            if (pos < stop && (data[pos].toInt() and 0xFF) == QUOTE) pos++
            val start = pos
            while (pos < stop && data[pos].toInt() != 0) pos++
            val s = decodeText(data.copyOfRange(start, pos), charset)
            pos = stop
            return s
        }

        /** Skips any header value by the generic WSP rules (length-prefixed, text or one byte). */
        fun skipValue() {
            val b = peek()
            when {
                b <= LENGTH_QUOTE -> {
                    val len = valueLength()
                    pos += len
                }
                b < 0x80 -> text()
                else -> pos++
            }
        }

        fun contentType(): ContentType {
            val ct = ContentType()
            val b = peek()
            if (b >= 0x80) {
                pos++
                ct.type = wellKnownType(b and 0x7F)
                return ct
            }
            if (b > LENGTH_QUOTE) {
                ct.type = text()
                return ct
            }
            val len = valueLength()
            val stop = pos + len
            val m = peek()
            ct.type = when {
                m >= 0x80 -> {
                    pos++
                    wellKnownType(m and 0x7F)
                }
                m > LENGTH_QUOTE -> text()
                else -> wellKnownType(longInteger().toInt())
            }
            while (pos < stop) {
                val p = peek()
                if (p >= 0x80) {
                    pos++
                    when (p) {
                        PARAM_CHARSET -> ct.charset = integerValue().toInt()
                        PARAM_TYPE -> ct.startType = if (peek() >= 0x80) wellKnownType(u8() and 0x7F) else text()
                        PARAM_START, PARAM_START_14 -> ct.start = text()
                        PARAM_NAME, PARAM_NAME_14, PARAM_FILENAME, PARAM_FILENAME_14 -> {
                            val n = text()
                            if (ct.name == null) ct.name = n
                        }
                        else -> skipValue()
                    }
                } else {
                    text() // untyped parameter name
                    skipValue() // and its value
                }
            }
            pos = stop
            return ct
        }

        fun multipart(out: MutableList<MmsPart>) {
            val count = uintvar()
            var i = 0L
            while (i < count && pos < end) {
                val headersLen = checkedLength(uintvar())
                val dataLen = uintvar().toInt()
                val headersEnd = pos + headersLen
                val ct = contentType()
                var location: String? = null
                var cid: String? = null
                while (pos < headersEnd) {
                    val h = u8()
                    when {
                        h == P_CONTENT_LOCATION -> location = text()
                        h == P_CONTENT_ID -> cid = stripBrackets(text())
                        h >= 0x80 -> skipValue()
                        else -> {
                            pos--
                            text()
                            text()
                        }
                    }
                }
                pos = headersEnd
                if (dataLen < 0 || pos + dataLen > end) throw IllegalArgumentException("part past end")
                val body = data.copyOfRange(pos, pos + dataLen)
                pos += dataLen
                out.add(MmsPart(ct.type, location ?: ct.name, cid, ct.charset, body))
                i++
            }
        }
    }

    fun stripBrackets(s: String?): String? {
        if (s == null) return null
        var t = s.trim { it <= ' ' }
        if (t.startsWith("<") && t.endsWith(">") && t.length >= 2) t = t.substring(1, t.length - 1)
        return t
    }

    fun isAscii(s: String): Boolean = s.all { it.code < 0x80 }
}
