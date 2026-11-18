package com.gungreeneyes.trekui.keyboard.logic;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SuggestionsTest {
    private static final Locale EN = Locale.ENGLISH;

    @Test
    public void onlyCompletionsOfThePrefix() {
        Map<String, Integer> dict = new HashMap<>();
        dict.put("hello", 200);
        dict.put("help", 100);
        dict.put("world", 250);
        List<String> out = Suggestions.rank("he", null, dict, 3, EN);
        assertThat(out).containsExactly("hello", "help").inOrder();
    }

    @Test
    public void typedWordsOutrankDictionaryWithUse() {
        Map<String, Integer> dict = new HashMap<>();
        dict.put("there", 120);
        Map<String, Integer> typed = new HashMap<>();
        typed.put("thermal", 5); // 5 * 40 = 200
        assertThat(Suggestions.rank("the", typed, dict, 3, EN)).containsExactly("thermal", "there").inOrder();
    }

    @Test
    public void bothSourcesAddUp() {
        Map<String, Integer> dict = new HashMap<>();
        dict.put("alpha", 100);
        dict.put("alpine", 150);
        Map<String, Integer> typed = new HashMap<>();
        typed.put("alpha", 2); // 100 + 80 = 180 > 150
        assertThat(Suggestions.rank("al", typed, dict, 3, EN).get(0)).isEqualTo("alpha");
    }

    @Test
    public void tiesGoToShorterThenAlphabetical() {
        Map<String, Integer> typed = new HashMap<>();
        typed.put("cart", 1);
        typed.put("car", 1);
        typed.put("cab", 1);
        assertThat(Suggestions.rank("ca", typed, null, 3, EN)).containsExactly("cab", "car", "cart").inOrder();
    }

    @Test
    public void exactPrefixIsNotOfferedAndLimitApplies() {
        Map<String, Integer> typed = new HashMap<>();
        typed.put("go", 9);
        typed.put("good", 3);
        typed.put("gone", 2);
        typed.put("google", 1);
        List<String> out = Suggestions.rank("go", typed, null, 2, EN);
        assertThat(out).containsExactly("good", "gone").inOrder();
    }

    @Test
    public void suggestionsFollowThePrefixCase() {
        Map<String, Integer> typed = new HashMap<>();
        typed.put("starship", 1);
        assertThat(Suggestions.rank("Sta", typed, null, 1, EN)).containsExactly("Starship");
        assertThat(Suggestions.rank("STA", typed, null, 1, EN)).containsExactly("STARSHIP");
        assertThat(Suggestions.rank("sta", typed, null, 1, EN)).containsExactly("starship");
    }

    @Test
    public void emptyInputs() {
        assertThat(Suggestions.rank("", null, null, 3, EN)).isEmpty();
        assertThat(Suggestions.rank(null, null, null, 3, EN)).isEmpty();
        assertThat(Suggestions.rank("a", null, null, 0, EN)).isEmpty();
    }

    @Test
    public void onlyRealWordsAreLearnable() {
        assertThat(Suggestions.learnable("hello")).isTrue();
        assertThat(Suggestions.learnable("don't")).isTrue();
        assertThat(Suggestions.learnable("señor")).isTrue();
        assertThat(Suggestions.learnable("a")).isFalse();
        assertThat(Suggestions.learnable("123456")).isFalse();
        assertThat(Suggestions.learnable("abc123")).isFalse();
        assertThat(Suggestions.learnable("x@y")).isFalse();
    }
}
