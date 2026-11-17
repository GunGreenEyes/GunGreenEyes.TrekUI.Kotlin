package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.junit.Assert.assertThrows;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** {@link LayoutArchive}: serialization round trip, secret filtering, validation, schema migration, missing apps. */
public class LayoutArchiveTest {

    /** A realistic preferences dump with layout, appearance, credentials and transient entries. */
    private static Map<String, Object> sample() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("faction", 1);
        m.put("theme", 2);
        m.put("anim", false);
        m.put("tiles", 9);
        m.put("sfxVol", 0.75f);
        m.put("someLong", 1L << 40);
        m.put("header1", "I.K.S. ROTARRAN");
        m.put("tilesJson", "[{\"label\":\"MAPS\",\"comp\":\"com.maps/.Main\",\"user\":-1,\"color\":-1},"
                + "{\"label\":\"\",\"action\":\"apps\",\"user\":-1,\"color\":2}]");
        m.put("dockJson", "[{\"label\":\"CHAT\",\"comp\":\"org.chat/.Home\",\"user\":-1,\"color\":-1}]");
        m.put("slotsJson", "{\"hElbowT\":{\"label\":\"OPS\",\"comp\":\"com.maps/.Main\",\"user\":-1,\"color\":3}}");
        m.put("hidden", new HashSet<>(Arrays.asList("b", "a")));
        // credentials and device-bound values
        m.put("goveeKey", "SECRET-KEY");
        m.put("nestSecret", "s3cret");
        m.put("nestRefresh", "1//refresh");
        m.put("nestAccess", "ya29.token");
        m.put("nestClientId", "client.apps.googleusercontent.com");
        m.put("goveeDevices", "[{\"ip\":\"192.168.1.4\"}]");
        m.put("goveeOn_ABC", true);
        m.put("haApiToken", "future-token");
        m.put("someServiceKey", "k");
        m.put("phoneUri0", "content://media/1");
        m.put("wxLat", "52.1");
        // transient
        m.put("ver", 41);
        m.put("welcomed", true);
        m.put("wxCache", "{}");
        m.put(Onboarding.KEY_SEEN, true);
        m.put(LayoutSnapshots.KEY_VERSION_CODE, 60);
        return m;
    }

    @Test
    public void secretsAndDeviceValuesAreExcluded() {
        for (String k : new String[]{"goveeKey", "nestSecret", "nestRefresh", "nestAccess", "nestAccessExp", "nestClientId",
                "nestProject", "goveeDevices", "goveeLastIp", "goveeOn_X", "haApiToken", "someServiceKey", "myPassword",
                "oauthState", "phoneUri2", "wxLat", "wxLon", "wxName", "ver", "welcomed", "permAsked_camera", "asked_call",
                "tourSeen", "tourReplay", "layoutVersionCode", "ha_url", "ha_token", "ha_entities", "appWidgetSlots",
                "appWidgetRows_0", "redAlertUntil", "ent.token", "", null}) {
            assertWithMessage(String.valueOf(k)).that(LayoutArchive.isExcluded(k)).isTrue();
        }
        for (String k : new String[]{"faction", "theme", "tilesJson", "dockJson", "slotsJson", "header1", "imperialLettering",
                "worder", "whidden", "hidden", "icons", "cols", "wxMode", "wxF", "sfxVol", "bootSeq", "trekuiDialer"}) {
            assertWithMessage(k).that(LayoutArchive.isExcluded(k)).isFalse();
        }
    }

    @Test
    public void encodeLeavesOutEverySecret() throws Exception {
        JSONObject doc = LayoutArchive.encode(sample(), 62, "6.2.0", 1, 1234L);
        String text = doc.toString();
        for (String secret : new String[]{"SECRET-KEY", "s3cret", "1//refresh", "ya29.token", "googleusercontent", "192.168.1.4",
                "future-token", "content://media", "52.1"}) {
            assertThat(text).doesNotContain(secret);
        }
        assertThat(doc.getString("format")).isEqualTo(LayoutArchive.FORMAT);
        assertThat(doc.getInt("schema")).isEqualTo(LayoutArchive.SCHEMA);
        assertThat(doc.getString("interface")).isEqualTo("IMPERIAL");
        assertThat(doc.getJSONObject("app").getInt("versionCode")).isEqualTo(62);
        assertThat(doc.getJSONObject("prefs").has("ver")).isFalse();
    }

    @Test
    public void roundTripKeepsTypesAndValues() throws Exception {
        JSONObject doc = LayoutArchive.encode(sample(), 62, "6.2.0", 1, 1234L);
        LayoutArchive.Parsed p = LayoutArchive.parse(doc.toString());
        assertThat(p.sourceSchema).isEqualTo(LayoutArchive.SCHEMA);
        assertThat(p.createdAt).isEqualTo(1234L);
        assertThat(p.appVersion).isEqualTo("6.2.0");
        assertThat(p.prefs.get("faction")).isEqualTo(1);
        assertThat(p.prefs.get("anim")).isEqualTo(false);
        assertThat(p.prefs.get("sfxVol")).isEqualTo(0.75f);
        assertThat(p.prefs.get("someLong")).isEqualTo(1L << 40);
        assertThat(p.prefs.get("header1")).isEqualTo("I.K.S. ROTARRAN");
        assertThat(p.prefs.get("hidden")).isEqualTo(new HashSet<>(Arrays.asList("a", "b")));
        assertThat(p.prefs).isEqualTo(LayoutArchive.filter(sample()));
        assertThat(LayoutArchive.factionOf(p.prefs)).isEqualTo(1);
        assertThat(LayoutArchive.assignedTiles(p.prefs)).isEqualTo(3);
    }

    @Test
    public void secretsInAFileAreDroppedAndCounted() throws Exception {
        JSONObject doc = LayoutArchive.encode(sample(), 62, "6.2.0", 0, 1L);
        JSONObject planted = new JSONObject().put("t", "string").put("v", "evil");
        doc.getJSONObject("prefs").put("goveeKey", planted).put("nestRefresh", planted).put("ver", new JSONObject().put("t", "int").put("v", 0));
        LayoutArchive.Parsed p = LayoutArchive.parse(doc.toString());
        assertThat(p.prefs).doesNotContainKey("goveeKey");
        assertThat(p.prefs).doesNotContainKey("nestRefresh");
        assertThat(p.prefs).doesNotContainKey("ver");
        assertThat(p.filtered).isEqualTo(3);
    }

    @Test
    public void validationRejectsBadDocuments() throws Exception {
        assertThrows(LayoutArchive.InvalidLayoutException.class, () -> LayoutArchive.parse(""));
        assertThrows(LayoutArchive.InvalidLayoutException.class, () -> LayoutArchive.parse("not json"));
        assertThrows(LayoutArchive.InvalidLayoutException.class, () -> LayoutArchive.parse("{\"format\":\"other\",\"schema\":1}"));
        assertThrows(LayoutArchive.InvalidLayoutException.class, () -> LayoutArchive.parse("{\"format\":\"trekui-layout\",\"schema\":1}"));
        // a pipe collection export has a schema but isn't a layout
        assertThrows(LayoutArchive.InvalidLayoutException.class, () -> LayoutArchive.parse("{\"schema\":1,\"pipes\":[]}"));
        // newer schema
        assertThrows(LayoutArchive.InvalidLayoutException.class,
                () -> LayoutArchive.parse("{\"format\":\"trekui-layout\",\"schema\":99,\"prefs\":{}}"));
        // wrong types
        for (String bad : new String[]{"{\"t\":\"int\",\"v\":\"3\"}", "{\"t\":\"int\",\"v\":1.5}", "{\"t\":\"int\",\"v\":9999999999}",
                "{\"t\":\"bool\",\"v\":1}", "{\"t\":\"set\",\"v\":[1]}", "{\"t\":\"string\",\"v\":3}", "{\"t\":\"blob\",\"v\":3}", "3"}) {
            String doc = "{\"format\":\"trekui-layout\",\"schema\":1,\"prefs\":{\"tiles\":" + bad + "}}";
            assertThrows(bad, LayoutArchive.InvalidLayoutException.class, () -> LayoutArchive.parse(doc));
        }
        StringBuilder huge = new StringBuilder();
        while (huge.length() <= LayoutArchive.MAX_DOCUMENT_CHARS) huge.append("          ");
        assertThrows(LayoutArchive.InvalidLayoutException.class, () -> LayoutArchive.parse("{" + huge + "}"));
    }

    @Test
    public void schemaZeroFlatDumpIsMigrated() throws Exception {
        JSONObject flat = new JSONObject()
                .put("faction", 2).put("anim", true).put("sfxVol", 0.5).put("big", 1L << 40)
                .put("header2", "NODE CLUSTER 01").put("hidden", new JSONArray().put("x"))
                .put("goveeKey", "SECRET");
        assertThat(LayoutArchive.schemaOf(flat)).isEqualTo(0);
        JSONObject migrated = LayoutArchive.migrate(flat);
        assertThat(migrated.getInt("schema")).isEqualTo(1);
        assertThat(migrated.getJSONObject("prefs").getJSONObject("faction").getString("t")).isEqualTo("int");

        LayoutArchive.Parsed p = LayoutArchive.parse(flat.toString());
        assertThat(p.sourceSchema).isEqualTo(0);
        assertThat(p.prefs.get("faction")).isEqualTo(2);
        assertThat(p.prefs.get("anim")).isEqualTo(true);
        assertThat(p.prefs.get("sfxVol")).isEqualTo(0.5f);
        assertThat(p.prefs.get("big")).isEqualTo(1L << 40);
        assertThat(p.prefs.get("header2")).isEqualTo("NODE CLUSTER 01");
        assertThat(p.prefs.get("hidden")).isEqualTo(new HashSet<>(Arrays.asList("x")));
        assertThat(p.prefs).doesNotContainKey("goveeKey");
        assertThat(p.filtered).isEqualTo(1);
    }

    @Test
    public void missingAppsBecomeEmptySlots() throws Exception {
        Map<String, Object> prefs = LayoutArchive.filter(sample());
        Set<String> installed = new HashSet<>(Arrays.asList("org.chat"));
        int cleared = LayoutArchive.clearMissingApps(prefs, installed::contains);
        assertThat(cleared).isEqualTo(2); // the MAPS tile and the elbow assigned to MAPS
        JSONArray tiles = new JSONArray((String) prefs.get("tilesJson"));
        assertThat(tiles.getJSONObject(0).has("comp")).isFalse();
        assertThat(tiles.getJSONObject(0).getString("label")).isEmpty();
        assertThat(tiles.getJSONObject(1).getString("action")).isEqualTo("apps"); // functions are kept
        assertThat(new JSONArray((String) prefs.get("dockJson")).getJSONObject(0).getString("comp")).isEqualTo("org.chat/.Home");
        JSONObject elbow = new JSONObject((String) prefs.get("slotsJson")).getJSONObject("hElbowT");
        assertThat(elbow.has("comp")).isFalse();
        assertThat(elbow.getString("label")).isEqualTo("OPS"); // the element keeps its look
        assertThat(elbow.getInt("color")).isEqualTo(3);
    }

    @Test
    public void corruptTileListIsDroppedNotFatal() {
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("tilesJson", "{not an array");
        assertThat(LayoutArchive.clearMissingApps(prefs, pkg -> false)).isEqualTo(0);
        assertThat(prefs).doesNotContainKey("tilesJson");
    }

    @Test
    public void keysToRemoveSparesExcludedEntries() {
        Map<String, Object> current = sample();
        Map<String, Object> target = new HashMap<>();
        target.put("faction", 0);
        assertThat(LayoutArchive.keysToRemove(current, target)).doesNotContain("goveeKey");
        assertThat(LayoutArchive.keysToRemove(current, target)).doesNotContain("ver");
        assertThat(LayoutArchive.keysToRemove(current, target)).doesNotContain("faction");
        assertThat(LayoutArchive.keysToRemove(current, target)).contains("tilesJson");
    }
}
