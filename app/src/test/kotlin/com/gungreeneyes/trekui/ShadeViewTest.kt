package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/** [ShadeView] brightness mapping and notification ages (pure JVM). */
class ShadeViewTest {

    @Test
    fun brightnessMapping_endsAndRoundTrip() {
        assertThat(ShadeView.sliderForBrightness(0)).isEqualTo(0)
        assertThat(ShadeView.sliderForBrightness(255)).isEqualTo(1000)
        assertThat(ShadeView.sliderForBrightness(999)).isEqualTo(1000)
        assertThat(ShadeView.brightnessForSlider(0)).isEqualTo(1)
        assertThat(ShadeView.brightnessForSlider(1000)).isEqualTo(255)
        var b = 20
        while (b <= 255) {
            val back = ShadeView.brightnessForSlider(ShadeView.sliderForBrightness(b))
            assertThat(abs(back - b)).isAtMost(1)
            b += 25
        }
    }

    @Test
    fun brightnessMapping_givesLowLevelsMoreTravel() {
        assertThat(ShadeView.sliderForBrightness(64)).isGreaterThan(500)
    }

    @Test
    fun ago() {
        val now = 10L * 24 * 3600 * 1000
        assertThat(ShadeView.ago(now, now - 30_000, Locale.US)).isEqualTo("NOW")
        assertThat(ShadeView.ago(now, now - 5 * 60_000, Locale.US)).isEqualTo("5M")
        assertThat(ShadeView.ago(now, now - 3 * 3600_000, Locale.US)).isEqualTo("3H")
        assertThat(ShadeView.ago(now, now - 3L * 24 * 3600_000, Locale.US)).isNotEmpty()
    }
}
