package com.gungreeneyes.trekui

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

/**
 * The sound manifest shipped in `assets/sounds/manifest.json`: for every sound id the app
 * knows (the same names the personal build has in res/raw, e.g. `ui_panel_open`,
 * `tap_rot_1`, `tk_wpn_phaser_1`) it says where the original recording is publicly
 * downloadable, its SHA-256, and how the app cuts it (start/end seconds, gain, mono fold, fades).
 *
 * The app ships no recordings. [SoundDownloader] uses this catalog to fetch the originals on
 * the user's request, verify them and render them into [SoundCache]. Sounds without a
 * confident public source are listed with `available = false`; the user can still import their
 * own file for them.
 *
 * Parsing is plain `org.json` so it can be unit-tested under Robolectric.
 */
class SoundCatalog private constructor(
    @JvmField val format: Int,
    @JvmField val notice: String,
    sounds: List<Sound>,
) {
    /** One downloadable original (a file on a public web page). */
    class Source(
        @JvmField val url: String,
        @JvmField val page: String,
        sha256: String?,
        @JvmField val site: String,
        @JvmField val bytes: Long,
    ) {
        @JvmField
        val sha256: String = sha256?.lowercase(Locale.US) ?: ""
    }

    /** One app sound and the recipe that turns its original into the clip the app plays. */
    class Sound(
        @JvmField val id: String,
        @JvmField val label: String,
        @JvmField val group: String,
        @JvmField val events: List<String>,
        @JvmField val available: Boolean,
        @JvmField val core: Boolean,
        @JvmField val source: Source?,
        @JvmField val alternates: List<Source>,
        /** Cut from the original, in seconds; `end <= 0` means "to the end of the file". */
        @JvmField val start: Double,
        @JvmField val end: Double,
        @JvmField val gainDb: Double,
        @JvmField val mono: Boolean,
        @JvmField val fadeInMs: Int,
        @JvmField val fadeOutMs: Int,
        @JvmField val confidence: Double,
    ) {
        /** Length of the rendered clip in seconds, or 0 when unknown (cut to end of file). */
        fun seconds(): Double = if (end > start) end - start else 0.0

        /** Every place the original can be fetched from, preferred first. */
        fun allSources(): List<Source> {
            val out = ArrayList<Source>()
            if (source != null) out.add(source)
            out.addAll(alternates)
            return out
        }
    }

    @JvmField
    val sounds: List<Sound> = Collections.unmodifiableList(sounds)
    private val byId = LinkedHashMap<String, Sound>().apply { for (s in sounds) put(s.id, s) }

    fun get(id: String?): Sound? = if (id == null) null else byId[id]

    fun size(): Int = sounds.size

    /** Sounds that have a verified public source. */
    fun available(coreOnly: Boolean): List<Sound> =
        sounds.filterTo(ArrayList()) { it.available && it.source != null && (!coreOnly || it.core) }

    companion object {
        /** Asset path of the manifest inside the APK. */
        const val ASSET = "sounds/manifest.json"
        /** Highest manifest format this code understands. */
        internal const val FORMAT = 1

        private val VALID_ID = Regex("[a-z0-9_]{1,80}")
        private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")

        /** An empty catalog (no manifest in this build): nothing to download, nothing breaks. */
        @JvmStatic
        fun empty(): SoundCatalog = SoundCatalog(FORMAT, "", ArrayList())

        /**
         * Groups the given sounds by the original they are cut from (keyed by SHA-256), so every
         * original is downloaded once even when several sounds come from it (tap_rot_1 and tap_rot_2
         * are both cut from one file).
         */
        @JvmStatic
        fun bySource(list: List<Sound>): Map<String, MutableList<Sound>> {
            val out = LinkedHashMap<String, MutableList<Sound>>()
            for (s in list) {
                val src = s.source ?: continue
                out.getOrPut(src.sha256) { ArrayList() }.add(s)
            }
            return out
        }

        /**
         * Rough size of the rendered cache for these sounds: 16-bit PCM at 44.1 kHz, mono or stereo.
         * Used for the "about N MB" hint before downloading.
         */
        @JvmStatic
        fun estimatedCacheBytes(list: List<Sound>): Long {
            var total = 0.0
            for (s in list) {
                val sec = if (s.seconds() > 0) s.seconds() else 3.0
                total += sec * 44100 * 2 * (if (s.mono) 1 else 2) + 44
            }
            return total.toLong()
        }

        /** Sum of the original downloads' sizes (each original counted once). */
        @JvmStatic
        fun downloadBytes(list: List<Sound>): Long {
            var total = 0L
            for (g in bySource(list).values) total += Math.max(0L, g[0].source!!.bytes)
            return total
        }

        // ---- parsing --------------------------------------------------------------------------

        /** Parses manifest JSON. Unknown fields are ignored; malformed entries are skipped. */
        @JvmStatic
        @Throws(JSONException::class)
        fun parse(json: String): SoundCatalog {
            val root = JSONObject(json)
            val format = root.optInt("format", 1)
            if (format > FORMAT) throw JSONException("manifest format $format is newer than this app understands")
            val arr = root.optJSONArray("sounds")
            val out = ArrayList<Sound>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    parseSound(o)?.let { out.add(it) }
                }
            }
            return SoundCatalog(format, root.optString("notice", ""), out)
        }

        private fun parseSound(o: JSONObject): Sound? {
            val id = o.optString("id", "")
            if (!isValidId(id)) return null
            val events = ArrayList<String>()
            val ev = o.optJSONArray("events")
            if (ev != null) for (i in 0 until ev.length()) events.add(ev.optString(i))
            val src = parseSource(o.optJSONObject("source"))
            val alts = ArrayList<Source>()
            val a = o.optJSONArray("alternates")
            if (a != null) {
                for (i in 0 until a.length()) parseSource(a.optJSONObject(i))?.let { alts.add(it) }
            }
            val available = o.optBoolean("available", src != null) && src != null
            val start = Math.max(0.0, o.optDouble("start", 0.0))
            var end = o.optDouble("end", 0.0)
            if (end.isNaN()) end = 0.0
            var gain = o.optDouble("gain_db", 0.0)
            if (gain.isNaN() || gain.isInfinite()) gain = 0.0
            gain = Math.max(-40.0, Math.min(24.0, gain))
            return Sound(
                id, o.optString("label", id.uppercase(Locale.US)), o.optString("group", ""), events, available,
                o.optBoolean("core", false), src, alts, start, end, gain, o.optBoolean("mono", true),
                clampInt(o.optInt("fade_in_ms", 4), 0, 5000), clampInt(o.optInt("fade_out_ms", 4), 0, 5000),
                o.optDouble("confidence", 0.0),
            )
        }

        private fun parseSource(o: JSONObject?): Source? {
            if (o == null) return null
            val url = o.optString("url", "")
            val sha = o.optString("sha256", "")
            if (!url.startsWith("https://") || !SHA256_HEX.matches(sha)) return null
            return Source(url, o.optString("page", ""), sha, o.optString("site", ""), o.optLong("bytes", -1))
        }

        /** A sound id doubles as a file name, so only lower-case letters, digits and '_' are allowed. */
        @JvmStatic
        fun isValidId(id: String?): Boolean = id != null && VALID_ID.matches(id)

        private fun clampInt(v: Int, lo: Int, hi: Int): Int = Math.max(lo, Math.min(hi, v))

        // ---- loading --------------------------------------------------------------------------

        private var cached: SoundCatalog? = null

        /** The bundled manifest (cached), or an empty catalog if the asset is missing or invalid. */
        @JvmStatic
        @Synchronized
        fun load(c: Context): SoundCatalog {
            cached?.let { return it }
            val loaded = try {
                c.applicationContext.assets.open(ASSET).use { parse(readAll(it)) }
            } catch (e: IOException) {
                empty()
            } catch (e: JSONException) {
                empty()
            } catch (e: RuntimeException) {
                empty()
            }
            cached = loaded
            return loaded
        }

        @JvmStatic
        @Throws(IOException::class)
        fun readAll(input: InputStream): String {
            val bo = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                bo.write(buf, 0, n)
            }
            return String(bo.toByteArray(), Charsets.UTF_8)
        }
    }
}
