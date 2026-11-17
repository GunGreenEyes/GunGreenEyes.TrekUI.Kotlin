package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Slot persistence, panel-id and size math of hosted widget panels ({@link WidgetSlots}). */
public class WidgetSlotsTest {

    @Test
    public void panelIdsRoundTripThroughSlotOf() {
        for (int slot = 1; slot <= WidgetSlots.MAX_SLOTS; slot++) {
            assertThat(WidgetSlots.slotOf(WidgetSlots.panelId(slot))).isEqualTo(slot);
            assertThat(WidgetSlots.isSlotId(WidgetSlots.panelId(slot))).isTrue();
        }
    }

    @Test
    public void builtInAndMalformedIdsAreNotSlots() {
        for (String id : Arrays.asList(null, "", "weather", "appwidget", "appwidget0", "appwidget01",
                "appwidget13", "appwidget-1", "appwidgetx", "appwidget1000", "xappwidget1")) {
            assertThat(WidgetSlots.slotOf(id)).isEqualTo(-1);
            assertThat(WidgetSlots.isSlotId(id)).isFalse();
        }
    }

    @Test
    public void slotMapRoundTrips() {
        LinkedHashMap<Integer, Integer> slots = new LinkedHashMap<>();
        slots.put(2, 57);
        slots.put(1, 42);
        slots.put(12, 7);
        String raw = WidgetSlots.format(slots);
        assertThat(raw).isEqualTo("2:57,1:42,12:7");
        assertThat(WidgetSlots.parse(raw)).containsExactlyEntriesIn(slots).inOrder();
    }

    @Test
    public void parseDropsBadPairsAndDuplicates() {
        Map<Integer, Integer> slots = WidgetSlots.parse(" 1:42, junk ,2:x,:5,3:0,13:9,0:4,1:99,4:42,5 : 77");
        assertThat(slots).containsExactly(1, 42, 5, 77).inOrder();
    }

    @Test
    public void parseEmptyGivesEmptyMap() {
        assertThat(WidgetSlots.parse(null)).isEmpty();
        assertThat(WidgetSlots.parse("")).isEmpty();
        assertThat(WidgetSlots.format(new LinkedHashMap<>())).isEmpty();
    }

    @Test
    public void nextSlotFillsGapsAndStopsWhenFull() {
        assertThat(WidgetSlots.nextSlot(new HashSet<>())).isEqualTo(1);
        assertThat(WidgetSlots.nextSlot(new HashSet<>(Arrays.asList(1, 2, 4)))).isEqualTo(3);
        HashSet<Integer> full = new HashSet<>();
        for (int i = 1; i <= WidgetSlots.MAX_SLOTS; i++) full.add(i);
        assertThat(WidgetSlots.nextSlot(full)).isEqualTo(-1);
    }

    @Test
    public void orphansAreUnreferencedIdsExceptThePendingOne() {
        int[] hostIds = {3, 5, 8, 9};
        assertThat(WidgetSlots.orphans(hostIds, Arrays.asList(5, 9), 8)).containsExactly(3);
        assertThat(WidgetSlots.orphans(hostIds, Arrays.asList(5, 9), WidgetSlots.INVALID_ID)).containsExactly(3, 8).inOrder();
        assertThat(WidgetSlots.orphans(null, Collections.<Integer>emptyList(), 0)).isEmpty();
    }

    @Test
    public void panelIdsFollowTheMapOrder() {
        LinkedHashMap<Integer, Integer> slots = new LinkedHashMap<>();
        slots.put(3, 30);
        slots.put(1, 10);
        assertThat(WidgetSlots.panelIds(slots)).containsExactly("appwidget3", "appwidget1").inOrder();
    }

    @Test
    public void widgetOrderKeepsSlotPositionsAndAppendsNewSlots() {
        List<String> extras = Arrays.asList("appwidget1", "appwidget2");
        List<String> order = Prefs.parseWidgetOrder("media,appwidget2,weather,bogus,appwidget9", extras);
        assertThat(order).containsExactly("media", "appwidget2", "weather",
                "calendar", "power", "systems", "appwidget1").inOrder();
    }

    @Test
    public void widgetOrderDropsRemovedSlots() {
        List<String> order = Prefs.parseWidgetOrder("appwidget1,weather", Collections.<String>emptyList());
        assertThat(order).doesNotContain("appwidget1");
        assertThat(order).containsExactlyElementsIn(Arrays.asList(Prefs.ALL_WIDGETS));
    }

    @Test
    public void rowsForMinimumHeight() {
        assertThat(WidgetSlots.rowsFor(0f)).isEqualTo(2);
        assertThat(WidgetSlots.rowsFor(Float.NaN)).isEqualTo(2);
        assertThat(WidgetSlots.rowsFor(40f)).isEqualTo(1);
        assertThat(WidgetSlots.rowsFor(WidgetSlots.ROW_DP)).isEqualTo(1);
        assertThat(WidgetSlots.rowsFor(WidgetSlots.ROW_DP + 1f)).isEqualTo(2);
        assertThat(WidgetSlots.rowsFor(10_000f)).isEqualTo(WidgetSlots.MAX_ROWS);
    }

    @Test
    public void clampRowsKeepsTheRange() {
        assertThat(WidgetSlots.clampRows(-3)).isEqualTo(WidgetSlots.MIN_ROWS);
        assertThat(WidgetSlots.clampRows(3)).isEqualTo(3);
        assertThat(WidgetSlots.clampRows(99)).isEqualTo(WidgetSlots.MAX_ROWS);
    }

    @Test
    public void heightScalesWithUiScaleAndIsCappedByTheWindow() {
        assertThat(WidgetSlots.heightDp(2, 1f, 800f)).isWithin(0.01f).of(2 * WidgetSlots.ROW_DP);
        assertThat(WidgetSlots.heightDp(2, 1.5f, 2000f)).isWithin(0.01f).of(3 * WidgetSlots.ROW_DP);
        // landscape phone: 5 rows would not fit, so 80% of the window
        assertThat(WidgetSlots.heightDp(5, 1f, 360f)).isWithin(0.01f).of(360f * WidgetSlots.MAX_WINDOW_FRACTION);
        // unknown scale or window: unscaled and uncapped
        assertThat(WidgetSlots.heightDp(1, 0f, 0f)).isWithin(0.01f).of(WidgetSlots.ROW_DP);
        // tiny windows still get a usable strip
        assertThat(WidgetSlots.heightDp(1, 1f, 20f)).isWithin(0.01f).of(WidgetSlots.ROW_DP * 0.5f);
    }

    @Test
    public void sizeDpRoundsPixelsByDensity() {
        assertThat(WidgetSlots.sizeDp(1080, 540, 3f)).asList().containsExactly(360, 180).inOrder();
        assertThat(WidgetSlots.sizeDp(1001, 499, 2.625f)).asList().containsExactly(381, 190).inOrder();
        assertThat(WidgetSlots.sizeDp(100, 50, 0f)).asList().containsExactly(100, 50).inOrder();
        assertThat(WidgetSlots.sizeDp(-5, 10, 1f)).asList().containsExactly(0, 10).inOrder();
    }

    @Test
    public void fallbackNamesAndPanelTitles() {
        assertThat(WidgetSlots.fallbackName(3)).isEqualTo("WIDGET 3");
        assertThat(HomeLogic.shortPanelName("appwidget3")).isEqualTo("WIDGET 3");
        assertThat(HomeLogic.shortPanelName("weather")).isEqualTo("WEATHER");
        assertThat(SettingsLogic.panelTitle("appwidget12")).isEqualTo("ANDROID WIDGET 12");
    }

    @Test
    public void remapRenumbersRestoredIdsAndKeepsOthers() {
        LinkedHashMap<Integer, Integer> slots = new LinkedHashMap<>();
        slots.put(1, 42);
        slots.put(2, 57);
        slots.put(3, 99);
        Map<Integer, Integer> out = WidgetSlots.remap(slots, new int[]{42, 57}, new int[]{7, 8});
        assertThat(out).containsExactly(1, 7, 2, 8, 3, 99).inOrder();
        assertThat(slots.get(1)).isEqualTo(42); // input untouched
    }

    @Test
    public void remapIgnoresMissingOrMismatchedArrays() {
        LinkedHashMap<Integer, Integer> slots = new LinkedHashMap<>();
        slots.put(1, 42);
        assertThat(WidgetSlots.remap(slots, null, new int[]{1})).isEqualTo(slots);
        assertThat(WidgetSlots.remap(slots, new int[]{42}, new int[]{1, 2})).isEqualTo(slots);
    }
}
