package com.gungreeneyes.trekui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import java.util.Locale

/**
 * The dialogs behind SETTINGS › AUDIO › DOWNLOAD SOUND LIBRARY: an explanation with the download
 * size and where the files come from, a live progress dialog with CANCEL, the result, and DELETE
 * DOWNLOADED SOUNDS. All work happens in [SoundDownloader]; this object only talks to the user.
 * The settings screen calls [status], [start] and [confirmDelete].
 */
internal object SoundDownloadUi {

    /** One-line state for the settings note, e.g. "112 OF 425 SOUNDS DOWNLOADED • 9.8 MB". */
    @JvmStatic
    fun status(c: Context): String {
        val cat = SoundCatalog.load(c)
        val avail = cat.available(false).size
        val have = SoundCache.count(c)
        if (avail == 0) return c.getString(R.string.sound_dl_none_listed)
        if (have == 0) return c.getString(R.string.sound_dl_status_empty, avail)
        return c.getString(R.string.sound_dl_status, have, avail, mb(SoundCache.bytes(c)))
    }

    private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))

    /**
     * Explains what will happen, then downloads with a progress dialog.
     *
     * @param coreOnly only the sounds the interfaces use by default
     * @param done     run on the main thread after the result dialog is dismissed (e.g. rebuild the list)
     */
    @JvmStatic
    fun start(a: Activity, coreOnly: Boolean, done: Runnable?) {
        if (SoundDownloader.isRunning()) {
            showProgress(a, done)
            return
        }
        val cat = SoundCatalog.load(a)
        val want = cat.available(coreOnly)
        if (want.isEmpty()) {
            ConsoleDialog.message(
                a, a.getString(R.string.sound_dl_title_list), a.getString(R.string.sound_dl_none_listed_short),
                arrayOf(a.getString(R.string.sound_dl_ok)), null,
            )
            return
        }
        val msg = a.getString(
            R.string.sound_dl_explain,
            want.size, mb(SoundCatalog.downloadBytes(want)), mb(SoundCatalog.estimatedCacheBytes(want)),
        )
        ConsoleDialog.message(
            a, a.getString(if (coreOnly) R.string.sound_dl_title_core else R.string.sound_dl_title_library), msg,
            arrayOf(a.getString(R.string.sound_dl_download), a.getString(R.string.sound_dl_cancel)),
        ) { which ->
            if (which == 0) {
                showProgress(a, done)
                SoundDownloader.start(a, coreOnly, null)
            }
        }
    }

    /** Shows (or re-attaches to) the progress dialog of the running download. */
    private fun showProgress(a: Activity, done: Runnable?) {
        val p = Prefs.get(a).palette()
        val b = ConsoleDialog.base(a, a.getString(R.string.sound_dl_title_progress))
        val line = Ui.text(a, a.getString(R.string.sound_dl_connecting), 16f, p.textHi)
        line.setPadding(Ui.dp(a, Ui.XS.toFloat()), 0, Ui.dp(a, Ui.XS.toFloat()), Ui.dp(a, Ui.L.toFloat()))
        b.body.addView(line)
        ConsoleDialog.addOption(
            b,
            ConsoleDialog.option(a, a.getString(R.string.sound_dl_cancel), p.c(3)) {
                SoundDownloader.cancel()
                line.text = a.getString(R.string.sound_dl_stopping)
            },
        )
        b.dialog.setCancelable(false)
        b.dialog.show()
        SoundDownloader.setListener(object : SoundDownloader.Listener {
            override fun progress(done: Int, total: Int, label: String) {
                line.text = String.format(Locale.US, "%d / %d\n%s", done, total, label)
            }

            override fun finished(result: SoundDownloader.Result) {
                SoundDownloader.setListener(null)
                dismiss(b.dialog)
                if (a.isFinishing || a.isDestroyed) return
                Sfx.play(a, if (result.failed == 0 && !result.cancelled) Sfx.CONFIRM else Sfx.DENY)
                ConsoleDialog.message(
                    a, a.getString(if (result.cancelled) R.string.sound_dl_stopped else R.string.sound_dl_complete),
                    result.summary(), arrayOf(a.getString(R.string.sound_dl_ok)),
                ) { done?.run() }
            }
        })
    }

    /** Asks, then deletes every downloaded clip and reloads the sounds (events fall back to silence). */
    @JvmStatic
    fun confirmDelete(a: Activity, done: Runnable?) {
        ConsoleDialog.message(
            a, a.getString(R.string.sound_dl_delete_title),
            a.getString(R.string.sound_dl_delete_message, SoundCache.count(a), mb(SoundCache.bytes(a))),
            arrayOf(a.getString(R.string.sound_dl_delete), a.getString(R.string.sound_dl_cancel)),
        ) { which ->
            if (which == 0) {
                if (!SoundDownloader.isRunning()) {
                    deleteAll(a, done)
                    return@message
                }
                // the file in flight would land after a clear: stop the run, clear once it has finished
                SoundDownloader.setListener(object : SoundDownloader.Listener {
                    override fun progress(done: Int, total: Int, label: String) = Unit

                    override fun finished(result: SoundDownloader.Result) {
                        SoundDownloader.setListener(null)
                        deleteAll(a, done)
                    }
                })
                SoundDownloader.cancel()
            }
        }
    }

    /** Main thread, no download running: deletes every clip, reloads the sounds, then `done`. */
    private fun deleteAll(a: Activity, done: Runnable?) {
        val app = a.applicationContext
        SoundCache.clear(app)
        Sfx.reload(app)
        if (!a.isFinishing && !a.isDestroyed) done?.run()
    }

    private fun dismiss(d: Dialog) {
        try {
            d.dismiss()
        } catch (ignored: RuntimeException) {
            // window already gone
        }
    }
}
