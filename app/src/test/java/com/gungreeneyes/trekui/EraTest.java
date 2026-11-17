package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Pure rules of the 23rd- and 25th-century Command eras ({@link Era}): theme specs and their
 * contrast, lamp blinking, cut-corner polygons, tick rules, plus the era hooks in {@link Skin},
 * {@link Prefs}, {@link Palette}, {@link SettingsLogic} and {@link EraBoot}.
 */
public class EraTest {

    @Test
    public void themeSpecs_haveEveryFieldAndParse() {
        for (String[][] set : new String[][][]{Era.ERA23_THEMES, Era.ERA25_THEMES}) {
            assertThat(set.length).isAtLeast(2);
            for (String[] spec : set) {
                assertThat(spec).hasLength(Era.SPEC_LENGTH);
                assertThat(spec[Era.SPEC_NAME]).isNotEmpty();
                for (int i = 1; i < spec.length; i++) assertThat(Era.rgb(spec[i]) >>> 24).isEqualTo(0xFF);
            }
        }
    }

    @Test
    public void era23_everyKeyColourCarriesBlackLettering() {
        for (String[] spec : Era.ERA23_THEMES) {
            for (int i = 0; i < Era.SPEC_ACCENTS; i++) {
                int accent = Era.rgb(spec[Era.SPEC_ACCENT0 + i]);
                assertThat(Era.contrast(accent, Era.ERA23_INK)).isAtLeast(Era.MIN_TEXT_CONTRAST);
            }
            assertThat(Era.contrast(Era.rgb(spec[Era.SPEC_TEXT_HI]), Era.ERA23_BG)).isAtLeast(Era.MIN_TEXT_CONTRAST);
            assertThat(Era.contrast(Era.rgb(spec[Era.SPEC_TEXT_LO]), Era.ERA23_BG)).isAtLeast(Era.MIN_TEXT_CONTRAST);
        }
    }

    @Test
    public void era25_linesShowOnTheBackgroundAndLetteringReadsOnGlass() {
        for (String[] spec : Era.ERA25_THEMES) {
            for (int i = 0; i < Era.SPEC_ACCENTS; i++) {
                int accent = Era.rgb(spec[Era.SPEC_ACCENT0 + i]);
                assertThat(Era.contrast(accent, Era.ERA25_BG)).isAtLeast(Era.MIN_LINE_CONTRAST);
                assertThat(Era.contrast(Era.ERA25_INK, Era.glassFill(accent))).isAtLeast(Era.MIN_TEXT_CONTRAST);
            }
            assertThat(Era.contrast(Era.rgb(spec[Era.SPEC_TEXT_HI]), Era.ERA25_BG)).isAtLeast(Era.MIN_TEXT_CONTRAST);
            assertThat(Era.contrast(Era.rgb(spec[Era.SPEC_TEXT_LO]), Era.ERA25_BG)).isAtLeast(Era.MIN_TEXT_CONTRAST);
        }
    }

    @Test
    public void rgbAndContrast_knownValues() {
        assertThat(Era.rgb("#FF8000")).isEqualTo(0xFFFF8000);
        assertThat(Era.rgb("80FF8000")).isEqualTo(0x80FF8000);
        assertThat(Era.contrast(0xFF000000, 0xFFFFFFFF)).isWithin(1e-9).of(21.0);
        assertThat(Era.contrast(0xFF777777, 0xFF777777)).isWithin(1e-9).of(1.0);
        assertThat(Era.relLuminance(0xFFFFFFFF)).isWithin(1e-9).of(1.0);
    }

    @Test
    public void glassFill_isATintOfTheBackground() {
        assertThat(Era.glassFill(Era.ERA25_BG)).isEqualTo(Era.ERA25_BG);
        assertThat(Era.relLuminance(Era.glassFill(0xFFFFFFFF))).isLessThan(0.1);
    }

    @Test
    public void themeIndex_clampsPerEra() {
        assertThat(Era.themeIndex(Skin.ERA23, 1)).isEqualTo(1);
        assertThat(Era.themeIndex(Skin.ERA23, Era.ERA23_THEMES.length)).isEqualTo(0);
        assertThat(Era.themeIndex(Skin.ERA25, -1)).isEqualTo(0);
        assertThat(Era.themes(Skin.IMPERIAL)).isEmpty();
        assertThat(Palette.themesFor(Skin.ERA23)).hasLength(Era.ERA23_THEMES.length);
        assertThat(Palette.themesFor(Skin.ERA25)).hasLength(Era.ERA25_THEMES.length);
        assertThat(Palette.themesFor(Skin.COMMAND)).isSameInstanceAs(Palette.ALL);
    }

    @Test
    public void lamps_steadyOnesStayLit() {
        for (int seed = 0; seed < 200; seed++) {
            if (Era.blinks(seed)) continue;
            assertThat(Era.lampOn(0, seed)).isTrue();
            assertThat(Era.lampOn(123_456, seed)).isTrue();
            assertThat(Era.nextToggleIn(5_000, seed)).isEqualTo(-1);
        }
    }

    @Test
    public void lamps_blinkingOnesToggleExactlyWhenPredicted() {
        int blinking = 0;
        for (int seed = 0; seed < 300; seed++) {
            if (!Era.blinks(seed)) continue;
            blinking++;
            for (long t : new long[]{0, 777, 10_000, 98_765}) {
                long next = Era.nextToggleIn(t, seed);
                assertThat(next).isAtLeast(1);
                assertThat(next).isAtMost(Era.halfPeriod(seed));
                assertThat(Era.lampOn(t + next - 1, seed)).isEqualTo(Era.lampOn(t, seed));
                assertThat(Era.lampOn(t + next, seed)).isNotEqualTo(Era.lampOn(t, seed));
            }
        }
        // about a third of the lamps blink
        assertThat(blinking).isAtLeast(60);
        assertThat(blinking).isAtMost(140);
    }

    @Test
    public void lamps_halfPeriodStaysInItsBand() {
        for (int seed = 0; seed < 100; seed++) {
            assertThat(Era.halfPeriod(seed)).isAtLeast(Era.BLINK_MS);
            assertThat(Era.halfPeriod(seed)).isLessThan(Era.BLINK_MS * 7 / 4);
        }
    }

    @Test
    public void lampColor_isAlwaysALampColour() {
        for (int seed = -50; seed < 50; seed++) {
            for (int i = 0; i < Era.MAX_LAMPS; i++) {
                int c = Era.lampColor(seed, i);
                boolean known = false;
                for (int lamp : Era.LAMPS) known |= lamp == c;
                assertThat(known).isTrue();
            }
        }
    }

    @Test
    public void lampCount_bySize() {
        float d = 2f;
        assertThat(Era.lampCount(20 * d, 50 * d, d)).isEqualTo(0); // swatch
        assertThat(Era.lampCount(100 * d, 12 * d, d)).isEqualTo(0); // thin bar
        assertThat(Era.lampCount(100 * d, 30 * d, d)).isEqualTo(1); // button
        assertThat(Era.lampCount(160 * d, 78 * d, d)).isEqualTo(Era.MAX_LAMPS); // tile
        int sidebar = Era.lampCount(64 * d, 62 * d, d);
        assertThat(sidebar).isAtLeast(1);
        assertThat(sidebar).isAtMost(Era.MAX_LAMPS);
    }

    @Test
    public void cutPolygon_pointCountFollowsTheCutCorners() {
        assertThat(Era.cutPolygon(0, 0, 100, 40, 10, 0)).hasLength(8);
        assertThat(Era.cutPolygon(0, 0, 100, 40, 10, Era.TL | Era.BR)).hasLength(12);
        assertThat(Era.cutPolygon(0, 0, 100, 40, 10, Era.TL | Era.TR | Era.BR | Era.BL)).hasLength(16);
        assertThat(Era.cutPolygon(0, 0, 100, 40, 0, Era.TL)).hasLength(8);
    }

    @Test
    public void cutPolygon_cutsAt45DegreesAndStaysInside() {
        float[] p = Era.cutPolygon(10, 20, 110, 60, 8, Era.TL | Era.BR);
        // top-left cut: (10, 28) -> (18, 20)
        assertThat(p[0]).isEqualTo(10f);
        assertThat(p[1]).isEqualTo(28f);
        assertThat(p[2]).isEqualTo(18f);
        assertThat(p[3]).isEqualTo(20f);
        for (int i = 0; i < p.length; i += 2) {
            assertThat(p[i]).isAtLeast(10f);
            assertThat(p[i]).isAtMost(110f);
            assertThat(p[i + 1]).isAtLeast(20f);
            assertThat(p[i + 1]).isAtMost(60f);
        }
        // the cut is clamped to half the shorter side
        float[] q = Era.cutPolygon(0, 0, 100, 10, 50, Era.TL);
        assertThat(q[1]).isEqualTo(5f);
        assertThat(q[2]).isEqualTo(5f);
    }

    @Test
    public void cutsFor_kinds() {
        assertThat(Era.cutsFor(Ui.SQUARE)).isEqualTo(Era.TL | Era.BR);
        assertThat(Era.cutsFor(Ui.LEFT)).isEqualTo(Era.TL | Era.BL);
        assertThat(Era.cutsFor(Ui.RIGHT)).isEqualTo(Era.TR | Era.BR);
        assertThat(Era.cutsFor(Ui.PILL)).isEqualTo(Era.TL | Era.TR | Era.BR | Era.BL);
    }

    @Test
    public void cutSize_isAShareOfTheShorterSideCapped() {
        float d = 3f;
        assertThat(Era.cutSize(200, 30, d)).isWithin(1e-4f).of(30 * Era.CUT_SHARE);
        assertThat(Era.cutSize(2000, 1000, d)).isWithin(1e-4f).of(Era.MAX_CUT_DP * d);
        assertThat(Era.cutSize(0, 50, d)).isEqualTo(0f);
    }

    @Test
    public void tickCount_spacing() {
        float d = 2f;
        assertThat(Era.tickCount(5 * d, d)).isEqualTo(0);
        assertThat(Era.tickCount(Era.TICK_SPACING_DP * d, d)).isEqualTo(2);
        assertThat(Era.tickCount(Era.TICK_SPACING_DP * d * 10, d)).isEqualTo(11);
    }

    @Test
    public void skinPredicates_erasAreCommandFamilyNotPipe() {
        int saved = Skin.faction;
        try {
            for (int f : new int[]{Skin.ERA23, Skin.ERA25}) {
                Skin.faction = f;
                assertThat(Skin.command()).isTrue();
                assertThat(Skin.era()).isTrue();
                assertThat(Skin.pipe()).isFalse();
                assertThat(Skin.scene()).isFalse();
                assertThat(Skin.imperial() || Skin.collective()).isFalse();
                assertThat(Skin.title()).isNotEmpty();
                assertThat(Skin.ship("COMMAND DEFAULT")).isNotEqualTo("COMMAND DEFAULT");
            }
            Skin.faction = Skin.COMMAND;
            assertThat(Skin.era()).isFalse();
            assertThat(Skin.ship("COMMAND DEFAULT")).isEqualTo("COMMAND DEFAULT");
            assertThat(Skin.commandFamily(Skin.IMPERIAL)).isFalse();
        } finally {
            Skin.faction = saved;
        }
    }

    @Test
    public void prefsAndSettings_knowTheEras() {
        assertThat(Prefs.clampFaction(Skin.ERA23)).isEqualTo(Skin.ERA23);
        assertThat(Prefs.clampFaction(Skin.ERA25)).isEqualTo(Skin.ERA25);
        assertThat(Prefs.themeKeyFor(Skin.COMMAND)).isEqualTo("theme");
        assertThat(Prefs.themeKeyFor(Skin.ERA23)).isEqualTo("theme9");
        assertThat(Prefs.themeKeyFor(Skin.ERA25)).isEqualTo("theme10");
        assertThat(Prefs.layoutKeyFor(Skin.ERA23)).isEqualTo(Prefs.layoutKeyFor(Skin.COMMAND));
        assertThat(SettingsLogic.isStarshipFaction(Skin.ERA25)).isTrue();
        assertThat(SettingsLogic.isStarshipFaction(Skin.BRIAR)).isFalse();
        assertThat(SettingsLogic.familyTarget(false, 3, Skin.ERA23)).isEqualTo(Skin.ERA23);
        assertThat(SettingsLogic.familyTarget(true, 3, Skin.ERA23)).isEqualTo(3);
        assertThat(Sfx.FACTION_DEFAULT).hasLength(Skin.NAMES.length);
    }

    @Test
    public void eraBoot_timings() {
        for (int f : new int[]{Skin.ERA23, Skin.ERA25}) {
            assertThat(EraBoot.fadeAtFor(f)).isLessThan(EraBoot.durationFor(f));
            assertThat(EraBoot.durationFor(f)).isAtMost(4000L);
        }
        assertThat(EraBoot.step(0, 100, 200)).isEqualTo(0f);
        assertThat(EraBoot.step(300, 100, 200)).isEqualTo(1f);
        assertThat(EraBoot.step(150, 100, 200)).isWithin(1e-6f).of(0.75f);
    }
}
