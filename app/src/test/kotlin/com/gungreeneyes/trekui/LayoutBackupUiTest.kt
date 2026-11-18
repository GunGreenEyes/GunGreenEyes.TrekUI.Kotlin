package com.gungreeneyes.trekui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Capture and apply against real SharedPreferences (Robolectric): a layout round trip restores the
 * layout, never exports credentials and never overwrites them on apply. Also [LayoutBackupUi]'s file name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayoutBackupUiTest {

    private lateinit var c: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        prefs = Prefs.get(c)
        prefs.sp.edit().clear().commit()
    }

    @Test
    fun captureAndApplyRoundTrip() {
        prefs.sp.edit().putInt("faction", 2).putInt("tiles", 9).putString("header2", "NODE CLUSTER 01")
            .putString("goveeKey", "SECRET").putString("nestRefresh", "REFRESH").commit()
        val doc = LayoutArchive.capture(c)
        assertThat(doc.toString()).doesNotContain("SECRET")
        assertThat(doc.toString()).doesNotContain("REFRESH")

        // the user changes things, including their credentials
        prefs.sp.edit().putInt("faction", 0).putInt("tiles", 3).putString("extra", "x")
            .putString("goveeKey", "NEW-KEY").commit()
        val before = prefs.version()

        LayoutArchive.apply(c, LayoutArchive.parse(doc.toString()).prefs)

        assertThat(prefs.i("faction", -1)).isEqualTo(2)
        assertThat(prefs.i("tiles", -1)).isEqualTo(9)
        assertThat(prefs.s("header2", "")).isEqualTo("NODE CLUSTER 01")
        assertThat(prefs.sp.contains("extra")).isFalse() // layout replaced, not merged
        assertThat(prefs.s("goveeKey", "")).isEqualTo("NEW-KEY") // credentials untouched
        assertThat(prefs.s("nestRefresh", "")).isEqualTo("REFRESH")
        assertThat(prefs.version()).isGreaterThan(before) // home rebuilds
    }

    @Test
    fun exportFileName() {
        assertThat(LayoutBackupUi.exportFileName(0L)).matches("trekui-layout-\\d{8}\\.json")
    }
}
