package com.gungreeneyes.trekui;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * Govee lights on/off from console controls (home tiles and CONFIG › DEVICES).
 *
 * <p>Two routes, LAN first:
 * <ul>
 *   <li><b>LAN API</b> (local Wi-Fi, no account): "LAN Control" switched on per device in the Govee
 *       Home app. Discovery: UDP multicast {@value #MULTICAST}:{@value #SCAN_PORT}; replies arrive
 *       on port {@value #LISTEN_PORT}; commands go to the device on port {@value #CMD_PORT}
 *       ("devStatus" to read on/off, "turn" to switch).</li>
 *   <li><b>Developer API</b> (cloud): an API key from Govee Home › Profile › Settings › Apply for
 *       API Key. POST /device/state to read, /device/control with powerSwitch to switch.</li>
 * </ul>
 *
 * <p>All network work runs on one background thread ({@link #IO}), so two operations never bind
 * the reply port at once; results come back on the main thread. Reply parsing and device-list
 * merging are pure functions, unit-tested in {@code GoveeTest}.
 */
final class Govee {
    private static final String CLOUD = "https://openapi.api.govee.com/router/api/v1";
    static final String MULTICAST = "239.255.255.250";
    static final int SCAN_PORT = 4001, LISTEN_PORT = 4002, CMD_PORT = 4003;

    /** LAN discovery window when scanning from settings, and when re-finding a moved light. */
    private static final int SCAN_WINDOW_MS = 2500, RESCAN_WINDOW_MS = 1500;
    /** Re-send the multicast scan this often (UDP may drop it), at most {@link #SCAN_SENDS} times. */
    private static final int SCAN_RESEND_MS = 600, SCAN_SENDS = 3;
    /** Cloud API timeouts: Govee's servers answer quickly or not at all. */
    private static final int CLOUD_TIMEOUT_MS = 8000;
    /** Multicast TTL: stay on the local network. */
    private static final int MULTICAST_TTL = 2;
    private static final int MAX_DATAGRAM = 2048;

    private static final String SCAN_MSG = "{\"msg\":{\"cmd\":\"scan\",\"data\":{\"account_topic\":\"reserve\"}}}";
    private static final String STATUS_MSG = "{\"msg\":{\"cmd\":\"devStatus\",\"data\":{}}}";

    /** Background thread for every Govee operation (LAN and cloud). */
    private static final ExecutorService IO = Http.newExecutor("trekui-govee");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A configured light: identity, the name shown, and how it can be reached. */
    static final class Device {
        /** Govee device id (MAC-like), the model (SKU), the console name, and the LAN address. */
        String id = "", sku = "", name = "", ip = "";
        /** Reachable through the cloud API (an API key is set and the cloud listed it). */
        boolean cloud;

        /** Name to show: the stored name, else "GOVEE " + model. */
        String title() { return name.isEmpty() ? ("GOVEE " + sku).trim() : name; }

        /** Routes for the settings list, e.g. "LAN 192.168.1.40 • CLOUD". */
        String route() {
            StringBuilder s = new StringBuilder();
            if (!ip.isEmpty()) s.append("LAN ").append(ip);
            if (cloud) s.append(s.length() > 0 ? " • " : "").append("CLOUD");
            return s.length() == 0 ? "NO ROUTE" : s.toString();
        }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id).put("sku", sku).put("name", name).put("ip", ip).put("cloud", cloud);
            } catch (JSONException ignored) {
                // keys are non-null literals: cannot happen
            }
            return o;
        }

        static Device fromJson(JSONObject o) {
            Device d = new Device();
            d.id = o.optString("id");
            d.sku = o.optString("sku");
            d.name = o.optString("name");
            d.ip = isValidIpv4(o.optString("ip")) ? o.optString("ip") : "";
            d.cloud = o.optBoolean("cloud");
            return d;
        }
    }

    /** Result of a power command: success, a status line for the screen, and the new state (null if unknown). */
    interface Done { void done(boolean ok, String message, Boolean on); }

    /** Result of a discovery: the devices found and an error text (null when the search itself worked). */
    interface Found { void found(List<Device> devices, String error); }

    private Govee() {}

    // ---- stored devices ------------------------------------------------------------------------

    /** The configured lights (from preferences); empty when none or the stored list is corrupt. */
    static List<Device> devices(Prefs p) {
        return parseStored(p.s("goveeDevices", "[]"));
    }

    /** Parses the stored device list; tolerant of bad entries. */
    static List<Device> parseStored(String json) {
        List<Device> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(Device.fromJson(o));
            }
        } catch (JSONException ignored) {
            // corrupt preference: treat as no devices
        }
        return out;
    }

    static void save(Prefs p, List<Device> list) {
        JSONArray a = new JSONArray();
        for (Device d : list) a.put(d.toJson());
        p.put("goveeDevices", a.toString());
    }

    static Device find(Prefs p, String id) {
        if (id == null) return null;
        for (Device d : devices(p)) if (d.id.equalsIgnoreCase(id)) return d;
        return null;
    }

    /** The Govee developer API key, or "" when none is set. */
    static String key(Prefs p) { return p.s("goveeKey", ""); }

    /** Adds or updates devices by ID, keeping names the person set and routes already known. */
    static List<Device> merge(List<Device> known, List<Device> found, boolean fromCloud) {
        List<Device> out = new ArrayList<>(known);
        for (Device f : found) {
            Device match = null;
            for (Device k : out) if (k.id.equalsIgnoreCase(f.id)) { match = k; break; }
            if (match == null) { out.add(f); continue; }
            if (!f.ip.isEmpty()) match.ip = f.ip;
            if (fromCloud) match.cloud = true;
            if (match.sku.isEmpty()) match.sku = f.sku;
            if (match.name.isEmpty() || (fromCloud && match.name.startsWith("GOVEE "))) match.name = f.name;
        }
        return out;
    }

    /**
     * True for a dotted-quad IPv4 unicast host address (each octet 0-255; not this-network 0.x,
     * loopback 127.x, multicast/reserved 224+ or a .255 subnet broadcast): the only kind of
     * address LAN commands are ever sent to.
     */
    static boolean isValidIpv4(String s) {
        if (s == null || !s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
        String[] p = s.split("\\.");
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            o[i] = Integer.parseInt(p[i]);
            if (o[i] > 255) return false;
        }
        if (o[0] == 0 || o[0] == 127 || o[0] >= 224) return false;
        return o[3] != 255;
    }

    // ---- discovery -----------------------------------------------------------------------------

    /** Scans the local network for Govee lights with LAN Control on; result on the main thread. */
    static void scanLan(Context c, final Found cb) {
        final Context app = c.getApplicationContext();
        init(app);
        IO.execute(new Runnable() {
            @Override public void run() {
                List<Device> found = new ArrayList<>();
                String err = null;
                try { found = lanScan(app, SCAN_WINDOW_MS); } catch (IOException | RuntimeException e) { err = message(e); }
                post(cb, found, err);
            }
        });
    }

    /** Lists the account's lights with the cloud API key; result on the main thread. */
    static void fetchCloud(final String key, final Found cb) {
        IO.execute(new Runnable() {
            @Override public void run() {
                List<Device> found = new ArrayList<>();
                String err = null;
                try { found = cloudDevices(key); } catch (IOException | RuntimeException e) { err = message(e); }
                post(cb, found, err);
            }
        });
    }

    private static void post(final Found cb, final List<Device> found, final String err) {
        MAIN.post(new Runnable() { @Override public void run() { cb.found(found, err); } });
    }

    private static String message(Exception e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName().toUpperCase(Locale.US);
    }

    // ---- switching -----------------------------------------------------------------------------

    /**
     * Switches a light: LAN first (re-finding it once if its address changed), then the cloud.
     * The callback runs on the main thread.
     *
     * @param id    the device id as stored
     * @param force null to toggle (the current state is read first), TRUE/FALSE to set
     */
    static void power(Context c, final String id, final Boolean force, final Done cb) {
        final Context app = c.getApplicationContext();
        final Prefs p = Prefs.get(app);
        init(app);
        IO.execute(new Runnable() {
            @Override public void run() {
                Device d = find(p, id);
                boolean ok = false;
                Boolean on = null;
                String msg;
                if (d == null) {
                    msg = "LIGHT NOT CONFIGURED • CONFIG › DEVICES";
                } else {
                    String err = null;
                    if (!d.ip.isEmpty()) {
                        try {
                            on = lanPower(d.ip, force);
                            ok = true;
                        } catch (IOException e) {
                            err = message(e);
                            // the router may have given it a new address: rescan once and retry
                            try {
                                for (Device f : lanScan(app, RESCAN_WINDOW_MS)) {
                                    if (f.id.equalsIgnoreCase(d.id) && !f.ip.equals(d.ip)) {
                                        d.ip = f.ip;
                                        save(p, merge(devices(p), Collections.singletonList(f), false));
                                        on = lanPower(d.ip, force);
                                        ok = true;
                                        break;
                                    }
                                }
                            } catch (IOException | RuntimeException ignored) {
                                // keep the first error for the message
                            }
                        }
                    }
                    if (!ok && d.cloud && !key(p).isEmpty()) {
                        try {
                            on = cloudPower(key(p), d, force);
                            ok = true;
                        } catch (IOException e) {
                            err = message(e);
                        }
                    }
                    msg = statusLine(d.title(), ok, on, err);
                    if (ok && on != null) p.put("goveeOn_" + d.id, on);
                }
                final boolean fok = ok;
                final String fmsg = msg;
                final Boolean fon = on;
                MAIN.post(new Runnable() { @Override public void run() { cb.done(fok, fmsg, fon); } });
            }
        });
    }

    /** The one-line result shown after a power command. */
    static String statusLine(String title, boolean ok, Boolean on, String error) {
        if (ok) return title + (on == null ? " • COMMAND SENT" : on ? " • ON" : " • OFF");
        return title + " • NO RESPONSE" + (error != null ? " (" + error + ")" : "");
    }

    // ---- LAN API -------------------------------------------------------------------------------

    /**
     * Socket on port 4002 (where Govee devices answer), joined to 239.255.255.250 on the Wi-Fi
     * interface, with outgoing multicast pinned to Wi-Fi so it doesn't leave via mobile data.
     */
    private static MulticastSocket listen(int timeoutMs) throws IOException {
        MulticastSocket s = new MulticastSocket(null);
        try {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(LISTEN_PORT));
            s.setSoTimeout(timeoutMs);
            s.setTimeToLive(MULTICAST_TTL);
            NetworkInterface wifi = wifiInterface();
            InetAddress group = InetAddress.getByName(MULTICAST);
            try {
                if (wifi != null) {
                    s.setNetworkInterface(wifi);
                    s.joinGroup(new InetSocketAddress(group, LISTEN_PORT), wifi);
                } else {
                    s.joinGroup(new InetSocketAddress(group, LISTEN_PORT), null);
                }
            } catch (IOException e) {
                // joining can fail on some networks; unicast replies still arrive on this socket
            }
            return s;
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    /** "wlan0 192.168.1.20" for the interface the last LAN operation used (shown in settings). */
    static volatile String lastInterface = "";

    /** The Wi-Fi network interface (has an IPv4 address and multicast), or null. */
    static NetworkInterface wifiInterface() {
        NetworkInterface fallback = null;
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all != null && all.hasMoreElements()) {
                NetworkInterface n = all.nextElement();
                if (!n.isUp() || n.isLoopback() || !n.supportsMulticast()) continue;
                boolean v4 = false;
                Enumeration<InetAddress> addrs = n.getInetAddresses();
                while (addrs.hasMoreElements()) if (addrs.nextElement() instanceof Inet4Address) v4 = true;
                if (!v4) continue;
                String name = n.getName().toLowerCase(Locale.US);
                if (isWifiName(name)) { lastInterface = describe(n); return n; }
                if (fallback == null && !isMobileOrVpnName(name)) fallback = n;
            }
        } catch (IOException | RuntimeException ignored) {
            // no interface list: fall through
        }
        lastInterface = fallback != null ? describe(fallback) : "NONE";
        return fallback;
    }

    /** Wi-Fi interface names on Android (wlan0, Samsung's swlan0 hotspot). */
    static boolean isWifiName(String name) { return name.startsWith("wlan") || name.startsWith("swlan"); }

    /** Mobile data (Qualcomm rmnet, MediaTek ccmni) and VPN tunnels: never used for LAN control. */
    static boolean isMobileOrVpnName(String name) {
        return name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun");
    }

    private static String describe(NetworkInterface n) {
        Enumeration<InetAddress> addrs = n.getInetAddresses();
        while (addrs.hasMoreElements()) {
            InetAddress a = addrs.nextElement();
            if (a instanceof Inet4Address) return n.getName() + " " + a.getHostAddress();
        }
        return n.getName();
    }

    private static void send(DatagramSocket s, String host, int port, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        s.send(new DatagramPacket(b, b.length, InetAddress.getByName(host), port));
    }

    /**
     * Waits for a Govee reply with the given command name (optionally only from one address).
     *
     * @return the reply's {@code data} object with the sender's address added as "_from", or null on timeout
     */
    private static JSONObject receive(DatagramSocket s, String cmd, String fromIp) throws IOException {
        byte[] buf = new byte[MAX_DATAGRAM];
        long until = System.currentTimeMillis() + Math.max(200, s.getSoTimeout());
        while (System.currentTimeMillis() < until) {
            DatagramPacket pk = new DatagramPacket(buf, buf.length);
            try {
                s.receive(pk);
            } catch (SocketTimeoutException e) {
                return null;
            }
            String from = pk.getAddress().getHostAddress();
            if (fromIp != null && !from.equals(fromIp)) continue;
            JSONObject data = parseLanReply(new String(pk.getData(), 0, pk.getLength(), StandardCharsets.UTF_8), cmd, from);
            if (data != null) return data;
        }
        return null;
    }

    /**
     * Parses one LAN datagram: {"msg":{"cmd":…,"data":{…}}}.
     *
     * @return the data object (with "_from" = sender) when the command matches, else null
     */
    static JSONObject parseLanReply(String text, String cmd, String from) {
        try {
            JSONObject msg = new JSONObject(text).optJSONObject("msg");
            if (msg == null || !cmd.equals(msg.optString("cmd"))) return null;
            JSONObject data = msg.optJSONObject("data");
            if (data == null) return null;
            data.put("_from", from == null ? "" : from);
            return data;
        } catch (JSONException e) {
            return null; // not a Govee message
        }
    }

    /**
     * Turns a LAN "scan" reply into a device: id, model, and the address it reported (or the
     * address it answered from). Null when it has no id or no usable address.
     */
    static Device deviceFromScan(JSONObject data) {
        Device dev = new Device();
        dev.id = data.optString("device");
        dev.sku = data.optString("sku");
        String ip = data.optString("ip");
        dev.ip = isValidIpv4(ip) ? ip : data.optString("_from");
        if (!isValidIpv4(dev.ip)) dev.ip = "";
        dev.name = "GOVEE " + dev.sku;
        return dev.id.isEmpty() || dev.ip.isEmpty() ? null : dev;
    }

    /** Multicast discovery over {@code windowMs}; blocks, background thread only. */
    static List<Device> lanScan(Context c, int windowMs) throws IOException {
        init(c);
        WifiManager.MulticastLock lock = multicastLock(c);
        Map<String, Device> out = new HashMap<>();
        List<Device> order = new ArrayList<>();
        try (MulticastSocket s = listen(400)) {
            long end = System.currentTimeMillis() + windowMs;
            send(s, MULTICAST, SCAN_PORT, SCAN_MSG);
            int sent = 1;
            long resendAt = System.currentTimeMillis() + SCAN_RESEND_MS;
            while (System.currentTimeMillis() < end) {
                if (sent < SCAN_SENDS && System.currentTimeMillis() > resendAt) {
                    send(s, MULTICAST, SCAN_PORT, SCAN_MSG);
                    sent++;
                    resendAt = System.currentTimeMillis() + SCAN_RESEND_MS;
                }
                JSONObject d = receive(s, "scan", null);
                Device dev = d == null ? null : deviceFromScan(d);
                if (dev == null) continue;
                String k = dev.id.toLowerCase(Locale.US);
                if (!out.containsKey(k)) { out.put(k, dev); order.add(dev); }
            }
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }
        return order;
    }

    /** Reads on/off over LAN (unless forced) and switches. @return the new state */
    private static boolean lanPower(String ip, Boolean force) throws IOException {
        if (!isValidIpv4(ip)) throw new IOException("BAD ADDRESS");
        WifiManager.MulticastLock lock = multicastLock(null);
        try (MulticastSocket s = listen(900)) {
            boolean target;
            if (force != null) {
                target = force;
            } else {
                JSONObject st = null;
                for (int i = 0; i < 2 && st == null; i++) {
                    send(s, ip, CMD_PORT, STATUS_MSG);
                    st = receive(s, "devStatus", ip);
                }
                if (st == null) throw new IOException("LAN TIMEOUT");
                target = st.optInt("onOff", 0) != 1;
            }
            send(s, ip, CMD_PORT, turnMessage(target));
            return target;
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }
    }

    /** The LAN "turn" command for on (1) or off (0). */
    static String turnMessage(boolean on) {
        return "{\"msg\":{\"cmd\":\"turn\",\"data\":{\"value\":" + (on ? 1 : 0) + "}}}";
    }

    /** Application context for multicast locks requested without a context (always the app context: no leaks). */
    private static volatile Context appContext;

    static void init(Context c) { if (appContext == null && c != null) appContext = c.getApplicationContext(); }

    /** Holds a Wi-Fi multicast lock (many phones drop multicast replies without one); null if unavailable. */
    private static WifiManager.MulticastLock multicastLock(Context c) {
        Context ctx = c != null ? c.getApplicationContext() : appContext;
        if (ctx == null) return null;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return null;
            WifiManager.MulticastLock l = wm.createMulticastLock("trekui-govee");
            l.setReferenceCounted(false);
            l.acquire();
            return l;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Adds a light by its IP address (for networks that block discovery): asks it to identify
     * itself (unicast scan) and for its status. Blocks; background thread only.
     *
     * @return the device, or null if it didn't answer
     * @throws IOException for an address that isn't a LAN IPv4 address, or a socket failure
     */
    static Device probe(Context c, String ip) throws IOException {
        if (!isValidIpv4(ip)) throw new IOException("ENTER AN ADDRESS LIKE 192.168.1.42");
        WifiManager.MulticastLock lock = multicastLock(c);
        try (MulticastSocket s = listen(700)) {
            JSONObject id = null, st = null;
            for (int i = 0; i < 3 && id == null; i++) {
                send(s, ip, SCAN_PORT, SCAN_MSG);
                id = receive(s, "scan", ip);
            }
            for (int i = 0; i < 2 && st == null; i++) {
                send(s, ip, CMD_PORT, STATUS_MSG);
                st = receive(s, "devStatus", ip);
            }
            if (id == null && st == null) return null;
            Device d = new Device();
            d.ip = ip;
            d.sku = id != null ? id.optString("sku") : "";
            d.id = id != null && !id.optString("device").isEmpty() ? id.optString("device") : "IP:" + ip;
            d.name = "GOVEE " + (d.sku.isEmpty() ? ip : d.sku);
            return d;
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }
    }

    // ---- cloud API -----------------------------------------------------------------------------

    /** One cloud API call; Govee's HTTP and API-level errors become short console messages. */
    private static JSONObject http(String key, String method, String path, JSONObject body) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("Govee-API-Key", key);
        Http.Response r = Http.request(method, CLOUD + path, headers,
                body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8), CLOUD_TIMEOUT_MS, CLOUD_TIMEOUT_MS);
        return checkReply(r.code, r.body);
    }

    /** Validates a cloud reply: maps HTTP and Govee "code" errors to messages, returns the JSON. */
    static JSONObject checkReply(int code, String text) throws IOException {
        if (code == 401 || code == 403) throw new IOException("API KEY REJECTED");
        if (code == 429) throw new IOException("GOVEE RATE LIMIT • TRY AGAIN IN A MINUTE");
        if (code >= 400) throw new IOException("GOVEE ERROR " + code);
        try {
            JSONObject o = new JSONObject(text);
            int apiCode = o.optInt("code", 200);
            if (apiCode != 200) {
                throw new IOException(o.optString("msg", o.optString("message", "GOVEE ERROR " + apiCode)).toUpperCase(Locale.US));
            }
            return o;
        } catch (JSONException e) {
            throw new IOException("UNEXPECTED REPLY FROM GOVEE");
        }
    }

    /** The account's switchable lights from the cloud API; blocks, background thread only. */
    static List<Device> cloudDevices(String key) throws IOException {
        if (key == null || key.trim().isEmpty()) throw new IOException("NO API KEY");
        return parseCloudDevices(http(key.trim(), "GET", "/user/devices", null));
    }

    /** Devices with an on/off capability from a /user/devices reply. */
    static List<Device> parseCloudDevices(JSONObject reply) {
        List<Device> out = new ArrayList<>();
        JSONArray a = reply.optJSONArray("data");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject d = a.optJSONObject(i);
            if (d == null || !hasPowerSwitch(d.optJSONArray("capabilities"))) continue;
            Device dev = new Device();
            dev.id = d.optString("device");
            if (dev.id.isEmpty()) continue;
            dev.sku = d.optString("sku");
            dev.name = d.optString("deviceName", "GOVEE " + dev.sku).toUpperCase(Locale.getDefault());
            dev.cloud = true;
            out.add(dev);
        }
        return out;
    }

    /** True when the capability list has a powerSwitch (or isn't given: let the person try). */
    static boolean hasPowerSwitch(JSONArray caps) {
        if (caps == null) return true;
        for (int i = 0; i < caps.length(); i++) {
            JSONObject c = caps.optJSONObject(i);
            if (c != null && "powerSwitch".equals(c.optString("instance"))) return true;
        }
        return false;
    }

    private static JSONObject payload(Device d) throws JSONException {
        return new JSONObject().put("requestId", UUID.randomUUID().toString())
                .put("payload", new JSONObject().put("sku", d.sku).put("device", d.id));
    }

    /** Reads powerSwitch from a device-state reply; null if it isn't reported. */
    static Boolean powerFromState(JSONObject reply) {
        JSONObject p = reply.optJSONObject("payload");
        JSONArray caps = p != null ? p.optJSONArray("capabilities") : null;
        if (caps == null) return null;
        for (int i = 0; i < caps.length(); i++) {
            JSONObject c = caps.optJSONObject(i);
            if (c == null || !"powerSwitch".equals(c.optString("instance"))) continue;
            JSONObject st = c.optJSONObject("state");
            if (st == null || !st.has("value") || "".equals(st.opt("value"))) return null;
            return st.optInt("value", 0) == 1;
        }
        return null;
    }

    /** Reads (unless forced) and sets the power state through the cloud. @return the new state */
    private static boolean cloudPower(String key, Device d, Boolean force) throws IOException {
        try {
            boolean target;
            if (force != null) {
                target = force;
            } else {
                Boolean cur = powerFromState(http(key, "POST", "/device/state", payload(d)));
                target = cur == null || !cur;
            }
            JSONObject body = payload(d);
            body.getJSONObject("payload").put("capability", new JSONObject()
                    .put("type", "devices.capabilities.on_off").put("instance", "powerSwitch").put("value", target ? 1 : 0));
            http(key, "POST", "/device/control", body);
            return target;
        } catch (JSONException e) {
            throw new IOException("COULD NOT BUILD REQUEST");
        }
    }
}
