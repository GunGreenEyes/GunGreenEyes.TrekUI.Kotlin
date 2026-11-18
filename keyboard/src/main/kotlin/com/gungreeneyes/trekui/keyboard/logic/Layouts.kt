package com.gungreeneyes.trekui.keyboard.logic

/**
 * The layout tables: builds every page (letters per language, two symbol pages, number pad, dial pad and
 * a small emoji page) as a [KeyLayout]. Pure Kotlin, so the tables are unit-tested.
 *
 * Letter pages: the language's three rows (top-row letters carry their digit as a long-press choice
 * and a corner hint), SHIFT and DELETE around the third row, and a bottom row of
 * `?123 , [globe] emoji SPACE . ENTER`. The comma becomes @ in e-mail fields and / in web-address
 * fields.
 */
internal object Layouts {
    /** Width of SHIFT, DELETE, page switch and ENTER keys. */
    const val WIDE = 1.5f
    /** Letter rows are laid out against at least this many key widths. */
    const val MIN_ROW = 10f

    /** Options the bottom row depends on. */
    data class Options(
        /** Show the globe key (more than one language on, or other keyboards to switch to). */
        var globe: Boolean = false,
        /** Add a digit row above the letters (top-row letters then drop their digit hints). */
        var digitRow: Boolean = false,
        var field: FieldRules.FieldKind = FieldRules.FieldKind.NORMAL,
    )

    /** Emoji on the built-in emoji page (a basic set; long-press the key for a full picker). */
    val EMOJI: List<String> = listOf(
        "😀", "😂", "🥲", "😊", "😍", "😘", "😉", "😎",
        "🤔", "😴", "😭", "😡", "👍", "👎", "👏", "🙏",
        "❤️", "🔥", "✨", "🎉", "💯", "✅", "❌", "⭐",
        "🚀", "🌍", "☕", "🍕", "🎵", "📷", "💡", "👋",
    )
    const val EMOJI_COLUMNS = 8

    /** Builds the page [mode] for [lang]. */
    fun build(mode: Mode, lang: Language, o: Options): KeyLayout = when (mode) {
        Mode.SYMBOLS -> symbols(lang, o)
        Mode.SYMBOLS_2 -> symbols2(lang, o)
        Mode.NUMERIC -> numeric(lang)
        Mode.PHONE -> phone(lang)
        Mode.EMOJI -> emoji(lang)
        Mode.LETTERS -> letters(lang, o)
    }

    // ---- letters ---------------------------------------------------------------------------------

    fun letters(lang: Language, o: Options): KeyLayout {
        val (top, middle, bottom) = lang.letterRows
        val width = maxOf(MIN_ROW, maxOf(top.length, middle.length).toFloat())
        val rows = mutableListOf<List<Key>>()
        if (o.digitRow) rows += charRow("1234567890", lang, false)
        rows += charRow(top, lang, !o.digitRow)
        rows += charRow(middle, lang, false)
        val side = maxOf(1f, (width - bottom.length) / 2f)
        rows += listOf(Key.fn(Key.SHIFT, side)) + charRow(bottom, lang, false) + Key.fn(Key.DELETE, side)
        rows += bottomRow(Key.TO_SYMBOLS, width, o)
        return KeyLayout(Mode.LETTERS, lang, rows)
    }

    /** One row of character keys; [digits] gives the first ten their digit (1..9, 0). */
    fun charRow(chars: String, lang: Language, digits: Boolean): List<Key> =
        Alternates.codePoints(chars).mapIndexed { col, s ->
            val digit = if (digits && col < 10) ((col + 1) % 10).toString() else null
            val alts = if (Character.isLetter(s.codePointAt(0))) {
                Alternates.forLetter(s, lang, digit)
            } else {
                Alternates.forSymbol(s)
            }
            Key.chr(s, alts, digit)
        }

    /** `?123 , [globe] emoji SPACE . ENTER`, the space bar filling the row to [width]. */
    fun bottomRow(pageKey: Int, width: Float, o: Options): List<Key> {
        val extra = when (o.field) {
            FieldRules.FieldKind.EMAIL -> "@"
            FieldRules.FieldKind.URL -> "/"
            FieldRules.FieldKind.NORMAL -> ","
        }
        val used = WIDE + 1f + (if (o.globe) 1f else 0f) + 1f + 1f + WIDE
        return buildList {
            add(Key.fn(pageKey, WIDE))
            add(Key.chr(extra, Alternates.forSymbol(extra), null))
            if (o.globe) add(Key.fn(Key.GLOBE, 1f))
            add(Key.fn(Key.EMOJI, 1f))
            add(Key.fn(Key.SPACE, maxOf(2f, width - used)))
            add(Key.chr(".", Alternates.forSymbol("."), null))
            add(Key.fn(Key.ENTER, WIDE))
        }
    }

    // ---- symbols ---------------------------------------------------------------------------------

    /** The currency key of [lang]'s first symbols page (€ for the euro languages). */
    fun currency(lang: Language): String = if (lang == Language.EN) "$" else "€"

    fun symbols(lang: Language, o: Options): KeyLayout {
        val second = listOf("@", "#", currency(lang), "%", "&", "-", "+", "(", ")", "/").map { s ->
            val alts = if (s == "€") listOf("$", "£", "¥", "¢") else Alternates.forSymbol(s)
            Key.chr(s, alts, null)
        }
        val third = listOf(Key.fn(Key.TO_SYMBOLS_2, WIDE)) + charRow("*\"':;!?", lang, false) +
            Key.fn(Key.DELETE, WIDE)
        val rows = listOf(
            charRow("1234567890", lang, false),
            second,
            third,
            bottomRow(Key.TO_LETTERS, MIN_ROW, o),
        )
        return KeyLayout(Mode.SYMBOLS, lang, rows)
    }

    fun symbols2(lang: Language, o: Options): KeyLayout {
        val third = listOf(Key.fn(Key.TO_SYMBOLS, WIDE)) + charRow("_<>[]©™", lang, false) +
            Key.fn(Key.DELETE, WIDE)
        val rows = listOf(
            charRow("~`|•√π÷×¶∆", lang, false),
            charRow("£¥€¢^°={}\\", lang, false),
            third,
            bottomRow(Key.TO_LETTERS, MIN_ROW, o),
        )
        return KeyLayout(Mode.SYMBOLS_2, lang, rows)
    }

    // ---- number pad and dial pad -------------------------------------------------------------------

    fun numeric(lang: Language): KeyLayout {
        val rows = listOf(
            listOf(digit("1"), digit("2"), digit("3"), Key.chr("-", Alternates.forSymbol("-"), null)),
            listOf(digit("4"), digit("5"), digit("6"), Key.fn(Key.SPACE, 1f)),
            listOf(digit("7"), digit("8"), digit("9"), Key.fn(Key.DELETE, 1f)),
            listOf(
                Key.chr(",", listOf("/", ":", "+", "*", "#"), null), digit("0"),
                Key.chr(".", listOf(","), null), Key.fn(Key.ENTER, 1f),
            ),
        )
        return KeyLayout(Mode.NUMERIC, lang, rows)
    }

    fun phone(lang: Language): KeyLayout {
        val rows = listOf(
            listOf(Key.chr("1", null, null), Key.chr("2", null, null), Key.chr("3", null, null),
                Key.chr("-", listOf("."), null)),
            listOf(Key.chr("4", null, null), Key.chr("5", null, null), Key.chr("6", null, null),
                Key.fn(Key.SPACE, 1f)),
            listOf(Key.chr("7", null, null), Key.chr("8", null, null), Key.chr("9", null, null),
                Key.fn(Key.DELETE, 1f)),
            listOf(Key.chr("*", listOf("(", ")", ",", ";"), null),
                Key.chr("0", listOf("+"), "+"), Key.chr("#", null, null), Key.fn(Key.ENTER, 1f)),
        )
        return KeyLayout(Mode.PHONE, lang, rows)
    }

    private fun digit(d: String): Key = Key.chr(d, Alternates.forSymbol(d), null)

    // ---- emoji -------------------------------------------------------------------------------------

    fun emoji(lang: Language): KeyLayout {
        val rows = EMOJI.chunked(EMOJI_COLUMNS) { row -> row.map { Key.chr(it, null, null) } }
            .filter { it.size == EMOJI_COLUMNS } +
            listOf(listOf(Key.fn(Key.TO_LETTERS, 2f), Key.fn(Key.SPACE, 4f), Key.fn(Key.DELETE, 2f)))
        return KeyLayout(Mode.EMOJI, lang, rows)
    }
}
