package com.gungreeneyes.trekui

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.TreeMap
import java.util.TreeSet

/**
 * The home layout and appearance as a versioned JSON document: the format behind LAYOUT EXPORT /
 * IMPORT in CONFIG › BACKUP and behind the automatic [LayoutSnapshots].
 *
 * The document holds every entry of the `"trekui"` preferences file ([TrekPrefs.FILE]) except
 * the ones [isExcluded] rejects: credentials (Govee key, Nest OAuth client, secret and tokens,
 * and anything whose name looks like a key, token, secret or password), device-bound values (Govee
 * light addresses, Nest device lists, ringtone content URIs, the weather location) and transient
 * bookkeeping (layout version counter, weather cache, cloak timers, permission prompts, first-run
 * flags). Excluded entries are never written to a file and never touched when one is applied, so a
 * restore can't log anyone out of their smart devices.
 *
 * ```
 * { "format": "trekui-layout", "schema": 1, "createdAt": 1700000000000,
 *   "app": { "versionCode": 62, "versionName": "6.2.0" }, "interface": "COMMAND",
 *   "prefs": { "faction": {"t": "int", "v": 0}, "tilesJson": {"t": "string", "v": "[…]"}, … } }
 * ```
 *
 * Value types mirror SharedPreferences: `int`, `long`, `float`, `bool`,
 * `string` and `set` (a string set). Schema 0 is an unversioned flat object of plain
 * values (a raw SharedPreferences dump); [migrate] upgrades it to the current schema by
 * inferring the types. Everything in this object except the few Context functions at the end is pure
 * and unit-tested on the JVM. The format is unchanged from the Java edition, so its exports import here.
 */
internal object LayoutArchive {
    /** Value of the `format` field identifying a layout document. */
    const val FORMAT = "trekui-layout"

    /** Current document schema. Bump it and add a step to [migrate] when the layout changes shape. */
    const val SCHEMA = 1

    /** MIME type used for SAF export and import. */
    const val MIME = "application/json"

    /** Largest document accepted on import, in characters. */
    const val MAX_DOCUMENT_CHARS = 2_000_000

    /** Most preference entries accepted. */
    const val MAX_ENTRIES = 2000

    /** Longest preference key accepted. */
    const val MAX_KEY_LENGTH = 120

    /** Longest string value accepted (tile and slot lists are JSON strings, so this is generous). */
    const val MAX_STRING_LENGTH = 500_000

    /** Most members of one string set accepted. */
    const val MAX_SET_SIZE = 5000

    const val T_INT = "int"
    const val T_LONG = "long"
    const val T_FLOAT = "float"
    const val T_BOOL = "bool"
    const val T_STRING = "string"
    const val T_SET = "set"

    /** Pref keys of the tile lists and the element ("slot") customisations, which name apps. */
    const val KEY_TILES = "tilesJson"
    const val KEY_DOCK = "dockJson"
    const val KEY_SLOTS = "slotsJson"

    /** Device-bound, transient or credential keys that are never exported or overwritten (exact names). */
    private val EXCLUDED_KEYS: Set<String> = hashSetOf(
        "ver", // layout change counter (bumped on apply)
        "welcomed", "bootSeen", "bootPreview", "calAsked", // first-run and one-shot flags
        "wxCache", "wxCacheF", "wxTime", "wxLat", "wxLon", "wxName", // weather cache and the user's location
        "cloakUntil", "cloakPrevMode", "cloakPrevValue", "cloakSetValue", // a cloak in progress
        "goveeKey", "goveeDevices", "goveeLastIp", // Govee cloud key and LAN lights
        "nestProject", "nestClientId", "nestSecret", "nestAccess", "nestAccessExp", "nestRefresh", "nestDevices",
        Onboarding.KEY_SEEN, Onboarding.KEY_REPLAY, LayoutSnapshots.KEY_VERSION_CODE,
    )

    /** Key prefixes that are never exported or overwritten. */
    private val EXCLUDED_PREFIXES = arrayOf(
        "goveeOn_", "govee", "nest", "permAsked_", "asked_", "phoneUri", "licens",
        "ha_", // Home Assistant URL, token and entity list (HomeAssistant)
        "appWidget", // hosted widget ids are allocated per device (WidgetSlots)
        "redAlertSince", "redAlertUntil", // a red alert in progress (RedAlert)
        "ent.", "trial.", "debug.", // licensing state (kept in its own file; listed for safety)
    )

    /** Lower-case fragments that mark a key as credential-like wherever they appear. */
    private val SECRET_FRAGMENTS = arrayOf(
        "token", "secret", "password", "passwd", "apikey", "api_key", "credential", "cookie", "session", "oauth", "bearer", "auth",
    )

    /** Thrown for a document that can't be imported; the message is shown to the user. */
    class InvalidLayoutException(message: String) : Exception(message)

    /** A parsed, validated and migrated document. */
    class Parsed(
        /** Preference values to apply, by key (sorted; excluded keys already removed). */
        @JvmField val prefs: MutableMap<String, Any>,
        /** Schema the document was written with (before migration). */
        @JvmField val sourceSchema: Int,
        /** When it was written (0 if unknown). */
        @JvmField val createdAt: Long,
        /** versionName of the app that wrote it ("" if unknown). */
        @JvmField val appVersion: String,
        /** Entries dropped because they were credentials or device-bound. */
        @JvmField val filtered: Int,
    )

    // ---- secret / device filtering ------------------------------------------------------------------

    /**
     * True when `key` must never leave the device or be overwritten from a file: credentials,
     * device-bound values and transient state (see the object comment). Unknown keys whose name looks
     * credential-like (contains "token", "secret", "password", "auth", … or ends in "key") are excluded too,
     * so a future integration's secrets are filtered without anyone remembering to list them.
     */
    @JvmStatic
    fun isExcluded(key: String?): Boolean {
        if (key.isNullOrEmpty()) return true
        if (key in EXCLUDED_KEYS || Prefs.retiredKey(key)) return true
        if (EXCLUDED_PREFIXES.any { key.startsWith(it) }) return true
        val lower = key.lowercase(Locale.ROOT)
        if (SECRET_FRAGMENTS.any { lower.contains(it) }) return true
        return lower.endsWith("key")
    }

    /** A sorted copy of `all` without the excluded keys and without values of unsupported types. */
    @JvmStatic
    fun filter(all: Map<String, *>): MutableMap<String, Any> {
        val out = TreeMap<String, Any>()
        for ((k, v) in all) {
            if (isExcluded(k) || v == null || typeOf(v) == null) continue
            out[k] = v
        }
        return out
    }

    /** The document type name of a SharedPreferences value, or null if it has none. */
    @JvmStatic
    fun typeOf(v: Any?): String? = when (v) {
        is Int -> T_INT
        is Long -> T_LONG
        is Float -> T_FLOAT
        is Boolean -> T_BOOL
        is String -> T_STRING
        is Set<*> -> T_SET
        else -> null
    }

    // ---- encoding --------------------------------------------------------------------------------

    /**
     * Builds the document for a SharedPreferences snapshot (`getAll()`). Excluded keys are left out.
     *
     * @param faction    the interface the layout uses (named in the document for the preview)
     * @param createdAt  wall-clock time of the export
     */
    @JvmStatic
    fun encode(all: Map<String, *>, versionCode: Int, versionName: String?, faction: Int, createdAt: Long): JSONObject {
        try {
            val doc = JSONObject()
            doc.put("format", FORMAT)
            doc.put("schema", SCHEMA)
            doc.put("createdAt", createdAt)
            val app = JSONObject()
            app.put("versionCode", versionCode)
            app.put("versionName", versionName ?: "")
            doc.put("app", app)
            doc.put("interface", interfaceName(faction))
            val prefs = JSONObject()
            for ((k, v) in filter(all)) prefs.put(k, encodeValue(v))
            doc.put("prefs", prefs)
            return doc
        } catch (e: JSONException) {
            throw IllegalStateException(e)
        }
    }

    /** One typed value: `{"t": type, "v": value}`. */
    private fun encodeValue(v: Any): JSONObject {
        val o = JSONObject()
        val type = typeOf(v)
        o.put("t", type)
        when (type) {
            T_SET -> {
                val a = JSONArray()
                for (s in TreeSet(stringSet(v))) a.put(s)
                o.put("v", a)
            }
            T_FLOAT -> o.put("v", (v as Float).toDouble())
            else -> o.put("v", v)
        }
        return o
    }

    @Suppress("UNCHECKED_CAST")
    private fun stringSet(v: Any): Set<String> = v as Set<String>

    /** Display name of an interface index (falls back to Command's for an unknown or retired one). */
    @JvmStatic
    fun interfaceName(faction: Int): String? = Skin.NAMES[if (Skin.exists(faction)) faction else Skin.COMMAND]

    // ---- decoding --------------------------------------------------------------------------------

    /** Parses, migrates and validates `source`; never touches storage. */
    @JvmStatic
    @Throws(InvalidLayoutException::class)
    fun parse(source: String?): Parsed {
        if (source == null || source.javaTrim().isEmpty()) throw InvalidLayoutException("THE FILE IS EMPTY")
        if (source.length > MAX_DOCUMENT_CHARS) throw InvalidLayoutException("THE FILE IS TOO LARGE FOR A LAYOUT")
        val raw = try {
            JSONObject(source)
        } catch (e: JSONException) {
            throw InvalidLayoutException("NOT A LAYOUT FILE (UNREADABLE JSON)")
        }
        val sourceSchema = schemaOf(raw)
        val doc = migrate(raw)
        val prefs = doc.optJSONObject("prefs") ?: throw InvalidLayoutException("NOT A LAYOUT FILE (NO SETTINGS)")
        if (prefs.length() > MAX_ENTRIES) throw InvalidLayoutException("TOO MANY SETTINGS IN THE FILE")
        val out = TreeMap<String, Any>()
        var filtered = 0
        for (key in prefs.keys()) {
            if (key.length > MAX_KEY_LENGTH) throw InvalidLayoutException("BAD SETTING NAME IN THE FILE")
            if (isExcluded(key)) {
                filtered++
                continue
            }
            val typed = prefs.optJSONObject(key) ?: throw InvalidLayoutException("BAD VALUE FOR " + key.uppercase(Locale.ROOT))
            out[key] = decodeValue(key, typed.optString("t"), typed.opt("v"))
        }
        val version = doc.optJSONObject("app")?.optString("versionName", "") ?: ""
        return Parsed(out, sourceSchema, doc.optLong("createdAt", 0L), version, filtered)
    }

    /** The schema a raw document declares: 0 for an unversioned flat dump, else its `schema`. */
    @JvmStatic
    @Throws(InvalidLayoutException::class)
    fun schemaOf(raw: JSONObject): Int {
        if (!raw.has("format") && !raw.has("schema")) return 0
        if (FORMAT != raw.optString("format")) throw InvalidLayoutException("NOT A LAYOUT FILE")
        val s = raw.opt("schema") as? Number ?: throw InvalidLayoutException("LAYOUT FILE HAS NO SCHEMA VERSION")
        return s.toInt()
    }

    /**
     * Upgrades a raw document to [SCHEMA], one step at a time. A document from a newer app
     * (higher schema) is refused rather than half-applied.
     *
     * - 0 → 1: a flat object of plain values (a SharedPreferences dump) is wrapped in a schema 1
     *   envelope; types are inferred (whole numbers → int, or long beyond the int range; decimals →
     *   float; true/false → bool; text → string; arrays → set).
     */
    @JvmStatic
    @Throws(InvalidLayoutException::class)
    fun migrate(raw: JSONObject): JSONObject {
        var schema = schemaOf(raw)
        if (schema > SCHEMA) throw InvalidLayoutException("MADE BY A NEWER TREKUI • UPDATE THE APP FIRST")
        if (schema < 0) throw InvalidLayoutException("UNKNOWN LAYOUT SCHEMA $schema")
        var doc = raw
        try {
            if (schema == 0) {
                doc = JSONObject()
                doc.put("format", FORMAT)
                doc.put("schema", 1)
                val prefs = JSONObject()
                for (k in raw.keys()) prefs.put(k, inferTyped(k, raw.get(k)))
                doc.put("prefs", prefs)
                schema = 1
            }
            // future steps: if (schema == 1) { …; schema = 2 }
        } catch (e: JSONException) {
            throw InvalidLayoutException("NOT A LAYOUT FILE")
        }
        return doc
    }

    /** Schema 0 → 1: a plain value with its inferred type. */
    private fun inferTyped(key: String, v: Any?): JSONObject {
        val t = when (v) {
            is Boolean -> T_BOOL
            is String -> T_STRING
            is JSONArray -> T_SET
            is Number -> {
                val d = v.toDouble()
                when {
                    d != Math.rint(d) -> T_FLOAT
                    d >= Int.MIN_VALUE && d <= Int.MAX_VALUE -> T_INT
                    else -> T_LONG
                }
            }
            else -> throw InvalidLayoutException("BAD VALUE FOR " + key.uppercase(Locale.ROOT))
        }
        val o = JSONObject()
        o.put("t", t)
        o.put("v", v)
        return o
    }

    /** Checks one typed value and converts it to the type SharedPreferences stores. */
    @JvmStatic
    @Throws(InvalidLayoutException::class)
    fun decodeValue(key: String, type: String?, v: Any?): Any {
        val bad = "BAD VALUE FOR " + key.uppercase(Locale.ROOT)
        when (type) {
            T_INT -> {
                val n = wholeNumber(v, bad)
                if (n < Int.MIN_VALUE || n > Int.MAX_VALUE) throw InvalidLayoutException(bad)
                return n.toInt()
            }
            T_LONG -> return wholeNumber(v, bad)
            T_FLOAT -> {
                if (v !is Number) throw InvalidLayoutException(bad)
                val d = v.toDouble()
                if (d.isNaN() || d.isInfinite()) throw InvalidLayoutException(bad)
                return d.toFloat()
            }
            T_BOOL -> {
                if (v !is Boolean) throw InvalidLayoutException(bad)
                return v
            }
            T_STRING -> {
                if (v !is String || v.length > MAX_STRING_LENGTH) throw InvalidLayoutException(bad)
                return v
            }
            T_SET -> {
                if (v !is JSONArray || v.length() > MAX_SET_SIZE) throw InvalidLayoutException(bad)
                val set = LinkedHashSet<String>()
                for (i in 0 until v.length()) {
                    val s = v.opt(i)
                    if (s !is String || s.length > MAX_KEY_LENGTH * 4) throw InvalidLayoutException(bad)
                    set.add(s)
                }
                return set
            }
            else -> throw InvalidLayoutException(bad)
        }
    }

    private fun wholeNumber(v: Any?, bad: String): Long {
        if (v !is Number) throw InvalidLayoutException(bad)
        val d = v.toDouble()
        if (d != Math.rint(d) || d.isInfinite()) throw InvalidLayoutException(bad)
        return v.toLong()
    }

    // ---- apps that aren't installed -----------------------------------------------------------------

    /**
     * Turns tiles, dock slots and element assignments that launch an app missing from this device into
     * empty slots (an element keeps its look and text, it just stops launching the app). Edits
     * `prefs` in place.
     *
     * @param installed answers whether a package name is installed
     * @return how many assignments were cleared
     */
    @JvmStatic
    fun clearMissingApps(prefs: MutableMap<String, Any>, installed: (String) -> Boolean): Int {
        var cleared = 0
        for (key in arrayOf(KEY_TILES, KEY_DOCK)) {
            val raw = prefs[key] as? String ?: continue
            try {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val t = a.optJSONObject(i)
                    if (t != null && isMissing(t, installed)) {
                        t.remove("comp")
                        t.put("label", "")
                        t.put("user", -1)
                        cleared++
                    }
                }
                prefs[key] = a.toString()
            } catch (e: JSONException) {
                prefs.remove(key) // unreadable list: the home screen falls back to its defaults
            }
        }
        val raw = prefs[KEY_SLOTS]
        if (raw is String) {
            try {
                val slots = JSONObject(raw)
                for (id in slots.keys()) {
                    val t = slots.optJSONObject(id)
                    if (t != null && isMissing(t, installed)) {
                        t.remove("comp")
                        t.put("user", -1)
                        cleared++
                    }
                }
                prefs[KEY_SLOTS] = slots.toString()
            } catch (e: JSONException) {
                prefs.remove(KEY_SLOTS)
            }
        }
        return cleared
    }

    /** True when a tile record names an app (`comp` "package/class") whose package isn't installed. */
    private fun isMissing(tile: JSONObject, installed: (String) -> Boolean): Boolean {
        val comp = tile.optString("comp", "")
        if (comp.isEmpty()) return false
        val slash = comp.indexOf('/')
        val pkg = if (slash > 0) comp.substring(0, slash) else comp
        return !installed(pkg)
    }

    /** Number of tiles and dock slots that hold an app or a function in `prefs`. */
    @JvmStatic
    fun assignedTiles(prefs: Map<String, Any>): Int {
        var n = 0
        for (key in arrayOf(KEY_TILES, KEY_DOCK)) {
            val raw = prefs[key] as? String ?: continue
            try {
                val a = JSONArray(raw)
                for (i in 0 until a.length()) {
                    val t = a.optJSONObject(i)
                    if (t != null && (t.has("comp") || t.has("action"))) n++
                }
            } catch (ignored: JSONException) {
                // counted as none
            }
        }
        return n
    }

    /** The interface index stored in `prefs` (Command when absent or invalid). */
    @JvmStatic
    fun factionOf(prefs: Map<String, Any>): Int {
        val f = prefs["faction"]
        return if (f is Int) TrekPrefs.clampFaction(f) else Skin.COMMAND
    }

    /** The non-excluded keys in `current` that `target` lacks: removed when `target` is applied (sorted). */
    @JvmStatic
    fun keysToRemove(current: Map<String, *>, target: Map<String, Any>): MutableList<String> =
        current.keys.filterTo(ArrayList()) { !isExcluded(it) && !target.containsKey(it) }.apply { sort() }

    // ---- device side ------------------------------------------------------------------------------

    /** The current layout as a document. Reads preferences (memory only); safe on the main thread. */
    @JvmStatic
    fun capture(c: Context): JSONObject {
        val p = Prefs.get(c)
        return encode(p.sp.all, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, p.faction(), System.currentTimeMillis())
    }

    /**
     * Replaces the layout with `values`: every non-excluded entry not in `values` is
     * removed, the rest written, then the home screen is told to rebuild. Credentials and device-bound
     * entries are left exactly as they were.
     */
    @JvmStatic
    fun apply(c: Context, values: Map<String, Any>) {
        val p = Prefs.get(c)
        val ed = p.sp.edit()
        for (k in keysToRemove(p.sp.all, values)) ed.remove(k)
        for ((k, v) in values) {
            if (isExcluded(k)) continue
            when (v) {
                is Int -> ed.putInt(k, v)
                is Long -> ed.putLong(k, v)
                is Float -> ed.putFloat(k, v)
                is Boolean -> ed.putBoolean(k, v)
                is String -> ed.putString(k, v)
                is Set<*> -> ed.putStringSet(k, HashSet(stringSet(v)))
            }
        }
        ed.commit()
        p.bump()
    }

    /** True when `pkg` is installed and visible to the launcher. */
    @JvmStatic
    fun installed(c: Context, pkg: String): Boolean = try {
        c.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** Reads a picked document as UTF-8, refusing anything over [MAX_DOCUMENT_CHARS]. Worker thread. */
    @JvmStatic
    @Throws(IOException::class)
    fun read(c: Context, uri: Uri): String {
        val input = c.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
        input.use { stream ->
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = stream.read(chunk)
                if (n <= 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_DOCUMENT_CHARS * 2) throw IOException("Too large")
            }
            return String(buf.toByteArray(), Charsets.UTF_8)
        }
    }

    /** Writes `text` to a created document (truncating it). Worker thread. */
    @JvmStatic
    @Throws(IOException::class)
    fun write(c: Context, uri: Uri, text: String) {
        val out = c.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open $uri")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }
}
