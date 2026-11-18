package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [AppInfo.categorize] by package and label, without ApplicationInfo (pure JVM). */
class AppInfoTest {

    private fun cat(pkg: String, label: String?): Int = AppInfo.categorize(pkg, label, null)

    @Test
    fun communications() {
        assertThat(cat("org.thoughtcrime.securesms", "signal")).isEqualTo(AppInfo.CAT_COMM)
        assertThat(cat("com.whatsapp", "whatsapp")).isEqualTo(AppInfo.CAT_COMM)
        assertThat(cat("com.example.x", "my mail")).isEqualTo(AppInfo.CAT_COMM)
    }

    @Test
    fun navigationBeforeMedia() {
        assertThat(cat("com.google.android.apps.maps", "maps")).isEqualTo(AppInfo.CAT_NAV)
        assertThat(cat("com.waze", "waze")).isEqualTo(AppInfo.CAT_NAV)
    }

    @Test
    fun mediaAndWork() {
        assertThat(cat("com.spotify.music", "spotify")).isEqualTo(AppInfo.CAT_MEDIA)
        assertThat(cat("com.example.cam", "Camera")).isEqualTo(AppInfo.CAT_MEDIA)
        assertThat(cat("com.microsoft.office.word", "word")).isEqualTo(AppInfo.CAT_WORK)
        assertThat(cat("com.example.z", "calculator")).isEqualTo(AppInfo.CAT_WORK)
    }

    @Test
    fun systemAndOther() {
        assertThat(cat("com.android.settings", "settings")).isEqualTo(AppInfo.CAT_SYSTEM)
        assertThat(cat("com.samsung.android.foo", "foo")).isEqualTo(AppInfo.CAT_SYSTEM)
        assertThat(cat("com.example.thing", "thing")).isEqualTo(AppInfo.CAT_OTHER)
    }

    @Test
    fun labelMatchingIgnoresCase() {
        assertThat(cat("com.example.q", "PHONE")).isEqualTo(AppInfo.CAT_COMM)
        assertThat(cat("com.example.q", null)).isEqualTo(AppInfo.CAT_OTHER)
    }

    @Test
    fun keyAndNames() {
        assertThat(AppInfo.key("com.a/.Main", 0)).isEqualTo("com.a/.Main#0")
        assertThat(AppInfo.CAT_NAMES[AppInfo.CAT_GAMES]).isEqualTo("GAMES")
        assertThat(AppInfo.CAT_NAMES).hasLength(AppInfo.CAT_OTHER + 1)
    }
}
