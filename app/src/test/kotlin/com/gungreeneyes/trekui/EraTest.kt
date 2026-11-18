package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure rules of the 23rd- and 25th-century Command eras ([Era]): theme specs and their
 * contrast, lamp blinking, cut-corner polygons, tick rules, plus the era hooks in [Skin],
 * [Prefs], [Palette], [SettingsLogic] and [EraBoot].
 */
class EraTest {

    @Test
    fun themeSpecs_haveEveryFieldAndParse() {
        for (set in arrayOf(Era.ERA23_THEMES, Era.ERA25_THEMES)) {
            assertThat(set.size).isAtLeast(2)
            for (spec in set) {
                assertThat(spec).hasLength(Era.SPEC_LENGTH)
                assertThat(spec[Era.SPEC_NAME]).isNotEmpty()
                for (i in 1 until spec.size) assertThat(Era.rgb(spec[i]) ushr 24).isEqualTo(0xFF)
            }
        }
    }

    @Test
    fun era23_everyKeyColourCarriesBlackLettering() {
        for (spec in Era.ERA23_THEMES) {
            for (i in 0 until Era.SPEC_ACCENTS) {
                val accent = Era.rgb(spec[Era.SPEC_ACCENT0 + i])
                assertThat(Era.contrast(accent, Era.ERA23_INK)).isAtLeast(Era.MIN_TEXT_CONTRAST)
            }
            assertThat(Era.contrast(Era.rgb(spec[Era.SPEC_TEXT_HI]), Era.ERA23_BG)).isAtLeast(Era.MIN_TEXT_CONTRAST)
            assertThat(Era.contrast(Era.rgb(spec[Era.SPEC_TEXT_LO]), Era.ERA23_BG)).isAtLeast(Era.MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun era25_linesShowOnTheBackgroundAndLetteringReadsOnGlass() {
        for (spec in Era.ERA25_THEMES) {
            for (i in 0 until Era.SPEC_ACCENTS) {
                val accent = Era.rgb(spec[Era.SPEC_ACCENT0 + i])
                assertThat(Era.contrast(accent, Era.ERA25_BG)).isAtLeast(Era.MIN_LINE_CONTRAST)
                assertThat(Era.contrast(Era.ERA25_INK, Era.glassFill(accent))).isAtLeast(Era.MIN_TEXT_CONTRAST)
            }
            assertThat(Era.contrast(Era.rgb(spec[Era.SPEC_TEXT_HI]), Era.ERA25_BG)).isAtLeast(Era.MIN_TEXT_CONTRAST)
            assertThat(Era.contrast(Era.rgb(spec[Era.SPEC_TEXT_LO]), Era.ERA25_BG)).isAtLeast(Era.MIN_TEXT_CONTRAST)
        }
    }

    @Test
    fun rgbAndContrast_knownValues() {
        assertThat(Era.rgb("#FF8000")).isEqualTo(0xFFFF8000.toInt())
        assertThat(Era.rgb("80FF8000")).isEqualTo(0x80FF8000.toInt())
        assertThat(Era.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt())).isWithin(1e-9).of(21.0)
        assertThat(Era.contrast(0xFF777777.toInt(), 0xFF777777.toInt())).isWithin(1e-9).of(1.0)
        assertThat(Era.relLuminance(0xFFFFFFFF.toInt())).isWithin(1e-9).of(1.0)
    }

    @Test
    fun glassFill_isATintOfTheBackground() {
        assertThat(Era.glassFill(Era.ERA25_BG)).isEqualTo(Era.ERA25_BG)
        assertThat(Era.relLuminance(Era.glassFill(0xFFFFFFFF.toInt()))).isLessThan(0.1)
    }

    @Test
    fun themeIndex_clampsPerEra() {
        assertThat(Era.themeIndex(Skin.ERA23, 1)).isEqualTo(1)
        assertThat(Era.themeIndex(Skin.ERA23, Era.ERA23_THEMES.size)).isEqualTo(0)
        assertThat(Era.themeIndex(Skin.ERA25, -1)).isEqualTo(0)
        assertThat(Era.themes(Skin.IMPERIAL)).isEmpty()
        assertThat(Palette.themesFor(Skin.ERA23)).hasLength(Era.ERA23_THEMES.size)
        assertThat(Palette.themesFor(Skin.ERA25)).hasLength(Era.ERA25_THEMES.size)
        assertThat(Palette.themesFor(Skin.COMMAND)).isSameInstanceAs(Palette.ALL)
    }

    @Test
    fun lamps_steadyOnesStayLit() {
        for (seed in 0 until 200) {
            if (Era.blinks(seed)) continue
            assertThat(Era.lampOn(0L, seed)).isTrue()
            assertThat(Era.lampOn(123_456L, seed)).isTrue()
            assertThat(Era.nextToggleIn(5_000L, seed)).isEqualTo(-1L)
        }
    }

    @Test
    fun lamps_blinkingOnesToggleExactlyWhenPredicted() {
        var blinking = 0
        for (seed in 0 until 300) {
            if (!Era.blinks(seed)) continue
            blinking++
            for (t in longArrayOf(0, 777, 10_000, 98_765)) {
                val next = Era.nextToggleIn(t, seed)
                assertThat(next).isAtLeast(1L)
                assertThat(next).isAtMost(Era.halfPeriod(seed))
                assertThat(Era.lampOn(t + next - 1, seed)).isEqualTo(Era.lampOn(t, seed))
                assertThat(Era.lampOn(t + next, seed)).isNotEqualTo(Era.lampOn(t, seed))
            }
        }
        // about a third of the lamps blink
        assertThat(blinking).isAtLeast(60)
        assertThat(blinking).isAtMost(140)
    }

    @Test
    fun lamps_halfPeriodStaysInItsBand() {
        for (seed in 0 until 100) {
            assertThat(Era.halfPeriod(seed)).isAtLeast(Era.BLINK_MS)
            assertThat(Era.halfPeriod(seed)).isLessThan(Era.BLINK_MS * 7 / 4)
        }
    }

    @Test
    fun lampColor_isAlwaysALampColour() {
        for (seed in -50 until 50) {
            for (i in 0 until Era.MAX_LAMPS) {
                val c = Era.lampColor(seed, i)
                var known = false
                for (lamp in Era.LAMPS) known = known or (lamp == c)
                assertThat(known).isTrue()
            }
        }
    }

    @Test
    fun lampCount_bySize() {
        val d = 2f
        assertThat(Era.lampCount(20 * d, 50 * d, d)).isEqualTo(0) // swatch
        assertThat(Era.lampCount(100 * d, 12 * d, d)).isEqualTo(0) // thin bar
        assertThat(Era.lampCount(100 * d, 30 * d, d)).isEqualTo(1) // button
        assertThat(Era.lampCount(160 * d, 78 * d, d)).isEqualTo(Era.MAX_LAMPS) // tile
        val sidebar = Era.lampCount(64 * d, 62 * d, d)
        assertThat(sidebar).isAtLeast(1)
        assertThat(sidebar).isAtMost(Era.MAX_LAMPS)
    }

    @Test
    fun cutPolygon_pointCountFollowsTheCutCorners() {
        assertThat(Era.cutPolygon(0f, 0f, 100f, 40f, 10f, 0)).hasLength(8)
        assertThat(Era.cutPolygon(0f, 0f, 100f, 40f, 10f, Era.TL or Era.BR)).hasLength(12)
        assertThat(Era.cutPolygon(0f, 0f, 100f, 40f, 10f, Era.TL or Era.TR or Era.BR or Era.BL)).hasLength(16)
        assertThat(Era.cutPolygon(0f, 0f, 100f, 40f, 0f, Era.TL)).hasLength(8)
    }

    @Test
    fun cutPolygon_cutsAt45DegreesAndStaysInside() {
        val p = Era.cutPolygon(10f, 20f, 110f, 60f, 8f, Era.TL or Era.BR)
        // top-left cut: (10, 28) -> (18, 20)
        assertThat(p[0]).isEqualTo(10f)
        assertThat(p[1]).isEqualTo(28f)
        assertThat(p[2]).isEqualTo(18f)
        assertThat(p[3]).isEqualTo(20f)
        for (i in p.indices step 2) {
            assertThat(p[i]).isAtLeast(10f)
            assertThat(p[i]).isAtMost(110f)
            assertThat(p[i + 1]).isAtLeast(20f)
            assertThat(p[i + 1]).isAtMost(60f)
        }
        // the cut is clamped to half the shorter side
        val q = Era.cutPolygon(0f, 0f, 100f, 10f, 50f, Era.TL)
        assertThat(q[1]).isEqualTo(5f)
        assertThat(q[2]).isEqualTo(5f)
    }

    @Test
    fun cutsFor_kinds() {
        assertThat(Era.cutsFor(Ui.SQUARE)).isEqualTo(Era.TL or Era.BR)
        assertThat(Era.cutsFor(Ui.LEFT)).isEqualTo(Era.TL or Era.BL)
        assertThat(Era.cutsFor(Ui.RIGHT)).isEqualTo(Era.TR or Era.BR)
        assertThat(Era.cutsFor(Ui.PILL)).isEqualTo(Era.TL or Era.TR or Era.BR or Era.BL)
    }

    @Test
    fun cutSize_isAShareOfTheShorterSideCapped() {
        val d = 3f
        assertThat(Era.cutSize(200f, 30f, d)).isWithin(1e-4f).of(30 * Era.CUT_SHARE)
        assertThat(Era.cutSize(2000f, 1000f, d)).isWithin(1e-4f).of(Era.MAX_CUT_DP * d)
        assertThat(Era.cutSize(0f, 50f, d)).isEqualTo(0f)
    }

    @Test
    fun tickCount_spacing() {
        val d = 2f
        assertThat(Era.tickCount(5 * d, d)).isEqualTo(0)
        assertThat(Era.tickCount(Era.TICK_SPACING_DP * d, d)).isEqualTo(2)
        assertThat(Era.tickCount(Era.TICK_SPACING_DP * d * 10, d)).isEqualTo(11)
    }

    @Test
    fun skinPredicates_erasAreCommandFamily() {
        val saved = Skin.faction
        try {
            for (f in intArrayOf(Skin.ERA23, Skin.ERA25)) {
                Skin.faction = f
                assertThat(Skin.command()).isTrue()
                assertThat(Skin.era()).isTrue()
                assertThat(Skin.imperial() || Skin.collective()).isFalse()
                assertThat(Skin.title()).isNotEmpty()
                assertThat(Skin.ship("COMMAND DEFAULT")).isNotEqualTo("COMMAND DEFAULT")
            }
            Skin.faction = Skin.COMMAND
            assertThat(Skin.era()).isFalse()
            assertThat(Skin.ship("COMMAND DEFAULT")).isEqualTo("COMMAND DEFAULT")
            assertThat(Skin.commandFamily(Skin.IMPERIAL)).isFalse()
        } finally {
            Skin.faction = saved
        }
    }

    @Test
    fun prefsAndSettings_knowTheEras() {
        assertThat(TrekPrefs.clampFaction(Skin.ERA23)).isEqualTo(Skin.ERA23)
        assertThat(TrekPrefs.clampFaction(Skin.ERA25)).isEqualTo(Skin.ERA25)
        // Prefs inherits themeKeyFor from TrekPrefs (Kotlin does not reach a superclass companion through Prefs)
        assertThat(TrekPrefs.themeKeyFor(Skin.COMMAND)).isEqualTo("theme")
        assertThat(TrekPrefs.themeKeyFor(Skin.ERA23)).isEqualTo("theme9")
        assertThat(TrekPrefs.themeKeyFor(Skin.ERA25)).isEqualTo("theme10")
        assertThat(Prefs.layoutKeyFor(Skin.ERA23)).isEqualTo(Prefs.layoutKeyFor(Skin.COMMAND))
        assertThat(SettingsLogic.isStarshipFaction(Skin.ERA25)).isTrue()
        assertThat(SettingsLogic.isStarshipFaction(6)).isFalse()
        assertThat(Sfx.FACTION_DEFAULT).hasLength(Skin.NAMES.size)
    }

    @Test
    fun eraBoot_timings() {
        for (f in intArrayOf(Skin.ERA23, Skin.ERA25)) {
            assertThat(EraBoot.fadeAtFor(f)).isLessThan(EraBoot.durationFor(f))
            assertThat(EraBoot.durationFor(f)).isAtMost(4000L)
        }
        assertThat(EraBoot.step(0L, 100L, 200L)).isEqualTo(0f)
        assertThat(EraBoot.step(300L, 100L, 200L)).isEqualTo(1f)
        assertThat(EraBoot.step(150L, 100L, 200L)).isWithin(1e-6f).of(0.75f)
    }
}
