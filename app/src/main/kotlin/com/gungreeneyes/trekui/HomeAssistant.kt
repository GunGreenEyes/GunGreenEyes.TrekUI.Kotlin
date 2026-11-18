package com.gungreeneyes.trekui

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * Home Assistant as a smart-home source next to [Govee] and [Nest]: lights (on/off,
 * brightness, colour), switches, fans and input booleans, climate (through
 * [ThermostatDialog]), scenes and scripts, over Home Assistant's REST API
 * (`GET /api/states`, `POST /api/services/<domain>/<service>`).
 *
 * Setup: the owner enters the base address and a long-lived access token (Home Assistant ›
 * profile › Security › Long-lived access tokens) in CONFIG › DEVICES, then IMPORT ENTITIES.
 * Addresses are HTTPS; plain http:// is accepted only for a local-network name ending in
 * `.local` or `.home.arpa` (see [Http.isLocalHostName] and
 * `res/xml/network_security_config.xml`), with a warning in CONFIG.
 *
 * Privacy: the address, token and imported entity list live in their own private preferences
 * file [FILE], which is excluded from Android backup and device transfer, and every key
 * starts with `ha_` (the token key is [KEY_TOKEN]) so settings export can skip it.
 * Nothing is sent anywhere but the owner's own Home Assistant.
 *
 * Requests block and run through [async] on a background thread with [Http]'s
 * timeouts; only the application context is used. URL checks, JSON parsing, the mapping of a
 * climate entity onto [Nest.State] and service-call building are pure and unit-tested.
 *
 * Java interop: SettingsActivity and MainActivity call these as statics ([JvmStatic]).
 */
internal object HomeAssistant {
    /** Private preferences file holding the address, token and entities (excluded from backup). */
    const val FILE = "trekui_ha"
    const val KEY_URL = "ha_url"
    const val KEY_TOKEN = "ha_token"
    const val KEY_ENTITIES = "ha_entities"
    const val KEY_FAHRENHEIT = "ha_unit_f"

    /** Domains console can control, in the order they are listed. */
    @JvmField
    val DOMAINS = arrayOf("light", "switch", "input_boolean", "fan", "climate", "scene", "script")

    /** Most entities kept after an import (a large install can have thousands). */
    const val MAX_ENTITIES = 300

    /** Largest `/api/states` reply accepted; big installs can reach a few MB. */
    const val MAX_STATES_BYTES = 8 shl 20

    /** Tile argument suffix: open the control dialog instead of toggling. */
    const val PANEL_SUFFIX = "#panel"

    /** Results of [checkUrl]. */
    const val URL_OK = 0
    const val URL_LOCAL_HTTP = 1
    const val URL_INVALID = 2
    const val URL_INSECURE = 3

    /** entity_id: domain "." object id, lower-case letters, digits and underscores. */
    private val ENTITY_ID = Regex("[a-z0-9_]+\\.[a-z0-9_]+")

    /** Service and domain names placed in the request path. */
    private val NAME = Regex("[a-z0-9_]+")

    /** Colour modes that accept an rgb_color. */
    private val COLOR_MODES = listOf("hs", "rgb", "rgbw", "rgbww", "xy")

    private val IO: ExecutorService = Http.newExecutor("trekui-ha")
    private val MAIN = Handler(Looper.getMainLooper())

    /** An imported entity (id and display name), plus its live state and attributes once fetched. */
    class Entity {
        @JvmField var id: String = ""

        @JvmField var name: String = ""

        /** Live state ("on", "off", "heat", "unavailable"…); "" when only the stored record is known. */
        @JvmField var state: String = ""

        /** Live attributes; empty when only the stored record is known. */
        @JvmField var attributes: JSONObject = JSONObject()

        fun domain(): String = domainOf(id)

        fun on(): Boolean = "on" == state

        fun available(): Boolean = "unavailable" != state && "unknown" != state

        /** Brightness as 0..100 %, or -1 when off or not reported. */
        fun brightnessPercent(): Int {
            val b = attributes.optInt("brightness", -1)
            return if (b < 0) -1 else Math.round(b * 100f / 255f)
        }

        /** True when the light accepts a colour (any of the RGB-capable colour modes). */
        fun supportsColor(): Boolean {
            val modes = attributes.optJSONArray("supported_color_modes") ?: return false
            for (i in 0 until modes.length()) if (COLOR_MODES.contains(modes.optString(i))) return true
            return false
        }

        /** True when the light can be dimmed. */
        fun supportsBrightness(): Boolean {
            val modes = attributes.optJSONArray("supported_color_modes") ?: return attributes.has("brightness")
            for (i in 0 until modes.length()) if ("onoff" != modes.optString(i)) return true
            return false
        }

        /** Upper-case title for console buttons. */
        fun title(): String = (if (name.isEmpty()) id else name).uppercase(Locale.getDefault())

        fun toJson(): JSONObject {
            val o = JSONObject()
            try {
                o.put("id", id).put("name", name)
            } catch (ignored: JSONException) {
            }
            return o
        }
    }

    /** A service call: `POST /api/services/<domain>/<service>` with a JSON body. */
    class Call(
        @JvmField val domain: String,
        @JvmField val service: String,
        @JvmField val body: JSONObject,
    ) {
        /** The request path, e.g. `/api/services/light/turn_on`. */
        fun path(): String = servicePath(domain, service)
    }

    /** Main-thread result of [async]: the value, or an error message (exactly one is non-null). */
    fun interface Result<T> {
        fun done(value: T?, error: String?)
    }

    /** Blocking work run by [async]. */
    fun interface Job<T> {
        @Throws(IOException::class)
        fun run(): T
    }

    // ---- configuration ------------------------------------------------------------------------------

    private fun sp(c: Context): SharedPreferences = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @JvmStatic
    fun baseUrl(c: Context): String = sp(c).getString(KEY_URL, "").orEmpty()

    @JvmStatic
    fun token(c: Context): String = sp(c).getString(KEY_TOKEN, "").orEmpty()

    @JvmStatic
    fun configured(c: Context): Boolean = baseUrl(c).isNotEmpty() && token(c).isNotEmpty()

    /** True when Home Assistant reported °F as its unit system at the last import. */
    @JvmStatic
    fun fahrenheit(c: Context): Boolean = sp(c).getBoolean(KEY_FAHRENHEIT, false)

    @JvmStatic
    fun setBaseUrl(c: Context, url: String?) = sp(c).edit().putString(KEY_URL, url ?: "").apply()

    @JvmStatic
    fun setToken(c: Context, token: String?) = sp(c).edit().putString(KEY_TOKEN, token?.trim() ?: "").apply()

    /** Forgets address, token and entities. */
    @JvmStatic
    fun forget(c: Context) = sp(c).edit().clear().apply()

    /**
     * Cleans up a typed address: trims, adds https:// when no scheme was given, drops a trailing
     * slash and a trailing `/api`. Returns "" for blank input.
     */
    @JvmStatic
    fun normalizeUrl(raw: String?): String {
        var s = raw?.trim() ?: ""
        if (s.isEmpty()) return ""
        if (!s.contains("://")) s = "https://$s"
        while (s.endsWith("/")) s = s.substring(0, s.length - 1)
        if (s.lowercase(Locale.ROOT).endsWith("/api")) s = s.substring(0, s.length - 4)
        while (s.endsWith("/")) s = s.substring(0, s.length - 1)
        return s
    }

    /**
     * Classifies a normalized base address: [URL_OK] (https), [URL_LOCAL_HTTP]
     * (http to a `.local` / `.home.arpa` name: allowed, with a warning),
     * [URL_INSECURE] (http to anything else, IP addresses included) or [URL_INVALID]
     * (not a usable address: other schemes, user info, query or fragment).
     */
    @JvmStatic
    fun checkUrl(url: String?): Int {
        if (url.isNullOrEmpty()) return URL_INVALID
        val u: URI =
            try {
                URI.create(url)
            } catch (e: IllegalArgumentException) {
                return URL_INVALID
            }
        if (u.host.isNullOrEmpty() || u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) return URL_INVALID
        val scheme = u.scheme?.lowercase(Locale.ROOT) ?: ""
        if ("https" == scheme) return URL_OK
        if ("http" != scheme) return URL_INVALID
        return if (Http.isLocalHostName(u.host)) URL_LOCAL_HTTP else URL_INSECURE
    }

    /** First API level whose system resolver answers `.local` names over mDNS (Android 13). */
    const val MDNS_RESOLVER_API = 33

    /**
     * True when `url` points at a `.local` (mDNS) host and the device's Android version
     * (`sdkInt`) predates system mDNS resolution, so the name may not resolve at all; CONFIG
     * then suggests the server's IP address over https or a `.home.arpa` name instead.
     */
    @JvmStatic
    fun localNameMayFail(url: String?, sdkInt: Int): Boolean {
        if (sdkInt >= MDNS_RESOLVER_API || url.isNullOrEmpty()) return false
        return try {
            val host = URI.create(url).host
            host != null && host.lowercase(Locale.ROOT).endsWith(".local")
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /** True for an entity_id of safe characters (it is placed in request bodies and stored). */
    @JvmStatic
    fun validEntityId(id: String?): Boolean = id != null && ENTITY_ID.matches(id)

    /** The domain part of an entity_id ("light.kitchen" → "light"), or "". */
    @JvmStatic
    fun domainOf(entityId: String?): String {
        val dot = entityId?.indexOf('.') ?: -1
        return if (dot <= 0) "" else entityId!!.substring(0, dot)
    }

    /** True for a domain console can control. */
    @JvmStatic
    fun supported(domain: String?): Boolean = DOMAINS.contains(domain)

    /** Splits a tile argument into its entity_id (dropping [PANEL_SUFFIX]). */
    @JvmStatic
    fun entityOfArg(arg: String?): String {
        if (arg == null) return ""
        return if (arg.endsWith(PANEL_SUFFIX)) arg.substring(0, arg.length - PANEL_SUFFIX.length) else arg
    }

    /** True when the tile argument asks for the control dialog rather than a toggle. */
    @JvmStatic
    fun panelArg(arg: String?): Boolean = arg != null && arg.endsWith(PANEL_SUFFIX)

    // ---- stored entities ----------------------------------------------------------------------------

    /** The entities imported at the last IMPORT ENTITIES. */
    @JvmStatic
    fun entities(c: Context): List<Entity> = parseStored(sp(c).getString(KEY_ENTITIES, "[]"))

    @JvmStatic
    fun find(c: Context, entityId: String?): Entity? = entities(c).firstOrNull { it.id == entityId }

    @JvmStatic
    fun saveEntities(c: Context, list: List<Entity>) = sp(c).edit().putString(KEY_ENTITIES, storeJson(list)).apply()

    /** Serialises entities for [KEY_ENTITIES] (id and name only, never live state). */
    @JvmStatic
    fun storeJson(list: List<Entity>): String {
        val a = JSONArray()
        for (e in list) a.put(e.toJson())
        return a.toString()
    }

    /** Reads [storeJson] output; bad ids and corrupt data are dropped. */
    @JvmStatic
    fun parseStored(json: String?): List<Entity> {
        val out = ArrayList<Entity>()
        try {
            val a = JSONArray(json ?: "[]")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i)
                if (o == null || !validEntityId(o.optString("id"))) continue
                val e = Entity()
                e.id = o.optString("id")
                e.name = o.optString("name")
                out.add(e)
            }
        } catch (ignored: JSONException) {
            // corrupt preference: no entities
        }
        return out
    }

    // ---- parsing ------------------------------------------------------------------------------------

    /** One state object from `/api/states` or `/api/states/<id>`; null when malformed. */
    @JvmStatic
    fun parseState(o: JSONObject?): Entity? {
        if (o == null || !validEntityId(o.optString("entity_id"))) return null
        val e = Entity()
        e.id = o.optString("entity_id")
        e.state = o.optString("state", "")
        val attrs = o.optJSONObject("attributes")
        if (attrs != null) e.attributes = attrs
        val friendly = e.attributes.optString("friendly_name", "").trim()
        e.name = if (friendly.isEmpty()) e.id.substring(e.id.indexOf('.') + 1).replace('_', ' ') else friendly
        return e
    }

    /**
     * The controllable entities in a `/api/states` reply (unsupported domains and malformed
     * entries skipped), sorted by [DOMAINS] order then name, at most [MAX_ENTITIES].
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parseStates(json: String): List<Entity> {
        val a: JSONArray =
            try {
                JSONArray(json)
            } catch (e: JSONException) {
                throw IOException("UNEXPECTED REPLY FROM HOME ASSISTANT")
            }
        val out = ArrayList<Entity>()
        for (i in 0 until a.length()) {
            val e = parseState(a.optJSONObject(i))
            if (e != null && supported(e.domain())) out.add(e)
        }
        val order = DOMAINS.asList()
        out.sortWith { x, y ->
            val d = order.indexOf(x.domain()).compareTo(order.indexOf(y.domain()))
            if (d != 0) d else x.name.compareTo(y.name, ignoreCase = true)
        }
        return if (out.size > MAX_ENTITIES) ArrayList(out.subList(0, MAX_ENTITIES)) else out
    }

    /** True when a `/api/config` reply says the unit system is Fahrenheit. */
    @JvmStatic
    fun configFahrenheit(json: String): Boolean =
        try {
            val units = JSONObject(json).optJSONObject("unit_system")
            units != null && units.optString("temperature", "").contains("F")
        } catch (e: JSONException) {
            false
        }

    /**
     * Maps a climate entity onto the [Nest.State] that [ThermostatDialog] draws.
     * Temperatures are converted to °C (Home Assistant reports them in its own unit system).
     * Modes: heat → HEAT, cool → COOL, heat_cool (or auto with a low/high range) → HEATCOOL,
     * off → OFF, anything else upper-cased (shown, but not adjustable). The "eco" preset is ECO.
     */
    @JvmStatic
    fun climateState(e: Entity, fahrenheit: Boolean): Nest.State {
        val s = Nest.State()
        val a = e.attributes
        s.label = e.title()
        s.scale = if (fahrenheit) "FAHRENHEIT" else "CELSIUS"
        s.connectivity = if (e.available()) "ONLINE" else "OFFLINE"
        s.ambientC = toC(a.optDouble("current_temperature", Double.NaN), fahrenheit)
        val h = a.optDouble("current_humidity", Double.NaN)
        s.humidity = if (h.isNaN()) -1 else Math.round(h).toInt()
        val range = a.has("target_temp_low") && a.has("target_temp_high") &&
            !a.isNull("target_temp_low") && !a.isNull("target_temp_high")
        s.mode = consoleMode(e.state, range)
        val modes = a.optJSONArray("hvac_modes")
        if (modes != null) {
            for (i in 0 until modes.length()) {
                val m = consoleMode(modes.optString(i), range)
                if (!s.modes.contains(m)) s.modes.add(m)
            }
        }
        val low = toC(a.optDouble("target_temp_low", Double.NaN), fahrenheit)
        val high = toC(a.optDouble("target_temp_high", Double.NaN), fahrenheit)
        val target = toC(a.optDouble("temperature", Double.NaN), fahrenheit)
        when (s.mode) {
            "HEATCOOL" -> {
                s.heatC = low
                s.coolC = high
            }
            "COOL" -> s.coolC = target
            else -> s.heatC = target
        }
        val action = a.optString("hvac_action", "")
        s.hvac = if ("heating" == action) "HEATING" else if ("cooling" == action) "COOLING" else "OFF"
        val presets = a.optJSONArray("preset_modes")
        if (presets != null) for (i in 0 until presets.length()) if ("eco" == presets.optString(i)) s.ecoAvailable = true
        if ("eco" == a.optString("preset_mode", "")) {
            s.eco = "MANUAL_ECO"
            s.ecoHeatC = if (low.isNaN()) target else low
            s.ecoCoolC = if (high.isNaN()) target else high
        }
        return s
    }

    /** Home Assistant hvac mode → the console mode name used by [ThermostatDialog]. */
    @JvmStatic
    fun consoleMode(haMode: String?, range: Boolean): String =
        when (haMode) {
            null -> "OFF"
            "heat" -> "HEAT"
            "cool" -> "COOL"
            "heat_cool" -> "HEATCOOL"
            "off" -> "OFF"
            "auto" -> if (range) "HEATCOOL" else "AUTO"
            else -> haMode.uppercase(Locale.US)
        }

    /** console mode → the Home Assistant hvac mode to send, preferring heat_cool over auto for HEATCOOL. */
    @JvmStatic
    fun haMode(consoleMode: String, haModes: List<String>?): String =
        when (consoleMode) {
            "HEAT" -> "heat"
            "COOL" -> "cool"
            "OFF" -> "off"
            "HEATCOOL" -> if (haModes != null && !haModes.contains("heat_cool") && haModes.contains("auto")) "auto" else "heat_cool"
            else -> consoleMode.lowercase(Locale.US)
        }

    private fun toC(v: Double, fahrenheit: Boolean): Double = if (v.isNaN() || !fahrenheit) v else Weather.fToC(v)

    /** °C → Home Assistant's unit, rounded to whole °F or half °C. */
    @JvmStatic
    fun fromC(celsius: Double, fahrenheit: Boolean): Double =
        if (fahrenheit) Math.round(Weather.cToF(celsius)).toDouble() else Math.round(celsius * 2) / 2.0

    // ---- service calls (pure) -----------------------------------------------------------------------

    /** `/api/services/<domain>/<service>`; both names must be lower-case identifiers. */
    @JvmStatic
    fun servicePath(domain: String?, service: String?): String {
        if (domain == null || service == null || !NAME.matches(domain) || !NAME.matches(service)) {
            throw IllegalArgumentException("bad service $domain/$service")
        }
        return "/api/services/$domain/$service"
    }

    private fun target(entityId: String?): JSONObject {
        if (!validEntityId(entityId)) throw IllegalArgumentException("bad entity $entityId")
        val o = JSONObject()
        try {
            o.put("entity_id", entityId)
        } catch (e: JSONException) {
            throw IllegalStateException(e)
        }
        return o
    }

    /**
     * The one-tap action for an entity: toggle a light / switch / input_boolean / fan, or activate
     * a scene / script. Null for climate (which opens the dialog) and unsupported domains.
     */
    @JvmStatic
    fun primary(entityId: String?): Call? {
        val d = domainOf(entityId)
        return when (d) {
            "light", "switch", "input_boolean", "fan" -> Call(d, "toggle", target(entityId))
            "scene", "script" -> Call(d, "turn_on", target(entityId))
            else -> null
        }
    }

    /** turn_on / turn_off for the entity's own domain. */
    @JvmStatic
    fun power(entityId: String?, on: Boolean): Call = Call(domainOf(entityId), if (on) "turn_on" else "turn_off", target(entityId))

    /**
     * `light.turn_on` with a brightness in percent (clamped 0..100; -1 = leave as is) and an
     * optional colour (0xRRGGBB, or -1 = leave as is).
     */
    @JvmStatic
    fun light(entityId: String?, brightnessPercent: Int, rgb: Int): Call {
        val b = target(entityId)
        try {
            if (brightnessPercent >= 0) b.put("brightness_pct", minOf(100, brightnessPercent))
            if (rgb >= 0) b.put("rgb_color", JSONArray().put((rgb shr 16) and 0xFF).put((rgb shr 8) and 0xFF).put(rgb and 0xFF))
        } catch (e: JSONException) {
            throw IllegalStateException(e)
        }
        return Call("light", "turn_on", b)
    }

    /** `climate.set_hvac_mode`. */
    @JvmStatic
    fun hvacMode(entityId: String?, haMode: String): Call {
        val b = target(entityId)
        try {
            b.put("hvac_mode", haMode)
        } catch (e: JSONException) {
            throw IllegalStateException(e)
        }
        return Call("climate", "set_hvac_mode", b)
    }

    /** `climate.set_preset_mode`. */
    @JvmStatic
    fun preset(entityId: String?, preset: String): Call {
        val b = target(entityId)
        try {
            b.put("preset_mode", preset)
        } catch (e: JSONException) {
            throw IllegalStateException(e)
        }
        return Call("climate", "set_preset_mode", b)
    }

    /**
     * `climate.set_temperature` for an console mode: HEATCOOL sends target_temp_low/high, HEAT
     * and COOL a single temperature. Values are given in °C and sent in Home Assistant's unit.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun temperature(entityId: String?, mode: String?, heatC: Double, coolC: Double, fahrenheit: Boolean): Call {
        val b = target(entityId)
        try {
            when (mode) {
                "HEATCOOL" -> b.put("target_temp_low", fromC(heatC, fahrenheit)).put("target_temp_high", fromC(coolC, fahrenheit))
                "HEAT" -> b.put("temperature", fromC(heatC, fahrenheit))
                "COOL" -> b.put("temperature", fromC(coolC, fahrenheit))
                else -> throw IOException("SETPOINTS CAN'T CHANGE WHILE THE SYSTEM IS OFF")
            }
        } catch (e: JSONException) {
            throw IOException("COULD NOT BUILD COMMAND")
        }
        return Call("climate", "set_temperature", b)
    }

    /** The preset to leave ECO with: "none" when offered, else the first non-eco preset, else "none". */
    @JvmStatic
    fun nonEcoPreset(presets: JSONArray?): String {
        if (presets == null) return "none"
        var first: String? = null
        for (i in 0 until presets.length()) {
            val p = presets.optString(i)
            if ("none" == p) return p
            if (first == null && "eco" != p && p.isNotEmpty()) first = p
        }
        return first ?: "none"
    }

    /** Headers for an authorised JSON request. */
    @JvmStatic
    fun headers(token: String): Map<String, String> {
        val h = HashMap<String, String>()
        h["Authorization"] = "Bearer $token"
        h["Content-Type"] = "application/json"
        h["Accept"] = "application/json"
        return h
    }

    /** Turns an HTTP error into a short console message (Home Assistant errors are {"message": …}). */
    @JvmStatic
    fun errorText(code: Int, body: String?): String {
        var msg = ""
        try {
            msg = JSONObject(body ?: "").optString("message", "")
        } catch (ignored: JSONException) {
            // not JSON
        }
        if (code == 401 || code == 403) return "ACCESS DENIED • CHECK THE LONG-LIVED ACCESS TOKEN"
        if (code == 404) return "NOT FOUND • CHECK THE ADDRESS AND THAT THE ENTITY STILL EXISTS"
        if (code in 300..399) return "HOME ASSISTANT REDIRECTED • ENTER ITS FINAL HTTPS ADDRESS"
        if (code == 502 || code == 503 || code == 504) return "HOME ASSISTANT UNREACHABLE ($code)"
        return (if (msg.isEmpty()) "HOME ASSISTANT ERROR $code" else msg).uppercase(Locale.US)
    }

    // ---- network (background thread only) -----------------------------------------------------------

    /**
     * Runs a job on the Home Assistant background thread and delivers the result on the main thread.
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

    /** One authorised request to the configured instance; returns the body of a 2xx reply. */
    @Throws(IOException::class)
    private fun request(c: Context, method: String, path: String, body: ByteArray?, maxBytes: Int): String {
        val base = baseUrl(c)
        val tok = token(c)
        if (base.isEmpty() || tok.isEmpty()) throw IOException("HOME ASSISTANT NOT SET UP • CONFIG › DEVICES")
        val kind = checkUrl(base)
        if (kind != URL_OK && kind != URL_LOCAL_HTTP) throw IOException("INSECURE ADDRESS REFUSED • USE HTTPS")
        val r = Http.requestAllowingLocalHttp(
            method,
            base + path,
            headers(tok),
            body,
            Http.CONNECT_TIMEOUT_MS,
            Http.READ_TIMEOUT_MS,
            maxBytes,
        )
        if (!r.ok()) throw IOException(errorText(r.code, r.body))
        return r.body
    }

    /**
     * Checks the address and token, reads the unit system and imports the controllable entities
     * (stored, then returned).
     */
    @JvmStatic
    @Throws(IOException::class)
    fun importEntities(c: Context): List<Entity> {
        val app = c.applicationContext
        val f = configFahrenheit(request(app, "GET", "/api/config", null, Http.MAX_BODY_BYTES))
        val found = parseStates(request(app, "GET", "/api/states", null, MAX_STATES_BYTES))
        sp(app).edit().putBoolean(KEY_FAHRENHEIT, f).apply()
        saveEntities(app, found)
        return found
    }

    /** Reads one entity's live state. */
    @JvmStatic
    @Throws(IOException::class)
    fun state(c: Context, entityId: String?): Entity {
        if (!validEntityId(entityId)) throw IOException("UNKNOWN ENTITY • IMPORT AGAIN IN CONFIG › DEVICES")
        try {
            return parseState(JSONObject(request(c.applicationContext, "GET", "/api/states/$entityId", null, Http.MAX_BODY_BYTES)))
                ?: throw IOException("UNEXPECTED REPLY FROM HOME ASSISTANT")
        } catch (e: JSONException) {
            throw IOException("UNEXPECTED REPLY FROM HOME ASSISTANT")
        }
    }

    /**
     * Performs a service call and returns the new state of `call`'s entity as reported in
     * the reply ("on", "off"…), or "" when the reply doesn't say.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun call(c: Context, call: Call): String {
        val reply = request(c.applicationContext, "POST", call.path(), call.body.toString().toByteArray(Charsets.UTF_8), Http.MAX_BODY_BYTES)
        return stateAfter(reply, call.body.optString("entity_id"))
    }

    /**
     * The state of `entityId` in a service-call reply (Home Assistant answers with the list
     * of states that changed), or "" when it isn't listed or the reply isn't a list.
     */
    @JvmStatic
    fun stateAfter(reply: String?, entityId: String?): String {
        try {
            val a = JSONArray(reply ?: "")
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i)
                if (o != null && entityId != null && entityId == o.optString("entity_id")) return o.optString("state", "")
            }
        } catch (ignored: JSONException) {
            // empty or non-list reply
        }
        return ""
    }

    // ---- climate through ThermostatDialog ----------------------------------------------------------

    /** [ThermostatDialog.Backend] for a Home Assistant climate entity. */
    class Climate(private val entityId: String?) : ThermostatDialog.Backend {
        /** Last live entity, for its hvac_modes and preset list. */
        @Volatile
        private var last: Entity? = null

        override fun read(app: Context): Nest.State {
            val e = state(app, entityId)
            last = e
            return climateState(e, fahrenheit(app))
        }

        private fun haModes(): List<String> {
            val out = ArrayList<String>()
            val a = last?.attributes?.optJSONArray("hvac_modes")
            if (a != null) for (i in 0 until a.length()) out.add(a.optString(i))
            return out
        }

        override fun setMode(app: Context, mode: String) {
            call(app, hvacMode(entityId, haMode(mode, haModes())))
        }

        override fun setEco(app: Context, on: Boolean) {
            val e = last
            call(app, preset(entityId, if (on) "eco" else nonEcoPreset(e?.attributes?.optJSONArray("preset_modes"))))
        }

        override fun setTemps(app: Context, mode: String, heatC: Double, coolC: Double) {
            call(app, temperature(entityId, mode, heatC, coolC, fahrenheit(app)))
        }
    }
}
