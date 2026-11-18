package com.gungreeneyes.trekui

import java.io.File

/**
 * Sound-library ids renamed so product text says Federation, Empire and Collective (TUK-T20). A user
 * who downloaded the library or picked one of these clips before the rename keeps it:
 *  - a stored id is read through [current] (Sfx.builtinFor, the per-event library choice);
 *  - the first use of the download cache ([SoundCache.dir]) renames `<oldId>.wav` to `<newId>.wav`
 *    ([migrateCache]);
 *  - a personal develop build's res/raw clip still named by the old id is found through [previous].
 * The old ids appear only here.
 */
object SoundIdMigration {
    /** Old id -> new id. */
    @JvmField
    val RENAMED: Map<String, String> = linkedMapOf(
        "alt_borg_computer" to "alt_collective_computer",
        "alt_klingon_transporter" to "alt_empire_transporter",
        "tk_cvox_priority_one_message_from_starfleet" to "tk_cvox_priority_one_message_from_federation"
    )

    private val PREVIOUS: Map<String, String> = RENAMED.entries.associate { (old, new) -> new to old }

    @Volatile
    private var cacheMigrated = false

    /** The id to use for a stored one: a renamed id gives its new name, any other comes back as is. */
    @JvmStatic
    fun current(id: String?): String? = if (id == null) null else RENAMED[id] ?: id

    /** The id a renamed sound had before the rename, or null for any other id. */
    @JvmStatic
    fun previous(id: String?): String? = if (id == null) null else PREVIOUS[id]

    /**
     * Renames every cached `<oldId>.wav` in [dir] to `<newId>.wav`. When both exist (the library was
     * downloaded again after the update) the new file is kept and the old one deleted. Returns the
     * number of files renamed; a second run finds nothing to do.
     */
    @JvmStatic
    fun migrateCache(dir: File): Int {
        var renamed = 0
        for ((old, new) in RENAMED) {
            val from = File(dir, "$old.wav")
            val to = File(dir, "$new.wav")
            if (from.isFile && to.exists()) {
                from.delete()
            } else if (from.isFile && from.renameTo(to)) {
                renamed++
            }
        }
        return renamed
    }

    /** [migrateCache] once per process, the first time the download cache folder is asked for. */
    @JvmStatic
    fun migrateCacheOnce(dir: File) {
        if (cacheMigrated) return
        synchronized(this) {
            if (!cacheMigrated) {
                cacheMigrated = true
                migrateCache(dir)
            }
        }
    }
}
