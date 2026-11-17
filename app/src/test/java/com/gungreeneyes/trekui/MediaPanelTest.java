package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** {@link MediaPanel} source button names (pure JVM). */
public class MediaPanelTest {

    @Test
    public void knownName() {
        assertThat(MediaPanel.knownName("com.google.android.apps.youtube.music")).isEqualTo("YT MUSIC");
        assertThat(MediaPanel.knownName("org.videolan.vlc")).isEqualTo("VLC");
        assertThat(MediaPanel.knownName("com.example.player")).isNull();
    }

    @Test
    public void abbreviate() {
        assertThat(MediaPanel.abbreviate(" Pocket Casts ")).isEqualTo("POCKET CAS");
        assertThat(MediaPanel.abbreviate("plex")).isEqualTo("PLEX");
        assertThat(MediaPanel.abbreviate(null)).isEmpty();
    }

    @Test
    public void defaultsMatchTheSlots() {
        assertThat(MediaPanel.DEFAULT_SOURCES).hasLength(MediaPanel.SLOTS);
    }
}
