package com.gungreeneyes.trekui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Every sound the launcher can play: the library clips (downloaded on request through
 * [SoundDownloader] into [SoundCache], or bundled in res/raw on personal develop builds)
 * plus any the user imports (files or a ZIP). Imported clips are decoded, trimmed and level-matched
 * once and stored as WAV in the app's private storage; they can be assigned to any console element, to
 * an interface event, or exported as a ringtone / notification / alarm.
 *
 * Clip IDs: a library name ("comms_hail", resolved cache → res/raw → silence) or "lib:" + slug
 * for imported clips. With nothing downloaded or imported the library is simply empty.
 */
object SoundLibrary {
    const val PREFIX = "lib:"
    private const val MAX_SECONDS = 40

    private val ZIP_PREFIX = Regex("^\\d+_")
    private val ARCHIVE_SUFFIX = Regex("(?i)[_\\- ]?(clean|ep)$")
    private val SEPARATORS = Regex("[_\\-.]+")
    private val LETTER_DIGIT = Regex("([A-Za-z])(\\d)")
    private val SPACES = Regex("\\s+")
    private val NON_SLUG = Regex("[^a-z0-9]+")
    private val EDGE_UNDERSCORES = Regex("^_+|_+$")

    class Entry(
        @JvmField val id: String,
        @JvmField val label: String,
        @JvmField val imported: Boolean,
    )

    /** Folder of imported clips. */
    @JvmStatic
    fun dir(c: Context): File = File(c.filesDir, "trekui_library")

    /** WAV file of an imported ("lib:") clip. */
    @JvmStatic
    fun file(c: Context, id: String): File = File(dir(c), slugOf(id) + ".wav")

    private fun slugOf(id: String): String = if (id.startsWith(PREFIX)) id.substring(PREFIX.length) else id

    private fun index(c: Context): JSONArray =
        try {
            JSONArray(TrekPrefs.get(c).s("soundLib", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }

    private fun saveIndex(c: Context, a: JSONArray) {
        TrekPrefs.get(c).put("soundLib", a.toString())
    }

    /** Library clips that can play (downloaded or bundled, in library order), then imported ones (newest last). */
    @JvmStatic
    fun all(c: Context): List<Entry> {
        val out = ArrayList<Entry>()
        for (i in 1 until Sfx.LIBRARY.size) {
            if (Sfx.available(c, Sfx.LIBRARY[i])) out.add(Entry(Sfx.LIBRARY[i], Sfx.LIBRARY_LABELS[i], false))
        }
        out.addAll(imported(c))
        return out
    }

    @JvmStatic
    fun imported(c: Context): List<Entry> {
        val out = ArrayList<Entry>()
        val a = index(c)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = PREFIX + o.optString("slug")
            if (file(c, id).isFile) out.add(Entry(id, o.optString("label"), true))
        }
        return out
    }

    /** True if the clip can play: an imported file that exists, or an available library clip. */
    @JvmStatic
    fun exists(c: Context, id: String?): Boolean {
        if (id == null || id.isEmpty() || "none" == id) return false
        if (Sfx.TAP_SET == id) return Sfx.available(c, Sfx.TAP_SET_CLIPS[0])
        return if (id.startsWith(PREFIX)) file(c, id).isFile else Sfx.available(c, id)
    }

    @JvmStatic
    fun label(c: Context, id: String?): String {
        if (id == null) return "DEFAULT"
        if ("none" == id) return "SILENT"
        if (id.startsWith(PREFIX)) {
            val a = index(c)
            val slug = slugOf(id)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i)
                if (o != null && slug == o.optString("slug")) return o.optString("label")
            }
            return "MISSING SOUND"
        }
        return Sfx.libraryLabel(id)
    }

    /** "hailingfrequencies_open3.mp3" → "HAILINGFREQUENCIES OPEN 3". Pure JVM (unit-tested). */
    @JvmStatic
    fun prettyName(fileName: String): String {
        var s = fileName
        val dot = s.lastIndexOf('.')
        if (dot > 0) s = s.substring(0, dot)
        s = s.replace(ZIP_PREFIX, "") // "3_" prefixes from ZIP extraction
        s = s.replace(ARCHIVE_SUFFIX, "") // archive suffixes
        s = s.replace(SEPARATORS, " ")
        s = s.replace(LETTER_DIGIT, "$1 $2")
        s = s.replace(SPACES, " ").trim { it <= ' ' } // java.lang.String.trim semantics
        return if (s.isEmpty()) "IMPORTED SOUND" else s.uppercase(Locale.US)
    }

    @JvmStatic
    fun slugFor(label: String, existing: JSONArray): String {
        var base = label.lowercase(Locale.US).replace(NON_SLUG, "_").replace(EDGE_UNDERSCORES, "")
        if (base.isEmpty()) base = "sound"
        if (base.length > 40) base = base.substring(0, 40)
        var slug = base
        var n = 2
        while (contains(existing, slug)) slug = base + "_" + n++
        return slug
    }

    private fun contains(a: JSONArray, slug: String): Boolean {
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i)
            if (o != null && slug == o.optString("slug")) return true
        }
        return false
    }

    /** Imports audio files into the library. Runs on a background thread. */
    @JvmStatic
    fun importItems(c: Context, items: List<SoundImporter.Item>): SoundImporter.Report {
        val r = SoundImporter.Report()
        val d = dir(c)
        if (!d.isDirectory && !d.mkdirs()) {
            r.failed.add("cannot create the sound library folder")
            return r
        }
        val idx = index(c)
        for (item in items) {
            try {
                val sr = IntArray(1)
                val x = SoundImporter.decode(c, item, sr)
                val y = SoundImporter.process(x, sr[0], MAX_SECONDS)
                val label = prettyName(item.name)
                val slug = slugFor(label, idx)
                SoundImporter.writeWav(File(d, "$slug.wav"), y, sr[0])
                idx.put(JSONObject().put("slug", slug).put("label", label).put("src", item.name))
                r.lines.add(label + String.format(Locale.US, "  •  %.1f S", y.size / sr[0].toFloat()))
                r.assigned++
            } catch (t: Throwable) {
                r.failed.add(item.name + ": " + (t.message ?: t.toString()))
            }
        }
        saveIndex(c, idx)
        return r
    }

    @JvmStatic
    fun rename(c: Context, id: String, label: String) {
        val a = index(c)
        val slug = slugOf(id)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i)
            if (o != null && slug == o.optString("slug")) {
                try {
                    o.put("label", label.trim { it <= ' ' }.uppercase(Locale.getDefault()))
                } catch (ignored: Exception) {
                }
            }
        }
        saveIndex(c, a)
    }

    @JvmStatic
    fun delete(c: Context, id: String) {
        val a = index(c)
        val keep = JSONArray()
        val slug = slugOf(id)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i)
            if (o != null && slug != o.optString("slug")) keep.put(o)
        }
        saveIndex(c, keep)
        file(c, id).delete()
    }
}
