package com.gungreeneyes.trekui.keyboard.logic;

/**
 * Text arithmetic around the cursor: the word being typed (for suggestions) and how much a delete-word
 * swipe removes. Pure Java (unit-tested).
 */
public final class WordEdit {
    private WordEdit() {}

    /** True for characters that belong to a word (letters, digits, inner apostrophes and hyphens). */
    public static boolean wordChar(int cp) {
        return Character.isLetterOrDigit(cp) || cp == '\'' || cp == '’' || cp == '-';
    }

    /** The word directly before the cursor ({@code before} = text before the cursor), or "". */
    public static String currentWord(CharSequence before) {
        if (before == null) return "";
        int end = before.length();
        int start = end;
        while (start > 0) {
            int cp = Character.codePointBefore(before, start);
            if (!wordChar(cp)) break;
            start -= Character.charCount(cp);
        }
        // a word does not start with an apostrophe or hyphen (opening quote, dash)
        while (start < end && !Character.isLetterOrDigit(Character.codePointAt(before, start))) {
            start += Character.charCount(Character.codePointAt(before, start));
        }
        return before.subSequence(start, end).toString();
    }

    /**
     * How many chars a delete-word swipe removes: trailing spaces, then the word before them (or, if
     * the cursor follows punctuation, that run of punctuation). Never more than {@code before.length()}.
     */
    public static int deleteWordLength(CharSequence before) {
        if (before == null || before.length() == 0) return 0;
        int i = before.length();
        while (i > 0 && Character.isWhitespace(before.charAt(i - 1))) i--;
        if (i == 0) return before.length();
        boolean word = wordChar(Character.codePointBefore(before, i));
        while (i > 0) {
            int cp = Character.codePointBefore(before, i);
            if (Character.isWhitespace(cp) || wordChar(cp) != word) break;
            i -= Character.charCount(cp);
        }
        return before.length() - i;
    }
}
