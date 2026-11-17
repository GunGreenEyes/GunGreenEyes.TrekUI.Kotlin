package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Pure helpers of {@link IconPacks} (per-app choice format, pack list comparison) and {@link IconPicker}. */
public class IconPacksTest {

    @Test
    public void choiceFormatRoundTrips() {
        String c = IconPacks.packChoice("com.pack.icons", "ic_chrome");
        assertThat(c).isEqualTo("pack:com.pack.icons/ic_chrome");
        assertThat(IconPacks.choicePack(c)).asList().containsExactly("com.pack.icons", "ic_chrome").inOrder();
        assertThat(IconPacks.choiceGlyph("glyph:rocket")).isEqualTo("rocket");
        assertThat(IconPacks.prefKey("com.a/.Main#0")).isEqualTo("appIcon.com.a/.Main#0");
    }

    @Test
    public void malformedChoicesAreIgnored() {
        assertThat(IconPacks.choiceGlyph("glyph:")).isNull();
        assertThat(IconPacks.choiceGlyph("pack:a/b")).isNull();
        assertThat(IconPacks.choiceGlyph(null)).isNull();
        assertThat(IconPacks.choicePack("pack:nodrawable")).isNull();
        assertThat(IconPacks.choicePack("pack:/x")).isNull();
        assertThat(IconPacks.choicePack("pack:a/")).isNull();
        assertThat(IconPacks.choicePack("app")).isNull();
        assertThat(IconPacks.choicePack(null)).isNull();
    }

    @Test
    public void samePacks_comparesPackagesInOrder() {
        List<IconPacks.Pack> a = Arrays.asList(new IconPacks.Pack("p1", "One"), new IconPacks.Pack("p2", "Two"));
        List<IconPacks.Pack> b = Arrays.asList(new IconPacks.Pack("p1", "ONE"), new IconPacks.Pack("p2", "TWO"));
        List<IconPacks.Pack> c = Arrays.asList(new IconPacks.Pack("p2", "Two"), new IconPacks.Pack("p1", "One"));
        assertThat(IconPacks.samePacks(a, b)).isTrue();
        assertThat(IconPacks.samePacks(a, c)).isFalse();
        assertThat(IconPacks.samePacks(a, a.subList(0, 1))).isFalse();
    }

    @Test
    public void picker_columnsAtLeastThree() {
        assertThat(IconPicker.columns(100f, 60f)).isEqualTo(3);
        assertThat(IconPicker.columns(400f, 60f)).isEqualTo(6);
    }

    @Test
    public void picker_filterPutsTheAppsOwnPackIconFirstAndMatchesEveryWord() {
        List<String> all = Arrays.asList("google_maps", "maps_alt", "chrome", "chrome_beta", "phone");
        assertThat(IconPicker.filter(all, "", "chrome", 10)).containsExactly("chrome", "google_maps", "maps_alt", "chrome_beta", "phone").inOrder();
        assertThat(IconPicker.filter(all, "MAPS", null, 10)).containsExactly("google_maps", "maps_alt").inOrder();
        assertThat(IconPicker.filter(all, "chrome beta", null, 10)).containsExactly("chrome_beta");
        assertThat(IconPicker.filter(all, "", null, 2)).hasSize(2);
        assertThat(IconPicker.filter(all, "", "missing", 10)).hasSize(5);
    }
}
