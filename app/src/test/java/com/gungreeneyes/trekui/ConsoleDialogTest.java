package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** Dialog sizing rules in {@link ConsoleDialog}. */
public class ConsoleDialogTest {

    private static final float EPS = 1e-3f;

    @Test
    public void phonesUseAlmostTheFullWidth() {
        assertThat(ConsoleDialog.widthDpFor(360, 1f)).isWithin(EPS).of(328f);
        assertThat(ConsoleDialog.widthDpFor(412, 1f)).isWithin(EPS).of(380f);
    }

    @Test
    public void wideWindowsAreCapped() {
        assertThat(ConsoleDialog.widthDpFor(915, 1f)).isWithin(EPS).of(460f);     // landscape phone
        assertThat(ConsoleDialog.widthDpFor(1280, 1.3f)).isWithin(EPS).of(598f);  // tablet: a column, not full width
        assertThat(ConsoleDialog.widthDpFor(1920, 1.3f)).isWithin(EPS).of(598f);
        for (float w = 200; w <= 3000; w += 25) {
            assertThat(ConsoleDialog.widthDpFor(w, 1.3f)).isAtMost(ConsoleDialog.MAX_WIDTH_DP * 1.3f + EPS);
            assertThat(ConsoleDialog.widthDpFor(w, 1f)).isLessThan(w);
        }
    }

    @Test
    public void tinyWindowsStillGetAPositiveWidth() {
        assertThat(ConsoleDialog.widthDpFor(10, 1f)).isEqualTo(1f);
    }

    @Test
    public void bodyHeightCap() {
        assertThat(ConsoleDialog.maxBodyHeightFor(1000)).isEqualTo(620);
        assertThat(ConsoleDialog.maxBodyHeightFor(0)).isEqualTo(0);
    }
}
