package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** [Govee]: address validation, LAN and cloud reply parsing, merging (Robolectric for real org.json). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoveeTest {

    @Test
    fun isValidIpv4() {
        assertThat(Govee.isValidIpv4("192.168.1.42")).isTrue()
        assertThat(Govee.isValidIpv4("10.0.0.1")).isTrue()
        assertThat(Govee.isValidIpv4("256.1.1.1")).isFalse()
        assertThat(Govee.isValidIpv4("127.0.0.1")).isFalse()
        assertThat(Govee.isValidIpv4("0.1.2.3")).isFalse()
        assertThat(Govee.isValidIpv4("239.255.255.250")).isFalse()
        assertThat(Govee.isValidIpv4("192.168.1.255")).isFalse()
        assertThat(Govee.isValidIpv4("192.168.1")).isFalse()
        assertThat(Govee.isValidIpv4("lamp.local")).isFalse()
        assertThat(Govee.isValidIpv4(null)).isFalse()
    }

    @Test
    fun title_and_route() {
        assertThat(device("a", "H6008", "", "", false).title()).isEqualTo("GOVEE H6008")
        assertThat(device("a", "H6008", "DESK", "", false).title()).isEqualTo("DESK")
        assertThat(device("a", "", "", "", false).route()).isEqualTo("NO ROUTE")
        assertThat(device("a", "", "", "192.168.1.9", true).route()).isEqualTo("LAN 192.168.1.9 • CLOUD")
    }

    @Test
    fun storedDevices_roundTripAndDropBadAddresses() {
        val a = JSONArray()
        a.put(device("AA:BB", "H6008", "DESK", "192.168.1.9", true).toJson())
        a.put(device("CC:DD", "H6159", "", "999.1.1.1", false).toJson())
        val list = Govee.parseStored(a.toString())
        assertThat(list).hasSize(2)
        assertThat(list[0].name).isEqualTo("DESK")
        assertThat(list[0].cloud).isTrue()
        assertThat(list[1].ip).isEmpty()
        assertThat(Govee.parseStored("not json")).isEmpty()
    }

    @Test
    fun merge_keepsNamesAndUpdatesRoutes() {
        val known = ArrayList<Govee.Device>()
        known.add(device("aa:bb", "H6008", "DESK", "192.168.1.9", false))
        known.add(device("cc:dd", "", "GOVEE ", "", false))
        val found = ArrayList<Govee.Device>()
        found.add(device("AA:BB", "H6008", "Desk lamp", "192.168.1.20", true))
        found.add(device("CC:DD", "H6159", "STRIP", "", true))
        found.add(device("EE:FF", "H7000", "NEW", "", true))
        val out = Govee.merge(known, found, true)
        assertThat(out).hasSize(3)
        assertThat(out[0].name).isEqualTo("DESK")
        assertThat(out[0].ip).isEqualTo("192.168.1.20")
        assertThat(out[0].cloud).isTrue()
        assertThat(out[1].name).isEqualTo("STRIP")
        assertThat(out[1].sku).isEqualTo("H6159")
        assertThat(out[2].id).isEqualTo("EE:FF")
    }

    @Test
    fun parseLanReply_matchesCommandAndAddsSender() {
        val scan = "{\"msg\":{\"cmd\":\"scan\",\"data\":{\"device\":\"AA:BB\",\"sku\":\"H6008\",\"ip\":\"192.168.1.9\"}}}"
        val data = Govee.parseLanReply(scan, "scan", "192.168.1.9")
        assertThat(data).isNotNull()
        assertThat(data!!.getString("_from")).isEqualTo("192.168.1.9")
        assertThat(Govee.parseLanReply(scan, "devStatus", "x")).isNull()
        assertThat(Govee.parseLanReply("garbage", "scan", "x")).isNull()
        assertThat(Govee.parseLanReply("{\"msg\":{\"cmd\":\"scan\"}}", "scan", "x")).isNull()
    }

    @Test
    fun deviceFromScan_prefersReportedAddress() {
        val d = JSONObject().put("device", "AA:BB").put("sku", "H6008").put("ip", "192.168.1.9").put("_from", "192.168.1.10")
        val dev = Govee.deviceFromScan(d)!!
        assertThat(dev.ip).isEqualTo("192.168.1.9")
        assertThat(dev.name).isEqualTo("GOVEE H6008")
        d.put("ip", "bogus")
        assertThat(Govee.deviceFromScan(d)!!.ip).isEqualTo("192.168.1.10")
        assertThat(Govee.deviceFromScan(JSONObject().put("sku", "H6008").put("_from", "192.168.1.10"))).isNull()
    }

    @Test
    fun turnMessage() {
        assertThat(Govee.turnMessage(true)).contains("\"value\":1")
        assertThat(Govee.turnMessage(false)).contains("\"value\":0")
    }

    @Test
    fun parseCloudDevices_keepsSwitchableLights() {
        val json = "{\"code\":200,\"data\":[" +
            "{\"device\":\"AA\",\"sku\":\"H6008\",\"deviceName\":\"Desk\",\"capabilities\":[{\"instance\":\"powerSwitch\"}]}," +
            "{\"device\":\"BB\",\"sku\":\"H5100\",\"deviceName\":\"Sensor\",\"capabilities\":[{\"instance\":\"sensorTemperature\"}]}," +
            "{\"device\":\"CC\",\"sku\":\"H6159\"}," +
            "{\"sku\":\"H1\",\"capabilities\":[{\"instance\":\"powerSwitch\"}]}]}"
        val list = Govee.parseCloudDevices(JSONObject(json))
        assertThat(list).hasSize(2)
        assertThat(list[0].name).isEqualTo("DESK")
        assertThat(list[0].cloud).isTrue()
        assertThat(list[1].name).isEqualTo("GOVEE H6159")
    }

    @Test
    fun checkReply_mapsErrors() {
        assertThat(Govee.checkReply(200, "{\"code\":200,\"data\":[]}").has("data")).isTrue()
        assertThat(message(401, "")).isEqualTo("API KEY REJECTED")
        assertThat(message(429, "")).startsWith("GOVEE RATE LIMIT")
        assertThat(message(500, "")).isEqualTo("GOVEE ERROR 500")
        assertThat(message(200, "{\"code\":400,\"msg\":\"device not found\"}")).isEqualTo("DEVICE NOT FOUND")
        assertThat(message(200, "<html>")).isEqualTo("UNEXPECTED REPLY FROM GOVEE")
    }

    @Test
    fun powerFromState() {
        val on = JSONObject("{\"payload\":{\"capabilities\":[{\"instance\":\"powerSwitch\",\"state\":{\"value\":1}}]}}")
        val off = JSONObject("{\"payload\":{\"capabilities\":[{\"instance\":\"powerSwitch\",\"state\":{\"value\":0}}]}}")
        val blank = JSONObject("{\"payload\":{\"capabilities\":[{\"instance\":\"powerSwitch\",\"state\":{\"value\":\"\"}}]}}")
        assertThat(Govee.powerFromState(on)).isTrue()
        assertThat(Govee.powerFromState(off)).isFalse()
        assertThat(Govee.powerFromState(blank)).isNull()
        assertThat(Govee.powerFromState(JSONObject())).isNull()
    }

    @Test
    fun hasPowerSwitch_unknownCapabilitiesAllowed() {
        assertThat(Govee.hasPowerSwitch(null)).isTrue()
        assertThat(Govee.hasPowerSwitch(JSONArray())).isFalse()
    }

    @Test
    fun statusLine() {
        assertThat(Govee.statusLine("DESK", true, true, null)).isEqualTo("DESK • ON")
        assertThat(Govee.statusLine("DESK", true, null, null)).isEqualTo("DESK • COMMAND SENT")
        assertThat(Govee.statusLine("DESK", false, null, "LAN TIMEOUT")).isEqualTo("DESK • NO RESPONSE (LAN TIMEOUT)")
    }

    @Test
    fun interfaceNames() {
        assertThat(Govee.isWifiName("wlan0")).isTrue()
        assertThat(Govee.isWifiName("swlan0")).isTrue()
        assertThat(Govee.isMobileOrVpnName("rmnet_data0")).isTrue()
        assertThat(Govee.isMobileOrVpnName("tun0")).isTrue()
        assertThat(Govee.isMobileOrVpnName("eth0")).isFalse()
        assertThat(emptyList<Any>()).isEmpty()
    }

    private companion object {
        fun device(id: String, sku: String, name: String, ip: String, cloud: Boolean): Govee.Device {
            val d = Govee.Device()
            d.id = id
            d.sku = sku
            d.name = name
            d.ip = ip
            d.cloud = cloud
            return d
        }

        fun message(code: Int, body: String): String? =
            try {
                Govee.checkReply(code, body)
                null
            } catch (e: IOException) {
                e.message
            }
    }
}
