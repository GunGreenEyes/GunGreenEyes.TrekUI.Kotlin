package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** TUK-T14: the pure parts of the API 23-25 fallbacks in [Compat]. */
class CompatTest {

    @Test
    fun contentLength_readsTheHeaderOrMinusOne() {
        assertThat(Compat.contentLength("1234")).isEqualTo(1234L)
        assertThat(Compat.contentLength(" 5000000000 ")).isEqualTo(5_000_000_000L) // over 2 GB
        assertThat(Compat.contentLength("0")).isEqualTo(0L)
        assertThat(Compat.contentLength(null)).isEqualTo(-1L)
        assertThat(Compat.contentLength("")).isEqualTo(-1L)
        assertThat(Compat.contentLength("-5")).isEqualTo(-1L)
        assertThat(Compat.contentLength("12kb")).isEqualTo(-1L)
    }

    @Test
    fun animators_areOffOnlyAtZeroScale() {
        assertThat(Compat.animatorsEnabled(1f)).isTrue()
        assertThat(Compat.animatorsEnabled(0.5f)).isTrue()
        assertThat(Compat.animatorsEnabled(0f)).isFalse()
    }
}
