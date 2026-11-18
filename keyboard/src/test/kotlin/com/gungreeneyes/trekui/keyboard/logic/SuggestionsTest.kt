package com.gungreeneyes.trekui.keyboard.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class SuggestionsTest {
    @Test
    fun onlyCompletionsOfThePrefix() {
        val dict = hashMapOf("hello" to 200, "help" to 100, "world" to 250)
        val out = Suggestions.rank("he", null, dict, 3, EN)
        assertThat(out).containsExactly("hello", "help").inOrder()
    }

    @Test
    fun typedWordsOutrankDictionaryWithUse() {
        val dict = hashMapOf("there" to 120)
        val typed = hashMapOf("thermal" to 5) // 5 * 40 = 200
        assertThat(Suggestions.rank("the", typed, dict, 3, EN)).containsExactly("thermal", "there").inOrder()
    }

    @Test
    fun bothSourcesAddUp() {
        val dict = hashMapOf("alpha" to 100, "alpine" to 150)
        val typed = hashMapOf("alpha" to 2) // 100 + 80 = 180 > 150
        assertThat(Suggestions.rank("al", typed, dict, 3, EN)[0]).isEqualTo("alpha")
    }

    /** TUK-T14: case variants of one dictionary word keep the higher frequency and the first spelling. */
    @Test
    fun dictionaryCaseVariantsKeepTheHigherFrequency() {
        val dict = linkedMapOf("Alps" to 10, "alps" to 300, "alpine" to 150)
        assertThat(Suggestions.rank("al", null, dict, 2, EN)).containsExactly("Alps", "alpine").inOrder()
    }

    @Test
    fun tiesGoToShorterThenAlphabetical() {
        val typed = hashMapOf("cart" to 1, "car" to 1, "cab" to 1)
        assertThat(Suggestions.rank("ca", typed, null, 3, EN)).containsExactly("cab", "car", "cart").inOrder()
    }

    @Test
    fun exactPrefixIsNotOfferedAndLimitApplies() {
        val typed = hashMapOf("go" to 9, "good" to 3, "gone" to 2, "google" to 1)
        val out = Suggestions.rank("go", typed, null, 2, EN)
        assertThat(out).containsExactly("good", "gone").inOrder()
    }

    @Test
    fun suggestionsFollowThePrefixCase() {
        val typed = hashMapOf("starship" to 1)
        assertThat(Suggestions.rank("Sta", typed, null, 1, EN)).containsExactly("Starship")
        assertThat(Suggestions.rank("STA", typed, null, 1, EN)).containsExactly("STARSHIP")
        assertThat(Suggestions.rank("sta", typed, null, 1, EN)).containsExactly("starship")
    }

    @Test
    fun emptyInputs() {
        assertThat(Suggestions.rank("", null, null, 3, EN)).isEmpty()
        assertThat(Suggestions.rank(null, null, null, 3, EN)).isEmpty()
        assertThat(Suggestions.rank("a", null, null, 0, EN)).isEmpty()
    }

    @Test
    fun onlyRealWordsAreLearnable() {
        assertThat(Suggestions.learnable("hello")).isTrue()
        assertThat(Suggestions.learnable("don't")).isTrue()
        assertThat(Suggestions.learnable("señor")).isTrue()
        assertThat(Suggestions.learnable("a")).isFalse()
        assertThat(Suggestions.learnable("123456")).isFalse()
        assertThat(Suggestions.learnable("abc123")).isFalse()
        assertThat(Suggestions.learnable("x@y")).isFalse()
    }

    private companion object {
        val EN: Locale = Locale.ENGLISH
    }
}
