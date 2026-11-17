package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** JVM tests for the SOUND SET decision (library / synthesized / silent) and the synthesized set's wiring. */
public class SfxTest {

    @Test
    public void library_usesTheLibraryClipWhenPlayable() {
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, "ui_panel_open", Sfx.E_NAV, true)).isEqualTo("ui_panel_open");
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, Sfx.TAP_SET, Sfx.E_BUTTON, true)).isEqualTo(Sfx.TAP_SET);
    }

    @Test
    public void library_fallsBackToSynthWhenNotDownloaded() {
        // the Play build out of the box: nothing downloaded, synthesized sounds play
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, "ui_panel_open", Sfx.E_NAV, false)).isEqualTo("synth_nav");
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, Sfx.TAP_SET, Sfx.E_BUTTON, false)).isEqualTo(Sfx.SYNTH_TAPS);
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, "alert_red_tng", Sfx.E_RED, false)).isEqualTo("synth_red_alert");
    }

    @Test
    public void synth_ignoresTheLibrary() {
        assertThat(Sfx.eventClip(Sfx.SET_SYNTH, "ui_panel_open", Sfx.E_NAV, true)).isEqualTo("synth_nav");
    }

    @Test
    public void userSynthPickIsKeptInAnySoundingSet() {
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, "synth_confirm", Sfx.E_NAV, true)).isEqualTo("synth_confirm");
        assertThat(Sfx.eventClip(Sfx.SET_SYNTH, "synth_confirm", Sfx.E_NAV, true)).isEqualTo("synth_confirm");
    }

    @Test
    public void silentAndNoneStaySilent() {
        assertThat(Sfx.eventClip(Sfx.SET_SILENT, "ui_panel_open", Sfx.E_NAV, true)).isEqualTo("none");
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, "none", Sfx.E_NAV, false)).isEqualTo("none");
        assertThat(Sfx.eventClip(Sfx.SET_SYNTH, "none", Sfx.E_NAV, false)).isEqualTo("none");
    }

    @Test
    public void everyEventHasASynthClip_andRotationsExpand() {
        assertThat(Sfx.SYNTH_EVENT).hasLength(Sfx.EVENTS);
        assertThat(Sfx.clipsOf(Sfx.SYNTH_TAPS)).isEqualTo(Sfx.SYNTH_TAP_CLIPS);
        assertThat(Sfx.clipsOf(Sfx.TAP_SET)).isEqualTo(Sfx.TAP_SET_CLIPS);
        assertThat(Sfx.clipsOf("synth_nav")).asList().containsExactly("synth_nav");
    }

    @Test
    public void synthClipsAreInTheLibraryAndShippedAsAssets() {
        java.util.List<String> lib = java.util.Arrays.asList(Sfx.LIBRARY);
        java.io.File dir = new java.io.File("src/main/assets/sounds/synth");
        if (!dir.isDirectory()) dir = new java.io.File("app/src/main/assets/sounds/synth");
        for (String id : Sfx.SYNTH_TAP_CLIPS) {
            assertThat(lib).contains(id);
            assertThat(new java.io.File(dir, id + ".ogg").isFile()).isTrue();
        }
        for (int e = 1; e < Sfx.EVENTS; e++) {
            assertThat(lib).contains(Sfx.SYNTH_EVENT[e]);
            assertThat(new java.io.File(dir, Sfx.SYNTH_EVENT[e] + ".ogg").isFile()).isTrue();
        }
        assertThat(new java.io.File(dir, Sfx.SYNTH_BOOT + ".ogg").isFile()).isTrue();
        assertThat(Sfx.LIBRARY.length).isEqualTo(Sfx.LIBRARY_LABELS.length);
        assertThat(Sfx.LIBRARY.length).isEqualTo(Sfx.LIBRARY_ORIGINAL.length);
    }

    @Test
    public void everyInterfaceHasASoundForEveryEventByDefault() {
        java.io.File dir = new java.io.File("src/main/assets/sounds/synth");
        if (!dir.isDirectory()) dir = new java.io.File("app/src/main/assets/sounds/synth");
        java.util.List<String> lib = java.util.Arrays.asList(Sfx.LIBRARY);
        for (int f = 0; f < Sfx.FACTION_DEFAULT.length; f++) {
            String[] synth = Sfx.synthEventFor(f);
            for (int e = 0; e < Sfx.EVENTS; e++) {
                assertThat(Sfx.FACTION_DEFAULT[f][e]).isNotEqualTo("none");
                // nothing downloaded: the interface's synthesized clip plays
                String clip = Sfx.eventClip(Sfx.SET_LIBRARY, Sfx.FACTION_DEFAULT[f][e], e, false, synth);
                assertThat(Sfx.isSynth(clip)).isTrue();
                for (String id : Sfx.clipsOf(clip)) {
                    assertThat(lib).contains(id);
                    assertThat(new java.io.File(dir, id + ".ogg").isFile()).isTrue();
                }
            }
            assertThat(new java.io.File(dir, Sfx.synthBootFor(f) + ".ogg").isFile()).isTrue();
        }
    }

    @Test
    public void families_pickTheInterfaceSet_andUserSilenceWins() {
        assertThat(Sfx.synthEventFor(Skin.IMPERIAL)[Sfx.E_ERROR]).isEqualTo("synth_kl_error");
        assertThat(Sfx.synthEventFor(Skin.COLLECTIVE)[Sfx.E_RED]).isEqualTo("synth_bg_red_alert");
        assertThat(Sfx.synthEventFor(Skin.LOUNGE)[Sfx.E_LAUNCH]).isEqualTo("synth_pp_launch");
        assertThat(Sfx.synthEventFor(Skin.ERA23)).isEqualTo(Sfx.SYNTH_EVENT);
        assertThat(Sfx.synthBootFor(Skin.COLLECTIVE)).isEqualTo("synth_bg_boot");
        assertThat(Sfx.synthBootFor(Skin.COMMAND)).isEqualTo(Sfx.SYNTH_BOOT);
        assertThat(Sfx.clipsOf(Sfx.SYNTH_PP_TAPS)).asList().containsExactly("synth_pp_tap_1", "synth_pp_tap_2", "synth_pp_tap_3", "synth_pp_tap_4").inOrder();
        assertThat(Sfx.isRotation(Sfx.SYNTH_KL_TAPS)).isTrue();
        // in SYNTHESIZED mode a library default becomes the interface's synth clip
        assertThat(Sfx.eventClip(Sfx.SET_SYNTH, "ui_denied", Sfx.E_ERROR, true, Sfx.SYNTH_KL_EVENT)).isEqualTo("synth_kl_error");
        // the user can always silence an event
        assertThat(Sfx.eventClip(Sfx.SET_LIBRARY, "none", Sfx.E_ERROR, false, Sfx.SYNTH_PP_EVENT)).isEqualTo("none");
    }

    @Test
    public void resolveOrder_assetIsLastBeforeSilence() {
        assertThat(SoundCache.resolve(true, 5, true)).isEqualTo(SoundCache.CACHE);
        assertThat(SoundCache.resolve(false, 5, true)).isEqualTo(SoundCache.RAW);
        assertThat(SoundCache.resolve(false, 0, true)).isEqualTo(SoundCache.ASSET);
        assertThat(SoundCache.resolve(false, 0, false)).isEqualTo(SoundCache.NONE);
    }
}
