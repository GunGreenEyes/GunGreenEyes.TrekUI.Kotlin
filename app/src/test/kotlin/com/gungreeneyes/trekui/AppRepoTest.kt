package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.text.Collator
import java.util.Locale

/** [AppRepo]: drawer ordering and search matching (pure JVM). */
class AppRepoTest {

    private fun primary(): Collator {
        val c = Collator.getInstance(Locale.US)
        c.strength = Collator.PRIMARY
        return c
    }

    @Test
    fun compareEntries_alphabeticalIgnoringCaseAndAccents() {
        val c = primary()
        assertThat(AppRepo.compareEntries(c, "alpha", true, "Beta", true)).isLessThan(0)
        assertThat(AppRepo.compareEntries(c, "Éclair", true, "eclair", true)).isEqualTo(0)
    }

    @Test
    fun compareEntries_mainProfileFirstOnTies() {
        val c = primary()
        assertThat(AppRepo.compareEntries(c, "Mail", true, "Mail", false)).isLessThan(0)
        assertThat(AppRepo.compareEntries(c, "Mail", false, "Mail", true)).isGreaterThan(0)
    }

    @Test
    fun matches_wordStartsAndPackage() {
        assertThat(AppRepo.matches("google maps", "com.google.android.apps.maps", "map")).isTrue()
        assertThat(AppRepo.matches("google maps", "com.x", "go ma")).isTrue()
        assertThat(AppRepo.matches("youtube music", "com.google.android.apps.youtube.music", "tube")).isTrue()
        assertThat(AppRepo.matches("calculator", "com.x.calc", "ulat")).isFalse()
        assertThat(AppRepo.matches("calculator", "com.x.calc", "")).isTrue()
        assertThat(AppRepo.matches("calculator", "com.x.calc", null)).isTrue()
        assertThat(AppRepo.matches("calculator", "com.x.calc", "calc zzz")).isFalse()
    }
}
