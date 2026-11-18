package com.gungreeneyes.trekui

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Locale

/**
 * Pure-JVM audio math for turning a downloaded original into an app clip, exactly as the manifest
 * describes it: cut [start, end), fold to mono if asked, apply a fixed gain in dB, de-click the
 * edges with short linear fades, clamp to [-1, 1], and write 16-bit PCM WAV. Also SHA-256 hashing
 * for verifying downloads. No Android classes here, so all of it is JVM unit-tested.
 *
 * PCM is interleaved float (`frames * channels` samples, range -1..1).
 *
 * The arithmetic keeps the Java edition's float / double steps one for one (and `Math.round`), so
 * rendered clips stay byte-identical.
 */
object SoundProcessor {
    /** A block of interleaved PCM. */
    class Pcm(
        @JvmField val data: FloatArray,
        @JvmField val channels: Int,
        @JvmField val sampleRate: Int,
    ) {
        init {
            require(channels >= 1) { "channels < 1" }
        }

        fun frames(): Int = data.size / channels

        fun seconds(): Double = frames() / sampleRate.toDouble()
    }

    /** dB to linear amplitude factor. */
    @JvmStatic
    fun dbToLinear(db: Double): Double = Math.pow(10.0, db / 20.0)

    /** Linear amplitude factor to dB (factor <= 0 gives -infinity). */
    @JvmStatic
    fun linearToDb(f: Double): Double = if (f <= 0) Double.NEGATIVE_INFINITY else 20 * Math.log10(f)

    /** Frame index for a time, clamped to [0, frames]. */
    @JvmStatic
    fun frameAt(seconds: Double, sampleRate: Int, frames: Int): Int {
        val f = Math.round(seconds * sampleRate)
        return Math.max(0L, Math.min(frames.toLong(), f)).toInt()
    }

    /**
     * Cuts [startSec, endSec) out of the PCM. `endSec <= startSec` means "to the end".
     * Out-of-range times are clamped; the result may be empty.
     */
    @JvmStatic
    fun cut(input: Pcm, startSec: Double, endSec: Double): Pcm {
        val frames = input.frames()
        val a = frameAt(startSec, input.sampleRate, frames)
        var b = if (endSec > startSec) frameAt(endSec, input.sampleRate, frames) else frames
        if (b < a) b = a
        val out = FloatArray((b - a) * input.channels)
        System.arraycopy(input.data, a * input.channels, out, 0, out.size)
        return Pcm(out, input.channels, input.sampleRate)
    }

    /** Averages all channels into one. Mono input is returned unchanged. */
    @JvmStatic
    fun foldMono(input: Pcm): Pcm {
        if (input.channels == 1) return input
        val n = input.frames()
        val out = FloatArray(n)
        for (f in 0 until n) {
            var sum = 0f
            for (c in 0 until input.channels) sum += input.data[f * input.channels + c]
            out[f] = sum / input.channels
        }
        return Pcm(out, 1, input.sampleRate)
    }

    /** Multiplies every sample by the dB gain, in place. */
    @JvmStatic
    fun applyGain(p: Pcm, gainDb: Double) {
        if (gainDb == 0.0) return
        val g = dbToLinear(gainDb).toFloat()
        for (i in p.data.indices) p.data[i] *= g
    }

    /** Linear fade-in over the first `fadeInMs` milliseconds and fade-out over the last `fadeOutMs`, in place. */
    @JvmStatic
    fun fadeEdges(p: Pcm, fadeInMs: Int, fadeOutMs: Int) {
        val frames = p.frames()
        val fin = Math.min(frames / 2, (p.sampleRate.toLong() * fadeInMs / 1000).toInt())
        val fout = Math.min(frames - fin, (p.sampleRate.toLong() * fadeOutMs / 1000).toInt())
        for (f in 0 until fin) {
            val k = (f + 1f) / (fin + 1f)
            for (c in 0 until p.channels) p.data[f * p.channels + c] *= k
        }
        for (i in 0 until fout) {
            val f = frames - 1 - i
            val k = (i + 1f) / (fout + 1f)
            for (c in 0 until p.channels) p.data[f * p.channels + c] *= k
        }
    }

    /** Hard clamp to [-1, 1] (guards against a manifest gain that would clip). */
    @JvmStatic
    fun clamp(p: Pcm) {
        for (i in p.data.indices) p.data[i] = Math.max(-1f, Math.min(1f, p.data[i]))
    }

    /**
     * The whole manifest recipe: cut, optional mono fold, gain, fades, clamp.
     *
     * @throws IOException if the cut is empty (the original is shorter than the manifest expects)
     */
    @JvmStatic
    @Throws(IOException::class)
    fun render(
        original: Pcm, startSec: Double, endSec: Double, gainDb: Double, mono: Boolean, fadeInMs: Int, fadeOutMs: Int,
    ): Pcm {
        var p = cut(original, startSec, endSec)
        if (p.frames() == 0) {
            throw IOException(
                String.format(Locale.US, "cut %.3f-%.3f s is outside the %.2f s original", startSec, endSec, original.seconds()),
            )
        }
        if (mono) p = foldMono(p)
        applyGain(p, gainDb)
        fadeEdges(p, fadeInMs, fadeOutMs)
        clamp(p)
        return p
    }

    /** 16-bit little-endian PCM WAV bytes. */
    @JvmStatic
    fun wavBytes(p: Pcm): ByteArray {
        val dataLen = p.data.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(ascii("RIFF")).putInt(36 + dataLen).put(ascii("WAVE"))
        bb.put(ascii("fmt ")).putInt(16).putShort(1.toShort()).putShort(p.channels.toShort())
            .putInt(p.sampleRate).putInt(p.sampleRate * p.channels * 2).putShort((p.channels * 2).toShort()).putShort(16.toShort())
        bb.put(ascii("data")).putInt(dataLen)
        for (v in p.data) bb.putShort(Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f).toShort())
        return bb.array()
    }

    /** Writes the WAV atomically: to a temp file first, then renamed over `dst`. */
    @JvmStatic
    @Throws(IOException::class)
    fun writeWav(dst: File, p: Pcm) {
        val tmp = File(dst.path + ".tmp")
        FileOutputStream(tmp).use { it.write(wavBytes(p)) }
        if (dst.exists() && !dst.delete()) {
            tmp.delete()
            throw IOException("cannot replace " + dst.name)
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete()
            throw IOException("cannot write " + dst.name)
        }
    }

    /** The four ASCII bytes of a RIFF chunk tag. */
    internal fun ascii(tag: String): ByteArray = tag.toByteArray(Charsets.US_ASCII)

    // ---- SHA-256 --------------------------------------------------------------------------

    @JvmStatic
    fun sha256(): MessageDigest =
        try {
            MessageDigest.getInstance("SHA-256")
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException(e) // every Java / Android runtime has SHA-256
        }

    @JvmStatic
    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append(String.format(Locale.US, "%02x", x.toInt() and 0xFF))
        return sb.toString()
    }

    @JvmStatic
    fun sha256Hex(data: ByteArray): String = hex(sha256().digest(data))

    @JvmStatic
    @Throws(IOException::class)
    fun sha256Hex(input: InputStream): String {
        val md = sha256()
        val buf = ByteArray(16384)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            md.update(buf, 0, n)
        }
        return hex(md.digest())
    }

    @JvmStatic
    @Throws(IOException::class)
    fun sha256Hex(f: File): String = FileInputStream(f).use { sha256Hex(it) }

    /** True if the hex digests are equal (case-insensitive); false for null/empty expectations. */
    @JvmStatic
    fun shaMatches(actual: String?, expected: String?): Boolean =
        actual != null && expected != null && expected.isNotEmpty() && actual.equals(expected, ignoreCase = true)
}
