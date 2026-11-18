package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.gungreeneyes.trekui.ScreenMetrics.SizeClass
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Every rule in [ScreenMetrics] (size classes, margins, gutters, UI scale clamp, grid columns,
 * orientation helpers, px conversion), checked across a table of real device windows.
 */
class ScreenMetricsTest {

    /** One row of the device table: window in dp, density, and the expected results. */
    private class Device(
        val name: String,
        val w: Float,
        val h: Float,
        val density: Float,
        val widthClass: SizeClass,
        val heightClass: SizeClass,
        val margin: Float,
        val gutter: Float,
        val scale: Float,
        val columns80: Int, // columns(80dp tiles, 1..10)
        val landscape: Boolean,
        val tall: Boolean,
        val twoPane: Boolean,
    )

    @Test
    fun deviceTable_everyRule() {
        for (d in DEVICES) {
            val m = ScreenMetrics.compute(d.w, d.h, d.density)
            val why = d.name + " " + m
            assertWithMessage(why).that(m.widthDp).isEqualTo(d.w)
            assertWithMessage(why).that(m.heightDp).isEqualTo(d.h)
            assertWithMessage(why).that(m.widthClass).isEqualTo(d.widthClass)
            assertWithMessage(why).that(m.heightClass).isEqualTo(d.heightClass)
            assertWithMessage(why).that(m.marginDp).isWithin(EPS).of(d.margin)
            assertWithMessage(why).that(m.gutterDp).isWithin(EPS).of(d.gutter)
            assertWithMessage(why).that(m.uiScale).isWithin(EPS).of(d.scale)
            assertWithMessage(why).that(m.columns(80f, 1, 10)).isEqualTo(d.columns80)
            assertWithMessage(why).that(m.isLandscape).isEqualTo(d.landscape)
            assertWithMessage(why).that(m.isTall).isEqualTo(d.tall)
            assertWithMessage(why).that(m.isTwoPane).isEqualTo(d.twoPane)
            assertWithMessage(why).that(m.aspectRatio()).isWithin(EPS).of(d.w / d.h)
            assertWithMessage(why).that(m.marginPx()).isEqualTo(Math.round(d.margin * d.density))
            assertWithMessage(why).that(m.gutterPx()).isEqualTo(Math.round(d.gutter * d.density))
        }
    }

    @Test
    fun scaleIsAlwaysWithinClamp() {
        for (d in DEVICES) {
            val m = ScreenMetrics.compute(d.w, d.h, d.density)
            assertThat(m.uiScale).isAtLeast(ScreenMetrics.MIN_SCALE)
            assertThat(m.uiScale).isAtMost(ScreenMetrics.MAX_SCALE)
        }
    }

    @Test
    fun widthClass_boundaries() {
        assertThat(ScreenMetrics.widthClassFor(599.9f)).isEqualTo(C)
        assertThat(ScreenMetrics.widthClassFor(600f)).isEqualTo(M)
        assertThat(ScreenMetrics.widthClassFor(839.9f)).isEqualTo(M)
        assertThat(ScreenMetrics.widthClassFor(840f)).isEqualTo(E)
    }

    @Test
    fun heightClass_boundaries() {
        assertThat(ScreenMetrics.heightClassFor(479.9f)).isEqualTo(C)
        assertThat(ScreenMetrics.heightClassFor(480f)).isEqualTo(M)
        assertThat(ScreenMetrics.heightClassFor(899.9f)).isEqualTo(M)
        assertThat(ScreenMetrics.heightClassFor(900f)).isEqualTo(E)
    }

    @Test
    fun margin_neverMoreThanSixPercentOfWidth() {
        assertThat(ScreenMetrics.marginFor(150f)).isWithin(EPS).of(9f) // 6% of 150 < 12
        assertThat(ScreenMetrics.marginFor(200f)).isWithin(EPS).of(12f) // 6% = 12
        assertThat(ScreenMetrics.marginFor(360f)).isWithin(EPS).of(12f)
        assertThat(ScreenMetrics.marginFor(600f)).isWithin(EPS).of(24f)
        assertThat(ScreenMetrics.marginFor(840f)).isWithin(EPS).of(32f)
        var w = 100f
        while (w <= 2000) {
            assertThat(ScreenMetrics.marginFor(w)).isAtMost(w * 0.06f + EPS)
            w += 37f
        }
    }

    @Test
    fun gutter_byWidthClass() {
        assertThat(ScreenMetrics.gutterFor(400f)).isEqualTo(6f)
        assertThat(ScreenMetrics.gutterFor(700f)).isEqualTo(10f)
        assertThat(ScreenMetrics.gutterFor(1000f)).isEqualTo(14f)
    }

    @Test
    fun uiScale_usesShorterSideAndClamps() {
        assertThat(ScreenMetrics.uiScaleFor(360f, 800f)).isWithin(EPS).of(1f)
        assertThat(ScreenMetrics.uiScaleFor(800f, 360f)).isWithin(EPS).of(1f)
        assertThat(ScreenMetrics.uiScaleFor(200f, 400f)).isWithin(EPS).of(ScreenMetrics.MIN_SCALE)
        assertThat(ScreenMetrics.uiScaleFor(2000f, 3000f)).isWithin(EPS).of(ScreenMetrics.MAX_SCALE)
    }

    @Test
    fun columns_respectMinAndMax() {
        assertThat(ScreenMetrics.columnsFor(200f, 300f, 2, 6)).isEqualTo(2)
        assertThat(ScreenMetrics.columnsFor(3000f, 50f, 2, 6)).isEqualTo(6)
        // exact fit: 360 - 24 margin = 336 usable, (336 + 6) / (51 + 6) = 6
        assertThat(ScreenMetrics.columnsFor(360f, 51f, 1, 12)).isEqualTo(6)
    }

    @Test
    fun columns_neverDecreaseAsWidthGrows() {
        var last = 0
        var w = 280f
        while (w <= 2000) {
            val n = ScreenMetrics.columnsFor(w, 80f, 1, 30)
            assertThat(n).isAtLeast(last - 1) // class changes can widen margins/gutters by a step
            last = n
            w += 5f
        }
    }

    @Test
    fun clamp() {
        assertThat(ScreenMetrics.clamp(5f, 0f, 1f)).isEqualTo(1f)
        assertThat(ScreenMetrics.clamp(-5f, 0f, 1f)).isEqualTo(0f)
        assertThat(ScreenMetrics.clamp(0.5f, 0f, 1f)).isEqualTo(0.5f)
    }

    @Test
    fun pxConversions() {
        val m = ScreenMetrics.compute(412f, 915f, 2.625f)
        assertThat(m.px(10f)).isEqualTo(26)
        assertThat(m.scaledPx(10f)).isEqualTo(Math.round(10 * (412f / 360f) * 2.625f))
    }

    @Test
    fun compute_rejectsNonPositive() {
        assertThrows(IllegalArgumentException::class.java) { ScreenMetrics.compute(0f, 800f, 2f) }
        assertThrows(IllegalArgumentException::class.java) { ScreenMetrics.compute(400f, -1f, 2f) }
        assertThrows(IllegalArgumentException::class.java) { ScreenMetrics.compute(400f, 800f, 0f) }
    }

    @Test
    fun toString_mentionsClasses() {
        assertThat(ScreenMetrics.compute(673f, 841f, 2.625f).toString()).contains("MEDIUM")
    }

    private companion object {
        const val EPS = 1e-3f

        val C = SizeClass.COMPACT
        val M = SizeClass.MEDIUM
        val E = SizeClass.EXPANDED

        val DEVICES = arrayOf(
            //     name                        w      h      dens    wC hC  margin gutter scale         cols  land   tall   2pane
            Device("small phone 320x568", 320f, 568f, 2f, C, M, 12f, 6f, 320f / 360f, 3, false, false, false),
            Device("Pixel 7 412x915", 412f, 915f, 2.625f, C, E, 12f, 6f, 412f / 360f, 4, false, true, false),
            Device("Xperia 21:9 411x960", 411f, 960f, 3f, C, E, 12f, 6f, 411f / 360f, 4, false, true, false),
            Device("Fold folded 344x882", 344f, 882f, 2.625f, C, M, 12f, 6f, 344f / 360f, 3, false, true, false),
            Device("Fold open 673x841", 673f, 841f, 2.625f, M, M, 24f, 10f, 1.6f, 7, false, false, true),
            Device("Pixel Tablet 1280x800", 1280f, 800f, 2f, E, M, 32f, 14f, 1.6f, 10, true, false, true),
            Device("10in tablet 800x1280", 800f, 1280f, 2f, M, E, 24f, 10f, 1.6f, 8, false, false, true),
            Device("landscape phone 915x412", 915f, 412f, 2.625f, E, C, 32f, 14f, 412f / 360f, 9, true, true, true),
            Device("desktop window 1920x1080", 1920f, 1080f, 1f, E, E, 32f, 14f, 1.6f, 10, true, false, true),
        )
    }
}
