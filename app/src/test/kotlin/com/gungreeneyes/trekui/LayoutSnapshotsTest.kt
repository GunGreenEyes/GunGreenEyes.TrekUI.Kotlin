package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import java.io.File

/** [LayoutSnapshots]: file naming, rotation, upgrade detection and duplicate detection. */
class LayoutSnapshotsTest {

    @Test
    fun fileNameRoundTrips() {
        val t = 1_763_400_000_123L
        val name = LayoutSnapshots.fileName(t, LayoutSnapshots.R_IMPORT)
        assertThat(name).endsWith("_import.json")
        val e = LayoutSnapshots.parse(File(name))
        assertThat(e).isNotNull()
        assertThat(e!!.time).isEqualTo(t)
        assertThat(e.reason).isEqualTo(LayoutSnapshots.R_IMPORT)
        assertThat(e.reasonLabel()).isEqualTo("BEFORE IMPORT")
    }

    @Test
    fun foreignFilesAreNotSnapshots() {
        for (n in arrayOf(
            "notes.txt", "layout.json", "20261117-153012-345.json", "20261117-153012-345_.json",
            "2026111-153012-345_import.json", "20261317-153012-345_import.json", "trekui-layout-20261117.json",
        )) {
            assertThat(LayoutSnapshots.parse(File(n))).isNull()
        }
    }

    @Test
    fun namesSortChronologically() {
        val base = 1_700_000_000_000L
        val names = longArrayOf(5_000, 50, 86_400_000L * 40, 0, 999).map { LayoutSnapshots.fileName(base + it, "manual") }
        val sorted = names.sorted()
        assertThat(sorted[0]).isEqualTo(LayoutSnapshots.fileName(base, "manual"))
        assertThat(sorted[4]).isEqualTo(LayoutSnapshots.fileName(base + 86_400_000L * 40, "manual"))
    }

    @Test
    fun rotationDeletesOnlyTheOldest() {
        val names = (0 until 13).map { LayoutSnapshots.fileName(1_700_000_000_000L + it * 1000L, "interface") }.shuffled()
        val doomed = LayoutSnapshots.rotation(names, LayoutSnapshots.KEEP)
        assertThat(doomed).hasSize(3)
        assertThat(doomed).containsExactly(
            LayoutSnapshots.fileName(1_700_000_000_000L, "interface"),
            LayoutSnapshots.fileName(1_700_000_001_000L, "interface"),
            LayoutSnapshots.fileName(1_700_000_002_000L, "interface"),
        ).inOrder()
        assertThat(LayoutSnapshots.rotation(names.subList(0, 4), LayoutSnapshots.KEEP)).isEmpty()
        assertThat(LayoutSnapshots.rotation(listOf("a", "b"), 0)).containsExactly("a", "b")
        assertThat(LayoutSnapshots.rotation(ArrayList(), 5)).isEmpty()
    }

    @Test
    fun namesAreUtcAndLegacyLocalNamesStillParse() {
        val t = 1_763_400_000_123L
        assertThat(LayoutSnapshots.fileName(t, "manual")).isEqualTo("20251117-172000-123Z_manual.json")
        val legacy = legacyName(t, "manual")
        assertThat(LayoutSnapshots.parse(File(legacy))!!.time).isEqualTo(t)
    }

    @Test
    fun rotationOrdersByCreationTimeAcrossFormats() {
        val base = 1_700_000_000_000L
        val legacy = legacyName(base, "reset")
        val newer = LayoutSnapshots.fileName(base + 60_000L, "reset")
        assertThat(LayoutSnapshots.rotation(listOf(newer, legacy), 1)).containsExactly(legacy)
    }

    /** A pre-UTC snapshot name: local-time stamp, no `Z`. */
    private fun legacyName(time: Long, reason: String): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US).format(java.util.Date(time)) +
            "_" + reason + ".json"

    @Test
    fun upgradeSnapshotOnlyWhenTheVersionChanged() {
        assertThat(LayoutSnapshots.needsUpgradeSnapshot(60, 61, true)).isTrue()
        assertThat(LayoutSnapshots.needsUpgradeSnapshot(61, 61, true)).isFalse()
        assertThat(LayoutSnapshots.needsUpgradeSnapshot(62, 61, true)).isTrue() // downgrade too
        // never stored: fresh install vs. upgrade from a build before this check
        assertThat(LayoutSnapshots.needsUpgradeSnapshot(-1, 61, false)).isFalse()
        assertThat(LayoutSnapshots.needsUpgradeSnapshot(-1, 61, true)).isTrue()
    }

    @Test
    fun sameLayoutIgnoresTimestamps() {
        val prefs = hashMapOf<String, Any>("faction" to 2, "tiles" to 6)
        val a = LayoutArchive.encode(prefs, 60, "6.1", 2, 1L)
        val b = LayoutArchive.encode(prefs, 61, "6.2", 2, 999L)
        assertThat(LayoutSnapshots.sameLayout(a, b)).isTrue()
        prefs["tiles"] = 9
        assertThat(LayoutSnapshots.sameLayout(a, LayoutArchive.encode(prefs, 61, "6.2", 2, 999L))).isFalse()
        assertThat(LayoutSnapshots.sameLayout(a, null)).isFalse()
        assertThat(LayoutSnapshots.sameLayout(a, JSONObject())).isFalse()
    }
}
