package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** JVM tests for PipeBoot's timeline easing and responsive composition column. */
public class PipeBootTest {

    @Test
    public void clamp01_bounds() {
        assertThat(PipeBoot.clamp01(-0.5f)).isEqualTo(0f);
        assertThat(PipeBoot.clamp01(0.25f)).isEqualTo(0.25f);
        assertThat(PipeBoot.clamp01(7f)).isEqualTo(1f);
    }

    @Test
    public void ease_isZeroBeforeOneAfterAndEasesOut() {
        assertThat(PipeBoot.ease(0, 100, 200)).isEqualTo(0f);
        assertThat(PipeBoot.ease(100, 100, 200)).isEqualTo(0f);
        assertThat(PipeBoot.ease(200, 100, 200)).isEqualTo(1f);
        assertThat(PipeBoot.ease(5000, 100, 200)).isEqualTo(1f);
        // quadratic ease-out: half-way in time is three quarters of the way
        assertThat(PipeBoot.ease(150, 100, 200)).isWithin(1e-6f).of(0.75f);
    }

    @Test
    public void ease_isMonotonic() {
        float last = -1f;
        for (long t = 0; t <= 1000; t += 25) {
            float v = PipeBoot.ease(t, 200, 800);
            assertThat(v).isAtLeast(last);
            last = v;
        }
    }

    @Test
    public void stageWidth_portraitUsesFullWidth() {
        assertThat(PipeBoot.stageWidth(412, 915)).isEqualTo(412f);
        assertThat(PipeBoot.stageWidth(800, 1280)).isEqualTo(800f);
    }

    @Test
    public void stageWidth_wideScreensGetACentredPortraitColumn() {
        // landscape phone, tablet landscape, desktop window, near-square foldable
        assertThat(PipeBoot.stageWidth(915, 412)).isWithin(1e-3f).of(412 * 0.8f);
        assertThat(PipeBoot.stageWidth(1280, 800)).isWithin(1e-3f).of(640f);
        assertThat(PipeBoot.stageWidth(1920, 1080)).isWithin(1e-3f).of(864f);
        assertThat(PipeBoot.stageWidth(673, 841)).isWithin(1e-3f).of(672.8f);
    }
}
