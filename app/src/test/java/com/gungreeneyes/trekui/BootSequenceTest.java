package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** {@link BootSequence#stageScale}: the design stage fits every window shape (pure JVM). */
public class BootSequenceTest {

    private static final float D = 3f; // 480 dpi

    @Test
    public void portraitPhoneIsUnscaled() {
        assertThat(BootSequence.stageScale(360 * D, 780 * D, D, 1f)).isEqualTo(1f);
    }

    @Test
    public void landscapePhoneShrinksToFitHeight() {
        float s = BootSequence.stageScale(780 * D, 360 * D, D, 1f);
        assertThat(s).isWithin(1e-4f).of(360f / BootSequence.MIN_STAGE_H_DP);
        assertThat(360 * D / s).isAtLeast(BootSequence.MIN_STAGE_H_DP * D - 0.5f);
    }

    @Test
    public void tabletGrowsByUiScaleButStillFits() {
        float s = BootSequence.stageScale(800 * D, 1280 * D, D, 1.6f);
        assertThat(s).isWithin(1e-4f).of(1.6f);
        assertThat(800 * D / s).isAtLeast(BootSequence.MIN_STAGE_W_DP * D);
    }

    @Test
    public void degenerateSizesAreSafe() {
        assertThat(BootSequence.stageScale(0, 100, D, 1f)).isEqualTo(1f);
        assertThat(BootSequence.stageScale(10, 10, D, 1f)).isAtLeast(0.3f);
    }
}
