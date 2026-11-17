package com.gungreeneyes.trekui;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Automatic layout snapshots: a {@link LayoutArchive} document written to {@code filesDir/layouts/}
 * before anything that could wreck a layout — an app upgrade (a {@code versionCode} change seen on
 * launch), a layout import, a reset, an interface switch, a restore — keeping the newest
 * {@link #KEEP}. CONFIG › BACKUP lists them with RESTORE, and UNDO LAST CHANGE restores the newest.
 *
 * <p>The preferences are copied on the calling thread (they are in memory) so the snapshot is exactly
 * the state before the change that follows; the file write runs on one background thread, in order.
 * A snapshot identical to the newest one is not written again, so switching back and forth doesn't
 * push older layouts out. File names carry the time and reason ({@code 20261117-153012-345_import.json}),
 * so the list is built without opening the files; rotation and name parsing are pure and unit-tested.
 */
final class LayoutSnapshots {
    /** Snapshots kept; older ones are deleted after each write. */
    static final int KEEP = 10;
    /** Folder under filesDir. */
    static final String DIR = "layouts";
    /** Pref holding the versionCode the layout was last seen with (excluded from archives). */
    static final String KEY_VERSION_CODE = "layoutVersionCode";

    static final String R_UPGRADE = "upgrade", R_IMPORT = "import", R_RESET = "reset", R_INTERFACE = "interface",
            R_RESTORE = "restore", R_MANUAL = "manual";
    /** Reasons, in the order of {@link #REASON_LABELS}. */
    static final String[] REASONS = {R_UPGRADE, R_IMPORT, R_RESET, R_INTERFACE, R_RESTORE, R_MANUAL};
    static final String[] REASON_LABELS = {"BEFORE APP UPDATE", "BEFORE IMPORT", "BEFORE RESET", "BEFORE INTERFACE SWITCH",
            "BEFORE RESTORE", "SAVED BY YOU"};

    private static final String STAMP = "yyyyMMdd-HHmmss-SSS";
    private static final String EXT = ".json";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "layout-snapshots");
        t.setDaemon(true);
        return t;
    });

    private LayoutSnapshots() {}

    /** One snapshot on disk. */
    static final class Entry {
        final File file;
        final long time;
        final String reason;

        Entry(File file, long time, String reason) {
            this.file = file;
            this.time = time;
            this.reason = reason;
        }

        /** "BEFORE IMPORT" etc. */
        String reasonLabel() { return reasonLabel(reason); }

        static String reasonLabel(String reason) {
            for (int i = 0; i < REASONS.length; i++) if (REASONS[i].equals(reason)) return REASON_LABELS[i];
            return reason.toUpperCase(Locale.ROOT);
        }
    }

    // ---- pure helpers (unit-tested) -----------------------------------------------------------------

    /** File name for a snapshot taken at {@code time} for {@code reason}. Sorts chronologically. */
    static String fileName(long time, String reason) {
        return new SimpleDateFormat(STAMP, Locale.US).format(new Date(time)) + "_" + reason + EXT;
    }

    /** Parses a snapshot file name; null when it isn't one. */
    static Entry parse(File file) {
        String name = file.getName();
        if (!name.endsWith(EXT)) return null;
        int sep = name.indexOf('_');
        if (sep != STAMP.length()) return null;
        String reason = name.substring(sep + 1, name.length() - EXT.length());
        if (reason.isEmpty()) return null;
        try {
            SimpleDateFormat f = new SimpleDateFormat(STAMP, Locale.US);
            f.setLenient(false);
            Date d = f.parse(name.substring(0, sep));
            return d == null ? null : new Entry(file, d.getTime(), reason);
        } catch (java.text.ParseException e) {
            return null;
        }
    }

    /** The snapshot names to delete so only the newest {@code keep} remain (names sort chronologically). */
    static List<String> rotation(List<String> names, int keep) {
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        int excess = sorted.size() - Math.max(0, keep);
        return excess <= 0 ? new ArrayList<>() : new ArrayList<>(sorted.subList(0, excess));
    }

    /**
     * Whether launching with {@code currentCode} after {@code storedCode} (-1 = never stored) needs an
     * upgrade snapshot. A first launch of a build that predates this check still snapshots when a layout
     * already exists ({@code hasLayout}); a fresh install doesn't.
     */
    static boolean needsUpgradeSnapshot(int storedCode, int currentCode, boolean hasLayout) {
        if (storedCode < 0) return hasLayout;
        return storedCode != currentCode;
    }

    /** True when two documents hold the same layout (ignores the time stamp and app version). */
    static boolean sameLayout(JSONObject a, JSONObject b) {
        if (a == null || b == null) return false;
        JSONObject pa = a.optJSONObject("prefs"), pb = b.optJSONObject("prefs");
        return pa != null && pb != null && pa.toString().equals(pb.toString());
    }

    // ---- device side ------------------------------------------------------------------------------

    private static File dir(Context c) { return new File(c.getFilesDir(), DIR); }

    /**
     * Snapshots the current layout for {@code reason}. Preferences are read now; the write, the
     * duplicate check and the rotation happen on the snapshot thread.
     */
    static void snapshot(Context c, final String reason) {
        final Context app = c.getApplicationContext();
        final JSONObject doc = LayoutArchive.capture(app);
        IO.execute(() -> write(app, doc, reason, System.currentTimeMillis()));
    }

    /** Writes one snapshot unless it matches the newest; then rotates. Snapshot thread. */
    private static void write(Context c, JSONObject doc, String reason, long time) {
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) return;
        List<Entry> existing = list(c);
        if (!existing.isEmpty() && sameLayout(doc, readDoc(existing.get(0).file))) return;
        File out = new File(d, fileName(time, reason));
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(doc.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return;
        }
        List<String> names = new ArrayList<>();
        String[] all = d.list();
        if (all != null) for (String n : all) if (parse(new File(d, n)) != null) names.add(n);
        for (String n : rotation(names, KEEP)) {
            //noinspection ResultOfMethodCallIgnored
            new File(d, n).delete();
        }
    }

    /** The snapshots on disk, newest first. Only lists the folder (no file is opened). */
    static List<Entry> list(Context c) {
        List<Entry> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        for (File f : files) {
            Entry e = parse(f);
            if (e != null) out.add(e);
        }
        Collections.sort(out, (a, b) -> b.file.getName().compareTo(a.file.getName()));
        return out;
    }

    /** Reads a snapshot document, or null when unreadable. Worker thread. */
    static JSONObject readDoc(File f) {
        try {
            return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** Reads and validates a snapshot. Worker thread. */
    static LayoutArchive.Parsed load(Entry e) throws LayoutArchive.InvalidLayoutException {
        JSONObject doc = readDoc(e.file);
        if (doc == null) throw new LayoutArchive.InvalidLayoutException("SNAPSHOT UNREADABLE");
        return LayoutArchive.parse(doc.toString());
    }

    /**
     * Called from home's onCreate: when the app's versionCode differs from the one the layout was
     * last seen with, snapshot the layout before anything new touches it, then remember the new code.
     */
    static void checkUpgrade(Context c) {
        Prefs p = Prefs.get(c);
        int stored = p.i(KEY_VERSION_CODE, -1);
        int current = BuildConfig.VERSION_CODE;
        if (stored == current) return;
        boolean hasLayout = !LayoutArchive.filter(p.sp.getAll()).isEmpty();
        if (needsUpgradeSnapshot(stored, current, hasLayout)) snapshot(c, R_UPGRADE);
        p.put(KEY_VERSION_CODE, current);
    }
}
