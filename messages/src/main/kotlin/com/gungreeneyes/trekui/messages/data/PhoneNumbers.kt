package com.gungreeneyes.trekui.messages.data

import java.util.Locale
import java.util.TreeSet

/**
 * Pure address handling for SMS/MMS (no Android dependencies, unit-tested): normalising what users type
 * or carriers deliver into one canonical form, a loose comparison key that treats "+1 (555) 010-2345",
 * "15550102345" and "555-010-2345" as the same person, recipient-list splitting for `smsto:` URIs
 * and a stable key for a set of recipients (drafts, grouping).
 *
 * Three kinds of address exist: phone numbers (digits, optional leading `+`), e-mail
 * addresses (some carriers deliver e-mail as SMS) and alphanumeric sender ids ("BANK", "AMAZON").
 * Only phone numbers are reduced to digits; the others keep their text, case-folded.
 */
@Suppress("TooManyFunctions") // one cohesive set of address helpers
internal object PhoneNumbers {
    /** Trailing digits compared by [key]: national numbers match regardless of country code. */
    const val KEY_DIGITS = 10

    /** Numbers at most this long are short codes and are compared whole. */
    const val SHORT_CODE_MAX = 6

    private val RECIPIENT_SEPARATORS = Regex("[,;]")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Canonical form: whitespace and dialling punctuation removed, an MMS `/TYPE=PLMN` suffix and
     * a `tel:` prefix dropped, a leading international `00` turned into `+`.
     * E-mail addresses are lower-cased, alphanumeric sender ids upper-cased. Null or blank gives "".
     */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        var s = raw.javaTrim()
        val type = s.uppercase(Locale.ROOT).indexOf("/TYPE=")
        if (type >= 0) s = s.substring(0, type)
        if (s.regionMatches(0, "tel:", 0, 4, ignoreCase = true)) s = s.substring(4)
        if (s.isEmpty()) return ""
        if ('@' in s) return s.lowercase(Locale.ROOT)
        if (!isPhoneLike(s)) return s.uppercase(Locale.ROOT)
        val out = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch in '0'..'9' -> out.append(ch)
                ch == '+' && out.isEmpty() -> out.append('+')
                ch == '*' || ch == '#' -> out.append(ch) // USSD-like short codes keep their marks
            }
        }
        val n = out.toString()
        return if (n.startsWith("00") && n.length > 4) "+" + n.substring(2) else n
    }

    /**
     * True if [s] looks like a dialable number: only digits, spaces and `+ - . ( ) / * #`,
     * with at least one digit.
     */
    fun isPhoneLike(s: String?): Boolean {
        if (s == null) return false
        var digit = false
        for (ch in s) {
            if (ch in '0'..'9') digit = true
            else if (" +-.()/*# ".indexOf(ch) < 0) return false
        }
        return digit
    }

    /**
     * Loose comparison key: the last [KEY_DIGITS] digits of a phone number (short codes whole),
     * or the normalised text of an e-mail / alphanumeric sender.
     */
    fun key(raw: String?): String {
        val n = normalize(raw)
        if (n.isEmpty() || !isPhoneLike(n)) return n
        val d = n.filter { it in '0'..'9' }
        if (d.length <= SHORT_CODE_MAX) return d
        return d.takeLast(KEY_DIGITS)
    }

    /** True if both addresses reach the same person ([key] equal and not empty). */
    fun same(a: String?, b: String?): Boolean {
        val ka = key(a)
        return ka.isNotEmpty() && ka == key(b)
    }

    /**
     * Splits a recipient list ("555 1234, 555 9876; bob@example.com") into normalised, de-duplicated
     * addresses in their original order. Separators are comma and semicolon.
     */
    fun splitRecipients(list: String?): MutableList<String> {
        val out = ArrayList<String>()
        if (list == null) return out
        val seen = TreeSet<String>()
        for (part in list.split(RECIPIENT_SEPARATORS)) {
            val n = normalize(part)
            if (n.isNotEmpty() && seen.add(key(n))) out.add(n)
        }
        return out
    }

    /**
     * The recipients of an `sms:`, `smsto:`, `mms:` or `mmsto:` URI's scheme-specific
     * part (already URL-decoded by the caller), without any `?body=` query.
     */
    fun fromSchemePart(schemeSpecificPart: String?): MutableList<String> {
        if (schemeSpecificPart == null) return ArrayList()
        var s: String = schemeSpecificPart.substringBefore('?')
        if (s.startsWith("//")) s = s.substring(2)
        return splitRecipients(s)
    }

    /**
     * The still-percent-encoded value of query parameter [name] (case-insensitive) in an `sms:` / `smsto:`
     * URI's encoded scheme-specific part, or null. Works for opaque URIs (`sms:555?body=hi`), where
     * `Uri.getQueryParameter` throws.
     */
    fun schemeQueryValue(encodedSchemePart: String?, name: String): String? =
        encodedSchemePart
            ?.substringAfter('?', "")
            ?.split('&')
            ?.firstOrNull { it.isNotEmpty() && it.substringBefore('=').equals(name, ignoreCase = true) }
            ?.substringAfter('=', "")

    /**
     * A block-rule prefix in the same canonical form as [normalize] gives numbers: also turns a short
     * international `00` prefix ("0044") into `+` ("+44"), which [normalize] leaves alone below 5 digits.
     */
    fun normalizePrefix(raw: String?): String {
        val n = normalize(raw)
        return if (n.startsWith("00") && n.length > 2 && isPhoneLike(n)) "+" + n.substring(2) else n
    }

    /**
     * The participants of an incoming MMS: the sender, then every To / Cc recipient except this device
     * ([own] numbers) and duplicates. When the device's number is unknown and only one other recipient is
     * listed, that recipient is taken to be this device, so a one-to-one MMS stays one-to-one.
     * Empty when there is no sender.
     */
    fun mmsParticipants(
        from: String?,
        to: Collection<String?>,
        cc: Collection<String?>,
        own: Collection<String?>,
    ): List<String> {
        val sender = normalize(from)
        if (sender.isEmpty()) return emptyList()
        val ownKeys = own.map(::key).filter { it.isNotEmpty() }.toSet()
        val seen = hashSetOf(key(sender))
        val others = (to + cc).map(::normalize)
            .filter { key(it).let { k -> k.isNotEmpty() && k != INSERT_ADDRESS_TOKEN && k !in ownKeys } }
            .filter { seen.add(key(it)) }
        return if (ownKeys.isEmpty() && others.size == 1) listOf(sender) else listOf(sender) + others
    }

    /** MMS placeholder for this device's own address, as [normalize] gives it. */
    private const val INSERT_ADDRESS_TOKEN = "INSERT-ADDRESS-TOKEN"

    /**
     * A stable key for a set of recipients, independent of order and formatting: the sorted
     * [key]s joined by `|`. Used for drafts and to match a typed recipient list to an
     * existing conversation.
     */
    fun setKey(addresses: Collection<String?>?): String {
        val keys = TreeSet<String>()
        addresses?.forEach { a -> key(a).takeIf { it.isNotEmpty() }?.let(keys::add) }
        return keys.joinToString("|")
    }

    /** Initials for an avatar block: first letters of up to two words, or the last two digits. */
    fun initials(displayName: String?): String {
        if (displayName == null || displayName.javaTrim().isEmpty()) return "#"
        val s = displayName.javaTrim()
        if (isPhoneLike(s)) return key(s).takeLast(2)
        val out = StringBuilder(2)
        for (w in s.split(WHITESPACE)) {
            if (w.isEmpty()) continue
            out.appendCodePoint(Character.toUpperCase(w.codePointAt(0)))
            if (out.length >= 2) break
        }
        return if (out.isEmpty()) "#" else out.toString()
    }
}

/**
 * `java.lang.String.trim()`: strips every char up to U+0020 from both ends. Kotlin's `trim()` strips
 * Unicode whitespace instead (no control chars, but no-break spaces), so stored and compared addresses
 * use this to stay exactly as the Java edition wrote them.
 */
internal fun String.javaTrim(): String = trim { it <= ' ' }
