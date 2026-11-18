package com.gungreeneyes.trekui.messages.data

/**
 * Unsent message text per conversation (pure logic over a small key/value [Backing], unit-tested;
 * the app backs it with SharedPreferences).
 *
 * A draft belongs to a recipient set, keyed by [PhoneNumbers.setKey], so it survives the
 * conversation being created, renamed or re-sorted, and a new conversation typed to the same people finds
 * it. Stored value: `<savedMillis>:<text>`. Blank text removes the draft.
 */
internal class DraftStore(private val backing: Backing) {
    /** Minimal storage the store needs. */
    interface Backing {
        fun get(key: String): String?
        fun put(key: String, value: String)
        fun remove(key: String)
        fun keys(): Set<String>
    }

    /** One saved draft. */
    data class Draft(val text: String, val savedMillis: Long)

    /** Saves (or, for blank text, removes) the draft for [recipients]. */
    fun save(recipients: Collection<String>, text: String?, nowMillis: Long) {
        val key = keyFor(recipients) ?: return
        if (text == null || text.javaTrim().isEmpty()) backing.remove(key)
        else backing.put(key, "$nowMillis:$text")
    }

    /** The draft for [recipients], or null. */
    fun load(recipients: Collection<String>): Draft? = keyFor(recipients)?.let { decode(backing.get(it)) }

    /** Removes the draft for [recipients] (after sending). */
    fun clear(recipients: Collection<String>) {
        keyFor(recipients)?.let(backing::remove)
    }

    /** Every stored draft by recipient-set key ([PhoneNumbers.setKey]). */
    fun all(): Map<String, Draft> {
        val out = LinkedHashMap<String, Draft>()
        for (k in backing.keys()) {
            if (!k.startsWith(PREFIX)) continue
            decode(backing.get(k))?.let { out[k.substring(PREFIX.length)] = it }
        }
        return out
    }

    companion object {
        /** Prefix of every draft key in the backing store (other keys are ignored). */
        const val PREFIX = "draft."

        /** Backing-store key for a recipient set, or null when the set is empty. */
        fun keyFor(recipients: Collection<String>): String? =
            PhoneNumbers.setKey(recipients).takeIf { it.isNotEmpty() }?.let { PREFIX + it }

        /** Parses a stored value; null when missing or malformed. */
        fun decode(value: String?): Draft? {
            if (value == null) return null
            val colon = value.indexOf(':')
            if (colon <= 0) return null
            val t = value.substring(0, colon).toLongOrNull() ?: return null
            val text = value.substring(colon + 1)
            return if (text.javaTrim().isEmpty()) null else Draft(text, t)
        }
    }
}
