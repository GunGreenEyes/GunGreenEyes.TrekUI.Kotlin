package com.gungreeneyes.trekui

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Makes console library clips (downloaded, bundled or imported) the phone's own ringtone,
 * notification and alarm sounds.
 * The clip is decoded on the phone, given console spacing for its role (ringtones double-chirp
 * then pause, the alarm loops like the ship's klaxon), saved to the shared Ringtones /
 * Notifications / Alarms folders through MediaStore and, with "Modify system settings",
 * set as the default. The previous default is remembered so it can be restored.
 */
internal object PhoneSounds {
    const val RING = 0
    const val NOTE = 1
    const val ALARM = 2

    @JvmField
    val NAMES: Array<String> = arrayOf("RINGTONE", "NOTIFICATIONS", "ALARM")

    private val TYPES = intArrayOf(RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_ALARM)
    private val DEFAULTS = arrayOf("comms_hail", "comms_hail", "alert_red_tng")

    /** Fallbacks from the launcher's own synthesized set when the library is not downloaded. */
    private val SYNTH_DEFAULTS = arrayOf("synth_comms", "synth_comms", "synth_red_alert")
    private val FOLDERS = arrayOf("Ringtones/", "Notifications/", "Alarms/")

    /** Every clip in the sound library (bundled and imported). */
    @JvmStatic
    fun choices(c: Context): Array<String> =
        SoundLibrary.all(c).filter { !Sfx.isRotation(it.id) }.map { it.id }.toTypedArray()

    /**
     * The clip chosen for this phone sound type; if that one is gone (not downloaded, import
     * deleted) the type's default, else its synthesized default, else the first clip in the
     * library, else the default id (which [install] then reports as not available).
     */
    @JvmStatic
    fun clip(c: Context, t: Int): String {
        val s = Prefs.get(c).s("phoneSnd$t", DEFAULTS[t])
        if (s != null && SoundLibrary.exists(c, s)) return s
        if (SoundLibrary.exists(c, DEFAULTS[t])) return DEFAULTS[t]
        if (SoundLibrary.exists(c, SYNTH_DEFAULTS[t])) return SYNTH_DEFAULTS[t]
        val all = choices(c)
        return if (all.isEmpty()) DEFAULTS[t] else all[0]
    }

    @JvmStatic
    fun setClip(c: Context, t: Int, res: String?) = Prefs.get(c).put("phoneSnd$t", res)

    /** Android 10+ and at least one clip in the library (downloaded, bundled or imported). */
    @JvmStatic
    fun available(c: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && SoundLibrary.all(c).isNotEmpty()

    @JvmStatic
    fun canSetDefault(c: Context): Boolean = Settings.System.canWrite(c)

    @JvmStatic
    fun hasPrevious(c: Context, t: Int): Boolean = Prefs.get(c).s("phonePrev$t", null) != null

    @JvmStatic
    fun isOurs(c: Context, t: Int): Boolean = try {
        val u = RingtoneManager.getActualDefaultRingtoneUri(c, TYPES[t])
        u != null && u.toString() == Prefs.get(c).s("phoneUri$t", "")
    } catch (e: Exception) {
        false
    }

    /** Arrange the clip for its role. Pure JVM so it can be unit-tested. */
    @JvmStatic
    fun arrange(x: FloatArray, sr: Int, t: Int): FloatArray {
        val clipSec = x.size / sr.toFloat()
        val parts: Array<FloatArray> = when (t) {
            // short chirps ring twice like an incoming hail; longer clips once, then a pause before Android repeats it
            RING -> if (clipSec < 0.8f) {
                arrayOf(x, silence(sr, 0.12f), x, silence(sr, 1.6f))
            } else {
                arrayOf(x, silence(sr, 1.2f))
            }
            ALARM -> arrayOf(x, silence(sr, if (clipSec < 0.8f) 0.6f else 0.35f))
            else -> arrayOf(x)
        }
        val y = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) {
            System.arraycopy(p, 0, y, o, p.size)
            o += p.size
        }
        return y
    }

    private fun silence(sr: Int, sec: Float): FloatArray = FloatArray(Math.round(sr * sec))

    @JvmStatic
    fun fileName(c: Context, t: Int): String = titleFor(c, clip(c, t))

    @JvmStatic
    fun titleFor(c: Context, clip: String?): String {
        val label = SoundLibrary.label(c, clip).replace("VOICE: ", "")
        val sb = StringBuilder("TrekUI ")
        for (w in label.lowercase(Locale.US).split(" ")) {
            if (w.isEmpty()) continue
            sb.append(Character.toUpperCase(w[0])).append(w.substring(1)).append(' ')
        }
        return sb.toString().javaTrim().replace("-on", "-On").replace("-off", "-Off")
    }

    /**
     * Saves the arranged clip to the shared folder and, if allowed, makes it the default.
     *
     * @param setDefault false = only save it to the shared folder (to pick per app)
     * @param res        any library clip (bundled or imported) to export as this kind of phone sound
     * @return true if it was set as the default, false if only saved (no permission)
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun install(c: Context, t: Int, setDefault: Boolean = true, res: String = clip(c, t)): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IOException("needs Android 10 or newer")
        if (!SoundLibrary.exists(c, res) || Sfx.isRotation(res)) throw IOException("sound not available")
        val src: File
        var temp = false
        if (res.startsWith(SoundLibrary.PREFIX)) {
            src = SoundLibrary.file(c, res)
        } else if (SoundCache.isCached(c, res)) {
            src = SoundCache.file(c, res)
        } else if (SoundCache.where(c, res) == SoundCache.ASSET) {
            src = File(c.cacheDir, "phone_src.ogg")
            temp = true
            c.assets.open(SoundCache.assetPath(res)).use { input -> FileOutputStream(src).use { os -> copy(input, os) } }
        } else {
            src = File(c.cacheDir, "phone_src.ogg")
            temp = true
            val rawId = SoundCache.rawId(c, res)
            if (rawId == 0) throw IOException("sound not available")
            c.resources.openRawResource(rawId).use { input -> FileOutputStream(src).use { os -> copy(input, os) } }
        }
        val sr = IntArray(1)
        val x = SoundImporter.decode(c, SoundImporter.Item(res, null, src), sr)
        val y = arrange(x, sr[0], t)
        val wav = File(c.cacheDir, "phone_out.wav")
        SoundImporter.writeWav(wav, y, sr[0])
        if (temp) src.delete()

        val title = titleFor(c, res)
        val cr = c.contentResolver
        val coll = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val cv = ContentValues()
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, "$title.wav")
        cv.put(MediaStore.MediaColumns.TITLE, title)
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-wav")
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDERS[t])
        cv.put(MediaStore.Audio.Media.IS_RINGTONE, if (t == RING) 1 else 0)
        cv.put(MediaStore.Audio.Media.IS_NOTIFICATION, if (t == NOTE) 1 else 0)
        cv.put(MediaStore.Audio.Media.IS_ALARM, if (t == ALARM) 1 else 0)
        cv.put(MediaStore.Audio.Media.IS_MUSIC, 0)
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val u = cr.insert(coll, cv) ?: throw IOException("could not create the sound file")
        FileInputStream(wav).use { input ->
            cr.openOutputStream(u).use { os ->
                if (os == null) throw IOException("could not write the sound file")
                copy(input, os)
            }
        }
        wav.delete()
        cv.clear()
        cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
        cr.update(u, cv, null, null)

        val p = Prefs.get(c)
        if (!setDefault || !canSetDefault(c)) {
            // only saved: the copy a default still points at stays (see removeOldCopies)
            removeOldCopies(c, t, u, title)
            return false
        }
        if (!isOurs(c, t) && p.s("phonePrev$t", null) == null) {
            val prev = RingtoneManager.getActualDefaultRingtoneUri(c, TYPES[t])
            p.put("phonePrev$t", prev?.toString() ?: "")
        }
        RingtoneManager.setActualDefaultRingtoneUri(c, TYPES[t], u)
        p.put("phoneUri$t", u.toString())
        removeOldCopies(c, t, u, title)
        return true
    }

    /** Puts back the sound that was the default before console replaced it. */
    @JvmStatic
    fun restore(c: Context, t: Int): Boolean {
        val p = Prefs.get(c)
        val prev = p.s("phonePrev$t", null)
        if (prev == null || !canSetDefault(c)) return false
        RingtoneManager.setActualDefaultRingtoneUri(c, TYPES[t], if (prev.isEmpty()) null else Uri.parse(prev))
        p.put("phonePrev$t", null)
        p.put("phoneUri$t", null)
        return true
    }

    /**
     * Deletes the earlier copies of this same sound in the folder, including the "Title (N).wav" names
     * MediaStore gives clashing files (only files this app created can be deleted). A copy that is
     * currently a system default ringtone, notification or alarm sound is never deleted. Other console
     * sounds stay, so several can be picked per app.
     */
    @Suppress("NestedBlockDepth")
    private fun removeOldCopies(c: Context, t: Int, keep: Uri, title: String) {
        val cr = c.contentResolver
        val coll = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val inUse = HashSet<Long>()
        for (type in TYPES) {
            try {
                val d = RingtoneManager.getActualDefaultRingtoneUri(c, type) ?: continue
                d.lastPathSegment?.toLongOrNull()?.let { inUse.add(it) }
            } catch (ignored: Exception) {
            }
        }
        try {
            cr.query(
                coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME +
                    " LIKE ? ESCAPE '\\' AND " + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + "=?",
                arrayOf(FOLDERS[t], likePrefix(title), c.packageName), null,
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val u = ContentUris.withAppendedId(coll, id)
                    if (u == keep || id in inUse || !isCopyName(title, cur.getString(1))) continue
                    try {
                        cr.delete(u, null, null)
                    } catch (ignored: Exception) {
                        // not ours
                    }
                }
            }
        } catch (ignored: Exception) {
        }
    }

    /** A LIKE pattern for names starting with `title` (`\`, `%` and `_` escaped with `\`). */
    @JvmStatic
    fun likePrefix(title: String): String =
        title.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** True for "title.wav" or a MediaStore clash rename "title (N).wav". Pure, unit-tested. */
    @JvmStatic
    fun isCopyName(title: String, name: String?): Boolean {
        if (name == null || !name.startsWith(title) || !name.endsWith(".wav")) return false
        val mid = name.removePrefix(title).removeSuffix(".wav")
        return mid.isEmpty() || COPY_SUFFIX.matches(mid)
    }

    private val COPY_SUFFIX = Regex(" \\(\\d+\\)")

    @Throws(IOException::class)
    private fun copy(input: InputStream, os: OutputStream) {
        val buf = ByteArray(16384)
        var n: Int
        while (input.read(buf).also { n = it } > 0) os.write(buf, 0, n)
    }
}
