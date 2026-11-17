package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** {@link Stations.Ops#redistribute}: OPS power always totals 100 and never starves a system (pure JVM). */
public class StationsTest {

    private static float sum(float[] a) {
        float s = 0;
        for (float v : a) s += v;
        return s;
    }

    @Test
    public void redistribute_movesPowerAndKeepsTotal() {
        float[] t = {22, 12, 18, 14, 20, 14};
        Stations.Ops.redistribute(t, 2);
        assertThat(t[2]).isWithin(1e-3f).of(28);
        assertThat(sum(t)).isWithin(1e-3f).of(100);
    }

    @Test
    public void redistribute_repeatedBoostsRespectTheFloor() {
        float[] t = {22, 12, 18, 14, 20, 14};
        for (int i = 0; i < 50; i++) Stations.Ops.redistribute(t, 0);
        for (int k = 1; k < t.length; k++) assertThat(t[k]).isAtLeast(4f);
        assertThat(sum(t)).isWithin(1e-3f).of(100);
    }
}
