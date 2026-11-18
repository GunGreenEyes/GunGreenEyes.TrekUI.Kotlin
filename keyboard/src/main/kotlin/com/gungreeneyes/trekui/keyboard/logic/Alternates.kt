package com.gungreeneyes.trekui.keyboard.logic

import java.util.Locale

/**
 * Long-press choices: accented letters per base letter (the language's own accents first) and
 * punctuation / symbol variants, plus the digit of a top-row letter. Pure tables (unit-tested).
 */
internal object Alternates {
    private val LETTERS: Map<String, String> = mapOf(
        "a" to "àáâäæãåā",
        "c" to "çćč",
        "d" to "ð",
        "e" to "èéêëēęė",
        "g" to "ğ",
        "i" to "ìíîïī",
        "l" to "ł",
        "n" to "ñń",
        "o" to "òóôöõœøō",
        "s" to "ßśš",
        "u" to "ùúûüū",
        "y" to "ÿý",
        "z" to "žźż",
    )

    private val SYMBOLS: Map<String, List<String>> = mapOf(
        "." to listOf("?", "!", ",", "'", "\"", ":", ";", "-", "@", "/", "…"),
        "," to listOf(";", ":", "'", "\""),
        "'" to listOf("‘", "’", "‚", "‹", "›"),
        "\"" to listOf("“", "”", "„", "«", "»"),
        "-" to listOf("–", "—", "_", "·"),
        "$" to listOf("€", "£", "¥", "¢", "₹", "₩"),
        "!" to listOf("¡"),
        "?" to listOf("¿"),
        "%" to listOf("‰"),
        "+" to listOf("±"),
        "=" to listOf("≠", "≈", "∞"),
        "<" to listOf("≤", "«", "‹"),
        ">" to listOf("≥", "»", "›"),
        "*" to listOf("†", "‡", "★"),
        "&" to listOf("§"),
        "/" to listOf("\\", "÷"),
        "(" to listOf("[", "{", "<"),
        ")" to listOf("]", "}", ">"),
        "0" to listOf("°", "∅", "ⁿ"),
        "1" to listOf("¹", "½", "⅓", "¼"),
        "2" to listOf("²", "⅔"),
        "3" to listOf("³", "¾"),
        "#" to listOf("№"),
    )

    /**
     * Choices for a letter in [lang]: the digit (if any) first, then the letter's accents with the
     * language's preferred ones leading, keeping the table order otherwise. Lower case.
     */
    fun forLetter(letter: String, lang: Language, digit: String?): List<String> {
        val out = mutableListOf<String>()
        if (digit != null) out += digit
        val accents = LETTERS[letter.lowercase(Locale.ROOT)] ?: return out
        val preferred = mutableListOf<String>()
        val rest = mutableListOf<String>()
        for (s in codePoints(accents)) {
            if (lang.preferred.contains(s)) preferred += s else rest += s
        }
        // keep the language's own order for its preferred accents
        out += preferred.sortedBy { lang.preferred.indexOf(it) }
        out += rest
        return out
    }

    /** Choices for a punctuation / symbol / digit key; empty when it has none. */
    fun forSymbol(symbol: String): List<String> = SYMBOLS[symbol].orEmpty().toList()

    /** [s] in upper case for the shifted keyboard (ß stays ß rather than becoming "SS"). */
    fun shifted(s: String, locale: Locale): String {
        val up = s.uppercase(locale)
        return if (up.codePointCount(0, up.length) == s.codePointCount(0, s.length)) up else s
    }

    /** The characters of [s], one string per code point. */
    fun codePoints(s: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            out += String(Character.toChars(cp))
            i += Character.charCount(cp)
        }
        return out
    }
}
