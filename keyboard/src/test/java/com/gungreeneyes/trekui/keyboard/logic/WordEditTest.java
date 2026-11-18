package com.gungreeneyes.trekui.keyboard.logic;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.Arrays;

public class WordEditTest {
    @Test
    public void currentWord() {
        assertThat(WordEdit.currentWord("hello wor")).isEqualTo("wor");
        assertThat(WordEdit.currentWord("hello ")).isEqualTo("");
        assertThat(WordEdit.currentWord("say \"don't")).isEqualTo("don't");
        assertThat(WordEdit.currentWord("'quote")).isEqualTo("quote");
        assertThat(WordEdit.currentWord("")).isEqualTo("");
        assertThat(WordEdit.currentWord(null)).isEqualTo("");
    }

    @Test
    public void deleteWordRemovesTheWordAndTrailingSpaces() {
        assertThat(WordEdit.deleteWordLength("hello world")).isEqualTo(5);
        assertThat(WordEdit.deleteWordLength("hello world  ")).isEqualTo(7);
        assertThat(WordEdit.deleteWordLength("one")).isEqualTo(3);
        assertThat(WordEdit.deleteWordLength("   ")).isEqualTo(3);
        assertThat(WordEdit.deleteWordLength("")).isEqualTo(0);
    }

    @Test
    public void deleteWordStopsAtPunctuation() {
        assertThat(WordEdit.deleteWordLength("end.")).isEqualTo(1);
        assertThat(WordEdit.deleteWordLength("wait!!! ")).isEqualTo(4);
        assertThat(WordEdit.deleteWordLength("a,b")).isEqualTo(1);
    }

    @Test
    public void sizingPresetsAndClamping() {
        assertThat(Sizing.rowDp(Sizing.S, false)).isLessThan(Sizing.rowDp(Sizing.XL, false));
        assertThat(Sizing.rowDp(Sizing.M, true)).isLessThan(Sizing.rowDp(Sizing.M, false));
        assertThat(Sizing.clampPreset(99)).isEqualTo(Sizing.M);
        // a short landscape window shrinks rows, but never below 60 % of the preset
        int px = Sizing.rowPx(Sizing.XL, true, 2f, 4, 400);
        assertThat(px).isEqualTo(60); // 50dp*2 = 100px wanted; 400*0.5/4 = 50; floor 60
        assertThat(Sizing.rowPx(Sizing.M, false, 2f, 4, 4000)).isEqualTo(104);
    }

    @Test
    public void languageCycle() {
        assertThat(Sizing.nextLanguage(Language.EN, Arrays.asList(Language.EN, Language.DE))).isEqualTo(Language.DE);
        assertThat(Sizing.nextLanguage(Language.DE, Arrays.asList(Language.EN, Language.DE))).isEqualTo(Language.EN);
        assertThat(Sizing.nextLanguage(Language.FR, Arrays.asList(Language.EN, Language.DE))).isEqualTo(Language.EN);
        assertThat(Language.forLocale("de_DE")).isEqualTo(Language.DE);
        assertThat(Language.forLocale("fr-CA")).isEqualTo(Language.FR);
        assertThat(Language.forLocale("ja_JP")).isEqualTo(Language.EN);
    }
}
