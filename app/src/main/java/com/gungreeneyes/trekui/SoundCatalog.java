package com.gungreeneyes.trekui;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The sound manifest shipped in {@code assets/sounds/manifest.json}: for every sound id the app
 * knows (the same names the personal build has in res/raw, e.g. {@code ui_panel_open},
 * {@code tap_rot_1}, {@code tk_wpn_phaser_1}) it says where the original recording is publicly
 * downloadable, its SHA-256, and how the app cuts it (start/end seconds, gain, mono fold, fades).
 *
 * <p>The app ships no recordings. {@link SoundDownloader} uses this catalog to fetch the originals on
 * the user's request, verify them and render them into {@link SoundCache}. Sounds without a
 * confident public source are listed with {@code available = false}; the user can still import their
 * own file for them.
 *
 * <p>Parsing is plain {@code org.json} so it can be unit-tested under Robolectric.
 */
final class SoundCatalog {
    /** Asset path of the manifest inside the APK. */
    static final String ASSET = "sounds/manifest.json";
    /** Highest manifest format this code understands. */
    static final int FORMAT = 1;

    /** One downloadable original (a file on a public web page). */
    static final class Source {
        final String url, page, sha256, site;
        final long bytes;

        Source(String url, String page, String sha256, String site, long bytes) {
            this.url = url;
            this.page = page;
            this.sha256 = sha256 == null ? "" : sha256.toLowerCase(Locale.US);
            this.site = site;
            this.bytes = bytes;
        }
    }

    /** One app sound and the recipe that turns its original into the clip the app plays. */
    static final class Sound {
        final String id, label, group;
        final List<String> events;
        final boolean available, core;
        final Source source;
        final List<Source> alternates;
        /** Cut from the original, in seconds; {@code end <= 0} means "to the end of the file". */
        final double start, end;
        final double gainDb;
        final boolean mono;
        final int fadeInMs, fadeOutMs;
        final double confidence;

        Sound(String id, String label, String group, List<String> events, boolean available, boolean core, Source source,
              List<Source> alternates, double start, double end, double gainDb, boolean mono, int fadeInMs, int fadeOutMs,
              double confidence) {
            this.id = id;
            this.label = label;
            this.group = group;
            this.events = events;
            this.available = available;
            this.core = core;
            this.source = source;
            this.alternates = alternates;
            this.start = start;
            this.end = end;
            this.gainDb = gainDb;
            this.mono = mono;
            this.fadeInMs = fadeInMs;
            this.fadeOutMs = fadeOutMs;
            this.confidence = confidence;
        }

        /** Length of the rendered clip in seconds, or 0 when unknown (cut to end of file). */
        double seconds() { return end > start ? end - start : 0; }

        /** Every place the original can be fetched from, preferred first. */
        List<Source> allSources() {
            List<Source> out = new ArrayList<>();
            if (source != null) out.add(source);
            out.addAll(alternates);
            return out;
        }
    }

    final int format;
    final String notice;
    final List<Sound> sounds;
    private final Map<String, Sound> byId = new LinkedHashMap<>();

    private SoundCatalog(int format, String notice, List<Sound> sounds) {
        this.format = format;
        this.notice = notice;
        this.sounds = Collections.unmodifiableList(sounds);
        for (Sound s : sounds) byId.put(s.id, s);
    }

    /** An empty catalog (no manifest in this build): nothing to download, nothing breaks. */
    static SoundCatalog empty() { return new SoundCatalog(FORMAT, "", new ArrayList<Sound>()); }

    Sound get(String id) { return id == null ? null : byId.get(id); }

    int size() { return sounds.size(); }

    /** Sounds that have a verified public source. */
    List<Sound> available(boolean coreOnly) {
        List<Sound> out = new ArrayList<>();
        for (Sound s : sounds) if (s.available && s.source != null && (!coreOnly || s.core)) out.add(s);
        return out;
    }

    /**
     * Groups the given sounds by the original they are cut from (keyed by SHA-256), so every
     * original is downloaded once even when several sounds come from it (tap_rot_1 and tap_rot_2
     * are both cut from one file).
     */
    static Map<String, List<Sound>> bySource(List<Sound> list) {
        Map<String, List<Sound>> out = new LinkedHashMap<>();
        for (Sound s : list) {
            if (s.source == null) continue;
            List<Sound> g = out.get(s.source.sha256);
            if (g == null) { g = new ArrayList<>(); out.put(s.source.sha256, g); }
            g.add(s);
        }
        return out;
    }

    /**
     * Rough size of the rendered cache for these sounds: 16-bit PCM at 44.1 kHz, mono or stereo.
     * Used for the "about N MB" hint before downloading.
     */
    static long estimatedCacheBytes(List<Sound> list) {
        double total = 0;
        for (Sound s : list) {
            double sec = s.seconds() > 0 ? s.seconds() : 3;
            total += sec * 44100 * 2 * (s.mono ? 1 : 2) + 44;
        }
        return (long) total;
    }

    /** Sum of the original downloads' sizes (each original counted once). */
    static long downloadBytes(List<Sound> list) {
        long total = 0;
        for (List<Sound> g : bySource(list).values()) total += Math.max(0, g.get(0).source.bytes);
        return total;
    }

    // ---- parsing --------------------------------------------------------------------------

    /** Parses manifest JSON. Unknown fields are ignored; malformed entries are skipped. */
    static SoundCatalog parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        int format = root.optInt("format", 1);
        if (format > FORMAT) throw new JSONException("manifest format " + format + " is newer than this app understands");
        JSONArray arr = root.optJSONArray("sounds");
        List<Sound> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                Sound s = parseSound(o);
                if (s != null) out.add(s);
            }
        }
        return new SoundCatalog(format, root.optString("notice", ""), out);
    }

    private static Sound parseSound(JSONObject o) {
        String id = o.optString("id", "");
        if (!isValidId(id)) return null;
        List<String> events = new ArrayList<>();
        JSONArray ev = o.optJSONArray("events");
        if (ev != null) for (int i = 0; i < ev.length(); i++) events.add(ev.optString(i));
        Source src = parseSource(o.optJSONObject("source"));
        List<Source> alts = new ArrayList<>();
        JSONArray a = o.optJSONArray("alternates");
        if (a != null) {
            for (int i = 0; i < a.length(); i++) {
                Source s = parseSource(a.optJSONObject(i));
                if (s != null) alts.add(s);
            }
        }
        boolean available = o.optBoolean("available", src != null) && src != null;
        double start = Math.max(0, o.optDouble("start", 0));
        double end = o.optDouble("end", 0);
        if (Double.isNaN(end)) end = 0;
        double gain = o.optDouble("gain_db", 0);
        if (Double.isNaN(gain) || Double.isInfinite(gain)) gain = 0;
        gain = Math.max(-40, Math.min(24, gain));
        return new Sound(id, o.optString("label", id.toUpperCase(Locale.US)), o.optString("group", ""), events, available,
                o.optBoolean("core", false), src, alts, start, end, gain, o.optBoolean("mono", true),
                clampInt(o.optInt("fade_in_ms", 4), 0, 5000), clampInt(o.optInt("fade_out_ms", 4), 0, 5000),
                o.optDouble("confidence", 0));
    }

    private static Source parseSource(JSONObject o) {
        if (o == null) return null;
        String url = o.optString("url", "");
        String sha = o.optString("sha256", "");
        if (!url.startsWith("https://") || !sha.matches("[0-9a-fA-F]{64}")) return null;
        return new Source(url, o.optString("page", ""), sha, o.optString("site", ""), o.optLong("bytes", -1));
    }

    /** A sound id doubles as a file name, so only lower-case letters, digits and '_' are allowed. */
    static boolean isValidId(String id) { return id != null && id.matches("[a-z0-9_]{1,80}"); }

    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---- loading --------------------------------------------------------------------------

    private static SoundCatalog cached;

    /** The bundled manifest (cached), or an empty catalog if the asset is missing or invalid. */
    static synchronized SoundCatalog load(Context c) {
        if (cached != null) return cached;
        try (InputStream in = c.getApplicationContext().getAssets().open(ASSET)) {
            cached = parse(readAll(in));
        } catch (IOException | JSONException | RuntimeException e) {
            cached = empty();
        }
        return cached;
    }

    static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
