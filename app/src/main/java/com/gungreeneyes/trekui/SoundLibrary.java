package com.gungreeneyes.trekui;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Every sound the launcher can play: the library clips (downloaded on request through
 * {@link SoundDownloader} into {@link SoundCache}, or bundled in res/raw on personal develop builds)
 * plus any the user imports (files or a ZIP). Imported clips are decoded, trimmed and level-matched
 * once and stored as WAV in the app's private storage; they can be assigned to any console element, to
 * an interface event, or exported as a ringtone / notification / alarm.
 *
 * <p>Clip IDs: a library name ("comms_hail", resolved cache → res/raw → silence) or "lib:" + slug
 * for imported clips. With nothing downloaded or imported the library is simply empty.
 */
final class SoundLibrary {
    static final String PREFIX = "lib:";
    private static final int MAX_SECONDS = 40;

    static final class Entry {
        final String id, label;
        final boolean imported;

        Entry(String id, String label, boolean imported) {
            this.id = id;
            this.label = label;
            this.imported = imported;
        }
    }

    private SoundLibrary() {}

    /** Folder of imported clips. */
    static File dir(Context c) { return new File(c.getFilesDir(), "trekui_library"); }

    /** WAV file of an imported ("lib:") clip. */
    static File file(Context c, String id) { return new File(dir(c), slugOf(id) + ".wav"); }

    private static String slugOf(String id) { return id.startsWith(PREFIX) ? id.substring(PREFIX.length()) : id; }

    private static JSONArray index(Context c) {
        try { return new JSONArray(Prefs.get(c).s("soundLib", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static void saveIndex(Context c, JSONArray a) { Prefs.get(c).put("soundLib", a.toString()); }

    /** Library clips that can play (downloaded or bundled, in library order), then imported ones (newest last). */
    static List<Entry> all(Context c) {
        List<Entry> out = new ArrayList<>();
        for (int i = 1; i < Sfx.LIBRARY.length; i++) {
            if (Sfx.available(c, Sfx.LIBRARY[i])) out.add(new Entry(Sfx.LIBRARY[i], Sfx.LIBRARY_LABELS[i], false));
        }
        out.addAll(imported(c));
        return out;
    }

    static List<Entry> imported(Context c) {
        List<Entry> out = new ArrayList<>();
        JSONArray a = index(c);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String id = PREFIX + o.optString("slug");
            if (file(c, id).isFile()) out.add(new Entry(id, o.optString("label"), true));
        }
        return out;
    }

    /** True if the clip can play: an imported file that exists, or an available library clip. */
    static boolean exists(Context c, String id) {
        if (id == null || id.isEmpty() || "none".equals(id)) return false;
        if (Sfx.TAP_SET.equals(id)) return Sfx.available(c, Sfx.TAP_SET_CLIPS[0]);
        return id.startsWith(PREFIX) ? file(c, id).isFile() : Sfx.available(c, id);
    }

    static String label(Context c, String id) {
        if (id == null) return "DEFAULT";
        if ("none".equals(id)) return "SILENT";
        if (id.startsWith(PREFIX)) {
            JSONArray a = index(c);
            String slug = slugOf(id);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && slug.equals(o.optString("slug"))) return o.optString("label");
            }
            return "MISSING SOUND";
        }
        return Sfx.libraryLabel(id);
    }

    /** "hailingfrequencies_open3.mp3" → "HAILINGFREQUENCIES OPEN 3". Pure Java (unit-tested). */
    static String prettyName(String fileName) {
        String s = fileName;
        int dot = s.lastIndexOf('.');
        if (dot > 0) s = s.substring(0, dot);
        s = s.replaceAll("^\\d+_", "");                       // "3_" prefixes from ZIP extraction
        s = s.replaceAll("(?i)[_\\- ]?(clean|ep)$", "");       // archive suffixes
        s = s.replaceAll("[_\\-.]+", " ");
        s = s.replaceAll("([A-Za-z])(\\d)", "$1 $2");
        s = s.replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? "IMPORTED SOUND" : s.toUpperCase(Locale.US);
    }

    static String slugFor(String label, JSONArray existing) {
        String base = label.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "sound";
        if (base.length() > 40) base = base.substring(0, 40);
        String slug = base;
        int n = 2;
        while (contains(existing, slug)) slug = base + "_" + n++;
        return slug;
    }

    private static boolean contains(JSONArray a, String slug) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && slug.equals(o.optString("slug"))) return true;
        }
        return false;
    }

    /** Imports audio files into the library. Runs on a background thread. */
    static SoundImporter.Report importItems(Context c, List<SoundImporter.Item> items) {
        SoundImporter.Report r = new SoundImporter.Report();
        File d = dir(c);
        if (!d.isDirectory() && !d.mkdirs()) {
            r.failed.add("cannot create the sound library folder");
            return r;
        }
        JSONArray idx = index(c);
        for (SoundImporter.Item it : items) {
            try {
                int[] sr = new int[1];
                float[] x = SoundImporter.decode(c, it, sr);
                float[] y = SoundImporter.process(x, sr[0], MAX_SECONDS);
                String label = prettyName(it.name);
                String slug = slugFor(label, idx);
                SoundImporter.writeWav(new File(d, slug + ".wav"), y, sr[0]);
                idx.put(new JSONObject().put("slug", slug).put("label", label).put("src", it.name));
                r.lines.add(label + String.format(Locale.US, "  •  %.1f S", y.length / (float) sr[0]));
                r.assigned++;
            } catch (Throwable t) {
                r.failed.add(it.name + ": " + (t.getMessage() != null ? t.getMessage() : t.toString()));
            }
        }
        saveIndex(c, idx);
        return r;
    }

    static void rename(Context c, String id, String label) {
        JSONArray a = index(c);
        String slug = slugOf(id);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && slug.equals(o.optString("slug"))) {
                try { o.put("label", label.trim().toUpperCase(Locale.getDefault())); } catch (Exception ignored) { }
            }
        }
        saveIndex(c, a);
    }

    static void delete(Context c, String id) {
        JSONArray a = index(c), keep = new JSONArray();
        String slug = slugOf(id);
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !slug.equals(o.optString("slug"))) keep.put(o);
        }
        saveIndex(c, keep);
        //noinspection ResultOfMethodCallIgnored
        file(c, id).delete();
    }
}
