package com.gungreeneyes.trekui.core;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.Map;

/** Suite contract rows: theme encoding with the real sub-theme clamp, the lockscreen switch and store links (LCA-T35). */
public class SuiteContractTest {

    private static final String LAUNCHER = SuiteContract.LAUNCHER;

    private static SuiteTheme roundTrip(int faction, int sub) {
        return SuiteContract.parseTheme(SuiteContract.themeRow(LAUNCHER, new SuiteTheme(faction, sub, 0, "synth")));
    }

    @Test
    public void theme_roundTripsAValidChoice() {
        assertThat(roundTrip(0, 10)).isEqualTo(new SuiteTheme(0, 10, 0, "synth"));
        assertThat(roundTrip(1, 4)).isEqualTo(new SuiteTheme(1, 4, 0, "synth"));
        assertThat(roundTrip(2, 3)).isEqualTo(new SuiteTheme(2, 3, 0, "synth"));
    }

    @Test
    public void theme_subThemeIsClampedIntoTheFactionsList() {
        assertThat(roundTrip(0, 11).subTheme).isEqualTo(0);   // Command has 11 themes
        assertThat(roundTrip(1, 5).subTheme).isEqualTo(0);    // Imperial has 5
        assertThat(roundTrip(2, -2).subTheme).isEqualTo(0);
        assertThat(roundTrip(4, 1).subTheme).isEqualTo(0);    // a pipe interface has one fixed theme
        assertThat(roundTrip(9, 2).subTheme).isEqualTo(2);    // eras keep their three
        assertThat(roundTrip(9, 3).subTheme).isEqualTo(0);
    }

    @Test
    public void theme_badFactionIsNoAnswer() {
        Map<String, String> row = SuiteContract.themeRow(LAUNCHER, new SuiteTheme(99, 0, 0, null));
        assertThat(SuiteContract.parseTheme(row)).isNull();
    }

    @Test
    public void lockscreen_roundTripsAndRejectsGarbage() {
        assertThat(SuiteContract.parseLockscreen(SuiteContract.lockscreenRow(LAUNCHER, true))).isTrue();
        assertThat(SuiteContract.parseLockscreen(SuiteContract.lockscreenRow(LAUNCHER, false))).isFalse();
        Map<String, String> row = SuiteContract.lockscreenRow(LAUNCHER, true);
        row.put(SuiteContract.COL_ENABLED, "yes");
        assertThat(SuiteContract.parseLockscreen(row)).isNull();
        row = SuiteContract.lockscreenRow(LAUNCHER, true);
        row.put(SuiteContract.COL_VERSION, "0");
        assertThat(SuiteContract.parseLockscreen(row)).isNull();
        assertThat(SuiteContract.parseLockscreen(null)).isNull();
    }

    @Test
    public void lockscreenColumns_matchTheRow() {
        assertThat(SuiteContract.lockscreenRow(LAUNCHER, true).keySet())
                .containsExactlyElementsIn(SuiteContract.LOCKSCREEN_COLUMNS).inOrder();
    }

    @Test
    public void storeLinks_pointAtTheProductionListing() {
        assertThat(SuiteContract.playUrl(SuiteContract.KEYBOARD + ".dev"))
                .isEqualTo("https://play.google.com/store/apps/details?id=com.gungreeneyes.trekui.keyboard");
        assertThat(SuiteContract.marketUri(SuiteContract.MESSAGES))
                .isEqualTo("market://details?id=com.gungreeneyes.trekui.messages");
    }
}
