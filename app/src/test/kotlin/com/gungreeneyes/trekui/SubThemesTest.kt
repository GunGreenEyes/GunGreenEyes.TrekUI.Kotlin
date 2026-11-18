package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Test

/** LCA-T35: the sub-theme tables ([SubThemes]), their lookup/clamp rules and the Imperial / Collective chrome tones. */
class SubThemesTest {

    @After
    fun reset() {
        Skin.faction = Skin.COMMAND
        Skin.useSubTheme(Skin.IMPERIAL, Palette.IMPERIAL[0])
        Skin.useSubTheme(Skin.COLLECTIVE, Palette.COLLECTIVE[0])
    }

    @Test
    fun counts_perFamily() {
        assertThat(SubThemes.count(Skin.COMMAND)).isEqualTo(11)
        assertThat(SubThemes.count(Skin.IMPERIAL)).isAtLeast(3)
        assertThat(SubThemes.count(Skin.IMPERIAL)).isAtMost(5)
        assertThat(SubThemes.count(Skin.COLLECTIVE)).isAtLeast(3)
        assertThat(SubThemes.count(Skin.COLLECTIVE)).isAtMost(5)
        assertThat(SubThemes.count(Skin.ERA23)).isEqualTo(Era.ERA23_THEMES.size)
        assertThat(SubThemes.count(Skin.ERA25)).isEqualTo(Era.ERA25_THEMES.size)
        for (f in Skin.INTERFACES) {
            assertThat(Palette.themesFor(f)).hasLength(SubThemes.count(f))
        }
    }

    @Test
    fun clamp_outOfRangeIsDefault() {
        assertThat(SubThemes.clamp(Skin.COMMAND, -1)).isEqualTo(0)
        assertThat(SubThemes.clamp(Skin.COMMAND, 10)).isEqualTo(10)
        assertThat(SubThemes.clamp(Skin.COMMAND, 11)).isEqualTo(0)
        val imperial = SubThemes.count(Skin.IMPERIAL)
        assertThat(SubThemes.clamp(Skin.IMPERIAL, imperial - 1)).isEqualTo(imperial - 1)
        assertThat(SubThemes.clamp(Skin.IMPERIAL, imperial)).isEqualTo(0)
        assertThat(SubThemes.clamp(Skin.FIRST_RETIRED, 2)).isEqualTo(0)
        assertThat(TrekPrefs.clampSubTheme(Skin.ERA23, Era.ERA23_THEMES.size)).isEqualTo(0)
        assertThat(Palette.of(Skin.COLLECTIVE, 99)).isSameInstanceAs(Palette.COLLECTIVE[0])
    }

    @Test
    fun commandKeepsLegacyIndexesAndAddsOwnerThemes() {
        val names = arrayOf(
            "CLASSIC", "MIDNIGHT BLUE", "RED ALERT", "ENGINEERING AMBER", "NEBULA VIOLET",
            "ASTROMETRICS TEAL", "DEEP BLUE", "SUNSET DECK", "NIGHT PADD", "STELLAR SILVER", "MODERN GRAY",
        )
        for (i in names.indices) assertThat(SubThemes.name(Skin.COMMAND, i)).isEqualTo(names[i])
        assertThat(Palette.ALL[0].c[0]).isEqualTo(0xFFEA9C72.toInt())
        assertThat(Palette.ALL[0].alert).isEqualTo(0xFFFF2200.toInt())
        assertThat(Palette.ALL[6].c[0]).isEqualTo(0xFF2266FF.toInt())
        assertThat(SubThemes.accents(Skin.COMMAND, 7)[0]).isEqualTo(0xFFFF7700.toInt())
        assertThat(SubThemes.name(Skin.FIRST_RETIRED, 0)).isEmpty()
        assertThat(SubThemes.accents(Skin.FIRST_RETIRED, 0)).isEmpty()
    }

    @Test
    fun defaultFactionThemesAreTheOriginalLooks() {
        val imperial = Palette.IMPERIAL[0]
        assertThat(imperial.c).asList().containsExactly(
            0xFFD8231E.toInt(), 0xFFF0821E.toInt(), 0xFFF5C830.toInt(),
            0xFFA3161A.toInt(), 0xFFE8562A.toInt(), 0xFFF2A43A.toInt(),
        ).inOrder()
        assertThat(imperial.bg).isEqualTo(0xFF050303.toInt())
        assertThat(imperial.surface).isEqualTo(0xFF140606.toInt())
        assertThat(imperial.grid).isEqualTo(0xFF4A0E0E.toInt())
        assertThat(imperial.detail).isEqualTo(0xFF2A0808.toInt())
        assertThat(imperial.ink).isEqualTo(0xFFF5B83A.toInt())
        assertThat(imperial.c[2]).isEqualTo(Skin.IMPERIAL_GLOW)
        val collective = Palette.COLLECTIVE[0]
        assertThat(collective.c).asList().containsExactly(
            0xFF79C98B.toInt(), 0xFFB1DC86.toInt(), 0xFF2B704A.toInt(),
            0xFF325846.toInt(), 0xFF73887F.toInt(), 0xFF123B2B.toInt(),
        ).inOrder()
        assertThat(collective.bg).isEqualTo(0xFF030706.toInt())
        assertThat(collective.surface).isEqualTo(0xFF06100C.toInt())
        assertThat(collective.grid).isEqualTo(0xFF123B2B.toInt())
        assertThat(collective.detail).isEqualTo(0xFF325846.toInt())
        assertThat(collective.ink).isEqualTo(0xFFC4D7C0.toInt())
        assertThat(collective.c[1]).isEqualTo(Skin.COLLECTIVE_GLOW)
        assertThat(Skin.IMPERIAL_PALETTE).isSameInstanceAs(imperial)
        assertThat(Skin.COLLECTIVE_PALETTE).isSameInstanceAs(collective)
    }

    @Test
    fun factionSpecsCarryChromeTones_commandAndErasDoNot() {
        for (spec in SubThemes.IMPERIAL) assertThat(spec).hasLength(SubThemes.SPEC_TONED_LENGTH)
        for (spec in SubThemes.COLLECTIVE) assertThat(spec).hasLength(SubThemes.SPEC_TONED_LENGTH)
        for (spec in SubThemes.COMMAND) assertThat(spec).hasLength(Era.SPEC_LENGTH)
        for (p in Palette.IMPERIAL) assertThat(p.toned).isTrue()
        for (p in Palette.ALL) assertThat(p.toned).isFalse()
    }

    @Test
    fun everyThemeIsReadable() {
        for (f in intArrayOf(Skin.COMMAND, Skin.IMPERIAL, Skin.COLLECTIVE)) {
            for (p in Palette.themesFor(f)) {
                val back = if (p.toned) p.bg else 0xFF000000.toInt()
                val what = p.name
                assertWithMessage("$what text hi").that(Era.contrast(p.textHi, p.panel)).isAtLeast(Era.MIN_TEXT_CONTRAST)
                assertWithMessage("$what text lo").that(Era.contrast(p.textLo, p.panel)).isAtLeast(Era.MIN_TEXT_CONTRAST)
                assertWithMessage("$what text lo on bg").that(Era.contrast(p.textLo, back)).isAtLeast(Era.MIN_TEXT_CONTRAST)
                assertWithMessage("$what primary").that(Era.contrast(p.c[0], back)).isAtLeast(Era.MIN_LINE_CONTRAST)
                if (p.toned) {
                    assertWithMessage("$what ink").that(Era.contrast(p.ink, p.surface)).isAtLeast(Era.MIN_TEXT_CONTRAST)
                }
            }
        }
    }

    @Test
    fun namesAreUniqueWithinAFamily() {
        for (f in intArrayOf(Skin.COMMAND, Skin.IMPERIAL, Skin.COLLECTIVE)) {
            val seen = HashSet<String>()
            for (i in 0 until SubThemes.count(f)) assertThat(seen.add(SubThemes.name(f, i))).isTrue()
        }
    }

    @Test
    fun useSubTheme_recoloursFactionChrome() {
        val ice = Palette.IMPERIAL[3]
        Skin.useSubTheme(Skin.IMPERIAL, ice)
        Skin.faction = Skin.IMPERIAL
        assertThat(Skin.bg()).isEqualTo(ice.bg)
        assertThat(Skin.surface()).isEqualTo(ice.surface)
        assertThat(Skin.grid()).isEqualTo(ice.grid)
        assertThat(ImperialDraw.RED).isEqualTo(ice.c[0])
        assertThat(ImperialDraw.YELLOW).isEqualTo(ice.c[2])
        assertThat(Skin.glow(0xFF101010.toInt())).isEqualTo(Skin.glowToward(0xFF101010.toInt(), ice.c[2]))

        val violet = Palette.COLLECTIVE[3]
        Skin.useSubTheme(Skin.COLLECTIVE, violet)
        Skin.faction = Skin.COLLECTIVE
        assertThat(Skin.bg()).isEqualTo(violet.bg)
        assertThat(Skin.collectiveLine()).isEqualTo(violet.c[2])
        assertThat(Skin.collectiveLit()).isEqualTo(violet.c[0])

        // Command palettes carry no tones and never change the faction chrome
        Skin.useSubTheme(Skin.IMPERIAL, Palette.ALL[1])
        assertThat(Skin.imperialTheme).isSameInstanceAs(ice)
    }

    @Test
    fun commandArt_mapsEveryThemeToBundledArt() {
        for (i in 0 until SubThemes.count(Skin.COMMAND)) {
            val art = SubThemes.commandArtIndex(i)
            assertThat(art).isAtLeast(0)
            assertThat(art).isLessThan(SubThemes.COMMAND_ART_THEMES)
            if (i < SubThemes.COMMAND_ART_THEMES) assertThat(art).isEqualTo(i)
        }
    }

    @Test
    fun themeKeys_perFaction() {
        assertThat(TrekPrefs.themeKeyFor(Skin.COMMAND)).isEqualTo("theme")
        assertThat(TrekPrefs.themeKeyFor(Skin.IMPERIAL)).isEqualTo("theme1")
        assertThat(TrekPrefs.themeKeyFor(Skin.COLLECTIVE)).isEqualTo("theme2")
        assertThat(TrekPrefs.themeKeyFor(Skin.ERA23)).isEqualTo("theme9")
    }
}
