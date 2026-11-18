package com.gungreeneyes.trekui.keyboard.logic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The layout tables: builds every page (letters per language, two symbol pages, number pad, dial pad and
 * a small emoji page) as a {@link KeyLayout}. Pure Java, so the tables are unit-tested.
 *
 * <p>Letter pages: the language's three rows (top-row letters carry their digit as a long-press choice
 * and a corner hint), SHIFT and DELETE around the third row, and a bottom row of
 * {@code ?123 , [globe] emoji SPACE . ENTER}. The comma becomes @ in e-mail fields and / in web-address
 * fields.
 */
public final class Layouts {
    private Layouts() {}

    /** Width of SHIFT, DELETE, page switch and ENTER keys. */
    static final float WIDE = 1.5f;
    /** Letter rows are laid out against at least this many key widths. */
    static final float MIN_ROW = 10f;

    /** Options the bottom row depends on. */
    public static final class Options {
        /** Show the globe key (more than one language on, or other keyboards to switch to). */
        public boolean globe;
        /** Add a digit row above the letters (top-row letters then drop their digit hints). */
        public boolean digitRow;
        public FieldRules.FieldKind field = FieldRules.FieldKind.NORMAL;
    }

    /** Emoji on the built-in emoji page (a basic set; long-press the key for a full picker). */
    static final String[] EMOJI = {
            "😀", "😂", "🥲", "😊", "😍", "😘", "😉", "😎",
            "🤔", "😴", "😭", "😡", "👍", "👎", "👏", "🙏",
            "❤️", "🔥", "✨", "🎉", "💯", "✅", "❌", "⭐",
            "🚀", "🌍", "☕", "🍕", "🎵", "📷", "💡", "👋"};
    static final int EMOJI_COLUMNS = 8;

    /** Builds the page {@code mode} for {@code lang}. */
    public static KeyLayout build(Mode mode, Language lang, Options o) {
        switch (mode) {
            case SYMBOLS: return symbols(lang, o);
            case SYMBOLS_2: return symbols2(lang, o);
            case NUMERIC: return numeric(lang);
            case PHONE: return phone(lang);
            case EMOJI: return emoji(lang);
            default: return letters(lang, o);
        }
    }

    // ---- letters ---------------------------------------------------------------------------------

    static KeyLayout letters(Language lang, Options o) {
        List<List<Key>> rows = new ArrayList<>();
        String[] lr = lang.letterRows;
        float width = Math.max(MIN_ROW, Math.max(lr[0].length(), lr[1].length()));
        if (o.digitRow) rows.add(charRow("1234567890", lang, false));
        rows.add(charRow(lr[0], lang, !o.digitRow));
        rows.add(charRow(lr[1], lang, false));
        List<Key> third = new ArrayList<>();
        float side = Math.max(1f, (width - lr[2].length()) / 2f);
        third.add(Key.fn(Key.SHIFT, side));
        third.addAll(charRow(lr[2], lang, false));
        third.add(Key.fn(Key.DELETE, side));
        rows.add(third);
        rows.add(bottomRow(Key.TO_SYMBOLS, width, o));
        return new KeyLayout(Mode.LETTERS, lang, rows);
    }

    /** One row of character keys; {@code digits} gives the first ten their digit (1..9, 0). */
    static List<Key> charRow(String chars, Language lang, boolean digits) {
        List<Key> row = new ArrayList<>();
        int col = 0;
        for (int i = 0; i < chars.length(); ) {
            int cp = chars.codePointAt(i);
            String s = new String(Character.toChars(cp));
            i += Character.charCount(cp);
            String digit = digits && col < 10 ? String.valueOf((col + 1) % 10) : null;
            List<String> alts = Character.isLetter(cp) ? Alternates.forLetter(s, lang, digit) : Alternates.forSymbol(s);
            row.add(Key.chr(s, alts, digit));
            col++;
        }
        return row;
    }

    /** {@code ?123 , [globe] emoji SPACE . ENTER}, the space bar filling the row to {@code width}. */
    static List<Key> bottomRow(int pageKey, float width, Options o) {
        List<Key> row = new ArrayList<>();
        row.add(Key.fn(pageKey, WIDE));
        String extra = o.field == FieldRules.FieldKind.EMAIL ? "@" : o.field == FieldRules.FieldKind.URL ? "/" : ",";
        row.add(Key.chr(extra, Alternates.forSymbol(extra), null));
        if (o.globe) row.add(Key.fn(Key.GLOBE, 1f));
        row.add(Key.fn(Key.EMOJI, 1f));
        float used = WIDE + 1f + (o.globe ? 1f : 0f) + 1f + 1f + WIDE;
        row.add(Key.fn(Key.SPACE, Math.max(2f, width - used)));
        row.add(Key.chr(".", Alternates.forSymbol("."), null));
        row.add(Key.fn(Key.ENTER, WIDE));
        return row;
    }

    // ---- symbols ---------------------------------------------------------------------------------

    /** The currency key of {@code lang}'s first symbols page (€ for the euro languages). */
    static String currency(Language lang) {
        return lang == Language.EN ? "$" : "€";
    }

    static KeyLayout symbols(Language lang, Options o) {
        List<List<Key>> rows = new ArrayList<>();
        rows.add(charRow("1234567890", lang, false));
        List<Key> r2 = new ArrayList<>();
        for (String s : Arrays.asList("@", "#", currency(lang), "%", "&", "-", "+", "(", ")", "/")) {
            List<String> alts = Alternates.forSymbol(s);
            if ("€".equals(s)) alts = Arrays.asList("$", "£", "¥", "¢");
            r2.add(Key.chr(s, alts, null));
        }
        rows.add(r2);
        List<Key> r3 = new ArrayList<>();
        r3.add(Key.fn(Key.TO_SYMBOLS_2, WIDE));
        r3.addAll(charRow("*\"':;!?", lang, false));
        r3.add(Key.fn(Key.DELETE, WIDE));
        rows.add(r3);
        rows.add(bottomRow(Key.TO_LETTERS, MIN_ROW, o));
        return new KeyLayout(Mode.SYMBOLS, lang, rows);
    }

    static KeyLayout symbols2(Language lang, Options o) {
        List<List<Key>> rows = new ArrayList<>();
        rows.add(charRow("~`|•√π÷×¶∆", lang, false));
        rows.add(charRow("£¥€¢^°={}\\", lang, false));
        List<Key> r3 = new ArrayList<>();
        r3.add(Key.fn(Key.TO_SYMBOLS, WIDE));
        r3.addAll(charRow("_<>[]©™", lang, false));
        r3.add(Key.fn(Key.DELETE, WIDE));
        rows.add(r3);
        rows.add(bottomRow(Key.TO_LETTERS, MIN_ROW, o));
        return new KeyLayout(Mode.SYMBOLS_2, lang, rows);
    }

    // ---- number pad and dial pad -------------------------------------------------------------------

    static KeyLayout numeric(Language lang) {
        List<List<Key>> rows = new ArrayList<>();
        rows.add(Arrays.asList(digit("1"), digit("2"), digit("3"), Key.chr("-", Alternates.forSymbol("-"), null)));
        rows.add(Arrays.asList(digit("4"), digit("5"), digit("6"), Key.fn(Key.SPACE, 1f)));
        rows.add(Arrays.asList(digit("7"), digit("8"), digit("9"), Key.fn(Key.DELETE, 1f)));
        rows.add(Arrays.asList(Key.chr(",", Arrays.asList("/", ":", "+", "*", "#"), null), digit("0"),
                Key.chr(".", Arrays.asList(","), null), Key.fn(Key.ENTER, 1f)));
        return new KeyLayout(Mode.NUMERIC, lang, rows);
    }

    static KeyLayout phone(Language lang) {
        List<List<Key>> rows = new ArrayList<>();
        rows.add(Arrays.asList(Key.chr("1", null, null), Key.chr("2", null, null), Key.chr("3", null, null),
                Key.chr("-", Arrays.asList("."), null)));
        rows.add(Arrays.asList(Key.chr("4", null, null), Key.chr("5", null, null), Key.chr("6", null, null),
                Key.fn(Key.SPACE, 1f)));
        rows.add(Arrays.asList(Key.chr("7", null, null), Key.chr("8", null, null), Key.chr("9", null, null),
                Key.fn(Key.DELETE, 1f)));
        rows.add(Arrays.asList(Key.chr("*", Arrays.asList("(", ")", ",", ";"), null),
                Key.chr("0", Arrays.asList("+"), "+"), Key.chr("#", null, null), Key.fn(Key.ENTER, 1f)));
        return new KeyLayout(Mode.PHONE, lang, rows);
    }

    private static Key digit(String d) {
        return Key.chr(d, Alternates.forSymbol(d), null);
    }

    // ---- emoji -------------------------------------------------------------------------------------

    static KeyLayout emoji(Language lang) {
        List<List<Key>> rows = new ArrayList<>();
        for (int r = 0; r < EMOJI.length / EMOJI_COLUMNS; r++) {
            List<Key> row = new ArrayList<>();
            for (int c = 0; c < EMOJI_COLUMNS; c++) row.add(Key.chr(EMOJI[r * EMOJI_COLUMNS + c], null, null));
            rows.add(row);
        }
        rows.add(Arrays.asList(Key.fn(Key.TO_LETTERS, 2f), Key.fn(Key.SPACE, 4f), Key.fn(Key.DELETE, 2f)));
        return new KeyLayout(Mode.EMOJI, lang, rows);
    }
}
