package com.gungreeneyes.trekui;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Pure-Java audio math for turning a downloaded original into an app clip, exactly as the manifest
 * describes it: cut [start, end), fold to mono if asked, apply a fixed gain in dB, de-click the
 * edges with short linear fades, clamp to [-1, 1], and write 16-bit PCM WAV. Also SHA-256 hashing
 * for verifying downloads. No Android classes here, so all of it is JVM unit-tested.
 *
 * <p>PCM is interleaved float ({@code frames * channels} samples, range -1..1).
 */
final class SoundProcessor {
    private SoundProcessor() {}

    /** A block of interleaved PCM. */
    static final class Pcm {
        final float[] data;
        final int channels, sampleRate;

        Pcm(float[] data, int channels, int sampleRate) {
            if (channels < 1) throw new IllegalArgumentException("channels < 1");
            this.data = data;
            this.channels = channels;
            this.sampleRate = sampleRate;
        }

        int frames() { return data.length / channels; }

        double seconds() { return frames() / (double) sampleRate; }
    }

    /** dB to linear amplitude factor. */
    static double dbToLinear(double db) { return Math.pow(10, db / 20.0); }

    /** Linear amplitude factor to dB (factor <= 0 gives -infinity). */
    static double linearToDb(double f) { return f <= 0 ? Double.NEGATIVE_INFINITY : 20 * Math.log10(f); }

    /**
     * Frame index for a time, clamped to [0, frames].
     */
    static int frameAt(double seconds, int sampleRate, int frames) {
        long f = Math.round(seconds * sampleRate);
        return (int) Math.max(0, Math.min(frames, f));
    }

    /**
     * Cuts [startSec, endSec) out of the PCM. {@code endSec <= startSec} means "to the end".
     * Out-of-range times are clamped; the result may be empty.
     */
    static Pcm cut(Pcm in, double startSec, double endSec) {
        int frames = in.frames();
        int a = frameAt(startSec, in.sampleRate, frames);
        int b = endSec > startSec ? frameAt(endSec, in.sampleRate, frames) : frames;
        if (b < a) b = a;
        float[] out = new float[(b - a) * in.channels];
        System.arraycopy(in.data, a * in.channels, out, 0, out.length);
        return new Pcm(out, in.channels, in.sampleRate);
    }

    /** Averages all channels into one. Mono input is returned unchanged. */
    static Pcm foldMono(Pcm in) {
        if (in.channels == 1) return in;
        int n = in.frames();
        float[] out = new float[n];
        for (int f = 0; f < n; f++) {
            float sum = 0;
            for (int c = 0; c < in.channels; c++) sum += in.data[f * in.channels + c];
            out[f] = sum / in.channels;
        }
        return new Pcm(out, 1, in.sampleRate);
    }

    /** Multiplies every sample by the dB gain, in place. */
    static void applyGain(Pcm p, double gainDb) {
        if (gainDb == 0) return;
        float g = (float) dbToLinear(gainDb);
        for (int i = 0; i < p.data.length; i++) p.data[i] *= g;
    }

    /** Linear fade-in over the first {@code ms} milliseconds and fade-out over the last, in place. */
    static void fadeEdges(Pcm p, int fadeInMs, int fadeOutMs) {
        int frames = p.frames();
        int fin = Math.min(frames / 2, (int) ((long) p.sampleRate * fadeInMs / 1000));
        int fout = Math.min(frames - fin, (int) ((long) p.sampleRate * fadeOutMs / 1000));
        for (int f = 0; f < fin; f++) {
            float k = (f + 1f) / (fin + 1f);
            for (int c = 0; c < p.channels; c++) p.data[f * p.channels + c] *= k;
        }
        for (int i = 0; i < fout; i++) {
            int f = frames - 1 - i;
            float k = (i + 1f) / (fout + 1f);
            for (int c = 0; c < p.channels; c++) p.data[f * p.channels + c] *= k;
        }
    }

    /** Hard clamp to [-1, 1] (guards against a manifest gain that would clip). */
    static void clamp(Pcm p) {
        for (int i = 0; i < p.data.length; i++) p.data[i] = Math.max(-1f, Math.min(1f, p.data[i]));
    }

    /**
     * The whole manifest recipe: cut, optional mono fold, gain, fades, clamp.
     *
     * @throws IOException if the cut is empty (the original is shorter than the manifest expects)
     */
    static Pcm render(Pcm original, double startSec, double endSec, double gainDb, boolean mono, int fadeInMs, int fadeOutMs)
            throws IOException {
        Pcm p = cut(original, startSec, endSec);
        if (p.frames() == 0) {
            throw new IOException(String.format(Locale.US, "cut %.3f-%.3f s is outside the %.2f s original", startSec, endSec,
                    original.seconds()));
        }
        if (mono) p = foldMono(p);
        applyGain(p, gainDb);
        fadeEdges(p, fadeInMs, fadeOutMs);
        clamp(p);
        return p;
    }

    /** 16-bit little-endian PCM WAV bytes. */
    static byte[] wavBytes(Pcm p) {
        int dataLen = p.data.length * 2;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataLen).put(new byte[]{'W', 'A', 'V', 'E'});
        bb.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) p.channels)
                .putInt(p.sampleRate).putInt(p.sampleRate * p.channels * 2).putShort((short) (p.channels * 2)).putShort((short) 16);
        bb.put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataLen);
        for (float v : p.data) bb.putShort((short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f));
        return bb.array();
    }

    /** Writes the WAV atomically: to a temp file first, then renamed over {@code dst}. */
    static void writeWav(File dst, Pcm p) throws IOException {
        File tmp = new File(dst.getPath() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(wavBytes(p));
        }
        if (dst.exists() && !dst.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("cannot replace " + dst.getName());
        }
        if (!tmp.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("cannot write " + dst.getName());
        }
    }

    // ---- SHA-256 --------------------------------------------------------------------------

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // every Java / Android runtime has SHA-256
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x & 0xFF));
        return sb.toString();
    }

    static String sha256Hex(byte[] data) { return hex(sha256().digest(data)); }

    static String sha256Hex(InputStream in) throws IOException {
        MessageDigest md = sha256();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        return hex(md.digest());
    }

    static String sha256Hex(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return sha256Hex(in);
        }
    }

    /** True if the hex digests are equal (case-insensitive); false for null/empty expectations. */
    static boolean shaMatches(String actual, String expected) {
        return actual != null && expected != null && !expected.isEmpty() && actual.equalsIgnoreCase(expected);
    }
}
