package com.gungreeneyes.trekui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.math.max

/**
 * Google Nest thermostats through Google's Smart Device Management (Device Access) API, used by
 * [ThermostatDialog], the home thermostat tiles and CONFIG › DEVICES.
 *
 * Setup (once): a Device Access project (one-time registration fee to Google), a Google Cloud
 * OAuth "Web application" client with redirect https://www.google.com, then account linking:
 * the browser ends on google.com with ?code=… in the address, which the owner pastes back.
 * The client secret and tokens are stored in [Prefs.SECRETS_FILE] (excluded from backup); access
 * tokens last an hour.
 *
 * Every request blocks and runs through [async] on a background thread with timeouts
 * (see [Http]); only the application context is kept by background work. Parsing
 * (device lists, thermostat state, error bodies, pasted OAuth codes) is pure and unit-tested.
 *
 * Java interop: SettingsActivity and MainActivity call these as statics ([JvmStatic]).
 */
internal object Nest {
    private const val SDM = "https://smartdevicemanagement.googleapis.com/v1/"
    private const val TOKEN = "https://www.googleapis.com/oauth2/v4/token"
    const val REDIRECT = "https://www.google.com"
    private const val TRAIT = "sdm.devices.traits."
    private const val THERMOSTAT_TYPE = "sdm.devices.types.THERMOSTAT"

    /** SDM device resource names: enterprises/{project}/devices/{id}. */
    private val DEVICE_NAME = Regex("enterprises/[A-Za-z0-9_\\-]+/devices/[A-Za-z0-9_\\-]+")

    /** Device Access project ids are UUIDs; accept any id of safe characters. */
    private val PROJECT_ID = Regex("[A-Za-z0-9_\\-]{1,128}")

    /** Refresh this long before the token's stated expiry, so a request never races it. */
    private const val TOKEN_MARGIN_S = 120L

    private val IO: ExecutorService = Http.newExecutor("trekui-nest")
    private val MAIN = Handler(Looper.getMainLooper())

    /** A linked thermostat: SDM resource name and its display label. */
    class Device {
        @JvmField var name: String = ""

        @JvmField var label: String = ""

        fun toJson(): JSONObject {
            val o = JSONObject()
            try {
                o.put("name", name).put("label", label)
            } catch (ignored: JSONException) {
            }
            return o
        }
    }

    /** A thermostat reading. Temperatures in °C (the API's unit); NaN when not reported. */
    class State {
        @JvmField var ambientC = Double.NaN

        @JvmField var heatC = Double.NaN

        @JvmField var coolC = Double.NaN

        @JvmField var ecoHeatC = Double.NaN

        @JvmField var ecoCoolC = Double.NaN

        /** Relative humidity in percent, -1 when not reported. */
        @JvmField var humidity = -1

        @JvmField var mode = "OFF"

        @JvmField var eco = "OFF"

        @JvmField var hvac = "OFF"

        @JvmField var scale = "CELSIUS"

        @JvmField var connectivity = "ONLINE"

        @JvmField var label = ""

        /** Modes the thermostat offers (HEAT, COOL, HEATCOOL, OFF); empty when not reported. */
        @JvmField var modes: MutableList<String> = ArrayList()

        @JvmField var ecoAvailable = false

        fun fahrenheit(): Boolean = "FAHRENHEIT" == scale

        fun online(): Boolean = "OFFLINE" != connectivity

        fun ecoOn(): Boolean = "MANUAL_ECO" == eco
    }

    /** Main-thread result of [async]: the value, or an error message (exactly one is non-null). */
    fun interface Result<T> {
        fun done(value: T?, error: String?)
    }

    // ---- configuration ---------------------------------------------------------------------------

    @JvmStatic
    fun project(p: Prefs): String = p.s("nestProject", "").orEmpty().trim()

    @JvmStatic
    fun clientId(p: Prefs): String = p.s("nestClientId", "").orEmpty().trim()

    @JvmStatic
    fun secret(p: Prefs): String = p.secret("nestSecret").trim()

    @JvmStatic
    fun configured(p: Prefs): Boolean = project(p).isNotEmpty() && clientId(p).isNotEmpty() && secret(p).isNotEmpty()

    @JvmStatic
    fun linked(p: Prefs): Boolean = p.secret("nestRefresh").isNotEmpty()

    /** True for a project id made of safe characters (it is placed in URLs). */
    @JvmStatic
    fun validProject(id: String?): Boolean = id != null && PROJECT_ID.matches(id)

    /** True for an SDM device resource name (it is placed in request URLs). */
    @JvmStatic
    fun validDeviceName(name: String?): Boolean = name != null && DEVICE_NAME.matches(name)

    /** Google's account-linking page for this project and OAuth client. */
    @JvmStatic
    fun authUrl(p: Prefs): String = authUrl(project(p), clientId(p))

    @JvmStatic
    fun authUrl(project: String?, clientId: String?): String =
        "https://nestservices.google.com/partnerconnections/" + enc(project) + "/auth?redirect_uri=" + enc(REDIRECT) +
            "&access_type=offline&prompt=consent&client_id=" + enc(clientId) +
            "&response_type=code&scope=" + enc("https://www.googleapis.com/auth/sdm.service")

    @JvmStatic
    fun authUri(p: Prefs): Uri = Uri.parse(authUrl(p))

    /**
     * Pulls the authorization code out of a pasted google.com address (or accepts a bare code).
     *
     * @return the code, or "" when the text doesn't contain one
     */
    @JvmStatic
    fun codeFrom(pasted: String?): String {
        val s = pasted?.trim() ?: ""
        if (s.isEmpty()) return ""
        if (s.contains("code=")) {
            var q = s.substring(if (s.indexOf('?') >= 0) s.indexOf('?') + 1 else 0)
            val hash = q.indexOf('#')
            if (hash >= 0) q = q.substring(0, hash)
            for (part in q.split("&")) {
                if (part.startsWith("code=")) {
                    return try {
                        URLDecoder.decode(part.substring(5), "UTF-8")
                    } catch (e: IllegalArgumentException) {
                        ""
                    }
                }
            }
        }
        // a bare code: Google's start with "4/" and contain no spaces; anything else with a '/' is a URL
        val looksLikeUrl = s.contains("/") && !s.startsWith("4/")
        return if (s.contains(" ") || looksLikeUrl) "" else s
    }

    /** The thermostats found at linking time (from preferences). */
    @JvmStatic
    fun devices(p: Prefs): List<Device> = parseStoredDevices(p.s("nestDevices", "[]"))

    @JvmStatic
    fun parseStoredDevices(json: String?): List<Device> {
        val out = ArrayList<Device>()
        try {
            val a = JSONArray(json)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i)
                if (o == null || !validDeviceName(o.optString("name"))) continue
                val d = Device()
                d.name = o.optString("name")
                d.label = o.optString("label")
                out.add(d)
            }
        } catch (ignored: JSONException) {
            // corrupt preference: no devices
        }
        return out
    }

    @JvmStatic
    fun find(p: Prefs, name: String?): Device? = devices(p).firstOrNull { it.name == name }

    /** Forgets the link (tokens and devices); the OAuth client settings stay. */
    @JvmStatic
    fun unlink(p: Prefs) {
        p.putSecret("nestRefresh", "")
        p.putSecret("nestAccess", "")
        p.put("nestAccessExp", 0L)
        p.put("nestDevices", "[]")
    }

    // ---- async wrapper ------------------------------------------------------------------------------

    /** Blocking work run by [async]. */
    fun interface Job<T> {
        @Throws(IOException::class)
        fun run(): T
    }

    /**
     * Runs a job on the Nest background thread and delivers the result on the main thread.
     * Jobs run one at a time, so a token refresh is never done twice in parallel.
     */
    @JvmStatic
    fun <T> async(job: Job<T>, cb: Result<T>) {
        IO.execute {
            var v: T? = null
            var err: String? = null
            try {
                v = job.run()
            } catch (e: IOException) {
                err = e.message ?: e.javaClass.simpleName.uppercase(Locale.US)
            } catch (e: RuntimeException) {
                err = e.message ?: e.javaClass.simpleName.uppercase(Locale.US)
            }
            val fv = v
            val fe = err
            MAIN.post { cb.done(fv, fe) }
        }
    }

    // ---- OAuth ----------------------------------------------------------------------------------------

    /** Exchanges a pasted authorization code for tokens and stores them. Background thread only. */
    @JvmStatic
    @Throws(IOException::class)
    fun link(c: Context, code: String?) {
        val p = Prefs.get(c.applicationContext)
        if (code.isNullOrEmpty()) throw IOException("NO AUTHORIZATION CODE FOUND IN THAT TEXT")
        val o = post(
            TOKEN,
            "client_id=" + enc(clientId(p)) + "&client_secret=" + enc(secret(p)) + "&code=" + enc(code) +
                "&grant_type=authorization_code&redirect_uri=" + enc(REDIRECT),
        )
        val refresh = o.optString("refresh_token")
        if (refresh.isEmpty()) throw IOException("GOOGLE DIDN'T RETURN A REFRESH TOKEN • LINK AGAIN")
        p.putSecret("nestRefresh", refresh)
        saveAccess(p, o)
    }

    private fun saveAccess(p: Prefs, o: JSONObject) {
        p.putSecret("nestAccess", o.optString("access_token"))
        p.put("nestAccessExp", accessExpiry(System.currentTimeMillis(), o.optLong("expires_in", 3599)))
    }

    /** When a token issued now with the given lifetime should be treated as expired (at least a minute ahead). */
    @JvmStatic
    fun accessExpiry(nowMs: Long, expiresInS: Long): Long = nowMs + max(60L, expiresInS - TOKEN_MARGIN_S) * 1000L

    /** A valid access token, refreshing it when it has expired. */
    @Synchronized
    @Throws(IOException::class)
    private fun token(c: Context): String {
        val p = Prefs.get(c)
        if (!linked(p)) throw IOException("NEST NOT LINKED • CONFIG › DEVICES")
        val access = p.secret("nestAccess")
        if (access.isNotEmpty() && System.currentTimeMillis() < p.l("nestAccessExp", 0L)) return access
        val o: JSONObject =
            try {
                post(
                    TOKEN,
                    "client_id=" + enc(clientId(p)) + "&client_secret=" + enc(secret(p)) +
                        "&refresh_token=" + enc(p.secret("nestRefresh")) + "&grant_type=refresh_token",
                )
            } catch (e: IOException) {
                if (e.message.toString().contains("invalid_grant")) {
                    throw IOException("GOOGLE ENDED THE LINK • RELINK IN CONFIG › DEVICES (PUBLISH THE OAUTH APP TO AVOID THE 7-DAY LIMIT)")
                }
                throw e
            }
        saveAccess(p, o)
        return p.secret("nestAccess")
    }

    // ---- SDM ------------------------------------------------------------------------------------------

    /** Lists the project's thermostats and stores them. Background thread only. */
    @JvmStatic
    @Throws(IOException::class)
    fun fetchDevices(c: Context): List<Device> {
        val app = c.applicationContext
        val p = Prefs.get(app)
        if (!validProject(project(p))) throw IOException("CHECK THE DEVICE ACCESS PROJECT ID")
        val out = parseDevices(request(app, "GET", SDM + "enterprises/" + project(p) + "/devices", null))
        val store = JSONArray()
        for (d in out) store.put(d.toJson())
        p.put("nestDevices", store.toString())
        return out
    }

    /** Thermostats from a devices.list reply (other device types and bad names are skipped). */
    @JvmStatic
    fun parseDevices(reply: JSONObject): List<Device> {
        val out = ArrayList<Device>()
        val a = reply.optJSONArray("devices") ?: return out
        for (i in 0 until a.length()) {
            val d = a.optJSONObject(i)
            if (d == null || THERMOSTAT_TYPE != d.optString("type")) continue
            if (!validDeviceName(d.optString("name"))) continue
            val dev = Device()
            dev.name = d.optString("name")
            dev.label = labelOf(d)
            out.add(dev)
        }
        return out
    }

    /** The thermostat's custom name, else its room, else "THERMOSTAT"; upper case. */
    @JvmStatic
    fun labelOf(device: JSONObject): String {
        val traits = device.optJSONObject("traits")
        val info = traits?.optJSONObject(TRAIT + "Info")
        var name = info?.optString("customName") ?: ""
        if (name.isEmpty()) {
            val rel = device.optJSONArray("parentRelations")
            val r = rel?.optJSONObject(0)
            if (r != null) name = r.optString("displayName")
        }
        return (if (name.isEmpty()) "THERMOSTAT" else name).uppercase(Locale.getDefault())
    }

    /** Reads one thermostat. Background thread only. */
    @JvmStatic
    @Throws(IOException::class)
    fun state(c: Context, device: String?): State = parse(request(c.applicationContext, "GET", deviceUrl(device), null))

    @Throws(IOException::class)
    private fun deviceUrl(device: String?): String {
        if (!validDeviceName(device)) throw IOException("UNKNOWN THERMOSTAT • RELINK IN CONFIG › DEVICES")
        return SDM + device
    }

    /** Parses a device resource into a [State]. Pure apart from org.json (unit-tested). */
    @JvmStatic
    fun parse(device: JSONObject): State {
        val s = State()
        s.label = labelOf(device)
        val t = device.optJSONObject("traits") ?: return s
        t.optJSONObject(TRAIT + "Temperature")?.let { x -> s.ambientC = x.optDouble("ambientTemperatureCelsius", Double.NaN) }
        t.optJSONObject(TRAIT + "Humidity")?.let { x ->
            val h = x.optDouble("ambientHumidityPercent", -1.0)
            s.humidity = if (h.isNaN()) -1 else Math.round(h).toInt()
        }
        t.optJSONObject(TRAIT + "ThermostatMode")?.let { x ->
            s.mode = x.optString("mode", "OFF")
            val m = x.optJSONArray("availableModes")
            if (m != null) for (i in 0 until m.length()) s.modes.add(m.optString(i))
        }
        t.optJSONObject(TRAIT + "ThermostatEco")?.let { x ->
            s.eco = x.optString("mode", "OFF")
            s.ecoHeatC = x.optDouble("heatCelsius", Double.NaN)
            s.ecoCoolC = x.optDouble("coolCelsius", Double.NaN)
            val m = x.optJSONArray("availableModes")
            if (m != null) for (i in 0 until m.length()) if ("MANUAL_ECO" == m.optString(i)) s.ecoAvailable = true
        }
        t.optJSONObject(TRAIT + "ThermostatHvac")?.let { x -> s.hvac = x.optString("status", "OFF") }
        t.optJSONObject(TRAIT + "ThermostatTemperatureSetpoint")?.let { x ->
            s.heatC = x.optDouble("heatCelsius", Double.NaN)
            s.coolC = x.optDouble("coolCelsius", Double.NaN)
        }
        t.optJSONObject(TRAIT + "Settings")?.let { x -> s.scale = x.optString("temperatureScale", "CELSIUS") }
        t.optJSONObject(TRAIT + "Connectivity")?.let { x -> s.connectivity = x.optString("status", "ONLINE") }
        return s
    }

    /** Sends an SDM command ("ThermostatMode.SetMode" etc.). Background thread only. */
    @JvmStatic
    @Throws(IOException::class)
    fun command(c: Context, device: String?, command: String, params: JSONObject) {
        try {
            request(
                c.applicationContext,
                "POST",
                deviceUrl(device) + ":executeCommand",
                JSONObject().put("command", "sdm.devices.commands.$command").put("params", params),
            )
        } catch (e: JSONException) {
            throw IOException("COULD NOT BUILD COMMAND")
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun setMode(c: Context, device: String?, mode: String) {
        try {
            command(c, device, "ThermostatMode.SetMode", JSONObject().put("mode", mode))
        } catch (e: JSONException) {
            throw IOException("COULD NOT BUILD COMMAND")
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun setEco(c: Context, device: String?, on: Boolean) {
        try {
            command(c, device, "ThermostatEco.SetMode", JSONObject().put("mode", if (on) "MANUAL_ECO" else "OFF"))
        } catch (e: JSONException) {
            throw IOException("COULD NOT BUILD COMMAND")
        }
    }

    /** Sends the setpoint command that matches the mode (HEAT → SetHeat, COOL → SetCool, HEATCOOL → SetRange). */
    @JvmStatic
    @Throws(IOException::class)
    fun setTemps(c: Context, device: String?, mode: String?, heatC: Double, coolC: Double) {
        try {
            val p = JSONObject()
            when (mode) {
                "HEAT" -> command(c, device, "ThermostatTemperatureSetpoint.SetHeat", p.put("heatCelsius", round1(heatC)))
                "COOL" -> command(c, device, "ThermostatTemperatureSetpoint.SetCool", p.put("coolCelsius", round1(coolC)))
                "HEATCOOL" -> command(
                    c,
                    device,
                    "ThermostatTemperatureSetpoint.SetRange",
                    p.put("heatCelsius", round1(heatC)).put("coolCelsius", round1(coolC)),
                )
                else -> throw IOException("SETPOINTS CAN'T CHANGE WHILE THE SYSTEM IS OFF")
            }
        } catch (e: JSONException) {
            throw IOException("COULD NOT BUILD COMMAND")
        }
    }

    /** Rounds to one decimal place (the API's setpoint precision). */
    @JvmStatic
    fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0

    @JvmStatic
    fun toF(c: Double): Double = Weather.cToF(c)

    @JvmStatic
    fun toC(f: Double): Double = Weather.fToC(f)

    // ---- HTTP -------------------------------------------------------------------------------------------

    /** An authorised SDM request; a stale token is refreshed once and the request retried. */
    @Throws(IOException::class)
    private fun request(c: Context, method: String, url: String, body: JSONObject?): JSONObject {
        val tok = token(c)
        return try {
            http(method, url, body?.toString(), "application/json", tok)
        } catch (e: IOException) {
            if ("UNAUTHENTICATED" != e.message) throw e
            Prefs.get(c).put("nestAccessExp", 0L)
            http(method, url, body?.toString(), "application/json", token(c))
        }
    }

    @Throws(IOException::class)
    private fun post(url: String, form: String): JSONObject = http("POST", url, form, "application/x-www-form-urlencoded", null)

    @Throws(IOException::class)
    private fun http(method: String, url: String, body: String?, type: String, bearer: String?): JSONObject {
        val headers = HashMap<String, String>()
        headers["Content-Type"] = type
        headers["Accept"] = "application/json"
        if (bearer != null) headers["Authorization"] = "Bearer $bearer"
        val r = Http.request(method, url, headers, body?.toByteArray(Charsets.UTF_8), Http.CONNECT_TIMEOUT_MS, Http.READ_TIMEOUT_MS)
        if (r.code >= 400) throw IOException(errorText(r.code, r.body))
        return try {
            if (r.body.trim().isEmpty()) JSONObject() else JSONObject(r.body)
        } catch (e: JSONException) {
            throw IOException("UNEXPECTED REPLY FROM GOOGLE")
        }
    }

    /** Turns Google's error JSON (SDM or OAuth style) into a short console message. */
    @JvmStatic
    fun errorText(code: Int, body: String?): String {
        var status = ""
        var msg = ""
        var oauth = ""
        try {
            val o = JSONObject(body ?: "")
            val e = o.optJSONObject("error")
            if (e != null) {
                status = e.optString("status")
                msg = e.optString("message")
            } else {
                oauth = o.optString("error") + " " + o.optString("error_description")
            }
        } catch (ignored: JSONException) {
            // not JSON: fall back to the status code
        }
        if (code == 401 || "UNAUTHENTICATED" == status) return "UNAUTHENTICATED"
        if (oauth.trim().isNotEmpty()) return oauth.trim()
        if (code == 429) return "GOOGLE RATE LIMIT • TRY AGAIN IN A MINUTE"
        if ("FAILED_PRECONDITION" == status) return if (msg.isEmpty()) "THERMOSTAT REJECTED THAT IN ITS CURRENT MODE" else msg.uppercase(Locale.US)
        if (code == 403 || "PERMISSION_DENIED" == status) return "ACCESS DENIED • CHECK THE PROJECT ID AND THAT THE THERMOSTAT WAS SHARED WHEN LINKING"
        if (code == 404) return "NOT FOUND • CHECK THE DEVICE ACCESS PROJECT ID"
        return (if (msg.isEmpty()) "GOOGLE ERROR $code" else msg).uppercase(Locale.US)
    }

    private fun enc(s: String?): String = URLEncoder.encode(s ?: "", "UTF-8")
}
