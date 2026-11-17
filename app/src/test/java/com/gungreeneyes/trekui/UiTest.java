package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** Pure rules in {@link Ui}: the responsive zoom and the bit-level colour helpers. */
public class UiTest {

    private static final float EPS = 1e-4f;

    private static float layoutZoom(float w, float h) {
        ScreenMetrics m = ScreenMetrics.compute(w, h, 2f);
        return Ui.layoutZoomFor(m.uiScale, Math.min(w, h));
    }

    private static float textZoom(float w, float h) {
        ScreenMetrics m = ScreenMetrics.compute(w, h, 2f);
        return Ui.textZoomFor(m.uiScale, Math.min(w, h));
    }

    @Test
    public void phonesKeepTheDesignedLayout() {
        assertThat(layoutZoom(320, 568)).isEqualTo(1f);
        assertThat(layoutZoom(412, 915)).isEqualTo(1f);
        assertThat(layoutZoom(411, 960)).isEqualTo(1f);
        assertThat(layoutZoom(344, 882)).isEqualTo(1f);
        assertThat(layoutZoom(915, 412)).isEqualTo(1f); // landscape phone: no growth, height is scarce
    }

    @Test
    public void largeScreensGrowByHalfTheScale() {
        // uiScale caps at 1.6 -> zoom 1.3
        assertThat(layoutZoom(673, 841)).isWithin(EPS).of(1.3f);
        assertThat(layoutZoom(1280, 800)).isWithin(EPS).of(1.3f);
        assertThat(layoutZoom(800, 1280)).isWithin(EPS).of(1.3f);
        assertThat(layoutZoom(1920, 1080)).isWithin(EPS).of(1.3f);
        // just at the threshold: 600/360 = 1.667 clamps to 1.6
        assertThat(Ui.layoutZoomFor(1.6f, 600f)).isWithin(EPS).of(1.3f);
        assertThat(Ui.layoutZoomFor(1.6f, 599.9f)).isEqualTo(1f);
    }

    @Test
    public void layoutZoomNeverShrinks() {
        assertThat(Ui.layoutZoomFor(0.5f, 700f)).isEqualTo(1f);
    }

    @Test
    public void textShrinksOnlyOnSmallPhones() {
        assertThat(textZoom(320, 568)).isWithin(EPS).of(320f / 360f);
        assertThat(textZoom(360, 640)).isWithin(EPS).of(1f);
        assertThat(textZoom(412, 915)).isWithin(EPS).of(1f);
        assertThat(textZoom(1280, 800)).isWithin(EPS).of(1.3f);
        assertThat(Ui.textZoomFor(0.5f, 200f)).isWithin(EPS).of(ScreenMetrics.MIN_SCALE);
    }

    @Test
    public void alpha_replacesOnlyAlpha() {
        assertThat(Ui.alpha(0xFF123456, 0f)).isEqualTo(0x00123456);
        assertThat(Ui.alpha(0x00123456, 1f)).isEqualTo(0xFF123456);
        assertThat(Ui.alpha(0xFF123456, 0.5f)).isEqualTo(0x80123456);
    }

    @Test
    public void darken_scalesChannelsAndIsOpaque() {
        assertThat(Ui.darken(0x80FF8040, 0.5f)).isEqualTo(0xFF804020);
        assertThat(Ui.darken(0xFFFFFFFF, 0f)).isEqualTo(0xFF000000);
        assertThat(Ui.darken(0xFF102030, 1f)).isEqualTo(0xFF102030);
    }
}
