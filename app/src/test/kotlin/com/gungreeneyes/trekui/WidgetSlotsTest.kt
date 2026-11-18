package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Slot persistence, panel-id and size math of hosted widget panels ([WidgetSlots]). */
class WidgetSlotsTest {

    @Test
    fun panelIdsRoundTripThroughSlotOf() {
        for (slot in 1..WidgetSlots.MAX_SLOTS) {
            assertThat(WidgetSlots.slotOf(WidgetSlots.panelId(slot))).isEqualTo(slot)
            assertThat(WidgetSlots.isSlotId(WidgetSlots.panelId(slot))).isTrue()
        }
    }

    @Test
    fun builtInAndMalformedIdsAreNotSlots() {
        for (id in listOf(
            null, "", "weather", "appwidget", "appwidget0", "appwidget01",
            "appwidget13", "appwidget-1", "appwidgetx", "appwidget1000", "xappwidget1",
        )) {
            assertThat(WidgetSlots.slotOf(id)).isEqualTo(-1)
            assertThat(WidgetSlots.isSlotId(id)).isFalse()
        }
    }

    @Test
    fun slotMapRoundTrips() {
        val slots = LinkedHashMap<Int, Int>()
        slots[2] = 57
        slots[1] = 42
        slots[12] = 7
        val raw = WidgetSlots.format(slots)
        assertThat(raw).isEqualTo("2:57,1:42,12:7")
        assertThat(WidgetSlots.parse(raw)).containsExactlyEntriesIn(slots).inOrder()
    }

    @Test
    fun parseDropsBadPairsAndDuplicates() {
        val slots = WidgetSlots.parse(" 1:42, junk ,2:x,:5,3:0,13:9,0:4,1:99,4:42,5 : 77")
        assertThat(slots).containsExactly(1, 42, 5, 77).inOrder()
    }

    @Test
    fun parseEmptyGivesEmptyMap() {
        assertThat(WidgetSlots.parse(null)).isEmpty()
        assertThat(WidgetSlots.parse("")).isEmpty()
        assertThat(WidgetSlots.format(LinkedHashMap())).isEmpty()
    }

    @Test
    fun nextSlotFillsGapsAndStopsWhenFull() {
        assertThat(WidgetSlots.nextSlot(HashSet())).isEqualTo(1)
        assertThat(WidgetSlots.nextSlot(hashSetOf(1, 2, 4))).isEqualTo(3)
        val full = HashSet<Int>()
        for (i in 1..WidgetSlots.MAX_SLOTS) full.add(i)
        assertThat(WidgetSlots.nextSlot(full)).isEqualTo(-1)
    }

    @Test
    fun orphansAreUnreferencedIdsExceptThePendingOne() {
        val hostIds = intArrayOf(3, 5, 8, 9)
        assertThat(WidgetSlots.orphans(hostIds, listOf(5, 9), 8)).containsExactly(3)
        assertThat(WidgetSlots.orphans(hostIds, listOf(5, 9), WidgetSlots.INVALID_ID)).containsExactly(3, 8).inOrder()
        assertThat(WidgetSlots.orphans(null, emptyList(), 0)).isEmpty()
    }

    @Test
    fun panelIdsFollowTheMapOrder() {
        val slots = LinkedHashMap<Int, Int>()
        slots[3] = 30
        slots[1] = 10
        assertThat(WidgetSlots.panelIds(slots)).containsExactly("appwidget3", "appwidget1").inOrder()
    }

    @Test
    fun widgetOrderKeepsSlotPositionsAndAppendsNewSlots() {
        val extras = listOf("appwidget1", "appwidget2")
        val order = Prefs.parseWidgetOrder("media,appwidget2,weather,bogus,appwidget9", extras)
        assertThat(order).containsExactly(
            "media", "appwidget2", "weather",
            "calendar", "power", "systems", "appwidget1",
        ).inOrder()
    }

    @Test
    fun widgetOrderDropsRemovedSlots() {
        val order = Prefs.parseWidgetOrder("appwidget1,weather", emptyList())
        assertThat(order).doesNotContain("appwidget1")
        assertThat(order).containsExactlyElementsIn(Prefs.ALL_WIDGETS.asList())
    }

    @Test
    fun rowsForMinimumHeight() {
        assertThat(WidgetSlots.rowsFor(0f)).isEqualTo(2)
        assertThat(WidgetSlots.rowsFor(Float.NaN)).isEqualTo(2)
        assertThat(WidgetSlots.rowsFor(40f)).isEqualTo(1)
        assertThat(WidgetSlots.rowsFor(WidgetSlots.ROW_DP)).isEqualTo(1)
        assertThat(WidgetSlots.rowsFor(WidgetSlots.ROW_DP + 1f)).isEqualTo(2)
        assertThat(WidgetSlots.rowsFor(10_000f)).isEqualTo(WidgetSlots.MAX_ROWS)
    }

    @Test
    fun clampRowsKeepsTheRange() {
        assertThat(WidgetSlots.clampRows(-3)).isEqualTo(WidgetSlots.MIN_ROWS)
        assertThat(WidgetSlots.clampRows(3)).isEqualTo(3)
        assertThat(WidgetSlots.clampRows(99)).isEqualTo(WidgetSlots.MAX_ROWS)
    }

    @Test
    fun heightScalesWithUiScaleAndIsCappedByTheWindow() {
        assertThat(WidgetSlots.heightDp(2, 1f, 800f)).isWithin(0.01f).of(2 * WidgetSlots.ROW_DP)
        assertThat(WidgetSlots.heightDp(2, 1.5f, 2000f)).isWithin(0.01f).of(3 * WidgetSlots.ROW_DP)
        // landscape phone: 5 rows would not fit, so 80% of the window
        assertThat(WidgetSlots.heightDp(5, 1f, 360f)).isWithin(0.01f).of(360f * WidgetSlots.MAX_WINDOW_FRACTION)
        // unknown scale or window: unscaled and uncapped
        assertThat(WidgetSlots.heightDp(1, 0f, 0f)).isWithin(0.01f).of(WidgetSlots.ROW_DP)
        // tiny windows still get a usable strip
        assertThat(WidgetSlots.heightDp(1, 1f, 20f)).isWithin(0.01f).of(WidgetSlots.ROW_DP * 0.5f)
    }

    @Test
    fun sizeDpRoundsPixelsByDensity() {
        assertThat(WidgetSlots.sizeDp(1080, 540, 3f)).asList().containsExactly(360, 180).inOrder()
        assertThat(WidgetSlots.sizeDp(1001, 499, 2.625f)).asList().containsExactly(381, 190).inOrder()
        assertThat(WidgetSlots.sizeDp(100, 50, 0f)).asList().containsExactly(100, 50).inOrder()
        assertThat(WidgetSlots.sizeDp(-5, 10, 1f)).asList().containsExactly(0, 10).inOrder()
    }

    @Test
    fun fallbackNamesAndPanelTitles() {
        assertThat(WidgetSlots.fallbackName(3)).isEqualTo("WIDGET 3")
        assertThat(HomeLogic.shortPanelName("appwidget3")).isEqualTo("WIDGET 3")
        assertThat(HomeLogic.shortPanelName("weather")).isEqualTo("WEATHER")
        assertThat(SettingsLogic.panelTitle("appwidget12")).isEqualTo("ANDROID WIDGET 12")
    }

    @Test
    fun remapRenumbersRestoredIdsAndKeepsOthers() {
        val slots = LinkedHashMap<Int, Int>()
        slots[1] = 42
        slots[2] = 57
        slots[3] = 99
        val out = WidgetSlots.remap(slots, intArrayOf(42, 57), intArrayOf(7, 8))
        assertThat(out).containsExactly(1, 7, 2, 8, 3, 99).inOrder()
        assertThat(slots[1]).isEqualTo(42) // input untouched
    }

    @Test
    fun remapIgnoresMissingOrMismatchedArrays() {
        val slots = LinkedHashMap<Int, Int>()
        slots[1] = 42
        assertThat(WidgetSlots.remap(slots, null, intArrayOf(1))).isEqualTo(slots)
        assertThat(WidgetSlots.remap(slots, intArrayOf(42), intArrayOf(1, 2))).isEqualTo(slots)
    }
}
