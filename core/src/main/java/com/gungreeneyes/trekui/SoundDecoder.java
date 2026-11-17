package com.gungreeneyes.trekui;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Decodes a downloaded original (MP3 / WAV / Ogg, anything the platform can read) into interleaved
 * float PCM with its own sample rate and channel count, using MediaExtractor + MediaCodec. Unlike
 * {@link SoundImporter#decode} this keeps the channels, because the manifest says per sound whether
 * to fold to mono or keep stereo. Runs on a background thread only.
 */
final class SoundDecoder {
    /** Hard limit on decoded length (ambient loops are cut to 40 s; originals run up to ~4 min). */
    static final int MAX_SECONDS = 300;
    private static final long TIMEOUT_US = 10_000;
    /** Give up after this many consecutive dequeue attempts without any progress (~6 s). */
    private static final int MAX_IDLE = 600;

    private SoundDecoder() {}

    /**
     * @param untilSeconds stop once this much audio is decoded (0 = whole file, up to {@link #MAX_SECONDS})
     */
    static SoundProcessor.Pcm decode(File file, double untilSeconds) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            ex.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) { track = i; fmt = f; break; }
            }
            if (track < 0) throw new IOException("no audio track");
            ex.selectTrack(track);
            int sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int ch = Math.max(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
            int enc = AudioFormat.ENCODING_PCM_16BIT;
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            float[] out = new float[sr * ch * 4];
            int len = 0;
            boolean inEos = false, outEos = false;
            int idle = 0;
            double limit = untilSeconds > 0 ? Math.min(untilSeconds + 0.5, MAX_SECONDS) : MAX_SECONDS;
            while (!outEos && idle < MAX_IDLE) {
                boolean progress = false;
                if (!inEos) {
                    int ii = dec.dequeueInputBuffer(TIMEOUT_US);
                    if (ii >= 0) {
                        ByteBuffer b = dec.getInputBuffer(ii);
                        int n = b == null ? -1 : ex.readSampleData(b, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                        progress = true;
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        int bytesPer = enc == AudioFormat.ENCODING_PCM_FLOAT ? 4 : enc == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
                        int samples = info.size / bytesPer;
                        samples -= samples % ch;
                        if (len + samples > out.length) out = Arrays.copyOf(out, Math.max(out.length * 2, len + samples));
                        for (int s = 0; s < samples; s++) {
                            float v;
                            if (bytesPer == 4) v = ob.getFloat();
                            else if (bytesPer == 1) v = ((ob.get() & 0xFF) - 128) / 128f;
                            else v = ob.getShort() / 32768f;
                            out[len++] = v;
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    if (len / ch > sr * limit) outEos = true;
                    progress = true;
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = Math.max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    progress = true;
                }
                idle = progress ? 0 : idle + 1;
            }
            if (len == 0) throw new IOException("could not decode");
            len -= len % ch;
            return new SoundProcessor.Pcm(Arrays.copyOf(out, len), ch, sr);
        } finally {
            if (dec != null) {
                try { dec.stop(); } catch (Exception ignored) { /* already stopped */ }
                dec.release();
            }
            ex.release();
        }
    }
}
