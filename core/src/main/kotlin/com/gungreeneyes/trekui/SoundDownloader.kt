package com.gungreeneyes.trekui

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * SETTINGS › AUDIO › DOWNLOAD SOUND LIBRARY. On the user's request, fetches the original recordings
 * listed in [SoundCatalog] from their public web pages, verifies each file's SHA-256, decodes it
 * ([SoundDecoder]), renders every app sound cut from it ([SoundProcessor]) and stores the
 * clips in [SoundCache] (app-private). The downloaded originals are deleted right after.
 *
 * One background thread; every original is fetched once even if several sounds are cut from it;
 * sounds already in the cache are skipped, so an interrupted run simply resumes. Callbacks arrive on
 * the main thread. HTTPS only, with connect/read timeouts and a size cap. Nothing is uploaded and
 * no personal data is sent: a plain GET with a generic User-Agent.
 */
@Suppress("TooManyFunctions")
object SoundDownloader {
    /** Progress / completion callbacks, always on the main thread. */
    interface Listener {
        /** @param done sounds finished so far, @param total sounds in this run, @param label what is being worked on */
        fun progress(done: Int, total: Int, label: String)

        fun finished(result: Result)
    }

    /** Outcome of a run. */
    class Result {
        @JvmField var rendered = 0
        @JvmField var skipped = 0
        @JvmField var failed = 0
        @JvmField var total = 0
        @JvmField var cancelled = false
        @JvmField val errors: MutableList<String> = ArrayList()

        /** Short human-readable summary for a dialog. */
        fun summary(): String {
            val sb = StringBuilder()
            sb.append(String.format(Locale.US, "%d DOWNLOADED, %d ALREADY PRESENT, %d FAILED", rendered, skipped, failed))
            if (cancelled) sb.append("\nCANCELLED")
            val n = Math.min(8, errors.size)
            for (i in 0 until n) sb.append("\n• ").append(errors[i])
            if (errors.size > n) sb.append("\n• … ").append(errors.size - n).append(" MORE")
            return sb.toString()
        }
    }

    internal const val CONNECT_TIMEOUT_MS = 15_000
    internal const val READ_TIMEOUT_MS = 30_000
    internal const val MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Android; TrekUI sound library)"

    @Volatile private var running = false
    @Volatile private var cancelRequested = false
    @Volatile private var listener: Listener? = null
    private val MAIN = Handler(Looper.getMainLooper())

    @JvmStatic
    fun isRunning(): Boolean = running

    /** Asks the running download to stop after the current file. */
    @JvmStatic
    fun cancel() {
        cancelRequested = true
    }

    /** Swap the listener (e.g. null from onDestroy so a running download never holds an Activity). */
    @JvmStatic
    fun setListener(l: Listener?) {
        listener = l
    }

    /**
     * Starts a download unless one is already running.
     * A null listener keeps the one already set with [setListener].
     *
     * @param coreOnly only the sounds the interface events use by default (small), else every available sound
     * @return false if a download is already running
     */
    @JvmStatic
    @Synchronized
    fun start(context: Context, coreOnly: Boolean, l: Listener?): Boolean {
        if (l != null) listener = l
        if (running) return false
        running = true
        cancelRequested = false
        val app = context.applicationContext
        val t = Thread({
            val r = Result()
            try {
                runAll(app, coreOnly, r)
            } catch (e: Throwable) {
                r.errors.add((e.message ?: e).toString())
            } finally {
                running = false
                MAIN.post {
                    if (r.rendered > 0) Sfx.reload(app)
                    listener?.finished(r)
                }
            }
        }, "trekui-sound-download")
        t.priority = Thread.MIN_PRIORITY
        t.start()
        return true
    }

    private fun post(done: Int, total: Int, label: String) {
        MAIN.post { listener?.progress(done, total, label) }
    }

    @Throws(IOException::class)
    // TooGenericExceptionCaught: a decoder or OOM failure fails one clip or original, not the run.
    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ThrowsCount")
    private fun runAll(c: Context, coreOnly: Boolean, r: Result) {
        val cat = SoundCatalog.load(c)
        val want = cat.available(coreOnly)
        r.total = want.size
        val out = SoundCache.dir(c)
        if (!out.isDirectory && !out.mkdirs()) throw IOException("cannot create the sound folder")
        val need = SoundCatalog.estimatedCacheBytes(want) + 20L * 1024 * 1024
        if (out.usableSpace > 0 && out.usableSpace < need) {
            throw IOException(String.format(Locale.US, "not enough free space (needs about %d MB)", need / (1024 * 1024)))
        }
        val tmpDir = File(c.cacheDir, "sound_download")
        SoundImporter.deleteTree(tmpDir)
        if (!tmpDir.mkdirs() && !tmpDir.isDirectory) throw IOException("cannot create the download folder")
        var done = 0
        try {
            for ((sha, sounds) in SoundCatalog.bySource(want)) {
                if (cancelRequested) {
                    r.cancelled = true
                    break
                }
                val group = ArrayList<SoundCatalog.Sound>()
                for (s in sounds) {
                    if (SoundCache.isCached(c, s.id)) {
                        r.skipped++
                        done++
                    } else {
                        group.add(s)
                    }
                }
                if (group.isEmpty()) continue
                post(done, r.total, group[0].label)
                val orig = File(tmpDir, "$sha.bin")
                try {
                    fetchVerified(group[0], orig)
                    var until = 0.0
                    for (s in group) {
                        if (s.end <= 0) {
                            until = 0.0
                            break
                        }
                        until = Math.max(until, s.end)
                    }
                    val pcm = SoundDecoder.decode(orig, until)
                    for (s in group) {
                        try {
                            val clip = SoundProcessor.render(pcm, s.start, s.end, s.gainDb, s.mono, s.fadeInMs, s.fadeOutMs)
                            SoundProcessor.writeWav(SoundCache.file(c, s.id), clip)
                            r.rendered++
                        } catch (ex: IOException) {
                            failClip(r, s, ex)
                        } catch (ex: RuntimeException) {
                            failClip(r, s, ex)
                        } catch (ex: OutOfMemoryError) {
                            failClip(r, s, ex) // the failed allocation is gone; the next clip may fit
                        }
                        post(++done, r.total, s.label)
                    }
                } catch (ex: IOException) {
                    done = failGroup(r, group, done, ex)
                } catch (ex: RuntimeException) {
                    done = failGroup(r, group, done, ex) // decoder failure: this original only
                } catch (ex: OutOfMemoryError) {
                    done = failGroup(r, group, done, ex) // the decoded PCM is unreachable once we are here
                } finally {
                    orig.delete()
                }
            }
        } finally {
            SoundImporter.deleteTree(tmpDir)
        }
    }

    /** Counts one clip that could not be rendered as failed. */
    private fun failClip(r: Result, s: SoundCatalog.Sound, ex: Throwable) {
        r.failed++
        r.errors.add(s.label + ": " + (ex.message ?: ex.toString()))
    }

    /** Counts every clip of an original that could not be fetched or decoded as failed; returns the new done count. */
    private fun failGroup(r: Result, group: List<SoundCatalog.Sound>, done: Int, ex: Throwable): Int {
        r.failed += group.size
        r.errors.add(group[0].label + ": " + (ex.message ?: ex.toString()))
        post(done + group.size, r.total, group[0].label)
        return done + group.size
    }

    /** Tries the preferred source, then each alternate, until one downloads with the right SHA-256. */
    @Throws(IOException::class)
    private fun fetchVerified(s: SoundCatalog.Sound, dst: File) {
        var last: IOException? = null
        for (src in s.allSources()) {
            if (cancelRequested) throw IOException("cancelled")
            try {
                val sha = download(src.url, dst)
                if (SoundProcessor.shaMatches(sha, src.sha256)) return
                last = IOException("file at " + host(src.url) + " has changed (checksum mismatch)")
            } catch (ex: IOException) {
                last = IOException(host(src.url) + ": " + ex.message, ex)
            }
        }
        throw last ?: IOException("no download source")
    }

    private fun host(url: String): String =
        try {
            URL(url).host
        } catch (e: Exception) {
            "?"
        }

    /** Streams the URL into dst while hashing it; returns the SHA-256 hex. HTTPS only. */
    @Throws(IOException::class)
    internal fun download(url: String, dst: File): String {
        val u = URL(url)
        if (!"https".equals(u.protocol, ignoreCase = true)) throw IOException("not an https link")
        val con = u.openConnection() as HttpURLConnection
        try {
            con.connectTimeout = CONNECT_TIMEOUT_MS
            con.readTimeout = READ_TIMEOUT_MS
            con.instanceFollowRedirects = true
            con.setRequestProperty("User-Agent", USER_AGENT)
            con.setRequestProperty("Accept", "audio/*, */*")
            val code = con.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            if (!"https".equals(con.url.protocol, ignoreCase = true)) throw IOException("redirected off https")
            val len = Compat.contentLength(con.getHeaderField("Content-Length")) // contentLengthLong is API 24
            if (len > MAX_DOWNLOAD_BYTES) throw IOException("file too large")
            val md = SoundProcessor.sha256()
            var total = 0L
            con.inputStream.use { input ->
                FileOutputStream(dst).use { os ->
                    val buf = ByteArray(32768)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_DOWNLOAD_BYTES) throw IOException("file too large")
                        if (cancelRequested) throw IOException("cancelled")
                        md.update(buf, 0, n)
                        os.write(buf, 0, n)
                    }
                }
            }
            return SoundProcessor.hex(md.digest())
        } finally {
            con.disconnect()
        }
    }
}
