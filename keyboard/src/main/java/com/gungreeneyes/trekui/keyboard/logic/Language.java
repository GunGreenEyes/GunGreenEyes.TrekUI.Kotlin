package com.gungreeneyes.trekui.keyboard.logic;

import java.util.Locale;

/**
 * The keyboard languages: their letter rows and the accented letters they offer first on long press.
 * Each matches one subtype in {@code res/xml/method.xml}.
 */
public enum Language {
    /** English, QWERTY. */
    EN("en", new String[]{"qwertyuiop", "asdfghjkl", "zxcvbnm"}, ""),
    /** German, QWERTZ with ü ö ä on the right. */
    DE("de", new String[]{"qwertzuiopü", "asdfghjklöä", "yxcvbnm"}, "äöüß"),
    /** French, AZERTY with the apostrophe after n. */
    FR("fr", new String[]{"azertyuiop", "qsdfghjklm", "wxcvbn'"}, "éèêëàâçîïôûùœæ"),
    /** Spanish, QWERTY with ñ after l. */
    ES("es", new String[]{"qwertyuiop", "asdfghjklñ", "zxcvbnm"}, "áéíóúñü");

    /** BCP-47 language code. */
    public final String code;
    /** The three letter rows (top to bottom); the third sits between SHIFT and DELETE. */
    final String[] letterRows;
    /** Accented letters this language lists first in a long-press popup. */
    final String preferred;

    Language(String code, String[] letterRows, String preferred) {
        this.code = code;
        this.letterRows = letterRows;
        this.preferred = preferred;
    }

    /** The language for a locale string such as "de_DE" or "fr-CA"; English for anything unknown. */
    public static Language forLocale(String locale) {
        if (locale == null || locale.isEmpty()) return EN;
        String lang = locale.replace('-', '_').split("_")[0].toLowerCase(Locale.ROOT);
        for (Language l : values()) if (l.code.equals(lang)) return l;
        return EN;
    }

    /** The language for a stored code ("en", "de", …), or {@code fallback}. */
    public static Language forCode(String code, Language fallback) {
        for (Language l : values()) if (l.code.equals(code)) return l;
        return fallback;
    }
}
