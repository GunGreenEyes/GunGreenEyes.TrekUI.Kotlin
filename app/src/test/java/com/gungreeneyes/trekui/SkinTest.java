package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.After;
import org.junit.Test;

/** Pure rules in {@link Skin}: faction families, colour mixing, glow lifting and tray radii. */
public class SkinTest {

    @After
    public void resetFaction() {
        Skin.faction = Skin.COMMAND;
    }

    @Test
    public void materialFamilies_scenesBorrowA60Family() {
        assertThat(Skin.mat(Skin.BRIAR)).isEqualTo(Skin.TOBACCONIST);
        assertThat(Skin.mat(Skin.LEDGER)).isEqualTo(Skin.JOURNAL);
        assertThat(Skin.mat(Skin.MIDNIGHT)).isEqualTo(Skin.LOUNGE);
        for (int f = Skin.COMMAND; f <= Skin.JOURNAL; f++) assertThat(Skin.mat(f)).isEqualTo(f);
    }

    @Test
    public void pipeFaction_coversThreeToEight() {
        for (int f = 0; f < Skin.NAMES.length; f++) assertThat(Skin.pipeFaction(f)).isEqualTo(f >= 3 && f <= 8);
        assertThat(Skin.pipeFaction(9)).isFalse();
        assertThat(Skin.pipeFaction(-1)).isFalse();
    }

    @Test
    public void namesAndSubtitlesCoverEveryFaction() {
        assertThat(Skin.SUBTITLES).hasLength(Skin.NAMES.length);
        assertThat(Skin.NAMES).hasLength(Skin.ERA25 + 1);
    }

    @Test
    public void factionPredicates() {
        Skin.faction = Skin.LEDGER;
        assertThat(Skin.pipe()).isTrue();
        assertThat(Skin.scene()).isTrue();
        assertThat(Skin.mJournal()).isTrue();
        assertThat(Skin.journal()).isFalse();
        Skin.faction = Skin.LOUNGE;
        assertThat(Skin.scene()).isFalse();
        assertThat(Skin.mLounge()).isTrue();
        Skin.faction = Skin.COLLECTIVE;
        assertThat(Skin.pipe()).isFalse();
    }

    @Test
    public void mix_endpointsAndMidpoint() {
        assertThat(Skin.mix(0xFF000000, 0xFFFFFFFF, 0f)).isEqualTo(0xFF000000);
        assertThat(Skin.mix(0xFF000000, 0xFFFFFFFF, 1f)).isEqualTo(0xFFFFFFFF);
        assertThat(Skin.mix(0xFF000000, 0xFFFF8040, 0.5f)).isEqualTo(0xFF804020);
    }

    @Test
    public void mix_resultIsOpaqueEvenFromTranslucentInputs() {
        assertThat(Skin.mix(0x10203040, 0x10203040, 0.3f)).isEqualTo(0xFF203040);
    }

    @Test
    public void luminance_blackWhiteAndGreenWeight() {
        assertThat(Skin.luminance(0xFF000000)).isWithin(1e-6f).of(0f);
        assertThat(Skin.luminance(0xFFFFFFFF)).isWithin(1e-4f).of(1f);
        assertThat(Skin.luminance(0xFF00FF00)).isWithin(1e-4f).of(0.7152f);
    }

    @Test
    public void glowToward_keepsBrightColours() {
        assertThat(Skin.glowToward(0xFFFFFFFF, Skin.COLLECTIVE_GLOW)).isEqualTo(0xFFFFFFFF);
        assertThat(Skin.glowToward(0xFF79C98B, Skin.COLLECTIVE_GLOW)).isEqualTo(0xFF79C98B);
    }

    @Test
    public void glowToward_liftsDarkColoursAndKeepsAlpha() {
        int dark = 0x80123B2B; // Collective conduit, half transparent
        int lifted = Skin.glowToward(dark, Skin.COLLECTIVE_GLOW);
        assertThat(lifted >>> 24).isEqualTo(0x80);
        assertThat(Skin.luminance(lifted)).isGreaterThan(Skin.luminance(dark));
        // black is lifted by the 75% cap
        assertThat(Skin.glowToward(0xFF000000, 0xFFFFFFFF)).isEqualTo(Skin.mix(0xFF000000, 0xFFFFFFFF, 0.75f));
    }

    @Test
    public void glow_passesThroughForCommandAndPipe() {
        Skin.faction = Skin.COMMAND;
        assertThat(Skin.glow(0xFF101010)).isEqualTo(0xFF101010);
        Skin.faction = Skin.MIDNIGHT;
        assertThat(Skin.glow(0xFF101010)).isEqualTo(0xFF101010);
        Skin.faction = Skin.IMPERIAL;
        assertThat(Skin.glow(0xFF101010)).isEqualTo(Skin.glowToward(0xFF101010, Skin.IMPERIAL_GLOW));
    }

    @Test
    public void trayRadius_perFaction() {
        assertThat(Skin.trayRadiusFor(Skin.COMMAND, 26f)).isEqualTo(26f);
        assertThat(Skin.trayRadiusFor(Skin.IMPERIAL, 26f)).isEqualTo(1f);
        assertThat(Skin.trayRadiusFor(Skin.COLLECTIVE, 26f)).isEqualTo(22f);
        assertThat(Skin.trayRadiusFor(Skin.TOBACCONIST, 26f)).isEqualTo(4f);
        assertThat(Skin.trayRadiusFor(Skin.BRIAR, 26f)).isEqualTo(4f);
        assertThat(Skin.trayRadiusFor(Skin.LOUNGE, 26f)).isEqualTo(1f);
        assertThat(Skin.trayRadiusFor(Skin.MIDNIGHT, 26f)).isEqualTo(1f);
        assertThat(Skin.trayRadiusFor(Skin.JOURNAL, 26f)).isEqualTo(14f);
        assertThat(Skin.trayRadiusFor(Skin.LEDGER, 26f)).isEqualTo(14f);
        assertThat(Skin.trayRadiusFor(Skin.ERA23, 26f)).isEqualTo(Skin.ERA23_RADIUS);
        assertThat(Skin.trayRadiusFor(Skin.ERA23, 1f)).isEqualTo(1f);
        assertThat(Skin.trayRadiusFor(Skin.ERA25, 26f)).isEqualTo(0f);
    }

    @Test
    public void textScaleNeverGrowsText() {
        for (int f = 0; f < Skin.NAMES.length; f++) {
            Skin.faction = f;
            assertThat(Skin.textScale()).isAtMost(1f);
            assertThat(Skin.textScale()).isGreaterThan(0.7f);
        }
    }

    @Test
    public void navLabelsExistForEveryFaction() {
        for (int f = 0; f < Skin.NAMES.length; f++) {
            Skin.faction = f;
            for (int i = 0; i < 3; i++) assertThat(Skin.nav(i)).isNotEmpty();
        }
    }
}
