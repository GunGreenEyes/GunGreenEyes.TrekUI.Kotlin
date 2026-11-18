package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

/** [LayoutArchive]: serialization round trip, secret filtering, validation, schema migration, missing apps. */
class LayoutArchiveTest {

    /** A realistic preferences dump with layout, appearance, credentials and transient entries. */
    private fun sample(): MutableMap<String, Any> = linkedMapOf(
        "faction" to 1,
        "theme" to 2,
        "anim" to false,
        "tiles" to 9,
        "sfxVol" to 0.75f,
        "someLong" to (1L shl 40),
        "header1" to "I.K.S. ROTARRAN",
        "tilesJson" to "[{\"label\":\"MAPS\",\"comp\":\"com.maps/.Main\",\"user\":-1,\"color\":-1}," +
            "{\"label\":\"\",\"action\":\"apps\",\"user\":-1,\"color\":2}]",
        "dockJson" to "[{\"label\":\"CHAT\",\"comp\":\"org.chat/.Home\",\"user\":-1,\"color\":-1}]",
        "slotsJson" to "{\"hElbowT\":{\"label\":\"OPS\",\"comp\":\"com.maps/.Main\",\"user\":-1,\"color\":3}}",
        "hidden" to hashSetOf("b", "a"),
        // credentials and device-bound values
        "goveeKey" to "SECRET-KEY",
        "nestSecret" to "s3cret",
        "nestRefresh" to "1//refresh",
        "nestAccess" to "ya29.token",
        "nestClientId" to "client.apps.googleusercontent.com",
        "goveeDevices" to "[{\"ip\":\"192.168.1.4\"}]",
        "goveeOn_ABC" to true,
        "haApiToken" to "future-token",
        "someServiceKey" to "k",
        "phoneUri0" to "content://media/1",
        "wxLat" to "52.1",
        // transient
        "ver" to 41,
        "welcomed" to true,
        "wxCache" to "{}",
        Onboarding.KEY_SEEN to true,
        LayoutSnapshots.KEY_VERSION_CODE to 60,
    )

    @Test
    fun secretsAndDeviceValuesAreExcluded() {
        for (k in arrayOf(
            "goveeKey", "nestSecret", "nestRefresh", "nestAccess", "nestAccessExp", "nestClientId",
            "nestProject", "goveeDevices", "goveeLastIp", "goveeOn_X", "haApiToken", "someServiceKey", "myPassword",
            "oauthState", "phoneUri2", "wxLat", "wxLon", "wxName", "ver", "welcomed", "permAsked_camera", "asked_call",
            "tourSeen", "tourReplay", "layoutVersionCode", "ha_url", "ha_token", "ha_entities", "appWidgetSlots",
            "appWidgetRows_0", "redAlertUntil", "ent.token", "", null,
        )) {
            assertWithMessage(k.toString()).that(LayoutArchive.isExcluded(k)).isTrue()
        }
        for (k in arrayOf(
            "faction", "theme", "tilesJson", "dockJson", "slotsJson", "header1", "imperialLettering",
            "worder", "whidden", "hidden", "icons", "cols", "wxMode", "wxF", "sfxVol", "bootSeq", "trekuiDialer",
        )) {
            assertWithMessage(k).that(LayoutArchive.isExcluded(k)).isFalse()
        }
    }

    @Test
    fun encodeLeavesOutEverySecret() {
        val doc = LayoutArchive.encode(sample(), 62, "6.2.0", 1, 1234L)
        val text = doc.toString()
        for (secret in arrayOf(
            "SECRET-KEY", "s3cret", "1//refresh", "ya29.token", "googleusercontent", "192.168.1.4",
            "future-token", "content://media", "52.1",
        )) {
            assertThat(text).doesNotContain(secret)
        }
        assertThat(doc.getString("format")).isEqualTo(LayoutArchive.FORMAT)
        assertThat(doc.getInt("schema")).isEqualTo(LayoutArchive.SCHEMA)
        assertThat(doc.getString("interface")).isEqualTo("IMPERIAL")
        assertThat(doc.getJSONObject("app").getInt("versionCode")).isEqualTo(62)
        assertThat(doc.getJSONObject("prefs").has("ver")).isFalse()
    }

    @Test
    fun roundTripKeepsTypesAndValues() {
        val doc = LayoutArchive.encode(sample(), 62, "6.2.0", 1, 1234L)
        val p = LayoutArchive.parse(doc.toString())
        assertThat(p.sourceSchema).isEqualTo(LayoutArchive.SCHEMA)
        assertThat(p.createdAt).isEqualTo(1234L)
        assertThat(p.appVersion).isEqualTo("6.2.0")
        assertThat(p.prefs["faction"]).isEqualTo(1)
        assertThat(p.prefs["anim"]).isEqualTo(false)
        assertThat(p.prefs["sfxVol"]).isEqualTo(0.75f)
        assertThat(p.prefs["someLong"]).isEqualTo(1L shl 40)
        assertThat(p.prefs["header1"]).isEqualTo("I.K.S. ROTARRAN")
        assertThat(p.prefs["hidden"]).isEqualTo(hashSetOf("a", "b"))
        assertThat(p.prefs).isEqualTo(LayoutArchive.filter(sample()))
        assertThat(LayoutArchive.factionOf(p.prefs)).isEqualTo(1)
        assertThat(LayoutArchive.assignedTiles(p.prefs)).isEqualTo(3)
    }

    @Test
    fun secretsInAFileAreDroppedAndCounted() {
        val doc = LayoutArchive.encode(sample(), 62, "6.2.0", 0, 1L)
        val planted = JSONObject().put("t", "string").put("v", "evil")
        doc.getJSONObject("prefs").put("goveeKey", planted).put("nestRefresh", planted)
            .put("ver", JSONObject().put("t", "int").put("v", 0))
        val p = LayoutArchive.parse(doc.toString())
        assertThat(p.prefs).doesNotContainKey("goveeKey")
        assertThat(p.prefs).doesNotContainKey("nestRefresh")
        assertThat(p.prefs).doesNotContainKey("ver")
        assertThat(p.filtered).isEqualTo(3)
    }

    @Test
    fun validationRejectsBadDocuments() {
        assertThrows(LayoutArchive.InvalidLayoutException::class.java) { LayoutArchive.parse("") }
        assertThrows(LayoutArchive.InvalidLayoutException::class.java) { LayoutArchive.parse("not json") }
        assertThrows(LayoutArchive.InvalidLayoutException::class.java) { LayoutArchive.parse("{\"format\":\"other\",\"schema\":1}") }
        assertThrows(LayoutArchive.InvalidLayoutException::class.java) { LayoutArchive.parse("{\"format\":\"trekui-layout\",\"schema\":1}") }
        // another export with a schema but no layout format isn't a layout
        assertThrows(LayoutArchive.InvalidLayoutException::class.java) { LayoutArchive.parse("{\"schema\":1,\"items\":[]}") }
        // newer schema
        assertThrows(LayoutArchive.InvalidLayoutException::class.java) {
            LayoutArchive.parse("{\"format\":\"trekui-layout\",\"schema\":99,\"prefs\":{}}")
        }
        // wrong types
        for (bad in arrayOf(
            "{\"t\":\"int\",\"v\":\"3\"}", "{\"t\":\"int\",\"v\":1.5}", "{\"t\":\"int\",\"v\":9999999999}",
            "{\"t\":\"bool\",\"v\":1}", "{\"t\":\"set\",\"v\":[1]}", "{\"t\":\"string\",\"v\":3}", "{\"t\":\"blob\",\"v\":3}", "3",
        )) {
            val doc = "{\"format\":\"trekui-layout\",\"schema\":1,\"prefs\":{\"tiles\":$bad}}"
            assertThrows(bad, LayoutArchive.InvalidLayoutException::class.java) { LayoutArchive.parse(doc) }
        }
        val huge = StringBuilder()
        while (huge.length <= LayoutArchive.MAX_DOCUMENT_CHARS) huge.append("          ")
        assertThrows(LayoutArchive.InvalidLayoutException::class.java) { LayoutArchive.parse("{$huge}") }
    }

    @Test
    fun schemaZeroFlatDumpIsMigrated() {
        val flat = JSONObject()
            .put("faction", 2).put("anim", true).put("sfxVol", 0.5).put("big", 1L shl 40)
            .put("header2", "NODE CLUSTER 01").put("hidden", JSONArray().put("x"))
            .put("goveeKey", "SECRET")
        assertThat(LayoutArchive.schemaOf(flat)).isEqualTo(0)
        val migrated = LayoutArchive.migrate(flat)
        assertThat(migrated.getInt("schema")).isEqualTo(1)
        assertThat(migrated.getJSONObject("prefs").getJSONObject("faction").getString("t")).isEqualTo("int")

        val p = LayoutArchive.parse(flat.toString())
        assertThat(p.sourceSchema).isEqualTo(0)
        assertThat(p.prefs["faction"]).isEqualTo(2)
        assertThat(p.prefs["anim"]).isEqualTo(true)
        assertThat(p.prefs["sfxVol"]).isEqualTo(0.5f)
        assertThat(p.prefs["big"]).isEqualTo(1L shl 40)
        assertThat(p.prefs["header2"]).isEqualTo("NODE CLUSTER 01")
        assertThat(p.prefs["hidden"]).isEqualTo(hashSetOf("x"))
        assertThat(p.prefs).doesNotContainKey("goveeKey")
        assertThat(p.filtered).isEqualTo(1)
    }

    @Test
    fun missingAppsBecomeEmptySlots() {
        val prefs = LayoutArchive.filter(sample())
        val installed = hashSetOf("org.chat")
        val cleared = LayoutArchive.clearMissingApps(prefs) { it in installed }
        assertThat(cleared).isEqualTo(2) // the MAPS tile and the elbow assigned to MAPS
        val tiles = JSONArray(prefs["tilesJson"] as String)
        assertThat(tiles.getJSONObject(0).has("comp")).isFalse()
        assertThat(tiles.getJSONObject(0).getString("label")).isEmpty()
        assertThat(tiles.getJSONObject(1).getString("action")).isEqualTo("apps") // functions are kept
        assertThat(JSONArray(prefs["dockJson"] as String).getJSONObject(0).getString("comp")).isEqualTo("org.chat/.Home")
        val elbow = JSONObject(prefs["slotsJson"] as String).getJSONObject("hElbowT")
        assertThat(elbow.has("comp")).isFalse()
        assertThat(elbow.getString("label")).isEqualTo("OPS") // the element keeps its look
        assertThat(elbow.getInt("color")).isEqualTo(3)
    }

    @Test
    fun corruptTileListIsDroppedNotFatal() {
        val prefs = HashMap<String, Any>()
        prefs["tilesJson"] = "{not an array"
        assertThat(LayoutArchive.clearMissingApps(prefs) { false }).isEqualTo(0)
        assertThat(prefs).doesNotContainKey("tilesJson")
    }

    @Test
    fun keysToRemoveSparesExcludedEntries() {
        val current = sample()
        val target = HashMap<String, Any>()
        target["faction"] = 0
        assertThat(LayoutArchive.keysToRemove(current, target)).doesNotContain("goveeKey")
        assertThat(LayoutArchive.keysToRemove(current, target)).doesNotContain("ver")
        assertThat(LayoutArchive.keysToRemove(current, target)).doesNotContain("faction")
        assertThat(LayoutArchive.keysToRemove(current, target)).contains("tilesJson")
    }
}
