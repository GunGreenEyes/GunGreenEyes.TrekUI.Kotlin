package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Palette index wrapping and the Command theme table; also [SegBar.filledSegments]. */
class PaletteTest {

    @Test
    fun wrapIndex_wrapsBothWays() {
        assertThat(Palette.wrapIndex(0, 6)).isEqualTo(0)
        assertThat(Palette.wrapIndex(7, 6)).isEqualTo(1)
        assertThat(Palette.wrapIndex(-1, 6)).isEqualTo(5)
        assertThat(Palette.wrapIndex(-13, 6)).isEqualTo(5)
    }

    @Test
    fun everyThemeHasSixAccents() {
        assertThat(Palette.ALL).hasLength(SubThemes.COMMAND.size) // 6 originals + 5 added in LCA-T35
        for (p in Palette.ALL) assertThat(p.c).hasLength(6)
    }

    @Test
    fun segBar_filledSegments() {
        assertThat(SegBar.filledSegments(0f, 10)).isEqualTo(0)
        assertThat(SegBar.filledSegments(0.01f, 10)).isEqualTo(1) // any charge shows one segment
        assertThat(SegBar.filledSegments(0.5f, 10)).isEqualTo(5)
        assertThat(SegBar.filledSegments(1f, 10)).isEqualTo(10)
    }
}
