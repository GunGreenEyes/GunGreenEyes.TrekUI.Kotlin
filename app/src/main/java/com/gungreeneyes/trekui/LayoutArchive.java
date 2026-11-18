package com.gungreeneyes.trekui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * The home layout and appearance as a versioned JSON document: the format behind LAYOUT EXPORT /
 * IMPORT in CONFIG › BACKUP and behind the automatic {@link LayoutSnapshots}.
 *
 * <p>The document holds every entry of the {@code "trekui"} preferences file ({@link Prefs#FILE}) except
 * the ones {@link #isExcluded} rejects: credentials (Govee key, Nest OAuth client, secret and tokens,
 * and anything whose name looks like a key, token, secret or password), device-bound values (Govee
 * light addresses, Nest device lists, ringtone content URIs, the weather location) and transient
 * bookkeeping (layout version counter, weather cache, cloak timers, permission prompts, first-run
 * flags). Excluded entries are never written to a file and never touched when one is applied, so a
 * restore can't log anyone out of their smart devices.
 *
 * <pre>
 * { "format": "trekui-layout", "schema": 1, "createdAt": 1700000000000,
 *   "app": { "versionCode": 62, "versionName": "6.2.0" }, "interface": "COMMAND",
 *   "prefs": { "faction": {"t": "int", "v": 0}, "tilesJson": {"t": "string", "v": "[…]"}, … } }
 * </pre>
 *
 * <p>Value types mirror SharedPreferences: {@code int}, {@code long}, {@code float}, {@code bool},
 * {@code string} and {@code set} (a string set). Schema 0 is an unversioned flat object of plain
 * values (a raw SharedPreferences dump); {@link #migrate} upgrades it to the current schema by
 * inferring the types. Everything in this class except the few Context methods at the end is pure
 * and unit-tested on the JVM.
 */
final class LayoutArchive {
    /** Value of the {@code format} field identifying a layout document. */
    static final String FORMAT = "trekui-layout";
    /** Current document schema. Bump it and add a step to {@link #migrate} when the layout changes shape. */
    static final int SCHEMA = 1;
    /** MIME type used for SAF export and import. */
    static final String MIME = "application/json";

    /** Largest document accepted on import, in characters. */
    static final int MAX_DOCUMENT_CHARS = 2_000_000;
    /** Most preference entries accepted. */
    static final int MAX_ENTRIES = 2000;
    /** Longest preference key accepted. */
    static final int MAX_KEY_LENGTH = 120;
    /** Longest string value accepted (tile and slot lists are JSON strings, so this is generous). */
    static final int MAX_STRING_LENGTH = 500_000;
    /** Most members of one string set accepted. */
    static final int MAX_SET_SIZE = 5000;

    static final String T_INT = "int", T_LONG = "long", T_FLOAT = "float", T_BOOL = "bool", T_STRING = "string", T_SET = "set";

    /** Pref keys of the tile lists and the element ("slot") customisations, which name apps. */
    static final String KEY_TILES = "tilesJson", KEY_DOCK = "dockJson", KEY_SLOTS = "slotsJson";

    /** Device-bound, transient or credential keys that are never exported or overwritten (exact names). */
    private static final Set<String> EXCLUDED_KEYS = new HashSet<>(Arrays.asList(
            "ver",                                                    // layout change counter (bumped on apply)
            "welcomed", "bootSeen", "bootPreview", "calAsked",        // first-run and one-shot flags
            "wxCache", "wxCacheF", "wxTime", "wxLat", "wxLon", "wxName", // weather cache and the user's location
            "cloakUntil", "cloakPrevMode", "cloakPrevValue", "cloakSetValue", // a cloak in progress
            "goveeKey", "goveeDevices", "goveeLastIp",                // Govee cloud key and LAN lights
            "nestProject", "nestClientId", "nestSecret", "nestAccess", "nestAccessExp", "nestRefresh", "nestDevices",
            Onboarding.KEY_SEEN, Onboarding.KEY_REPLAY, LayoutSnapshots.KEY_VERSION_CODE));

    /** Key prefixes that are never exported or overwritten. */
    private static final String[] EXCLUDED_PREFIXES = {
            "goveeOn_", "govee", "nest", "permAsked_", "asked_", "phoneUri", "licens",
            "ha_",                        // Home Assistant URL, token and entity list (HomeAssistant)
            "appWidget",                  // hosted widget ids are allocated per device (WidgetSlots)
            "redAlertSince", "redAlertUntil", // a red alert in progress (RedAlert)
            "ent.", "trial.", "debug."};  // licensing state (kept in its own file; listed for safety)

    /** Lower-case fragments that mark a key as credential-like wherever they appear. */
    private static final String[] SECRET_FRAGMENTS = {
            "token", "secret", "password", "passwd", "apikey", "api_key", "credential", "cookie", "session", "oauth", "bearer", "auth"};

    private LayoutArchive() {}

    /** Thrown for a document that can't be imported; the message is shown to the user. */
    static final class InvalidLayoutException extends Exception {
        InvalidLayoutException(String message) { super(message); }
    }

    /** A parsed, validated and migrated document. */
    static final class Parsed {
        /** Preference values to apply, by key (sorted; excluded keys already removed). */
        final Map<String, Object> prefs;
        /** Schema the document was written with (before migration). */
        final int sourceSchema;
        /** When it was written (0 if unknown). */
        final long createdAt;
        /** versionName of the app that wrote it ("" if unknown). */
        final String appVersion;
        /** Entries dropped because they were credentials or device-bound. */
        final int filtered;

        Parsed(Map<String, Object> prefs, int sourceSchema, long createdAt, String appVersion, int filtered) {
            this.prefs = prefs;
            this.sourceSchema = sourceSchema;
            this.createdAt = createdAt;
            this.appVersion = appVersion;
            this.filtered = filtered;
        }
    }

    // ---- secret / device filtering ------------------------------------------------------------------

    /**
     * True when {@code key} must never leave the device or be overwritten from a file: credentials,
     * device-bound values and transient state (see the class comment). Unknown keys whose name looks
     * credential-like (contains "token", "secret", "password", "auth", … or ends in "key") are excluded too,
     * so a future integration's secrets are filtered without anyone remembering to list them.
     */
    static boolean isExcluded(String key) {
        if (key == null || key.isEmpty()) return true;
        if (EXCLUDED_KEYS.contains(key) || Prefs.retiredKey(key)) return true;
        for (String p : EXCLUDED_PREFIXES) if (key.startsWith(p)) return true;
        String lower = key.toLowerCase(Locale.ROOT);
        for (String f : SECRET_FRAGMENTS) if (lower.contains(f)) return true;
        return lower.endsWith("key");
    }

    /** A sorted copy of {@code all} without the excluded keys and without values of unsupported types. */
    static Map<String, Object> filter(Map<String, ?> all) {
        Map<String, Object> out = new TreeMap<>();
        for (Map.Entry<String, ?> e : all.entrySet()) {
            if (isExcluded(e.getKey()) || typeOf(e.getValue()) == null) continue;
            out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /** The document type name of a SharedPreferences value, or null if it has none. */
    static String typeOf(Object v) {
        if (v instanceof Integer) return T_INT;
        if (v instanceof Long) return T_LONG;
        if (v instanceof Float) return T_FLOAT;
        if (v instanceof Boolean) return T_BOOL;
        if (v instanceof String) return T_STRING;
        if (v instanceof Set) return T_SET;
        return null;
    }

    // ---- encoding --------------------------------------------------------------------------------

    /**
     * Builds the document for a SharedPreferences snapshot ({@code getAll()}). Excluded keys are left out.
     *
     * @param faction    the interface the layout uses (named in the document for the preview)
     * @param createdAt  wall-clock time of the export
     */
    static JSONObject encode(Map<String, ?> all, int versionCode, String versionName, int faction, long createdAt) {
        try {
            JSONObject doc = new JSONObject();
            doc.put("format", FORMAT);
            doc.put("schema", SCHEMA);
            doc.put("createdAt", createdAt);
            JSONObject app = new JSONObject();
            app.put("versionCode", versionCode);
            app.put("versionName", versionName == null ? "" : versionName);
            doc.put("app", app);
            doc.put("interface", interfaceName(faction));
            JSONObject prefs = new JSONObject();
            for (Map.Entry<String, Object> e : filter(all).entrySet()) prefs.put(e.getKey(), encodeValue(e.getValue()));
            doc.put("prefs", prefs);
            return doc;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One typed value: {@code {"t": type, "v": value}}. */
    private static JSONObject encodeValue(Object v) throws JSONException {
        JSONObject o = new JSONObject();
        String type = typeOf(v);
        o.put("t", type);
        if (T_SET.equals(type)) {
            JSONArray a = new JSONArray();
            for (Object s : new java.util.TreeSet<>(stringSet(v))) a.put(s);
            o.put("v", a);
        } else if (T_FLOAT.equals(type)) {
            o.put("v", (double) (Float) v);
        } else {
            o.put("v", v);
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> stringSet(Object v) { return (Set<String>) v; }

    /** Display name of an interface index (falls back to Command's for an unknown or retired one). */
    static String interfaceName(int faction) {
        return Skin.NAMES[Skin.exists(faction) ? faction : Skin.COMMAND];
    }

    // ---- decoding --------------------------------------------------------------------------------

    /** Parses, migrates and validates {@code source}; never touches storage. */
    static Parsed parse(String source) throws InvalidLayoutException {
        if (source == null || source.trim().isEmpty()) throw new InvalidLayoutException("THE FILE IS EMPTY");
        if (source.length() > MAX_DOCUMENT_CHARS) throw new InvalidLayoutException("THE FILE IS TOO LARGE FOR A LAYOUT");
        JSONObject raw;
        try {
            raw = new JSONObject(source);
        } catch (JSONException e) {
            throw new InvalidLayoutException("NOT A LAYOUT FILE (UNREADABLE JSON)");
        }
        int sourceSchema = schemaOf(raw);
        JSONObject doc = migrate(raw);
        JSONObject prefs = doc.optJSONObject("prefs");
        if (prefs == null) throw new InvalidLayoutException("NOT A LAYOUT FILE (NO SETTINGS)");
        if (prefs.length() > MAX_ENTRIES) throw new InvalidLayoutException("TOO MANY SETTINGS IN THE FILE");
        Map<String, Object> out = new TreeMap<>();
        int filtered = 0;
        Iterator<String> keys = prefs.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.length() > MAX_KEY_LENGTH) throw new InvalidLayoutException("BAD SETTING NAME IN THE FILE");
            if (isExcluded(key)) {
                filtered++;
                continue;
            }
            JSONObject typed = prefs.optJSONObject(key);
            if (typed == null) throw new InvalidLayoutException("BAD VALUE FOR " + key.toUpperCase(Locale.ROOT));
            out.put(key, decodeValue(key, typed.optString("t"), typed.opt("v")));
        }
        String version = "";
        JSONObject app = doc.optJSONObject("app");
        if (app != null) version = app.optString("versionName", "");
        return new Parsed(out, sourceSchema, doc.optLong("createdAt", 0L), version, filtered);
    }

    /** The schema a raw document declares: 0 for an unversioned flat dump, else its {@code schema}. */
    static int schemaOf(JSONObject raw) throws InvalidLayoutException {
        if (!raw.has("format") && !raw.has("schema")) return 0;
        if (!FORMAT.equals(raw.optString("format"))) throw new InvalidLayoutException("NOT A LAYOUT FILE");
        Object s = raw.opt("schema");
        if (!(s instanceof Number)) throw new InvalidLayoutException("LAYOUT FILE HAS NO SCHEMA VERSION");
        return ((Number) s).intValue();
    }

    /**
     * Upgrades a raw document to {@link #SCHEMA}, one step at a time. A document from a newer app
     * (higher schema) is refused rather than half-applied.
     *
     * <ul>
     *   <li>0 → 1: a flat object of plain values (a SharedPreferences dump) is wrapped in a schema 1
     *       envelope; types are inferred (whole numbers → int, or long beyond the int range; decimals →
     *       float; true/false → bool; text → string; arrays → set).</li>
     * </ul>
     */
    static JSONObject migrate(JSONObject raw) throws InvalidLayoutException {
        int schema = schemaOf(raw);
        if (schema > SCHEMA) throw new InvalidLayoutException("MADE BY A NEWER TREKUI • UPDATE THE APP FIRST");
        if (schema < 0) throw new InvalidLayoutException("UNKNOWN LAYOUT SCHEMA " + schema);
        JSONObject doc = raw;
        try {
            if (schema == 0) {
                doc = new JSONObject();
                doc.put("format", FORMAT);
                doc.put("schema", 1);
                JSONObject prefs = new JSONObject();
                Iterator<String> keys = raw.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    prefs.put(k, inferTyped(k, raw.get(k)));
                }
                doc.put("prefs", prefs);
                schema = 1;
            }
            // future steps: if (schema == 1) { …; schema = 2; }
        } catch (JSONException e) {
            throw new InvalidLayoutException("NOT A LAYOUT FILE");
        }
        return doc;
    }

    /** Schema 0 → 1: a plain value with its inferred type. */
    private static JSONObject inferTyped(String key, Object v) throws JSONException, InvalidLayoutException {
        JSONObject o = new JSONObject();
        String t;
        if (v instanceof Boolean) t = T_BOOL;
        else if (v instanceof String) t = T_STRING;
        else if (v instanceof JSONArray) t = T_SET;
        else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (d != Math.rint(d)) t = T_FLOAT;
            else if (d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) t = T_INT;
            else t = T_LONG;
        } else throw new InvalidLayoutException("BAD VALUE FOR " + key.toUpperCase(Locale.ROOT));
        o.put("t", t);
        o.put("v", v);
        return o;
    }

    /** Checks one typed value and converts it to the Java type SharedPreferences stores. */
    static Object decodeValue(String key, String type, Object v) throws InvalidLayoutException {
        String bad = "BAD VALUE FOR " + key.toUpperCase(Locale.ROOT);
        switch (type) {
            case T_INT: {
                long n = wholeNumber(v, bad);
                if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw new InvalidLayoutException(bad);
                return (int) n;
            }
            case T_LONG:
                return wholeNumber(v, bad);
            case T_FLOAT: {
                if (!(v instanceof Number)) throw new InvalidLayoutException(bad);
                double d = ((Number) v).doubleValue();
                if (Double.isNaN(d) || Double.isInfinite(d)) throw new InvalidLayoutException(bad);
                return (float) d;
            }
            case T_BOOL:
                if (!(v instanceof Boolean)) throw new InvalidLayoutException(bad);
                return v;
            case T_STRING:
                if (!(v instanceof String) || ((String) v).length() > MAX_STRING_LENGTH) throw new InvalidLayoutException(bad);
                return v;
            case T_SET: {
                if (!(v instanceof JSONArray)) throw new InvalidLayoutException(bad);
                JSONArray a = (JSONArray) v;
                if (a.length() > MAX_SET_SIZE) throw new InvalidLayoutException(bad);
                Set<String> set = new LinkedHashSet<>();
                for (int i = 0; i < a.length(); i++) {
                    Object s = a.opt(i);
                    if (!(s instanceof String) || ((String) s).length() > MAX_KEY_LENGTH * 4) throw new InvalidLayoutException(bad);
                    set.add((String) s);
                }
                return set;
            }
            default:
                throw new InvalidLayoutException(bad);
        }
    }

    private static long wholeNumber(Object v, String bad) throws InvalidLayoutException {
        if (!(v instanceof Number)) throw new InvalidLayoutException(bad);
        double d = ((Number) v).doubleValue();
        if (d != Math.rint(d) || Double.isInfinite(d)) throw new InvalidLayoutException(bad);
        return ((Number) v).longValue();
    }

    // ---- apps that aren't installed -----------------------------------------------------------------

    /**
     * Turns tiles, dock slots and element assignments that launch an app missing from this device into
     * empty slots (an element keeps its look and text, it just stops launching the app). Edits
     * {@code prefs} in place.
     *
     * @param installed answers whether a package name is installed
     * @return how many assignments were cleared
     */
    static int clearMissingApps(Map<String, Object> prefs, Predicate<String> installed) {
        int cleared = 0;
        for (String key : new String[]{KEY_TILES, KEY_DOCK}) {
            Object raw = prefs.get(key);
            if (!(raw instanceof String)) continue;
            try {
                JSONArray a = new JSONArray((String) raw);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject t = a.optJSONObject(i);
                    if (t != null && isMissing(t, installed)) {
                        t.remove("comp");
                        t.put("label", "");
                        t.put("user", -1);
                        cleared++;
                    }
                }
                prefs.put(key, a.toString());
            } catch (JSONException e) {
                prefs.remove(key); // unreadable list: the home screen falls back to its defaults
            }
        }
        Object raw = prefs.get(KEY_SLOTS);
        if (raw instanceof String) {
            try {
                JSONObject slots = new JSONObject((String) raw);
                Iterator<String> ids = slots.keys();
                while (ids.hasNext()) {
                    JSONObject t = slots.optJSONObject(ids.next());
                    if (t != null && isMissing(t, installed)) {
                        t.remove("comp");
                        t.put("user", -1);
                        cleared++;
                    }
                }
                prefs.put(KEY_SLOTS, slots.toString());
            } catch (JSONException e) {
                prefs.remove(KEY_SLOTS);
            }
        }
        return cleared;
    }

    /** True when a tile record names an app ({@code comp} "package/class") whose package isn't installed. */
    private static boolean isMissing(JSONObject tile, Predicate<String> installed) {
        String comp = tile.optString("comp", "");
        if (comp.isEmpty()) return false;
        int slash = comp.indexOf('/');
        String pkg = slash > 0 ? comp.substring(0, slash) : comp;
        return !installed.test(pkg);
    }

    /** Number of tiles and dock slots that hold an app or a function in {@code prefs}. */
    static int assignedTiles(Map<String, Object> prefs) {
        int n = 0;
        for (String key : new String[]{KEY_TILES, KEY_DOCK}) {
            Object raw = prefs.get(key);
            if (!(raw instanceof String)) continue;
            try {
                JSONArray a = new JSONArray((String) raw);
                for (int i = 0; i < a.length(); i++) {
                    JSONObject t = a.optJSONObject(i);
                    if (t != null && (t.has("comp") || t.has("action"))) n++;
                }
            } catch (JSONException ignored) {
                // counted as none
            }
        }
        return n;
    }

    /** The interface index stored in {@code prefs} (Command when absent or invalid). */
    static int factionOf(Map<String, Object> prefs) {
        Object f = prefs.get("faction");
        return f instanceof Integer ? Prefs.clampFaction((Integer) f) : Skin.COMMAND;
    }

    /** The non-excluded keys in {@code current} that {@code target} lacks: removed when {@code target} is applied (sorted). */
    static List<String> keysToRemove(Map<String, ?> current, Map<String, Object> target) {
        List<String> out = new java.util.ArrayList<>();
        for (String k : current.keySet()) if (!isExcluded(k) && !target.containsKey(k)) out.add(k);
        java.util.Collections.sort(out);
        return out;
    }

    // ---- device side ------------------------------------------------------------------------------

    /** The current layout as a document. Reads preferences (memory only); safe on the main thread. */
    static JSONObject capture(Context c) {
        Prefs p = Prefs.get(c);
        return encode(p.sp.getAll(), BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, p.faction(), System.currentTimeMillis());
    }

    /**
     * Replaces the layout with {@code values}: every non-excluded entry not in {@code values} is
     * removed, the rest written, then the home screen is told to rebuild. Credentials and device-bound
     * entries are left exactly as they were.
     */
    static void apply(Context c, Map<String, Object> values) {
        Prefs p = Prefs.get(c);
        SharedPreferences.Editor ed = p.sp.edit();
        for (String k : keysToRemove(p.sp.getAll(), values)) ed.remove(k);
        for (Map.Entry<String, Object> e : values.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (isExcluded(k)) continue;
            if (v instanceof Integer) ed.putInt(k, (Integer) v);
            else if (v instanceof Long) ed.putLong(k, (Long) v);
            else if (v instanceof Float) ed.putFloat(k, (Float) v);
            else if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
            else if (v instanceof String) ed.putString(k, (String) v);
            else if (v instanceof Set) ed.putStringSet(k, new HashSet<>(stringSet(v)));
        }
        ed.commit();
        p.bump();
    }

    /** True when {@code pkg} is installed and visible to the launcher. */
    static boolean installed(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Reads a picked document as UTF-8, refusing anything over {@link #MAX_DOCUMENT_CHARS}. Worker thread. */
    static String read(Context c, Uri uri) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open " + uri);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_DOCUMENT_CHARS * 2) throw new IOException("Too large");
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** Writes {@code text} to a created document (truncating it). Worker thread. */
    static void write(Context c, Uri uri, String text) throws IOException {
        try (OutputStream out = c.getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Cannot open " + uri);
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }
}
