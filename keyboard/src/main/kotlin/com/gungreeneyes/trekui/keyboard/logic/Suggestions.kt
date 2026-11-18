package com.gungreeneyes.trekui.keyboard.logic

import java.util.Locale

/**
 * Ranks word completions for the suggestion strip from two local sources: the user's personal dictionary
 * (Android's UserDictionary, frequency 1..255) and words typed on this keyboard (use counts). No
 * autocorrect: only words that start with what was typed are offered, so a suggestion never changes what
 * the user meant. Pure Kotlin (unit-tested).
 *
 * Score: dictionary frequency + [TYPED_WEIGHT] per use, capped; ties go to the shorter word, then
 * alphabetical. The typed prefix itself is never offered. Suggestions take the prefix's capitalisation
 * (Capitalised or ALL CAPS).
 */
internal object Suggestions {
    /** Score of one typed use (a word typed 5 times outranks a mid-frequency dictionary word). */
    const val TYPED_WEIGHT = 40
    /** Highest score from typed uses. */
    const val TYPED_CAP = 1000
    /** Completions start after this many typed characters. */
    const val MIN_PREFIX = 1

    /**
     * Up to [limit] completions of [prefix].
     *
     * @param typed      typed word → use count (lower case keys), may be null
     * @param dictionary dictionary word → frequency (original case), may be null
     */
    fun rank(
        prefix: String?,
        typed: Map<String, Int?>?,
        dictionary: Map<String, Int?>?,
        limit: Int,
        locale: Locale,
    ): List<String> {
        if (prefix == null || prefix.length < MIN_PREFIX || limit <= 0) return emptyList()
        val p = prefix.lowercase(locale)
        val scores = HashMap<String, Int>()
        val display = HashMap<String, String>()
        dictionary?.forEach { (w, freq) ->
            val lw = w.lowercase(locale)
            if (!lw.startsWith(p) || lw == p) return@forEach
            scores.merge(lw, maxOf(0, freq ?: 0)) { a, b -> maxOf(a, b) }
            display.putIfAbsent(lw, w)
        }
        typed?.forEach { (word, uses) ->
            val lw = word.lowercase(locale)
            if (!lw.startsWith(p) || lw == p) return@forEach
            val s = minOf(TYPED_CAP, maxOf(0, uses ?: 0) * TYPED_WEIGHT)
            scores.merge(lw, s) { a, b -> a + b }
            display.putIfAbsent(lw, word)
        }
        val order = compareByDescending<String> { scores.getValue(it) }
            .thenBy { it.length }
            .thenBy { it }
        return scores.keys.sortedWith(order).take(limit).map { matchCase(prefix, display.getValue(it), locale) }
    }

    /** [word] in the capitalisation of [prefix]: ALL CAPS, Capitalised, or as stored. */
    fun matchCase(prefix: String, word: String, locale: Locale): String {
        if (prefix.isEmpty()) return word
        val allUpper = prefix.length > 1 && prefix == prefix.uppercase(locale) &&
            prefix != prefix.lowercase(locale)
        if (allUpper) return word.uppercase(locale)
        if (Character.isUpperCase(prefix.codePointAt(0))) {
            val first = word.codePointAt(0)
            val n = Character.charCount(first)
            return String(Character.toChars(Character.toUpperCase(first))) + word.substring(n)
        }
        return word
    }

    /**
     * True if [word] may be learned: 2..32 characters, letters with inner apostrophes or hyphens,
     * and not all digits (numbers, codes and one-time passwords are never stored).
     */
    fun learnable(word: String?): Boolean {
        if (word == null || word.length !in 2..32) return false
        var letters = 0
        var i = 0
        while (i < word.length) {
            val cp = word.codePointAt(i)
            i += Character.charCount(cp)
            when {
                Character.isLetter(cp) -> letters++
                cp != '\''.code && cp != '-'.code && cp != '’'.code -> return false
            }
        }
        return letters >= 2
    }
}
