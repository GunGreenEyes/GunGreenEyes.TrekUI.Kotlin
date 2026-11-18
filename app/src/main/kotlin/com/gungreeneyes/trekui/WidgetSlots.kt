package com.gungreeneyes.trekui

import java.util.Locale

/**
 * Pure bookkeeping for hosted Android widget panels ([WidgetPanel]): which widget slot holds
 * which bound `appWidgetId`, the panel ids those slots appear under in the panel order, and the
 * size math used to lay a widget out inside its console card.
 *
 * Every slot is a home panel with the id `"appwidget<slot>"` (slots count from 1). The
 * slot → widget-id map is stored in [Prefs] under [KEY_SLOTS] as `"1:42,2:57"`; the
 * panel ids take part in [Prefs.widgetOrder] and [Prefs.hiddenWidgets] exactly like
 * the built-in panels, so MOVE UP / MOVE DOWN / HIDE PANEL, CONFIG › PANELS and the reveal-hub info
 * nodes all work unchanged. Everything here except [load]/[save] is a function
 * with no Android dependency, so it is unit-tested on the JVM.
 */
internal object WidgetSlots {
    /** Panel-id prefix of a hosted widget panel ("appwidget3"). */
    const val PREFIX = "appwidget"

    /** Prefs key of the encoded slot → appWidgetId map. */
    const val KEY_SLOTS = "appWidgetSlots"

    /** Prefs key prefix of a slot's chosen height in rows ("appWidgetRows_3"). */
    const val KEY_ROWS = "appWidgetRows_"

    /** Most widget panels a home screen can hold. */
    const val MAX_SLOTS = 12

    /** Height of one widget row, design dp (before the window's UI scale). */
    const val ROW_DP = 90f

    /** Allowed row counts for WIDGET SIZE. */
    const val MIN_ROWS = 1
    const val MAX_ROWS = 5

    /** A widget panel never gets taller than this fraction of the window height. */
    const val MAX_WINDOW_FRACTION = 0.8f

    /** `AppWidgetManager.INVALID_APPWIDGET_ID`, repeated here so tests need no Android stubs. */
    const val INVALID_ID = 0

    // ---- panel ids --------------------------------------------------------------------------------

    /** True for a hosted-widget panel id ("appwidget1" .. "appwidget12"). */
    @JvmStatic
    fun isSlotId(panelId: String?): Boolean = slotOf(panelId) > 0

    /** Panel id of `slot`. */
    @JvmStatic
    fun panelId(slot: Int): String = PREFIX + slot

    /** Slot number of a hosted-widget panel id, or -1 when `panelId` is not one. */
    @JvmStatic
    fun slotOf(panelId: String?): Int {
        if (panelId == null || !panelId.startsWith(PREFIX) || panelId.length == PREFIX.length) return -1
        val digits = panelId.substring(PREFIX.length)
        if (digits.length > 3) return -1
        for (ch in digits) {
            if (!Character.isDigit(ch)) return -1
        }
        val slot = digits.toInt()
        return if (slot in 1..MAX_SLOTS && digits[0] != '0') slot else -1
    }

    /** Panel ids of every slot in `slots`, in slot order of the map. */
    @JvmStatic
    fun panelIds(slots: Map<Int, Int>): List<String> = slots.keys.map { panelId(it) }

    // ---- slot map encoding -----------------------------------------------------------------------

    /**
     * Decodes `"1:42,2:57"` into slot → appWidgetId (insertion-ordered). Malformed pairs, slots
     * outside 1..[MAX_SLOTS], invalid widget ids and repeated slots or widget ids are dropped;
     * `null` or blank gives an empty map.
     */
    @JvmStatic
    fun parse(raw: String?): LinkedHashMap<Int, Int> {
        val out = LinkedHashMap<Int, Int>()
        if (raw == null || raw.javaTrim().isEmpty()) return out
        for (pair in raw.split(",")) {
            val colon = pair.indexOf(':')
            if (colon <= 0) continue
            val slot: Int
            val widgetId: Int
            try {
                slot = pair.substring(0, colon).javaTrim().toInt()
                widgetId = pair.substring(colon + 1).javaTrim().toInt()
            } catch (bad: NumberFormatException) {
                continue
            }
            if (slot < 1 || slot > MAX_SLOTS || widgetId <= INVALID_ID) continue
            if (out.containsKey(slot) || out.containsValue(widgetId)) continue
            out[slot] = widgetId
        }
        return out
    }

    /** Encodes a slot → appWidgetId map as `"1:42,2:57"`. */
    @JvmStatic
    fun format(slots: Map<Int, Int>): String =
        slots.entries.joinToString(",") { (slot, widgetId) -> "$slot:$widgetId" }

    /** Lowest free slot (1-based) not in `used`, or -1 when all [MAX_SLOTS] are taken. */
    @JvmStatic
    fun nextSlot(used: Set<Int>): Int {
        for (slot in 1..MAX_SLOTS) {
            if (slot !in used) return slot
        }
        return -1
    }

    /**
     * The widget ids the host still owns that no slot references (left behind when the process died in
     * the middle of an add flow), excluding `pendingId`, which an add flow is still working on
     * (pass [INVALID_ID] when none is). These are deleted so the system does not keep updating them forever.
     */
    @JvmStatic
    fun orphans(hostIds: IntArray?, kept: Collection<Int>, pendingId: Int): List<Int> {
        if (hostIds == null) return ArrayList()
        return hostIds.filter { it != pendingId && it !in kept }
    }

    /**
     * The slot map after a backup restore renumbered the host's widget ids
     * (`AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED`): every slot whose id is
     * `oldIds[i]` now holds `newIds[i]`; slots with ids not in `oldIds` keep theirs.
     * Null or mismatched arrays leave the map unchanged. The result keeps the slot order.
     */
    @JvmStatic
    fun remap(slots: Map<Int, Int>, oldIds: IntArray?, newIds: IntArray?): LinkedHashMap<Int, Int> {
        val out = LinkedHashMap(slots)
        if (oldIds == null || newIds == null || oldIds.size != newIds.size) return out
        for (e in out.entries) {
            val i = oldIds.indexOf(e.value)
            if (i >= 0) e.setValue(newIds[i])
        }
        return out
    }

    // ---- sizing ----------------------------------------------------------------------------------

    /** Clamps a stored row count into [MIN_ROWS]..[MAX_ROWS]. */
    @JvmStatic
    fun clampRows(rows: Int): Int = Math.max(MIN_ROWS, Math.min(MAX_ROWS, rows))

    /** Default row count for a widget whose provider asks for at least `minHeightDp`. */
    @JvmStatic
    fun rowsFor(minHeightDp: Float): Int {
        if (!(minHeightDp > 0f)) return 2
        return clampRows(Math.ceil((minHeightDp / ROW_DP).toDouble()).toInt())
    }

    /**
     * Height (dp) of a widget panel's widget area: `rows` × [ROW_DP] scaled by the window's
     * UI scale, but never taller than [MAX_WINDOW_FRACTION] of the window (landscape phones).
     */
    @JvmStatic
    fun heightDp(rows: Int, uiScale: Float, windowHeightDp: Float): Float {
        val scale = if (uiScale > 0f) uiScale else 1f
        var h = clampRows(rows) * ROW_DP * scale
        if (windowHeightDp > 0f) h = Math.min(h, windowHeightDp * MAX_WINDOW_FRACTION)
        return Math.max(ROW_DP * 0.5f, h)
    }

    /** Converts a laid-out pixel size to the whole dp {width, height} reported to the widget provider. */
    @JvmStatic
    fun sizeDp(widthPx: Int, heightPx: Int, density: Float): IntArray {
        val d = if (density > 0f) density else 1f
        return intArrayOf(Math.max(0, Math.round(widthPx / d)), Math.max(0, Math.round(heightPx / d)))
    }

    /** "WIDGET 3" style fallback name of a slot (hub node label, CONFIG row, offline card title). */
    @JvmStatic
    fun fallbackName(slot: Int): String = String.format(Locale.US, "WIDGET %d", slot)

    // ---- persistence (thin Prefs wrappers) ---------------------------------------------------------

    /** The stored slot → appWidgetId map. */
    @JvmStatic
    fun load(prefs: Prefs): LinkedHashMap<Int, Int> = parse(prefs.s(KEY_SLOTS, null))

    /** Stores the slot map and marks the home layout stale so it rebuilds. */
    @JvmStatic
    fun save(prefs: Prefs, slots: Map<Int, Int>) {
        prefs.put(KEY_SLOTS, format(slots))
        prefs.bump()
    }

    /** The widget area height, in rows, chosen for `slot` (0 when the user never chose one). */
    @JvmStatic
    fun storedRows(prefs: Prefs, slot: Int): Int = prefs.i(KEY_ROWS + slot, 0)
}
