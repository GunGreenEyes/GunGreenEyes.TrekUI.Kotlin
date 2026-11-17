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
        for (int f = Skin.TOBACCONIST; f <= Skin.MIDNIGHT; f++) {
            assertThat(Prefs.layoutKeyFor(f)).isEqualTo("pipeLayout" + f);
        }
    }

    @Test
    public void headerKeys_perFaction() {
        assertThat(Prefs.headerKeyFor(Skin.COMMAND)).isEqualTo("header");
        assertThat(Prefs.headerKeyFor(Skin.COLLECTIVE)).isEqualTo("header2");
        assertThat(Prefs.headerKeyFor(Skin.MIDNIGHT)).isEqualTo("header8");
    }

    @Test
    public void clampFaction_outOfRangeIsCommand() {
        assertThat(Prefs.clampFaction(-1)).isEqualTo(Skin.COMMAND);
        assertThat(Prefs.clampFaction(Prefs.MAX_FACTION + 1)).isEqualTo(Skin.COMMAND);
        for (int f = 0; f <= Prefs.MAX_FACTION; f++) assertThat(Prefs.clampFaction(f)).isEqualTo(f);
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
