package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** JVM tests for the manifest recipe math (cut, gain, mono fold, fades, WAV) and SHA-256 checks. */
public class SoundProcessorTest {

    private static SoundProcessor.Pcm ramp(int frames, int channels, int sr) {
        float[] d = new float[frames * channels];
        for (int f = 0; f < frames; f++) for (int c = 0; c < channels; c++) d[f * channels + c] = f / (float) frames;
        return new SoundProcessor.Pcm(d, channels, sr);
    }

    @Test
    public void dbConversions() {
        assertThat(SoundProcessor.dbToLinear(0)).isWithin(1e-12).of(1.0);
        assertThat(SoundProcessor.dbToLinear(20)).isWithin(1e-9).of(10.0);
        assertThat(SoundProcessor.dbToLinear(-6.0206)).isWithin(1e-4).of(0.5);
        assertThat(SoundProcessor.linearToDb(0.5)).isWithin(1e-3).of(-6.0206);
        assertThat(SoundProcessor.linearToDb(0)).isNegativeInfinity();
    }

    @Test
    public void frameAt_roundsAndClamps() {
        assertThat(SoundProcessor.frameAt(0.027, 44100, 100000)).isEqualTo(1191); // 1190.7
        assertThat(SoundProcessor.frameAt(-1, 44100, 1000)).isEqualTo(0);
        assertThat(SoundProcessor.frameAt(99, 44100, 1000)).isEqualTo(1000);
    }

    @Test
    public void cut_takesTheRequestedFrames() {
        SoundProcessor.Pcm p = ramp(1000, 2, 1000); // 1 s at 1 kHz, stereo
        SoundProcessor.Pcm c = SoundProcessor.cut(p, 0.1, 0.25);
        assertThat(c.frames()).isEqualTo(150);
        assertThat(c.channels).isEqualTo(2);
        assertThat(c.data[0]).isEqualTo(p.data[100 * 2]);
        assertThat(c.data[c.data.length - 1]).isEqualTo(p.data[249 * 2 + 1]);
    }

    @Test
    public void cut_endZeroMeansToTheEnd_andOutOfRangeIsEmpty() {
        SoundProcessor.Pcm p = ramp(1000, 1, 1000);
        assertThat(SoundProcessor.cut(p, 0.5, 0).frames()).isEqualTo(500);
        assertThat(SoundProcessor.cut(p, 2.0, 3.0).frames()).isEqualTo(0);
        assertThat(SoundProcessor.cut(p, 0.9, 5.0).frames()).isEqualTo(100);
    }

    @Test
    public void foldMono_averagesChannels() {
        SoundProcessor.Pcm p = new SoundProcessor.Pcm(new float[]{1f, 0f, 0.5f, -0.5f, -1f, -1f}, 2, 8000);
        SoundProcessor.Pcm m = SoundProcessor.foldMono(p);
        assertThat(m.channels).isEqualTo(1);
        assertThat(m.data).usingTolerance(1e-6).containsExactly(0.5f, 0f, -1f).inOrder();
        assertThat(SoundProcessor.foldMono(m)).isSameInstanceAs(m);
    }

    @Test
    public void applyGain_scalesByDb() {
        SoundProcessor.Pcm p = new SoundProcessor.Pcm(new float[]{0.1f, -0.2f}, 1, 8000);
        SoundProcessor.applyGain(p, 6.0206);
        assertThat(p.data).usingTolerance(1e-4).containsExactly(0.2f, -0.4f).inOrder();
        SoundProcessor.applyGain(p, -6.0206);
        assertThat(p.data).usingTolerance(1e-4).containsExactly(0.1f, -0.2f).inOrder();
    }

    @Test
    public void fadeEdges_rampsOnlyTheEdges() {
        float[] d = new float[1000];
        java.util.Arrays.fill(d, 1f);
        SoundProcessor.Pcm p = new SoundProcessor.Pcm(d, 1, 1000); // 1 frame per ms
        SoundProcessor.fadeEdges(p, 4, 10);
        assertThat(p.data[0]).isLessThan(0.25f);
        assertThat(p.data[3]).isLessThan(1f);
        assertThat(p.data[4]).isEqualTo(1f);
        assertThat(p.data[500]).isEqualTo(1f);
        assertThat(p.data[989]).isEqualTo(1f);
        assertThat(p.data[990]).isLessThan(1f);
        assertThat(p.data[999]).isLessThan(0.1f);
        for (int i = 1; i < 4; i++) assertThat(p.data[i]).isGreaterThan(p.data[i - 1]);
    }

    @Test
    public void fadeEdges_onTinyClipDoesNotOverrun() {
        SoundProcessor.Pcm p = new SoundProcessor.Pcm(new float[]{1f, 1f, 1f}, 1, 1000);
        SoundProcessor.fadeEdges(p, 50, 50);
        for (float v : p.data) assertThat(v).isAtMost(1f);
    }

    @Test
    public void render_appliesWholeRecipe_andClamps() throws IOException {
        float[] d = new float[2000 * 2];
        for (int i = 0; i < d.length; i++) d[i] = 0.6f;
        SoundProcessor.Pcm p = new SoundProcessor.Pcm(d, 2, 1000);
        SoundProcessor.Pcm r = SoundProcessor.render(p, 0.5, 1.5, 12, true, 0, 0);
        assertThat(r.channels).isEqualTo(1);
        assertThat(r.frames()).isEqualTo(1000);
        for (float v : r.data) assertThat(v).isEqualTo(1f); // 0.6 * 4 clamps to 1
    }

    @Test(expected = IOException.class)
    public void render_failsWhenCutIsOutsideTheOriginal() throws IOException {
        SoundProcessor.render(ramp(100, 1, 1000), 5, 6, 0, true, 4, 4);
    }

    @Test
    public void wavBytes_headerIsCorrect() {
        SoundProcessor.Pcm p = new SoundProcessor.Pcm(new float[]{0f, 1f, -1f, 0.5f}, 2, 44100);
        byte[] w = SoundProcessor.wavBytes(p);
        assertThat(w.length).isEqualTo(44 + 8);
        ByteBuffer b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(new String(w, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(b.getInt(4)).isEqualTo(36 + 8);
        assertThat(new String(w, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(b.getShort(20)).isEqualTo((short) 1);      // PCM
        assertThat(b.getShort(22)).isEqualTo((short) 2);      // channels
        assertThat(b.getInt(24)).isEqualTo(44100);            // sample rate
        assertThat(b.getInt(28)).isEqualTo(44100 * 2 * 2);    // byte rate
        assertThat(b.getShort(32)).isEqualTo((short) 4);      // block align
        assertThat(b.getShort(34)).isEqualTo((short) 16);     // bits
        assertThat(b.getInt(40)).isEqualTo(8);
        assertThat(b.getShort(46)).isEqualTo((short) 32767);
        assertThat(b.getShort(48)).isEqualTo((short) -32767);
    }

    @Test
    public void sha256_knownVectors() throws IOException {
        assertThat(SoundProcessor.sha256Hex("abc".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(SoundProcessor.sha256Hex(new ByteArrayInputStream(new byte[0])))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    public void shaMatches_isCaseInsensitiveAndRejectsEmpty() {
        String h = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertThat(SoundProcessor.shaMatches(h, h.toUpperCase())).isTrue();
        assertThat(SoundProcessor.shaMatches(h, "")).isFalse();
        assertThat(SoundProcessor.shaMatches(h, null)).isFalse();
        assertThat(SoundProcessor.shaMatches(null, h)).isFalse();
        assertThat(SoundProcessor.shaMatches(h, h.replace('a', 'b'))).isFalse();
    }
}
