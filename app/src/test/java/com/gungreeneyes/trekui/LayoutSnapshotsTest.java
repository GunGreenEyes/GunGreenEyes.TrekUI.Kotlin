package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** {@link LayoutSnapshots}: file naming, rotation, upgrade detection and duplicate detection. */
public class LayoutSnapshotsTest {

    @Test
    public void fileNameRoundTrips() {
        long t = 1_763_400_000_123L;
        String name = LayoutSnapshots.fileName(t, LayoutSnapshots.R_IMPORT);
        assertThat(name).endsWith("_import.json");
        LayoutSnapshots.Entry e = LayoutSnapshots.parse(new File(name));
        assertThat(e).isNotNull();
        assertThat(e.time).isEqualTo(t);
        assertThat(e.reason).isEqualTo(LayoutSnapshots.R_IMPORT);
        assertThat(e.reasonLabel()).isEqualTo("BEFORE IMPORT");
    }

    @Test
    public void foreignFilesAreNotSnapshots() {
        for (String n : new String[]{"notes.txt", "layout.json", "20261117-153012-345.json", "20261117-153012-345_.json",
                "2026111-153012-345_import.json", "20261317-153012-345_import.json", "trekui-layout-20261117.json"}) {
            assertThat(LayoutSnapshots.parse(new File(n))).isNull();
        }
    }

    @Test
    public void namesSortChronologically() {
        List<String> names = new ArrayList<>();
        long base = 1_700_000_000_000L;
        for (long dt : new long[]{5_000, 50, 86_400_000L * 40, 0, 999}) names.add(LayoutSnapshots.fileName(base + dt, "manual"));
        List<String> sorted = new ArrayList<>(names);
        Collections.sort(sorted);
        assertThat(sorted.get(0)).isEqualTo(LayoutSnapshots.fileName(base, "manual"));
        assertThat(sorted.get(4)).isEqualTo(LayoutSnapshots.fileName(base + 86_400_000L * 40, "manual"));
    }

    @Test
    public void rotationDeletesOnlyTheOldest() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 13; i++) names.add(LayoutSnapshots.fileName(1_700_000_000_000L + i * 1000L, "interface"));
        Collections.shuffle(names);
        List<String> doomed = LayoutSnapshots.rotation(names, LayoutSnapshots.KEEP);
        assertThat(doomed).hasSize(3);
        assertThat(doomed).containsExactly(
                LayoutSnapshots.fileName(1_700_000_000_000L, "interface"),
                LayoutSnapshots.fileName(1_700_000_001_000L, "interface"),
                LayoutSnapshots.fileName(1_700_000_002_000L, "interface")).inOrder();
        assertThat(LayoutSnapshots.rotation(names.subList(0, 4), LayoutSnapshots.KEEP)).isEmpty();
        assertThat(LayoutSnapshots.rotation(Arrays.asList("a", "b"), 0)).containsExactly("a", "b");
        assertThat(LayoutSnapshots.rotation(new ArrayList<>(), 5)).isEmpty();
    }

    @Test
    public void upgradeSnapshotOnlyWhenTheVersionChanged() {
        assertThat(LayoutSnapshots.needsUpgradeSnapshot(60, 61, true)).isTrue();
        assertThat(LayoutSnapshots.needsUpgradeSnapshot(61, 61, true)).isFalse();
        assertThat(LayoutSnapshots.needsUpgradeSnapshot(62, 61, true)).isTrue(); // downgrade too
        // never stored: fresh install vs. upgrade from a build before this check
        assertThat(LayoutSnapshots.needsUpgradeSnapshot(-1, 61, false)).isFalse();
        assertThat(LayoutSnapshots.needsUpgradeSnapshot(-1, 61, true)).isTrue();
    }

    @Test
    public void sameLayoutIgnoresTimestamps() throws Exception {
        java.util.Map<String, Object> prefs = new java.util.HashMap<>();
        prefs.put("faction", 2);
        prefs.put("tiles", 6);
        JSONObject a = LayoutArchive.encode(prefs, 60, "6.1", 2, 1L);
        JSONObject b = LayoutArchive.encode(prefs, 61, "6.2", 2, 999L);
        assertThat(LayoutSnapshots.sameLayout(a, b)).isTrue();
        prefs.put("tiles", 9);
        assertThat(LayoutSnapshots.sameLayout(a, LayoutArchive.encode(prefs, 61, "6.2", 2, 999L))).isFalse();
        assertThat(LayoutSnapshots.sameLayout(a, null)).isFalse();
        assertThat(LayoutSnapshots.sameLayout(a, new JSONObject())).isFalse();
    }
}
