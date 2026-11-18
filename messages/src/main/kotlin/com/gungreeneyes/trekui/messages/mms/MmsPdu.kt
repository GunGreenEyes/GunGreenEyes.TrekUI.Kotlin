package com.gungreeneyes.trekui.messages.mms

/**
 * The headers and parts of one MMS PDU (OMA MMS encapsulation 1.2) this app reads or writes:
 * m-send-req (outgoing), m-send-conf (the carrier's answer), m-notification-ind (WAP push announcing a
 * message) and m-retrieve-conf (the downloaded message). Mutable bag filled by [PduCodec].
 */
internal class MmsPdu {
    var messageType: Int = 0
    var transactionId: String? = null

    /** MMS version as (major << 4) | minor; 0x12 = 1.2. */
    var version: Int = 0x12

    /** Sender; null on an outgoing message (the carrier inserts it). */
    var from: String? = null
    val to: MutableList<String> = ArrayList()
    val cc: MutableList<String> = ArrayList()
    var subject: String? = null

    /** Seconds since the epoch, 0 when absent. */
    var dateSeconds: Long = 0

    /** m-notification-ind: where to download the message. */
    var contentLocation: String? = null

    /** m-notification-ind: announced size in bytes. */
    var messageSize: Long = 0
    var messageId: String? = null

    /** m-send-conf: X-Mms-Response-Status; 0 when absent. */
    var responseStatus: Int = 0

    /** Content type of the body (`application/vnd.wap.multipart.related` etc.). */
    var contentType: String? = null

    /** multipart/related: the Start parameter (Content-ID of the SMIL part, with brackets). */
    var start: String? = null

    /** multipart/related: the Type parameter (the start part's type). */
    var startType: String? = null
    val parts: MutableList<MmsPart> = ArrayList()

    /** All text parts joined by newlines. */
    fun text(): String = parts.filter { it.isText }.joinToString("\n") { it.text() }

    /** The image parts. */
    fun images(): List<MmsPart> = parts.filter { it.isImage }

    companion object {
        const val TYPE_SEND_REQ = 0x80
        const val TYPE_SEND_CONF = 0x81
        const val TYPE_NOTIFICATION_IND = 0x82
        const val TYPE_NOTIFYRESP_IND = 0x83
        const val TYPE_RETRIEVE_CONF = 0x84
        const val TYPE_ACKNOWLEDGE_IND = 0x85
        const val TYPE_DELIVERY_IND = 0x86

        /** X-Mms-Response-Status "Ok". */
        const val RESPONSE_OK = 0x80
    }
}
