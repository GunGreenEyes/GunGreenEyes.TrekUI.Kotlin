package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import com.gungreeneyes.trekui.ScreenMetrics.SizeClass;

import org.junit.Test;

/**
 * Every rule in {@link ScreenMetrics} (size classes, margins, gutters, UI scale clamp, grid columns,
 * orientation helpers, px conversion), checked across a table of real device windows.
 */
public class ScreenMetricsTest {

    private static final float EPS = 1e-3f;

    /** One row of the device table: window in dp, density, and the expected results. */
    private static final class Device {
        final String name;
        final float w, h, density;
        final SizeClass widthClass, heightClass;
        final float margin, gutter, scale;
        final int columns80; // columns(80dp tiles, 1..10)
        final boolean landscape, tall, twoPane;

        Device(String name, float w, float h, float density, SizeClass wc, SizeClass hc, float margin, float gutter,
               float scale, int columns80, boolean landscape, boolean tall, boolean twoPane) {
            this.name = name;
            this.w = w;
            this.h = h;
            this.density = density;
            this.widthClass = wc;
            this.heightClass = hc;
            this.margin = margin;
            this.gutter = gutter;
            this.scale = scale;
            this.columns80 = columns80;
            this.landscape = landscape;
            this.tall = tall;
            this.twoPane = twoPane;
        }
    }

    private static final SizeClass C = SizeClass.COMPACT, M = SizeClass.MEDIUM, E = SizeClass.EXPANDED;

    private static final Device[] DEVICES = {
            //          name                    w     h     dens   wC hC  margin gutter scale        cols  land   tall   2pane
            new Device("small phone 320x568", 320, 568, 2f, C, M, 12, 6, 320f / 360f, 3, false, false, false),
            new Device("Pixel 7 412x915", 412, 915, 2.625f, C, E, 12, 6, 412f / 360f, 4, false, true, false),
            new Device("Xperia 21:9 411x960", 411, 960, 3f, C, E, 12, 6, 411f / 360f, 4, false, true, false),
            new Device("Fold folded 344x882", 344, 882, 2.625f, C, M, 12, 6, 344f / 360f, 3, false, true, false),
            new Device("Fold open 673x841", 673, 841, 2.625f, M, M, 24, 10, 1.6f, 7, false, false, true),
            new Device("Pixel Tablet 1280x800", 1280, 800, 2f, E, M, 32, 14, 1.6f, 10, true, false, true),
            new Device("10in tablet 800x1280", 800, 1280, 2f, M, E, 24, 10, 1.6f, 8, false, false, true),
            new Device("landscape phone 915x412", 915, 412, 2.625f, E, C, 32, 14, 412f / 360f, 9, true, true, true),
            new Device("desktop window 1920x1080", 1920, 1080, 1f, E, E, 32, 14, 1.6f, 10, true, false, true),
    };

    @Test
    public void deviceTable_everyRule() {
        for (Device d : DEVICES) {
            ScreenMetrics m = ScreenMetrics.compute(d.w, d.h, d.density);
            String why = d.name + " " + m;
            assertWithMessage(why).that(m.widthDp).isEqualTo(d.w);
            assertWithMessage(why).that(m.heightDp).isEqualTo(d.h);
            assertWithMessage(why).that(m.widthClass).isEqualTo(d.widthClass);
            assertWithMessage(why).that(m.heightClass).isEqualTo(d.heightClass);
            assertWithMessage(why).that(m.marginDp).isWithin(EPS).of(d.margin);
            assertWithMessage(why).that(m.gutterDp).isWithin(EPS).of(d.gutter);
            assertWithMessage(why).that(m.uiScale).isWithin(EPS).of(d.scale);
            assertWithMessage(why).that(m.columns(80, 1, 10)).isEqualTo(d.columns80);
            assertWithMessage(why).that(m.isLandscape()).isEqualTo(d.landscape);
            assertWithMessage(why).that(m.isTall()).isEqualTo(d.tall);
            assertWithMessage(why).that(m.isTwoPane()).isEqualTo(d.twoPane);
            assertWithMessage(why).that(m.aspectRatio()).isWithin(EPS).of(d.w / d.h);
            assertWithMessage(why).that(m.marginPx()).isEqualTo(Math.round(d.margin * d.density));
            assertWithMessage(why).that(m.gutterPx()).isEqualTo(Math.round(d.gutter * d.density));
        }
    }

    @Test
    public void scaleIsAlwaysWithinClamp() {
        for (Device d : DEVICES) {
            ScreenMetrics m = ScreenMetrics.compute(d.w, d.h, d.density);
            assertThat(m.uiScale).isAtLeast(ScreenMetrics.MIN_SCALE);
            assertThat(m.uiScale).isAtMost(ScreenMetrics.MAX_SCALE);
        }
    }

    @Test
    public void widthClass_boundaries() {
        assertThat(ScreenMetrics.widthClassFor(599.9f)).isEqualTo(C);
        assertThat(ScreenMetrics.widthClassFor(600f)).isEqualTo(M);
        assertThat(ScreenMetrics.widthClassFor(839.9f)).isEqualTo(M);
        assertThat(ScreenMetrics.widthClassFor(840f)).isEqualTo(E);
    }

    @Test
    public void heightClass_boundaries() {
        assertThat(ScreenMetrics.heightClassFor(479.9f)).isEqualTo(C);
        assertThat(ScreenMetrics.heightClassFor(480f)).isEqualTo(M);
        assertThat(ScreenMetrics.heightClassFor(899.9f)).isEqualTo(M);
        assertThat(ScreenMetrics.heightClassFor(900f)).isEqualTo(E);
    }

    @Test
    public void margin_neverMoreThanSixPercentOfWidth() {
        assertThat(ScreenMetrics.marginFor(150f)).isWithin(EPS).of(9f);       // 6% of 150 < 12
        assertThat(ScreenMetrics.marginFor(200f)).isWithin(EPS).of(12f);      // 6% = 12
        assertThat(ScreenMetrics.marginFor(360f)).isWithin(EPS).of(12f);
        assertThat(ScreenMetrics.marginFor(600f)).isWithin(EPS).of(24f);
        assertThat(ScreenMetrics.marginFor(840f)).isWithin(EPS).of(32f);
        for (float w = 100; w <= 2000; w += 37) {
            assertThat(ScreenMetrics.marginFor(w)).isAtMost(w * 0.06f + EPS);
        }
    }

    @Test
    public void gutter_byWidthClass() {
        assertThat(ScreenMetrics.gutterFor(400f)).isEqualTo(6f);
        assertThat(ScreenMetrics.gutterFor(700f)).isEqualTo(10f);
        assertThat(ScreenMetrics.gutterFor(1000f)).isEqualTo(14f);
    }

    @Test
    public void uiScale_usesShorterSideAndClamps() {
        assertThat(ScreenMetrics.uiScaleFor(360f, 800f)).isWithin(EPS).of(1f);
        assertThat(ScreenMetrics.uiScaleFor(800f, 360f)).isWithin(EPS).of(1f);
        assertThat(ScreenMetrics.uiScaleFor(200f, 400f)).isWithin(EPS).of(ScreenMetrics.MIN_SCALE);
        assertThat(ScreenMetrics.uiScaleFor(2000f, 3000f)).isWithin(EPS).of(ScreenMetrics.MAX_SCALE);
    }

    @Test
    public void columns_respectMinAndMax() {
        assertThat(ScreenMetrics.columnsFor(200f, 300f, 2, 6)).isEqualTo(2);
        assertThat(ScreenMetrics.columnsFor(3000f, 50f, 2, 6)).isEqualTo(6);
        // exact fit: 360 - 24 margin = 336 usable, (336 + 6) / (51 + 6) = 6
        assertThat(ScreenMetrics.columnsFor(360f, 51f, 1, 12)).isEqualTo(6);
    }

    @Test
    public void columns_neverDecreaseAsWidthGrows() {
        int last = 0;
        for (float w = 280; w <= 2000; w += 5) {
            int n = ScreenMetrics.columnsFor(w, 80f, 1, 30);
            assertThat(n).isAtLeast(last - 1); // class changes can widen margins/gutters by a step
            last = n;
        }
    }

    @Test
    public void clamp() {
        assertThat(ScreenMetrics.clamp(5f, 0f, 1f)).isEqualTo(1f);
        assertThat(ScreenMetrics.clamp(-5f, 0f, 1f)).isEqualTo(0f);
        assertThat(ScreenMetrics.clamp(0.5f, 0f, 1f)).isEqualTo(0.5f);
    }

    @Test
    public void pxConversions() {
        ScreenMetrics m = ScreenMetrics.compute(412, 915, 2.625f);
        assertThat(m.px(10)).isEqualTo(26);
        assertThat(m.scaledPx(10)).isEqualTo(Math.round(10 * (412f / 360f) * 2.625f));
    }

    @Test
    public void compute_rejectsNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> ScreenMetrics.compute(0, 800, 2));
        assertThrows(IllegalArgumentException.class, () -> ScreenMetrics.compute(400, -1, 2));
        assertThrows(IllegalArgumentException.class, () -> ScreenMetrics.compute(400, 800, 0));
    }

    @Test
    public void toString_mentionsClasses() {
        assertThat(ScreenMetrics.compute(673, 841, 2.625f).toString()).contains("MEDIUM");
    }
}
