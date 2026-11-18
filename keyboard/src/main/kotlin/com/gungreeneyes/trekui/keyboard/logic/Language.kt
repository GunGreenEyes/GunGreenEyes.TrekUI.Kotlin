package com.gungreeneyes.trekui.keyboard.logic

import java.util.Locale

/**
 * The keyboard languages: their letter rows and the accented letters they offer first on long press.
 * Each matches one subtype in `res/xml/method.xml`.
 */
internal enum class Language(
    /** BCP-47 language code. */
    val code: String,
    /** The three letter rows (top to bottom); the third sits between SHIFT and DELETE. */
    val letterRows: List<String>,
    /** Accented letters this language lists first in a long-press popup. */
    val preferred: String,
) {
    /** English, QWERTY. */
    EN("en", listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"), ""),

    /** German, QWERTZ with ü ö ä on the right. */
    DE("de", listOf("qwertzuiopü", "asdfghjklöä", "yxcvbnm"), "äöüß"),

    /** French, AZERTY with the apostrophe after n. */
    FR("fr", listOf("azertyuiop", "qsdfghjklm", "wxcvbn'"), "éèêëàâçîïôûùœæ"),

    /** Spanish, QWERTY with ñ after l. */
    ES("es", listOf("qwertyuiop", "asdfghjklñ", "zxcvbnm"), "áéíóúñü");

    companion object {
        /** The language for a locale string such as "de_DE" or "fr-CA"; English for anything unknown. */
        fun forLocale(locale: String?): Language {
            if (locale.isNullOrEmpty()) return EN
            val lang = locale.replace('-', '_').split("_")[0].lowercase(Locale.ROOT)
            return entries.firstOrNull { it.code == lang } ?: EN
        }

        /** The language for a stored code ("en", "de", …), or [fallback]. */
        fun forCode(code: String?, fallback: Language?): Language? =
            entries.firstOrNull { it.code == code } ?: fallback
    }
}
