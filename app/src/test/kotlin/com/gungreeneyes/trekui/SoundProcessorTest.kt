package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** JVM tests for the manifest recipe math (cut, gain, mono fold, fades, WAV) and SHA-256 checks. */
class SoundProcessorTest {

    @Test
    fun dbConversions() {
        assertThat(SoundProcessor.dbToLinear(0.0)).isWithin(1e-12).of(1.0)
        assertThat(SoundProcessor.dbToLinear(20.0)).isWithin(1e-9).of(10.0)
        assertThat(SoundProcessor.dbToLinear(-6.0206)).isWithin(1e-4).of(0.5)
        assertThat(SoundProcessor.linearToDb(0.5)).isWithin(1e-3).of(-6.0206)
        assertThat(SoundProcessor.linearToDb(0.0)).isNegativeInfinity()
    }

    @Test
    fun frameAt_roundsAndClamps() {
        assertThat(SoundProcessor.frameAt(0.027, 44100, 100000)).isEqualTo(1191) // 1190.7
        assertThat(SoundProcessor.frameAt(-1.0, 44100, 1000)).isEqualTo(0)
        assertThat(SoundProcessor.frameAt(99.0, 44100, 1000)).isEqualTo(1000)
    }

    @Test
    fun cut_takesTheRequestedFrames() {
        val p = ramp(1000, 2, 1000) // 1 s at 1 kHz, stereo
        val c = SoundProcessor.cut(p, 0.1, 0.25)
        assertThat(c.frames()).isEqualTo(150)
        assertThat(c.channels).isEqualTo(2)
        assertThat(c.data[0]).isEqualTo(p.data[100 * 2])
        assertThat(c.data[c.data.size - 1]).isEqualTo(p.data[249 * 2 + 1])
    }

    @Test
    fun cut_endZeroMeansToTheEnd_andOutOfRangeIsEmpty() {
        val p = ramp(1000, 1, 1000)
        assertThat(SoundProcessor.cut(p, 0.5, 0.0).frames()).isEqualTo(500)
        assertThat(SoundProcessor.cut(p, 2.0, 3.0).frames()).isEqualTo(0)
        assertThat(SoundProcessor.cut(p, 0.9, 5.0).frames()).isEqualTo(100)
    }

    @Test
    fun foldMono_averagesChannels() {
        val p = SoundProcessor.Pcm(floatArrayOf(1f, 0f, 0.5f, -0.5f, -1f, -1f), 2, 8000)
        val m = SoundProcessor.foldMono(p)
        assertThat(m.channels).isEqualTo(1)
        assertThat(m.data).usingTolerance(1e-6).containsExactly(0.5f, 0f, -1f).inOrder()
        assertThat(SoundProcessor.foldMono(m)).isSameInstanceAs(m)
    }

    @Test
    fun applyGain_scalesByDb() {
        val p = SoundProcessor.Pcm(floatArrayOf(0.1f, -0.2f), 1, 8000)
        SoundProcessor.applyGain(p, 6.0206)
        assertThat(p.data).usingTolerance(1e-4).containsExactly(0.2f, -0.4f).inOrder()
        SoundProcessor.applyGain(p, -6.0206)
        assertThat(p.data).usingTolerance(1e-4).containsExactly(0.1f, -0.2f).inOrder()
    }

    @Test
    fun fadeEdges_rampsOnlyTheEdges() {
        val d = FloatArray(1000) { 1f }
        val p = SoundProcessor.Pcm(d, 1, 1000) // 1 frame per ms
        SoundProcessor.fadeEdges(p, 4, 10)
        assertThat(p.data[0]).isLessThan(0.25f)
        assertThat(p.data[3]).isLessThan(1f)
        assertThat(p.data[4]).isEqualTo(1f)
        assertThat(p.data[500]).isEqualTo(1f)
        assertThat(p.data[989]).isEqualTo(1f)
        assertThat(p.data[990]).isLessThan(1f)
        assertThat(p.data[999]).isLessThan(0.1f)
        for (i in 1 until 4) assertThat(p.data[i]).isGreaterThan(p.data[i - 1])
    }

    @Test
    fun fadeEdges_onTinyClipDoesNotOverrun() {
        val p = SoundProcessor.Pcm(floatArrayOf(1f, 1f, 1f), 1, 1000)
        SoundProcessor.fadeEdges(p, 50, 50)
        for (v in p.data) assertThat(v).isAtMost(1f)
    }

    @Test
    fun render_appliesWholeRecipe_andClamps() {
        val d = FloatArray(2000 * 2) { 0.6f }
        val p = SoundProcessor.Pcm(d, 2, 1000)
        val r = SoundProcessor.render(p, 0.5, 1.5, 12.0, true, 0, 0)
        assertThat(r.channels).isEqualTo(1)
        assertThat(r.frames()).isEqualTo(1000)
        for (v in r.data) assertThat(v).isEqualTo(1f) // 0.6 * 4 clamps to 1
    }

    @Test(expected = IOException::class)
    fun render_failsWhenCutIsOutsideTheOriginal() {
        SoundProcessor.render(ramp(100, 1, 1000), 5.0, 6.0, 0.0, true, 4, 4)
    }

    @Test
    fun wavBytes_headerIsCorrect() {
        val p = SoundProcessor.Pcm(floatArrayOf(0f, 1f, -1f, 0.5f), 2, 44100)
        val w = SoundProcessor.wavBytes(p)
        assertThat(w.size).isEqualTo(44 + 8)
        val b = ByteBuffer.wrap(w).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(String(w, 0, 4, Charsets.US_ASCII)).isEqualTo("RIFF")
        assertThat(b.getInt(4)).isEqualTo(36 + 8)
        assertThat(String(w, 8, 4, Charsets.US_ASCII)).isEqualTo("WAVE")
        assertThat(b.getShort(20)).isEqualTo(1.toShort()) // PCM
        assertThat(b.getShort(22)).isEqualTo(2.toShort()) // channels
        assertThat(b.getInt(24)).isEqualTo(44100) // sample rate
        assertThat(b.getInt(28)).isEqualTo(44100 * 2 * 2) // byte rate
        assertThat(b.getShort(32)).isEqualTo(4.toShort()) // block align
        assertThat(b.getShort(34)).isEqualTo(16.toShort()) // bits
        assertThat(b.getInt(40)).isEqualTo(8)
        assertThat(b.getShort(46)).isEqualTo(32767.toShort())
        assertThat(b.getShort(48)).isEqualTo((-32767).toShort())
    }

    @Test
    fun sha256_knownVectors() {
        assertThat(SoundProcessor.sha256Hex("abc".toByteArray(Charsets.US_ASCII)))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(SoundProcessor.sha256Hex(ByteArrayInputStream(ByteArray(0))))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
    }

    @Test
    fun shaMatches_isCaseInsensitiveAndRejectsEmpty() {
        val h = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertThat(SoundProcessor.shaMatches(h, h.uppercase(Locale.ROOT))).isTrue()
        assertThat(SoundProcessor.shaMatches(h, "")).isFalse()
        assertThat(SoundProcessor.shaMatches(h, null)).isFalse()
        assertThat(SoundProcessor.shaMatches(null, h)).isFalse()
        assertThat(SoundProcessor.shaMatches(h, h.replace('a', 'b'))).isFalse()
    }

    private companion object {
        fun ramp(frames: Int, channels: Int, sr: Int): SoundProcessor.Pcm {
            val d = FloatArray(frames * channels)
            for (f in 0 until frames) for (c in 0 until channels) d[f * channels + c] = f / frames.toFloat()
            return SoundProcessor.Pcm(d, channels, sr)
        }
    }
}
