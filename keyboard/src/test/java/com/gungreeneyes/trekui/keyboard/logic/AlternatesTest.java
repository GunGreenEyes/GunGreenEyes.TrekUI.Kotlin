package com.gungreeneyes.trekui.keyboard.logic;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.List;
import java.util.Locale;

public class AlternatesTest {
    @Test
    public void digitComesFirstThenAccents() {
        List<String> e = Alternates.forLetter("e", Language.EN, "3");
        assertThat(e.get(0)).isEqualTo("3");
        assertThat(e).containsAtLeast("é", "è", "ê", "ë");
    }

    @Test
    public void languagePreferredAccentsLead() {
        assertThat(Alternates.forLetter("e", Language.FR, null).subList(0, 4))
                .containsExactly("é", "è", "ê", "ë").inOrder();
        assertThat(Alternates.forLetter("a", Language.DE, null).get(0)).isEqualTo("ä");
        assertThat(Alternates.forLetter("u", Language.ES, null).subList(0, 2))
                .containsExactly("ú", "ü").inOrder();
        assertThat(Alternates.forLetter("s", Language.DE, null).get(0)).isEqualTo("ß");
    }

    @Test
    public void lettersWithoutAccentsHaveOnlyTheirDigit() {
        assertThat(Alternates.forLetter("q", Language.EN, "1")).containsExactly("1");
        assertThat(Alternates.forLetter("k", Language.EN, null)).isEmpty();
    }

    @Test
    public void noDuplicates() {
        for (Language lang : Language.values()) {
            for (char c = 'a'; c <= 'z'; c++) {
                List<String> alts = Alternates.forLetter(String.valueOf(c), lang, "1");
                assertThat(alts).containsNoDuplicates();
            }
        }
    }

    @Test
    public void punctuationAlternates() {
        assertThat(Alternates.forSymbol("?")).containsExactly("¿");
        assertThat(Alternates.forSymbol("$")).contains("€");
        assertThat(Alternates.forSymbol(".")).contains("!");
        assertThat(Alternates.forSymbol("x")).isEmpty();
    }

    @Test
    public void shiftKeepsSharpS() {
        assertThat(Alternates.shifted("ß", Locale.GERMAN)).isEqualTo("ß");
        assertThat(Alternates.shifted("é", Locale.FRENCH)).isEqualTo("É");
        assertThat(Alternates.shifted("ñ", Locale.ROOT)).isEqualTo("Ñ");
        assertThat(Alternates.shifted("1", Locale.ROOT)).isEqualTo("1");
    }
}
