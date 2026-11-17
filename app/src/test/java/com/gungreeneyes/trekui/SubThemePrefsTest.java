package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.gungreeneyes.trekui.core.SuiteTheme;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** LCA-T35: sub-theme and SUITE switches round-trip through {@link TrekPrefs} and reach the palette. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SubThemePrefsTest {

    private TrekPrefs prefs;

    @Before
    public void setUp() {
        Context c = ApplicationProvider.getApplicationContext();
        c.getSharedPreferences(TrekPrefs.FILE, Context.MODE_PRIVATE).edit().clear().commit();
        prefs = new TrekPrefs(c); // not the process singleton: each test gets this test's context
    }

    @After
    public void tearDown() {
        TrekPrefs.setThemeOverride(null);
        Skin.faction = Skin.COMMAND;
        Skin.useSubTheme(Skin.IMPERIAL, Palette.IMPERIAL[0]);
        Skin.useSubTheme(Skin.COLLECTIVE, Palette.COLLECTIVE[0]);
    }

    @Test
    public void freshInstall_usesEachInterfacesDefaultLook() {
        for (int f = 0; f <= TrekPrefs.MAX_FACTION; f++) assertThat(prefs.subTheme(f)).isEqualTo(0);
        assertThat(prefs.palette()).isSameInstanceAs(Palette.ALL[0]);
        assertThat(prefs.lockscreenEnabled()).isTrue();
    }

    @Test
    public void subTheme_roundTripsPerInterface() {
        prefs.setSubTheme(Skin.COMMAND, 8);
        prefs.setSubTheme(Skin.IMPERIAL, 2);
        prefs.setSubTheme(Skin.COLLECTIVE, 4);
        assertThat(prefs.subTheme(Skin.COMMAND)).isEqualTo(8);
        assertThat(prefs.subTheme(Skin.IMPERIAL)).isEqualTo(2);
        assertThat(prefs.subTheme(Skin.COLLECTIVE)).isEqualTo(4);
        assertThat(prefs.i("theme", -1)).isEqualTo(8); // Command keeps its 1.0 key
    }

    @Test
    public void subTheme_outOfRangeIsClamped() {
        prefs.put("theme1", 42);
        assertThat(prefs.subTheme(Skin.IMPERIAL)).isEqualTo(0);
        prefs.setSubTheme(Skin.COLLECTIVE, -5);
        assertThat(prefs.subTheme(Skin.COLLECTIVE)).isEqualTo(0);
    }

    @Test
    public void palette_followsTheChosenSubThemeAndRecoloursChrome() {
        prefs.setFaction(Skin.IMPERIAL);
        prefs.setSubTheme(Skin.IMPERIAL, 1);
        Palette p = prefs.palette();
        assertThat(p).isSameInstanceAs(Palette.IMPERIAL[1]);
        assertThat(Skin.faction).isEqualTo(Skin.IMPERIAL);
        assertThat(Skin.bg()).isEqualTo(Palette.IMPERIAL[1].bg);
        assertThat(ImperialDraw.RED).isEqualTo(Palette.IMPERIAL[1].c[0]);
        assertThat(prefs.currentTheme().subTheme).isEqualTo(1);
    }

    @Test
    public void override_followsTheLaunchersSubTheme() {
        prefs.setSubTheme(Skin.COLLECTIVE, 1);
        TrekPrefs.setThemeOverride(new SuiteTheme(Skin.COLLECTIVE, 3, 0, null));
        assertThat(prefs.subTheme(Skin.COLLECTIVE)).isEqualTo(3);
        assertThat(prefs.palette()).isSameInstanceAs(Palette.COLLECTIVE[3]);
        TrekPrefs.setThemeOverride(null);
        assertThat(prefs.subTheme(Skin.COLLECTIVE)).isEqualTo(1);
    }

    @Test
    public void lockscreenSwitch_roundTrips() {
        prefs.setLockscreenEnabled(false);
        assertThat(prefs.lockscreenEnabled()).isFalse();
        prefs.setLockscreenEnabled(true);
        assertThat(prefs.lockscreenEnabled()).isTrue();
    }
}
