package com.gungreeneyes.trekui.keyboard.logic;

import static com.google.common.truth.Truth.assertThat;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import org.junit.Test;

public class FieldRulesTest {
    private static final int TEXT = InputType.TYPE_CLASS_TEXT;

    @Test
    public void inputTypeSelectsThePage() {
        assertThat(FieldRules.modeFor(TEXT)).isEqualTo(Mode.LETTERS);
        assertThat(FieldRules.modeFor(InputType.TYPE_CLASS_NUMBER)).isEqualTo(Mode.NUMERIC);
        assertThat(FieldRules.modeFor(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL)).isEqualTo(Mode.NUMERIC);
        assertThat(FieldRules.modeFor(InputType.TYPE_CLASS_DATETIME)).isEqualTo(Mode.NUMERIC);
        assertThat(FieldRules.modeFor(InputType.TYPE_CLASS_PHONE)).isEqualTo(Mode.PHONE);
        assertThat(FieldRules.modeFor(InputType.TYPE_NULL)).isEqualTo(Mode.LETTERS);
    }

    @Test
    public void fieldKinds() {
        assertThat(FieldRules.fieldKind(TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)).isEqualTo(FieldRules.FieldKind.EMAIL);
        assertThat(FieldRules.fieldKind(TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)).isEqualTo(FieldRules.FieldKind.EMAIL);
        assertThat(FieldRules.fieldKind(TEXT | InputType.TYPE_TEXT_VARIATION_URI)).isEqualTo(FieldRules.FieldKind.URL);
        assertThat(FieldRules.fieldKind(TEXT)).isEqualTo(FieldRules.FieldKind.NORMAL);
    }

    @Test
    public void passwordsAreIncognito() {
        int[] passwords = {
                TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD};
        for (int t : passwords) {
            assertThat(FieldRules.isPassword(t)).isTrue();
            assertThat(FieldRules.noLearning(t, 0)).isTrue();
            assertThat(FieldRules.suggestionsAllowed(t, 0)).isFalse();
        }
    }

    @Test
    public void noPersonalizedLearningFlagIsIncognito() {
        int flag = FieldRules.FLAG_NO_PERSONALIZED_LEARNING;
        assertThat(FieldRules.noLearning(TEXT, flag)).isTrue();
        assertThat(FieldRules.suggestionsAllowed(TEXT, flag)).isFalse();
        assertThat(FieldRules.noLearning(TEXT, 0)).isFalse();
        assertThat(FieldRules.suggestionsAllowed(TEXT, 0)).isTrue();
    }

    @Test
    public void noSuggestionsFlagAndAddressesHideTheStrip() {
        assertThat(FieldRules.suggestionsAllowed(TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0)).isFalse();
        assertThat(FieldRules.suggestionsAllowed(TEXT | InputType.TYPE_TEXT_VARIATION_URI, 0)).isFalse();
        assertThat(FieldRules.suggestionsAllowed(InputType.TYPE_CLASS_NUMBER, 0)).isFalse();
    }

    @Test
    public void autoCaps() {
        assertThat(FieldRules.wantsCaps(TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)).isTrue();
        assertThat(FieldRules.wantsCaps(TEXT)).isFalse();
        assertThat(FieldRules.wantsCaps(TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_VARIATION_PASSWORD)).isFalse();
    }

    @Test
    public void enterKeyFollowsImeOptions() {
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_SEARCH)).isEqualTo(FieldRules.Enter.SEARCH);
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_SEND)).isEqualTo(FieldRules.Enter.SEND);
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_GO)).isEqualTo(FieldRules.Enter.GO);
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_NEXT)).isEqualTo(FieldRules.Enter.NEXT);
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_DONE)).isEqualTo(FieldRules.Enter.DONE);
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_PREVIOUS)).isEqualTo(FieldRules.Enter.PREVIOUS);
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)).isEqualTo(FieldRules.Enter.NEWLINE);
        assertThat(FieldRules.enterFor(TEXT, EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_ENTER_ACTION))
                .isEqualTo(FieldRules.Enter.NEWLINE);
        assertThat(FieldRules.actionId(FieldRules.Enter.SEND)).isEqualTo(EditorInfo.IME_ACTION_SEND);
        assertThat(FieldRules.actionId(FieldRules.Enter.NEWLINE)).isEqualTo(EditorInfo.IME_ACTION_NONE);
    }
}
