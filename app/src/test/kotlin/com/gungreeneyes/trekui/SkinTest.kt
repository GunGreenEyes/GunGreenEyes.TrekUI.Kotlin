package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test

/** Pure rules in [Skin]: the interface list, faction families, colour mixing, glow lifting and tray radii. */
class SkinTest {

    @After
    fun resetFaction() {
        Skin.faction = Skin.COMMAND
    }

    @Test
    fun interfaces_areTheFiveStarshipConsolesWithStableIds() {
        assertThat(Skin.INTERFACES).asList()
            .containsExactly(Skin.COMMAND, Skin.IMPERIAL, Skin.COLLECTIVE, Skin.ERA23, Skin.ERA25).inOrder()
        assertThat(Skin.COMMAND).isEqualTo(0)
        assertThat(Skin.IMPERIAL).isEqualTo(1)
        assertThat(Skin.COLLECTIVE).isEqualTo(2)
        assertThat(Skin.ERA23).isEqualTo(9)
        assertThat(Skin.ERA25).isEqualTo(10)
    }

    @Test
    fun exists_onlyForTheOfferedInterfaces() {
        for (f in -1..Skin.NAMES.size) {
            val offered = f == Skin.COMMAND || f == Skin.IMPERIAL || f == Skin.COLLECTIVE || f == Skin.ERA23 || f == Skin.ERA25
            assertThat(Skin.exists(f)).isEqualTo(offered)
        }
        for (f in Skin.FIRST_RETIRED..Skin.LAST_RETIRED) assertThat(Skin.exists(f)).isFalse()
    }

    @Test
    fun namesAndSubtitlesCoverEveryFaction() {
        assertThat(Skin.SUBTITLES).hasLength(Skin.NAMES.size)
        assertThat(Skin.NAMES).hasLength(Skin.ERA25 + 1)
        for (f in Skin.INTERFACES) {
            assertThat(Skin.NAMES[f]).isNotEmpty()
            assertThat(Skin.SUBTITLES[f]).isNotEmpty()
        }
    }

    @Test
    fun interfaceNames_followPickerOrder() {
        val names = Skin.interfaceNames()
        assertThat(names).hasLength(Skin.INTERFACES.size)
        for (n in names.indices) {
            assertThat(names[n]).isEqualTo(Skin.NAMES[Skin.INTERFACES[n]])
            assertThat(Skin.interfaceIndex(Skin.INTERFACES[n])).isEqualTo(n)
        }
        assertThat(Skin.interfaceIndex(5)).isEqualTo(0)
    }

    @Test
    fun factionPredicates() {
        Skin.faction = Skin.ERA23
        assertThat(Skin.command()).isTrue()
        assertThat(Skin.era()).isTrue()
        Skin.faction = Skin.COLLECTIVE
        assertThat(Skin.collective()).isTrue()
        assertThat(Skin.command()).isFalse()
    }

    @Test
    fun mix_endpointsAndMidpoint() {
        assertThat(Skin.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0f)).isEqualTo(0xFF000000.toInt())
        assertThat(Skin.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 1f)).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(Skin.mix(0xFF000000.toInt(), 0xFFFF8040.toInt(), 0.5f)).isEqualTo(0xFF804020.toInt())
    }

    @Test
    fun mix_resultIsOpaqueEvenFromTranslucentInputs() {
        assertThat(Skin.mix(0x10203040, 0x10203040, 0.3f)).isEqualTo(0xFF203040.toInt())
    }

    @Test
    fun luminance_blackWhiteAndGreenWeight() {
        assertThat(Skin.luminance(0xFF000000.toInt())).isWithin(1e-6f).of(0f)
        assertThat(Skin.luminance(0xFFFFFFFF.toInt())).isWithin(1e-4f).of(1f)
        assertThat(Skin.luminance(0xFF00FF00.toInt())).isWithin(1e-4f).of(0.7152f)
    }

    @Test
    fun glowToward_keepsBrightColours() {
        assertThat(Skin.glowToward(0xFFFFFFFF.toInt(), Skin.COLLECTIVE_GLOW)).isEqualTo(0xFFFFFFFF.toInt())
        assertThat(Skin.glowToward(0xFF79C98B.toInt(), Skin.COLLECTIVE_GLOW)).isEqualTo(0xFF79C98B.toInt())
    }

    @Test
    fun glowToward_liftsDarkColoursAndKeepsAlpha() {
        val dark = 0x80123B2B.toInt() // Collective conduit, half transparent
        val lifted = Skin.glowToward(dark, Skin.COLLECTIVE_GLOW)
        assertThat(lifted ushr 24).isEqualTo(0x80)
        assertThat(Skin.luminance(lifted)).isGreaterThan(Skin.luminance(dark))
        // black is lifted by the 75% cap
        assertThat(Skin.glowToward(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
            .isEqualTo(Skin.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.75f))
    }

    @Test
    fun glow_passesThroughForTheCommandFamily() {
        Skin.faction = Skin.COMMAND
        assertThat(Skin.glow(0xFF101010.toInt())).isEqualTo(0xFF101010.toInt())
        Skin.faction = Skin.ERA25
        assertThat(Skin.glow(0xFF101010.toInt())).isEqualTo(0xFF101010.toInt())
        Skin.faction = Skin.IMPERIAL
        assertThat(Skin.glow(0xFF101010.toInt())).isEqualTo(Skin.glowToward(0xFF101010.toInt(), Skin.IMPERIAL_GLOW))
    }

    @Test
    fun trayRadius_perFaction() {
        assertThat(Skin.trayRadiusFor(Skin.COMMAND, 26f)).isEqualTo(26f)
        assertThat(Skin.trayRadiusFor(Skin.IMPERIAL, 26f)).isEqualTo(1f)
        assertThat(Skin.trayRadiusFor(Skin.COLLECTIVE, 26f)).isEqualTo(22f)
        assertThat(Skin.trayRadiusFor(Skin.ERA23, 26f)).isEqualTo(Skin.ERA23_RADIUS)
        assertThat(Skin.trayRadiusFor(Skin.ERA23, 1f)).isEqualTo(1f)
        assertThat(Skin.trayRadiusFor(Skin.ERA25, 26f)).isEqualTo(0f)
    }

    @Test
    fun textScaleNeverGrowsText() {
        for (f in Skin.INTERFACES) {
            Skin.faction = f
            assertThat(Skin.textScale()).isAtMost(1f)
            assertThat(Skin.textScale()).isGreaterThan(0.7f)
        }
    }

    @Test
    fun navLabelsExistForEveryFaction() {
        for (f in Skin.INTERFACES) {
            Skin.faction = f
            for (i in 0 until 3) assertThat(Skin.nav(i)).isNotEmpty()
        }
    }
}
