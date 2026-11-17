package com.gungreeneyes.trekui;

import android.content.Context;
import android.database.Cursor;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns user-supplied audio files into console event sounds, entirely on the phone:
 * decode (any format Android can read) → mono → trim leading/trailing silence → gentle level
 * match → 16-bit WAV in private app storage. Pitch, speed and timbre are never altered:
 * there is no resampling, EQ, compression or effects.
 */
final class SoundImporter {
    static final class Item {
        final String name;
        final Uri uri;
        final File file;
        Item(String name, Uri uri, File file) { this.name = name; this.uri = uri; this.file = file; }
    }

    static final class Report {
        final List<String> lines = new ArrayList<>();
        final List<String> unmatched = new ArrayList<>();
        final List<String> failed = new ArrayList<>();
        int assigned;
    }

    private static final String[] AUDIO_EXT = {".mp3", ".wav", ".ogg", ".oga", ".m4a", ".aac", ".flac", ".opus", ".3gp", ".amr"};
    private static final int MAX_SECONDS = 10;

    private SoundImporter() {}

    // ---- filename → event matching ---------------------------------------------------------------

    private static final String[][] ALIASES = {
        {"button", "button_press", "buttonpress", "press", "tap"},
        {"navigation", "nav"},
        {"launch", "app_launch", "applaunch"},
        {"confirm", "confirmation", "input_confirmation", "inputconfirmation"},
        {"error"},
        {"comms", "comm", "communications", "communication"},
        {"diagnostics", "system_diagnostics", "systemdiagnostics", "diagnostic"},
        {"red_alert", "redalert", "alert_red"},
    };

    /** Keyword rules, most specific first: {event, keywords...}. */
    private static final Object[][] RULES = {
        {Sfx.E_RED, "red_alert", "redalert", "klaxon"},
        {Sfx.E_ERROR, "deny", "denied", "error", "incorrect", "unable", "invalid", "fail", "criticalstop", "reject"},
        {Sfx.E_COMMS, "chirp", "hail", "communicator", "combadge", "incoming", "ringin", "transmission", "comm_badge"},
        {Sfx.E_DIAG, "scan", "scanner", "diagnostic", "working", "thinking", "accessing", "sequence", "processing"},
        {Sfx.E_CONFIRM, "confirm", "affirm", "complete", "verified", "accept", "acknowledg", "authacc", "filedone", "success"},
        {Sfx.E_LAUNCH, "launch", "viewscreen_on", "maximize", "engage", "open"},
        {Sfx.E_NAV, "navigation", "viewscreen_off", "minimize", "popup", "swipe", "close", "page"},
        {Sfx.E_BUTTON, "beep", "button", "keypress", "panel", "press", "click", "computer", "tap", "console"},
    };

    static String baseName(String name) {
        String n = name.toLowerCase(Locale.US);
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        return n.replace(' ', '_').replace('-', '_');
    }

    /** @return event index or -1. */
    static int match(String fileName) {
        String n = baseName(fileName);
        String bare = n.replaceAll("[_0-9]+$", "");
        for (int e = 0; e < ALIASES.length; e++) {
            for (String a : ALIASES[e]) if (n.equals(a) || bare.equals(a)) return e;
        }
        for (Object[] rule : RULES) {
            for (int i = 1; i < rule.length; i++) if (n.contains((String) rule[i])) return (Integer) rule[0];
        }
        return -1;
    }

    static boolean isAudio(String name) {
        String n = name.toLowerCase(Locale.US);
        for (String e : AUDIO_EXT) if (n.endsWith(e)) return true;
        return false;
    }

    static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0);
        } catch (Exception ignored) {
        }
        String p = uri.getLastPathSegment();
        return p != null ? p : "sound";
    }

    /** Extracts audio entries from a ZIP into a temporary folder (base names only, so no path traversal). */
    static List<Item> unzip(Context c, Uri zip) throws IOException {
        File tmp = new File(c.getCacheDir(), "trekui_import");
        deleteTree(tmp);
        if (!tmp.mkdirs() && !tmp.isDirectory()) throw new IOException("cannot create temp folder");
        List<Item> out = new ArrayList<>();
        try (InputStream raw = c.getContentResolver().openInputStream(zip);
             ZipInputStream zin = new ZipInputStream(raw)) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (e.isDirectory() || name.contains("__MACOSX") || base.startsWith(".") || !isAudio(base)) continue;
                File f = new File(tmp, out.size() + "_" + base);
                long total = 0;
                try (OutputStream os = new FileOutputStream(f)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        total += n;
                        if (total > 30L * 1024 * 1024) throw new IOException(base + " is too large");
                        os.write(buf, 0, n);
                    }
                }
                out.add(new Item(base, null, f));
            }
        }
        return out;
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ---- import flows ------------------------------------------------------------------------

    /** Auto-match a batch of files to events by filename; only events with a match are replaced. */
    static Report importAuto(Context c, List<Item> items, String sourceLabel) {
        Report r = new Report();
        List<List<Item>> byEvent = new ArrayList<>();
        for (int e = 0; e < Sfx.EVENTS; e++) byEvent.add(new ArrayList<Item>());
        for (Item it : items) {
            int e = match(it.name);
            if (e < 0) r.unmatched.add(it.name);
            else byEvent.get(e).add(it);
        }
        for (int e = 0; e < Sfx.EVENTS; e++) {
            List<Item> list = byEvent.get(e);
            if (list.isEmpty()) continue;
            sortByPreference(list, e);
            // Never auto-pick clips from other series or spoken episode lines; offer them for manual assignment.
            List<Item> eligible = new ArrayList<>();
            for (Item it : list) {
                if (score(it.name, e) >= 10) r.unmatched.add(it.name + " (other series / voice: assign manually)");
                else eligible.add(it);
            }
            list = eligible;
            if (list.isEmpty()) continue;
            int max = e == Sfx.E_BUTTON ? Sfx.MAX_VARIANTS : 1;
            List<Item> use = list.subList(0, Math.min(max, list.size()));
            int ok = assign(c, e, use, sourceLabel, r);
            if (ok > 0) {
                r.assigned++;
                StringBuilder sb = new StringBuilder(Sfx.EVENT_NAMES[e]).append("  ←  ").append(use.get(0).name);
                if (ok > 1) sb.append("  +").append(ok - 1).append(" VARIANT").append(ok > 2 ? "S" : "");
                r.lines.add(sb.toString());
            }
            for (int i = use.size(); i < list.size(); i++) r.unmatched.add(list.get(i).name + " (extra " + Sfx.EVENT_KEYS[e] + ")");
        }
        return r;
    }

    /** Replace one event's sounds with the given files (button press keeps up to 4 variants). */
    static int assign(Context c, int event, List<Item> items, String sourceLabel, Report r) {
        File dir = Sfx.dir(c);
        if (!dir.isDirectory() && !dir.mkdirs()) { r.failed.add("cannot create sound folder"); return 0; }
        int max = event == Sfx.E_BUTTON ? Sfx.MAX_VARIANTS : 1;
        List<String> names = new ArrayList<>();
        List<File> made = new ArrayList<>();
        for (Item it : items) {
            if (names.size() >= max) break;
            File tmp = new File(dir, Sfx.EVENT_KEYS[event] + "_new" + names.size() + ".wav");
            try {
                int[] sr = new int[1];
                float[] pcm = decode(c, it, sr);
                float[] out = process(pcm, sr[0]);
                writeWav(tmp, out, sr[0]);
                names.add(it.name);
                made.add(tmp);
            } catch (Throwable t) {
                r.failed.add(it.name + " (" + (t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName()) + ")");
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        if (made.isEmpty()) return 0;
        clear(c, event);
        Prefs p = Prefs.get(c);
        for (int v = 0; v < made.size(); v++) {
            File dst = Sfx.file(c, event, v);
            //noinspection ResultOfMethodCallIgnored
            made.get(v).renameTo(dst);
            p.put("snd" + event + "name" + v, names.get(v));
        }
        p.put("snd" + event + "n", made.size());
        p.put("snd" + event + "src", sourceLabel);
        return made.size();
    }

    static void clear(Context c, int event) {
        for (int v = 0; v < Sfx.MAX_VARIANTS; v++) {
            //noinspection ResultOfMethodCallIgnored
            Sfx.file(c, event, v).delete();
        }
        Prefs.get(c).sp.edit().remove("snd" + event + "n").remove("snd" + event + "src")
                .apply();
        android.content.SharedPreferences.Editor ed = Prefs.get(c).sp.edit();
        for (int v = 0; v < Sfx.MAX_VARIANTS; v++) ed.remove("snd" + event + "name" + v);
        ed.apply();
    }

    /** Preferred source names per event (earlier = better). */
    private static final String[][] PREFERRED = {
        {"tng_computer_beep", "computer_beep", "computerbeep", "tng_", "beep"},
        {"viewscreen_off", "minimize", "close"},
        {"viewscreen_on", "maximize", "launch"},
        {"transfercomplete", "confirm", "complete", "authacc"},
        {"denybeep", "deny", "error"},
        {"tng_chirp", "chirp", "hailbeep", "hail"},
        {"beepsworking", "scan", "working", "accessing"},
        {"tng_red_alert", "red_alert", "redalert"},
    };
    /** File-name tags of other collections / non-console sources (as named on the public source pages), and spoken clips: ranked last. */
    private static final String[] AVOID = {"tos_", "ent_", "ds9", "voy_", "alien", "_ep"};

    private static int score(String name, int event) {
        String n = baseName(name);
        int s = 0;
        for (String av : AVOID) if (n.contains(av)) s += 10;
        if (n.contains("tng")) s -= 3;
        if (n.contains("clean")) s -= 1;
        String[] pref = PREFERRED[event];
        for (int i = 0; i < pref.length; i++) if (n.contains(pref[i])) { s -= (pref.length - i); break; }
        return s;
    }

    private static void sortByPreference(List<Item> list, final int event) {
        Collections.sort(list, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                int d = score(a.name, event) - score(b.name, event);
                return d != 0 ? d : naturalKey(a.name).compareTo(naturalKey(b.name));
            }
        });
    }

    private static void sortByName(List<Item> list) {
        Collections.sort(list, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) { return naturalKey(a.name).compareTo(naturalKey(b.name)); }
        });
    }

    /** "beep 2" sorts before "beep 10". */
    private static String naturalKey(String s) {
        StringBuilder sb = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+|\\D+").matcher(s.toLowerCase(Locale.US));
        while (m.find()) {
            String g = m.group();
            if (Character.isDigit(g.charAt(0))) sb.append(String.format(Locale.US, "%08d", Long.parseLong(g.length() > 8 ? g.substring(0, 8) : g)));
            else sb.append(g);
        }
        return sb.toString();
    }

    // ---- decode / process / write ------------------------------------------------------------

    /** Decodes to mono float PCM at the file's own sample rate (max 20 s read). */
    static float[] decode(Context c, Item it, int[] srOut) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        try {
            if (it.file != null) ex.setDataSource(it.file.getAbsolutePath());
            else ex.setDataSource(c, it.uri, null);
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
            float[] out = new float[sr * 2];
            int len = 0;
            boolean inEos = false, outEos = false;
            int idle = 0;
            while (!outEos && idle < 600) {
                boolean progress = false;
                if (!inEos) {
                    int ii = dec.dequeueInputBuffer(5000);
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
                int oi = dec.dequeueOutputBuffer(info, 5000);
                if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        int bytesPer = enc == AudioFormat.ENCODING_PCM_FLOAT ? 4 : enc == AudioFormat.ENCODING_PCM_8BIT ? 1 : 2;
                        int frames = info.size / (bytesPer * ch);
                        if (len + frames > out.length) out = Arrays.copyOf(out, Math.max(out.length * 2, len + frames));
                        for (int f = 0; f < frames; f++) {
                            float sum = 0;
                            for (int k = 0; k < ch; k++) {
                                if (bytesPer == 4) sum += ob.getFloat();
                                else if (bytesPer == 1) sum += ((ob.get() & 0xFF) - 128) / 128f;
                                else sum += ob.getShort() / 32768f;
                            }
                            out[len++] = sum / ch;
                        }
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    if (len > sr * 20) outEos = true;
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
            srOut[0] = sr;
            return Arrays.copyOf(out, len);
        } finally {
            if (dec != null) { try { dec.stop(); } catch (Exception ignored) { } dec.release(); }
            ex.release();
        }
    }

    /**
     * Trim + gentle level match. Only gain and edge fades are applied; the waveform's pitch,
     * duration (apart from removed silence) and timbre are untouched.
     */
    static float[] process(float[] x, int sr) throws IOException { return process(x, sr, MAX_SECONDS); }

    /** @param maxSeconds longer clips are cut (with a fade) at this length */
    static float[] process(float[] x, int sr, int maxSeconds) throws IOException {
        final int MAX_SECONDS = maxSeconds;
        float peak = 0;
        for (float v : x) peak = Math.max(peak, Math.abs(v));
        if (peak < 1e-4f) throw new IOException("file is silent");
        float thr = Math.max(peak * 0.0056f, 0.0006f); // -45 dB relative to the clip's own peak
        int a = 0, b = x.length - 1;
        while (a < x.length && Math.abs(x[a]) < thr) a++;
        while (b > a && Math.abs(x[b]) < thr) b--;
        int start = Math.max(0, a - (int) (sr * 0.003f));
        int end = Math.min(x.length, b + 1 + (int) (sr * 0.04f));
        float[] y = Arrays.copyOfRange(x, start, end);
        boolean cutTail = end < x.length;
        if (y.length > sr * MAX_SECONDS) {
            y = Arrays.copyOf(y, sr * MAX_SECONDS);
            cutTail = true;
        }
        // de-click the new edges only
        if (start > 0) fade(y, 0, Math.min(y.length, (int) (sr * 0.0015f)), true);
        if (cutTail) {
            int n = Math.min(y.length, (int) (sr * (y.length >= sr * MAX_SECONDS ? 0.05f : 0.01f)));
            fade(y, y.length - n, n, false);
        }
        // gentle loudness match: RMS of the active part toward -18 dBFS, limited to ±9 dB, peak ≤ -1 dBFS
        double sum = 0;
        int cnt = 0;
        float pk = 0;
        for (float v : y) {
            pk = Math.max(pk, Math.abs(v));
            if (Math.abs(v) >= thr) { sum += v * v; cnt++; }
        }
        double rms = Math.sqrt(sum / Math.max(1, cnt));
        double g = rms > 0 ? 0.126 / rms : 1;
        g = Math.max(0.355, Math.min(2.82, g));
        if (pk * g > 0.89) g = 0.89 / pk;
        for (int i = 0; i < y.length; i++) y[i] *= (float) g;
        return y;
    }

    private static void fade(float[] y, int from, int n, boolean in) {
        for (int i = 0; i < n; i++) {
            float k = (i + 1f) / n;
            y[from + i] *= in ? k : 1f - k;
        }
    }

    static void writeWav(File f, float[] y, int sr) throws IOException {
        int dataLen = y.length * 2;
        ByteBuffer bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        bb.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + dataLen).put(new byte[]{'W', 'A', 'V', 'E'});
        bb.put(new byte[]{'f', 'm', 't', ' '}).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(sr).putInt(sr * 2).putShort((short) 2).putShort((short) 16);
        bb.put(new byte[]{'d', 'a', 't', 'a'}).putInt(dataLen);
        for (float v : y) bb.putShort((short) Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f));
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(bb.array());
        }
    }
}
