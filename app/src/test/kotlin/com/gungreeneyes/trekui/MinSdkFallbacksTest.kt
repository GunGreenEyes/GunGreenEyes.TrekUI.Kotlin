package com.gungreeneyes.trekui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * TUK-T14 (minSdk 23): the launcher's version-guarded paths, on Android 6.0 (API 23, the fallbacks) and on a
 * current release (API 34, unchanged behaviour). Robolectric 4.18 has no API 23 runtime ("API level 23 is not
 * available"), so the Android 6.0 cases run on API 34 with `Build.VERSION.SDK_INT` pinned to 23 ([onAndroid6]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MinSdkFallbacksTest {

    /** Runs [block] with `Build.VERSION.SDK_INT` = 23, so the code takes its Android 6.0 branches. */
    private fun <T> onAndroid6(block: () -> T): T {
        val saved = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.M)
        try {
            return block()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", saved)
        }
    }

    @Test
    fun defaultAppsScreen_isTheAppsScreenOnAndroid6() {
        onAndroid6 {
            assertThat(SuiteSettings.defaultAppsIntent().action).isEqualTo(Settings.ACTION_APPLICATION_SETTINGS)
        }
    }

    @Test
    fun defaultAppsScreen_isTheDefaultAppsScreenFromAndroid7() {
        assertThat(SuiteSettings.defaultAppsIntent().action).isEqualTo(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
    }

    @Test
    fun restoringTheSmsApp_usesTheChangeDefaultDialogOnAndroid6() {
        onAndroid6 {
            val i = SuiteSettings.restoreSmsIntent("com.example.sms")
            assertThat(i.action).isEqualTo(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            assertThat(i.getStringExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME)).isEqualTo("com.example.sms")
        }
    }

    @Test
    fun declaredCategory_isIgnoredBeforeAndroid8() {
        onAndroid6 {
            val ai = ApplicationInfo().apply { category = ApplicationInfo.CATEGORY_GAME }
            assertThat(AppInfo.categorize("com.example.app", "app", ai)).isEqualTo(AppInfo.CAT_OTHER)
        }
    }

    @Test
    fun declaredCategory_picksGamesFromAndroid8() {
        val ai = ApplicationInfo().apply { category = ApplicationInfo.CATEGORY_GAME }
        assertThat(AppInfo.categorize("com.example.app", "app", ai)).isEqualTo(AppInfo.CAT_GAMES)
    }

    @Test
    fun retiredRecords_areEmptiedOnAndroid6() {
        onAndroid6 {
            val c = ApplicationProvider.getApplicationContext<Context>()
            val records = c.getSharedPreferences(Prefs.RETIRED_FILE, Context.MODE_PRIVATE)
            records.edit().putString("journal", "[{}]").commit()
            val sp = c.getSharedPreferences(TrekPrefs.FILE, Context.MODE_PRIVATE)

            Prefs.dropRetiredData(c, sp)

            assertThat(c.getSharedPreferences(Prefs.RETIRED_FILE, Context.MODE_PRIVATE).all).isEmpty()
        }
    }

    @Test
    fun animatorScale_isReadFromSettingsOnAndroid6() {
        onAndroid6 {
            val c = ApplicationProvider.getApplicationContext<Context>()
            Settings.Global.putFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            assertThat(Compat.animatorsEnabled(c)).isFalse()
            Settings.Global.putFloat(c.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            assertThat(Compat.animatorsEnabled(c)).isTrue()
        }
    }

    @Test
    fun quickSettingsRefresh_isANoOpOnAndroid6() {
        onAndroid6 {
            QsTiles.refresh(ApplicationProvider.getApplicationContext()) // no TileService before API 24: must not throw
            assertThat(QsTiles.canRequestAdd()).isFalse()
        }
    }
}
