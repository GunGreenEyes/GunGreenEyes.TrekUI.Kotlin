package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import java.util.Random;

import org.junit.Test;

/** JVM tests for PipeHome's layout rule and LORE picker. */
public class PipeHomeTest {

    @Test
    public void sideBySide_onlyForWideLandscapeWindows() {
        // portrait phones, tablets and the open foldable stay stacked
        assertThat(PipeHome.sideBySide(320, 568)).isFalse();
        assertThat(PipeHome.sideBySide(412, 915)).isFalse();
        assertThat(PipeHome.sideBySide(411, 960)).isFalse();
        assertThat(PipeHome.sideBySide(344, 882)).isFalse();
        assertThat(PipeHome.sideBySide(673, 841)).isFalse();
        assertThat(PipeHome.sideBySide(800, 1280)).isFalse();
        // landscape phone, landscape tablet, desktop window
        assertThat(PipeHome.sideBySide(915, 412)).isTrue();
        assertThat(PipeHome.sideBySide(1280, 800)).isTrue();
        assertThat(PipeHome.sideBySide(1920, 1080)).isTrue();
        // a small landscape split-screen window is too narrow for two panes
        assertThat(PipeHome.sideBySide(568, 320)).isFalse();
    }

    @Test
    public void nextLore_neverRepeatsThePreviousPage() {
        Random random = new Random(42);
        int previous = -1;
        for (int n = 0; n < 500; n++) {
            int i = PipeHome.nextLore(random, PipeHome.LORE.length, previous);
            assertThat(i).isAtLeast(0);
            assertThat(i).isLessThan(PipeHome.LORE.length);
            assertThat(i).isNotEqualTo(previous);
            previous = i;
        }
    }

    @Test
    public void nextLore_singlePageMayRepeat() {
        assertThat(PipeHome.nextLore(new Random(1), 1, 0)).isEqualTo(0);
    }
}
