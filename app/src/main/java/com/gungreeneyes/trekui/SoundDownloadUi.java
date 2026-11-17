package com.gungreeneyes.trekui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

/**
 * The dialogs behind SETTINGS › AUDIO › DOWNLOAD SOUND LIBRARY: an explanation with the download
 * size and where the files come from, a live progress dialog with CANCEL, the result, and DELETE
 * DOWNLOADED SOUNDS. All work happens in {@link SoundDownloader}; this class only talks to the user.
 * The settings screen calls {@link #status}, {@link #start} and {@link #confirmDelete}.
 */
final class SoundDownloadUi {
    private SoundDownloadUi() {}

    /** One-line state for the settings note, e.g. "112 OF 425 SOUNDS DOWNLOADED • 9.8 MB". */
    static String status(Context c) {
        SoundCatalog cat = SoundCatalog.load(c);
        int avail = cat.available(false).size();
        int have = SoundCache.count(c);
        if (avail == 0) return c.getString(R.string.sound_dl_none_listed);
        if (have == 0) {
            return c.getString(R.string.sound_dl_status_empty, avail);
        }
        return c.getString(R.string.sound_dl_status, have, avail, mb(SoundCache.bytes(c)));
    }

    private static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    /**
     * Explains what will happen, then downloads with a progress dialog.
     *
     * @param coreOnly only the sounds the interfaces use by default
     * @param done     run on the main thread after the result dialog is dismissed (e.g. rebuild the list)
     */
    static void start(final Activity a, final boolean coreOnly, final Runnable done) {
        if (SoundDownloader.isRunning()) {
            showProgress(a, done);
            return;
        }
        SoundCatalog cat = SoundCatalog.load(a);
        List<SoundCatalog.Sound> want = cat.available(coreOnly);
        if (want.isEmpty()) {
            ConsoleDialog.message(a, a.getString(R.string.sound_dl_title_list), a.getString(R.string.sound_dl_none_listed_short),
                    new String[]{a.getString(R.string.sound_dl_ok)}, null);
            return;
        }
        String msg = a.getString(R.string.sound_dl_explain,
                want.size(), mb(SoundCatalog.downloadBytes(want)), mb(SoundCatalog.estimatedCacheBytes(want)));
        ConsoleDialog.message(a, a.getString(coreOnly ? R.string.sound_dl_title_core : R.string.sound_dl_title_library), msg,
                new String[]{a.getString(R.string.sound_dl_download), a.getString(R.string.sound_dl_cancel)},
                new ConsoleDialog.OnPick() {
                    @Override public void pick(int which) {
                        if (which != 0) return;
                        showProgress(a, done);
                        SoundDownloader.start(a, coreOnly, null);
                    }
                });
    }

    /** Shows (or re-attaches to) the progress dialog of the running download. */
    private static void showProgress(final Activity a, final Runnable done) {
        Palette p = Prefs.get(a).palette();
        final ConsoleDialog.Built b = ConsoleDialog.base(a, a.getString(R.string.sound_dl_title_progress));
        final TextView line = Ui.text(a, a.getString(R.string.sound_dl_connecting), 16, p.textHi);
        line.setPadding(Ui.dp(a, Ui.XS), 0, Ui.dp(a, Ui.XS), Ui.dp(a, Ui.L));
        b.body.addView(line);
        ConsoleDialog.addOption(b, ConsoleDialog.option(a, a.getString(R.string.sound_dl_cancel), p.c(3), new View.OnClickListener() {
            @Override public void onClick(View v) {
                SoundDownloader.cancel();
                line.setText(a.getString(R.string.sound_dl_stopping));
            }
        }));
        b.dialog.setCancelable(false);
        b.dialog.show();
        SoundDownloader.setListener(new SoundDownloader.Listener() {
            @Override public void progress(int doneCount, int total, String label) {
                line.setText(String.format(Locale.US, "%d / %d\n%s", doneCount, total, label));
            }

            @Override public void finished(SoundDownloader.Result r) {
                SoundDownloader.setListener(null);
                dismiss(b.dialog);
                if (a.isFinishing() || a.isDestroyed()) return;
                Sfx.play(a, r.failed == 0 && !r.cancelled ? Sfx.CONFIRM : Sfx.DENY);
                ConsoleDialog.message(a, a.getString(r.cancelled ? R.string.sound_dl_stopped : R.string.sound_dl_complete), r.summary(),
                        new String[]{a.getString(R.string.sound_dl_ok)},
                        new ConsoleDialog.OnPick() {
                            @Override public void pick(int which) {
                                if (done != null) done.run();
                            }
                        });
            }
        });
    }

    /** Asks, then deletes every downloaded clip and reloads the sounds (events fall back to silence). */
    static void confirmDelete(final Activity a, final Runnable done) {
        ConsoleDialog.message(a, a.getString(R.string.sound_dl_delete_title), a.getString(R.string.sound_dl_delete_message,
                SoundCache.count(a), mb(SoundCache.bytes(a))),
                new String[]{a.getString(R.string.sound_dl_delete), a.getString(R.string.sound_dl_cancel)}, new ConsoleDialog.OnPick() {
                    @Override public void pick(int which) {
                        if (which != 0) return;
                        if (SoundDownloader.isRunning()) SoundDownloader.cancel();
                        SoundCache.clear(a);
                        Sfx.reload(a);
                        if (done != null) done.run();
                    }
                });
    }

    private static void dismiss(Dialog d) {
        try {
            d.dismiss();
        } catch (RuntimeException ignored) {
            // window already gone
        }
    }
}
