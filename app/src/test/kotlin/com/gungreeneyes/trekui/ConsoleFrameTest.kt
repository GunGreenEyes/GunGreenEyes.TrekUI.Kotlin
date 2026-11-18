package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure geometry helpers in [ConsoleFrame]. */
class ConsoleFrameTest {

    @Test
    fun elbowSpan_isBarPlusInnerRadiusPlusExtension() {
        assertThat(ConsoleFrame.elbowSpanFor(18, 18, 22)).isEqualTo(58)
    }

    @Test
    fun elbowSpan_zeroWithoutBar() {
        assertThat(ConsoleFrame.elbowSpanFor(0, 18, 22)).isEqualTo(0)
    }

    @Test
    fun sweptBarEnd_animatesFromMinToFull() {
        assertThat(ConsoleFrame.sweptBarEnd(100f, 500f, 0f)).isEqualTo(100f)
        assertThat(ConsoleFrame.sweptBarEnd(100f, 500f, 0.5f)).isEqualTo(300f)
        assertThat(ConsoleFrame.sweptBarEnd(100f, 500f, 1f)).isEqualTo(500f)
    }

    @Test
    fun sweptBarEnd_neverShorterThanMin() {
        // blocks can push the bar end left of the elbow on narrow screens
        assertThat(ConsoleFrame.sweptBarEnd(100f, 40f, 1f)).isEqualTo(100f)
    }

    @Test
    fun pressedTint_brightensAndClamps() {
        assertThat(ConsoleFrame.pressedTint(0xFF102030.toInt())).isEqualTo(0xFF566676.toInt())
        assertThat(ConsoleFrame.pressedTint(0xFFF0F0F0.toInt())).isEqualTo(0xFFFFFFFF.toInt())
    }

    @Test
    fun pressedTint_isOpaque() {
        assertThat(ConsoleFrame.pressedTint(0x00000000) ushr 24).isEqualTo(0xFF)
    }
}
