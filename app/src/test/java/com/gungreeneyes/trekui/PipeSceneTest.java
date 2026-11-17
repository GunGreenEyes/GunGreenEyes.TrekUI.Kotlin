package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.graphics.RectF;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

/**
 * Scene placement in {@link PipeScene}: the painting and its tap targets go through one transform,
 * every target stays on screen, and cropping stays within limits, for every scene on every shape of
 * screen (Robolectric for real {@link RectF} maths).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PipeSceneTest {

    private static final float EPS = 0.51f; // half a pixel

    /** The outermost tap targets of each scene (painting coordinates), taken from the scene builders. */
    private static final List<List<RectF>> TARGETS = Arrays.asList(
            Arrays.asList(new RectF(240, 80, 710, 215), new RectF(85, 1218, 457, 1362), new RectF(485, 1218, 857, 1362),
                    new RectF(668, 816, 866, 864), new RectF(380, 1550, 560, 1610), new RectF(757, 1447, 853, 1543)),
            Arrays.asList(new RectF(75, 320, 222, 380), new RectF(752, 95, 880, 235), new RectF(515, 1205, 895, 1400),
                    new RectF(80, 1402, 252, 1460), new RectF(380, 1550, 560, 1610)),
            Arrays.asList(new RectF(330, 110, 612, 300), new RectF(122, 166, 245, 314), new RectF(700, 160, 828, 305),
                    new RectF(150, 1127, 250, 1213), new RectF(698, 1127, 798, 1213), new RectF(158, 1493, 252, 1587),
                    new RectF(688, 1493, 782, 1587)));

    /** View sizes in px: phones 16:9..21:9, foldables, tablets both ways, landscape phone, desktop, odd strips. */
    private static final int[][] VIEWS = {
            {640, 1000}, {1080, 2000}, {1080, 2200}, {1080, 2400}, {1644, 3480}, {903, 2100},
            {1767, 2000}, {2560, 1500}, {1600, 2400}, {2400, 900}, {1920, 1000}, {300, 300}, {2000, 200}, {200, 2000},
    };

    @Test
    public void everyTargetIsOnScreen_andCropIsLimited() {
        for (int k = 0; k < PipeScene.CROP.length; k++) {
            int[] crop = PipeScene.CROP[k];
            float cw = crop[2] - crop[0], ch = crop[3] - crop[1];
            RectF keep = PipeScene.keepBounds(TARGETS.get(k), crop, PipeScene.KEEP_PAD);
            for (int[] v : VIEWS) {
                PipeScene.Fit f = PipeScene.fitScene(v[0], v[1], cw, ch, keep, PipeScene.MAX_CROP);
                String why = "scene " + k + " view " + v[0] + "x" + v[1] + " " + f;
                float contain = Math.min(v[0] / cw, v[1] / ch);
                assertWithMessage(why).that(f.scale).isAtLeast(contain - 1e-6f);
                for (RectF t : TARGETS.get(k)) {
                    RectF r = PipeScene.toView(t, crop, f);
                    assertWithMessage(why + " target " + t).that(r.left).isAtLeast(-EPS);
                    assertWithMessage(why + " target " + t).that(r.top).isAtLeast(-EPS);
                    assertWithMessage(why + " target " + t).that(r.right).isAtMost(v[0] + EPS);
                    assertWithMessage(why + " target " + t).that(r.bottom).isAtMost(v[1] + EPS);
                }
                float pw = cw * f.scale, ph = ch * f.scale;
                // at most MAX_CROP of each dimension is cut off
                assertWithMessage(why).that(Math.max(0, pw - v[0]) / pw).isAtMost(PipeScene.MAX_CROP + 1e-4f);
                assertWithMessage(why).that(Math.max(0, ph - v[1]) / ph).isAtMost(PipeScene.MAX_CROP + 1e-4f);
                // an overflowing axis is fully covered; a short axis is centred (letterbox both sides)
                checkAxis(why + " x", v[0], f.left, pw);
                checkAxis(why + " y", v[1], f.top, ph);
            }
        }
    }

    private static void checkAxis(String why, float view, float offset, float size) {
        if (size >= view) {
            assertWithMessage(why).that(offset).isAtMost(EPS);
            assertWithMessage(why).that(offset + size).isAtLeast(view - EPS);
        } else {
            assertWithMessage(why).that(offset).isWithin(EPS).of((view - size) / 2f);
        }
    }

    @Test
    public void withoutKeep_isPlainContainCentred() {
        PipeScene.Fit f = PipeScene.fitScene(1000, 1000, 500, 1000, null, PipeScene.MAX_CROP);
        assertThat(f.scale).isWithin(1e-6f).of(1f);
        assertThat(f.left).isWithin(1e-3f).of(250f);
        assertThat(f.top).isWithin(1e-3f).of(0f);
    }

    @Test
    public void zoomsTowardCoverWhenTargetsAllow() {
        // painting 1000x2000 in a 1000x1800 view: contain 0.9; a small keep area allows up to MAX_CROP
        RectF keep = new RectF(400, 900, 600, 1100);
        PipeScene.Fit f = PipeScene.fitScene(1000, 1800, 1000, 2000, keep, 0.08f);
        // cover would be 1.0 (10% of the height cropped); the 8% limit stops at 1800 / (2000 * 0.92)
        assertThat(f.scale).isWithin(1e-4f).of(1800f / 1840f);
        assertThat(f.scale).isGreaterThan(0.9f);
    }

    @Test
    public void degenerateSizesDoNotCrash() {
        PipeScene.Fit f = PipeScene.fitScene(0, 100, 875, 1540, null, 0.08f);
        assertThat(f.scale).isEqualTo(1f);
    }

    @Test
    public void viewAndPaintingTransformsAreInverse() {
        int[] crop = PipeScene.CROP[1];
        PipeScene.Fit f = PipeScene.fitScene(1080, 2200, crop[2] - crop[0], crop[3] - crop[1], null, 0f);
        RectF r = PipeScene.toView(new RectF(262, 350, 514, 422), crop, f);
        float[] p = PipeScene.toPainting(r.left, r.top, crop, f);
        assertThat(p[0]).isWithin(1e-2f).of(262f);
        assertThat(p[1]).isWithin(1e-2f).of(350f);
        float[] q = PipeScene.toPainting(r.right, r.bottom, crop, f);
        assertThat(q[0]).isWithin(1e-2f).of(514f);
        assertThat(q[1]).isWithin(1e-2f).of(422f);
    }

    @Test
    public void keepBounds_unionPaddedAndClippedToCrop() {
        int[] crop = {10, 20, 110, 220};
        RectF k = PipeScene.keepBounds(Arrays.asList(new RectF(15, 30, 50, 60), new RectF(60, 100, 108, 219)), crop, 8);
        assertThat(k.left).isEqualTo(0f);   // 15 - 8 - 10 < 0
        assertThat(k.top).isEqualTo(2f);    // 30 - 8 - 20
        assertThat(k.right).isEqualTo(100f); // clipped to crop width
        assertThat(k.bottom).isEqualTo(200f);
        assertThat(PipeScene.keepBounds(Arrays.<RectF>asList(), crop, 8)).isNull();
    }

    @Test
    public void landscapeGoesSideBySide() {
        assertThat(PipeScene.sideBySide(2400, 1080)).isTrue();
        assertThat(PipeScene.sideBySide(1080, 2400)).isFalse();
        assertThat(PipeScene.sideBySide(1000, 1000)).isFalse(); // square: stacked
    }

    @Test
    public void paneWidth() {
        assertThat(PipeScene.paneWidthDp(915)).isWithin(0.01f).of(915 * PipeScene.PANE_FRACTION); // landscape phone
        assertThat(PipeScene.paneWidthDp(1280)).isEqualTo(PipeScene.PANE_MAX_DP);                 // tablet
        assertThat(PipeScene.paneWidthDp(480)).isEqualTo(PipeScene.PANE_MIN_DP);                  // small split window
        assertThat(PipeScene.paneWidthDp(300)).isEqualTo(150f);                                   // never over half
    }

    @Test
    public void landscapePhone_paintingIsTallerSideBySide() {
        // 915x412dp landscape phone, ~24dp status + ~0 nav: the stacked layout lost 52dp to the strip
        int[] crop = PipeScene.CROP[0];
        float cw = crop[2] - crop[0], ch = crop[3] - crop[1];
        float stacked = PipeScene.fitScene(915, 388 - PipeScene.STRIP_DP, cw, ch, null, 0f).scale;
        float side = PipeScene.fitScene(915 - PipeScene.paneWidthDp(915), 388, cw, ch, null, 0f).scale;
        assertThat(side).isGreaterThan(stacked);
    }

    @Test
    public void placeAxis() {
        assertThat(PipeScene.placeAxis(100, 50, 0, 0)).isEqualTo(25f);           // centred letterbox
        assertThat(PipeScene.placeAxis(100, 120, 0, 0)).isEqualTo(-10f);         // centred overflow
        assertThat(PipeScene.placeAxis(100, 120, 0, 50)).isEqualTo(0f);          // keep at the start
        assertThat(PipeScene.placeAxis(100, 120, 70, 120)).isEqualTo(-20f);      // keep at the end
    }
}
