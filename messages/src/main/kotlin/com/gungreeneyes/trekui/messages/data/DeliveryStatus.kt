package com.gungreeneyes.trekui.messages.data

/**
 * Pure delivery-report logic for (multipart) SMS (unit-tested). Each part reports its own status; the
 * message's status is the worst of them: failed beats pending beats complete, and a part not yet heard
 * from counts as pending. Values are the Telephony provider's `Sms.STATUS_*` constants.
 */
internal object DeliveryStatus {
    /** `Telephony.Sms.STATUS_COMPLETE`. */
    const val COMPLETE = 0

    /** `Telephony.Sms.STATUS_PENDING`. */
    const val PENDING = 32

    /** `Telephony.Sms.STATUS_FAILED`. */
    const val FAILED = 64

    /** TP-Status ranges (3GPP TS 23.040): 0x00-0x1F done, 0x20-0x3F still trying, 0x40+ failed. */
    const val TP_STATUS_PENDING_MIN = 0x20
    const val TP_STATUS_FAILED_MIN = 0x40

    /** The provider status for a status report's TP-Status [tp]. */
    fun fromTp(tp: Int): Int = when {
        tp >= TP_STATUS_FAILED_MIN -> FAILED
        tp >= TP_STATUS_PENDING_MIN -> PENDING
        else -> COMPLETE
    }

    /** The message status from the latest status of each part ([byPart]: part index to status) of [parts]. */
    fun merge(byPart: Map<Int, Int>, parts: Int): Int = when {
        byPart.values.any { it == FAILED } -> FAILED
        (0 until parts.coerceAtLeast(1)).all { byPart[it] == COMPLETE } -> COMPLETE
        else -> PENDING
    }

    /** True once every part has a final status (complete or failed): nothing more will change. */
    fun settled(byPart: Map<Int, Int>, parts: Int): Boolean =
        (0 until parts.coerceAtLeast(1)).all { byPart[it] == COMPLETE || byPart[it] == FAILED }

    /** Stored form of [byPart]: "part:status" pairs joined by commas. */
    fun encode(byPart: Map<Int, Int>): String = byPart.entries.joinToString(",") { "${it.key}:${it.value}" }

    /** Reads [encode]'s form; malformed pairs are skipped. */
    fun decode(s: String?): MutableMap<Int, Int> {
        val out = HashMap<Int, Int>()
        if (s.isNullOrEmpty()) return out
        for (pair in s.split(',')) {
            val part = pair.substringBefore(':').toIntOrNull()
            val status = pair.substringAfter(':', "").toIntOrNull()
            if (part != null && status != null) out[part] = status
        }
        return out
    }
}
