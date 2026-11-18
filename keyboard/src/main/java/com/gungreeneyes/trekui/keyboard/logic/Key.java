package com.gungreeneyes.trekui.keyboard.logic;

import java.util.Collections;
import java.util.List;

/**
 * One key of a {@link KeyLayout}: what it is ({@link #kind}), what it types ({@link #text}), how wide it is
 * relative to its row ({@link #weight}) and the characters its long-press popup offers
 * ({@link #alternates}). Immutable and free of Android types, so layout tables are unit-testable.
 */
public final class Key {
    /** Types {@link #text}. */
    public static final int CHAR = 0;
    public static final int SHIFT = 1;
    public static final int DELETE = 2;
    public static final int ENTER = 3;
    public static final int SPACE = 4;
    /** Letters → first symbols page. */
    public static final int TO_SYMBOLS = 5;
    /** First symbols page → second. */
    public static final int TO_SYMBOLS_2 = 6;
    /** Any other page → letters. */
    public static final int TO_LETTERS = 7;
    /** Next language (or next keyboard app when only one language is on). */
    public static final int GLOBE = 8;
    /** Emoji page (long press: another keyboard's full emoji picker). */
    public static final int EMOJI = 9;

    public final int kind;
    /** Text typed by a {@link #CHAR} key, in lower case for letters; null for function keys. */
    public final String text;
    /** Width relative to a standard letter key (1.0). */
    public final float weight;
    /** Long-press choices (lower case for letters), first = default selection; never null. */
    public final List<String> alternates;
    /** Small hint drawn in the corner (the top-row digit), or null. */
    public final String hint;

    public Key(int kind, String text, float weight, List<String> alternates, String hint) {
        this.kind = kind;
        this.text = text;
        this.weight = weight;
        this.alternates = alternates == null ? Collections.emptyList() : Collections.unmodifiableList(alternates);
        this.hint = hint;
    }

    /** A plain character key of width 1 with the given alternates. */
    public static Key chr(String text, List<String> alternates, String hint) {
        return new Key(CHAR, text, 1f, alternates, hint);
    }

    /** A function key of {@code kind} and width {@code weight}. */
    public static Key fn(int kind, float weight) {
        return new Key(kind, null, weight, null, null);
    }

    /** True for a single-letter character key (affected by shift). */
    public boolean isLetter() {
        return kind == CHAR && text != null && text.codePointCount(0, text.length()) == 1
                && Character.isLetter(text.codePointAt(0));
    }

    @Override
    public String toString() {
        return kind == CHAR ? text : "#" + kind;
    }
}
