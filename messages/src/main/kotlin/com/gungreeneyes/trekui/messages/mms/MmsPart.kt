package com.gungreeneyes.trekui.messages.mms

import java.util.Locale

/**
 * One body part of an MMS (text, image or SMIL layout). Plain value object (a regular class rather than
 * a data class: its bytes are an array, which a data class would compare by identity).
 *
 * @param contentType MIME type, e.g. `text/plain`, `image/jpeg`, `application/smil`
 *   (`application/octet-stream` when null)
 * @property location Content-Location / file name, e.g. `image0.jpg`; may be null
 * @property contentId Content-ID without angle brackets, e.g. `image0`; may be null
 * @property charset IANA MIBenum charset of a text part (106 = UTF-8), or 0 when not given
 */
internal class MmsPart(
    contentType: String?,
    val location: String?,
    val contentId: String?,
    val charset: Int,
    data: ByteArray?,
) {
    /** MIME type, e.g. `text/plain`, `image/jpeg`, `application/smil`. */
    val contentType: String = contentType ?: "application/octet-stream"
    val data: ByteArray = data ?: ByteArray(0)

    val isText: Boolean get() = contentType.lowercase(Locale.ROOT).startsWith("text/plain")

    val isImage: Boolean get() = contentType.lowercase(Locale.ROOT).startsWith("image/")

    val isSmil: Boolean get() = contentType.lowercase(Locale.ROOT).startsWith("application/smil")

    /** The text of a text part, decoded with its charset (UTF-8 when unknown). */
    fun text(): String = PduCodec.decodeText(data, charset)

    companion object {
        /** A UTF-8 text/plain part. */
        fun text(text: String, name: String?): MmsPart =
            MmsPart(PduCodec.TEXT_PLAIN, name, stripExtension(name), PduCodec.CHARSET_UTF8, text.toByteArray(Charsets.UTF_8))

        /** An image part with its bytes. */
        fun image(contentType: String?, data: ByteArray?, name: String?): MmsPart =
            MmsPart(contentType, name, stripExtension(name), 0, data)

        private fun stripExtension(name: String?): String? {
            if (name == null) return null
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }
    }
}
