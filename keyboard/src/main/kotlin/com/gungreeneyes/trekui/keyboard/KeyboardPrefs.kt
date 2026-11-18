package com.gungreeneyes.trekui.keyboard

import android.content.Context
import com.gungreeneyes.trekui.TrekPrefs
import com.gungreeneyes.trekui.keyboard.logic.Language
import com.gungreeneyes.trekui.keyboard.logic.Sizing
import java.util.Locale

/**
 * The keyboard's own settings, stored next to the shared look settings in this app's `trekui`
 * prefs file under `kb.` keys: size presets, one-handed mode, digit row, haptics, suggestions,
 * learning and the enabled / current languages. Key sounds and their volume live in `KeyboardLook`
 * (they are the :core Sfx switches).
 */
internal class KeyboardPrefs private constructor(c: Context) {
    private val p: TrekPrefs = TrekPrefs.get(c)

    /** Portrait size preset (S..XL). */
    var size: Int
        get() = Sizing.clampPreset(p.i(SIZE, Sizing.M))
        set(v) = p.put(SIZE, Sizing.clampPreset(v))

    /** Landscape size preset (S..XL). */
    var sizeLandscape: Int
        get() = Sizing.clampPreset(p.i(SIZE_LAND, Sizing.M))
        set(v) = p.put(SIZE_LAND, Sizing.clampPreset(v))

    /** One-handed mode (`Sizing.ONE_HANDED_*`); OFF for unknown stored values. */
    var oneHanded: Int
        get() = p.i(ONE_HANDED, Sizing.ONE_HANDED_OFF)
            .takeIf { it in Sizing.ONE_HANDED_OFF..Sizing.ONE_HANDED_RIGHT } ?: Sizing.ONE_HANDED_OFF
        set(v) = p.put(ONE_HANDED, v)

    var digitRow: Boolean
        get() = p.b(DIGIT_ROW, false)
        set(v) = p.put(DIGIT_ROW, v)

    var haptics: Boolean
        get() = p.b(HAPTICS, true)
        set(v) = p.put(HAPTICS, v)

    var suggestions: Boolean
        get() = p.b(SUGGEST, true)
        set(v) = p.put(SUGGEST, v)

    var learn: Boolean
        get() = p.b(LEARN, true)
        set(v) = p.put(LEARN, v)

    /** The languages switched on, in table order; defaults to the device language (English otherwise). */
    fun languages(): List<Language> {
        val on = p.s(LANGS, null)
            ?.split(",")
            ?.mapNotNull { Language.forCode(it.trim(), null) }
            .orEmpty()
            .ifEmpty { listOf(Language.forLocale(Locale.getDefault().toString())) }
        return Language.entries.filter { it in on }
    }

    /** Switches [l] on or off (the last language can't be switched off). */
    fun setLanguage(l: Language, on: Boolean) {
        val now = languages().toMutableList()
        if (on && l !in now) now += l
        if (!on && now.size > 1) now -= l
        p.put(LANGS, now.joinToString(",") { it.code })
    }

    /** The language in use (one of [languages]). */
    var current: Language
        get() {
            val on = languages()
            val l = Language.forCode(p.s(LANG, null), on[0]) ?: on[0]
            return if (l in on) l else on[0]
        }
        set(l) = p.put(LANG, l.code)

    companion object {
        const val SIZE = "kb.size"
        const val SIZE_LAND = "kb.sizeLand"
        const val ONE_HANDED = "kb.oneHanded"
        const val DIGIT_ROW = "kb.digitRow"
        const val HAPTICS = "kb.haptics"
        const val SUGGEST = "kb.suggest"
        const val LEARN = "kb.learn"
        const val LANGS = "kb.langs"
        const val LANG = "kb.lang"

        fun get(c: Context): KeyboardPrefs = KeyboardPrefs(c)
    }
}
