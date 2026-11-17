package com.gungreeneyes.trekui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The flows behind CONFIG › BACKUP: EXPORT LAYOUT and IMPORT LAYOUT through the Storage Access
 * Framework, SNAPSHOT NOW, UNDO LAST CHANGE and RESTORE of an automatic {@link LayoutSnapshots} entry.
 *
 * <p>Every import and restore is read and validated on a worker thread, then shown as a preview
 * (interface, settings count, assigned tiles, apps missing on this device, credentials left out)
 * with APPLY / CANCEL; nothing changes until APPLY, and APPLY first snapshots the current layout so it
 * can be undone. {@link SettingsActivity} owns the section rows and forwards its activity results to
 * {@link #onResult}.
 */
final class LayoutBackupUi {
    /** Activity request codes (SettingsActivity forwards them). */
    static final int REQ_EXPORT = 330, REQ_IMPORT = 331;

    private static final ExecutorService WORK = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "layout-backup");
        t.setDaemon(true);
        return t;
    });

    private LayoutBackupUi() {}

    /** Suggested export file name, e.g. {@code console-layout-20261117.json}. */
    static String exportFileName(long time) {
        return "trekui-layout-" + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(time)) + ".json";
    }

    /** Opens the system "save as" picker for a layout file. */
    static void startExport(Activity a) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(LayoutArchive.MIME)
                .putExtra(Intent.EXTRA_TITLE, exportFileName(System.currentTimeMillis()));
        start(a, i, REQ_EXPORT);
    }

    /** Opens the system file picker for a layout file. */
    static void startImport(Activity a) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{LayoutArchive.MIME, "text/plain", "application/octet-stream"});
        start(a, i, REQ_IMPORT);
    }

    private static void start(Activity a, Intent i, int req) {
        try {
            a.startActivityForResult(i, req);
        } catch (RuntimeException e) {
            toast(a, a.getString(R.string.toast_no_file_picker));
        }
    }

    /**
     * Handles the SAF result for {@link #REQ_EXPORT} / {@link #REQ_IMPORT}.
     *
     * @param changed run on the UI thread after a layout was applied (rebuilds CONFIG, bumps home)
     * @return true when the request code was one of ours
     */
    static boolean onResult(final Activity a, int requestCode, int resultCode, Intent data, final Runnable changed) {
        if (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT) return false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        final Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            final String text = LayoutArchive.capture(a).toString();
            WORK.execute(() -> {
                boolean ok;
                try {
                    LayoutArchive.write(a, uri, text);
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                }
                final boolean done = ok;
                ui(a, () -> {
                    Sfx.play(a, done ? Sfx.STATUS_OK : Sfx.ERROR);
                    toast(a, a.getString(done ? R.string.toast_layout_exported : R.string.toast_layout_export_failed));
                });
            });
        } else {
            WORK.execute(() -> {
                try {
                    LayoutArchive.Parsed parsed = LayoutArchive.parse(LayoutArchive.read(a, uri));
                    int missing = LayoutArchive.clearMissingApps(parsed.prefs, pkg -> LayoutArchive.installed(a, pkg));
                    ui(a, () -> confirm(a, a.getString(R.string.layout_import_title), parsed, missing,
                            LayoutSnapshots.R_IMPORT, changed));
                } catch (LayoutArchive.InvalidLayoutException e) {
                    ui(a, () -> refuse(a, e.getMessage()));
                } catch (Exception e) {
                    ui(a, () -> refuse(a, a.getString(R.string.layout_unreadable)));
                }
            });
        }
        return true;
    }

    /** Snapshots the layout now (SNAPSHOT NOW). */
    static void snapshotNow(Activity a, Runnable changed) {
        LayoutSnapshots.snapshot(a, LayoutSnapshots.R_MANUAL);
        Sfx.play(a, Sfx.CONFIRM);
        toast(a, a.getString(R.string.toast_layout_snapshot));
        // the list refreshes once the write is done
        WORK.execute(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ui(a, changed);
        });
    }

    /** UNDO LAST CHANGE: previews the newest snapshot that differs from the current layout. */
    static void undo(final Activity a, final Runnable changed) {
        final org.json.JSONObject now = LayoutArchive.capture(a);
        WORK.execute(() -> {
            for (LayoutSnapshots.Entry e : LayoutSnapshots.list(a)) {
                if (LayoutSnapshots.sameLayout(now, LayoutSnapshots.readDoc(e.file))) continue;
                loadAndConfirm(a, e, changed);
                return;
            }
            ui(a, () -> refuse(a, a.getString(R.string.layout_nothing_to_undo)));
        });
    }

    /** RESTORE on one snapshot row: previews it, then applies on confirm. */
    static void restore(final Activity a, final LayoutSnapshots.Entry e, final Runnable changed) {
        WORK.execute(() -> loadAndConfirm(a, e, changed));
    }

    /** Worker thread: loads a snapshot and shows its preview. */
    private static void loadAndConfirm(final Activity a, final LayoutSnapshots.Entry e, final Runnable changed) {
        try {
            final LayoutArchive.Parsed parsed = LayoutSnapshots.load(e);
            final int missing = LayoutArchive.clearMissingApps(parsed.prefs, pkg -> LayoutArchive.installed(a, pkg));
            ui(a, () -> confirm(a, a.getString(R.string.layout_restore_title, e.reasonLabel()), parsed, missing,
                    LayoutSnapshots.R_RESTORE, changed));
        } catch (LayoutArchive.InvalidLayoutException ex) {
            ui(a, () -> refuse(a, ex.getMessage()));
        }
    }

    /** One line for a snapshot row: "17 NOV 2026 15:30 • BEFORE IMPORT". */
    static String rowLabel(LayoutSnapshots.Entry e) {
        return when(e.time) + " • " + e.reasonLabel();
    }

    private static String when(long time) {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(time)).toUpperCase(Locale.getDefault());
    }

    /** The preview text for a layout about to be applied. */
    static String preview(Activity a, LayoutArchive.Parsed parsed, int missing) {
        Map<String, Object> prefs = parsed.prefs;
        StringBuilder sb = new StringBuilder();
        sb.append(a.getString(R.string.layout_preview_interface, LayoutArchive.interfaceName(LayoutArchive.factionOf(prefs))));
        sb.append('\n').append(a.getString(R.string.layout_preview_counts, prefs.size(), LayoutArchive.assignedTiles(prefs)));
        if (parsed.createdAt > 0) {
            sb.append('\n').append(a.getString(R.string.layout_preview_saved, when(parsed.createdAt),
                    parsed.appVersion.isEmpty() ? "?" : parsed.appVersion));
        }
        if (missing > 0) sb.append('\n').append(a.getString(R.string.layout_preview_missing, missing));
        if (parsed.filtered > 0) sb.append('\n').append(a.getString(R.string.layout_preview_filtered, parsed.filtered));
        if (parsed.sourceSchema < LayoutArchive.SCHEMA) sb.append('\n').append(a.getString(R.string.layout_preview_migrated, parsed.sourceSchema));
        sb.append("\n\n").append(a.getString(R.string.layout_preview_footer));
        return sb.toString();
    }

    /** Preview with APPLY / CANCEL; APPLY snapshots the current layout ({@code reason}) then applies. */
    private static void confirm(final Activity a, String title, final LayoutArchive.Parsed parsed, int missing,
                                final String reason, final Runnable changed) {
        Sfx.play(a, Sfx.ALERT);
        ConsoleDialog.message(a, title, preview(a, parsed, missing),
                new String[]{a.getString(R.string.layout_apply), a.getString(R.string.layout_cancel)}, which -> {
                    if (which != 0) {
                        Sfx.play(a, Sfx.CANCEL);
                        return;
                    }
                    LayoutSnapshots.snapshot(a, reason);
                    LayoutArchive.apply(a, parsed.prefs);
                    Prefs.get(a).palette(); // syncs Skin.faction with the applied interface
                    Sfx.reloadProfile(a);
                    Sfx.play(a, Sfx.STATUS_OK);
                    toast(a, a.getString(R.string.toast_layout_applied));
                    changed.run();
                });
    }

    private static void refuse(Activity a, String why) {
        Sfx.play(a, Sfx.DENY);
        Dialog d = ConsoleDialog.message(a, a.getString(R.string.layout_refused_title), why,
                new String[]{a.getString(R.string.layout_ok)}, null);
        d.setCanceledOnTouchOutside(true);
    }

    /** Snapshot rows for CONFIG (folder listing only). */
    static List<LayoutSnapshots.Entry> snapshots(Activity a) { return LayoutSnapshots.list(a); }

    private static void ui(final Activity a, final Runnable r) {
        a.runOnUiThread(() -> {
            if (!a.isFinishing() && !a.isDestroyed()) r.run();
        });
    }

    private static void toast(Activity a, String msg) {
        Toast.makeText(a, msg, Toast.LENGTH_SHORT).show();
    }
}
