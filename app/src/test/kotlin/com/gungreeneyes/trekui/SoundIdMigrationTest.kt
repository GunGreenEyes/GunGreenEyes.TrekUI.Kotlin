package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** JVM tests for the renamed sound ids: stored ids map to the new names, cached clips are renamed once. */
class SoundIdMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun renamedIdsMapToTheNewNames() {
        assertThat(SoundIdMigration.RENAMED.values).containsExactly(
            "alt_collective_computer", "alt_empire_transporter", "tk_cvox_priority_one_message_from_federation"
        ).inOrder()
        for ((old, new) in SoundIdMigration.RENAMED) {
            assertThat(SoundIdMigration.current(old)).isEqualTo(new)
            assertThat(SoundIdMigration.current(new)).isEqualTo(new)
            assertThat(SoundIdMigration.previous(new)).isEqualTo(old)
        }
    }

    @Test
    fun otherIdsAreUnchanged() {
        assertThat(SoundIdMigration.current("ui_denied")).isEqualTo("ui_denied")
        assertThat(SoundIdMigration.current("lib:my_clip")).isEqualTo("lib:my_clip")
        assertThat(SoundIdMigration.current(null)).isNull()
        assertThat(SoundIdMigration.previous("ui_denied")).isNull()
        assertThat(SoundIdMigration.previous(null)).isNull()
    }

    @Test
    fun theLibraryUsesOnlyTheNewIds() {
        val library = Sfx.LIBRARY.toList()
        assertThat(library).containsAtLeastElementsIn(SoundIdMigration.RENAMED.values)
        assertThat(library).containsNoneIn(SoundIdMigration.RENAMED.keys)
    }

    @Test
    fun cachedClipsUnderOldIdsAreRenamed() {
        val dir = tmp.newFolder("sounds")
        for (old in SoundIdMigration.RENAMED.keys) File(dir, "$old.wav").writeText("clip of $old")
        File(dir, "ui_denied.wav").writeText("untouched")

        assertThat(SoundIdMigration.migrateCache(dir)).isEqualTo(SoundIdMigration.RENAMED.size)

        for ((old, new) in SoundIdMigration.RENAMED) {
            assertThat(File(dir, "$old.wav").exists()).isFalse()
            assertThat(File(dir, "$new.wav").readText()).isEqualTo("clip of $old")
        }
        assertThat(File(dir, "ui_denied.wav").readText()).isEqualTo("untouched")
        assertThat(SoundIdMigration.migrateCache(dir)).isEqualTo(0) // already done: nothing left to rename
    }

    @Test
    fun aClipAlreadyCachedUnderTheNewIdWins() {
        val dir = tmp.newFolder("sounds")
        val (old, new) = SoundIdMigration.RENAMED.entries.first().toPair()
        File(dir, "$old.wav").writeText("old download")
        File(dir, "$new.wav").writeText("new download")

        assertThat(SoundIdMigration.migrateCache(dir)).isEqualTo(0)

        assertThat(File(dir, "$old.wav").exists()).isFalse()
        assertThat(File(dir, "$new.wav").readText()).isEqualTo("new download")
    }

    @Test
    fun aMissingCacheFolderIsNotAnError() {
        assertThat(SoundIdMigration.migrateCache(File(tmp.root, "never_created"))).isEqualTo(0)
    }
}
