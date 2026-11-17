package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manifest parsing (Robolectric supplies a real org.json) plus sanity checks of the shipped
 * {@code assets/sounds/manifest.json}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class SoundCatalogTest {
    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    private static String sound(String id, String sha, String extra) {
        return "{\"id\":\"" + id + "\",\"label\":\"" + id.toUpperCase() + "\",\"group\":\"LAUNCHER\",\"events\":[\"button\"],"
                + "\"source\":{\"url\":\"https://example.org/" + id + ".mp3\",\"page\":\"https://example.org/\",\"sha256\":\"" + sha
                + "\",\"site\":\"example\",\"bytes\":1000}" + extra + "}";
    }

    @Test
    public void parsesRecipeFields() throws JSONException {
        SoundCatalog c = SoundCatalog.parse("{\"format\":1,\"notice\":\"n\",\"sounds\":["
                + sound("tap_rot_1", SHA_A, ",\"start\":0.027,\"end\":0.113,\"gain_db\":-3.5,\"mono\":true,\"fade_in_ms\":2,"
                + "\"fade_out_ms\":4,\"confidence\":0.97,\"core\":true,\"available\":true") + "]}");
        assertThat(c.size()).isEqualTo(1);
        SoundCatalog.Sound s = c.get("tap_rot_1");
        assertThat(s.start).isWithin(1e-9).of(0.027);
        assertThat(s.end).isWithin(1e-9).of(0.113);
        assertThat(s.seconds()).isWithin(1e-9).of(0.086);
        assertThat(s.gainDb).isWithin(1e-9).of(-3.5);
        assertThat(s.mono).isTrue();
        assertThat(s.fadeInMs).isEqualTo(2);
        assertThat(s.fadeOutMs).isEqualTo(4);
        assertThat(s.core).isTrue();
        assertThat(s.available).isTrue();
        assertThat(s.events).containsExactly("button");
        assertThat(s.source.sha256).isEqualTo(SHA_A);
        assertThat(s.source.bytes).isEqualTo(1000);
        assertThat(c.notice).isEqualTo("n");
    }

    @Test
    public void rejectsUnsafeIdsAndSources() throws JSONException {
        SoundCatalog c = SoundCatalog.parse("{\"sounds\":["
                + sound("../evil", SHA_A, "") + ","
                + sound("Upper", SHA_A, "") + ","
                + "{\"id\":\"plain_http\",\"source\":{\"url\":\"http://example.org/x.mp3\",\"sha256\":\"" + SHA_A + "\"}},"
                + "{\"id\":\"bad_sha\",\"source\":{\"url\":\"https://example.org/x.mp3\",\"sha256\":\"1234\"}},"
                + "{\"id\":\"no_source\",\"available\":true}"
                + "]}");
        assertThat(c.get("../evil")).isNull();
        assertThat(c.get("Upper")).isNull();
        // kept, but without a usable source they can never be downloaded
        assertThat(c.get("plain_http").available).isFalse();
        assertThat(c.get("bad_sha").available).isFalse();
        assertThat(c.get("no_source").available).isFalse();
        assertThat(c.available(false)).isEmpty();
    }

    @Test
    public void clampsAbsurdGain() throws JSONException {
        SoundCatalog c = SoundCatalog.parse("{\"sounds\":[" + sound("loud", SHA_A, ",\"gain_db\":400") + "]}");
        assertThat(c.get("loud").gainDb).isAtMost(24.0);
    }

    @Test(expected = JSONException.class)
    public void refusesNewerFormat() throws JSONException {
        SoundCatalog.parse("{\"format\":99,\"sounds\":[]}");
    }

    @Test
    public void groupsSoundsCutFromTheSameOriginal() throws JSONException {
        SoundCatalog c = SoundCatalog.parse("{\"sounds\":["
                + sound("tap_rot_1", SHA_A, ",\"core\":true") + "," + sound("tap_rot_2", SHA_A, ",\"core\":true") + ","
                + sound("tk_x", SHA_B, "") + "]}");
        Map<String, List<SoundCatalog.Sound>> g = SoundCatalog.bySource(c.available(false));
        assertThat(g).hasSize(2);
        assertThat(g.get(SHA_A)).hasSize(2);
        assertThat(c.available(true)).hasSize(2);
        assertThat(SoundCatalog.downloadBytes(c.available(false))).isEqualTo(2000);
    }

    @Test
    public void emptyCatalogIsHarmless() {
        SoundCatalog c = SoundCatalog.empty();
        assertThat(c.size()).isEqualTo(0);
        assertThat(c.get("ui_button_tap")).isNull();
        assertThat(c.available(false)).isEmpty();
        assertThat(SoundCatalog.bySource(c.available(false))).isEmpty();
    }

    @Test
    public void shippedManifestIsValid() throws IOException, JSONException {
        File f = new File("src/main/assets/" + SoundCatalog.ASSET);
        if (!f.isFile()) f = new File("app/src/main/assets/" + SoundCatalog.ASSET);
        assertThat(f.isFile()).isTrue();
        String json;
        try (InputStream in = new FileInputStream(f)) {
            json = SoundCatalog.readAll(in);
        }
        SoundCatalog c = SoundCatalog.parse(json);
        assertThat(c.size()).isGreaterThan(0);
        Set<String> ids = new HashSet<>();
        for (SoundCatalog.Sound s : c.sounds) {
            assertThat(ids.add(s.id)).isTrue();
            if (!s.available) continue;
            assertThat(s.source.url).startsWith("https://");
            assertThat(s.source.sha256).matches("[0-9a-f]{64}");
            assertThat(s.start).isAtLeast(0.0);
            assertThat(s.end).isGreaterThan(s.start);
        }
        // every tap-rotation clip is listed (available or not)
        for (String id : Sfx.TAP_SET_CLIPS) assertThat(c.get(id)).isNotNull();
    }
}
