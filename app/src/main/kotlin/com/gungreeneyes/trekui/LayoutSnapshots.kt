package com.gungreeneyes.trekui

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Automatic layout snapshots: a [LayoutArchive] document written to `filesDir/layouts/`
 * before anything that could wreck a layout — an app upgrade (a `versionCode` change seen on
 * launch), a layout import, a reset, an interface switch, a restore — keeping the newest
 * [KEEP]. CONFIG › BACKUP lists them with RESTORE, and UNDO LAST CHANGE restores the newest.
 *
 * The preferences are copied on the calling thread (they are in memory) so the snapshot is exactly
 * the state before the change that follows; the file write runs on one background thread, in order.
 * A snapshot identical to the newest one is not written again, so switching back and forth doesn't
 * push older layouts out. File names carry the UTC time and reason (`20261117-153012-345Z_import.json`),
 * so the list is built without opening the files; rotation and name parsing are pure and unit-tested.
 */
internal object LayoutSnapshots {
    /** Snapshots kept; older ones are deleted after each write. */
    const val KEEP = 10

    /** Folder under filesDir. */
    const val DIR = "layouts"

    /** Pref holding the versionCode the layout was last seen with (excluded from archives). */
    const val KEY_VERSION_CODE = "layoutVersionCode"

    const val R_UPGRADE = "upgrade"
    const val R_IMPORT = "import"
    const val R_RESET = "reset"
    const val R_INTERFACE = "interface"
    const val R_RESTORE = "restore"
    const val R_MANUAL = "manual"

    /** Reasons, in the order of [REASON_LABELS]. */
    @JvmField
    val REASONS: Array<String> = arrayOf(R_UPGRADE, R_IMPORT, R_RESET, R_INTERFACE, R_RESTORE, R_MANUAL)

    @JvmField
    val REASON_LABELS: Array<String> = arrayOf(
        "BEFORE APP UPDATE", "BEFORE IMPORT", "BEFORE RESET", "BEFORE INTERFACE SWITCH",
        "BEFORE RESTORE", "SAVED BY YOU",
    )

    private const val STAMP = "yyyyMMdd-HHmmss-SSS"
    private const val EXT = ".json"
    private const val UTC_MARK = 'Z'
    private val IO: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "layout-snapshots").apply { isDaemon = true }
    }

    /** One snapshot on disk. */
    class Entry(
        @JvmField val file: File,
        @JvmField val time: Long,
        @JvmField val reason: String,
    ) {
        /** "BEFORE IMPORT" etc. */
        fun reasonLabel(): String = reasonLabel(reason)

        companion object {
            @JvmStatic
            fun reasonLabel(reason: String): String {
                val i = REASONS.indexOf(reason)
                return if (i >= 0) REASON_LABELS[i] else reason.uppercase(Locale.ROOT)
            }
        }
    }

    // ---- pure helpers (unit-tested) -----------------------------------------------------------------

    /**
     * File name for a snapshot taken at `time` for `reason`: the UTC stamp plus `Z`
     * (`20261117-153012-345Z_import.json`), so names never go backwards on a DST or time-zone change.
     * Older builds wrote local-time stamps without the `Z`; [parse] still reads those.
     */
    @JvmStatic
    fun fileName(time: Long, reason: String): String =
        stampFormat(utc = true).format(Date(time)) + UTC_MARK + "_" + reason + EXT

    /** Parses a snapshot file name (UTC `…Z_` or legacy local-time `…_`); null when it isn't one. */
    @JvmStatic
    fun parse(file: File): Entry? {
        val name = file.name
        if (!name.endsWith(EXT)) return null
        val sep = name.indexOf('_')
        val utc = sep == STAMP.length + 1 && name[STAMP.length] == UTC_MARK
        if (sep != STAMP.length && !utc) return null
        val reason = name.substring(sep + 1, name.length - EXT.length)
        if (reason.isEmpty()) return null
        return try {
            val d: Date? = stampFormat(utc).parse(name.substring(0, STAMP.length))
            if (d == null) null else Entry(file, d.time, reason)
        } catch (e: ParseException) {
            null
        }
    }

    private fun stampFormat(utc: Boolean): SimpleDateFormat {
        val f = SimpleDateFormat(STAMP, Locale.US)
        f.isLenient = false
        if (utc) f.timeZone = TimeZone.getTimeZone("UTC")
        return f
    }

    /** Oldest first by creation time (names that aren't snapshots first), then by name. */
    private val OLDEST_FIRST: Comparator<String> =
        compareBy<String> { parse(File(it))?.time ?: Long.MIN_VALUE }.thenBy { it }

    /** The snapshot names to delete so only the newest `keep` remain (ordered by creation time). */
    @JvmStatic
    fun rotation(names: List<String>, keep: Int): MutableList<String> {
        val sorted = names.sortedWith(OLDEST_FIRST)
        val excess = sorted.size - maxOf(0, keep)
        return if (excess <= 0) ArrayList() else ArrayList(sorted.subList(0, excess))
    }

    /**
     * Whether launching with `currentCode` after `storedCode` (-1 = never stored) needs an
     * upgrade snapshot. A first launch of a build that predates this check still snapshots when a layout
     * already exists (`hasLayout`); a fresh install doesn't.
     */
    @JvmStatic
    fun needsUpgradeSnapshot(storedCode: Int, currentCode: Int, hasLayout: Boolean): Boolean =
        if (storedCode < 0) hasLayout else storedCode != currentCode

    /** True when two documents hold the same layout (ignores the time stamp and app version). */
    @JvmStatic
    fun sameLayout(a: JSONObject?, b: JSONObject?): Boolean {
        if (a == null || b == null) return false
        val pa = a.optJSONObject("prefs")
        val pb = b.optJSONObject("prefs")
        return pa != null && pb != null && pa.toString() == pb.toString()
    }

    // ---- device side ------------------------------------------------------------------------------

    private fun dir(c: Context): File = File(c.filesDir, DIR)

    /**
     * Snapshots the current layout for `reason`. Preferences are read now; the write, the
     * duplicate check and the rotation happen on the snapshot thread.
     */
    @JvmStatic
    fun snapshot(c: Context, reason: String) {
        val app = c.applicationContext
        val doc = LayoutArchive.capture(app)
        IO.execute { write(app, doc, reason, System.currentTimeMillis()) }
    }

    /** Writes one snapshot unless it matches the newest; then rotates. Snapshot thread. */
    private fun write(c: Context, doc: JSONObject, reason: String, time: Long) {
        val d = dir(c)
        if (!d.isDirectory && !d.mkdirs()) return
        val existing = list(c)
        if (existing.isNotEmpty() && sameLayout(doc, readDoc(existing[0].file))) return
        val out = File(d, fileName(time, reason))
        try {
            FileOutputStream(out).use { os -> os.write(doc.toString().toByteArray(Charsets.UTF_8)) }
        } catch (e: IOException) {
            out.delete()
            return
        }
        val names = d.list()?.filter { parse(File(d, it)) != null } ?: emptyList()
        for (n in rotation(names, KEEP)) File(d, n).delete()
    }

    /** The snapshots on disk, newest first. Only lists the folder (no file is opened). */
    @JvmStatic
    fun list(c: Context): MutableList<Entry> {
        val files = dir(c).listFiles() ?: return ArrayList()
        val out = files.mapNotNullTo(ArrayList()) { parse(it) }
        out.sortWith(compareByDescending<Entry> { it.time }.thenByDescending { it.file.name })
        return out
    }

    /**
     * Runs `r` on the snapshot thread after every snapshot queued so far has been written, so a
     * listing made there sees them (UNDO right after APPLY).
     */
    @JvmStatic
    fun afterPendingWrites(r: Runnable) {
        IO.execute(r)
    }

    /** Reads a snapshot document, or null when unreadable. Worker thread. */
    @JvmStatic
    fun readDoc(f: File): JSONObject? = try {
        JSONObject(f.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    /** Reads and validates a snapshot. Worker thread. */
    @JvmStatic
    @Throws(LayoutArchive.InvalidLayoutException::class)
    fun load(e: Entry): LayoutArchive.Parsed {
        val doc = readDoc(e.file) ?: throw LayoutArchive.InvalidLayoutException("SNAPSHOT UNREADABLE")
        return LayoutArchive.parse(doc.toString())
    }

    /**
     * Called from home's onCreate: when the app's versionCode differs from the one the layout was
     * last seen with, snapshot the layout before anything new touches it, then remember the new code.
     */
    @JvmStatic
    fun checkUpgrade(c: Context) {
        val p = Prefs.get(c)
        val stored = p.i(KEY_VERSION_CODE, -1)
        val current = BuildConfig.VERSION_CODE
        if (stored == current) return
        val hasLayout = LayoutArchive.filter(p.sp.all).isNotEmpty()
        if (needsUpgradeSnapshot(stored, current, hasLayout)) snapshot(c, R_UPGRADE)
        p.put(KEY_VERSION_CODE, current)
    }
}
