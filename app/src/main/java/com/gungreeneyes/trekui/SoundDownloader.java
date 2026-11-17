package com.gungreeneyes.trekui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SETTINGS › AUDIO › DOWNLOAD SOUND LIBRARY. On the user's request, fetches the original recordings
 * listed in {@link SoundCatalog} from their public web pages, verifies each file's SHA-256, decodes it
 * ({@link SoundDecoder}), renders every app sound cut from it ({@link SoundProcessor}) and stores the
 * clips in {@link SoundCache} (app-private). The downloaded originals are deleted right after.
 *
 * <p>One background thread; every original is fetched once even if several sounds are cut from it;
 * sounds already in the cache are skipped, so an interrupted run simply resumes. Callbacks arrive on
 * the main thread. HTTPS only, with connect/read timeouts and a size cap. Nothing is uploaded and
 * no personal data is sent: a plain GET with a generic User-Agent.
 */
final class SoundDownloader {
    /** Progress / completion callbacks, always on the main thread. */
    interface Listener {
        /** @param done sounds finished so far, @param total sounds in this run, @param label what is being worked on */
        void progress(int done, int total, String label);

        void finished(Result result);
    }

    /** Outcome of a run. */
    static final class Result {
        int rendered, skipped, failed, total;
        boolean cancelled;
        final List<String> errors = new ArrayList<>();

        /** Short human-readable summary for a dialog. */
        String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "%d DOWNLOADED, %d ALREADY PRESENT, %d FAILED", rendered, skipped, failed));
            if (cancelled) sb.append("\nCANCELLED");
            int n = Math.min(8, errors.size());
            for (int i = 0; i < n; i++) sb.append("\n• ").append(errors.get(i));
            if (errors.size() > n) sb.append("\n• … ").append(errors.size() - n).append(" MORE");
            return sb.toString();
        }
    }

    static final int CONNECT_TIMEOUT_MS = 15_000;
    static final int READ_TIMEOUT_MS = 30_000;
    static final long MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024;
    private static final String USER_AGENT = "Mozilla/5.0 (Android; TrekUI sound library)";

    private static volatile boolean running, cancel;
    private static volatile Listener listener;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SoundDownloader() {}

    static boolean isRunning() { return running; }

    /** Asks the running download to stop after the current file. */
    static void cancel() { cancel = true; }

    /** Swap the listener (e.g. null from onDestroy so a running download never holds an Activity). */
    static void setListener(Listener l) { listener = l; }

    /**
     * Starts a download unless one is already running.
     * A null listener keeps the one already set with {@link #setListener}.
     *
     * @param coreOnly only the sounds the interface events use by default (small), else every available sound
     * @return false if a download is already running
     */
    static synchronized boolean start(Context context, final boolean coreOnly, Listener l) {
        if (l != null) listener = l;
        if (running) return false;
        running = true;
        cancel = false;
        final Context app = context.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                Result r = new Result();
                try {
                    runAll(app, coreOnly, r);
                } catch (Throwable e) {
                    r.errors.add(String.valueOf(e.getMessage() != null ? e.getMessage() : e));
                } finally {
                    running = false;
                    final Result fin = r;
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            if (fin.rendered > 0) Sfx.reload(app);
                            Listener cb = listener;
                            if (cb != null) cb.finished(fin);
                        }
                    });
                }
            }
        }, "trekui-sound-download");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        return true;
    }

    private static void post(final int done, final int total, final String label) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                Listener cb = listener;
                if (cb != null) cb.progress(done, total, label);
            }
        });
    }

    private static void runAll(Context c, boolean coreOnly, Result r) throws IOException {
        SoundCatalog cat = SoundCatalog.load(c);
        List<SoundCatalog.Sound> want = cat.available(coreOnly);
        r.total = want.size();
        File out = SoundCache.dir(c);
        if (!out.isDirectory() && !out.mkdirs()) throw new IOException("cannot create the sound folder");
        long need = SoundCatalog.estimatedCacheBytes(want) + 20L * 1024 * 1024;
        if (out.getUsableSpace() > 0 && out.getUsableSpace() < need) {
            throw new IOException(String.format(Locale.US, "not enough free space (needs about %d MB)", need / (1024 * 1024)));
        }
        File tmpDir = new File(c.getCacheDir(), "sound_download");
        SoundImporter.deleteTree(tmpDir);
        if (!tmpDir.mkdirs() && !tmpDir.isDirectory()) throw new IOException("cannot create the download folder");
        int done = 0;
        try {
            for (Map.Entry<String, List<SoundCatalog.Sound>> e : SoundCatalog.bySource(want).entrySet()) {
                if (cancel) { r.cancelled = true; break; }
                List<SoundCatalog.Sound> group = new ArrayList<>();
                for (SoundCatalog.Sound s : e.getValue()) {
                    if (SoundCache.isCached(c, s.id)) { r.skipped++; done++; } else group.add(s);
                }
                if (group.isEmpty()) continue;
                post(done, r.total, group.get(0).label);
                File orig = new File(tmpDir, e.getKey() + ".bin");
                try {
                    fetchVerified(group.get(0), orig);
                    double until = 0;
                    for (SoundCatalog.Sound s : group) {
                        if (s.end <= 0) { until = 0; break; }
                        until = Math.max(until, s.end);
                    }
                    SoundProcessor.Pcm pcm = SoundDecoder.decode(orig, until);
                    for (SoundCatalog.Sound s : group) {
                        try {
                            SoundProcessor.Pcm clip = SoundProcessor.render(pcm, s.start, s.end, s.gainDb, s.mono, s.fadeInMs, s.fadeOutMs);
                            SoundProcessor.writeWav(SoundCache.file(c, s.id), clip);
                            r.rendered++;
                        } catch (IOException ex) {
                            r.failed++;
                            r.errors.add(s.label + ": " + ex.getMessage());
                        }
                        post(++done, r.total, s.label);
                    }
                } catch (IOException ex) {
                    r.failed += group.size();
                    done += group.size();
                    r.errors.add(group.get(0).label + ": " + ex.getMessage());
                    post(done, r.total, group.get(0).label);
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    orig.delete();
                }
            }
        } finally {
            SoundImporter.deleteTree(tmpDir);
        }
    }

    /** Tries the preferred source, then each alternate, until one downloads with the right SHA-256. */
    private static void fetchVerified(SoundCatalog.Sound s, File dst) throws IOException {
        IOException last = null;
        for (SoundCatalog.Source src : s.allSources()) {
            if (cancel) throw new IOException("cancelled");
            try {
                String sha = download(src.url, dst);
                if (SoundProcessor.shaMatches(sha, src.sha256)) return;
                last = new IOException("file at " + host(src.url) + " has changed (checksum mismatch)");
            } catch (IOException ex) {
                last = new IOException(host(src.url) + ": " + ex.getMessage(), ex);
            }
        }
        throw last != null ? last : new IOException("no download source");
    }

    private static String host(String url) {
        try { return new URL(url).getHost(); } catch (Exception e) { return "?"; }
    }

    /** Streams the URL into dst while hashing it; returns the SHA-256 hex. HTTPS only. */
    static String download(String url, File dst) throws IOException {
        URL u = new URL(url);
        if (!"https".equalsIgnoreCase(u.getProtocol())) throw new IOException("not an https link");
        HttpURLConnection con = (HttpURLConnection) u.openConnection();
        try {
            con.setConnectTimeout(CONNECT_TIMEOUT_MS);
            con.setReadTimeout(READ_TIMEOUT_MS);
            con.setInstanceFollowRedirects(true);
            con.setRequestProperty("User-Agent", USER_AGENT);
            con.setRequestProperty("Accept", "audio/*, */*");
            int code = con.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            if (!"https".equalsIgnoreCase(con.getURL().getProtocol())) throw new IOException("redirected off https");
            long len = con.getContentLengthLong();
            if (len > MAX_DOWNLOAD_BYTES) throw new IOException("file too large");
            MessageDigest md = SoundProcessor.sha256();
            long total = 0;
            try (InputStream in = con.getInputStream(); OutputStream os = new FileOutputStream(dst)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_DOWNLOAD_BYTES) throw new IOException("file too large");
                    if (cancel) throw new IOException("cancelled");
                    md.update(buf, 0, n);
                    os.write(buf, 0, n);
                }
            }
            return SoundProcessor.hex(md.digest());
        } finally {
            con.disconnect();
        }
    }
}
