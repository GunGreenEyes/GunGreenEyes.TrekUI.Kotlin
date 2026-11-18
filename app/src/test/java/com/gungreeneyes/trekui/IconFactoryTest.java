package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/** Pure glyph rule matching and lookup in {@link IconFactory}. */
public class IconFactoryTest {

    @Test
    public void firstInstalledPackageInARuleWins() {
        Map<String, String> m = IconFactory.matchGlyphs(Arrays.asList(
                "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer"));
        assertThat(m).containsExactly("com.samsung.android.dialer", "phone");
    }

    @Test
    public void laterPackageMatchesWhenEarlierMissing() {
        Map<String, String> m = IconFactory.matchGlyphs(Arrays.asList("com.android.dialer", "org.mozilla.firefox"));
        assertThat(m).containsEntry("com.android.dialer", "phone");
        assertThat(m).containsEntry("org.mozilla.firefox", "browser");
    }

    @Test
    public void unknownPackagesGetNothing() {
        assertThat(IconFactory.matchGlyphs(Arrays.asList("com.example.app"))).isEmpty();
    }

    @Test
    public void everyGlyphNameIsUniqueAndEveryPackageAppearsOnce() {
        Set<String> names = new HashSet<>();
        Set<String> pkgs = new HashSet<>();
        for (String[] rule : IconFactory.GLYPH_RULES) {
            assertThat(rule.length).isAtLeast(2);
            assertThat(names.add(rule[0])).isTrue();
            for (int i = 1; i < rule.length; i++) assertThat(pkgs.add(rule[i])).isTrue();
        }
    }

    @Test
    public void glyphPath_isThePlainConsoleSet() {
        assertThat(IconFactory.glyphPath("phone")).isEqualTo("glyphs/phone.png");
    }

    @Test
    public void accentIndex_inRangeAndStable() {
        for (String p : Arrays.asList("a", "com.example", "polygenelubricants", "")) {
            int i = IconFactory.accentIndex(p);
            assertThat(i).isAtLeast(0);
            assertThat(i).isLessThan(4);
            assertThat(IconFactory.accentIndex(p)).isEqualTo(i);
        }
        assertThat(IconFactory.accentIndex("polygenelubricants")).isEqualTo(0); // hashCode == Integer.MIN_VALUE
    }
}
