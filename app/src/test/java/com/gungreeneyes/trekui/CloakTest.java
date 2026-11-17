package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** {@link Cloak} brightness levels (pure JVM). */
public class CloakTest {

    @Test
    public void brightnessFor() {
        assertThat(Cloak.brightnessFor(0)).isEqualTo(1);
        assertThat(Cloak.brightnessFor(6)).isEqualTo(15);
        assertThat(Cloak.brightnessFor(100)).isEqualTo(255);
        assertThat(Cloak.brightnessFor(250)).isEqualTo(255);
    }

    @Test
    public void offeredLevelsAreDimAndOrdered() {
        for (int i = 1; i < Cloak.LEVELS.length; i++) assertThat(Cloak.LEVELS[i]).isGreaterThan(Cloak.LEVELS[i - 1]);
        for (int m : Cloak.MINUTES) assertThat(m).isGreaterThan(0);
    }
}
