package com.gungreeneyes.trekui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pure (Android-free) rules for the two extra Command eras, [Skin.ERA23] and
 * [Skin.ERA25]: their colour themes (as `#RRGGBB` specs, so JVM tests can check
 * contrast without `android.graphics.Color`), WCAG contrast maths, the blinking indicator
 * lamps of the 23rd-century console and the cut-corner polygons of the 25th-century glass panels.
 *
 * The drawing itself lives in [EraDraw] (controls, frame corners, brackets, icon frames,
 * ornaments, previews) and `EraBoot` (start-up sequences); both take every size and timing
 * decision from here so it is unit-tested in `EraTest`.
 *
 * Both looks are original geometry evoking an era, not copies of any screen art:
 * ```
 *  23RD CENTURY  chunky square keys in primary colours with a bevel, rows of indicator lamps
 *                (some blinking), plain geometric sans lettering (Josefin Sans), no elbows.
 *  25TH CENTURY  thin translucent blue/grey panels layered over each other, 1dp outlines,
 *                45° cut corners, fine tick rules and light Antonio lettering.
 * ```
 *
 * Java interop: constants are `const`, tables [JvmField]s and functions [JvmStatic], so Java keeps
 * calling `Era.rgb(...)` and reading `Era.ERA23_THEMES`.
 */
object Era {
    // ---- colour themes ----------------------------------------------------------------------------

    /**
     * Theme spec layout: name, six accents (slot 0 primary .. 5), text high, text low, alert, panel.
     * [Palette.fromSpec] turns a spec into a [Palette].
     */
    const val SPEC_NAME = 0
    const val SPEC_ACCENT0 = 1
    const val SPEC_ACCENTS = 6
    const val SPEC_TEXT_HI = 7
    const val SPEC_TEXT_LO = 8
    const val SPEC_ALERT = 9
    const val SPEC_PANEL = 10
    const val SPEC_LENGTH = 11

    /**
     * 23rd-century console themes. Every accent is bright enough to carry the black lettering of a
     * key (WCAG contrast at least [MIN_TEXT_CONTRAST] against black).
     */
    @JvmField
    val ERA23_THEMES: Array<Array<String>> = arrayOf(
        arrayOf("BRIDGE PRIMARIES", "#F2C230", "#E5402F", "#4C8FE0", "#46B85C", "#F08A24", "#B88AE0",
            "#F4EEDC", "#C9B98A", "#FF3B2B", "#15120C"),
        arrayOf("ENGINEERING DECK", "#F0A030", "#D9472B", "#2FA8A0", "#E8D25A", "#C8642A", "#7FC6E8",
            "#F6EBD6", "#D2B48A", "#FF3B2B", "#16100A"),
        arrayOf("SICKBAY", "#7FD0E0", "#E86A8A", "#9FD27A", "#F0E08A", "#6FA0E8", "#D8A0E8",
            "#EEF6F6", "#A8C8CC", "#FF4A3A", "#0C1214"),
    )

    /**
     * 25th-century glass themes: blue/grey families with one warm signal accent. Accents are drawn
     * as lines and translucent fills on the dark background, with light lettering on top.
     */
    @JvmField
    val ERA25_THEMES: Array<Array<String>> = arrayOf(
        arrayOf("FLEET GLASS", "#7FA8D8", "#A9BCCF", "#5C86B8", "#C9D6E3", "#E0A060", "#8A9BB0",
            "#E8F0F8", "#9FB3C8", "#F0604A", "#0B121A"),
        arrayOf("STARLIGHT SILVER", "#B8C4D0", "#8FA4BA", "#D8E0E8", "#7A90A8", "#9CC8E8", "#A0A8C8",
            "#F0F4F8", "#A8B4C0", "#F06A50", "#0E1216"),
        arrayOf("DEEP SPACE CYAN", "#5FC0D8", "#8AA8C0", "#4A9CC0", "#B8E0EC", "#E8B060", "#7090B0",
            "#E4F4FA", "#8FB8C8", "#F05A48", "#08121A"),
    )

    /** WCAG AA contrast for normal text. */
    const val MIN_TEXT_CONTRAST = 4.5
    /** WCAG contrast for UI components and graphical objects (lines on the background). */
    const val MIN_LINE_CONTRAST = 3.0

    /** Screen backgrounds: warm console black (23rd) and blue-black glass (25th). */
    const val ERA23_BG = 0xFF0A0908.toInt()
    const val ERA25_BG = 0xFF05080C.toInt()
    /** Lettering on a control: black on the 23rd-century keys, pale blue-white on 25th-century glass. */
    const val ERA23_INK = 0xFF000000.toInt()
    const val ERA25_INK = 0xFFE8F0F8.toInt()
    /** How much of a control's colour tints a 25th-century glass fill (over the background). */
    const val GLASS_TINT = 0.16f

    /** Themes offered for an era faction (empty for any other faction). */
    @JvmStatic
    fun themes(faction: Int): Array<Array<String>> = when (faction) {
        Skin.ERA23 -> ERA23_THEMES
        Skin.ERA25 -> ERA25_THEMES
        else -> emptyArray()
    }

    /** Clamps a stored theme index into the era's theme list (0 when out of range). */
    @JvmStatic
    fun themeIndex(faction: Int, stored: Int): Int {
        val n = themes(faction).size
        return if (stored < 0 || stored >= n) 0 else stored
    }

    /** Parses `#RRGGBB` (or `#AARRGGBB`) to an ARGB int; RGB-only input becomes opaque. */
    @JvmStatic
    fun rgb(hex: String): Int {
        val s = hex.removePrefix("#")
        var v = s.toLong(16)
        if (s.length == 6) v = v or 0xFF000000L
        return v.toInt()
    }

    /** sRGB channel (0..255) to linear light (0..1). */
    @JvmStatic
    fun linear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance of a colour's RGB channels (alpha ignored), 0..1. */
    @JvmStatic
    fun relLuminance(color: Int): Double =
        0.2126 * linear((color shr 16) and 0xFF) + 0.7152 * linear((color shr 8) and 0xFF) + 0.0722 * linear(color and 0xFF)

    /** WCAG contrast ratio between two colours, 1..21. */
    @JvmStatic
    fun contrast(a: Int, b: Int): Double {
        val la = relLuminance(a)
        val lb = relLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** The opaque colour a 25th-century glass control shows: `color` at [GLASS_TINT] over the background. */
    @JvmStatic
    fun glassFill(color: Int): Int = Skin.mix(ERA25_BG, color, GLASS_TINT)

    // ---- indicator lamps (23rd century) ------------------------------------------------------------

    /** Lamp colours: signal red, amber, green, blue and white, as lit bulbs behind coloured caps. */
    @JvmField
    val LAMPS: IntArray = intArrayOf(
        0xFFFF3B2B.toInt(), 0xFFFFC83A.toInt(), 0xFF4CE06A.toInt(), 0xFF5AA8FF.toInt(), 0xFFF4F0E0.toInt(),
    )
    /** Base blink half-period; each lamp adds its own offset so a console never blinks in step. */
    const val BLINK_MS = 520L
    /** Lamp size, spacing and inset from the key edge, in dp. */
    const val LAMP_DP = 6f
    const val LAMP_GAP_DP = 4f
    const val LAMP_INSET_DP = 7f
    /** At most this many lamps on one key. */
    const val MAX_LAMPS = 3

    /** A small, stable scramble of an int (used to vary lamps per key without randomness). */
    @JvmStatic
    fun hash(seed: Int): Int {
        var h = seed * 0x9E3779B1.toInt()
        h = h xor (h ushr 15)
        h *= 0x85EBCA77.toInt()
        h = h xor (h ushr 13)
        return h and 0x7FFFFFFF
    }

    /** True for the lamps that blink (about one in three); the rest burn steadily. */
    @JvmStatic
    fun blinks(seed: Int): Boolean = hash(seed) % 3 == 0

    /** Half-period of a lamp's blink: [BLINK_MS] plus up to 3/4 of it more, fixed per lamp. */
    @JvmStatic
    fun halfPeriod(seed: Int): Long = BLINK_MS + (hash(seed xor 0x5bd1e995) % (BLINK_MS * 3 / 4))

    /** Whether a lamp is lit at `timeMs`: steady lamps always are; blinking lamps alternate. */
    @JvmStatic
    fun lampOn(timeMs: Long, seed: Int): Boolean {
        if (!blinks(seed)) return true
        val half = halfPeriod(seed)
        val phase = hash(seed xor 0x27d4eb2d) % (half * 2)
        return ((timeMs + phase) / half) % 2 == 0L
    }

    /** Milliseconds until a blinking lamp next changes state (a steady lamp never does: -1). */
    @JvmStatic
    fun nextToggleIn(timeMs: Long, seed: Int): Long {
        if (!blinks(seed)) return -1
        val half = halfPeriod(seed)
        val phase = hash(seed xor 0x27d4eb2d) % (half * 2)
        val into = (timeMs + phase) % half
        return half - into
    }

    /** Colour of lamp `index` on the key with `seed`. */
    @JvmStatic
    fun lampColor(seed: Int, index: Int): Int = LAMPS[hash(seed * 31 + index) % LAMPS.size]

    /** How many lamps fit on a key `widthPx` wide (`d` px per dp): 1 on buttons, up to 3 on tiles. */
    @JvmStatic
    fun lampCount(widthPx: Float, heightPx: Float, d: Float): Int {
        if (widthPx < 40 * d || heightPx < 18 * d) return 0
        if (heightPx < 40 * d) return 1
        val room = widthPx * 0.35f
        val n = ((room + LAMP_GAP_DP * d) / ((LAMP_DP + LAMP_GAP_DP) * d)).toInt()
        return max(1, min(MAX_LAMPS, n))
    }

    // ---- cut corners (25th century) ----------------------------------------------------------------

    /** Corner flags for [cutPolygon]: top-left, top-right, bottom-right, bottom-left. */
    const val TL = 1
    const val TR = 2
    const val BR = 4
    const val BL = 8
    /** Largest corner cut, in dp, and its share of the shorter side. */
    const val MAX_CUT_DP = 12f
    const val CUT_SHARE = 0.32f

    /** Corner cut for a w x h panel: [CUT_SHARE] of the shorter side, at most [MAX_CUT_DP]. */
    @JvmStatic
    fun cutSize(w: Float, h: Float, d: Float): Float = max(0f, min(min(w, h) * CUT_SHARE, MAX_CUT_DP * d))

    /**
     * Which corners a 25th-century control cuts for a `Ui` corner kind: SQUARE cuts the
     * top-left and bottom-right (the signature diagonal), LEFT both left corners, RIGHT both right
     * corners, PILL all four.
     */
    @JvmStatic
    fun cutsFor(kind: Int): Int = when (kind) {
        Ui.LEFT -> TL or BL
        Ui.RIGHT -> TR or BR
        Ui.PILL -> TL or TR or BR or BL
        else -> TL or BR
    }

    /**
     * Outline of a rectangle with 45° cuts of size `k` on the flagged corners, clockwise from
     * the top-left, as x,y pairs. `k` is clamped to half the shorter side. Uncut corners add
     * one point, cut corners two.
     */
    @JvmStatic
    fun cutPolygon(l: Float, t: Float, r: Float, b: Float, k: Float, corners: Int): FloatArray {
        val cut = max(0f, min(k, min(r - l, b - t) / 2f))
        val out = FloatArray(16)
        var n = 0
        fun add(x: Float, y: Float) {
            out[n++] = x
            out[n++] = y
        }
        val cuts = cut > 0
        if (cuts && (corners and TL) != 0) { add(l, t + cut); add(l + cut, t) } else add(l, t)
        if (cuts && (corners and TR) != 0) { add(r - cut, t); add(r, t + cut) } else add(r, t)
        if (cuts && (corners and BR) != 0) { add(r, b - cut); add(r - cut, b) } else add(r, b)
        if (cuts && (corners and BL) != 0) { add(l + cut, b); add(l, b - cut) } else add(l, b)
        return out.copyOf(n)
    }

    /** Fine tick rule: spacing between ticks in dp, and every how many ticks one is drawn long. */
    const val TICK_SPACING_DP = 8f
    const val TICK_MAJOR_EVERY = 5

    /** Number of ticks on a rule `lengthPx` long (at least 0, one at each end when it fits). */
    @JvmStatic
    fun tickCount(lengthPx: Float, d: Float): Int {
        val step = TICK_SPACING_DP * d
        if (lengthPx < step || step <= 0) return 0
        return (lengthPx / step).toInt() + 1
    }
}
