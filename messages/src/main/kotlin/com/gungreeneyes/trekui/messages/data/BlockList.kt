package com.gungreeneyes.trekui.messages.data

/**
 * Pure block-list matching (unit-tested). Entries come from the system's BlockedNumberContract (exact
 * numbers, shared with the dialer) plus this app's own prefix rules, written with a trailing `*`
 * ("+1900*" blocks every number starting +1900). Alphanumeric senders and e-mail addresses match by
 * their normalised text; a `*` rule on text matches its prefix.
 */
internal class BlockList(entries: Collection<String?>?) {
    private val exact = ArrayList<String>()
    private val prefixes = ArrayList<String>()

    init {
        entries?.filterNotNull()?.forEach { e ->
            val t = e.javaTrim()
            if (t.endsWith("*")) {
                val p = PhoneNumbers.normalize(t.dropLast(1))
                if (p.isNotEmpty()) prefixes.add(p)
            } else {
                val k = PhoneNumbers.key(t)
                if (k.isNotEmpty()) exact.add(k)
            }
        }
    }

    /** True if [address] is blocked by an exact entry or a prefix rule. */
    fun matches(address: String?): Boolean {
        val key = PhoneNumbers.key(address)
        if (key.isEmpty()) return false
        if (key in exact) return true
        val n = PhoneNumbers.normalize(address)
        return prefixes.any { p ->
            // "+1900*" also catches the same number delivered without the country prefix's plus
            n.startsWith(p) || (p.startsWith("+") && n.startsWith(p.substring(1)))
        }
    }

    /** Number of rules (exact + prefix). */
    fun size(): Int = exact.size + prefixes.size

    /** The prefix rules, normalised (for display). */
    fun prefixRules(): List<String> = java.util.Collections.unmodifiableList(prefixes)

    companion object {
        /** True if [rule] is a valid prefix rule (non-empty text before a trailing `*`). */
        fun isPrefixRule(rule: String?): Boolean {
            val t = rule?.javaTrim() ?: return false
            return t.endsWith("*") && PhoneNumbers.normalize(t.dropLast(1)).isNotEmpty()
        }

        /**
         * True when [entry] is kept in this app's own rules rather than the system block list: prefix rules
         * always, and every entry when the device has no system list ([systemList] false: Android 6.0, API 23,
         * which predates BlockedNumberContract).
         */
        fun storedLocally(entry: String?, systemList: Boolean): Boolean = !systemList || isPrefixRule(entry)

        /** The text an entry is stored as locally: prefix rules trimmed, numbers normalised like the system list. */
        fun localForm(entry: String?): String =
            if (isPrefixRule(entry)) entry?.javaTrim().orEmpty() else PhoneNumbers.normalize(entry)
    }
}
