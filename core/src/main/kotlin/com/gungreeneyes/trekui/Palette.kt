package com.gungreeneyes.trekui

/**
 * A colour theme: six accent colours used in rotation for frames, tiles and data, plus text, alert
 * and panel colours. [ALL] holds the Command themes, [ERA23] / [ERA25] the two
 * extra Command eras (specs in [Era]), [IMPERIAL] / [COLLECTIVE] the faction
 * sub-themes (specs in [SubThemes]). [themesFor] is the one
 * lookup every screen uses.
 *
 * Colours are parsed with the pure [Era.rgb] (not `android.graphics.Color`), so the
 * theme tables hold their real values in JVM unit tests too.
 *
 * Java interop: the colour fields are [JvmField]s and the tables and lookups live in the companion
 * as [JvmField]s / [JvmStatic]s, so Java keeps reading `p.c[i]`, `p.textHi` and `Palette.ALL`.
 */
class Palette private constructor(
    @JvmField val name: String,
    accents: Array<String>,
    textHi: String,
    textLo: String,
    alert: String,
    panel: String,
    /** Chrome tones {bg, surface, grid, detail, ink}, or null for none. */
    tones: Array<String>?,
) {
    /** Accent slots: 0 primary (orange), 1 lavender, 2 blue, 3 pink, 4 gold, 5 purple. */
    @JvmField val c: IntArray = IntArray(accents.size) { Era.rgb(accents[it]) }
    /** Bright readout text (clock, values). */
    @JvmField val textHi: Int = Era.rgb(textHi)
    /** Secondary data text. */
    @JvmField val textLo: Int = Era.rgb(textLo)
    /** Warning / low battery colour. */
    @JvmField val alert: Int = Era.rgb(alert)
    /** Card/panel fill behind data. */
    @JvmField val panel: Int = Era.rgb(panel)
    /** True when this palette carries its own chrome tones (Imperial and Collective sub-themes). */
    @JvmField val toned: Boolean = tones != null

    /*
     * Chrome tones of an Imperial / Collective sub-theme (screen background, tray surface, grid lines,
     * detail marks, control lettering); only meaningful when [toned].
     */
    /** Screen background (opaque black when untoned). */
    @JvmField val bg: Int = if (tones != null) Era.rgb(tones[0]) else BLACK
    /** Tray surface (the panel colour when untoned). */
    @JvmField val surface: Int = if (tones != null) Era.rgb(tones[1]) else this.panel
    /** Grid lines (0 when untoned). */
    @JvmField val grid: Int = if (tones != null) Era.rgb(tones[2]) else 0
    /** Detail marks (0 when untoned). */
    @JvmField val detail: Int = if (tones != null) Era.rgb(tones[3]) else 0
    /** Control lettering (opaque black when untoned). */
    @JvmField val ink: Int = if (tones != null) Era.rgb(tones[4]) else BLACK

    /** Builds a palette from `#RRGGBB` strings. */
    constructor(name: String, accents: Array<String>, textHi: String, textLo: String, alert: String, panel: String) :
        this(name, accents, textHi, textLo, alert, panel, null)

    /** Accent `i`, wrapping around (negative indexes count back from the end). */
    fun c(i: Int): Int = c[wrapIndex(i, c.size)]

    companion object {
        private const val BLACK = 0xFF000000.toInt()

        /**
         * Builds a palette from a theme spec (name, six accents, text hi/lo, alert, panel, then optionally
         * the five [SubThemes] chrome tones).
         */
        @JvmStatic
        fun fromSpec(spec: Array<String>): Palette {
            val accents = spec.copyOfRange(Era.SPEC_ACCENT0, Era.SPEC_ACCENT0 + Era.SPEC_ACCENTS)
            val tones = if (spec.size >= SubThemes.SPEC_TONED_LENGTH) {
                spec.copyOfRange(SubThemes.SPEC_BG, SubThemes.SPEC_TONED_LENGTH)
            } else {
                null
            }
            return Palette(spec[Era.SPEC_NAME], accents, spec[Era.SPEC_TEXT_HI], spec[Era.SPEC_TEXT_LO],
                spec[Era.SPEC_ALERT], spec[Era.SPEC_PANEL], tones)
        }

        private fun fromSpecs(specs: Array<Array<String>>): Array<Palette> = Array(specs.size) { fromSpec(specs[it]) }

        /** The 23rd-century console themes ([Era.ERA23_THEMES]). */
        @JvmField
        val ERA23: Array<Palette> = fromSpecs(Era.ERA23_THEMES)
        /** The 25th-century glass themes ([Era.ERA25_THEMES]). */
        @JvmField
        val ERA25: Array<Palette> = fromSpecs(Era.ERA25_THEMES)
        /** The Imperial sub-themes ([SubThemes.IMPERIAL]); 0 is the original tactical red. */
        @JvmField
        val IMPERIAL: Array<Palette> = fromSpecs(SubThemes.IMPERIAL)
        /** The Collective sub-themes ([SubThemes.COLLECTIVE]); 0 is the original matrix green. */
        @JvmField
        val COLLECTIVE: Array<Palette> = fromSpecs(SubThemes.COLLECTIVE)

        /**
         * The Command themes offered in CONFIG, by stored theme index ([SubThemes.COMMAND]): CLASSIC
         * (default, the owner's reference palette) and the original five keep indexes 0-5; the 6.3 themes
         * follow, so a stored choice never changes meaning.
         */
        @JvmField
        val ALL: Array<Palette> = fromSpecs(SubThemes.COMMAND)

        /**
         * Colour themes offered for a faction, indexed by stored sub-theme: the Command list, the era
         * sets, or the Imperial / Collective sub-themes (Command's list for any other id).
         */
        @JvmStatic
        fun themesFor(faction: Int): Array<Palette> = when (faction) {
            Skin.ERA23 -> ERA23
            Skin.ERA25 -> ERA25
            Skin.IMPERIAL -> IMPERIAL
            Skin.COLLECTIVE -> COLLECTIVE
            else -> ALL
        }

        /** The palette of sub-theme `index` of `faction`, clamped into its list. */
        @JvmStatic
        fun of(faction: Int, index: Int): Palette {
            val list = themesFor(faction)
            return list[if (index < 0 || index >= list.size) 0 else index]
        }

        /** `i` modulo `n`, always in 0..n-1 (also for negative `i`). */
        @JvmStatic
        fun wrapIndex(i: Int, n: Int): Int = ((i % n) + n) % n
    }
}
