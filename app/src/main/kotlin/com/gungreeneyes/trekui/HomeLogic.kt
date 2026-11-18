package com.gungreeneyes.trekui

import java.util.Calendar
import java.util.Locale
import kotlin.math.floor

/**
 * Pure (Android-free) rules used by the home screen ([MainActivity]).
 *
 * Everything here is a function of its arguments, or a tiny state holder with no
 * framework dependencies, so it can be unit-tested on the JVM (see `HomeLogicTest`).
 * MainActivity keeps the views; this object keeps the decisions.
 */
internal object HomeLogic {

    /** Window width (dp) the original single-column-family layouts were designed for. */
    const val REFERENCE_WIDTH_DP = 360f

    /** Longest label put on a tile or dock slot when one is assigned from an app name. */
    const val SHORT_LABEL_MAX = 14

    /** Hub page shown in the reveal layouts: the shortcut tiles. */
    const val HUB_PAGE_TILES = 0

    /** Hub page: one node per information panel (weather, calendar…). */
    const val HUB_PAGE_INFO = 1

    /** Hub page: the "stations" (ship stations) list. */
    const val HUB_PAGE_STATIONS = 2

    /** Package-name fragments of chat, call and messaging apps (launching one plays the COMM chirp). */
    @JvmField
    val COMMS_PACKAGES: Array<String> = arrayOf(
        "thoughtcrime.securesms", "com.microsoft.teams", "whatsapp", "telegram",
        "com.discord", "com.facebook.orca", "com.slack", "com.skype", "us.zoom", "com.google.android.apps.tachyon",
        "com.google.android.apps.meetings", "com.viber", "jp.naver.line", "com.kakao.talk", "ch.threema", "im.vector",
        "com.wire", "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.samsung.android.dialer",
        "com.google.android.dialer", "com.android.dialer", "com.android.mms", "com.anthropic.claude",
    )

    /** Package-name fragments of e-mail apps, which are never treated as comms apps. */
    @JvmField
    val EMAIL_MARKERS: Array<String> = arrayOf("mail", "outlook", "android.gm", ".email", "office", "protonmail", "spark")

    /** Palette accent order used to colour consecutive tiles and hub nodes. */
    private val ACCENT_CYCLE = intArrayOf(0, 1, 3, 2, 4, 5)

    /** Palette accent index for the n-th tile / node / settings row (cycles through six accents). */
    @JvmStatic
    fun accentIndex(position: Int): Int = ACCENT_CYCLE[Math.floorMod(position, ACCENT_CYCLE.size)]

    /**
     * Number of shortcut-tile columns for the grid layouts.
     *
     * @param baseColumns     columns the interface uses on a 360dp-wide phone (Command 3, Imperial 2, Collective 1…)
     * @param availableWidthDp width the tile grid can use, in dp
     * @return `baseColumns` on phones, growing with width up to twice that on tablets / unfolded foldables
     */
    @JvmStatic
    fun tileColumns(baseColumns: Int, availableWidthDp: Float): Int {
        val base = maxOf(1, baseColumns)
        val scaled = floor((base * availableWidthDp / REFERENCE_WIDTH_DP).toDouble()).toInt()
        return maxOf(base, minOf(base * 2, scaled))
    }

    /** Trims an app label to at most [SHORT_LABEL_MAX] characters for a tile caption. */
    @JvmStatic
    fun shortLabel(label: String?): String {
        val trimmed = label?.javaTrim() ?: ""
        return if (trimmed.length > SHORT_LABEL_MAX) trimmed.substring(0, SHORT_LABEL_MAX).javaTrim() else trimmed
    }

    /** Pictogram name (see IconFactory.glyph) for an console function assigned to a tile or slot. */
    @JvmStatic
    fun actionGlyph(action: String?): String = when (action) {
        null -> "apps"
        Tile.A_SEARCH -> "search"
        Tile.A_CONFIG, Tile.A_SHADE, Tile.A_CASCADE -> "system"
        Tile.A_ANDROID, Tile.A_PANEL -> "settings"
        Tile.A_DIALER -> "phone"
        Tile.A_CLOAK, Tile.A_LOCK -> "secure"
        Tile.A_RED_ALERT -> "alert"
        Tile.A_GOVEE -> "weather"
        Tile.A_NEST, Tile.A_HA -> "home"
        else -> "apps"
    }

    /** Short upper-case caption for an information panel id ("weather" → "WEATHER"). */
    @JvmStatic
    fun shortPanelName(panelId: String): String {
        val widgetSlot = WidgetSlots.slotOf(panelId)
        if (widgetSlot > 0) return WidgetSlots.fallbackName(widgetSlot)
        return panelId.uppercase(Locale.US)
    }

    /** Pictogram for an information panel's hub node, keyed by its short name. */
    @JvmStatic
    fun panelGlyph(shortName: String): String = when (shortName) {
        "WEATHER" -> "weather"
        "CALENDAR" -> "calendar"
        "MEDIA" -> "music"
        "POWER" -> "alert"
        "SYSTEMS" -> "computer"
        else -> "apps" // also hosted widget panels ("WIDGET 3")
    }

    /**
     * True when an app should get the communications launch chirp instead of the normal launch sound.
     *
     * @param pkg           package name
     * @param label         app label (may be null)
     * @param isCommCategory whether the app was categorised as COMM by AppInfo
     * @param dialerPkg     default dialer package (may be null)
     * @param smsPkg        default SMS package (may be null)
     */
    @JvmStatic
    fun isCommsApp(pkg: String, label: String?, isCommCategory: Boolean, dialerPkg: String?, smsPkg: String?): Boolean {
        val p = pkg.lowercase(Locale.US)
        if (EMAIL_MARKERS.any { p.contains(it) }) return false
        if (p == dialerPkg || p == smsPkg) return true
        if (COMMS_PACKAGES.any { p.contains(it) }) return true
        val l = label?.lowercase(Locale.US) ?: ""
        return isCommCategory && (
            l.contains("messag") || l.contains("chat") || l.contains("phone") || l.contains("call") ||
                p.contains("messag") || p.contains("chat") || p.contains("dialer")
            )
    }

    /**
     * Stardate: 1000 units per year, 41000.0 at the start of 1987, with the fraction of the
     * year (day plus time of day) in the remaining units. Formatted with one decimal.
     */
    @JvmStatic
    fun stardate(calendar: Calendar): String {
        val year = calendar.get(Calendar.YEAR)
        val dayFraction = (calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)) / 1440.0
        val yearFraction = (calendar.get(Calendar.DAY_OF_YEAR) - 1 + dayFraction) / calendar.getActualMaximum(Calendar.DAY_OF_YEAR)
        return String.format(Locale.US, "%.1f", yearFraction * 1000.0 + (year - 1987) * 1000 + 41000)
    }

    /** Battery percentage from the BATTERY_CHANGED level/scale extras, or -1 when unknown. */
    @JvmStatic
    fun batteryPercent(level: Int, scale: Int): Int {
        if (level < 0 || scale <= 0) return -1
        return Math.round(level * 100.0f / scale)
    }

    /** Clock ticker period while the data cascade animation runs. */
    const val CASCADE_PERIOD_MS = 2600L

    /** Small slack so the ticker fires just after the minute/second boundary, never just before. */
    const val TICK_SLACK_MS = 15L

    /**
     * Delay until the home ticker should next run: the next second (seconds shown) or minute
     * boundary, but no later than the next cascade step when animations are on.
     */
    @JvmStatic
    fun nextTickDelayMs(nowMs: Long, showSeconds: Boolean, animate: Boolean): Long {
        var delay = if (showSeconds) 1000 - (nowMs % 1000) else 60000 - (nowMs % 60000)
        if (animate) delay = minOf(delay, CASCADE_PERIOD_MS)
        return delay + TICK_SLACK_MS
    }

    /** Random-looking console code block text, "NN-NNNN". */
    @JvmStatic
    fun codeText(twoDigits: Int, fourDigits: Int): String = String.format(Locale.US, "%02d-%04d", twoDigits, fourDigits)

    /** Two-digit tile number ("01".."12") for a zero-based tile position. */
    @JvmStatic
    fun tileNumber(position: Int): String = String.format(Locale.US, "%02d", position + 1)

    /** Slot key for an ConsoleFrame part: prefix "h" + part "elbowT" → "hElbowT". */
    @JvmStatic
    fun framePartSlot(prefix: String, part: String): String = prefix + part[0].uppercaseChar() + part.substring(1)

    /** The battery sound the home screen should play after a BATTERY_CHANGED update. */
    enum class BatteryAlert { NONE, PLUGGED_IN, LOW, CRITICAL }

    /**
     * Battery warning state: plays the power chirp when a charger is connected, a warning at 15%
     * and then every further 4%, and the critical klaxon once at 5%. Plugging in re-arms it.
     */
    class BatteryWatch {
        private var lastPlugged = -1
        private var warnedLevel = DISARMED
        private var criticalWarned = false

        /**
         * Feeds one battery update.
         *
         * @param plugged  BatteryManager EXTRA_PLUGGED (0 = on battery)
         * @param percent  battery percentage, or -1 when unknown
         * @param announce false for silent refreshes (initial sticky broadcast, UI rebuilds, not resumed)
         * @return which alert to play
         */
        fun update(plugged: Int, percent: Int, announce: Boolean): BatteryAlert {
            var alert = BatteryAlert.NONE
            if (announce) {
                if (lastPlugged == 0 && plugged != 0) {
                    alert = BatteryAlert.PLUGGED_IN
                }
                if (plugged == 0 && percent >= 0 && percent <= CRITICAL_PERCENT && !criticalWarned) {
                    alert = BatteryAlert.CRITICAL
                    criticalWarned = true
                    warnedLevel = percent - REPEAT_STEP
                } else if (plugged == 0 && percent >= 0 && percent <= LOW_PERCENT && percent < warnedLevel) {
                    alert = BatteryAlert.LOW
                    warnedLevel = percent - REPEAT_STEP
                }
            }
            if (plugged != 0) {
                warnedLevel = DISARMED
                criticalWarned = false
            }
            lastPlugged = plugged
            return alert
        }

        companion object {
            const val LOW_PERCENT = 15
            const val CRITICAL_PERCENT = 5
            const val REPEAT_STEP = 4
            private const val DISARMED = 101
        }
    }
}
