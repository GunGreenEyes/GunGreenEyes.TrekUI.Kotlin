package com.gungreeneyes.trekui

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import kotlin.math.max

/**
 * Govee lights on/off from console controls (home tiles and CONFIG › DEVICES).
 *
 * Two routes, LAN first:
 * - **LAN API** (local Wi-Fi, no account): "LAN Control" switched on per device in the Govee
 *   Home app. Discovery: UDP multicast [MULTICAST]:[SCAN_PORT]; replies arrive
 *   on port [LISTEN_PORT]; commands go to the device on port [CMD_PORT]
 *   ("devStatus" to read on/off, "turn" to switch).
 * - **Developer API** (cloud): an API key from Govee Home › Profile › Settings › Apply for
 *   API Key. POST /device/state to read, /device/control with powerSwitch to switch.
 *
 * All network work runs on one background thread ([IO]), so two operations never bind
 * the reply port at once; results come back on the main thread. Reply parsing and device-list
 * merging are pure functions, unit-tested in `GoveeTest`.
 *
 * Java interop: MainActivity and SettingsActivity call these as statics ([JvmStatic]).
 */
internal object Govee {
    private const val CLOUD = "https://openapi.api.govee.com/router/api/v1"
    const val MULTICAST = "239.255.255.250"
    const val SCAN_PORT = 4001
    const val LISTEN_PORT = 4002
    const val CMD_PORT = 4003

    /** LAN discovery window when scanning from settings, and when re-finding a moved light. */
    private const val SCAN_WINDOW_MS = 2500
    private const val RESCAN_WINDOW_MS = 1500

    /** Re-send the multicast scan this often (UDP may drop it), at most [SCAN_SENDS] times. */
    private const val SCAN_RESEND_MS = 600
    private const val SCAN_SENDS = 3

    /** Cloud API timeouts: Govee's servers answer quickly or not at all. */
    private const val CLOUD_TIMEOUT_MS = 8000

    /** Multicast TTL: stay on the local network. */
    private const val MULTICAST_TTL = 2
    private const val MAX_DATAGRAM = 2048

    private const val SCAN_MSG = "{\"msg\":{\"cmd\":\"scan\",\"data\":{\"account_topic\":\"reserve\"}}}"
    private const val STATUS_MSG = "{\"msg\":{\"cmd\":\"devStatus\",\"data\":{}}}"

    private val IPV4 = Regex("\\d{1,3}(\\.\\d{1,3}){3}")

    /** Background thread for every Govee operation (LAN and cloud). */
    private const val IO_NAME = "trekui-govee"
    private val IO: ExecutorService = Http.newExecutor(IO_NAME)
    private val MAIN = Handler(Looper.getMainLooper())

    /** A configured light: identity, the name shown, and how it can be reached. */
    class Device {
        /** Govee device id (MAC-like). */
        @JvmField var id: String = ""

        /** The model (SKU). */
        @JvmField var sku: String = ""

        /** The console name. */
        @JvmField var name: String = ""

        /** The LAN address. */
        @JvmField var ip: String = ""

        /** Reachable through the cloud API (an API key is set and the cloud listed it). */
        @JvmField var cloud = false

        /** Name to show: the stored name, else "GOVEE " + model. */
        fun title(): String = if (name.isEmpty()) "GOVEE $sku".trim() else name

        /** Routes for the settings list, e.g. "LAN 192.168.1.40 • CLOUD". */
        fun route(): String {
            val s = StringBuilder()
            if (ip.isNotEmpty()) s.append("LAN ").append(ip)
            if (cloud) s.append(if (s.isNotEmpty()) " • " else "").append("CLOUD")
            return if (s.isEmpty()) "NO ROUTE" else s.toString()
        }

        fun toJson(): JSONObject {
            val o = JSONObject()
            try {
                o.put("id", id).put("sku", sku).put("name", name).put("ip", ip).put("cloud", cloud)
            } catch (ignored: JSONException) {
                // keys are non-null literals: cannot happen
            }
            return o
        }

        companion object {
            @JvmStatic
            fun fromJson(o: JSONObject): Device {
                val d = Device()
                d.id = o.optString("id")
                d.sku = o.optString("sku")
                d.name = o.optString("name")
                d.ip = if (isValidIpv4(o.optString("ip"))) o.optString("ip") else ""
                d.cloud = o.optBoolean("cloud")
                return d
            }
        }
    }

    /** Result of a power command: success, a status line for the screen, and the new state (null if unknown). */
    fun interface Done {
        fun done(ok: Boolean, message: String, on: Boolean?)
    }

    /** Result of a discovery: the devices found and an error text (null when the search itself worked). */
    fun interface Found {
        fun found(devices: List<Device>, error: String?)
    }

    // ---- stored devices ------------------------------------------------------------------------

    /** The configured lights (from preferences); empty when none or the stored list is corrupt. */
    @JvmStatic
    fun devices(p: Prefs): MutableList<Device> = parseStored(p.s("goveeDevices", "[]"))

    /** Parses the stored device list; tolerant of bad entries. */
    @JvmStatic
    fun parseStored(json: String?): MutableList<Device> {
        val out = ArrayList<Device>()
        try {
            val a = JSONArray(json)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i)
                if (o != null) out.add(Device.fromJson(o))
            }
        } catch (ignored: JSONException) {
            // corrupt preference: treat as no devices
        }
        return out
    }

    @JvmStatic
    fun save(p: Prefs, list: List<Device>) {
        val a = JSONArray()
        for (d in list) a.put(d.toJson())
        p.put("goveeDevices", a.toString())
    }

    @JvmStatic
    fun find(p: Prefs, id: String?): Device? {
        if (id == null) return null
        return devices(p).firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    /** The Govee developer API key, or "" when none is set. */
    @JvmStatic
    fun key(p: Prefs): String = p.secret("goveeKey")

    /** Adds or updates devices by ID, keeping names the person set and routes already known. */
    @JvmStatic
    fun merge(known: List<Device>, found: List<Device>, fromCloud: Boolean): MutableList<Device> {
        val out = ArrayList(known)
        for (f in found) {
            val match = out.firstOrNull { it.id.equals(f.id, ignoreCase = true) }
            if (match == null) {
                out.add(f)
                continue
            }
            if (f.ip.isNotEmpty()) match.ip = f.ip
            if (fromCloud) match.cloud = true
            if (match.sku.isEmpty()) match.sku = f.sku
            if (match.name.isEmpty() || (fromCloud && match.name.startsWith("GOVEE "))) match.name = f.name
        }
        return out
    }

    /**
     * True for a dotted-quad IPv4 unicast host address (each octet 0-255; not this-network 0.x,
     * loopback 127.x, multicast/reserved 224+ or a .255 subnet broadcast): the only kind of
     * address LAN commands are ever sent to.
     */
    @JvmStatic
    fun isValidIpv4(s: String?): Boolean {
        if (s == null || !IPV4.matches(s)) return false
        val p = s.split(".")
        val o = IntArray(4)
        for (i in 0 until 4) {
            o[i] = p[i].toInt()
            if (o[i] > 255) return false
        }
        if (o[0] == 0 || o[0] == 127 || o[0] >= 224) return false
        return o[3] != 255
    }

    // ---- discovery -----------------------------------------------------------------------------

    /** Scans the local network for Govee lights with LAN Control on; result on the main thread. */
    @JvmStatic
    fun scanLan(c: Context, cb: Found) {
        val app = c.applicationContext
        init(app)
        IO.execute {
            var found: List<Device> = ArrayList()
            var err: String? = null
            try {
                found = lanScan(app, SCAN_WINDOW_MS)
            } catch (e: IOException) {
                err = message(e)
            } catch (e: RuntimeException) {
                err = message(e)
            }
            post(cb, found, err)
        }
    }

    /** Lists the account's lights with the cloud API key; result on the main thread. */
    @JvmStatic
    fun fetchCloud(key: String?, cb: Found) {
        IO.execute {
            var found: List<Device> = ArrayList()
            var err: String? = null
            try {
                found = cloudDevices(key)
            } catch (e: IOException) {
                err = message(e)
            } catch (e: RuntimeException) {
                err = message(e)
            }
            post(cb, found, err)
        }
    }

    private fun post(cb: Found, found: List<Device>, err: String?) {
        MAIN.post { cb.found(found, err) }
    }

    private fun message(e: Exception): String = e.message ?: e.javaClass.simpleName.uppercase(Locale.US)

    // ---- switching -----------------------------------------------------------------------------

    /**
     * Switches a light: LAN first (re-finding it once if its address changed), then the cloud.
     * The callback runs on the main thread.
     *
     * @param id    the device id as stored
     * @param force null to toggle (the current state is read first), TRUE/FALSE to set
     */
    @JvmStatic
    fun power(c: Context, id: String?, force: Boolean?, cb: Done) {
        val app = c.applicationContext
        val p = Prefs.get(app)
        init(app)
        IO.execute {
            val d = find(p, id)
            var ok = false
            var on: Boolean? = null
            val msg: String
            if (d == null) {
                msg = "LIGHT NOT CONFIGURED • CONFIG › DEVICES"
            } else {
                var err: String? = null
                if (d.ip.isNotEmpty()) {
                    try {
                        on = lanPower(d.ip, force)
                        ok = true
                    } catch (e: IOException) {
                        err = message(e)
                        // the router may have given it a new address: rescan once and retry
                        try {
                            for (f in lanScan(app, RESCAN_WINDOW_MS)) {
                                if (f.id.equals(d.id, ignoreCase = true) && f.ip != d.ip) {
                                    d.ip = f.ip
                                    save(p, merge(devices(p), listOf(f), false))
                                    on = lanPower(d.ip, force)
                                    ok = true
                                    break
                                }
                            }
                        } catch (ignored: IOException) {
                            // keep the first error for the message
                        } catch (ignored: RuntimeException) {
                        }
                    }
                }
                if (!ok && d.cloud && key(p).isNotEmpty()) {
                    try {
                        on = cloudPower(key(p), d, force)
                        ok = true
                    } catch (e: IOException) {
                        err = message(e)
                    }
                }
                msg = statusLine(d.title(), ok, on, err)
                if (ok && on != null) p.put("goveeOn_" + d.id, on)
            }
            val fok = ok
            val fon = on
            MAIN.post { cb.done(fok, msg, fon) }
        }
    }

    /** The one-line result shown after a power command. */
    @JvmStatic
    fun statusLine(title: String, ok: Boolean, on: Boolean?, error: String?): String {
        if (ok) return title + (if (on == null) " • COMMAND SENT" else if (on) " • ON" else " • OFF")
        return title + " • NO RESPONSE" + (if (error != null) " ($error)" else "")
    }

    // ---- LAN API -------------------------------------------------------------------------------

    /**
     * Socket on port 4002 (where Govee devices answer), joined to 239.255.255.250 on the Wi-Fi
     * interface, with outgoing multicast pinned to Wi-Fi so it doesn't leave via mobile data.
     */
    @Throws(IOException::class)
    private fun listen(timeoutMs: Int): MulticastSocket {
        val s = MulticastSocket(null)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(LISTEN_PORT))
            s.soTimeout = timeoutMs
            s.timeToLive = MULTICAST_TTL
            val wifi = wifiInterface()
            val group = InetAddress.getByName(MULTICAST)
            try {
                if (wifi != null) {
                    s.networkInterface = wifi
                    s.joinGroup(InetSocketAddress(group, LISTEN_PORT), wifi)
                } else {
                    s.joinGroup(InetSocketAddress(group, LISTEN_PORT), null)
                }
            } catch (e: IOException) {
                // joining can fail on some networks; unicast replies still arrive on this socket
            }
            return s
        } catch (e: Exception) {
            s.close()
            throw e
        }
    }

    /** "wlan0 192.168.1.20" for the interface the last LAN operation used (shown in settings). */
    @JvmField
    @Volatile
    var lastInterface: String = ""

    /** The Wi-Fi network interface (has an IPv4 address and multicast), or null. */
    @JvmStatic
    fun wifiInterface(): NetworkInterface? {
        var fallback: NetworkInterface? = null
        try {
            val all = NetworkInterface.getNetworkInterfaces()
            while (all != null && all.hasMoreElements()) {
                val n = all.nextElement()
                if (!n.isUp || n.isLoopback || !n.supportsMulticast()) continue
                var v4 = false
                val addrs = n.inetAddresses
                while (addrs.hasMoreElements()) if (addrs.nextElement() is Inet4Address) v4 = true
                if (!v4) continue
                val name = n.name.lowercase(Locale.US)
                if (isWifiName(name)) {
                    lastInterface = describe(n)
                    return n
                }
                if (fallback == null && !isMobileOrVpnName(name)) fallback = n
            }
        } catch (ignored: IOException) {
            // no interface list: fall through
        } catch (ignored: RuntimeException) {
        }
        lastInterface = if (fallback != null) describe(fallback) else "NONE"
        return fallback
    }

    /** Wi-Fi interface names on Android (wlan0, Samsung's swlan0 hotspot). */
    @JvmStatic
    fun isWifiName(name: String): Boolean = name.startsWith("wlan") || name.startsWith("swlan")

    /** Mobile data (Qualcomm rmnet, MediaTek ccmni) and VPN tunnels: never used for LAN control. */
    @JvmStatic
    fun isMobileOrVpnName(name: String): Boolean = name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("tun")

    private fun describe(n: NetworkInterface): String {
        val addrs = n.inetAddresses
        while (addrs.hasMoreElements()) {
            val a = addrs.nextElement()
            if (a is Inet4Address) return n.name + " " + a.hostAddress
        }
        return n.name
    }

    @Throws(IOException::class)
    private fun send(s: DatagramSocket, host: String, port: Int, json: String) {
        val b = json.toByteArray(Charsets.UTF_8)
        s.send(DatagramPacket(b, b.size, InetAddress.getByName(host), port))
    }

    /**
     * Waits for a Govee reply with the given command name (optionally only from one address).
     *
     * @return the reply's `data` object with the sender's address added as "_from", or null on timeout
     */
    @Throws(IOException::class)
    private fun receive(s: DatagramSocket, cmd: String, fromIp: String?): JSONObject? {
        val buf = ByteArray(MAX_DATAGRAM)
        val until = System.currentTimeMillis() + max(200, s.soTimeout)
        while (System.currentTimeMillis() < until) {
            val pk = DatagramPacket(buf, buf.size)
            try {
                s.receive(pk)
            } catch (e: SocketTimeoutException) {
                return null
            }
            val from = pk.address.hostAddress
            if (fromIp != null && from != fromIp) continue
            val data = parseLanReply(String(pk.data, 0, pk.length, Charsets.UTF_8), cmd, from)
            if (data != null) return data
        }
        return null
    }

    /**
     * Parses one LAN datagram: {"msg":{"cmd":…,"data":{…}}}.
     *
     * @return the data object (with "_from" = sender) when the command matches, else null
     */
    @JvmStatic
    fun parseLanReply(text: String, cmd: String, from: String?): JSONObject? =
        try {
            val msg = JSONObject(text).optJSONObject("msg")
            if (msg == null || cmd != msg.optString("cmd")) {
                null
            } else {
                val data = msg.optJSONObject("data")
                data?.put("_from", from ?: "")
            }
        } catch (e: JSONException) {
            null // not a Govee message
        }

    /**
     * Turns a LAN "scan" reply into a device: id, model, and the address it reported (or the
     * address it answered from). Null when it has no id or no usable address.
     */
    @JvmStatic
    fun deviceFromScan(data: JSONObject): Device? {
        val dev = Device()
        dev.id = data.optString("device")
        dev.sku = data.optString("sku")
        val ip = data.optString("ip")
        dev.ip = if (isValidIpv4(ip)) ip else data.optString("_from")
        if (!isValidIpv4(dev.ip)) dev.ip = ""
        dev.name = "GOVEE " + dev.sku
        return if (dev.id.isEmpty() || dev.ip.isEmpty()) null else dev
    }

    /** Multicast discovery over `windowMs`; blocks, background thread only. */
    @JvmStatic
    @Throws(IOException::class)
    fun lanScan(c: Context, windowMs: Int): List<Device> {
        init(c)
        val lock = multicastLock(c)
        val out = HashMap<String, Device>()
        val order = ArrayList<Device>()
        try {
            listen(400).use { s ->
                val end = System.currentTimeMillis() + windowMs
                send(s, MULTICAST, SCAN_PORT, SCAN_MSG)
                var sent = 1
                var resendAt = System.currentTimeMillis() + SCAN_RESEND_MS
                while (System.currentTimeMillis() < end) {
                    if (sent < SCAN_SENDS && System.currentTimeMillis() > resendAt) {
                        send(s, MULTICAST, SCAN_PORT, SCAN_MSG)
                        sent++
                        resendAt = System.currentTimeMillis() + SCAN_RESEND_MS
                    }
                    val d = receive(s, "scan", null)
                    val dev = (if (d == null) null else deviceFromScan(d)) ?: continue
                    val k = dev.id.lowercase(Locale.US)
                    if (!out.containsKey(k)) {
                        out[k] = dev
                        order.add(dev)
                    }
                }
            }
        } finally {
            if (lock != null && lock.isHeld) lock.release()
        }
        return order
    }

    /** Reads on/off over LAN (unless forced) and switches. @return the new state */
    @Throws(IOException::class)
    private fun lanPower(ip: String, force: Boolean?): Boolean {
        if (!isValidIpv4(ip)) throw IOException("BAD ADDRESS")
        val lock = multicastLock(null)
        try {
            listen(900).use { s ->
                val target: Boolean
                if (force != null) {
                    target = force
                } else {
                    var st: JSONObject? = null
                    var i = 0
                    while (i < 2 && st == null) {
                        send(s, ip, CMD_PORT, STATUS_MSG)
                        st = receive(s, "devStatus", ip)
                        i++
                    }
                    if (st == null) throw IOException("LAN TIMEOUT")
                    target = st.optInt("onOff", 0) != 1
                }
                send(s, ip, CMD_PORT, turnMessage(target))
                return target
            }
        } finally {
            if (lock != null && lock.isHeld) lock.release()
        }
    }

    /** The LAN "turn" command for on (1) or off (0). */
    @JvmStatic
    fun turnMessage(on: Boolean): String = "{\"msg\":{\"cmd\":\"turn\",\"data\":{\"value\":" + (if (on) 1 else 0) + "}}}"

    /** Application context for multicast locks requested without a context (always the app context: no leaks). */
    @Volatile
    private var appContext: Context? = null

    @JvmStatic
    fun init(c: Context?) {
        if (appContext == null && c != null) appContext = c.applicationContext
    }

    /** Holds a Wi-Fi multicast lock (many phones drop multicast replies without one); null if unavailable. */
    private fun multicastLock(c: Context?): WifiManager.MulticastLock? {
        val ctx = c?.applicationContext ?: appContext ?: return null
        return try {
            val wm = ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager? ?: return null
            val l = wm.createMulticastLock("trekui-govee")
            l.setReferenceCounted(false)
            l.acquire()
            l
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * Adds a light by its IP address (for networks that block discovery): asks it to identify
     * itself (unicast scan) and for its status. Blocks; background thread only.
     *
     * @return the device, or null if it didn't answer
     * @throws IOException for an address that isn't a LAN IPv4 address, or a socket failure
     */
    @JvmStatic
    @Throws(IOException::class)
    @Suppress("ThrowsCount", "SwallowedException") // rethrows the IO task's own cause
    fun probe(c: Context, ip: String?): Device? {
        if (ip == null || !isValidIpv4(ip)) throw IOException("ENTER AN ADDRESS LIKE 192.168.1.42")
        // the socket work runs on IO like every other LAN operation, so only one socket binds 4002
        if (Thread.currentThread().name.startsWith(IO_NAME + "-")) return probeNow(c, ip)
        val f = IO.submit(Callable { probeNow(c, ip) })
        try {
            return f.get()
        } catch (e: ExecutionException) {
            when (val cause = e.cause) {
                is IOException -> throw cause
                is RuntimeException -> throw cause
                else -> throw IOException(cause?.message ?: "PROBE FAILED", cause)
            }
        } catch (e: InterruptedException) {
            f.cancel(true)
            Thread.currentThread().interrupt()
            throw IOException("PROBE INTERRUPTED", e)
        }
    }

    /** [probe]'s socket work; runs on [IO] only. */
    @Throws(IOException::class)
    private fun probeNow(c: Context, ip: String): Device? {
        val lock = multicastLock(c)
        try {
            listen(700).use { s ->
                var id: JSONObject? = null
                var st: JSONObject? = null
                var i = 0
                while (i < 3 && id == null) {
                    send(s, ip, SCAN_PORT, SCAN_MSG)
                    id = receive(s, "scan", ip)
                    i++
                }
                i = 0
                while (i < 2 && st == null) {
                    send(s, ip, CMD_PORT, STATUS_MSG)
                    st = receive(s, "devStatus", ip)
                    i++
                }
                if (id == null && st == null) return null
                val d = Device()
                d.ip = ip
                d.sku = id?.optString("sku") ?: ""
                d.id = if (id != null && id.optString("device").isNotEmpty()) id.optString("device") else "IP:$ip"
                d.name = "GOVEE " + (if (d.sku.isEmpty()) ip else d.sku)
                return d
            }
        } finally {
            if (lock != null && lock.isHeld) lock.release()
        }
    }

    // ---- cloud API -----------------------------------------------------------------------------

    /** One cloud API call; Govee's HTTP and API-level errors become short console messages. */
    @Throws(IOException::class)
    private fun http(key: String, method: String, path: String, body: JSONObject?): JSONObject {
        val headers = HashMap<String, String>()
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json"
        headers["Govee-API-Key"] = key
        val r = Http.request(
            method,
            CLOUD + path,
            headers,
            body?.toString()?.toByteArray(Charsets.UTF_8),
            CLOUD_TIMEOUT_MS,
            CLOUD_TIMEOUT_MS,
        )
        return checkReply(r.code, r.body)
    }

    /** Validates a cloud reply: maps HTTP and Govee "code" errors to messages, returns the JSON. */
    @JvmStatic
    @Throws(IOException::class)
    fun checkReply(code: Int, text: String): JSONObject {
        if (code == 401 || code == 403) throw IOException("API KEY REJECTED")
        if (code == 429) throw IOException("GOVEE RATE LIMIT • TRY AGAIN IN A MINUTE")
        if (code >= 400) throw IOException("GOVEE ERROR $code")
        try {
            val o = JSONObject(text)
            val apiCode = o.optInt("code", 200)
            if (apiCode != 200) {
                throw IOException(o.optString("msg", o.optString("message", "GOVEE ERROR $apiCode")).uppercase(Locale.US))
            }
            return o
        } catch (e: JSONException) {
            throw IOException("UNEXPECTED REPLY FROM GOVEE")
        }
    }

    /** The account's switchable lights from the cloud API; blocks, background thread only. */
    @JvmStatic
    @Throws(IOException::class)
    fun cloudDevices(key: String?): List<Device> {
        if (key == null || key.trim().isEmpty()) throw IOException("NO API KEY")
        return parseCloudDevices(http(key.trim(), "GET", "/user/devices", null))
    }

    /** Devices with an on/off capability from a /user/devices reply. */
    @JvmStatic
    fun parseCloudDevices(reply: JSONObject): List<Device> {
        val out = ArrayList<Device>()
        val a = reply.optJSONArray("data") ?: return out
        for (i in 0 until a.length()) {
            val d = a.optJSONObject(i)
            if (d == null || !hasPowerSwitch(d.optJSONArray("capabilities"))) continue
            val dev = Device()
            dev.id = d.optString("device")
            if (dev.id.isEmpty()) continue
            dev.sku = d.optString("sku")
            dev.name = d.optString("deviceName", "GOVEE " + dev.sku).uppercase(Locale.getDefault())
            dev.cloud = true
            out.add(dev)
        }
        return out
    }

    /** True when the capability list has a powerSwitch (or isn't given: let the person try). */
    @JvmStatic
    fun hasPowerSwitch(caps: JSONArray?): Boolean {
        if (caps == null) return true
        for (i in 0 until caps.length()) {
            val c = caps.optJSONObject(i)
            if (c != null && "powerSwitch" == c.optString("instance")) return true
        }
        return false
    }

    @Throws(JSONException::class)
    private fun payload(d: Device): JSONObject =
        JSONObject().put("requestId", UUID.randomUUID().toString())
            .put("payload", JSONObject().put("sku", d.sku).put("device", d.id))

    /** Reads powerSwitch from a device-state reply; null if it isn't reported. */
    @JvmStatic
    fun powerFromState(reply: JSONObject): Boolean? {
        val p = reply.optJSONObject("payload")
        val caps = p?.optJSONArray("capabilities") ?: return null
        for (i in 0 until caps.length()) {
            val c = caps.optJSONObject(i)
            if (c == null || "powerSwitch" != c.optString("instance")) continue
            val st = c.optJSONObject("state")
            if (st == null || !st.has("value") || "" == st.opt("value")) return null
            return st.optInt("value", 0) == 1
        }
        return null
    }

    /** Reads (unless forced) and sets the power state through the cloud. @return the new state */
    @Throws(IOException::class)
    private fun cloudPower(key: String, d: Device, force: Boolean?): Boolean {
        try {
            val target: Boolean =
                if (force != null) {
                    force
                } else {
                    val cur = powerFromState(http(key, "POST", "/device/state", payload(d)))
                    cur == null || !cur
                }
            val body = payload(d)
            body.getJSONObject("payload").put(
                "capability",
                JSONObject().put("type", "devices.capabilities.on_off").put("instance", "powerSwitch").put("value", if (target) 1 else 0),
            )
            http(key, "POST", "/device/control", body)
            return target
        } catch (e: JSONException) {
            throw IOException("COULD NOT BUILD REQUEST")
        }
    }
}
