package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [BootSequence.stageScale]: the design stage fits every window shape (pure JVM). */
class BootSequenceTest {

    @Test
    fun portraitPhoneIsUnscaled() {
        assertThat(BootSequence.stageScale(360 * D, 780 * D, D, 1f)).isEqualTo(1f)
    }

    @Test
    fun landscapePhoneShrinksToFitHeight() {
        val s = BootSequence.stageScale(780 * D, 360 * D, D, 1f)
        assertThat(s).isWithin(1e-4f).of(360f / BootSequence.MIN_STAGE_H_DP)
        assertThat(360 * D / s).isAtLeast(BootSequence.MIN_STAGE_H_DP * D - 0.5f)
    }

    @Test
    fun tabletGrowsByUiScaleButStillFits() {
        val s = BootSequence.stageScale(800 * D, 1280 * D, D, 1.6f)
        assertThat(s).isWithin(1e-4f).of(1.6f)
        assertThat(800 * D / s).isAtLeast(BootSequence.MIN_STAGE_W_DP * D)
    }

    @Test
    fun degenerateSizesAreSafe() {
        assertThat(BootSequence.stageScale(0f, 100f, D, 1f)).isEqualTo(1f)
        assertThat(BootSequence.stageScale(10f, 10f, D, 1f)).isAtLeast(0.3f)
    }

    private companion object {
        const val D = 3f // 480 dpi
    }
}
