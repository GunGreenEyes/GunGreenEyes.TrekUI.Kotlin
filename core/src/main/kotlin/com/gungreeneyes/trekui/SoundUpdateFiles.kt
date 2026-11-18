package com.gungreeneyes.trekui

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

/**
 * The app-private files of the sound update channel ([SoundUpdate]): the accepted manifest, the
 * record of which owned sounds (id = SHA-256) were downloaded into [SoundCache], and the bundled
 * assets' hashes. Writes are atomic (temp file,
 * then rename), so an interrupted write never leaves a half file behind.
 */
object SoundUpdateFiles {
    /** Folder under filesDir. */
    const val DIR = "sfx_update"

    private const val APPLIED_NAME = "applied.properties"

    @JvmStatic
    fun dir(c: Context): File = File(c.filesDir, DIR)

    /** Where the accepted channel manifest is saved ([SoundCatalog.load] merges it). */
    @JvmStatic
    fun manifestFile(c: Context): File = File(dir(c), SoundUpdate.MANIFEST_NAME)

    /** SHA-256 of the bundled synthesized asset for this id, or null when the build has none. */
    @JvmStatic
    fun bundledAssetSha(c: Context, id: String): String? {
        if (!SoundCache.hasAsset(c, id)) return null
        return try {
            c.assets.open(SoundCache.assetPath(id)).use { SoundProcessor.sha256Hex(it) }
        } catch (ignored: IOException) {
            null
        }
    }

    /** Owned sounds downloaded so far: id to the SHA-256 that was downloaded (empty when unreadable). */
    @JvmStatic
    fun loadApplied(c: Context): Properties {
        val p = Properties()
        val f = File(dir(c), APPLIED_NAME)
        if (f.isFile) {
            try {
                FileInputStream(f).use { p.load(it) }
            } catch (ignored: IOException) {
                p.clear() // unreadable record: everything is fetched again
            } catch (ignored: IllegalArgumentException) {
                p.clear()
            }
        }
        return p
    }

    @Throws(IOException::class)
    @JvmStatic
    fun saveApplied(c: Context, p: Properties) {
        val f = File(dir(c), APPLIED_NAME)
        if (p.isEmpty) {
            f.delete()
            return
        }
        if (!dir(c).isDirectory && !dir(c).mkdirs()) throw IOException("cannot create " + DIR)
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { p.store(it, null) }
        replace(tmp, f)
    }

    @Throws(IOException::class)
    @JvmStatic
    fun writeAtomically(f: File, bytes: ByteArray) {
        val parent = f.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) throw IOException("cannot create " + parent.name)
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes) }
        replace(tmp, f)
    }

    @Throws(IOException::class)
    private fun replace(tmp: File, f: File) {
        if (f.exists() && !f.delete()) {
            tmp.delete()
            throw IOException("cannot replace " + f.name)
        }
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw IOException("cannot write " + f.name)
        }
    }
}
