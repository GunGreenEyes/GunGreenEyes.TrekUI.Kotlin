package com.gungreeneyes.trekui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The flows behind CONFIG › BACKUP: EXPORT LAYOUT and IMPORT LAYOUT through the Storage Access
 * Framework, SNAPSHOT NOW, UNDO LAST CHANGE and RESTORE of an automatic [LayoutSnapshots] entry.
 *
 * Every import and restore is read and validated on a worker thread, then shown as a preview
 * (interface, settings count, assigned tiles, apps missing on this device, credentials left out)
 * with APPLY / CANCEL; nothing changes until APPLY, and APPLY first snapshots the current layout so it
 * can be undone. `SettingsActivity` owns the section rows and forwards its activity results to
 * [onResult].
 */
internal object LayoutBackupUi {
    /** Activity request code of EXPORT LAYOUT (SettingsActivity forwards it). */
    const val REQ_EXPORT = 330

    /** Activity request code of IMPORT LAYOUT (SettingsActivity forwards it). */
    const val REQ_IMPORT = 331

    private val WORK: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "layout-backup").apply { isDaemon = true }
    }

    /** Suggested export file name, e.g. `trekui-layout-20261117.json`. */
    @JvmStatic
    fun exportFileName(time: Long): String =
        "trekui-layout-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(time)) + ".json"

    /** Opens the system "save as" picker for a layout file. */
    @JvmStatic
    fun startExport(a: Activity) {
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(LayoutArchive.MIME)
            .putExtra(Intent.EXTRA_TITLE, exportFileName(System.currentTimeMillis()))
        start(a, i, REQ_EXPORT)
    }

    /** Opens the system file picker for a layout file. */
    @JvmStatic
    fun startImport(a: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(LayoutArchive.MIME, "text/plain", "application/octet-stream"))
        start(a, i, REQ_IMPORT)
    }

    private fun start(a: Activity, i: Intent, req: Int) {
        try {
            a.startActivityForResult(i, req)
        } catch (e: RuntimeException) {
            toast(a, a.getString(R.string.toast_no_file_picker))
        }
    }

    /**
     * Handles the SAF result for [REQ_EXPORT] / [REQ_IMPORT].
     *
     * @param changed run on the UI thread after a layout was applied (rebuilds CONFIG, bumps home)
     * @return true when the request code was one of ours
     */
    @JvmStatic
    fun onResult(a: Activity, requestCode: Int, resultCode: Int, data: Intent?, changed: Runnable): Boolean {
        if (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT) return false
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) return true
        if (requestCode == REQ_EXPORT) {
            val text = LayoutArchive.capture(a).toString()
            WORK.execute {
                val done = try {
                    LayoutArchive.write(a, uri, text)
                    true
                } catch (e: Exception) {
                    false
                }
                ui(a) {
                    Sfx.play(a, if (done) Sfx.STATUS_OK else Sfx.ERROR)
                    toast(a, a.getString(if (done) R.string.toast_layout_exported else R.string.toast_layout_export_failed))
                }
            }
        } else {
            WORK.execute {
                try {
                    val parsed = LayoutArchive.parse(LayoutArchive.read(a, uri))
                    val missing = LayoutArchive.clearMissingApps(parsed.prefs) { pkg -> LayoutArchive.installed(a, pkg) }
                    ui(a) {
                        confirm(a, a.getString(R.string.layout_import_title), parsed, missing, LayoutSnapshots.R_IMPORT, changed)
                    }
                } catch (e: LayoutArchive.InvalidLayoutException) {
                    ui(a) { refuse(a, e.message) }
                } catch (e: Exception) {
                    ui(a) { refuse(a, a.getString(R.string.layout_unreadable)) }
                }
            }
        }
        return true
    }

    /** Snapshots the layout now (SNAPSHOT NOW). */
    @JvmStatic
    fun snapshotNow(a: Activity, changed: Runnable) {
        LayoutSnapshots.snapshot(a, LayoutSnapshots.R_MANUAL)
        Sfx.play(a, Sfx.CONFIRM)
        toast(a, a.getString(R.string.toast_layout_snapshot))
        // the list refreshes once the write is done
        WORK.execute {
            try {
                Thread.sleep(300)
            } catch (ignored: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            ui(a, changed)
        }
    }

    /** UNDO LAST CHANGE: previews the newest snapshot that differs from the current layout. */
    @JvmStatic
    fun undo(a: Activity, changed: Runnable) {
        val now = LayoutArchive.capture(a)
        // list on the snapshot thread, after any snapshot still being written (APPLY's "before" one)
        LayoutSnapshots.afterPendingWrites {
            for (e in LayoutSnapshots.list(a)) {
                if (LayoutSnapshots.sameLayout(now, LayoutSnapshots.readDoc(e.file))) continue
                loadAndConfirm(a, e, changed)
                return@afterPendingWrites
            }
            ui(a) { refuse(a, a.getString(R.string.layout_nothing_to_undo)) }
        }
    }

    /** RESTORE on one snapshot row: previews it, then applies on confirm. */
    @JvmStatic
    fun restore(a: Activity, e: LayoutSnapshots.Entry, changed: Runnable) {
        WORK.execute { loadAndConfirm(a, e, changed) }
    }

    /** Worker thread: loads a snapshot and shows its preview. */
    private fun loadAndConfirm(a: Activity, e: LayoutSnapshots.Entry, changed: Runnable) {
        try {
            val parsed = LayoutSnapshots.load(e)
            val missing = LayoutArchive.clearMissingApps(parsed.prefs) { pkg -> LayoutArchive.installed(a, pkg) }
            ui(a) {
                confirm(
                    a, a.getString(R.string.layout_restore_title, e.reasonLabel()), parsed, missing,
                    LayoutSnapshots.R_RESTORE, changed,
                )
            }
        } catch (ex: LayoutArchive.InvalidLayoutException) {
            ui(a) { refuse(a, ex.message) }
        }
    }

    /** One line for a snapshot row: "17 NOV 2026 15:30 • BEFORE IMPORT". */
    @JvmStatic
    fun rowLabel(e: LayoutSnapshots.Entry): String = formatTime(e.time) + " • " + e.reasonLabel()

    private fun formatTime(time: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(time)).uppercase(Locale.getDefault())

    /** The preview text for a layout about to be applied. */
    @JvmStatic
    fun preview(a: Activity, parsed: LayoutArchive.Parsed, missing: Int): String {
        val prefs = parsed.prefs
        val sb = StringBuilder()
        sb.append(a.getString(R.string.layout_preview_interface, LayoutArchive.interfaceName(LayoutArchive.factionOf(prefs))))
        sb.append('\n').append(a.getString(R.string.layout_preview_counts, prefs.size, LayoutArchive.assignedTiles(prefs)))
        if (parsed.createdAt > 0) {
            sb.append('\n').append(
                a.getString(
                    R.string.layout_preview_saved, formatTime(parsed.createdAt),
                    parsed.appVersion.ifEmpty { "?" },
                ),
            )
        }
        if (missing > 0) sb.append('\n').append(a.getString(R.string.layout_preview_missing, missing))
        if (parsed.filtered > 0) sb.append('\n').append(a.getString(R.string.layout_preview_filtered, parsed.filtered))
        if (parsed.sourceSchema < LayoutArchive.SCHEMA) {
            sb.append('\n').append(a.getString(R.string.layout_preview_migrated, parsed.sourceSchema))
        }
        sb.append("\n\n").append(a.getString(R.string.layout_preview_footer))
        return sb.toString()
    }

    /** Preview with APPLY / CANCEL; APPLY snapshots the current layout (`reason`) then applies. */
    private fun confirm(
        a: Activity, title: String, parsed: LayoutArchive.Parsed, missing: Int,
        reason: String, changed: Runnable,
    ) {
        Sfx.play(a, Sfx.ALERT)
        ConsoleDialog.message(
            a, title, preview(a, parsed, missing),
            arrayOf(a.getString(R.string.layout_apply), a.getString(R.string.layout_cancel)),
        ) { which ->
            if (which != 0) {
                Sfx.play(a, Sfx.CANCEL)
            } else {
                LayoutSnapshots.snapshot(a, reason)
                LayoutArchive.apply(a, parsed.prefs)
                Prefs.get(a).palette() // syncs Skin.faction with the applied interface
                Sfx.reloadProfile(a)
                Sfx.play(a, Sfx.STATUS_OK)
                toast(a, a.getString(R.string.toast_layout_applied))
                changed.run()
            }
        }
    }

    private fun refuse(a: Activity, why: String?) {
        Sfx.play(a, Sfx.DENY)
        val d = ConsoleDialog.message(a, a.getString(R.string.layout_refused_title), why, arrayOf(a.getString(R.string.layout_ok)), null)
        d.setCanceledOnTouchOutside(true)
    }

    /** Snapshot rows for CONFIG (folder listing only). */
    @JvmStatic
    fun snapshots(a: Activity): List<LayoutSnapshots.Entry> = LayoutSnapshots.list(a)

    private fun ui(a: Activity, r: Runnable) {
        a.runOnUiThread {
            if (!a.isFinishing && !a.isDestroyed) r.run()
        }
    }

    private fun toast(a: Activity, msg: String) {
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show()
    }
}
