package com.gungreeneyes.trekui.keyboard.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ranks word completions for the suggestion strip from two local sources: the user's personal dictionary
 * (Android's UserDictionary, frequency 1..255) and words typed on this keyboard (use counts). No
 * autocorrect: only words that start with what was typed are offered, so a suggestion never changes what
 * the user meant. Pure Java (unit-tested).
 *
 * <p>Score: dictionary frequency + {@link #TYPED_WEIGHT} per use, capped; ties go to the shorter word, then
 * alphabetical. The typed prefix itself is never offered. Suggestions take the prefix's capitalisation
 * (Capitalised or ALL CAPS).
 */
public final class Suggestions {
    private Suggestions() {}

    /** Score of one typed use (a word typed 5 times outranks a mid-frequency dictionary word). */
    static final int TYPED_WEIGHT = 40;
    /** Highest score from typed uses. */
    static final int TYPED_CAP = 1000;
    /** Completions start after this many typed characters. */
    public static final int MIN_PREFIX = 1;

    /**
     * Up to {@code limit} completions of {@code prefix}.
     *
     * @param typed      typed word → use count (lower case keys), may be null
     * @param dictionary dictionary word → frequency (original case), may be null
     */
    public static List<String> rank(String prefix, Map<String, Integer> typed, Map<String, Integer> dictionary,
                                    int limit, Locale locale) {
        List<String> out = new ArrayList<>();
        if (prefix == null || prefix.length() < MIN_PREFIX || limit <= 0) return out;
        String p = prefix.toLowerCase(locale);
        Map<String, Integer> scores = new HashMap<>();
        Map<String, String> display = new HashMap<>();
        if (dictionary != null) {
            for (Map.Entry<String, Integer> e : dictionary.entrySet()) {
                String w = e.getKey();
                String lw = w.toLowerCase(locale);
                if (!lw.startsWith(p) || lw.equals(p)) continue;
                int f = e.getValue() == null ? 0 : Math.max(0, e.getValue());
                scores.merge(lw, f, Math::max);
                display.putIfAbsent(lw, w);
            }
        }
        if (typed != null) {
            for (Map.Entry<String, Integer> e : typed.entrySet()) {
                String lw = e.getKey().toLowerCase(locale);
                if (!lw.startsWith(p) || lw.equals(p)) continue;
                int uses = e.getValue() == null ? 0 : Math.max(0, e.getValue());
                int s = Math.min(TYPED_CAP, uses * TYPED_WEIGHT);
                scores.merge(lw, s, Integer::sum);
                display.putIfAbsent(lw, e.getKey());
            }
        }
        List<String> words = new ArrayList<>(scores.keySet());
        words.sort((a, b) -> {
            int c = Integer.compare(scores.get(b), scores.get(a));
            if (c != 0) return c;
            c = Integer.compare(a.length(), b.length());
            return c != 0 ? c : a.compareTo(b);
        });
        for (String w : words) {
            if (out.size() >= limit) break;
            out.add(matchCase(prefix, display.get(w), locale));
        }
        return out;
    }

    /** {@code word} in the capitalisation of {@code prefix}: ALL CAPS, Capitalised, or as stored. */
    public static String matchCase(String prefix, String word, Locale locale) {
        if (prefix.isEmpty()) return word;
        boolean allUpper = prefix.length() > 1 && prefix.equals(prefix.toUpperCase(locale))
                && !prefix.equals(prefix.toLowerCase(locale));
        if (allUpper) return word.toUpperCase(locale);
        if (Character.isUpperCase(prefix.codePointAt(0))) {
            int first = word.codePointAt(0);
            int n = Character.charCount(first);
            return new String(Character.toChars(Character.toUpperCase(first))) + word.substring(n);
        }
        return word;
    }

    /**
     * True if {@code word} may be learned: 2..32 characters, letters with inner apostrophes or hyphens,
     * and not all digits (numbers, codes and one-time passwords are never stored).
     */
    public static boolean learnable(String word) {
        if (word == null || word.length() < 2 || word.length() > 32) return false;
        int letters = 0;
        for (int i = 0; i < word.length(); ) {
            int cp = word.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetter(cp)) letters++;
            else if (cp != '\'' && cp != '-' && cp != '’') return false;
        }
        return letters >= 2;
    }
}
