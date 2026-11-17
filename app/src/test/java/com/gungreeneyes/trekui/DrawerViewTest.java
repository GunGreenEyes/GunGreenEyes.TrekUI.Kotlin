package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import org.junit.Test;

/** Responsive column count and search ranking in {@link DrawerView}. */
public class DrawerViewTest {

    private static int cols(float w, float h, int user, float zoom) {
        return DrawerView.columnsFor(ScreenMetrics.compute(w, h, 2f), user, zoom);
    }

    @Test
    public void referencePhoneShowsExactlyTheUsersSetting() {
        for (int user = DrawerView.MIN_USER_COLS; user <= DrawerView.MAX_USER_COLS; user++) {
            assertWithMessage("user " + user).that(cols(360, 640, user, 1f)).isEqualTo(user);
        }
    }

    @Test
    public void userSettingIsClamped() {
        assertThat(DrawerView.minTileDpFor(1)).isEqualTo(DrawerView.minTileDpFor(DrawerView.MIN_USER_COLS));
        assertThat(DrawerView.minTileDpFor(20)).isEqualTo(DrawerView.minTileDpFor(DrawerView.MAX_USER_COLS));
    }

    @Test
    public void deviceTable_defaultFourColumns() {
        assertThat(cols(320, 568, 4, 1f)).isEqualTo(3);      // small phone: one fewer, labels stay readable
        assertThat(cols(412, 915, 4, 1f)).isEqualTo(4);      // Pixel 7
        assertThat(cols(411, 960, 4, 1f)).isEqualTo(4);      // 21:9
        assertThat(cols(344, 882, 4, 1f)).isEqualTo(3);      // Fold, folded
        assertThat(cols(673, 841, 4, 1.3f)).isEqualTo(5);    // Fold, open
        assertThat(cols(915, 412, 4, 1f)).isEqualTo(9);      // landscape phone
        assertThat(cols(800, 1280, 4, 1.3f)).isEqualTo(6);   // 10in tablet portrait
        assertThat(cols(1280, 800, 4, 1.3f)).isEqualTo(10);  // Pixel Tablet landscape
        assertThat(cols(1920, 1080, 4, 1.3f)).isEqualTo(DrawerView.MAX_COLS);
    }

    @Test
    public void columnsStayInRange() {
        for (float w = 200; w <= 2600; w += 50) {
            for (int user = 3; user <= 6; user++) {
                int n = cols(w, 800, user, 1f);
                assertThat(n).isAtLeast(DrawerView.MIN_COLS);
                assertThat(n).isAtMost(DrawerView.MAX_COLS);
            }
        }
    }

    @Test
    public void matchRank() {
        assertThat(DrawerView.matchRank("maps", "com.google.android.apps.maps", "ma")).isEqualTo(DrawerView.MATCH_PREFIX);
        assertThat(DrawerView.matchRank("google maps", "com.google.android.apps.maps", "maps")).isEqualTo(DrawerView.MATCH_CONTAINS);
        assertThat(DrawerView.matchRank("photos", "com.google.android.apps.photos", "google")).isEqualTo(DrawerView.MATCH_CONTAINS);
        assertThat(DrawerView.matchRank("clock", "com.android.deskclock", "zz")).isEqualTo(DrawerView.NO_MATCH);
    }
}
