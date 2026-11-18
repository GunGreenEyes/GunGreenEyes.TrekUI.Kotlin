package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [Stations.Ops.redistribute]: OPS power always totals 100 and never starves a system (pure JVM). */
class StationsTest {

    private fun sum(a: FloatArray): Float {
        var s = 0f
        for (v in a) s += v
        return s
    }

    @Test
    fun redistribute_movesPowerAndKeepsTotal() {
        val t = floatArrayOf(22f, 12f, 18f, 14f, 20f, 14f)
        Stations.Ops.redistribute(t, 2)
        assertThat(t[2]).isWithin(1e-3f).of(28f)
        assertThat(sum(t)).isWithin(1e-3f).of(100f)
    }

    @Test
    fun redistribute_repeatedBoostsRespectTheFloor() {
        val t = floatArrayOf(22f, 12f, 18f, 14f, 20f, 14f)
        repeat(50) { Stations.Ops.redistribute(t, 0) }
        for (k in 1 until t.size) assertThat(t[k]).isAtLeast(4f)
        assertThat(sum(t)).isWithin(1e-3f).of(100f)
    }
}
