package com.gungreeneyes.trekui.keyboard.logic

import kotlin.math.roundToInt

/**
 * Key-height presets, one-handed width and the language cycle. Pure Kotlin (unit-tested).
 */
internal object Sizing {
    /** Size presets S, M, L, XL. */
    const val S = 0
    const val M = 1
    const val L = 2
    const val XL = 3

    /** Row heights (dp) per preset in portrait. */
    private val PORTRAIT_DP = floatArrayOf(46f, 52f, 58f, 64f)
    /** Row heights (dp) per preset in landscape. */
    private val LANDSCAPE_DP = floatArrayOf(36f, 40f, 45f, 50f)
    /** The keys (without suggestion strip) never take more than this share of the window height. */
    const val MAX_HEIGHT_SHARE = 0.5f
    /** One-handed mode: share of the width the keys keep. */
    const val ONE_HANDED_SHARE = 0.82f

    /** One-handed modes. */
    const val ONE_HANDED_OFF = 0
    const val ONE_HANDED_LEFT = 1
    const val ONE_HANDED_RIGHT = 2

    /** Clamps a stored preset into S..XL (M for unknown values). */
    fun clampPreset(preset: Int): Int = if (preset in S..XL) preset else M

    /** Row height in dp for [preset] in the given orientation. */
    fun rowDp(preset: Int, landscape: Boolean): Float =
        (if (landscape) LANDSCAPE_DP else PORTRAIT_DP)[clampPreset(preset)]

    /**
     * Row height in px: the preset, shrunk if [rows] rows would exceed [MAX_HEIGHT_SHARE] of a
     * [windowPx]-high window (landscape phones), never below 60 % of the preset.
     */
    fun rowPx(preset: Int, landscape: Boolean, density: Float, rows: Int, windowPx: Int): Int {
        var want = rowDp(preset, landscape) * density
        if (rows > 0 && windowPx > 0) {
            val max = windowPx * MAX_HEIGHT_SHARE / rows
            want = maxOf(want * 0.6f, minOf(want, max))
        }
        return want.roundToInt()
    }

    /** The language after [current] among [enabled] (wraps; the first if current is not on). */
    fun nextLanguage(current: Language, enabled: List<Language>?): Language {
        if (enabled.isNullOrEmpty()) return current
        val i = enabled.indexOf(current)
        return enabled[(i + 1) % enabled.size]
    }
}
