package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/** JVM tests for the SOUND SET decision (library / synthesized / silent) and the synthesized set's wiring. */
class SfxTest {

    @Test
    fun library_usesTheLibraryClipWhenPlayable() {
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, "ui_panel_open", Sfx.E_NAV, true)).isEqualTo("ui_panel_open")
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, Sfx.TAP_SET, Sfx.E_BUTTON, true)).isEqualTo(Sfx.TAP_SET)
    }

    @Test
    fun library_fallsBackToSynthWhenNotDownloaded() {
        // the Play build out of the box: nothing downloaded, synthesized sounds play
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, "ui_panel_open", Sfx.E_NAV, false)).isEqualTo("synth_nav")
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, Sfx.TAP_SET, Sfx.E_BUTTON, false)).isEqualTo(Sfx.SYNTH_TAPS)
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, "alert_red_tng", Sfx.E_RED, false)).isEqualTo("synth_red_alert")
    }

    @Test
    fun synth_ignoresTheLibrary() {
        assertThat(Sfx.eventClip(Sfx.SET_SYNTH, "ui_panel_open", Sfx.E_NAV, true)).isEqualTo("synth_nav")
    }

    @Test
    fun userSynthPickIsKeptInAnySoundingSet() {
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, "synth_confirm", Sfx.E_NAV, true)).isEqualTo("synth_confirm")
        assertThat(Sfx.eventClip(Sfx.SET_SYNTH, "synth_confirm", Sfx.E_NAV, true)).isEqualTo("synth_confirm")
    }

    @Test
    fun silentAndNoneStaySilent() {
        assertThat(Sfx.eventClip(Sfx.SET_SILENT, "ui_panel_open", Sfx.E_NAV, true)).isEqualTo("none")
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, "none", Sfx.E_NAV, false)).isEqualTo("none")
        assertThat(Sfx.eventClip(Sfx.SET_SYNTH, "none", Sfx.E_NAV, false)).isEqualTo("none")
    }

    @Test
    fun everyEventHasASynthClip_andRotationsExpand() {
        assertThat(Sfx.SYNTH_EVENT).hasLength(Sfx.EVENTS)
        assertThat(Sfx.clipsOf(Sfx.SYNTH_TAPS)).isEqualTo(Sfx.SYNTH_TAP_CLIPS)
        assertThat(Sfx.clipsOf(Sfx.TAP_SET)).isEqualTo(Sfx.TAP_SET_CLIPS)
        assertThat(Sfx.clipsOf("synth_nav")).asList().containsExactly("synth_nav")
    }

    @Test
    fun synthClipsAreInTheLibraryAndShippedAsAssets() {
        val lib = Sfx.LIBRARY.asList()
        val dir = synthDir()
        for (id in Sfx.SYNTH_TAP_CLIPS) {
            assertThat(lib).contains(id)
            assertThat(File(dir, "$id.ogg").isFile).isTrue()
        }
        for (e in 1 until Sfx.EVENTS) {
            assertThat(lib).contains(Sfx.SYNTH_EVENT[e])
            assertThat(File(dir, Sfx.SYNTH_EVENT[e] + ".ogg").isFile).isTrue()
        }
        assertThat(File(dir, Sfx.SYNTH_BOOT + ".ogg").isFile).isTrue()
        assertThat(Sfx.LIBRARY.size).isEqualTo(Sfx.LIBRARY_LABELS.size)
        assertThat(Sfx.LIBRARY.size).isEqualTo(Sfx.LIBRARY_ORIGINAL.size)
    }

    @Test
    fun everyInterfaceHasASoundForEveryEventByDefault() {
        val dir = synthDir()
        val lib = Sfx.LIBRARY.asList()
        for (f in Sfx.FACTION_DEFAULT.indices) {
            val synth = Sfx.synthEventFor(f)
            for (e in 0 until Sfx.EVENTS) {
                assertThat(Sfx.FACTION_DEFAULT[f][e]).isNotEqualTo("none")
                // nothing downloaded: the interface's synthesized clip plays
                val clip = Sfx.eventClip(Sfx.SET_LIBRARY, Sfx.FACTION_DEFAULT[f][e], e, false, synth)
                assertThat(Sfx.isSynth(clip)).isTrue()
                for (id in Sfx.clipsOf(clip)) {
                    assertThat(lib).contains(id)
                    assertThat(File(dir, "$id.ogg").isFile).isTrue()
                }
            }
            assertThat(File(dir, Sfx.synthBootFor(f) + ".ogg").isFile).isTrue()
        }
    }

    @Test
    fun families_pickTheInterfaceSet_andUserSilenceWins() {
        assertThat(Sfx.synthEventFor(Skin.IMPERIAL)[Sfx.E_ERROR]).isEqualTo("synth_kl_error")
        assertThat(Sfx.synthEventFor(Skin.COLLECTIVE)[Sfx.E_RED]).isEqualTo("synth_bg_red_alert")
        assertThat(Sfx.synthEventFor(Skin.ERA23)).isEqualTo(Sfx.SYNTH_EVENT)
        assertThat(Sfx.synthBootFor(Skin.COLLECTIVE)).isEqualTo("synth_bg_boot")
        assertThat(Sfx.synthBootFor(Skin.COMMAND)).isEqualTo(Sfx.SYNTH_BOOT)
        assertThat(Sfx.clipsOf(Sfx.SYNTH_KL_TAPS)).asList()
            .containsExactly("synth_kl_tap_1", "synth_kl_tap_2", "synth_kl_tap_3", "synth_kl_tap_4").inOrder()
        assertThat(Sfx.isRotation(Sfx.SYNTH_KL_TAPS)).isTrue()
        // in SYNTHESIZED mode a library default becomes the interface's synth clip
        assertThat(Sfx.eventClip(Sfx.SET_SYNTH, "ui_denied", Sfx.E_ERROR, true, Sfx.SYNTH_KL_EVENT)).isEqualTo("synth_kl_error")
        // the user can always silence an event
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, "none", Sfx.E_ERROR, false, Sfx.SYNTH_BG_EVENT)).isEqualTo("none")
    }

    @Test
    fun retiredFamily_isGoneAndOldPicksReadAsDefault() {
        // the retired interfaces' synthesized family is no longer shipped or offered
        for (id in Sfx.LIBRARY) assertThat(id.startsWith("synth_pp_") || id == "#synthtaps_pp").isFalse()
        for (f in Skin.FIRST_RETIRED..Skin.LAST_RETIRED) {
            assertThat(Sfx.synthEventFor(f)).isEqualTo(Sfx.SYNTH_EVENT)
            assertThat(Sfx.FACTION_DEFAULT[f]).isEqualTo(Sfx.DEFAULT_RES)
        }
        // a stored pick from that family is ignored (the event falls back to the interface default)
        assertThat(Sfx.retiredClip("synth_pp_launch")).isTrue()
        assertThat(Sfx.retiredClip("#synthtaps_pp")).isTrue()
        assertThat(Sfx.retiredClip("synth_kl_launch")).isFalse()
        assertThat(Sfx.retiredClip("#synthtaps_kl")).isFalse()
        assertThat(Sfx.retiredClip("synth_launch")).isFalse()
        assertThat(Sfx.retiredClip(null)).isFalse()
    }

    @Test
    fun resolveOrder_assetIsLastBeforeSilence() {
        assertThat(SoundCache.resolve(true, 5, true)).isEqualTo(SoundCache.CACHE)
        assertThat(SoundCache.resolve(false, 5, true)).isEqualTo(SoundCache.RAW)
        assertThat(SoundCache.resolve(false, 0, true)).isEqualTo(SoundCache.ASSET)
        assertThat(SoundCache.resolve(false, 0, false)).isEqualTo(SoundCache.NONE)
    }

    private companion object {
        /** The synth clips ship from :core. */
        fun synthDir(): File {
            val dir = File("../core/src/main/assets/sounds/synth")
            return if (dir.isDirectory) dir else File("core/src/main/assets/sounds/synth")
        }
    }
}
