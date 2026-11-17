package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** LCA-T35: the pure rules behind CONFIG › SUITE ({@link SuiteSettings}). */
public class SuiteSettingsTest {

    private static final String KEYBOARD = "com.gungreeneyes.trekui.keyboard";
    private static final String MESSAGES = "com.gungreeneyes.trekui.messages";

    @Test
    public void imePackage_parsesInputMethodIds() {
        assertThat(SuiteSettings.imePackage("com.example.ime/.Service")).isEqualTo("com.example.ime");
        assertThat(SuiteSettings.imePackage(KEYBOARD + "/" + KEYBOARD + ".KeysService")).isEqualTo(KEYBOARD);
        assertThat(SuiteSettings.imePackage("bare.package")).isEqualTo("bare.package");
        assertThat(SuiteSettings.imePackage("/.Service")).isNull();
        assertThat(SuiteSettings.imePackage(null)).isNull();
    }

    @Test
    public void isSuiteKeyboard_matchesOnlyTheExactPackage() {
        assertThat(SuiteSettings.isSuiteKeyboard(KEYBOARD + "/.Ime", KEYBOARD)).isTrue();
        assertThat(SuiteSettings.isSuiteKeyboard(KEYBOARD + ".dev/.Ime", KEYBOARD)).isFalse();
        assertThat(SuiteSettings.isSuiteKeyboard("com.google.android.inputmethod.latin/.LatinIME", KEYBOARD)).isFalse();
        assertThat(SuiteSettings.isSuiteKeyboard(null, KEYBOARD)).isFalse();
    }

    @Test
    public void restoreTarget_skipsMissingAndOurOwnMessages() {
        assertThat(SuiteSettings.restoreTarget("com.google.android.apps.messaging", MESSAGES))
                .isEqualTo("com.google.android.apps.messaging");
        assertThat(SuiteSettings.restoreTarget(MESSAGES, MESSAGES)).isNull();
        assertThat(SuiteSettings.restoreTarget("", MESSAGES)).isNull();
        assertThat(SuiteSettings.restoreTarget(null, MESSAGES)).isNull();
    }
}
