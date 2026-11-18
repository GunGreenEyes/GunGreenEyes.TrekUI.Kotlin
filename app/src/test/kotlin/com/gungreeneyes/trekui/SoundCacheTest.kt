package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** JVM tests for the sound resolution order: downloaded cache, then res/raw, then silence. */
class SoundCacheTest {

    @Test
    fun downloadedClipWinsOverBundledResource() {
        assertThat(SoundCache.resolve(true, 0x7f100001)).isEqualTo(SoundCache.CACHE)
        assertThat(SoundCache.resolve(true, 0)).isEqualTo(SoundCache.CACHE)
    }

    @Test
    fun bundledResourceIsUsedWhenNothingDownloaded() {
        assertThat(SoundCache.resolve(false, 0x7f100001)).isEqualTo(SoundCache.RAW)
    }

    @Test
    fun nothingAnywhereIsSilence() {
        // the production default: no recordings shipped, none downloaded
        assertThat(SoundCache.resolve(false, 0)).isEqualTo(SoundCache.NONE)
    }
}
