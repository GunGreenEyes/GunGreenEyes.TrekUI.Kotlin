package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Dialog sizing rules in [ConsoleDialog]. */
class ConsoleDialogTest {

    @Test
    fun phonesUseAlmostTheFullWidth() {
        assertThat(ConsoleDialog.widthDpFor(360f, 1f)).isWithin(EPS).of(328f)
        assertThat(ConsoleDialog.widthDpFor(412f, 1f)).isWithin(EPS).of(380f)
    }

    @Test
    fun wideWindowsAreCapped() {
        assertThat(ConsoleDialog.widthDpFor(915f, 1f)).isWithin(EPS).of(460f) // landscape phone
        assertThat(ConsoleDialog.widthDpFor(1280f, 1.3f)).isWithin(EPS).of(598f) // tablet: a column, not full width
        assertThat(ConsoleDialog.widthDpFor(1920f, 1.3f)).isWithin(EPS).of(598f)
        var w = 200f
        while (w <= 3000) {
            assertThat(ConsoleDialog.widthDpFor(w, 1.3f)).isAtMost(ConsoleDialog.MAX_WIDTH_DP * 1.3f + EPS)
            assertThat(ConsoleDialog.widthDpFor(w, 1f)).isLessThan(w)
            w += 25f
        }
    }

    @Test
    fun tinyWindowsStillGetAPositiveWidth() {
        assertThat(ConsoleDialog.widthDpFor(10f, 1f)).isEqualTo(1f)
    }

    @Test
    fun bodyHeightCap() {
        assertThat(ConsoleDialog.maxBodyHeightFor(1000)).isEqualTo(620)
        assertThat(ConsoleDialog.maxBodyHeightFor(0)).isEqualTo(0)
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
