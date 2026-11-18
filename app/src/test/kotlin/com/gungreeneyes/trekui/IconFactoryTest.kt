package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure glyph rule matching and lookup in [IconFactory]. */
class IconFactoryTest {

    @Test
    fun firstInstalledPackageInARuleWins() {
        val m = IconFactory.matchGlyphs(
            listOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"),
        )
        assertThat(m).containsExactly("com.samsung.android.dialer", "phone")
    }

    @Test
    fun laterPackageMatchesWhenEarlierMissing() {
        val m = IconFactory.matchGlyphs(listOf("com.android.dialer", "org.mozilla.firefox"))
        assertThat(m).containsEntry("com.android.dialer", "phone")
        assertThat(m).containsEntry("org.mozilla.firefox", "browser")
    }

    @Test
    fun unknownPackagesGetNothing() {
        assertThat(IconFactory.matchGlyphs(listOf("com.example.app"))).isEmpty()
    }

    @Test
    fun everyGlyphNameIsUniqueAndEveryPackageAppearsOnce() {
        val names = HashSet<String>()
        val pkgs = HashSet<String>()
        for (rule in IconFactory.GLYPH_RULES) {
            assertThat(rule.size).isAtLeast(2)
            assertThat(names.add(rule[0])).isTrue()
            for (i in 1 until rule.size) assertThat(pkgs.add(rule[i])).isTrue()
        }
    }

    @Test
    fun glyphPath_isThePlainConsoleSet() {
        assertThat(IconFactory.glyphPath("phone")).isEqualTo("glyphs/phone.png")
    }

    @Test
    fun accentIndex_inRangeAndStable() {
        for (p in listOf("a", "com.example", "polygenelubricants", "")) {
            val i = IconFactory.accentIndex(p)
            assertThat(i).isAtLeast(0)
            assertThat(i).isLessThan(4)
            assertThat(IconFactory.accentIndex(p)).isEqualTo(i)
        }
        assertThat(IconFactory.accentIndex("polygenelubricants")).isEqualTo(0) // hashCode == Integer.MIN_VALUE
    }
}
