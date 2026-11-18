package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure helpers of [IconPacks] (per-app choice format, pack list comparison) and [IconPicker]. */
class IconPacksTest {

    @Test
    fun choiceFormatRoundTrips() {
        val c = IconPacks.packChoice("com.pack.icons", "ic_chrome")
        assertThat(c).isEqualTo("pack:com.pack.icons/ic_chrome")
        assertThat(IconPacks.choicePack(c)).asList().containsExactly("com.pack.icons", "ic_chrome").inOrder()
        assertThat(IconPacks.choiceGlyph("glyph:rocket")).isEqualTo("rocket")
        assertThat(IconPacks.prefKey("com.a/.Main#0")).isEqualTo("appIcon.com.a/.Main#0")
    }

    @Test
    fun malformedChoicesAreIgnored() {
        assertThat(IconPacks.choiceGlyph("glyph:")).isNull()
        assertThat(IconPacks.choiceGlyph("pack:a/b")).isNull()
        assertThat(IconPacks.choiceGlyph(null)).isNull()
        assertThat(IconPacks.choicePack("pack:nodrawable")).isNull()
        assertThat(IconPacks.choicePack("pack:/x")).isNull()
        assertThat(IconPacks.choicePack("pack:a/")).isNull()
        assertThat(IconPacks.choicePack("app")).isNull()
        assertThat(IconPacks.choicePack(null)).isNull()
    }

    @Test
    fun samePacks_comparesPackagesInOrder() {
        val a = listOf(IconPacks.Pack("p1", "One"), IconPacks.Pack("p2", "Two"))
        val b = listOf(IconPacks.Pack("p1", "ONE"), IconPacks.Pack("p2", "TWO"))
        val c = listOf(IconPacks.Pack("p2", "Two"), IconPacks.Pack("p1", "One"))
        assertThat(IconPacks.samePacks(a, b)).isTrue()
        assertThat(IconPacks.samePacks(a, c)).isFalse()
        assertThat(IconPacks.samePacks(a, a.subList(0, 1))).isFalse()
    }

    @Test
    fun picker_columnsAtLeastThree() {
        assertThat(IconPicker.columns(100f, 60f)).isEqualTo(3)
        assertThat(IconPicker.columns(400f, 60f)).isEqualTo(6)
    }

    @Test
    fun picker_filterPutsTheAppsOwnPackIconFirstAndMatchesEveryWord() {
        val all = listOf("google_maps", "maps_alt", "chrome", "chrome_beta", "phone")
        assertThat(IconPicker.filter(all, "", "chrome", 10))
            .containsExactly("chrome", "google_maps", "maps_alt", "chrome_beta", "phone").inOrder()
        assertThat(IconPicker.filter(all, "MAPS", null, 10)).containsExactly("google_maps", "maps_alt").inOrder()
        assertThat(IconPicker.filter(all, "chrome beta", null, 10)).containsExactly("chrome_beta")
        assertThat(IconPicker.filter(all, "", null, 2)).hasSize(2)
        assertThat(IconPicker.filter(all, "", "missing", 10)).hasSize(5)
    }
}
