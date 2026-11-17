package com.gungreeneyes.trekui;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * Makes console library clips (downloaded, bundled or imported) the phone's own ringtone,
 * notification and alarm sounds.
 * The clip is decoded on the phone, given console spacing for its role (ringtones double-chirp
 * then pause, the alarm loops like the ship's klaxon), saved to the shared Ringtones /
 * Notifications / Alarms folders through MediaStore and, with "Modify system settings",
 * set as the default. The previous default is remembered so it can be restored.
 */
final class PhoneSounds {
    static final int RING = 0, NOTE = 1, ALARM = 2;
    static final String[] NAMES = {"RINGTONE", "NOTIFICATIONS", "ALARM"};
    private static final int[] TYPES = {RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_ALARM};
    private static final String[] DEFAULTS = {"comms_hail", "comms_hail", "alert_red_tng"};
    /** Fallbacks from the launcher's own synthesized set when the library is not downloaded. */
    private static final String[] SYNTH_DEFAULTS = {"synth_comms", "synth_comms", "synth_red_alert"};
    private static final String[] FOLDERS = {"Ringtones/", "Notifications/", "Alarms/"};

    private PhoneSounds() {}

    /** Every clip in the sound library (bundled and imported). */
    static String[] choices(Context c) {
        List<String> out = new java.util.ArrayList<>();
        for (SoundLibrary.Entry e : SoundLibrary.all(c)) if (!Sfx.isRotation(e.id)) out.add(e.id);
        return out.toArray(new String[0]);
    }

    /**
     * The clip chosen for this phone sound type; if that one is gone (not downloaded, import
     * deleted) the type's default, else its synthesized default, else the first clip in the
     * library, else the default id
     * (which {@link #install} then reports as not available).
     */
    static String clip(Context c, int t) {
        String s = Prefs.get(c).s("phoneSnd" + t, DEFAULTS[t]);
        if (SoundLibrary.exists(c, s)) return s;
        if (SoundLibrary.exists(c, DEFAULTS[t])) return DEFAULTS[t];
        if (SoundLibrary.exists(c, SYNTH_DEFAULTS[t])) return SYNTH_DEFAULTS[t];
        String[] all = choices(c);
        return all.length == 0 ? DEFAULTS[t] : all[0];
    }

    static void setClip(Context c, int t, String res) { Prefs.get(c).put("phoneSnd" + t, res); }

    /** Android 10+ and at least one clip in the library (downloaded, bundled or imported). */
    static boolean available(Context c) { return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !SoundLibrary.all(c).isEmpty(); }

    static boolean canSetDefault(Context c) { return Settings.System.canWrite(c); }

    static boolean hasPrevious(Context c, int t) { return Prefs.get(c).s("phonePrev" + t, null) != null; }

    static boolean isOurs(Context c, int t) {
        try {
            Uri u = RingtoneManager.getActualDefaultRingtoneUri(c, TYPES[t]);
            return u != null && u.toString().equals(Prefs.get(c).s("phoneUri" + t, ""));
        } catch (Exception e) {
            return false;
        }
    }

    /** Arrange the clip for its role. Pure Java so it can be unit-tested. */
    static float[] arrange(float[] x, int sr, int t) {
        float clipSec = x.length / (float) sr;
        float[][] parts;
        if (t == RING) {
            // short chirps ring twice like an incoming hail; longer clips once, then a pause before Android repeats it
            if (clipSec < 0.8f) parts = new float[][]{x, silence(sr, 0.12f), x, silence(sr, 1.6f)};
            else parts = new float[][]{x, silence(sr, 1.2f)};
        } else if (t == ALARM) {
            parts = new float[][]{x, silence(sr, clipSec < 0.8f ? 0.6f : 0.35f)};
        } else {
            parts = new float[][]{x};
        }
        int n = 0;
        for (float[] p : parts) n += p.length;
        float[] y = new float[n];
        int o = 0;
        for (float[] p : parts) { System.arraycopy(p, 0, y, o, p.length); o += p.length; }
        return y;
    }

    private static float[] silence(int sr, float sec) { return new float[Math.round(sr * sec)]; }

    static String fileName(Context c, int t) { return titleFor(c, clip(c, t)); }

    static String titleFor(Context c, String clip) {
        String label = SoundLibrary.label(c, clip).replace("VOICE: ", "");
        StringBuilder sb = new StringBuilder("TrekUI ");
        for (String w : label.toLowerCase(java.util.Locale.US).split(" ")) {
            if (w.isEmpty()) continue;
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        }
        return sb.toString().trim().replace("-on", "-On").replace("-off", "-Off");
    }

    /**
     * Saves the arranged clip to the shared folder and, if allowed, makes it the default.
     * @return true if it was set as the default, false if only saved (no permission)
     */
    static boolean install(Context c, int t) throws IOException { return install(c, t, true); }

    /** @param setDefault false = only save it to the shared folder (to pick per app) */
    static boolean install(Context c, int t, boolean setDefault) throws IOException {
        return install(c, t, setDefault, clip(c, t));
    }

    /** Exports any library clip (bundled or imported) as this kind of phone sound. */
    static boolean install(Context c, int t, boolean setDefault, String res) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw new IOException("needs Android 10 or newer");
        if (!SoundLibrary.exists(c, res) || Sfx.isRotation(res)) throw new IOException("sound not available");
        File src;
        boolean temp = false;
        if (res.startsWith(SoundLibrary.PREFIX)) {
            src = SoundLibrary.file(c, res);
        } else if (SoundCache.isCached(c, res)) {
            src = SoundCache.file(c, res);
        } else if (SoundCache.where(c, res) == SoundCache.ASSET) {
            src = new File(c.getCacheDir(), "phone_src.ogg");
            temp = true;
            try (InputStream in = c.getAssets().open(SoundCache.assetPath(res)); OutputStream os = new FileOutputStream(src)) {
                copy(in, os);
            }
        } else {
            src = new File(c.getCacheDir(), "phone_src.ogg");
            temp = true;
            int rawId = SoundCache.rawId(c, res);
            if (rawId == 0) throw new IOException("sound not available");
            try (InputStream in = c.getResources().openRawResource(rawId); OutputStream os = new FileOutputStream(src)) {
                copy(in, os);
            }
        }
        int[] sr = new int[1];
        float[] x = SoundImporter.decode(c, new SoundImporter.Item(res, null, src), sr);
        float[] y = arrange(x, sr[0], t);
        File wav = new File(c.getCacheDir(), "phone_out.wav");
        SoundImporter.writeWav(wav, y, sr[0]);
        if (temp) src.delete();

        String title = titleFor(c, res);
        ContentResolver cr = c.getContentResolver();
        Uri coll = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, title + ".wav");
        cv.put(MediaStore.MediaColumns.TITLE, title);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDERS[t]);
        cv.put(MediaStore.Audio.Media.IS_RINGTONE, t == RING ? 1 : 0);
        cv.put(MediaStore.Audio.Media.IS_NOTIFICATION, t == NOTE ? 1 : 0);
        cv.put(MediaStore.Audio.Media.IS_ALARM, t == ALARM ? 1 : 0);
        cv.put(MediaStore.Audio.Media.IS_MUSIC, 0);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = cr.insert(coll, cv);
        if (u == null) throw new IOException("could not create the sound file");
        try (InputStream in = new FileInputStream(wav); OutputStream os = cr.openOutputStream(u)) {
            if (os == null) throw new IOException("could not write the sound file");
            copy(in, os);
        }
        wav.delete();
        cv.clear();
        cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(u, cv, null, null);
        removeOldCopies(c, t, u, title + ".wav");

        Prefs p = Prefs.get(c);
        if (!setDefault || !canSetDefault(c)) return false;
        if (!isOurs(c, t) && p.s("phonePrev" + t, null) == null) {
            Uri prev = RingtoneManager.getActualDefaultRingtoneUri(c, TYPES[t]);
            p.put("phonePrev" + t, prev == null ? "" : prev.toString());
        }
        RingtoneManager.setActualDefaultRingtoneUri(c, TYPES[t], u);
        p.put("phoneUri" + t, u.toString());
        return true;
    }

    /** Puts back the sound that was the default before console replaced it. */
    static boolean restore(Context c, int t) {
        Prefs p = Prefs.get(c);
        String prev = p.s("phonePrev" + t, null);
        if (prev == null || !canSetDefault(c)) return false;
        RingtoneManager.setActualDefaultRingtoneUri(c, TYPES[t], prev.isEmpty() ? null : Uri.parse(prev));
        p.put("phonePrev" + t, (String) null);
        p.put("phoneUri" + t, (String) null);
        return true;
    }

    /**
     * Deletes the earlier copy of this same sound in the folder (only files this app created can be
     * deleted). Other console sounds stay, so several can be picked per app.
     */
    private static void removeOldCopies(Context c, int t, Uri keep, String name) {
        ContentResolver cr = c.getContentResolver();
        Uri coll = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        try (Cursor cur = cr.query(coll, new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                new String[]{FOLDERS[t], name}, null)) {
            if (cur == null) return;
            while (cur.moveToNext()) {
                Uri u = android.content.ContentUris.withAppendedId(coll, cur.getLong(0));
                if (u.equals(keep)) continue;
                try { cr.delete(u, null, null); } catch (Exception ignored) { /* not ours */ }
            }
        } catch (Exception ignored) {
        }
    }

    private static void copy(InputStream in, OutputStream os) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
    }
}
