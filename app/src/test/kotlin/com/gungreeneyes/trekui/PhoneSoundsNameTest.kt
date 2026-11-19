package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The names [PhoneSounds] treats as earlier copies of one sound (MediaStore renames clashes "Title (N).wav"). */
class PhoneSoundsNameTest {

    @Test
    fun matchesExactAndClashRenames() {
        assertThat(PhoneSounds.isCopyName("TrekUI Red Alert", "TrekUI Red Alert.wav")).isTrue()
        assertThat(PhoneSounds.isCopyName("TrekUI Red Alert", "TrekUI Red Alert (1).wav")).isTrue()
        assertThat(PhoneSounds.isCopyName("TrekUI Red Alert", "TrekUI Red Alert (12).wav")).isTrue()
    }

    @Test
    fun rejectsOtherSounds() {
        assertThat(PhoneSounds.isCopyName("TrekUI Red Alert", "TrekUI Red Alert Tng.wav")).isFalse()
        assertThat(PhoneSounds.isCopyName("TrekUI Red Alert", "TrekUI Red Alert (x).wav")).isFalse()
        assertThat(PhoneSounds.isCopyName("TrekUI Red Alert", "TrekUI Red Alert.ogg")).isFalse()
        assertThat(PhoneSounds.isCopyName("TrekUI Red Alert", null)).isFalse()
    }

    @Test
    fun likePrefixEscapesWildcards() {
        assertThat(PhoneSounds.likePrefix("A_b%c\\d")).isEqualTo("A\\_b\\%c\\\\d%")
    }
}
