package com.gungreeneyes.trekui.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Suite contract rows: theme encoding with the real sub-theme clamp, the lockscreen switch and store links (LCA-T35). */
class SuiteContractTest {

    @Test
    fun theme_roundTripsAValidChoice() {
        assertThat(roundTrip(0, 10)).isEqualTo(SuiteTheme(0, 10, 0, "synth"))
        assertThat(roundTrip(1, 4)).isEqualTo(SuiteTheme(1, 4, 0, "synth"))
        assertThat(roundTrip(2, 3)).isEqualTo(SuiteTheme(2, 3, 0, "synth"))
    }

    @Test
    fun theme_subThemeIsClampedIntoTheFactionsList() {
        assertThat(roundTrip(0, 11)!!.subTheme).isEqualTo(0) // Command has 11 themes
        assertThat(roundTrip(1, 5)!!.subTheme).isEqualTo(0) // Imperial has 5
        assertThat(roundTrip(2, -2)!!.subTheme).isEqualTo(0)
        assertThat(roundTrip(9, 2)!!.subTheme).isEqualTo(2) // eras keep their three
        assertThat(roundTrip(9, 3)!!.subTheme).isEqualTo(0)
    }

    @Test
    fun theme_retiredInterfaceIsFollowedAsCommandAtItsFirstSubTheme() {
        // a launcher row still naming one of the retired interfaces (ids 3-8)
        for (f in 3..8) {
            assertThat(roundTrip(f, 0)).isEqualTo(SuiteTheme(0, 0, 0, "synth"))
            assertThat(roundTrip(f, 4)).isEqualTo(SuiteTheme(0, 0, 0, "synth"))
        }
        // the remaining interfaces are untouched
        assertThat(roundTrip(9, 1)).isEqualTo(SuiteTheme(9, 1, 0, "synth"))
        assertThat(roundTrip(10, 0)).isEqualTo(SuiteTheme(10, 0, 0, "synth"))
    }

    @Test
    fun theme_badFactionIsNoAnswer() {
        val row = SuiteContract.themeRow(LAUNCHER, SuiteTheme(99, 0, 0, null))
        assertThat(SuiteContract.parseTheme(row)).isNull()
    }

    @Test
    fun lockscreen_roundTripsAndRejectsGarbage() {
        assertThat(SuiteContract.parseLockscreen(SuiteContract.lockscreenRow(LAUNCHER, true))).isTrue()
        assertThat(SuiteContract.parseLockscreen(SuiteContract.lockscreenRow(LAUNCHER, false))).isFalse()
        var row = SuiteContract.lockscreenRow(LAUNCHER, true)
        row[SuiteContract.COL_ENABLED] = "yes"
        assertThat(SuiteContract.parseLockscreen(row)).isNull()
        row = SuiteContract.lockscreenRow(LAUNCHER, true)
        row[SuiteContract.COL_VERSION] = "0"
        assertThat(SuiteContract.parseLockscreen(row)).isNull()
        assertThat(SuiteContract.parseLockscreen(null)).isNull()
    }

    @Test
    fun lockscreenColumns_matchTheRow() {
        assertThat(SuiteContract.lockscreenRow(LAUNCHER, true).keys)
            .containsExactlyElementsIn(SuiteContract.LOCKSCREEN_COLUMNS).inOrder()
    }

    @Test
    fun storeLinks_pointAtTheProductionListing() {
        assertThat(SuiteContract.playUrl(SuiteContract.KEYBOARD + ".dev"))
            .isEqualTo("https://play.google.com/store/apps/details?id=com.gungreeneyes.trekui.keyboard")
        assertThat(SuiteContract.marketUri(SuiteContract.MESSAGES))
            .isEqualTo("market://details?id=com.gungreeneyes.trekui.messages")
    }

    private companion object {
        const val LAUNCHER = SuiteContract.LAUNCHER

        fun roundTrip(faction: Int, sub: Int): SuiteTheme? =
            SuiteContract.parseTheme(SuiteContract.themeRow(LAUNCHER, SuiteTheme(faction, sub, 0, "synth")))
    }
}
