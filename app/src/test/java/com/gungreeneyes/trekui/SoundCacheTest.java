package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** JVM tests for the sound resolution order: downloaded cache, then res/raw, then silence. */
public class SoundCacheTest {

    @Test
    public void downloadedClipWinsOverBundledResource() {
        assertThat(SoundCache.resolve(true, 0x7f100001)).isEqualTo(SoundCache.CACHE);
        assertThat(SoundCache.resolve(true, 0)).isEqualTo(SoundCache.CACHE);
    }

    @Test
    public void bundledResourceIsUsedWhenNothingDownloaded() {
        assertThat(SoundCache.resolve(false, 0x7f100001)).isEqualTo(SoundCache.RAW);
    }

    @Test
    public void nothingAnywhereIsSilence() {
        // the production default: no recordings shipped, none downloaded
        assertThat(SoundCache.resolve(false, 0)).isEqualTo(SoundCache.NONE);
    }
}
