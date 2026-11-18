package com.gungreeneyes.trekui.keyboard.logic

/** Keyboard pages. */
internal enum class Mode {
    LETTERS, SYMBOLS, SYMBOLS_2, NUMERIC, PHONE, EMOJI;

    /** True for the pages a text field shows (letters and both symbol pages, emoji). */
    val textual: Boolean
        get() = when (this) {
            LETTERS, SYMBOLS, SYMBOLS_2, EMOJI -> true
            NUMERIC, PHONE -> false
        }
}
