package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.text.Collator;
import java.util.Locale;

/** {@link AppRepo}: drawer ordering and search matching (pure JVM). */
public class AppRepoTest {

    private static Collator primary() {
        Collator c = Collator.getInstance(Locale.US);
        c.setStrength(Collator.PRIMARY);
        return c;
    }

    @Test
    public void compareEntries_alphabeticalIgnoringCaseAndAccents() {
        Collator c = primary();
        assertThat(AppRepo.compareEntries(c, "alpha", true, "Beta", true)).isLessThan(0);
        assertThat(AppRepo.compareEntries(c, "Éclair", true, "eclair", true)).isEqualTo(0);
    }

    @Test
    public void compareEntries_mainProfileFirstOnTies() {
        Collator c = primary();
        assertThat(AppRepo.compareEntries(c, "Mail", true, "Mail", false)).isLessThan(0);
        assertThat(AppRepo.compareEntries(c, "Mail", false, "Mail", true)).isGreaterThan(0);
    }

    @Test
    public void matches_wordStartsAndPackage() {
        assertThat(AppRepo.matches("google maps", "com.google.android.apps.maps", "map")).isTrue();
        assertThat(AppRepo.matches("google maps", "com.x", "go ma")).isTrue();
        assertThat(AppRepo.matches("youtube music", "com.google.android.apps.youtube.music", "tube")).isTrue();
        assertThat(AppRepo.matches("calculator", "com.x.calc", "ulat")).isFalse();
        assertThat(AppRepo.matches("calculator", "com.x.calc", "")).isTrue();
        assertThat(AppRepo.matches("calculator", "com.x.calc", null)).isTrue();
        assertThat(AppRepo.matches("calculator", "com.x.calc", "calc zzz")).isFalse();
    }
}
