package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure encode/decode rules in [Prefs] (and the faction rules it inherits from [TrekPrefs]). */
class PrefsTest {

    @Test
    fun widgetOrder_nullOrEmptyGivesDefault() {
        assertThat(Prefs.parseWidgetOrder(null)).isEqualTo(DEFAULT_ORDER)
        assertThat(Prefs.parseWidgetOrder("")).isEqualTo(DEFAULT_ORDER)
    }

    @Test
    fun widgetOrder_keepsUserOrderDropsUnknownAndDuplicates_appendsMissing() {
        assertThat(Prefs.parseWidgetOrder("power,bogus,media,power"))
            .containsExactly("power", "media", "weather", "calendar", "systems").inOrder()
    }

    @Test
    fun widgetOrder_roundTrips() {
        val order = listOf("systems", "power", "media", "calendar", "weather")
        val raw = Prefs.joinWidgetOrder(order)
        assertThat(raw).isEqualTo("systems,power,media,calendar,weather")
        assertThat(Prefs.parseWidgetOrder(raw)).isEqualTo(order)
        assertThat(Prefs.joinWidgetOrder(emptyList())).isEmpty()
    }

    @Test
    fun layoutKeys_perFaction() {
        assertThat(Prefs.layoutKeyFor(Skin.COMMAND)).isEqualTo("commandLayout")
        assertThat(Prefs.layoutKeyFor(Skin.IMPERIAL)).isEqualTo("imperialLayout")
        assertThat(Prefs.layoutKeyFor(Skin.COLLECTIVE)).isEqualTo("collectiveLayout")
        assertThat(Prefs.layoutKeyFor(Skin.ERA25)).isEqualTo("commandLayout")
    }

    @Test
    fun headerKeys_perFaction() {
        assertThat(Prefs.headerKeyFor(Skin.COMMAND)).isEqualTo("header")
        assertThat(Prefs.headerKeyFor(Skin.COLLECTIVE)).isEqualTo("header2")
        assertThat(Prefs.headerKeyFor(Skin.ERA25)).isEqualTo("header10")
    }

    @Test
    fun clampFaction_outOfRangeAndRetiredAreCommand() {
        assertThat(TrekPrefs.clampFaction(-1)).isEqualTo(Skin.COMMAND)
        assertThat(TrekPrefs.clampFaction(TrekPrefs.MAX_FACTION + 1)).isEqualTo(Skin.COMMAND)
        for (f in Skin.INTERFACES) assertThat(TrekPrefs.clampFaction(f)).isEqualTo(f)
        for (f in Skin.FIRST_RETIRED..Skin.LAST_RETIRED) {
            assertThat(TrekPrefs.clampFaction(f)).isEqualTo(Skin.COMMAND)
            assertThat(TrekPrefs.retiredFaction(f)).isTrue()
        }
        for (f in Skin.INTERFACES) assertThat(TrekPrefs.retiredFaction(f)).isFalse()
    }

    @Test
    fun retiredKeys_onlyTheRemovedInterfacesKeys() {
        for (f in Skin.FIRST_RETIRED..Skin.LAST_RETIRED) {
            assertThat(Prefs.retiredKey("pipeLayout$f")).isTrue()
            assertThat(Prefs.retiredKey("header$f")).isTrue()
            assertThat(Prefs.retiredKey("theme$f")).isTrue()
            assertThat(Prefs.retiredKey("sndBi${f}_3")).isTrue()
        }
        for (k in arrayOf("lastPipe", "lastStarship", "pipeTimerAcc", "pipeTimerRun", "pipeTimerStart")) {
            assertThat(Prefs.retiredKey(k)).isTrue()
        }
        // the remaining interfaces' keys stay
        for (f in Skin.INTERFACES) {
            assertThat(Prefs.retiredKey(Prefs.headerKeyFor(f))).isFalse()
            assertThat(Prefs.retiredKey(TrekPrefs.themeKeyFor(f))).isFalse()
            assertThat(Prefs.retiredKey(Prefs.layoutKeyFor(f))).isFalse()
            assertThat(Prefs.retiredKey("sndBi${f}_3")).isFalse()
        }
        for (k in arrayOf("faction", "theme", "header", "sndBi3", "sndBi10_3", "collectiveLayout", "tilesJson", null)) {
            assertThat(Prefs.retiredKey(k)).isFalse()
        }
        assertThat(LayoutArchive.isExcluded("header5")).isTrue()
        assertThat(LayoutArchive.isExcluded("header1")).isFalse()
    }

    @Test
    fun themeIndex_clamped() {
        assertThat(TrekPrefs.themeIndex(-3)).isEqualTo(0)
        assertThat(TrekPrefs.themeIndex(Palette.ALL.size)).isEqualTo(0)
        assertThat(TrekPrefs.themeIndex(Palette.ALL.size - 1)).isEqualTo(Palette.ALL.size - 1)
    }

    @Test
    fun fahrenheitCountries() {
        assertThat(Prefs.defaultFahrenheit("US")).isTrue()
        assertThat(Prefs.defaultFahrenheit("LR")).isTrue()
        assertThat(Prefs.defaultFahrenheit("MM")).isTrue()
        assertThat(Prefs.defaultFahrenheit("GB")).isFalse()
        assertThat(Prefs.defaultFahrenheit("")).isFalse()
    }

    @Test
    fun toggled_addsAndRemoves() {
        val s = hashSetOf("a")
        assertThat(Prefs.toggled(s, "b", true)).containsExactly("a", "b")
        assertThat(Prefs.toggled(s, "a", false)).containsExactly("b")
        assertThat(Prefs.toggled(s, "zzz", false)).containsExactly("b")
    }

    private companion object {
        val DEFAULT_ORDER: List<String> = Prefs.ALL_WIDGETS.toList()
    }
}
