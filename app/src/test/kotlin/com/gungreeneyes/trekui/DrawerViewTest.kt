package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** Responsive column count and search ranking in [DrawerView]. */
class DrawerViewTest {

    private fun cols(w: Float, h: Float, user: Int, zoom: Float): Int =
        DrawerView.columnsFor(ScreenMetrics.compute(w, h, 2f), user, zoom)

    @Test
    fun referencePhoneShowsExactlyTheUsersSetting() {
        for (user in DrawerView.MIN_USER_COLS..DrawerView.MAX_USER_COLS) {
            assertWithMessage("user $user").that(cols(360f, 640f, user, 1f)).isEqualTo(user)
        }
    }

    @Test
    fun userSettingIsClamped() {
        assertThat(DrawerView.minTileDpFor(1)).isEqualTo(DrawerView.minTileDpFor(DrawerView.MIN_USER_COLS))
        assertThat(DrawerView.minTileDpFor(20)).isEqualTo(DrawerView.minTileDpFor(DrawerView.MAX_USER_COLS))
    }

    @Test
    fun deviceTable_defaultFourColumns() {
        assertThat(cols(320f, 568f, 4, 1f)).isEqualTo(3) // small phone: one fewer, labels stay readable
        assertThat(cols(412f, 915f, 4, 1f)).isEqualTo(4) // Pixel 7
        assertThat(cols(411f, 960f, 4, 1f)).isEqualTo(4) // 21:9
        assertThat(cols(344f, 882f, 4, 1f)).isEqualTo(3) // Fold, folded
        assertThat(cols(673f, 841f, 4, 1.3f)).isEqualTo(5) // Fold, open
        assertThat(cols(915f, 412f, 4, 1f)).isEqualTo(9) // landscape phone
        assertThat(cols(800f, 1280f, 4, 1.3f)).isEqualTo(6) // 10in tablet portrait
        assertThat(cols(1280f, 800f, 4, 1.3f)).isEqualTo(10) // Pixel Tablet landscape
        assertThat(cols(1920f, 1080f, 4, 1.3f)).isEqualTo(DrawerView.MAX_COLS)
    }

    @Test
    fun columnsStayInRange() {
        var w = 200f
        while (w <= 2600f) {
            for (user in 3..6) {
                val n = cols(w, 800f, user, 1f)
                assertThat(n).isAtLeast(DrawerView.MIN_COLS)
                assertThat(n).isAtMost(DrawerView.MAX_COLS)
            }
            w += 50f
        }
    }

    @Test
    fun matchRank() {
        assertThat(DrawerView.matchRank("maps", "com.google.android.apps.maps", "ma")).isEqualTo(DrawerView.MATCH_PREFIX)
        assertThat(DrawerView.matchRank("google maps", "com.google.android.apps.maps", "maps")).isEqualTo(DrawerView.MATCH_CONTAINS)
        assertThat(DrawerView.matchRank("photos", "com.google.android.apps.photos", "google")).isEqualTo(DrawerView.MATCH_CONTAINS)
        assertThat(DrawerView.matchRank("clock", "com.android.deskclock", "zz")).isEqualTo(DrawerView.NO_MATCH)
    }
}
