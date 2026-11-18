package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.min

/** Pure rules in [Ui]: the responsive zoom and the bit-level colour helpers. */
class UiTest {

    @Test
    fun phonesKeepTheDesignedLayout() {
        assertThat(layoutZoom(320f, 568f)).isEqualTo(1f)
        assertThat(layoutZoom(412f, 915f)).isEqualTo(1f)
        assertThat(layoutZoom(411f, 960f)).isEqualTo(1f)
        assertThat(layoutZoom(344f, 882f)).isEqualTo(1f)
        assertThat(layoutZoom(915f, 412f)).isEqualTo(1f) // landscape phone: no growth, height is scarce
    }

    @Test
    fun largeScreensGrowByHalfTheScale() {
        // uiScale caps at 1.6 -> zoom 1.3
        assertThat(layoutZoom(673f, 841f)).isWithin(EPS).of(1.3f)
        assertThat(layoutZoom(1280f, 800f)).isWithin(EPS).of(1.3f)
        assertThat(layoutZoom(800f, 1280f)).isWithin(EPS).of(1.3f)
        assertThat(layoutZoom(1920f, 1080f)).isWithin(EPS).of(1.3f)
        // just at the threshold: 600/360 = 1.667 clamps to 1.6
        assertThat(Ui.layoutZoomFor(1.6f, 600f)).isWithin(EPS).of(1.3f)
        assertThat(Ui.layoutZoomFor(1.6f, 599.9f)).isEqualTo(1f)
    }

    @Test
    fun layoutZoomNeverShrinks() {
        assertThat(Ui.layoutZoomFor(0.5f, 700f)).isEqualTo(1f)
    }

    @Test
    fun textShrinksOnlyOnSmallPhones() {
        assertThat(textZoom(320f, 568f)).isWithin(EPS).of(320f / 360f)
        assertThat(textZoom(360f, 640f)).isWithin(EPS).of(1f)
        assertThat(textZoom(412f, 915f)).isWithin(EPS).of(1f)
        assertThat(textZoom(1280f, 800f)).isWithin(EPS).of(1.3f)
        assertThat(Ui.textZoomFor(0.5f, 200f)).isWithin(EPS).of(ScreenMetrics.MIN_SCALE)
    }

    @Test
    fun alpha_replacesOnlyAlpha() {
        assertThat(Ui.alpha(0xFF123456.toInt(), 0f)).isEqualTo(0x00123456)
        assertThat(Ui.alpha(0x00123456, 1f)).isEqualTo(0xFF123456.toInt())
        assertThat(Ui.alpha(0xFF123456.toInt(), 0.5f)).isEqualTo(0x80123456.toInt())
    }

    @Test
    fun darken_scalesChannelsAndIsOpaque() {
        assertThat(Ui.darken(0x80FF8040.toInt(), 0.5f)).isEqualTo(0xFF804020.toInt())
        assertThat(Ui.darken(0xFFFFFFFF.toInt(), 0f)).isEqualTo(0xFF000000.toInt())
        assertThat(Ui.darken(0xFF102030.toInt(), 1f)).isEqualTo(0xFF102030.toInt())
    }

    private companion object {
        const val EPS = 1e-4f

        fun layoutZoom(w: Float, h: Float): Float {
            val m = ScreenMetrics.compute(w, h, 2f)
            return Ui.layoutZoomFor(m.uiScale, min(w, h))
        }

        fun textZoom(w: Float, h: Float): Float {
            val m = ScreenMetrics.compute(w, h, 2f)
            return Ui.textZoomFor(m.uiScale, min(w, h))
        }
    }
}
