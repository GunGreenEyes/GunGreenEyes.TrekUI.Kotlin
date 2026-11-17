package com.gungreeneyes.trekui;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure bookkeeping for hosted Android widget panels ({@link WidgetPanel}): which widget slot holds
 * which bound {@code appWidgetId}, the panel ids those slots appear under in the panel order, and the
 * size math used to lay a widget out inside its console card.
 *
 * <p>Every slot is a home panel with the id {@code "appwidget<slot>"} (slots count from 1). The
 * slot → widget-id map is stored in {@link Prefs} under {@link #KEY_SLOTS} as {@code "1:42,2:57"}; the
 * panel ids take part in {@link Prefs#widgetOrder()} and {@link Prefs#hiddenWidgets()} exactly like
 * the built-in panels, so MOVE UP / MOVE DOWN / HIDE PANEL, CONFIG › PANELS and the reveal-hub info
 * nodes all work unchanged. Everything here except {@link #load}/{@link #save} is a static function
 * with no Android dependency, so it is unit-tested on the JVM.
 */
final class WidgetSlots {
    /** Panel-id prefix of a hosted widget panel ("appwidget3"). */
    static final String PREFIX = "appwidget";
    /** Prefs key of the encoded slot → appWidgetId map. */
    static final String KEY_SLOTS = "appWidgetSlots";
    /** Prefs key prefix of a slot's chosen height in rows ("appWidgetRows_3"). */
    static final String KEY_ROWS = "appWidgetRows_";
    /** Most widget panels a home screen can hold. */
    static final int MAX_SLOTS = 12;
    /** Height of one widget row, design dp (before the window's UI scale). */
    static final float ROW_DP = 90f;
    /** Allowed row counts for WIDGET SIZE. */
    static final int MIN_ROWS = 1, MAX_ROWS = 5;
    /** A widget panel never gets taller than this fraction of the window height. */
    static final float MAX_WINDOW_FRACTION = 0.8f;
    /** {@code AppWidgetManager.INVALID_APPWIDGET_ID}, repeated here so tests need no Android stubs. */
    static final int INVALID_ID = 0;

    private WidgetSlots() {}

    // ---- panel ids --------------------------------------------------------------------------------

    /** True for a hosted-widget panel id ("appwidget1" .. "appwidget12"). */
    static boolean isSlotId(String panelId) {
        return slotOf(panelId) > 0;
    }

    /** Panel id of {@code slot}. */
    static String panelId(int slot) {
        return PREFIX + slot;
    }

    /** Slot number of a hosted-widget panel id, or -1 when {@code panelId} is not one. */
    static int slotOf(String panelId) {
        if (panelId == null || !panelId.startsWith(PREFIX) || panelId.length() == PREFIX.length()) return -1;
        String digits = panelId.substring(PREFIX.length());
        if (digits.length() > 3) return -1;
        for (int i = 0; i < digits.length(); i++) {
            if (!Character.isDigit(digits.charAt(i))) return -1;
        }
        int slot = Integer.parseInt(digits);
        return slot >= 1 && slot <= MAX_SLOTS && digits.charAt(0) != '0' ? slot : -1;
    }

    /** Panel ids of every slot in {@code slots}, in slot order of the map. */
    static List<String> panelIds(Map<Integer, Integer> slots) {
        List<String> out = new ArrayList<>();
        for (Integer slot : slots.keySet()) out.add(panelId(slot));
        return out;
    }

    // ---- slot map encoding -----------------------------------------------------------------------

    /**
     * Decodes {@code "1:42,2:57"} into slot → appWidgetId (insertion-ordered). Malformed pairs, slots
     * outside 1..{@link #MAX_SLOTS}, invalid widget ids and repeated slots or widget ids are dropped;
     * {@code null} or blank gives an empty map.
     */
    static LinkedHashMap<Integer, Integer> parse(String raw) {
        LinkedHashMap<Integer, Integer> out = new LinkedHashMap<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String pair : raw.split(",")) {
            int colon = pair.indexOf(':');
            if (colon <= 0) continue;
            int slot, widgetId;
            try {
                slot = Integer.parseInt(pair.substring(0, colon).trim());
                widgetId = Integer.parseInt(pair.substring(colon + 1).trim());
            } catch (NumberFormatException bad) {
                continue;
            }
            if (slot < 1 || slot > MAX_SLOTS || widgetId <= INVALID_ID) continue;
            if (out.containsKey(slot) || out.containsValue(widgetId)) continue;
            out.put(slot, widgetId);
        }
        return out;
    }

    /** Encodes a slot → appWidgetId map as {@code "1:42,2:57"}. */
    static String format(Map<Integer, Integer> slots) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : slots.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    /** Lowest free slot (1-based) not in {@code used}, or -1 when all {@link #MAX_SLOTS} are taken. */
    static int nextSlot(Set<Integer> used) {
        for (int slot = 1; slot <= MAX_SLOTS; slot++) {
            if (!used.contains(slot)) return slot;
        }
        return -1;
    }

    /**
     * The widget ids the host still owns that no slot references (left behind when the process died in
     * the middle of an add flow), excluding {@code pendingId}, which an add flow is still working on
     * (pass {@link #INVALID_ID} when none is). These are deleted so the system does not keep updating them forever.
     */
    static List<Integer> orphans(int[] hostIds, Collection<Integer> kept, int pendingId) {
        List<Integer> out = new ArrayList<>();
        if (hostIds == null) return out;
        for (int id : hostIds) {
            if (id != pendingId && !kept.contains(id)) out.add(id);
        }
        return out;
    }

    /**
     * The slot map after a backup restore renumbered the host's widget ids
     * ({@code AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED}): every slot whose id is
     * {@code oldIds[i]} now holds {@code newIds[i]}; slots with ids not in {@code oldIds} keep theirs.
     * Null or mismatched arrays leave the map unchanged. The result keeps the slot order.
     */
    static LinkedHashMap<Integer, Integer> remap(Map<Integer, Integer> slots, int[] oldIds, int[] newIds) {
        LinkedHashMap<Integer, Integer> out = new LinkedHashMap<>(slots);
        if (oldIds == null || newIds == null || oldIds.length != newIds.length) return out;
        for (Map.Entry<Integer, Integer> e : out.entrySet()) {
            for (int i = 0; i < oldIds.length; i++) {
                if (e.getValue() == oldIds[i]) {
                    e.setValue(newIds[i]);
                    break;
                }
            }
        }
        return out;
    }

    // ---- sizing ----------------------------------------------------------------------------------

    /** Clamps a stored row count into {@link #MIN_ROWS}..{@link #MAX_ROWS}. */
    static int clampRows(int rows) {
        return Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows));
    }

    /** Default row count for a widget whose provider asks for at least {@code minHeightDp}. */
    static int rowsFor(float minHeightDp) {
        if (!(minHeightDp > 0f)) return 2;
        return clampRows((int) Math.ceil(minHeightDp / ROW_DP));
    }

    /**
     * Height (dp) of a widget panel's widget area: {@code rows} × {@link #ROW_DP} scaled by the window's
     * UI scale, but never taller than {@link #MAX_WINDOW_FRACTION} of the window (landscape phones).
     */
    static float heightDp(int rows, float uiScale, float windowHeightDp) {
        float scale = uiScale > 0f ? uiScale : 1f;
        float h = clampRows(rows) * ROW_DP * scale;
        if (windowHeightDp > 0f) h = Math.min(h, windowHeightDp * MAX_WINDOW_FRACTION);
        return Math.max(ROW_DP * 0.5f, h);
    }

    /** Converts a laid-out pixel size to the whole dp {width, height} reported to the widget provider. */
    static int[] sizeDp(int widthPx, int heightPx, float density) {
        float d = density > 0f ? density : 1f;
        return new int[]{Math.max(0, Math.round(widthPx / d)), Math.max(0, Math.round(heightPx / d))};
    }

    /** "WIDGET 3" style fallback name of a slot (hub node label, CONFIG row, offline card title). */
    static String fallbackName(int slot) {
        return String.format(Locale.US, "WIDGET %d", slot);
    }

    // ---- persistence (thin Prefs wrappers) ---------------------------------------------------------

    /** The stored slot → appWidgetId map. */
    static LinkedHashMap<Integer, Integer> load(Prefs prefs) {
        return parse(prefs.s(KEY_SLOTS, null));
    }

    /** Stores the slot map and marks the home layout stale so it rebuilds. */
    static void save(Prefs prefs, Map<Integer, Integer> slots) {
        prefs.put(KEY_SLOTS, format(slots));
        prefs.bump();
    }

    /** The widget area height, in rows, chosen for {@code slot} (0 when the user never chose one). */
    static int storedRows(Prefs prefs, int slot) {
        return prefs.i(KEY_ROWS + slot, 0);
    }
}
