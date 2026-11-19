package com.gungreeneyes.trekui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Govee and Nest credentials live in [Prefs.SECRETS_FILE] and move out of the backed-up "trekui" file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecretPrefsTest {

    private lateinit var c: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        c = ApplicationProvider.getApplicationContext()
        prefs = Prefs.get(c)
        prefs.sp.edit().clear().commit()
        c.getSharedPreferences(Prefs.SECRETS_FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun migratesOldValuesAndDeletesThem() {
        prefs.sp.edit().putString("goveeKey", "G").putString("nestSecret", "S")
            .putString("nestRefresh", "R").putString("nestAccess", "A").putInt("tiles", 9).commit()

        assertThat(Govee.key(prefs)).isEqualTo("G")
        assertThat(Nest.secret(prefs)).isEqualTo("S")
        assertThat(Nest.linked(prefs)).isTrue()
        assertThat(prefs.secret("nestAccess")).isEqualTo("A")
        for (k in Prefs.SECRET_KEYS) assertThat(prefs.sp.contains(k)).isFalse()
        assertThat(prefs.i("tiles", 0)).isEqualTo(9)
    }

    @Test
    fun putSecretStaysOutOfMainFile() {
        prefs.putSecret("nestRefresh", "R2")
        assertThat(prefs.sp.contains("nestRefresh")).isFalse()
        Nest.unlink(prefs)
        assertThat(Nest.linked(prefs)).isFalse()
    }
}
