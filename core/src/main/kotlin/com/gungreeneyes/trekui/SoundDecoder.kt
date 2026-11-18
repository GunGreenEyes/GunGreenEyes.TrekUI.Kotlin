package com.gungreeneyes.trekui

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes a downloaded original (MP3 / WAV / Ogg, anything the platform can read) into interleaved
 * float PCM with its own sample rate and channel count, using MediaExtractor + MediaCodec. Unlike
 * [SoundImporter.decode] this keeps the channels, because the manifest says per sound whether
 * to fold to mono or keep stereo. Runs on a background thread only.
 *
 * Only [SoundDownloader] uses it, so it is `internal` to `:core`.
 */
internal object SoundDecoder {
    /** Hard limit on decoded length (ambient loops are cut to 40 s; originals run up to ~4 min). */
    const val MAX_SECONDS = 300
    private const val TIMEOUT_US = 10_000L
    /** Give up after this many consecutive dequeue attempts without any progress (~6 s). */
    private const val MAX_IDLE = 600

    /**
     * @param untilSeconds stop once this much audio is decoded (0 = whole file, up to [MAX_SECONDS])
     */
    @JvmStatic
    @Throws(IOException::class)
    fun decode(file: File, untilSeconds: Double): SoundProcessor.Pcm {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            ex.setDataSource(file.absolutePath)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) throw IOException("no audio track")
            ex.selectTrack(track)
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = Math.max(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            var enc = AudioFormat.ENCODING_PCM_16BIT
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec = codec
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var out = FloatArray(sr * ch * 4)
            var len = 0
            var inEos = false
            var outEos = false
            var idle = 0
            val limit = if (untilSeconds > 0) Math.min(untilSeconds + 0.5, MAX_SECONDS.toDouble()) else MAX_SECONDS.toDouble()
            while (!outEos && idle < MAX_IDLE) {
                var progress = false
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (ii >= 0) {
                        val b = codec.getInputBuffer(ii)
                        val n = if (b == null) -1 else ex.readSampleData(b, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                        progress = true
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        val bytesPer = when (enc) {
                            AudioFormat.ENCODING_PCM_FLOAT -> 4
                            AudioFormat.ENCODING_PCM_8BIT -> 1
                            else -> 2
                        }
                        var samples = info.size / bytesPer
                        samples -= samples % ch
                        if (len + samples > out.size) out = out.copyOf(Math.max(out.size * 2, len + samples))
                        for (s in 0 until samples) {
                            val v = when (bytesPer) {
                                4 -> ob.getFloat()
                                1 -> ((ob.get().toInt() and 0xFF) - 128) / 128f
                                else -> ob.getShort() / 32768f
                            }
                            out[len++] = v
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                    if ((len / ch).toDouble() > sr * limit) outEos = true
                    progress = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = Math.max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    progress = true
                }
                idle = if (progress) 0 else idle + 1
            }
            if (len == 0) throw IOException("could not decode")
            len -= len % ch
            return SoundProcessor.Pcm(out.copyOf(len), ch, sr)
        } finally {
            dec?.let {
                try {
                    it.stop()
                } catch (ignored: Exception) {
                    // already stopped
                }
                it.release()
            }
            ex.release()
        }
    }
}
