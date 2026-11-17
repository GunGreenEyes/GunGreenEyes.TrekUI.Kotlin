package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** {@link Govee}: address validation, LAN and cloud reply parsing, merging (Robolectric for real org.json). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GoveeTest {

    private static Govee.Device device(String id, String sku, String name, String ip, boolean cloud) {
        Govee.Device d = new Govee.Device();
        d.id = id;
        d.sku = sku;
        d.name = name;
        d.ip = ip;
        d.cloud = cloud;
        return d;
    }

    @Test
    public void isValidIpv4() {
        assertThat(Govee.isValidIpv4("192.168.1.42")).isTrue();
        assertThat(Govee.isValidIpv4("10.0.0.1")).isTrue();
        assertThat(Govee.isValidIpv4("256.1.1.1")).isFalse();
        assertThat(Govee.isValidIpv4("127.0.0.1")).isFalse();
        assertThat(Govee.isValidIpv4("0.1.2.3")).isFalse();
        assertThat(Govee.isValidIpv4("239.255.255.250")).isFalse();
        assertThat(Govee.isValidIpv4("192.168.1.255")).isFalse();
        assertThat(Govee.isValidIpv4("192.168.1")).isFalse();
        assertThat(Govee.isValidIpv4("lamp.local")).isFalse();
        assertThat(Govee.isValidIpv4(null)).isFalse();
    }

    @Test
    public void title_and_route() {
        assertThat(device("a", "H6008", "", "", false).title()).isEqualTo("GOVEE H6008");
        assertThat(device("a", "H6008", "DESK", "", false).title()).isEqualTo("DESK");
        assertThat(device("a", "", "", "", false).route()).isEqualTo("NO ROUTE");
        assertThat(device("a", "", "", "192.168.1.9", true).route()).isEqualTo("LAN 192.168.1.9 • CLOUD");
    }

    @Test
    public void storedDevices_roundTripAndDropBadAddresses() {
        JSONArray a = new JSONArray();
        a.put(device("AA:BB", "H6008", "DESK", "192.168.1.9", true).toJson());
        a.put(device("CC:DD", "H6159", "", "999.1.1.1", false).toJson());
        List<Govee.Device> list = Govee.parseStored(a.toString());
        assertThat(list).hasSize(2);
        assertThat(list.get(0).name).isEqualTo("DESK");
        assertThat(list.get(0).cloud).isTrue();
        assertThat(list.get(1).ip).isEmpty();
        assertThat(Govee.parseStored("not json")).isEmpty();
    }

    @Test
    public void merge_keepsNamesAndUpdatesRoutes() {
        List<Govee.Device> known = new ArrayList<>();
        known.add(device("aa:bb", "H6008", "DESK", "192.168.1.9", false));
        known.add(device("cc:dd", "", "GOVEE ", "", false));
        List<Govee.Device> found = new ArrayList<>();
        found.add(device("AA:BB", "H6008", "Desk lamp", "192.168.1.20", true));
        found.add(device("CC:DD", "H6159", "STRIP", "", true));
        found.add(device("EE:FF", "H7000", "NEW", "", true));
        List<Govee.Device> out = Govee.merge(known, found, true);
        assertThat(out).hasSize(3);
        assertThat(out.get(0).name).isEqualTo("DESK");
        assertThat(out.get(0).ip).isEqualTo("192.168.1.20");
        assertThat(out.get(0).cloud).isTrue();
        assertThat(out.get(1).name).isEqualTo("STRIP");
        assertThat(out.get(1).sku).isEqualTo("H6159");
        assertThat(out.get(2).id).isEqualTo("EE:FF");
    }

    @Test
    public void parseLanReply_matchesCommandAndAddsSender() throws Exception {
        String scan = "{\"msg\":{\"cmd\":\"scan\",\"data\":{\"device\":\"AA:BB\",\"sku\":\"H6008\",\"ip\":\"192.168.1.9\"}}}";
        JSONObject data = Govee.parseLanReply(scan, "scan", "192.168.1.9");
        assertThat(data).isNotNull();
        assertThat(data.getString("_from")).isEqualTo("192.168.1.9");
        assertThat(Govee.parseLanReply(scan, "devStatus", "x")).isNull();
        assertThat(Govee.parseLanReply("garbage", "scan", "x")).isNull();
        assertThat(Govee.parseLanReply("{\"msg\":{\"cmd\":\"scan\"}}", "scan", "x")).isNull();
    }

    @Test
    public void deviceFromScan_prefersReportedAddress() throws Exception {
        JSONObject d = new JSONObject().put("device", "AA:BB").put("sku", "H6008").put("ip", "192.168.1.9").put("_from", "192.168.1.10");
        Govee.Device dev = Govee.deviceFromScan(d);
        assertThat(dev.ip).isEqualTo("192.168.1.9");
        assertThat(dev.name).isEqualTo("GOVEE H6008");
        d.put("ip", "bogus");
        assertThat(Govee.deviceFromScan(d).ip).isEqualTo("192.168.1.10");
        assertThat(Govee.deviceFromScan(new JSONObject().put("sku", "H6008").put("_from", "192.168.1.10"))).isNull();
    }

    @Test
    public void turnMessage() {
        assertThat(Govee.turnMessage(true)).contains("\"value\":1");
        assertThat(Govee.turnMessage(false)).contains("\"value\":0");
    }

    @Test
    public void parseCloudDevices_keepsSwitchableLights() throws Exception {
        String json = "{\"code\":200,\"data\":["
                + "{\"device\":\"AA\",\"sku\":\"H6008\",\"deviceName\":\"Desk\",\"capabilities\":[{\"instance\":\"powerSwitch\"}]},"
                + "{\"device\":\"BB\",\"sku\":\"H5100\",\"deviceName\":\"Sensor\",\"capabilities\":[{\"instance\":\"sensorTemperature\"}]},"
                + "{\"device\":\"CC\",\"sku\":\"H6159\"},"
                + "{\"sku\":\"H1\",\"capabilities\":[{\"instance\":\"powerSwitch\"}]}]}";
        List<Govee.Device> list = Govee.parseCloudDevices(new JSONObject(json));
        assertThat(list).hasSize(2);
        assertThat(list.get(0).name).isEqualTo("DESK");
        assertThat(list.get(0).cloud).isTrue();
        assertThat(list.get(1).name).isEqualTo("GOVEE H6159");
    }

    @Test
    public void checkReply_mapsErrors() throws Exception {
        assertThat(Govee.checkReply(200, "{\"code\":200,\"data\":[]}").has("data")).isTrue();
        assertThat(message(401, "")).isEqualTo("API KEY REJECTED");
        assertThat(message(429, "")).startsWith("GOVEE RATE LIMIT");
        assertThat(message(500, "")).isEqualTo("GOVEE ERROR 500");
        assertThat(message(200, "{\"code\":400,\"msg\":\"device not found\"}")).isEqualTo("DEVICE NOT FOUND");
        assertThat(message(200, "<html>")).isEqualTo("UNEXPECTED REPLY FROM GOVEE");
    }

    private static String message(int code, String body) {
        try {
            Govee.checkReply(code, body);
            return null;
        } catch (IOException e) {
            return e.getMessage();
        }
    }

    @Test
    public void powerFromState() throws Exception {
        JSONObject on = new JSONObject("{\"payload\":{\"capabilities\":[{\"instance\":\"powerSwitch\",\"state\":{\"value\":1}}]}}");
        JSONObject off = new JSONObject("{\"payload\":{\"capabilities\":[{\"instance\":\"powerSwitch\",\"state\":{\"value\":0}}]}}");
        JSONObject blank = new JSONObject("{\"payload\":{\"capabilities\":[{\"instance\":\"powerSwitch\",\"state\":{\"value\":\"\"}}]}}");
        assertThat(Govee.powerFromState(on)).isTrue();
        assertThat(Govee.powerFromState(off)).isFalse();
        assertThat(Govee.powerFromState(blank)).isNull();
        assertThat(Govee.powerFromState(new JSONObject())).isNull();
    }

    @Test
    public void hasPowerSwitch_unknownCapabilitiesAllowed() throws Exception {
        assertThat(Govee.hasPowerSwitch(null)).isTrue();
        assertThat(Govee.hasPowerSwitch(new JSONArray())).isFalse();
    }

    @Test
    public void statusLine() {
        assertThat(Govee.statusLine("DESK", true, true, null)).isEqualTo("DESK • ON");
        assertThat(Govee.statusLine("DESK", true, null, null)).isEqualTo("DESK • COMMAND SENT");
        assertThat(Govee.statusLine("DESK", false, null, "LAN TIMEOUT")).isEqualTo("DESK • NO RESPONSE (LAN TIMEOUT)");
    }

    @Test
    public void interfaceNames() {
        assertThat(Govee.isWifiName("wlan0")).isTrue();
        assertThat(Govee.isWifiName("swlan0")).isTrue();
        assertThat(Govee.isMobileOrVpnName("rmnet_data0")).isTrue();
        assertThat(Govee.isMobileOrVpnName("tun0")).isTrue();
        assertThat(Govee.isMobileOrVpnName("eth0")).isFalse();
        assertThat(Collections.emptyList()).isEmpty();
    }
}
