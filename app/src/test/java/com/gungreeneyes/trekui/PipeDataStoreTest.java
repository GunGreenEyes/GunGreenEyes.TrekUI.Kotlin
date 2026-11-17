package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** {@link PipeDataStore}: pure JSON helpers and the SharedPreferences round trip (Robolectric for org.json). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class PipeDataStoreTest {

    private PipeDataStore store;

    @Before
    public void setUp() {
        Context c = ApplicationProvider.getApplicationContext();
        c.getSharedPreferences(PipeDataStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        store = new PipeDataStore(c);
    }

    private static JSONObject rec(String id, String name) throws JSONException {
        JSONObject o = new JSONObject().put("name", name);
        if (id != null) o.put("id", id);
        return o;
    }

    private static String doc(JSONArray pipes, JSONArray blends, JSONArray journal) throws JSONException {
        return new JSONObject().put("schema", 1).put("pipes", pipes).put("blends", blends).put("journal", journal).toString();
    }

    @Test
    public void upsertGetDelete() throws Exception {
        String id = store.upsert("pipes", rec(null, "Billiard"));
        assertThat(id).isNotEmpty();
        assertThat(store.get("pipes", id).optString("name")).isEqualTo("Billiard");
        assertThat(store.get("pipes", id).has("updatedAt")).isTrue();
        store.upsert("pipes", rec(id, "Bent Billiard"));
        assertThat(store.count("pipes")).isEqualTo(1);
        assertThat(store.get("pipes", id).optString("name")).isEqualTo("Bent Billiard");
        store.delete("pipes", id);
        assertThat(store.count("pipes")).isEqualTo(0);
        assertThat(store.get("pipes", id)).isNull();
    }

    @Test
    public void unknownTableIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.records("cigars"));
        assertThrows(IllegalArgumentException.class, () -> PipeDataStore.checkTable("x"));
    }

    @Test
    public void selectionAndRitual() {
        store.select("pipes", "p1");
        store.select("blends", "b1");
        assertThat(store.selected("pipes")).isEqualTo("p1");
        assertThat(store.selected("blends")).isEqualTo("b1");
        store.ritualMask(0b10101);
        assertThat(store.ritualMask()).isEqualTo(0b10101);
        assertThat(PipeDataStore.selectionKey("pipes")).isEqualTo(PipeDataStore.KEY_CURRENT_PIPE);
        assertThat(PipeDataStore.selectionKey("blends")).isEqualTo(PipeDataStore.KEY_CURRENT_BLEND);
    }

    @Test
    public void withoutIdAndFind() throws Exception {
        JSONArray a = new JSONArray().put(rec("a", "A")).put(rec("b", "B")).put("not an object").put(rec("a", "A2"));
        JSONArray out = PipeDataStore.withoutId(a, "a");
        assertThat(out.length()).isEqualTo(1);
        assertThat(out.getJSONObject(0).getString("id")).isEqualTo("b");
        assertThat(PipeDataStore.find(a, "b").getString("name")).isEqualTo("B");
        assertThat(PipeDataStore.find(a, "zz")).isNull();
    }

    @Test
    public void mergeById_replacesInPlaceThenAppends() throws Exception {
        JSONArray existing = new JSONArray().put(rec("a", "old A")).put(rec("b", "B"));
        JSONArray incoming = new JSONArray().put(rec("c", "C")).put(rec("a", "new A"));
        JSONArray m = PipeDataStore.mergeById(existing, incoming);
        assertThat(m.length()).isEqualTo(3);
        assertThat(m.getJSONObject(0).getString("name")).isEqualTo("new A");
        assertThat(m.getJSONObject(1).getString("id")).isEqualTo("b");
        assertThat(m.getJSONObject(2).getString("id")).isEqualTo("c");
    }

    @Test
    public void parseImport_validates() throws Exception {
        JSONArray empty = new JSONArray();
        assertThrows(JSONException.class, () -> PipeDataStore.parseImport("{\"schema\":2}"));
        assertThrows(JSONException.class, () -> PipeDataStore.parseImport("not json"));
        assertThrows(JSONException.class, () -> PipeDataStore.parseImport("{\"schema\":1,\"pipes\":[]}")); // missing tables
        assertThrows(JSONException.class, () -> PipeDataStore.parseImport(doc(new JSONArray().put(rec(null, "x")), empty, empty)));
        StringBuilder longId = new StringBuilder();
        for (int i = 0; i <= PipeDataStore.MAX_ID_LENGTH; i++) longId.append('x');
        assertThrows(JSONException.class, () -> PipeDataStore.parseImport(doc(new JSONArray().put(rec(longId.toString(), "x")), empty, empty)));
        StringBuilder big = new StringBuilder();
        for (int i = 0; i <= PipeDataStore.MAX_RECORD_CHARS; i++) big.append('y');
        assertThrows(JSONException.class, () -> PipeDataStore.parseImport(doc(new JSONArray().put(rec("id", big.toString())), empty, empty)));
        assertThat(PipeDataStore.parseImport(doc(empty, empty, empty)).getInt("schema")).isEqualTo(1);
    }

    @Test
    public void failedImportLeavesStorageUntouched() throws Exception {
        store.upsert("pipes", rec("keep", "Keeper"));
        String bad = doc(new JSONArray().put(rec("new", "N")), new JSONArray().put(rec(null, "no id")), new JSONArray());
        assertThrows(JSONException.class, () -> store.importJson(bad));
        assertThat(store.count("pipes")).isEqualTo(1);
        assertThat(store.get("pipes", "new")).isNull();
    }

    @Test
    public void exportImportRoundTrip() throws Exception {
        store.upsert("pipes", rec("p1", "Dublin"));
        store.upsert("blends", rec("b1", "Latakia"));
        store.upsert("journal", rec("j1", "First bowl"));
        store.select("pipes", "p1");
        store.ritualMask(3);
        JSONObject exported = store.exportAll();
        assertThat(exported.getInt("schema")).isEqualTo(PipeDataStore.SCHEMA);

        setUp(); // wipe
        assertThat(store.count("pipes")).isEqualTo(0);
        int n = store.importJson(exported.toString());
        assertThat(n).isEqualTo(3);
        assertThat(store.get("blends", "b1").getString("name")).isEqualTo("Latakia");
        assertThat(store.selected("pipes")).isEqualTo("p1");
        assertThat(store.ritualMask()).isEqualTo(3);
    }

    @Test
    public void exportDocumentShape() throws Exception {
        JSONArray[] t = {new JSONArray(), new JSONArray().put(rec("b", "B")), new JSONArray()};
        JSONObject d = PipeDataStore.exportDocument(t, "p", "b", 5, 1234L);
        assertThat(d.getLong("exportedAt")).isEqualTo(1234L);
        assertThat(d.getJSONArray("blends").length()).isEqualTo(1);
        assertThat(d.getJSONObject("selection").getInt(PipeDataStore.KEY_RITUAL_MASK)).isEqualTo(5);
    }
}
