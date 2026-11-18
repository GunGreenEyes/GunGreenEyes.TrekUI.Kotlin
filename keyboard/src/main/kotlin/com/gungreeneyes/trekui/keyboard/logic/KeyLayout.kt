package com.gungreeneyes.trekui.keyboard.logic

/** A built keyboard page: rows of [Key]s plus the mode and language it was built for. */
internal class KeyLayout(
    val mode: Mode,
    val language: Language,
    rows: List<List<Key>>,
) {
    /** The rows, top to bottom (copied, so the layout cannot change after it is built). */
    val rows: List<List<Key>> = rows.map { it.toList() }

    /** Sum of the key weights of row [r]. */
    fun rowWeight(r: Int): Float = rows[r].fold(0f) { w, k -> w + k.weight }

    /** The widest row's weight (every row is laid out against this, so keys keep one width). */
    fun maxRowWeight(): Float = rows.indices.fold(0f) { max, r -> maxOf(max, rowWeight(r)) }

    /** The first key of [kind], or null. */
    fun find(kind: Int): Key? = rows.asSequence().flatten().firstOrNull { it.kind == kind }

    /** The first character key typing [text], or null. */
    fun findChar(text: String): Key? =
        rows.asSequence().flatten().firstOrNull { it.kind == Key.CHAR && it.text == text }

    /** Row [r]'s characters joined (function keys as '#'), for tests and debugging. */
    fun rowString(r: Int): String = rows[r].joinToString("") { if (it.kind == Key.CHAR) it.text.toString() else "#" }
}
