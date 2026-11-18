package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [MediaPanel] source button names (pure JVM). */
class MediaPanelTest {

    @Test
    fun knownName() {
        assertThat(MediaPanel.knownName("com.google.android.apps.youtube.music")).isEqualTo("YT MUSIC")
        assertThat(MediaPanel.knownName("org.videolan.vlc")).isEqualTo("VLC")
        assertThat(MediaPanel.knownName("com.example.player")).isNull()
    }

    @Test
    fun abbreviate() {
        assertThat(MediaPanel.abbreviate(" Pocket Casts ")).isEqualTo("POCKET CAS")
        assertThat(MediaPanel.abbreviate("plex")).isEqualTo("PLEX")
        assertThat(MediaPanel.abbreviate(null)).isEmpty()
    }

    @Test
    fun defaultsMatchTheSlots() {
        assertThat(MediaPanel.DEFAULT_SOURCES).hasLength(MediaPanel.SLOTS)
    }
}
