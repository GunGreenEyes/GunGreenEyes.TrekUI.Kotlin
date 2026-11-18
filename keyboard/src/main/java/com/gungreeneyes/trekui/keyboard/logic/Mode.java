package com.gungreeneyes.trekui.keyboard.logic;

/** Keyboard pages. */
public enum Mode {
    LETTERS, SYMBOLS, SYMBOLS_2, NUMERIC, PHONE, EMOJI;

    /** True for the pages a text field shows (letters and both symbol pages, emoji). */
    public boolean textual() {
        return this == LETTERS || this == SYMBOLS || this == SYMBOLS_2 || this == EMOJI;
    }
}
