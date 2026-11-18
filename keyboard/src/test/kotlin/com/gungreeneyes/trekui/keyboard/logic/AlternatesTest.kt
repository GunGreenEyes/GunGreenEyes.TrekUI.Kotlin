package com.gungreeneyes.trekui.keyboard.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class AlternatesTest {
    @Test
    fun digitComesFirstThenAccents() {
        val e = Alternates.forLetter("e", Language.EN, "3")
        assertThat(e[0]).isEqualTo("3")
        assertThat(e).containsAtLeast("é", "è", "ê", "ë")
    }

    @Test
    fun languagePreferredAccentsLead() {
        assertThat(Alternates.forLetter("e", Language.FR, null).subList(0, 4))
            .containsExactly("é", "è", "ê", "ë").inOrder()
        assertThat(Alternates.forLetter("a", Language.DE, null)[0]).isEqualTo("ä")
        assertThat(Alternates.forLetter("u", Language.ES, null).subList(0, 2))
            .containsExactly("ú", "ü").inOrder()
        assertThat(Alternates.forLetter("s", Language.DE, null)[0]).isEqualTo("ß")
    }

    @Test
    fun lettersWithoutAccentsHaveOnlyTheirDigit() {
        assertThat(Alternates.forLetter("q", Language.EN, "1")).containsExactly("1")
        assertThat(Alternates.forLetter("k", Language.EN, null)).isEmpty()
    }

    @Test
    fun noDuplicates() {
        for (lang in Language.entries) {
            for (c in 'a'..'z') {
                val alts = Alternates.forLetter(c.toString(), lang, "1")
                assertThat(alts).containsNoDuplicates()
            }
        }
    }

    @Test
    fun punctuationAlternates() {
        assertThat(Alternates.forSymbol("?")).containsExactly("¿")
        assertThat(Alternates.forSymbol("$")).contains("€")
        assertThat(Alternates.forSymbol(".")).contains("!")
        assertThat(Alternates.forSymbol("x")).isEmpty()
    }

    @Test
    fun shiftKeepsSharpS() {
        assertThat(Alternates.shifted("ß", Locale.GERMAN)).isEqualTo("ß")
        assertThat(Alternates.shifted("é", Locale.FRENCH)).isEqualTo("É")
        assertThat(Alternates.shifted("ñ", Locale.ROOT)).isEqualTo("Ñ")
        assertThat(Alternates.shifted("1", Locale.ROOT)).isEqualTo("1")
    }
}
