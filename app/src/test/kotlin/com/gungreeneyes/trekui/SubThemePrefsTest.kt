package com.gungreeneyes.trekui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.gungreeneyes.trekui.core.SuiteTheme
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LCA-T35: sub-theme and SUITE switches round-trip through [TrekPrefs] and reach the palette. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubThemePrefsTest {

    private lateinit var prefs: TrekPrefs

    @Before
    fun setUp() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        c.getSharedPreferences(TrekPrefs.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        // not the process singleton: each test gets this test's context (the constructor is protected)
        prefs = object : TrekPrefs(c) {}
    }

    @After
    fun tearDown() {
        TrekPrefs.setThemeOverride(null)
        Skin.faction = Skin.COMMAND
        Skin.useSubTheme(Skin.IMPERIAL, Palette.IMPERIAL[0])
        Skin.useSubTheme(Skin.COLLECTIVE, Palette.COLLECTIVE[0])
    }

    @Test
    fun freshInstall_usesEachInterfacesDefaultLook() {
        for (f in 0..TrekPrefs.MAX_FACTION) assertThat(prefs.subTheme(f)).isEqualTo(0)
        assertThat(prefs.palette()).isSameInstanceAs(Palette.ALL[0])
        assertThat(prefs.lockscreenEnabled()).isTrue()
    }

    @Test
    fun subTheme_roundTripsPerInterface() {
        prefs.setSubTheme(Skin.COMMAND, 8)
        prefs.setSubTheme(Skin.IMPERIAL, 2)
        prefs.setSubTheme(Skin.COLLECTIVE, 4)
        assertThat(prefs.subTheme(Skin.COMMAND)).isEqualTo(8)
        assertThat(prefs.subTheme(Skin.IMPERIAL)).isEqualTo(2)
        assertThat(prefs.subTheme(Skin.COLLECTIVE)).isEqualTo(4)
        assertThat(prefs.i("theme", -1)).isEqualTo(8) // Command keeps its 1.0 key
    }

    @Test
    fun subTheme_outOfRangeIsClamped() {
        prefs.put("theme1", 42)
        assertThat(prefs.subTheme(Skin.IMPERIAL)).isEqualTo(0)
        prefs.setSubTheme(Skin.COLLECTIVE, -5)
        assertThat(prefs.subTheme(Skin.COLLECTIVE)).isEqualTo(0)
    }

    @Test
    fun palette_followsTheChosenSubThemeAndRecoloursChrome() {
        prefs.setFaction(Skin.IMPERIAL)
        prefs.setSubTheme(Skin.IMPERIAL, 1)
        val p = prefs.palette()
        assertThat(p).isSameInstanceAs(Palette.IMPERIAL[1])
        assertThat(Skin.faction).isEqualTo(Skin.IMPERIAL)
        assertThat(Skin.bg()).isEqualTo(Palette.IMPERIAL[1].bg)
        assertThat(ImperialDraw.RED).isEqualTo(Palette.IMPERIAL[1].c[0])
        assertThat(prefs.currentTheme().subTheme).isEqualTo(1)
    }

    @Test
    fun override_followsTheLaunchersSubTheme() {
        prefs.setSubTheme(Skin.COLLECTIVE, 1)
        TrekPrefs.setThemeOverride(SuiteTheme(Skin.COLLECTIVE, 3, 0, null))
        assertThat(prefs.subTheme(Skin.COLLECTIVE)).isEqualTo(3)
        assertThat(prefs.palette()).isSameInstanceAs(Palette.COLLECTIVE[3])
        TrekPrefs.setThemeOverride(null)
        assertThat(prefs.subTheme(Skin.COLLECTIVE)).isEqualTo(1)
    }

    @Test
    fun lockscreenSwitch_roundTrips() {
        prefs.setLockscreenEnabled(false)
        assertThat(prefs.lockscreenEnabled()).isFalse()
        prefs.setLockscreenEnabled(true)
        assertThat(prefs.lockscreenEnabled()).isTrue()
    }
}
