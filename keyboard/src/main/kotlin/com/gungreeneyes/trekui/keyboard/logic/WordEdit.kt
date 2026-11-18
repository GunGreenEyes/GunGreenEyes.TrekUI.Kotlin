package com.gungreeneyes.trekui.keyboard.logic

/**
 * Text arithmetic around the cursor: the word being typed (for suggestions) and how much a delete-word
 * swipe removes. Pure Kotlin (unit-tested).
 */
internal object WordEdit {
    /** True for characters that belong to a word (letters, digits, inner apostrophes and hyphens). */
    fun wordChar(cp: Int): Boolean =
        Character.isLetterOrDigit(cp) || cp == '\''.code || cp == '’'.code || cp == '-'.code

    /** The word directly before the cursor ([before] = text before the cursor), or "". */
    fun currentWord(before: CharSequence?): String {
        if (before == null) return ""
        val end = before.length
        var start = end
        while (start > 0) {
            val cp = Character.codePointBefore(before, start)
            if (!wordChar(cp)) break
            start -= Character.charCount(cp)
        }
        // a word does not start with an apostrophe or hyphen (opening quote, dash)
        while (start < end && !Character.isLetterOrDigit(Character.codePointAt(before, start))) {
            start += Character.charCount(Character.codePointAt(before, start))
        }
        return before.subSequence(start, end).toString()
    }

    /**
     * How many chars a delete-word swipe removes: trailing spaces, then the word before them (or, if
     * the cursor follows punctuation, that run of punctuation). Never more than `before.length`.
     */
    fun deleteWordLength(before: CharSequence?): Int {
        if (before.isNullOrEmpty()) return 0
        var i = before.length
        while (i > 0 && Character.isWhitespace(before[i - 1])) i--
        if (i == 0) return before.length
        val word = wordChar(Character.codePointBefore(before, i))
        while (i > 0) {
            val cp = Character.codePointBefore(before, i)
            if (Character.isWhitespace(cp) || wordChar(cp) != word) break
            i -= Character.charCount(cp)
        }
        return before.length - i
    }
}
