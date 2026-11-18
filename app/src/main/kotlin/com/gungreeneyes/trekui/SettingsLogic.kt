package com.gungreeneyes.trekui

import java.util.Locale

/**
 * Pure (Android-free) rules used by the CONFIG screen ([SettingsActivity]): option-row layout,
 * preference ↔ choice mapping, text masking and report formatting. Unit-tested in `SettingsLogicTest`.
 */
internal object SettingsLogic {

    /** The starship interfaces in CONFIG order: Command, its 23rd- and 25th-century eras, Imperial, Collective. */
    @JvmField
    val STARSHIP_FACTIONS: IntArray = intArrayOf(Skin.COMMAND, Skin.ERA23, Skin.ERA25, Skin.IMPERIAL, Skin.COLLECTIVE)

    /** True for a starship interface (any of [STARSHIP_FACTIONS]). */
    @JvmStatic
    fun isStarshipFaction(f: Int): Boolean = f in STARSHIP_FACTIONS

    /** Wallpaper dimming choices (percent) for BACKGROUND = WALLPAPER. */
    @JvmField
    val DIM_LEVELS: IntArray = intArrayOf(30, 55, 75)

    /** Dock slot counts offered (0 = no dock). */
    @JvmField
    val DOCK_SIZES: IntArray = intArrayOf(0, 4, 5)

    /** WallpaperManager.FLAG_SYSTEM / FLAG_LOCK (same values, kept here so this object stays framework-free). */
    const val WALLPAPER_HOME = 1
    const val WALLPAPER_LOCK = 2

    /** How many unmatched / failed files an import report lists before summarising. */
    const val REPORT_MAX_UNMATCHED = 12
    const val REPORT_MAX_FAILED = 8

    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    /**
     * Buttons per row in an option group: all of them when they fit (`≤4`, or `≤6` on wide
     * windows), otherwise rows of three.
     */
    @JvmStatic
    fun choiceColumns(optionCount: Int, wide: Boolean): Int =
        if (optionCount <= (if (wide) 6 else 4)) maxOf(1, optionCount) else 3

    /**
     * Corner style (Ui.SQUARE / PILL / LEFT / RIGHT) for option `index` so each row reads as one
     * segmented console bar: rounded left end, square middles, rounded right end; a lone option is a pill.
     */
    @JvmStatic
    fun choiceCorner(index: Int, columns: Int, optionCount: Int): Int {
        if (columns == 1) return Ui.PILL
        val column = index % columns
        if (column == 0) return Ui.LEFT
        if (column == columns - 1 || index == optionCount - 1) return Ui.RIGHT
        return Ui.SQUARE
    }

    /** Selected WALLPAPER DIMMING option for a stored dim percentage. */
    @JvmStatic
    fun dimIndex(dimPercent: Int): Int = when {
        dimPercent <= DIM_LEVELS[0] -> 0
        dimPercent <= DIM_LEVELS[1] -> 1
        else -> 2
    }

    /** Selected DOCK SLOTS option for a stored dock size. */
    @JvmStatic
    fun dockIndex(dockCount: Int): Int = when (dockCount) {
        0 -> 0
        4 -> 1
        else -> 2
    }

    /** WallpaperManager flags for the APPLY dialog choice (HOME SCREEN / LOCK SCREEN / HOME + LOCK). */
    @JvmStatic
    fun wallpaperTarget(choice: Int): Int = when (choice) {
        0 -> WALLPAPER_HOME
        1 -> WALLPAPER_LOCK
        else -> WALLPAPER_HOME or WALLPAPER_LOCK
    }

    /** True for a dotted IPv4 address such as 192.168.1.42 (each part 0..255). */
    @JvmStatic
    fun isIpv4(text: String?): Boolean {
        if (text == null || !IPV4.matches(text)) return false
        return text.split(".").all { it.toInt() <= 255 }
    }

    /** "••••" plus the last four characters, for API keys and secrets. */
    @JvmStatic
    fun maskSecret(secret: String): String = "••••" + secret.substring(maxOf(0, secret.length - 4))

    /** Shortens long ids to "first 12…last 8" so they fit on a button. */
    @JvmStatic
    fun abbreviate(text: String): String =
        if (text.length > 26) text.substring(0, 12) + "…" + text.substring(text.length - 8) else text

    /** "#RRGGBB" for an ARGB colour (alpha dropped). */
    @JvmStatic
    fun hexColor(argb: Int): String = String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

    /** Display name for a hidden-app key ("pkg/Activity#user") when the app is no longer installed. */
    @JvmStatic
    fun hiddenAppName(key: String): String {
        val hash = key.indexOf('#')
        return if (hash > 0) key.substring(0, hash) else key
    }

    /** Title of a home panel row in PANELS (ORDER &amp; VISIBILITY). */
    @JvmStatic
    fun panelTitle(panelId: String): String {
        val widgetSlot = WidgetSlots.slotOf(panelId)
        if (widgetSlot > 0) return "ANDROID " + WidgetSlots.fallbackName(widgetSlot)
        return when (panelId) {
            "weather" -> "WEATHER"
            "calendar" -> "SCHEDULE (CALENDAR)"
            "media" -> "AUDIO SYSTEMS (MEDIA)"
            "power" -> "POWER SYSTEMS (BATTERY)"
            else -> "SHIP SYSTEMS (DEVICE)"
        }
    }

    /** "1 LIGHT" / "3 LIGHTS" style counts. */
    @JvmStatic
    fun count(n: Int, singular: String, plural: String): String = "$n " + (if (n == 1) singular else plural)

    /** Whole minutes left, rounded up (cloak countdown). */
    @JvmStatic
    fun minutesLeft(remainingMs: Long): Long = (remainingMs + 59_999L) / 60_000L

    /**
     * The text of the sound-import report dialog: assigned lines, then up to 12 unmatched files and up to
     * 8 failures.
     */
    @JvmStatic
    fun importReport(lines: List<String>, unmatched: List<String>, failed: List<String>): String {
        val sb = StringBuilder()
        if (lines.isEmpty()) sb.append("No events were assigned.\n")
        for (line in lines) sb.append(line).append('\n')
        if (unmatched.isNotEmpty()) {
            sb.append("\nNOT MATCHED (assign manually if wanted):\n")
            for (name in unmatched.take(REPORT_MAX_UNMATCHED)) sb.append("• ").append(name).append('\n')
            if (unmatched.size > REPORT_MAX_UNMATCHED) {
                sb.append("• +").append(unmatched.size - REPORT_MAX_UNMATCHED).append(" more\n")
            }
        }
        if (failed.isNotEmpty()) {
            sb.append("\nCOULD NOT USE:\n")
            for (name in failed.take(REPORT_MAX_FAILED)) sb.append("• ").append(name).append('\n')
        }
        return sb.toString().javaTrim()
    }

    /** Signal's colour sliders: hue in degrees and saturation in percent, from an ARGB colour's HSV. */
    @JvmStatic
    fun signalSliders(hsv: FloatArray): IntArray = intArrayOf(Math.round(hsv[0]), Math.round(hsv[1] * 100f))
}
