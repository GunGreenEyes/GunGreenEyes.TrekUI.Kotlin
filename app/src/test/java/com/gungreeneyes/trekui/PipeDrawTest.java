package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** JVM tests for PipeDraw's pure colour maths and centre-crop geometry. */
public class PipeDrawTest {

    @Test
    public void channels_unpackArgb() {
        int c = 0x80123456;
        assertThat(PipeDraw.alphaOf(c)).isEqualTo(0x80);
        assertThat(PipeDraw.redOf(c)).isEqualTo(0x12);
        assertThat(PipeDraw.greenOf(c)).isEqualTo(0x34);
        assertThat(PipeDraw.blueOf(c)).isEqualTo(0x56);
        assertThat(PipeDraw.alphaOf(0xFFFFFFFF)).isEqualTo(255);
    }

    @Test
    public void argb_packsAndClamps() {
        assertThat(PipeDraw.argb(0xFF, 0x12, 0x34, 0x56)).isEqualTo(0xFF123456);
        assertThat(PipeDraw.argb(300, -5, 256, 0)).isEqualTo(0xFF00FF00);
    }

    @Test
    public void mix_endpointsAndMidpoint() {
        int a = 0xFF000000, b = 0xFFFFFFFF;
        assertThat(PipeDraw.mix(a, b, 0f)).isEqualTo(a);
        assertThat(PipeDraw.mix(a, b, 1f)).isEqualTo(b);
        assertThat(PipeDraw.mix(a, b, 0.5f)).isEqualTo(0xFF808080);
        assertThat(PipeDraw.mix(0x00FF0000, 0xFF0000FF, 0.5f)).isEqualTo(0x80800080);
    }

    @Test
    public void a_multipliesExistingAlphaAndClamps() {
        assertThat(PipeDraw.a(0xFF112233, 0.5f)).isEqualTo(0x80112233);
        assertThat(PipeDraw.a(0x80112233, 0.5f)).isEqualTo(0x40112233);
        assertThat(PipeDraw.a(0xFF112233, 2f)).isEqualTo(0xFF112233);
        assertThat(PipeDraw.a(0xFF112233, -1f)).isEqualTo(0x00112233);
    }

    @Test
    public void lum_andDim() {
        assertThat(PipeDraw.lum(0xFFFFFFFF)).isWithin(1e-4f).of(1f);
        assertThat(PipeDraw.lum(0xFF000000)).isWithin(1e-4f).of(0f);
        assertThat(PipeDraw.lum(0xFF00FF00)).isWithin(1e-4f).of(0.7152f);
        assertThat(PipeDraw.dim(PipeDraw.LACQ)).isTrue();
        assertThat(PipeDraw.dim(PipeDraw.OXBLOOD)).isTrue();
        assertThat(PipeDraw.dim(PipeDraw.BRASS_HI)).isFalse();
        assertThat(PipeDraw.dim(PipeDraw.GOLD)).isFalse();
    }

    @Test
    public void liftedFor_movesTowardsTheFamilyLightColour() {
        int lounge = PipeDraw.liftedFor(PipeDraw.LACQ, true);
        int other = PipeDraw.liftedFor(PipeDraw.LACQ, false);
        assertThat(PipeDraw.alphaOf(lounge)).isEqualTo(255);
        assertThat(lounge).isEqualTo(PipeDraw.mix(PipeDraw.LACQ, PipeDraw.GOLD_PALE, PipeDraw.LIFT_AMOUNT));
        assertThat(other).isEqualTo(PipeDraw.mix(PipeDraw.LACQ, PipeDraw.PARCHMENT_LIGHT, PipeDraw.LIFT_AMOUNT));
        // lifted lettering is no longer dim
        assertThat(PipeDraw.dim(lounge)).isFalse();
        assertThat(PipeDraw.dim(other)).isFalse();
        // translucent input is made opaque first
        assertThat(PipeDraw.alphaOf(PipeDraw.liftedFor(0x20000000, false))).isEqualTo(255);
    }

    @Test
    public void centerCropSource_landscapeTargetTrimsTopAndBottom() {
        // 800x1500 portrait backdrop on a 2400x1080 landscape phone
        float[] r = PipeDraw.centerCropSource(800, 1500, 2400, 1080);
        assertThat(r[0]).isWithin(1e-3f).of(0f);
        assertThat(r[2]).isWithin(1e-3f).of(800f);
        float visibleH = r[3] - r[1];
        assertThat(visibleH).isWithin(1e-2f).of(800f * 1080f / 2400f);
        assertThat(r[1] + visibleH / 2f).isWithin(1e-2f).of(750f);
    }

    @Test
    public void centerCropSource_tallTargetTrimsSides() {
        float[] r = PipeDraw.centerCropSource(1000, 1000, 400, 1000);
        assertThat(r[1]).isWithin(1e-3f).of(0f);
        assertThat(r[3]).isWithin(1e-3f).of(1000f);
        assertThat(r[0]).isWithin(1e-3f).of(300f);
        assertThat(r[2]).isWithin(1e-3f).of(700f);
    }

    @Test
    public void centerCropSource_sameAspectKeepsWholeImage() {
        float[] r = PipeDraw.centerCropSource(800, 1500, 1600, 3000);
        assertThat(r).usingTolerance(1e-3).containsExactly(0f, 0f, 800f, 1500f).inOrder();
    }
}
