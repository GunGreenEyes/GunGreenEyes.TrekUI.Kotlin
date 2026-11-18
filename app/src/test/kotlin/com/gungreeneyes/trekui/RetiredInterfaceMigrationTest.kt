package com.gungreeneyes.trekui

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Upgrading with a saved choice of one of the retired interfaces (ids 3-8, removed in 44): the
 * `trekui` preferences read as COMMAND at sub-theme 0, the stored values are rewritten, and the
 * remaining interfaces' saved choices are left exactly as they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RetiredInterfaceMigrationTest {

    private lateinit var sp: SharedPreferences
    private lateinit var prefs: TrekPrefs

    @Before
    fun setUp() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        sp = c.getSharedPreferences(TrekPrefs.FILE, Context.MODE_PRIVATE)
        sp.edit().clear().commit()
        TrekPrefs.setThemeOverride(null)
        // a plain TrekPrefs (its constructor is protected), not the process singleton
        prefs = object : TrekPrefs(c) {}
    }

    @After
    fun tearDown() {
        sp.edit().clear().commit()
        Skin.faction = Skin.COMMAND
    }

    @Test
    fun savedRetiredInterface_readsAsCommandSubThemeZero_andIsRewritten() {
        for (retired in Skin.FIRST_RETIRED..Skin.LAST_RETIRED) {
            sp.edit().clear().putInt(TrekPrefs.KEY_FACTION, retired).putInt("theme", 4).commit()

            assertThat(prefs.faction()).isEqualTo(Skin.COMMAND)
            assertThat(prefs.subTheme(Skin.COMMAND)).isEqualTo(0)
            assertThat(sp.getInt(TrekPrefs.KEY_FACTION, -1)).isEqualTo(Skin.COMMAND)
            assertThat(sp.getInt(TrekPrefs.themeKeyFor(Skin.COMMAND), -1)).isEqualTo(0)
            // the whole look follows: palette() syncs Skin.faction to Command's first theme
            assertThat(prefs.palette()).isSameInstanceAs(Palette.of(Skin.COMMAND, 0))
            assertThat(Skin.faction).isEqualTo(Skin.COMMAND)
            assertThat(prefs.currentTheme().faction).isEqualTo(Skin.COMMAND)
            assertThat(prefs.currentTheme().subTheme).isEqualTo(0)
        }
    }

    @Test
    fun remainingInterfaces_keepTheirChoiceAndSubTheme() {
        for (f in Skin.INTERFACES) {
            sp.edit().clear().putInt(TrekPrefs.KEY_FACTION, f).putInt(TrekPrefs.themeKeyFor(f), 1).commit()

            assertThat(prefs.faction()).isEqualTo(f)
            assertThat(prefs.subTheme(f)).isEqualTo(TrekPrefs.clampSubTheme(f, 1))
            assertThat(sp.getInt(TrekPrefs.KEY_FACTION, -1)).isEqualTo(f)
            assertThat(sp.getInt(TrekPrefs.themeKeyFor(f), -1)).isEqualTo(1)
        }
    }

    @Test
    fun unknownInterface_readsAsCommandWithoutTouchingCommandsTheme() {
        sp.edit().putInt(TrekPrefs.KEY_FACTION, 42).putInt("theme", 4).commit()

        assertThat(prefs.faction()).isEqualTo(Skin.COMMAND)
        assertThat(prefs.subTheme(Skin.COMMAND)).isEqualTo(4)
    }

    @Test
    fun setFaction_neverStoresARetiredId() {
        prefs.setFaction(5)
        assertThat(sp.getInt(TrekPrefs.KEY_FACTION, -1)).isEqualTo(Skin.COMMAND)
    }

    @Test
    fun launcherDropsTheRetiredInterfacesDataButKeepsTheRest() {
        val c = ApplicationProvider.getApplicationContext<Context>()
        val records = c.getSharedPreferences(Prefs.RETIRED_FILE, Context.MODE_PRIVATE)
        records.edit().putString("journal", "[{}]").commit()
        sp.edit().putInt("pipeLayout4", 1).putString("header6", "X").putInt("theme8", 0).putString("sndBi5_2", "synth_pp_launch")
            .putLong("pipeTimerStart", 1L).putInt("lastPipe", 6).putString("header1", "KEEP").putInt("theme9", 2)
            .putInt(TrekPrefs.KEY_FACTION, Skin.ERA23).commit()

        Prefs.dropRetiredData(c, sp)

        for (gone in arrayOf("pipeLayout4", "header6", "theme8", "sndBi5_2", "pipeTimerStart", "lastPipe")) {
            assertThat(sp.contains(gone)).isFalse()
        }
        assertThat(sp.getString("header1", null)).isEqualTo("KEEP")
        assertThat(sp.getInt("theme9", -1)).isEqualTo(2)
        assertThat(sp.getInt(TrekPrefs.KEY_FACTION, -1)).isEqualTo(Skin.ERA23)
        assertThat(c.getSharedPreferences(Prefs.RETIRED_FILE, Context.MODE_PRIVATE).all).isEmpty()
    }
}
