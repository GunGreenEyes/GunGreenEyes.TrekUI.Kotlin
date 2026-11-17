package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** Palette index wrapping and the Command theme table; also {@link SegBar#filledSegments}. */
public class PaletteTest {

    @Test
    public void wrapIndex_wrapsBothWays() {
        assertThat(Palette.wrapIndex(0, 6)).isEqualTo(0);
        assertThat(Palette.wrapIndex(7, 6)).isEqualTo(1);
        assertThat(Palette.wrapIndex(-1, 6)).isEqualTo(5);
        assertThat(Palette.wrapIndex(-13, 6)).isEqualTo(5);
    }

    @Test
    public void everyThemeHasSixAccents() {
        assertThat(Palette.ALL).hasLength(SubThemes.COMMAND.length); // 6 originals + 5 added in LCA-T35
        for (Palette p : Palette.ALL) assertThat(p.c).hasLength(6);
    }

    @Test
    public void segBar_filledSegments() {
        assertThat(SegBar.filledSegments(0f, 10)).isEqualTo(0);
        assertThat(SegBar.filledSegments(0.01f, 10)).isEqualTo(1); // any charge shows one segment
        assertThat(SegBar.filledSegments(0.5f, 10)).isEqualTo(5);
        assertThat(SegBar.filledSegments(1f, 10)).isEqualTo(10);
    }
}
