package com.gungreeneyes.trekui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * Home Assistant as a smart-home source next to {@link Govee} and {@link Nest}: lights (on/off,
 * brightness, colour), switches, fans and input booleans, climate (through
 * {@link ThermostatDialog}), scenes and scripts, over Home Assistant's REST API
 * ({@code GET /api/states}, {@code POST /api/services/<domain>/<service>}).
 *
 * <p>Setup: the owner enters the base address and a long-lived access token (Home Assistant ›
 * profile › Security › Long-lived access tokens) in CONFIG › DEVICES, then IMPORT ENTITIES.
 * Addresses are HTTPS; plain http:// is accepted only for a local-network name ending in
 * {@code .local} or {@code .home.arpa} (see {@link Http#LOCAL_CLEARTEXT_SUFFIXES} and
 * {@code res/xml/network_security_config.xml}), with a warning in CONFIG.
 *
 * <p>Privacy: the address, token and imported entity list live in their own private preferences
 * file {@link #FILE}, which is excluded from Android backup and device transfer, and every key
 * starts with {@code ha_} (the token key is {@link #KEY_TOKEN}) so settings export can skip it.
 * Nothing is sent anywhere but the owner's own Home Assistant.
 *
 * <p>Requests block and run through {@link #async} on a background thread with {@link Http}'s
 * timeouts; only the application context is used. URL checks, JSON parsing, the mapping of a
 * climate entity onto {@link Nest.State} and service-call building are pure and unit-tested.
 */
final class HomeAssistant {
    /** Private preferences file holding the address, token and entities (excluded from backup). */
    static final String FILE = "trekui_ha";
    static final String KEY_URL = "ha_url", KEY_TOKEN = "ha_token", KEY_ENTITIES = "ha_entities",
            KEY_FAHRENHEIT = "ha_unit_f";

    /** Domains console can control, in the order they are listed. */
    static final String[] DOMAINS = {"light", "switch", "input_boolean", "fan", "climate", "scene", "script"};
    /** Most entities kept after an import (a large install can have thousands). */
    static final int MAX_ENTITIES = 300;
    /** Largest {@code /api/states} reply accepted; big installs can reach a few MB. */
    static final int MAX_STATES_BYTES = 8 << 20;
    /** Tile argument suffix: open the control dialog instead of toggling. */
    static final String PANEL_SUFFIX = "#panel";

    /** Result of {@link #checkUrl}. */
    static final int URL_OK = 0, URL_LOCAL_HTTP = 1, URL_INVALID = 2, URL_INSECURE = 3;

    /** entity_id: domain "." object id, lower-case letters, digits and underscores. */
    private static final Pattern ENTITY_ID = Pattern.compile("[a-z0-9_]+\\.[a-z0-9_]+");
    /** Service and domain names placed in the request path. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    /** Colour modes that accept an rgb_color. */
    private static final List<String> COLOR_MODES = Arrays.asList("hs", "rgb", "rgbw", "rgbww", "xy");

    private static final ExecutorService IO = Http.newExecutor("trekui-ha");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** An imported entity (id and display name), plus its live state and attributes once fetched. */
    static final class Entity {
        String id = "", name = "";
        /** Live state ("on", "off", "heat", "unavailable"…); "" when only the stored record is known. */
        String state = "";
        /** Live attributes; empty when only the stored record is known. */
        JSONObject attributes = new JSONObject();

        String domain() { return domainOf(id); }

        boolean on() { return "on".equals(state); }

        boolean available() { return !"unavailable".equals(state) && !"unknown".equals(state); }

        /** Brightness as 0..100 %, or -1 when off or not reported. */
        int brightnessPercent() {
            int b = attributes.optInt("brightness", -1);
            return b < 0 ? -1 : Math.round(b * 100f / 255f);
        }

        /** True when the light accepts a colour (any of the RGB-capable colour modes). */
        boolean supportsColor() {
            JSONArray modes = attributes.optJSONArray("supported_color_modes");
            if (modes == null) return false;
            for (int i = 0; i < modes.length(); i++) if (COLOR_MODES.contains(modes.optString(i))) return true;
            return false;
        }

        /** True when the light can be dimmed. */
        boolean supportsBrightness() {
            JSONArray modes = attributes.optJSONArray("supported_color_modes");
            if (modes == null) return attributes.has("brightness");
            for (int i = 0; i < modes.length(); i++) if (!"onoff".equals(modes.optString(i))) return true;
            return false;
        }

        /** Upper-case title for console buttons. */
        String title() { return (name.isEmpty() ? id : name).toUpperCase(Locale.getDefault()); }

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try { o.put("id", id).put("name", name); } catch (JSONException ignored) { }
            return o;
        }
    }

    /** A service call: {@code POST /api/services/<domain>/<service>} with a JSON body. */
    static final class Call {
        final String domain, service;
        final JSONObject body;

        Call(String domain, String service, JSONObject body) {
            this.domain = domain;
            this.service = service;
            this.body = body;
        }

        /** The request path, e.g. {@code /api/services/light/turn_on}. */
        String path() { return servicePath(domain, service); }
    }

    /** Main-thread result of {@link #async}: the value, or an error message (exactly one is non-null). */
    interface Result<T> { void done(T value, String error); }

    /** Blocking work run by {@link #async}. */
    interface Job<T> { T run() throws IOException; }

    private HomeAssistant() {}

    // ---- configuration ------------------------------------------------------------------------------

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static String baseUrl(Context c) { return sp(c).getString(KEY_URL, ""); }
    static String token(Context c) { return sp(c).getString(KEY_TOKEN, ""); }
    static boolean configured(Context c) { return !baseUrl(c).isEmpty() && !token(c).isEmpty(); }
    /** True when Home Assistant reported °F as its unit system at the last import. */
    static boolean fahrenheit(Context c) { return sp(c).getBoolean(KEY_FAHRENHEIT, false); }

    static void setBaseUrl(Context c, String url) { sp(c).edit().putString(KEY_URL, url == null ? "" : url).apply(); }
    static void setToken(Context c, String token) { sp(c).edit().putString(KEY_TOKEN, token == null ? "" : token.trim()).apply(); }

    /** Forgets address, token and entities. */
    static void forget(Context c) { sp(c).edit().clear().apply(); }

    /**
     * Cleans up a typed address: trims, adds https:// when no scheme was given, drops a trailing
     * slash and a trailing {@code /api}. Returns "" for blank input.
     */
    static String normalizeUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (!s.contains("://")) s = "https://" + s;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.toLowerCase(Locale.ROOT).endsWith("/api")) s = s.substring(0, s.length() - 4);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Classifies a normalized base address: {@link #URL_OK} (https), {@link #URL_LOCAL_HTTP}
     * (http to a {@code .local} / {@code .home.arpa} name: allowed, with a warning),
     * {@link #URL_INSECURE} (http to anything else, IP addresses included) or {@link #URL_INVALID}
     * (not a usable address: other schemes, user info, query or fragment).
     */
    static int checkUrl(String url) {
        if (url == null || url.isEmpty()) return URL_INVALID;
        URI u;
        try {
            u = URI.create(url);
        } catch (IllegalArgumentException e) {
            return URL_INVALID;
        }
        if (u.getHost() == null || u.getHost().isEmpty() || u.getRawUserInfo() != null
                || u.getRawQuery() != null || u.getRawFragment() != null) return URL_INVALID;
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) return URL_OK;
        if (!"http".equals(scheme)) return URL_INVALID;
        return Http.isLocalHostName(u.getHost()) ? URL_LOCAL_HTTP : URL_INSECURE;
    }

    /** First API level whose system resolver answers {@code .local} names over mDNS (Android 13). */
    static final int MDNS_RESOLVER_API = 33;

    /**
     * True when {@code url} points at a {@code .local} (mDNS) host and the device's Android version
     * ({@code sdkInt}) predates system mDNS resolution, so the name may not resolve at all; CONFIG
     * then suggests the server's IP address over https or a {@code .home.arpa} name instead.
     */
    static boolean localNameMayFail(String url, int sdkInt) {
        if (sdkInt >= MDNS_RESOLVER_API || url == null || url.isEmpty()) return false;
        try {
            String host = URI.create(url).getHost();
            return host != null && host.toLowerCase(Locale.ROOT).endsWith(".local");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** True for an entity_id of safe characters (it is placed in request bodies and stored). */
    static boolean validEntityId(String id) { return id != null && ENTITY_ID.matcher(id).matches(); }

    /** The domain part of an entity_id ("light.kitchen" → "light"), or "". */
    static String domainOf(String entityId) {
        int dot = entityId == null ? -1 : entityId.indexOf('.');
        return dot <= 0 ? "" : entityId.substring(0, dot);
    }

    /** True for a domain console can control. */
    static boolean supported(String domain) { return Arrays.asList(DOMAINS).contains(domain); }

    /** Splits a tile argument into its entity_id (dropping {@link #PANEL_SUFFIX}). */
    static String entityOfArg(String arg) {
        if (arg == null) return "";
        return arg.endsWith(PANEL_SUFFIX) ? arg.substring(0, arg.length() - PANEL_SUFFIX.length()) : arg;
    }

    /** True when the tile argument asks for the control dialog rather than a toggle. */
    static boolean panelArg(String arg) { return arg != null && arg.endsWith(PANEL_SUFFIX); }

    // ---- stored entities ----------------------------------------------------------------------------

    /** The entities imported at the last IMPORT ENTITIES. */
    static List<Entity> entities(Context c) { return parseStored(sp(c).getString(KEY_ENTITIES, "[]")); }

    static Entity find(Context c, String entityId) {
        for (Entity e : entities(c)) if (e.id.equals(entityId)) return e;
        return null;
    }

    static void saveEntities(Context c, List<Entity> list) {
        sp(c).edit().putString(KEY_ENTITIES, storeJson(list)).apply();
    }

    /** Serialises entities for {@link #KEY_ENTITIES} (id and name only, never live state). */
    static String storeJson(List<Entity> list) {
        JSONArray a = new JSONArray();
        for (Entity e : list) a.put(e.toJson());
        return a.toString();
    }

    /** Reads {@link #storeJson} output; bad ids and corrupt data are dropped. */
    static List<Entity> parseStored(String json) {
        List<Entity> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json == null ? "[]" : json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || !validEntityId(o.optString("id"))) continue;
                Entity e = new Entity();
                e.id = o.optString("id");
                e.name = o.optString("name");
                out.add(e);
            }
        } catch (JSONException ignored) {
            // corrupt preference: no entities
        }
        return out;
    }

    // ---- parsing ------------------------------------------------------------------------------------

    /** One state object from {@code /api/states} or {@code /api/states/<id>}; null when malformed. */
    static Entity parseState(JSONObject o) {
        if (o == null || !validEntityId(o.optString("entity_id"))) return null;
        Entity e = new Entity();
        e.id = o.optString("entity_id");
        e.state = o.optString("state", "");
        JSONObject attrs = o.optJSONObject("attributes");
        if (attrs != null) e.attributes = attrs;
        String friendly = e.attributes.optString("friendly_name", "").trim();
        e.name = friendly.isEmpty() ? e.id.substring(e.id.indexOf('.') + 1).replace('_', ' ') : friendly;
        return e;
    }

    /**
     * The controllable entities in a {@code /api/states} reply (unsupported domains and malformed
     * entries skipped), sorted by {@link #DOMAINS} order then name, at most {@link #MAX_ENTITIES}.
     */
    static List<Entity> parseStates(String json) throws IOException {
        JSONArray a;
        try {
            a = new JSONArray(json);
        } catch (JSONException e) {
            throw new IOException("UNEXPECTED REPLY FROM HOME ASSISTANT");
        }
        List<Entity> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Entity e = parseState(a.optJSONObject(i));
            if (e != null && supported(e.domain())) out.add(e);
        }
        final List<String> order = Arrays.asList(DOMAINS);
        Collections.sort(out, new Comparator<Entity>() {
            @Override public int compare(Entity x, Entity y) {
                int d = Integer.compare(order.indexOf(x.domain()), order.indexOf(y.domain()));
                return d != 0 ? d : x.name.compareToIgnoreCase(y.name);
            }
        });
        return out.size() > MAX_ENTITIES ? new ArrayList<>(out.subList(0, MAX_ENTITIES)) : out;
    }

    /** True when a {@code /api/config} reply says the unit system is Fahrenheit. */
    static boolean configFahrenheit(String json) {
        try {
            JSONObject units = new JSONObject(json).optJSONObject("unit_system");
            return units != null && units.optString("temperature", "").contains("F");
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * Maps a climate entity onto the {@link Nest.State} that {@link ThermostatDialog} draws.
     * Temperatures are converted to °C (Home Assistant reports them in its own unit system).
     * Modes: heat → HEAT, cool → COOL, heat_cool (or auto with a low/high range) → HEATCOOL,
     * off → OFF, anything else upper-cased (shown, but not adjustable). The "eco" preset is ECO.
     */
    static Nest.State climateState(Entity e, boolean fahrenheit) {
        Nest.State s = new Nest.State();
        JSONObject a = e.attributes;
        s.label = e.title();
        s.scale = fahrenheit ? "FAHRENHEIT" : "CELSIUS";
        s.connectivity = e.available() ? "ONLINE" : "OFFLINE";
        s.ambientC = toC(a.optDouble("current_temperature", Double.NaN), fahrenheit);
        double h = a.optDouble("current_humidity", Double.NaN);
        s.humidity = Double.isNaN(h) ? -1 : (int) Math.round(h);
        boolean range = a.has("target_temp_low") && a.has("target_temp_high")
                && !a.isNull("target_temp_low") && !a.isNull("target_temp_high");
        s.mode = consoleMode(e.state, range);
        JSONArray modes = a.optJSONArray("hvac_modes");
        if (modes != null) {
            for (int i = 0; i < modes.length(); i++) {
                String m = consoleMode(modes.optString(i), range);
                if (!s.modes.contains(m)) s.modes.add(m);
            }
        }
        double low = toC(a.optDouble("target_temp_low", Double.NaN), fahrenheit);
        double high = toC(a.optDouble("target_temp_high", Double.NaN), fahrenheit);
        double target = toC(a.optDouble("temperature", Double.NaN), fahrenheit);
        if ("HEATCOOL".equals(s.mode)) {
            s.heatC = low;
            s.coolC = high;
        } else if ("COOL".equals(s.mode)) {
            s.coolC = target;
        } else {
            s.heatC = target;
        }
        String action = a.optString("hvac_action", "");
        s.hvac = "heating".equals(action) ? "HEATING" : "cooling".equals(action) ? "COOLING" : "OFF";
        JSONArray presets = a.optJSONArray("preset_modes");
        if (presets != null) for (int i = 0; i < presets.length(); i++) if ("eco".equals(presets.optString(i))) s.ecoAvailable = true;
        if ("eco".equals(a.optString("preset_mode", ""))) {
            s.eco = "MANUAL_ECO";
            s.ecoHeatC = Double.isNaN(low) ? target : low;
            s.ecoCoolC = Double.isNaN(high) ? target : high;
        }
        return s;
    }

    /** Home Assistant hvac mode → the console mode name used by {@link ThermostatDialog}. */
    static String consoleMode(String haMode, boolean range) {
        if (haMode == null) return "OFF";
        switch (haMode) {
            case "heat": return "HEAT";
            case "cool": return "COOL";
            case "heat_cool": return "HEATCOOL";
            case "off": return "OFF";
            case "auto": return range ? "HEATCOOL" : "AUTO";
            default: return haMode.toUpperCase(Locale.US);
        }
    }

    /** console mode → the Home Assistant hvac mode to send, preferring heat_cool over auto for HEATCOOL. */
    static String haMode(String consoleMode, List<String> haModes) {
        switch (consoleMode) {
            case "HEAT": return "heat";
            case "COOL": return "cool";
            case "OFF": return "off";
            case "HEATCOOL": return haModes != null && !haModes.contains("heat_cool") && haModes.contains("auto") ? "auto" : "heat_cool";
            default: return consoleMode.toLowerCase(Locale.US);
        }
    }

    private static double toC(double v, boolean fahrenheit) {
        return Double.isNaN(v) || !fahrenheit ? v : Weather.fToC(v);
    }

    /** °C → Home Assistant's unit, rounded to whole °F or half °C. */
    static double fromC(double celsius, boolean fahrenheit) {
        return fahrenheit ? Math.round(Weather.cToF(celsius)) : Math.round(celsius * 2) / 2.0;
    }

    // ---- service calls (pure) -----------------------------------------------------------------------

    /** {@code /api/services/<domain>/<service>}; both names must be lower-case identifiers. */
    static String servicePath(String domain, String service) {
        if (domain == null || service == null || !NAME.matcher(domain).matches() || !NAME.matcher(service).matches()) {
            throw new IllegalArgumentException("bad service " + domain + "/" + service);
        }
        return "/api/services/" + domain + "/" + service;
    }

    private static JSONObject target(String entityId) {
        if (!validEntityId(entityId)) throw new IllegalArgumentException("bad entity " + entityId);
        JSONObject o = new JSONObject();
        try { o.put("entity_id", entityId); } catch (JSONException e) { throw new IllegalStateException(e); }
        return o;
    }

    /**
     * The one-tap action for an entity: toggle a light / switch / input_boolean / fan, or activate
     * a scene / script. Null for climate (which opens the dialog) and unsupported domains.
     */
    static Call primary(String entityId) {
        String d = domainOf(entityId);
        switch (d) {
            case "light": case "switch": case "input_boolean": case "fan": return new Call(d, "toggle", target(entityId));
            case "scene": case "script": return new Call(d, "turn_on", target(entityId));
            default: return null;
        }
    }

    /** turn_on / turn_off for the entity's own domain. */
    static Call power(String entityId, boolean on) {
        return new Call(domainOf(entityId), on ? "turn_on" : "turn_off", target(entityId));
    }

    /**
     * {@code light.turn_on} with a brightness in percent (clamped 0..100; -1 = leave as is) and an
     * optional colour (0xRRGGBB, or -1 = leave as is).
     */
    static Call light(String entityId, int brightnessPercent, int rgb) {
        JSONObject b = target(entityId);
        try {
            if (brightnessPercent >= 0) b.put("brightness_pct", Math.min(100, brightnessPercent));
            if (rgb >= 0) b.put("rgb_color", new JSONArray().put((rgb >> 16) & 0xFF).put((rgb >> 8) & 0xFF).put(rgb & 0xFF));
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
        return new Call("light", "turn_on", b);
    }

    /** {@code climate.set_hvac_mode}. */
    static Call hvacMode(String entityId, String haMode) {
        JSONObject b = target(entityId);
        try { b.put("hvac_mode", haMode); } catch (JSONException e) { throw new IllegalStateException(e); }
        return new Call("climate", "set_hvac_mode", b);
    }

    /** {@code climate.set_preset_mode}. */
    static Call preset(String entityId, String preset) {
        JSONObject b = target(entityId);
        try { b.put("preset_mode", preset); } catch (JSONException e) { throw new IllegalStateException(e); }
        return new Call("climate", "set_preset_mode", b);
    }

    /**
     * {@code climate.set_temperature} for an console mode: HEATCOOL sends target_temp_low/high, HEAT
     * and COOL a single temperature. Values are given in °C and sent in Home Assistant's unit.
     */
    static Call temperature(String entityId, String mode, double heatC, double coolC, boolean fahrenheit) throws IOException {
        JSONObject b = target(entityId);
        try {
            if ("HEATCOOL".equals(mode)) {
                b.put("target_temp_low", fromC(heatC, fahrenheit)).put("target_temp_high", fromC(coolC, fahrenheit));
            } else if ("HEAT".equals(mode)) {
                b.put("temperature", fromC(heatC, fahrenheit));
            } else if ("COOL".equals(mode)) {
                b.put("temperature", fromC(coolC, fahrenheit));
            } else {
                throw new IOException("SETPOINTS CAN'T CHANGE WHILE THE SYSTEM IS OFF");
            }
        } catch (JSONException e) {
            throw new IOException("COULD NOT BUILD COMMAND");
        }
        return new Call("climate", "set_temperature", b);
    }

    /** The preset to leave ECO with: "none" when offered, else the first non-eco preset, else "none". */
    static String nonEcoPreset(JSONArray presets) {
        if (presets == null) return "none";
        String first = null;
        for (int i = 0; i < presets.length(); i++) {
            String p = presets.optString(i);
            if ("none".equals(p)) return p;
            if (first == null && !"eco".equals(p) && !p.isEmpty()) first = p;
        }
        return first != null ? first : "none";
    }

    /** Headers for an authorised JSON request. */
    static Map<String, String> headers(String token) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + token);
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json");
        return h;
    }

    /** Turns an HTTP error into a short console message (Home Assistant errors are {"message": …}). */
    static String errorText(int code, String body) {
        String msg = "";
        try {
            msg = new JSONObject(body == null ? "" : body).optString("message", "");
        } catch (JSONException ignored) {
            // not JSON
        }
        if (code == 401 || code == 403) return "ACCESS DENIED • CHECK THE LONG-LIVED ACCESS TOKEN";
        if (code == 404) return "NOT FOUND • CHECK THE ADDRESS AND THAT THE ENTITY STILL EXISTS";
        if (code >= 300 && code < 400) return "HOME ASSISTANT REDIRECTED • ENTER ITS FINAL HTTPS ADDRESS";
        if (code == 502 || code == 503 || code == 504) return "HOME ASSISTANT UNREACHABLE (" + code + ")";
        return (msg.isEmpty() ? "HOME ASSISTANT ERROR " + code : msg).toUpperCase(Locale.US);
    }

    // ---- network (background thread only) -----------------------------------------------------------

    /**
     * Runs a job on the Home Assistant background thread and delivers the result on the main thread.
     */
    static <T> void async(final Job<T> job, final Result<T> cb) {
        IO.execute(new Runnable() {
            @Override public void run() {
                T v = null;
                String err = null;
                try {
                    v = job.run();
                } catch (IOException | RuntimeException e) {
                    err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName().toUpperCase(Locale.US);
                }
                final T fv = v;
                final String fe = err;
                MAIN.post(new Runnable() { @Override public void run() { cb.done(fv, fe); } });
            }
        });
    }

    /** One authorised request to the configured instance; returns the body of a 2xx reply. */
    private static String request(Context c, String method, String path, byte[] body, int maxBytes) throws IOException {
        String base = baseUrl(c), tok = token(c);
        if (base.isEmpty() || tok.isEmpty()) throw new IOException("HOME ASSISTANT NOT SET UP • CONFIG › DEVICES");
        int kind = checkUrl(base);
        if (kind != URL_OK && kind != URL_LOCAL_HTTP) throw new IOException("INSECURE ADDRESS REFUSED • USE HTTPS");
        Http.Response r = Http.requestAllowingLocalHttp(method, base + path, headers(tok), body,
                Http.CONNECT_TIMEOUT_MS, Http.READ_TIMEOUT_MS, maxBytes);
        if (!r.ok()) throw new IOException(errorText(r.code, r.body));
        return r.body;
    }

    /**
     * Checks the address and token, reads the unit system and imports the controllable entities
     * (stored, then returned).
     */
    static List<Entity> importEntities(Context c) throws IOException {
        Context app = c.getApplicationContext();
        boolean f = configFahrenheit(request(app, "GET", "/api/config", null, Http.MAX_BODY_BYTES));
        List<Entity> found = parseStates(request(app, "GET", "/api/states", null, MAX_STATES_BYTES));
        sp(app).edit().putBoolean(KEY_FAHRENHEIT, f).apply();
        saveEntities(app, found);
        return found;
    }

    /** Reads one entity's live state. */
    static Entity state(Context c, String entityId) throws IOException {
        if (!validEntityId(entityId)) throw new IOException("UNKNOWN ENTITY • IMPORT AGAIN IN CONFIG › DEVICES");
        try {
            Entity e = parseState(new JSONObject(request(c.getApplicationContext(), "GET", "/api/states/" + entityId, null, Http.MAX_BODY_BYTES)));
            if (e == null) throw new IOException("UNEXPECTED REPLY FROM HOME ASSISTANT");
            return e;
        } catch (JSONException e) {
            throw new IOException("UNEXPECTED REPLY FROM HOME ASSISTANT");
        }
    }

    /**
     * Performs a service call and returns the new state of {@code call}'s entity as reported in
     * the reply ("on", "off"…), or "" when the reply doesn't say.
     */
    static String call(Context c, Call call) throws IOException {
        String reply = request(c.getApplicationContext(), "POST", call.path(),
                call.body.toString().getBytes(StandardCharsets.UTF_8), Http.MAX_BODY_BYTES);
        return stateAfter(reply, call.body.optString("entity_id"));
    }

    /**
     * The state of {@code entityId} in a service-call reply (Home Assistant answers with the list
     * of states that changed), or "" when it isn't listed or the reply isn't a list.
     */
    static String stateAfter(String reply, String entityId) {
        try {
            JSONArray a = new JSONArray(reply == null ? "" : reply);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && entityId != null && entityId.equals(o.optString("entity_id"))) return o.optString("state", "");
            }
        } catch (JSONException ignored) {
            // empty or non-list reply
        }
        return "";
    }

    // ---- climate through ThermostatDialog ----------------------------------------------------------

    /** {@link ThermostatDialog.Backend} for a Home Assistant climate entity. */
    static final class Climate implements ThermostatDialog.Backend {
        private final String entityId;
        /** Last live entity, for its hvac_modes and preset list. */
        private volatile Entity last;

        Climate(String entityId) { this.entityId = entityId; }

        @Override public Nest.State read(Context app) throws IOException {
            Entity e = state(app, entityId);
            last = e;
            return climateState(e, fahrenheit(app));
        }

        private List<String> haModes() {
            List<String> out = new ArrayList<>();
            Entity e = last;
            JSONArray a = e != null ? e.attributes.optJSONArray("hvac_modes") : null;
            if (a != null) for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
            return out;
        }

        @Override public void setMode(Context app, String mode) throws IOException {
            call(app, hvacMode(entityId, haMode(mode, haModes())));
        }

        @Override public void setEco(Context app, boolean on) throws IOException {
            Entity e = last;
            call(app, preset(entityId, on ? "eco" : nonEcoPreset(e != null ? e.attributes.optJSONArray("preset_modes") : null)));
        }

        @Override public void setTemps(Context app, String mode, double heatC, double coolC) throws IOException {
            call(app, temperature(entityId, mode, heatC, coolC, fahrenheit(app)));
        }
    }
}
