package com.gungreeneyes.trekui

import android.content.Context
import com.gungreeneyes.trekui.core.CoreConfig
import java.io.File
import java.io.IOException
import java.util.Properties
import org.json.JSONException
import org.json.JSONObject

/**
 * The sound update channel (docs/SOUNDS.md): `manifest.json` at [CoreConfig.SFX_BASE_URL] lists the
 * project's own sounds (the synthesized set and tools/sounds/owned/) with their SHA-256, so an owned sound
 * can be added or fixed without an app update.
 *
 * A check fetches the manifest (at most [MAX_MANIFEST_BYTES]), validates it and, when its `version` is
 * higher than the bundled manifest's, saves it to [SoundUpdateFiles.manifestFile]; [SoundCatalog.load]
 * then merges it over the bundled one. Every owned sound whose SHA-256 differs from the bundled
 * synthesized asset is downloaded (at most [MAX_SOUND_BYTES]), checked against its SHA-256 and rendered
 * into [SoundCache], where it overrides the bundled asset. This needs no opt-in: it is the project's
 * own audio. A remote sound equal to the bundled asset (or no longer listed) removes the earlier
 * override again.
 *
 * Every failure (offline, HTTP error, wrong checksum, malformed or oversized JSON) is recorded in the
 * [Result] and otherwise ignored: the bundled and cached sounds keep playing. The network itself is the
 * caller's [Fetcher] (the launcher's HTTPS helper); apps without the INTERNET permission (the keyboard)
 * never run a check. No identifier or analytics is sent, only plain GETs.
 *
 * Blocking: run it on a background thread, never the main thread.
 */
object SoundUpdate {
    /** File name of the manifest on the channel and in [dir]. */
    const val MANIFEST_NAME = "manifest.json"

    /** Largest manifest accepted (2 MiB). */
    const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024

    /** Largest sound file accepted (5 MiB). */
    const val MAX_SOUND_BYTES = 5 * 1024 * 1024

    /** Automatic checks run at most this often (24 h); opening the sound settings checks regardless. */
    const val INTERVAL_MS = 24L * 60 * 60 * 1000

    private const val DOWNLOAD_NAME = "download.tmp"
    private const val PREFS = "trekui_sfx_update"
    private const val KEY_LAST_CHECK = "last_check_ms"

    /** Fetches one https URL and returns its body, failing with [IOException] above `maxBytes`. */
    fun interface Fetcher {
        @Throws(IOException::class)
        fun get(url: String, maxBytes: Int): ByteArray
    }

    /** Turns a verified download into the cached clip `dst` (decode + manifest recipe + WAV). */
    fun interface Renderer {
        @Throws(IOException::class)
        fun render(original: File, sound: SoundCatalog.Sound, dst: File)
    }

    /** Outcome of one check. */
    class Result {
        /** The channel's manifest was valid and newer than the bundled one, and is now in use. */
        @JvmField var manifestAccepted = false

        /** Owned sounds downloaded into the cache (they now override the bundled asset). */
        @JvmField var updated = 0

        /** Earlier overrides removed because the channel no longer differs from the bundled asset. */
        @JvmField var reverted = 0

        /** Owned sounds that could not be fetched or verified (the previous sound keeps playing). */
        @JvmField var failed = 0

        @JvmField val errors: MutableList<String> = ArrayList()

        /** True when the playable sounds changed, so the sound pool should be reloaded. */
        fun changed(): Boolean = updated > 0 || reverted > 0
    }

    /** Decodes the downloaded file and applies the sound's recipe, as the library download does. */
    @JvmField
    val DECODE_RENDERER: Renderer = Renderer { original, s, dst ->
        val pcm = SoundDecoder.decode(original, 0.0)
        val clip = SoundProcessor.render(pcm, s.start, s.end, s.gainDb, s.mono, s.fadeInMs, s.fadeOutMs)
        SoundProcessor.writeWav(dst, clip)
    }

    /** Address of the channel's manifest. */
    @JvmStatic
    fun manifestUrl(): String = CoreConfig.SFX_BASE_URL + MANIFEST_NAME

    /**
     * The 24-hour throttle as a pure function: due when there was no check yet, when [INTERVAL_MS] has
     * passed, or when the clock went backwards (a changed clock must not block checks for days).
     */
    @JvmStatic
    fun isDue(lastCheckMs: Long, nowMs: Long): Boolean =
        lastCheckMs <= 0L || nowMs < lastCheckMs || nowMs - lastCheckMs >= INTERVAL_MS

    /**
     * Parses and validates a channel manifest: at most [MAX_MANIFEST_BYTES], a JSON object with a positive
     * integer `version` and a `sounds` array, in a format this app understands.
     *
     * @throws IOException for anything else
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parseRemote(bytes: ByteArray): SoundCatalog {
        if (bytes.size > MAX_MANIFEST_BYTES) throw IOException("manifest too large")
        val text = String(bytes, Charsets.UTF_8)
        val catalog = try {
            val root = JSONObject(text)
            val v = root.opt("version")
            val valid = (v is Int || v is Long) && (v as Number).toLong() > 0L && root.optJSONArray("sounds") != null
            if (valid) SoundCatalog.parse(text) else null
        } catch (ignored: JSONException) {
            null // malformed JSON or a newer format: reported below
        }
        return catalog ?: throw IOException("malformed manifest (needs JSON with an integer version and sounds)")
    }

    /**
     * Runs a check unless one ran less than [INTERVAL_MS] ago (`force` skips the throttle: the user opened
     * the sound settings). The attempt is recorded before it runs, so a failing server is not retried
     * sooner. Returns null when throttled.
     */
    @JvmStatic
    @JvmOverloads
    fun checkIfDue(
        c: Context,
        nowMs: Long,
        force: Boolean,
        fetcher: Fetcher,
        renderer: Renderer = DECODE_RENDERER,
    ): Result? {
        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && !isDue(prefs.getLong(KEY_LAST_CHECK, 0L), nowMs)) return null
        prefs.edit().putLong(KEY_LAST_CHECK, nowMs).apply()
        return run(c, fetcher, renderer)
    }

    /**
     * One check: fetch and save the manifest, then bring the owned sounds up to date. I/O failures end
     * up in [Result.errors]; nothing changes for what they affected.
     */
    @JvmStatic
    fun run(c: Context, fetcher: Fetcher, renderer: Renderer): Result {
        val r = Result()
        try {
            r.manifestAccepted = fetchManifest(c, fetcher)
            applyOwned(c, SoundCatalog.load(c), fetcher, renderer, r)
        } catch (e: IOException) {
            r.errors.add(e.message ?: e.toString())
        }
        return r
    }

    /** Fetches and validates the manifest; saves it when it is newer than the bundled one. */
    @Throws(IOException::class)
    private fun fetchManifest(c: Context, fetcher: Fetcher): Boolean {
        val bytes = fetcher.get(manifestUrl(), MAX_MANIFEST_BYTES)
        val remote = parseRemote(bytes)
        if (!SoundCatalog.isNewer(remote.version, SoundCatalog.loadBundled(c).version)) return false
        val f = SoundUpdateFiles.manifestFile(c)
        if (!f.isFile || !f.readBytes().contentEquals(bytes)) {
            SoundUpdateFiles.writeAtomically(f, bytes)
            SoundCatalog.invalidate()
        }
        return true
    }

    @Throws(IOException::class)
    private fun applyOwned(c: Context, cat: SoundCatalog, fetcher: Fetcher, renderer: Renderer, r: Result) {
        val applied = SoundUpdateFiles.loadApplied(c)
        val owned = cat.owned()
        val ownedIds = owned.mapTo(HashSet()) { it.id }
        for (id in applied.stringPropertyNames()) {
            if (id !in ownedIds) revert(c, applied, id, r)
        }
        for (s in owned) {
            val want = s.source?.sha256.orEmpty()
            val had = applied.getProperty(s.id)
            when {
                want == SoundUpdateFiles.bundledAssetSha(c, s.id) -> if (had != null) revert(c, applied, s.id, r)
                had == want && SoundCache.isCached(c, s.id) -> Unit // already current
                fetchFirst(c, s, fetcher, renderer, r) -> {
                    applied.setProperty(s.id, want)
                    r.updated++
                }
                else -> r.failed++
            }
        }
        SoundUpdateFiles.saveApplied(c, applied)
    }

    /** Removes an override this channel wrote, so the bundled asset plays again. */
    private fun revert(c: Context, applied: Properties, id: String, r: Result) {
        applied.remove(id)
        if (SoundCache.file(c, id).delete()) r.reverted++
    }

    /** Tries each source of an owned sound in order (update channel first) until one installs. */
    private fun fetchFirst(
        c: Context,
        s: SoundCatalog.Sound,
        fetcher: Fetcher,
        renderer: Renderer,
        r: Result,
    ): Boolean {
        for (src in s.allSources()) {
            val error = fetchOne(c, s, src, fetcher, renderer) ?: return true
            r.errors.add(s.id + ": " + error)
        }
        return false
    }

    /**
     * Downloads one source of an owned sound, checks its SHA-256 and renders it into the cache.
     *
     * @return null when installed, else why not
     */
    private fun fetchOne(
        c: Context,
        s: SoundCatalog.Sound,
        src: SoundCatalog.Source,
        fetcher: Fetcher,
        renderer: Renderer,
    ): String? {
        val tmp = File(SoundUpdateFiles.dir(c), DOWNLOAD_NAME)
        return try {
            val bytes = fetcher.get(src.url, MAX_SOUND_BYTES)
            val sha = SoundProcessor.sha256Hex(bytes)
            if (!SoundProcessor.shaMatches(sha, src.sha256)) throw IOException("checksum mismatch")
            SoundUpdateFiles.writeAtomically(tmp, bytes)
            val out = SoundCache.dir(c)
            if (!out.isDirectory && !out.mkdirs()) throw IOException("cannot create the sound folder")
            renderer.render(tmp, s, SoundCache.file(c, s.id))
            null
        } catch (e: IOException) {
            e.message ?: e.toString()
        } finally {
            tmp.delete()
        }
    }
}
