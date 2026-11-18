package com.gungreeneyes.trekui.keyboard.logic

/**
 * One key of a [KeyLayout]: what it is ([kind]), what it types ([text]), how wide it is relative to its
 * row ([weight]) and the characters its long-press popup offers ([alternates]). Immutable and free of
 * Android types, so layout tables are unit-testable.
 */
internal data class Key(
    val kind: Int,
    /** Text typed by a [CHAR] key, in lower case for letters; null for function keys. */
    val text: String?,
    /** Width relative to a standard letter key (1.0). */
    val weight: Float,
    /** Long-press choices (lower case for letters), first = default selection. */
    val alternates: List<String> = emptyList(),
    /** Small hint drawn in the corner (the top-row digit), or null. */
    val hint: String? = null,
) {
    /** True for a single-letter character key (affected by shift). */
    val isLetter: Boolean
        get() = kind == CHAR && text != null && text.codePointCount(0, text.length) == 1 &&
            Character.isLetter(text.codePointAt(0))

    override fun toString(): String = if (kind == CHAR) text.toString() else "#$kind"

    companion object {
        /** Types [text]. */
        const val CHAR = 0
        const val SHIFT = 1
        const val DELETE = 2
        const val ENTER = 3
        const val SPACE = 4
        /** Letters → first symbols page. */
        const val TO_SYMBOLS = 5
        /** First symbols page → second. */
        const val TO_SYMBOLS_2 = 6
        /** Any other page → letters. */
        const val TO_LETTERS = 7
        /** Next language (or next keyboard app when only one language is on). */
        const val GLOBE = 8
        /** Emoji page (long press: another keyboard's full emoji picker). */
        const val EMOJI = 9

        /** A plain character key of width 1 with the given alternates (a copy is kept). */
        fun chr(text: String, alternates: List<String>?, hint: String?): Key =
            Key(CHAR, text, 1f, alternates?.toList().orEmpty(), hint)

        /** A function key of [kind] and width [weight]. */
        fun fn(kind: Int, weight: Float): Key = Key(kind, null, weight)
    }
}
