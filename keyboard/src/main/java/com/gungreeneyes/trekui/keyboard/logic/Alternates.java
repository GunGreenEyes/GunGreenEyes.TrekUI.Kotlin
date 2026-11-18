package com.gungreeneyes.trekui.keyboard.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Long-press choices: accented letters per base letter (the language's own accents first) and
 * punctuation / symbol variants, plus the digit of a top-row letter. Pure tables (unit-tested).
 */
public final class Alternates {
    private Alternates() {}

    private static final Map<String, String> LETTERS = new HashMap<>();
    private static final Map<String, String[]> SYMBOLS = new HashMap<>();

    static {
        LETTERS.put("a", "àáâäæãåā");
        LETTERS.put("c", "çćč");
        LETTERS.put("d", "ð");
        LETTERS.put("e", "èéêëēęė");
        LETTERS.put("g", "ğ");
        LETTERS.put("i", "ìíîïī");
        LETTERS.put("l", "ł");
        LETTERS.put("n", "ñń");
        LETTERS.put("o", "òóôöõœøō");
        LETTERS.put("s", "ßśš");
        LETTERS.put("u", "ùúûüū");
        LETTERS.put("y", "ÿý");
        LETTERS.put("z", "žźż");

        SYMBOLS.put(".", new String[]{"?", "!", ",", "'", "\"", ":", ";", "-", "@", "/", "…"});
        SYMBOLS.put(",", new String[]{";", ":", "'", "\""});
        SYMBOLS.put("'", new String[]{"‘", "’", "‚", "‹", "›"});
        SYMBOLS.put("\"", new String[]{"“", "”", "„", "«", "»"});
        SYMBOLS.put("-", new String[]{"–", "—", "_", "·"});
        SYMBOLS.put("$", new String[]{"€", "£", "¥", "¢", "₹", "₩"});
        SYMBOLS.put("!", new String[]{"¡"});
        SYMBOLS.put("?", new String[]{"¿"});
        SYMBOLS.put("%", new String[]{"‰"});
        SYMBOLS.put("+", new String[]{"±"});
        SYMBOLS.put("=", new String[]{"≠", "≈", "∞"});
        SYMBOLS.put("<", new String[]{"≤", "«", "‹"});
        SYMBOLS.put(">", new String[]{"≥", "»", "›"});
        SYMBOLS.put("*", new String[]{"†", "‡", "★"});
        SYMBOLS.put("&", new String[]{"§"});
        SYMBOLS.put("/", new String[]{"\\", "÷"});
        SYMBOLS.put("(", new String[]{"[", "{", "<"});
        SYMBOLS.put(")", new String[]{"]", "}", ">"});
        SYMBOLS.put("0", new String[]{"°", "∅", "ⁿ"});
        SYMBOLS.put("1", new String[]{"¹", "½", "⅓", "¼"});
        SYMBOLS.put("2", new String[]{"²", "⅔"});
        SYMBOLS.put("3", new String[]{"³", "¾"});
        SYMBOLS.put("#", new String[]{"№"});
    }

    /**
     * Choices for a letter in {@code lang}: the digit (if any) first, then the letter's accents with the
     * language's preferred ones leading, keeping the table order otherwise. Lower case.
     */
    public static List<String> forLetter(String letter, Language lang, String digit) {
        List<String> out = new ArrayList<>();
        if (digit != null) out.add(digit);
        String accents = LETTERS.get(letter.toLowerCase(Locale.ROOT));
        if (accents == null) return out;
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < accents.length(); ) {
            int cp = accents.codePointAt(i);
            String s = new String(Character.toChars(cp));
            i += Character.charCount(cp);
            if (lang.preferred.contains(s)) out.add(s); else rest.add(s);
        }
        // keep the language's own order for its preferred accents
        List<String> preferred = out.subList(digit == null ? 0 : 1, out.size());
        preferred.sort((a, b) -> Integer.compare(lang.preferred.indexOf(a), lang.preferred.indexOf(b)));
        out.addAll(rest);
        return out;
    }

    /** Choices for a punctuation / symbol / digit key; empty when it has none. */
    public static List<String> forSymbol(String symbol) {
        String[] alts = SYMBOLS.get(symbol);
        List<String> out = new ArrayList<>();
        if (alts != null) for (String a : alts) out.add(a);
        return out;
    }

    /** {@code s} in upper case for the shifted keyboard (ß stays ß rather than becoming "SS"). */
    public static String shifted(String s, Locale locale) {
        String up = s.toUpperCase(locale);
        return up.codePointCount(0, up.length()) == s.codePointCount(0, s.length()) ? up : s;
    }
}
