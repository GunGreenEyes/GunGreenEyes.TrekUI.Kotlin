package com.gungreeneyes.trekui.messages.mms

import com.gungreeneyes.trekui.messages.data.PhoneNumbers
import com.gungreeneyes.trekui.messages.data.javaTrim

/**
 * Builds an outgoing m-send-req (pure, unit-tested): images and text in a multipart/related body whose
 * first part is a generated SMIL layout (image above text, one slide per image), which is what carriers
 * and receiving phones expect. Recipients are written as `<number>/TYPE=PLMN` (e-mail addresses
 * as they are).
 */
internal object MmsBuilder {
    /** Content-ID of the SMIL part; the multipart Start parameter points at it. */
    const val SMIL_ID = "smil"

    /**
     * An m-send-req to [recipients] with optional [text] and [images] (JPEG bytes).
     *
     * @param transactionId unique per message (the carrier echoes it in m-send-conf)
     * @param dateSeconds   send time, seconds since the epoch
     */
    fun sendRequest(
        recipients: List<String>,
        text: String?,
        images: List<ByteArray>?,
        transactionId: String,
        dateSeconds: Long,
    ): MmsPdu {
        val imageCount = images?.size ?: 0
        val body = text?.takeIf { it.javaTrim().isNotEmpty() }
        val hasText = body != null
        return MmsPdu().apply {
            messageType = MmsPdu.TYPE_SEND_REQ
            this.transactionId = transactionId
            this.dateSeconds = dateSeconds
            recipients.mapTo(to, ::mmsAddress)
            contentType = PduCodec.MULTIPART_RELATED
            start = "<$SMIL_ID>"
            startType = PduCodec.SMIL
            parts.add(
                MmsPart(PduCodec.SMIL, "smil.xml", SMIL_ID, PduCodec.CHARSET_UTF8,
                    smil(imageCount, hasText).toByteArray(Charsets.UTF_8)),
            )
            images?.forEachIndexed { i, jpeg -> parts.add(MmsPart.image(PduCodec.IMAGE_JPEG, jpeg, "image$i.jpg")) }
            body?.let { parts.add(MmsPart.text(it, "text0.txt")) }
        }
    }

    /** A phone number as an MMS address (`+15550102345/TYPE=PLMN`); e-mail unchanged. */
    fun mmsAddress(address: String?): String {
        val n = PhoneNumbers.normalize(address)
        if ('@' in n || !PhoneNumbers.isPhoneLike(n)) return n
        return "$n/TYPE=PLMN"
    }

    /**
     * SMIL layout: an Image region above a Text region; one `<par>` slide per image, the text on
     * the first slide (or a text-only slide when there are no images).
     */
    fun smil(imageCount: Int, hasText: Boolean): String = buildString {
        append("<smil><head><layout><root-layout/>")
        if (imageCount > 0) append("<region id=\"Image\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\" fit=\"meet\"/>")
        if (hasText) append("<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\" fit=\"scroll\"/>")
        append("</layout></head><body>")
        for (i in 0 until maxOf(1, imageCount)) {
            append("<par dur=\"5000ms\">")
            if (i < imageCount) append("<img src=\"image").append(i).append(".jpg\" region=\"Image\"/>")
            if (hasText && i == 0) append("<text src=\"text0.txt\" region=\"Text\"/>")
            append("</par>")
        }
        append("</body></smil>")
    }
}
