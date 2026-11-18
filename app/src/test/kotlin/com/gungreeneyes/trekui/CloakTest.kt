package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [Cloak] brightness levels (pure JVM). */
class CloakTest {

    @Test
    fun brightnessFor() {
        assertThat(Cloak.brightnessFor(0)).isEqualTo(1)
        assertThat(Cloak.brightnessFor(6)).isEqualTo(15)
        assertThat(Cloak.brightnessFor(100)).isEqualTo(255)
        assertThat(Cloak.brightnessFor(250)).isEqualTo(255)
    }

    @Test
    fun offeredLevelsAreDimAndOrdered() {
        for (i in 1 until Cloak.LEVELS.size) assertThat(Cloak.LEVELS[i]).isGreaterThan(Cloak.LEVELS[i - 1])
        for (m in Cloak.MINUTES) assertThat(m).isGreaterThan(0)
    }
}
