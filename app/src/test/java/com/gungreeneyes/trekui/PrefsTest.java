package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure encode/decode rules in {@link Prefs}. */
public class PrefsTest {

    private static final List<String> DEFAULT_ORDER = Arrays.asList(Prefs.ALL_WIDGETS);

    @Test
    public void widgetOrder_nullOrEmptyGivesDefault() {
        assertThat(Prefs.parseWidgetOrder(null)).isEqualTo(DEFAULT_ORDER);
        assertThat(Prefs.parseWidgetOrder("")).isEqualTo(DEFAULT_ORDER);
    }

    @Test
    public void widgetOrder_keepsUserOrderDropsUnknownAndDuplicates_appendsMissing() {
        assertThat(Prefs.parseWidgetOrder("power,bogus,media,power"))
                .containsExactly("power", "media", "weather", "calendar", "systems").inOrder();
    }

    @Test
    public void widgetOrder_roundTrips() {
        List<String> order = Arrays.asList("systems", "power", "media", "calendar", "weather");
        String raw = Prefs.joinWidgetOrder(order);
        assertThat(raw).isEqualTo("systems,power,media,calendar,weather");
        assertThat(Prefs.parseWidgetOrder(raw)).isEqualTo(order);
        assertThat(Prefs.joinWidgetOrder(Arrays.<String>asList())).isEmpty();
    }

    @Test
    public void layoutKeys_perFaction() {
        assertThat(Prefs.layoutKeyFor(Skin.COMMAND)).isEqualTo("commandLayout");
        assertThat(Prefs.layoutKeyFor(Skin.IMPERIAL)).isEqualTo("imperialLayout");
        assertThat(Prefs.layoutKeyFor(Skin.COLLECTIVE)).isEqualTo("collectiveLayout");
        assertThat(Prefs.layoutKeyFor(Skin.ERA25)).isEqualTo("commandLayout");
    }

    @Test
    public void headerKeys_perFaction() {
        assertThat(Prefs.headerKeyFor(Skin.COMMAND)).isEqualTo("header");
        assertThat(Prefs.headerKeyFor(Skin.COLLECTIVE)).isEqualTo("header2");
        assertThat(Prefs.headerKeyFor(Skin.ERA25)).isEqualTo("header10");
    }

    @Test
    public void clampFaction_outOfRangeAndRetiredAreCommand() {
        assertThat(Prefs.clampFaction(-1)).isEqualTo(Skin.COMMAND);
        assertThat(Prefs.clampFaction(Prefs.MAX_FACTION + 1)).isEqualTo(Skin.COMMAND);
        for (int f : Skin.INTERFACES) assertThat(Prefs.clampFaction(f)).isEqualTo(f);
        for (int f = Skin.FIRST_RETIRED; f <= Skin.LAST_RETIRED; f++) {
            assertThat(Prefs.clampFaction(f)).isEqualTo(Skin.COMMAND);
            assertThat(Prefs.retiredFaction(f)).isTrue();
        }
        for (int f : Skin.INTERFACES) assertThat(Prefs.retiredFaction(f)).isFalse();
    }

    @Test
    public void retiredKeys_onlyTheRemovedInterfacesKeys() {
        for (int f = Skin.FIRST_RETIRED; f <= Skin.LAST_RETIRED; f++) {
            assertThat(Prefs.retiredKey("pipeLayout" + f)).isTrue();
            assertThat(Prefs.retiredKey("header" + f)).isTrue();
            assertThat(Prefs.retiredKey("theme" + f)).isTrue();
            assertThat(Prefs.retiredKey("sndBi" + f + "_3")).isTrue();
        }
        for (String k : new String[]{"lastPipe", "lastStarship", "pipeTimerAcc", "pipeTimerRun", "pipeTimerStart"}) {
            assertThat(Prefs.retiredKey(k)).isTrue();
        }
        // the remaining interfaces' keys stay
        for (int f : Skin.INTERFACES) {
            assertThat(Prefs.retiredKey(Prefs.headerKeyFor(f))).isFalse();
            assertThat(Prefs.retiredKey(Prefs.themeKeyFor(f))).isFalse();
            assertThat(Prefs.retiredKey(Prefs.layoutKeyFor(f))).isFalse();
            assertThat(Prefs.retiredKey("sndBi" + f + "_3")).isFalse();
        }
        for (String k : new String[]{"faction", "theme", "header", "sndBi3", "sndBi10_3", "collectiveLayout", "tilesJson", null}) {
            assertThat(Prefs.retiredKey(k)).isFalse();
        }
        assertThat(LayoutArchive.isExcluded("header5")).isTrue();
        assertThat(LayoutArchive.isExcluded("header1")).isFalse();
    }

    @Test
    public void themeIndex_clamped() {
        assertThat(Prefs.themeIndex(-3)).isEqualTo(0);
        assertThat(Prefs.themeIndex(Palette.ALL.length)).isEqualTo(0);
        assertThat(Prefs.themeIndex(Palette.ALL.length - 1)).isEqualTo(Palette.ALL.length - 1);
    }

    @Test
    public void fahrenheitCountries() {
        assertThat(Prefs.defaultFahrenheit("US")).isTrue();
        assertThat(Prefs.defaultFahrenheit("LR")).isTrue();
        assertThat(Prefs.defaultFahrenheit("MM")).isTrue();
        assertThat(Prefs.defaultFahrenheit("GB")).isFalse();
        assertThat(Prefs.defaultFahrenheit("")).isFalse();
    }

    @Test
    public void toggled_addsAndRemoves() {
        Set<String> s = new HashSet<>(Arrays.asList("a"));
        assertThat(Prefs.toggled(s, "b", true)).containsExactly("a", "b");
        assertThat(Prefs.toggled(s, "a", false)).containsExactly("b");
        assertThat(Prefs.toggled(s, "zzz", false)).containsExactly("b");
    }
}
