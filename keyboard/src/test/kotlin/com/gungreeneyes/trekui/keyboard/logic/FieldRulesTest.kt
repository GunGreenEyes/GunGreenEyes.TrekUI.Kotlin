package com.gungreeneyes.trekui.keyboard.logic

import android.text.InputType
import android.view.inputmethod.EditorInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FieldRulesTest {
    @Test
    fun inputTypeSelectsThePage() {
        assertThat(FieldRules.modeFor(TEXT)).isEqualTo(Mode.LETTERS)
        assertThat(FieldRules.modeFor(InputType.TYPE_CLASS_NUMBER)).isEqualTo(Mode.NUMERIC)
        assertThat(FieldRules.modeFor(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)).isEqualTo(Mode.NUMERIC)
        assertThat(FieldRules.modeFor(InputType.TYPE_CLASS_DATETIME)).isEqualTo(Mode.NUMERIC)
        assertThat(FieldRules.modeFor(InputType.TYPE_CLASS_PHONE)).isEqualTo(Mode.PHONE)
        assertThat(FieldRules.modeFor(InputType.TYPE_NULL)).isEqualTo(Mode.LETTERS)
    }

    @Test
    fun fieldKinds() {
        assertThat(FieldRules.fieldKind(TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)).isEqualTo(FieldRules.FieldKind.EMAIL)
        assertThat(FieldRules.fieldKind(TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)).isEqualTo(FieldRules.FieldKind.EMAIL)
        assertThat(FieldRules.fieldKind(TEXT or InputType.TYPE_TEXT_VARIATION_URI)).isEqualTo(FieldRules.FieldKind.URL)
        assertThat(FieldRules.fieldKind(TEXT)).isEqualTo(FieldRules.FieldKind.NORMAL)
    }

    @Test
    fun passwordsAreIncognito() {
        val passwords = intArrayOf(
            TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        for (t in passwords) {
            assertThat(FieldRules.isPassword(t)).isTrue()
            assertThat(FieldRules.noLearning(t, 0)).isTrue()
            assertThat(FieldRules.suggestionsAllowed(t, 0)).isFalse()
        }
    }

    @Test
    fun noPersonalizedLearningFlagIsIncognito() {
        val flag = FieldRules.FLAG_NO_PERSONALIZED_LEARNING
        assertThat(FieldRules.noLearning(TEXT, flag)).isTrue()
        assertThat(FieldRules.suggestionsAllowed(TEXT, flag)).isFalse()
        assertThat(FieldRules.noLearning(TEXT, 0)).isFalse()
        assertThat(FieldRules.suggestionsAllowed(TEXT, 0)).isTrue()
    }

    @Test
    fun noSuggestionsFlagAndAddressesHideTheStrip() {
        assertThat(FieldRules.suggestionsAllowed(TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0)).isFalse()
        assertThat(FieldRules.suggestionsAllowed(TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0)).isFalse()
        assertThat(FieldRules.suggestionsAllowed(InputType.TYPE_CLASS_NUMBER, 0)).isFalse()
    }

    @Test
    fun autoCaps() {
        assertThat(FieldRules.wantsCaps(TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)).isTrue()
        assertThat(FieldRules.wantsCaps(TEXT)).isFalse()
        assertThat(
            FieldRules.wantsCaps(TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        ).isFalse()
    }

    @Test
    fun enterKeyFollowsImeOptions() {
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_SEARCH)).isEqualTo(FieldRules.Enter.SEARCH)
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_SEND)).isEqualTo(FieldRules.Enter.SEND)
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_GO)).isEqualTo(FieldRules.Enter.GO)
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_NEXT)).isEqualTo(FieldRules.Enter.NEXT)
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_DONE)).isEqualTo(FieldRules.Enter.DONE)
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_PREVIOUS)).isEqualTo(FieldRules.Enter.PREVIOUS)
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)).isEqualTo(FieldRules.Enter.NEWLINE)
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
            .isEqualTo(FieldRules.Enter.NEWLINE)
        assertThat(FieldRules.actionId(FieldRules.Enter.SEND)).isEqualTo(EditorInfo.IME_ACTION_SEND)
        assertThat(FieldRules.actionId(FieldRules.Enter.NEWLINE)).isEqualTo(EditorInfo.IME_ACTION_NONE)
    }

    private companion object {
        const val TEXT = InputType.TYPE_CLASS_TEXT
    }
}
