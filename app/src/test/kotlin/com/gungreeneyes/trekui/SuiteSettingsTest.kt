package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** LCA-T35: the pure rules behind CONFIG › SUITE ([SuiteSettings]). */
class SuiteSettingsTest {

    @Test
    fun imePackage_parsesInputMethodIds() {
        assertThat(SuiteSettings.imePackage("com.example.ime/.Service")).isEqualTo("com.example.ime")
        assertThat(SuiteSettings.imePackage("$KEYBOARD/$KEYBOARD.KeysService")).isEqualTo(KEYBOARD)
        assertThat(SuiteSettings.imePackage("bare.package")).isEqualTo("bare.package")
        assertThat(SuiteSettings.imePackage("/.Service")).isNull()
        assertThat(SuiteSettings.imePackage(null)).isNull()
    }

    @Test
    fun isSuiteKeyboard_matchesOnlyTheExactPackage() {
        assertThat(SuiteSettings.isSuiteKeyboard("$KEYBOARD/.Ime", KEYBOARD)).isTrue()
        assertThat(SuiteSettings.isSuiteKeyboard("$KEYBOARD.dev/.Ime", KEYBOARD)).isFalse()
        assertThat(SuiteSettings.isSuiteKeyboard("com.google.android.inputmethod.latin/.LatinIME", KEYBOARD)).isFalse()
        assertThat(SuiteSettings.isSuiteKeyboard(null, KEYBOARD)).isFalse()
    }

    @Test
    fun restoreTarget_skipsMissingAndOurOwnMessages() {
        assertThat(SuiteSettings.restoreTarget("com.google.android.apps.messaging", MESSAGES))
            .isEqualTo("com.google.android.apps.messaging")
        assertThat(SuiteSettings.restoreTarget(MESSAGES, MESSAGES)).isNull()
        assertThat(SuiteSettings.restoreTarget("", MESSAGES)).isNull()
        assertThat(SuiteSettings.restoreTarget(null, MESSAGES)).isNull()
    }

    private companion object {
        const val KEYBOARD = "com.gungreeneyes.trekui.keyboard"
        const val MESSAGES = "com.gungreeneyes.trekui.messages"
    }
}
