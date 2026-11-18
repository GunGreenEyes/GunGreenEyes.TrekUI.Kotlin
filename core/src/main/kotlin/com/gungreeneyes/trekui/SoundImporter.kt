package com.gungreeneyes.trekui

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Turns user-supplied audio files into console event sounds, entirely on the phone:
 * decode (any format Android can read) → mono → trim leading/trailing silence → gentle level
 * match → 16-bit WAV in private app storage. Pitch, speed and timbre are never altered:
 * there is no resampling, EQ, compression or effects.
 *
 * The audio arithmetic keeps the Java edition's float / double steps one for one, so imported
 * clips stay byte-identical.
 */
object SoundImporter {
    class Item(
        @JvmField val name: String,
        @JvmField val uri: Uri?,
        @JvmField val file: File?,
    )

    class Report {
        @JvmField val lines: MutableList<String> = ArrayList()
        @JvmField val unmatched: MutableList<String> = ArrayList()
        @JvmField val failed: MutableList<String> = ArrayList()
        @JvmField var assigned = 0
    }

    private val AUDIO_EXT = arrayOf(".mp3", ".wav", ".ogg", ".oga", ".m4a", ".aac", ".flac", ".opus", ".3gp", ".amr")
    private const val MAX_SECONDS = 10
    private val TRAILING_DIGITS = Regex("[_0-9]+$")
    private val NATURAL_PARTS = Regex("\\d+|\\D+")

    // ---- filename → event matching ---------------------------------------------------------------

    private val ALIASES = arrayOf(
        arrayOf("button", "button_press", "buttonpress", "press", "tap"),
        arrayOf("navigation", "nav"),
        arrayOf("launch", "app_launch", "applaunch"),
        arrayOf("confirm", "confirmation", "input_confirmation", "inputconfirmation"),
        arrayOf("error"),
        arrayOf("comms", "comm", "communications", "communication"),
        arrayOf("diagnostics", "system_diagnostics", "systemdiagnostics", "diagnostic"),
        arrayOf("red_alert", "redalert", "alert_red"),
    )

    /** Keyword rules, most specific first: event and its keywords. */
    private val RULES: Array<Pair<Int, Array<String>>> = arrayOf(
        Sfx.E_RED to arrayOf("red_alert", "redalert", "klaxon"),
        Sfx.E_ERROR to arrayOf("deny", "denied", "error", "incorrect", "unable", "invalid", "fail", "criticalstop", "reject"),
        Sfx.E_COMMS to arrayOf("chirp", "hail", "communicator", "combadge", "incoming", "ringin", "transmission", "comm_badge"),
        Sfx.E_DIAG to arrayOf("scan", "scanner", "diagnostic", "working", "thinking", "accessing", "sequence", "processing"),
        Sfx.E_CONFIRM to arrayOf("confirm", "affirm", "complete", "verified", "accept", "acknowledg", "authacc", "filedone", "success"),
        Sfx.E_LAUNCH to arrayOf("launch", "viewscreen_on", "maximize", "engage", "open"),
        Sfx.E_NAV to arrayOf("navigation", "viewscreen_off", "minimize", "popup", "swipe", "close", "page"),
        Sfx.E_BUTTON to arrayOf("beep", "button", "keypress", "panel", "press", "click", "computer", "tap", "console"),
    )

    @JvmStatic
    fun baseName(name: String): String {
        var n = name.lowercase(Locale.US)
        val slash = n.lastIndexOf('/')
        if (slash >= 0) n = n.substring(slash + 1)
        val dot = n.lastIndexOf('.')
        if (dot > 0) n = n.substring(0, dot)
        return n.replace(' ', '_').replace('-', '_')
    }

    /** @return event index or -1. */
    @JvmStatic
    fun match(fileName: String): Int {
        val n = baseName(fileName)
        val bare = n.replace(TRAILING_DIGITS, "")
        for (e in ALIASES.indices) {
            for (a in ALIASES[e]) if (n == a || bare == a) return e
        }
        for ((event, keywords) in RULES) {
            for (k in keywords) if (n.contains(k)) return event
        }
        return -1
    }

    @JvmStatic
    fun isAudio(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return AUDIO_EXT.any { n.endsWith(it) }
    }

    @JvmStatic
    fun displayName(c: Context, uri: Uri): String {
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst() && !cur.isNull(0)) return cur.getString(0)
            }
        } catch (ignored: Exception) {
        }
        return uri.lastPathSegment ?: "sound"
    }

    /** Extracts audio entries from a ZIP into a temporary folder (base names only, so no path traversal). */
    @JvmStatic
    @Throws(IOException::class)
    fun unzip(c: Context, zip: Uri): List<Item> {
        val tmp = File(c.cacheDir, "trekui_import")
        deleteTree(tmp)
        if (!tmp.mkdirs() && !tmp.isDirectory) throw IOException("cannot create temp folder")
        val out = ArrayList<Item>()
        c.contentResolver.openInputStream(zip).use { raw ->
            ZipInputStream(raw).use { zin ->
                val buf = ByteArray(16384)
                while (true) {
                    val e = zin.nextEntry ?: break
                    val name = e.name
                    val base = name.substring(name.lastIndexOf('/') + 1)
                    if (e.isDirectory || name.contains("__MACOSX") || base.startsWith(".") || !isAudio(base)) continue
                    val f = File(tmp, out.size.toString() + "_" + base)
                    var total = 0L
                    FileOutputStream(f).use { os ->
                        while (true) {
                            val n = zin.read(buf)
                            if (n <= 0) break
                            total += n
                            if (total > 30L * 1024 * 1024) throw IOException("$base is too large")
                            os.write(buf, 0, n)
                        }
                    }
                    out.add(Item(base, null, f))
                }
            }
        }
        return out
    }

    @JvmStatic
    fun deleteTree(f: File?) {
        if (f == null || !f.exists()) return
        f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }

    // ---- import flows ------------------------------------------------------------------------

    /** Auto-match a batch of files to events by filename; only events with a match are replaced. */
    @JvmStatic
    fun importAuto(c: Context, items: List<Item>, sourceLabel: String): Report {
        val r = Report()
        val byEvent = List(Sfx.EVENTS) { ArrayList<Item>() }
        for (item in items) {
            val e = match(item.name)
            if (e < 0) r.unmatched.add(item.name) else byEvent[e].add(item)
        }
        for (e in 0 until Sfx.EVENTS) {
            var list: MutableList<Item> = byEvent[e]
            if (list.isEmpty()) continue
            sortByPreference(list, e)
            // Never auto-pick clips from other series or spoken episode lines; offer them for manual assignment.
            val eligible = ArrayList<Item>()
            for (item in list) {
                if (score(item.name, e) >= 10) r.unmatched.add(item.name + " (other series / voice: assign manually)")
                else eligible.add(item)
            }
            list = eligible
            if (list.isEmpty()) continue
            val max = if (e == Sfx.E_BUTTON) Sfx.MAX_VARIANTS else 1
            val use = list.subList(0, Math.min(max, list.size))
            val ok = assign(c, e, use, sourceLabel, r)
            if (ok > 0) {
                r.assigned++
                val sb = StringBuilder(Sfx.EVENT_NAMES[e]).append("  ←  ").append(use[0].name)
                if (ok > 1) sb.append("  +").append(ok - 1).append(" VARIANT").append(if (ok > 2) "S" else "")
                r.lines.add(sb.toString())
            }
            for (i in use.size until list.size) r.unmatched.add(list[i].name + " (extra " + Sfx.EVENT_KEYS[e] + ")")
        }
        return r
    }

    /** Replace one event's sounds with the given files (button press keeps up to [Sfx.MAX_VARIANTS] variants). */
    @JvmStatic
    fun assign(c: Context, event: Int, items: List<Item>, sourceLabel: String, r: Report): Int {
        val dir = Sfx.dir(c)
        if (!dir.isDirectory && !dir.mkdirs()) {
            r.failed.add("cannot create sound folder")
            return 0
        }
        val max = if (event == Sfx.E_BUTTON) Sfx.MAX_VARIANTS else 1
        val names = ArrayList<String>()
        val made = ArrayList<File>()
        for (item in items) {
            if (names.size >= max) break
            val tmp = File(dir, Sfx.EVENT_KEYS[event] + "_new" + names.size + ".wav")
            try {
                val sr = IntArray(1)
                val pcm = decode(c, item, sr)
                val out = process(pcm, sr[0])
                writeWav(tmp, out, sr[0])
                names.add(item.name)
                made.add(tmp)
            } catch (t: Throwable) {
                r.failed.add(item.name + " (" + (t.message ?: t.javaClass.simpleName) + ")")
                tmp.delete()
            }
        }
        if (made.isEmpty()) return 0
        clear(c, event)
        val p = TrekPrefs.get(c)
        for (v in made.indices) {
            val dst = Sfx.file(c, event, v)
            made[v].renameTo(dst)
            p.put("snd" + event + "name" + v, names[v])
        }
        p.put("snd" + event + "n", made.size)
        p.put("snd" + event + "src", sourceLabel)
        return made.size
    }

    @JvmStatic
    fun clear(c: Context, event: Int) {
        for (v in 0 until Sfx.MAX_VARIANTS) Sfx.file(c, event, v).delete()
        TrekPrefs.get(c).sp.edit().remove("snd" + event + "n").remove("snd" + event + "src").apply()
        val ed = TrekPrefs.get(c).sp.edit()
        for (v in 0 until Sfx.MAX_VARIANTS) ed.remove("snd" + event + "name" + v)
        ed.apply()
    }

    /** Preferred source names per event (earlier = better). */
    private val PREFERRED = arrayOf(
        arrayOf("tng_computer_beep", "computer_beep", "computerbeep", "tng_", "beep"),
        arrayOf("viewscreen_off", "minimize", "close"),
        arrayOf("viewscreen_on", "maximize", "launch"),
        arrayOf("transfercomplete", "confirm", "complete", "authacc"),
        arrayOf("denybeep", "deny", "error"),
        arrayOf("tng_chirp", "chirp", "hailbeep", "hail"),
        arrayOf("beepsworking", "scan", "working", "accessing"),
        arrayOf("tng_red_alert", "red_alert", "redalert"),
    )

    /** File-name tags of other collections / non-console sources (as named on the public source pages), and spoken clips: ranked last. */
    private val AVOID = arrayOf("tos_", "ent_", "ds9", "voy_", "alien", "_ep")

    private fun score(name: String, event: Int): Int {
        val n = baseName(name)
        var s = 0
        for (av in AVOID) if (n.contains(av)) s += 10
        if (n.contains("tng")) s -= 3
        if (n.contains("clean")) s -= 1
        val pref = PREFERRED[event]
        for (i in pref.indices) {
            if (n.contains(pref[i])) {
                s -= pref.size - i
                break
            }
        }
        return s
    }

    private fun sortByPreference(list: MutableList<Item>, event: Int) {
        list.sortWith { a, b ->
            val d = score(a.name, event) - score(b.name, event)
            if (d != 0) d else naturalKey(a.name).compareTo(naturalKey(b.name))
        }
    }

    /** "beep 2" sorts before "beep 10". */
    private fun naturalKey(s: String): String {
        val sb = StringBuilder()
        for (m in NATURAL_PARTS.findAll(s.lowercase(Locale.US))) {
            val g = m.value
            if (Character.isDigit(g[0])) {
                sb.append(String.format(Locale.US, "%08d", (if (g.length > 8) g.substring(0, 8) else g).toLong()))
            } else {
                sb.append(g)
            }
        }
        return sb.toString()
    }

    // ---- decode / process / write ------------------------------------------------------------

    /** Decodes to mono float PCM at the file's own sample rate (max 20 s read). */
    @JvmStatic
    @Throws(IOException::class)
    fun decode(c: Context, item: Item, srOut: IntArray): FloatArray {
        val ex = MediaExtractor()
        var dec: MediaCodec? = null
        try {
            if (item.file != null) ex.setDataSource(item.file.absolutePath) else ex.setDataSource(c, item.uri!!, null)
            var track = -1
            var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    track = i
                    fmt = f
                    break
                }
            }
            if (track < 0 || fmt == null) throw IOException("no audio track")
            ex.selectTrack(track)
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = Math.max(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            var enc = AudioFormat.ENCODING_PCM_16BIT
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            dec = codec
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var out = FloatArray(sr * 2)
            var len = 0
            var inEos = false
            var outEos = false
            var idle = 0
            while (!outEos && idle < 600) {
                var progress = false
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val b = codec.getInputBuffer(ii)
                        val n = if (b == null) -1 else ex.readSampleData(b, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                        progress = true
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 5000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        val bytesPer = when (enc) {
                            AudioFormat.ENCODING_PCM_FLOAT -> 4
                            AudioFormat.ENCODING_PCM_8BIT -> 1
                            else -> 2
                        }
                        val frames = info.size / (bytesPer * ch)
                        if (len + frames > out.size) out = out.copyOf(Math.max(out.size * 2, len + frames))
                        for (f in 0 until frames) {
                            var sum = 0f
                            for (k in 0 until ch) {
                                sum += when (bytesPer) {
                                    4 -> ob.getFloat()
                                    1 -> ((ob.get().toInt() and 0xFF) - 128) / 128f
                                    else -> ob.getShort() / 32768f
                                }
                            }
                            out[len++] = sum / ch
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true
                    if (len > sr * 20) outEos = true
                    progress = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) ch = Math.max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    progress = true
                }
                idle = if (progress) 0 else idle + 1
            }
            if (len == 0) throw IOException("could not decode")
            srOut[0] = sr
            return out.copyOf(len)
        } finally {
            dec?.let {
                try {
                    it.stop()
                } catch (ignored: Exception) {
                }
                it.release()
            }
            ex.release()
        }
    }

    /**
     * Trim + gentle level match. Only gain and edge fades are applied; the waveform's pitch,
     * duration (apart from removed silence) and timbre are untouched.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun process(x: FloatArray, sr: Int): FloatArray = process(x, sr, MAX_SECONDS)

    /** @param maxSeconds longer clips are cut (with a fade) at this length */
    @JvmStatic
    @Throws(IOException::class)
    fun process(x: FloatArray, sr: Int, maxSeconds: Int): FloatArray {
        var peak = 0f
        for (v in x) peak = Math.max(peak, Math.abs(v))
        if (peak < 1e-4f) throw IOException("file is silent")
        val thr = Math.max(peak * 0.0056f, 0.0006f) // -45 dB relative to the clip's own peak
        var a = 0
        var b = x.size - 1
        while (a < x.size && Math.abs(x[a]) < thr) a++
        while (b > a && Math.abs(x[b]) < thr) b--
        val start = Math.max(0, a - (sr * 0.003f).toInt())
        val end = Math.min(x.size, b + 1 + (sr * 0.04f).toInt())
        var y = x.copyOfRange(start, end)
        var cutTail = end < x.size
        if (y.size > sr * maxSeconds) {
            y = y.copyOf(sr * maxSeconds)
            cutTail = true
        }
        // de-click the new edges only
        if (start > 0) fade(y, 0, Math.min(y.size, (sr * 0.0015f).toInt()), true)
        if (cutTail) {
            val n = Math.min(y.size, (sr * (if (y.size >= sr * maxSeconds) 0.05f else 0.01f)).toInt())
            fade(y, y.size - n, n, false)
        }
        // gentle loudness match: RMS of the active part toward -18 dBFS, limited to ±9 dB, peak ≤ -1 dBFS
        var sum = 0.0
        var cnt = 0
        var pk = 0f
        for (v in y) {
            pk = Math.max(pk, Math.abs(v))
            if (Math.abs(v) >= thr) {
                sum += v * v
                cnt++
            }
        }
        val rms = Math.sqrt(sum / Math.max(1, cnt))
        var g = if (rms > 0) 0.126 / rms else 1.0
        g = Math.max(0.355, Math.min(2.82, g))
        if (pk * g > 0.89) g = 0.89 / pk
        for (i in y.indices) y[i] *= g.toFloat()
        return y
    }

    private fun fade(y: FloatArray, from: Int, n: Int, fadeIn: Boolean) {
        for (i in 0 until n) {
            val k = (i + 1f) / n
            y[from + i] *= if (fadeIn) k else 1f - k
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun writeWav(f: File, y: FloatArray, sr: Int) {
        val dataLen = y.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(SoundProcessor.ascii("RIFF")).putInt(36 + dataLen).put(SoundProcessor.ascii("WAVE"))
        bb.put(SoundProcessor.ascii("fmt ")).putInt(16).putShort(1.toShort()).putShort(1.toShort())
            .putInt(sr).putInt(sr * 2).putShort(2.toShort()).putShort(16.toShort())
        bb.put(SoundProcessor.ascii("data")).putInt(dataLen)
        for (v in y) bb.putShort(Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f).toShort())
        FileOutputStream(f).use { it.write(bb.array()) }
    }
}
