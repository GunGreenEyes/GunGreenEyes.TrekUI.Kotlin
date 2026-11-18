package com.gungreeneyes.trekui.messages.data

/**
 * One SMS or MMS as the UI shows it (an immutable value). Plain Kotlin so the grouping and merging
 * logic ([ThreadGrouper]) is unit-testable; the Android `MessageStore` fills it from the Telephony
 * provider.
 *
 * @property id provider row id (unique within [mms] = false or true)
 * @property address the other party: sender of an incoming message, first recipient of an outgoing one
 * @property body text body ("" for an image-only MMS)
 * @property subId subscription (SIM) id it was sent or received on, or -1
 * @property images MMS image parts as content URI strings
 */
internal data class MessageItem(
    val id: Long,
    val threadId: Long,
    val address: String,
    val dateMillis: Long,
    val body: String,
    val read: Boolean,
    val outgoing: Boolean,
    val mms: Boolean,
    val state: State = if (outgoing) State.SENT else State.RECEIVED,
    val subId: Int = -1,
    val images: List<String> = emptyList(),
) {
    /** Delivery state shown under an outgoing bubble. */
    enum class State { RECEIVED, SENDING, SENT, DELIVERED, FAILED }

    /** True for an incoming message the user has not read yet. */
    val unread: Boolean get() = !outgoing && !read

    /** A key unique across SMS and MMS rows. */
    val uid: String get() = (if (mms) "m" else "s") + id

    /** One-line preview: the body, or a marker for an attachment-only message. */
    fun preview(imageMarker: String): String {
        val text = body.javaTrim()
        return when {
            text.isNotEmpty() -> text.replace('\n', ' ')
            images.isEmpty() -> ""
            else -> imageMarker
        }
    }

    companion object {
        /** Shorthand for an SMS with no attachments. */
        fun sms(
            id: Long,
            threadId: Long,
            address: String?,
            dateMillis: Long,
            body: String?,
            read: Boolean,
            outgoing: Boolean,
        ): MessageItem = MessageItem(id, threadId, address.orEmpty(), dateMillis, body.orEmpty(), read, outgoing, false)
    }
}
