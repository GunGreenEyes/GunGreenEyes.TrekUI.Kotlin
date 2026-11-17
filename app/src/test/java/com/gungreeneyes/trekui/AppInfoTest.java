package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** {@link AppInfo#categorize} by package and label, without ApplicationInfo (pure JVM). */
public class AppInfoTest {

    private static int cat(String pkg, String label) {
        return AppInfo.categorize(pkg, label, null);
    }

    @Test
    public void communications() {
        assertThat(cat("org.thoughtcrime.securesms", "signal")).isEqualTo(AppInfo.CAT_COMM);
        assertThat(cat("com.whatsapp", "whatsapp")).isEqualTo(AppInfo.CAT_COMM);
        assertThat(cat("com.example.x", "my mail")).isEqualTo(AppInfo.CAT_COMM);
    }

    @Test
    public void navigationBeforeMedia() {
        assertThat(cat("com.google.android.apps.maps", "maps")).isEqualTo(AppInfo.CAT_NAV);
        assertThat(cat("com.waze", "waze")).isEqualTo(AppInfo.CAT_NAV);
    }

    @Test
    public void mediaAndWork() {
        assertThat(cat("com.spotify.music", "spotify")).isEqualTo(AppInfo.CAT_MEDIA);
        assertThat(cat("com.example.cam", "Camera")).isEqualTo(AppInfo.CAT_MEDIA);
        assertThat(cat("com.microsoft.office.word", "word")).isEqualTo(AppInfo.CAT_WORK);
        assertThat(cat("com.example.z", "calculator")).isEqualTo(AppInfo.CAT_WORK);
    }

    @Test
    public void systemAndOther() {
        assertThat(cat("com.android.settings", "settings")).isEqualTo(AppInfo.CAT_SYSTEM);
        assertThat(cat("com.samsung.android.foo", "foo")).isEqualTo(AppInfo.CAT_SYSTEM);
        assertThat(cat("com.example.thing", "thing")).isEqualTo(AppInfo.CAT_OTHER);
    }

    @Test
    public void labelMatchingIgnoresCase() {
        assertThat(cat("com.example.q", "PHONE")).isEqualTo(AppInfo.CAT_COMM);
        assertThat(cat("com.example.q", null)).isEqualTo(AppInfo.CAT_OTHER);
    }

    @Test
    public void keyAndNames() {
        assertThat(AppInfo.key("com.a/.Main", 0)).isEqualTo("com.a/.Main#0");
        assertThat(AppInfo.CAT_NAMES[AppInfo.CAT_GAMES]).isEqualTo("GAMES");
        assertThat(AppInfo.CAT_NAMES).hasLength(AppInfo.CAT_OTHER + 1);
    }
}
