package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import org.junit.After;
import org.junit.Test;

/** LCA-T35: the sub-theme tables ({@link SubThemes}), their lookup/clamp rules and the Imperial / Collective chrome tones. */
public class SubThemesTest {

    @After
    public void reset() {
        Skin.faction = Skin.COMMAND;
        Skin.useSubTheme(Skin.IMPERIAL, Palette.IMPERIAL[0]);
        Skin.useSubTheme(Skin.COLLECTIVE, Palette.COLLECTIVE[0]);
    }

    @Test
    public void counts_perFamily() {
        assertThat(SubThemes.count(Skin.COMMAND)).isEqualTo(11);
        assertThat(SubThemes.count(Skin.IMPERIAL)).isAtLeast(3);
        assertThat(SubThemes.count(Skin.IMPERIAL)).isAtMost(5);
        assertThat(SubThemes.count(Skin.COLLECTIVE)).isAtLeast(3);
        assertThat(SubThemes.count(Skin.COLLECTIVE)).isAtMost(5);
        assertThat(SubThemes.count(Skin.ERA23)).isEqualTo(Era.ERA23_THEMES.length);
        assertThat(SubThemes.count(Skin.ERA25)).isEqualTo(Era.ERA25_THEMES.length);
        for (int f = Skin.FIRST_PIPE; f <= Skin.LAST_PIPE; f++) {
            assertThat(SubThemes.count(f)).isEqualTo(1);
            assertThat(SubThemes.selectable(f)).isFalse();
            assertThat(Palette.themesFor(f)).hasLength(1);
            assertThat(Palette.themesFor(f)[0]).isSameInstanceAs(Skin.pipePalette(f));
        }
        for (int f = 0; f <= TrekPrefs.MAX_FACTION; f++) {
            assertThat(Palette.themesFor(f)).hasLength(SubThemes.count(f));
        }
    }

    @Test
    public void clamp_outOfRangeIsDefault() {
        assertThat(SubThemes.clamp(Skin.COMMAND, -1)).isEqualTo(0);
        assertThat(SubThemes.clamp(Skin.COMMAND, 10)).isEqualTo(10);
        assertThat(SubThemes.clamp(Skin.COMMAND, 11)).isEqualTo(0);
        int imperial = SubThemes.count(Skin.IMPERIAL);
        assertThat(SubThemes.clamp(Skin.IMPERIAL, imperial - 1)).isEqualTo(imperial - 1);
        assertThat(SubThemes.clamp(Skin.IMPERIAL, imperial)).isEqualTo(0);
        assertThat(SubThemes.clamp(Skin.TOBACCONIST, 2)).isEqualTo(0);
        assertThat(TrekPrefs.clampSubTheme(Skin.ERA23, Era.ERA23_THEMES.length)).isEqualTo(0);
        assertThat(Palette.of(Skin.COLLECTIVE, 99)).isSameInstanceAs(Palette.COLLECTIVE[0]);
    }

    @Test
    public void commandKeepsLegacyIndexesAndAddsOwnerThemes() {
        String[] names = {"CLASSIC", "MIDNIGHT BLUE", "RED ALERT", "ENGINEERING AMBER", "NEBULA VIOLET",
                "ASTROMETRICS TEAL", "DEEP BLUE", "SUNSET DECK", "NIGHT PADD", "STELLAR SILVER", "MODERN GRAY"};
        for (int i = 0; i < names.length; i++) assertThat(SubThemes.name(Skin.COMMAND, i)).isEqualTo(names[i]);
        assertThat(Palette.ALL[0].c[0]).isEqualTo(0xFFEA9C72);
        assertThat(Palette.ALL[0].alert).isEqualTo(0xFFFF2200);
        assertThat(Palette.ALL[6].c[0]).isEqualTo(0xFF2266FF);
        assertThat(SubThemes.accents(Skin.COMMAND, 7)[0]).isEqualTo(0xFFFF7700);
        assertThat(SubThemes.name(Skin.LOUNGE, 0)).isEmpty();
        assertThat(SubThemes.accents(Skin.LOUNGE, 0)).isEmpty();
    }

    @Test
    public void defaultFactionThemesAreTheOriginalLooks() {
        Palette imperial = Palette.IMPERIAL[0];
        assertThat(imperial.c).asList().containsExactly(0xFFD8231E, 0xFFF0821E, 0xFFF5C830, 0xFFA3161A, 0xFFE8562A, 0xFFF2A43A).inOrder();
        assertThat(imperial.bg).isEqualTo(0xFF050303);
        assertThat(imperial.surface).isEqualTo(0xFF140606);
        assertThat(imperial.grid).isEqualTo(0xFF4A0E0E);
        assertThat(imperial.detail).isEqualTo(0xFF2A0808);
        assertThat(imperial.ink).isEqualTo(0xFFF5B83A);
        assertThat(imperial.c[2]).isEqualTo(Skin.IMPERIAL_GLOW);
        Palette collective = Palette.COLLECTIVE[0];
        assertThat(collective.c).asList().containsExactly(0xFF79C98B, 0xFFB1DC86, 0xFF2B704A, 0xFF325846, 0xFF73887F, 0xFF123B2B).inOrder();
        assertThat(collective.bg).isEqualTo(0xFF030706);
        assertThat(collective.surface).isEqualTo(0xFF06100C);
        assertThat(collective.grid).isEqualTo(0xFF123B2B);
        assertThat(collective.detail).isEqualTo(0xFF325846);
        assertThat(collective.ink).isEqualTo(0xFFC4D7C0);
        assertThat(collective.c[1]).isEqualTo(Skin.COLLECTIVE_GLOW);
        assertThat(Skin.IMPERIAL_PALETTE).isSameInstanceAs(imperial);
        assertThat(Skin.COLLECTIVE_PALETTE).isSameInstanceAs(collective);
    }

    @Test
    public void factionSpecsCarryChromeTones_commandAndErasDoNot() {
        for (String[] spec : SubThemes.IMPERIAL) assertThat(spec).hasLength(SubThemes.SPEC_TONED_LENGTH);
        for (String[] spec : SubThemes.COLLECTIVE) assertThat(spec).hasLength(SubThemes.SPEC_TONED_LENGTH);
        for (String[] spec : SubThemes.COMMAND) assertThat(spec).hasLength(Era.SPEC_LENGTH);
        for (Palette p : Palette.IMPERIAL) assertThat(p.toned).isTrue();
        for (Palette p : Palette.ALL) assertThat(p.toned).isFalse();
    }

    @Test
    public void everyThemeIsReadable() {
        for (int f : new int[]{Skin.COMMAND, Skin.IMPERIAL, Skin.COLLECTIVE}) {
            for (Palette p : Palette.themesFor(f)) {
                int back = p.toned ? p.bg : 0xFF000000;
                String what = p.name;
                assertWithMessage(what + " text hi").that(Era.contrast(p.textHi, p.panel)).isAtLeast(Era.MIN_TEXT_CONTRAST);
                assertWithMessage(what + " text lo").that(Era.contrast(p.textLo, p.panel)).isAtLeast(Era.MIN_TEXT_CONTRAST);
                assertWithMessage(what + " text lo on bg").that(Era.contrast(p.textLo, back)).isAtLeast(Era.MIN_TEXT_CONTRAST);
                assertWithMessage(what + " primary").that(Era.contrast(p.c[0], back)).isAtLeast(Era.MIN_LINE_CONTRAST);
                if (p.toned) {
                    assertWithMessage(what + " ink").that(Era.contrast(p.ink, p.surface)).isAtLeast(Era.MIN_TEXT_CONTRAST);
                }
            }
        }
    }

    @Test
    public void namesAreUniqueWithinAFamily() {
        for (int f : new int[]{Skin.COMMAND, Skin.IMPERIAL, Skin.COLLECTIVE}) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < SubThemes.count(f); i++) assertThat(seen.add(SubThemes.name(f, i))).isTrue();
        }
    }

    @Test
    public void useSubTheme_recoloursFactionChrome() {
        Palette ice = Palette.IMPERIAL[3];
        Skin.useSubTheme(Skin.IMPERIAL, ice);
        Skin.faction = Skin.IMPERIAL;
        assertThat(Skin.bg()).isEqualTo(ice.bg);
        assertThat(Skin.surface()).isEqualTo(ice.surface);
        assertThat(Skin.grid()).isEqualTo(ice.grid);
        assertThat(ImperialDraw.RED).isEqualTo(ice.c[0]);
        assertThat(ImperialDraw.YELLOW).isEqualTo(ice.c[2]);
        assertThat(Skin.glow(0xFF101010)).isEqualTo(Skin.glowToward(0xFF101010, ice.c[2]));

        Palette violet = Palette.COLLECTIVE[3];
        Skin.useSubTheme(Skin.COLLECTIVE, violet);
        Skin.faction = Skin.COLLECTIVE;
        assertThat(Skin.bg()).isEqualTo(violet.bg);
        assertThat(Skin.collectiveLine()).isEqualTo(violet.c[2]);
        assertThat(Skin.collectiveLit()).isEqualTo(violet.c[0]);

        // Command palettes carry no tones and never change the faction chrome
        Skin.useSubTheme(Skin.IMPERIAL, Palette.ALL[1]);
        assertThat(Skin.imperialTheme).isSameInstanceAs(ice);
    }

    @Test
    public void commandArt_mapsEveryThemeToBundledArt() {
        for (int i = 0; i < SubThemes.count(Skin.COMMAND); i++) {
            int art = SubThemes.commandArtIndex(i);
            assertThat(art).isAtLeast(0);
            assertThat(art).isLessThan(SubThemes.COMMAND_ART_THEMES);
            if (i < SubThemes.COMMAND_ART_THEMES) assertThat(art).isEqualTo(i);
        }
    }

    @Test
    public void themeKeys_perFaction() {
        assertThat(TrekPrefs.themeKeyFor(Skin.COMMAND)).isEqualTo("theme");
        assertThat(TrekPrefs.themeKeyFor(Skin.IMPERIAL)).isEqualTo("theme1");
        assertThat(TrekPrefs.themeKeyFor(Skin.COLLECTIVE)).isEqualTo("theme2");
        assertThat(TrekPrefs.themeKeyFor(Skin.ERA23)).isEqualTo("theme9");
    }
}
