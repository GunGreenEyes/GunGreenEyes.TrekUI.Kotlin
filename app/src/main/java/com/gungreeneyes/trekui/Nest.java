package com.gungreeneyes.trekui;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Pattern;

/**
 * Google Nest thermostats through Google's Smart Device Management (Device Access) API, used by
 * {@link ThermostatDialog}, the home thermostat tiles and CONFIG › DEVICES.
 *
 * <p>Setup (once): a Device Access project (one-time registration fee to Google), a Google Cloud
 * OAuth "Web application" client with redirect https://www.google.com, then account linking:
 * the browser ends on google.com with ?code=… in the address, which the owner pastes back.
 * The refresh token is stored in the launcher's private preferences; access tokens last an hour.
 *
 * <p>Every request blocks and runs through {@link #async} on a background thread with timeouts
 * (see {@link Http}); only the application context is kept by background work. Parsing
 * (device lists, thermostat state, error bodies, pasted OAuth codes) is pure and unit-tested.
 */
final class Nest {
    private static final String SDM = "https://smartdevicemanagement.googleapis.com/v1/";
    private static final String TOKEN = "https://www.googleapis.com/oauth2/v4/token";
    static final String REDIRECT = "https://www.google.com";
    private static final String TRAIT = "sdm.devices.traits.";
    private static final String THERMOSTAT_TYPE = "sdm.devices.types.THERMOSTAT";
    /** SDM device resource names: enterprises/{project}/devices/{id}. */
    private static final Pattern DEVICE_NAME = Pattern.compile("enterprises/[A-Za-z0-9_\\-]+/devices/[A-Za-z0-9_\\-]+");
    /** Device Access project ids are UUIDs; accept any id of safe characters. */
    private static final Pattern PROJECT_ID = Pattern.compile("[A-Za-z0-9_\\-]{1,128}");
    /** Refresh this long before the token's stated expiry, so a request never races it. */
    private static final long TOKEN_MARGIN_S = 120;

    private static final ExecutorService IO = Http.newExecutor("trekui-nest");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** A linked thermostat: SDM resource name and its display label. */
    static final class Device {
        String name = "", label = "";

        JSONObject toJson() {
            JSONObject o = new JSONObject();
            try { o.put("name", name).put("label", label); } catch (JSONException ignored) { }
            return o;
        }
    }

    /** A thermostat reading. Temperatures in °C (the API's unit); NaN when not reported. */
    static final class State {
        double ambientC = Double.NaN, heatC = Double.NaN, coolC = Double.NaN, ecoHeatC = Double.NaN, ecoCoolC = Double.NaN;
        /** Relative humidity in percent, -1 when not reported. */
        int humidity = -1;
        String mode = "OFF", eco = "OFF", hvac = "OFF", scale = "CELSIUS", connectivity = "ONLINE", label = "";
        /** Modes the thermostat offers (HEAT, COOL, HEATCOOL, OFF); empty when not reported. */
        List<String> modes = new ArrayList<>();
        boolean ecoAvailable;

        boolean fahrenheit() { return "FAHRENHEIT".equals(scale); }
        boolean online() { return !"OFFLINE".equals(connectivity); }
        boolean ecoOn() { return "MANUAL_ECO".equals(eco); }
    }

    /** Main-thread result of {@link #async}: the value, or an error message (exactly one is non-null). */
    interface Result<T> { void done(T value, String error); }

    private Nest() {}

    // ---- configuration ---------------------------------------------------------------------------

    static String project(Prefs p) { return p.s("nestProject", "").trim(); }
    static String clientId(Prefs p) { return p.s("nestClientId", "").trim(); }
    static String secret(Prefs p) { return p.s("nestSecret", "").trim(); }
    static boolean configured(Prefs p) { return !project(p).isEmpty() && !clientId(p).isEmpty() && !secret(p).isEmpty(); }
    static boolean linked(Prefs p) { return !p.s("nestRefresh", "").isEmpty(); }

    /** True for a project id made of safe characters (it is placed in URLs). */
    static boolean validProject(String id) { return id != null && PROJECT_ID.matcher(id).matches(); }

    /** True for an SDM device resource name (it is placed in request URLs). */
    static boolean validDeviceName(String name) { return name != null && DEVICE_NAME.matcher(name).matches(); }

    /** Google's account-linking page for this project and OAuth client. */
    static String authUrl(Prefs p) {
        return authUrl(project(p), clientId(p));
    }

    static String authUrl(String project, String clientId) {
        return "https://nestservices.google.com/partnerconnections/" + enc(project) + "/auth?redirect_uri=" + enc(REDIRECT)
                + "&access_type=offline&prompt=consent&client_id=" + enc(clientId)
                + "&response_type=code&scope=" + enc("https://www.googleapis.com/auth/sdm.service");
    }

    static Uri authUri(Prefs p) { return Uri.parse(authUrl(p)); }

    /**
     * Pulls the authorization code out of a pasted google.com address (or accepts a bare code).
     *
     * @return the code, or "" when the text doesn't contain one
     */
    static String codeFrom(String pasted) {
        String s = pasted == null ? "" : pasted.trim();
        if (s.isEmpty()) return "";
        if (s.contains("code=")) {
            String q = s.substring(s.indexOf('?') >= 0 ? s.indexOf('?') + 1 : 0);
            int hash = q.indexOf('#');
            if (hash >= 0) q = q.substring(0, hash);
            for (String part : q.split("&")) {
                if (part.startsWith("code=")) {
                    try {
                        return URLDecoder.decode(part.substring(5), "UTF-8");
                    } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                        return "";
                    }
                }
            }
        }
        // a bare code: Google's start with "4/" and contain no spaces; anything else with a '/' is a URL
        boolean looksLikeUrl = s.contains("/") && !s.startsWith("4/");
        return s.contains(" ") || looksLikeUrl ? "" : s;
    }

    /** The thermostats found at linking time (from preferences). */
    static List<Device> devices(Prefs p) {
        return parseStoredDevices(p.s("nestDevices", "[]"));
    }

    static List<Device> parseStoredDevices(String json) {
        List<Device> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null || !validDeviceName(o.optString("name"))) continue;
                Device d = new Device();
                d.name = o.optString("name");
                d.label = o.optString("label");
                out.add(d);
            }
        } catch (JSONException ignored) {
            // corrupt preference: no devices
        }
        return out;
    }

    static Device find(Prefs p, String name) {
        for (Device d : devices(p)) if (d.name.equals(name)) return d;
        return null;
    }

    /** Forgets the link (tokens and devices); the OAuth client settings stay. */
    static void unlink(Prefs p) {
        p.put("nestRefresh", "");
        p.put("nestAccess", "");
        p.put("nestAccessExp", 0L);
        p.put("nestDevices", "[]");
    }

    // ---- async wrapper ------------------------------------------------------------------------------

    /** Blocking work run by {@link #async}. */
    interface Job<T> { T run() throws IOException; }

    /**
     * Runs a job on the Nest background thread and delivers the result on the main thread.
     * Jobs run one at a time, so a token refresh is never done twice in parallel.
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

    // ---- OAuth ----------------------------------------------------------------------------------------

    /** Exchanges a pasted authorization code for tokens and stores them. Background thread only. */
    static void link(Context c, String code) throws IOException {
        Prefs p = Prefs.get(c.getApplicationContext());
        if (code == null || code.isEmpty()) throw new IOException("NO AUTHORIZATION CODE FOUND IN THAT TEXT");
        JSONObject o = post(TOKEN, "client_id=" + enc(clientId(p)) + "&client_secret=" + enc(secret(p)) + "&code=" + enc(code)
                + "&grant_type=authorization_code&redirect_uri=" + enc(REDIRECT));
        String refresh = o.optString("refresh_token");
        if (refresh.isEmpty()) throw new IOException("GOOGLE DIDN'T RETURN A REFRESH TOKEN • LINK AGAIN");
        p.put("nestRefresh", refresh);
        saveAccess(p, o);
    }

    private static void saveAccess(Prefs p, JSONObject o) {
        p.put("nestAccess", o.optString("access_token"));
        p.put("nestAccessExp", accessExpiry(System.currentTimeMillis(), o.optLong("expires_in", 3599)));
    }

    /** When a token issued now with the given lifetime should be treated as expired (at least a minute ahead). */
    static long accessExpiry(long nowMs, long expiresInS) {
        return nowMs + Math.max(60, expiresInS - TOKEN_MARGIN_S) * 1000L;
    }

    /** A valid access token, refreshing it when it has expired. */
    private static synchronized String token(Context c) throws IOException {
        Prefs p = Prefs.get(c);
        if (!linked(p)) throw new IOException("NEST NOT LINKED • CONFIG › DEVICES");
        String access = p.s("nestAccess", "");
        if (!access.isEmpty() && System.currentTimeMillis() < p.l("nestAccessExp", 0L)) return access;
        JSONObject o;
        try {
            o = post(TOKEN, "client_id=" + enc(clientId(p)) + "&client_secret=" + enc(secret(p))
                    + "&refresh_token=" + enc(p.s("nestRefresh", "")) + "&grant_type=refresh_token");
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).contains("invalid_grant")) {
                throw new IOException("GOOGLE ENDED THE LINK • RELINK IN CONFIG › DEVICES (PUBLISH THE OAUTH APP TO AVOID THE 7-DAY LIMIT)");
            }
            throw e;
        }
        saveAccess(p, o);
        return p.s("nestAccess", "");
    }

    // ---- SDM ------------------------------------------------------------------------------------------

    /** Lists the project's thermostats and stores them. Background thread only. */
    static List<Device> fetchDevices(Context c) throws IOException {
        Context app = c.getApplicationContext();
        Prefs p = Prefs.get(app);
        if (!validProject(project(p))) throw new IOException("CHECK THE DEVICE ACCESS PROJECT ID");
        List<Device> out = parseDevices(request(app, "GET", SDM + "enterprises/" + project(p) + "/devices", null));
        JSONArray store = new JSONArray();
        for (Device d : out) store.put(d.toJson());
        p.put("nestDevices", store.toString());
        return out;
    }

    /** Thermostats from a devices.list reply (other device types and bad names are skipped). */
    static List<Device> parseDevices(JSONObject reply) {
        List<Device> out = new ArrayList<>();
        JSONArray a = reply.optJSONArray("devices");
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject d = a.optJSONObject(i);
            if (d == null || !THERMOSTAT_TYPE.equals(d.optString("type"))) continue;
            if (!validDeviceName(d.optString("name"))) continue;
            Device dev = new Device();
            dev.name = d.optString("name");
            dev.label = labelOf(d);
            out.add(dev);
        }
        return out;
    }

    /** The thermostat's custom name, else its room, else "THERMOSTAT"; upper case. */
    static String labelOf(JSONObject device) {
        JSONObject traits = device.optJSONObject("traits");
        JSONObject info = traits != null ? traits.optJSONObject(TRAIT + "Info") : null;
        String name = info != null ? info.optString("customName") : "";
        if (name.isEmpty()) {
            JSONArray rel = device.optJSONArray("parentRelations");
            JSONObject r = rel != null ? rel.optJSONObject(0) : null;
            if (r != null) name = r.optString("displayName");
        }
        return (name.isEmpty() ? "THERMOSTAT" : name).toUpperCase(Locale.getDefault());
    }

    /** Reads one thermostat. Background thread only. */
    static State state(Context c, String device) throws IOException {
        return parse(request(c.getApplicationContext(), "GET", deviceUrl(device), null));
    }

    private static String deviceUrl(String device) throws IOException {
        if (!validDeviceName(device)) throw new IOException("UNKNOWN THERMOSTAT • RELINK IN CONFIG › DEVICES");
        return SDM + device;
    }

    /** Parses a device resource into a {@link State}. Pure Java apart from org.json (unit-tested). */
    static State parse(JSONObject device) {
        State s = new State();
        s.label = labelOf(device);
        JSONObject t = device.optJSONObject("traits");
        if (t == null) return s;
        JSONObject x;
        if ((x = t.optJSONObject(TRAIT + "Temperature")) != null) s.ambientC = x.optDouble("ambientTemperatureCelsius", Double.NaN);
        if ((x = t.optJSONObject(TRAIT + "Humidity")) != null) {
            double h = x.optDouble("ambientHumidityPercent", -1);
            s.humidity = Double.isNaN(h) ? -1 : (int) Math.round(h);
        }
        if ((x = t.optJSONObject(TRAIT + "ThermostatMode")) != null) {
            s.mode = x.optString("mode", "OFF");
            JSONArray m = x.optJSONArray("availableModes");
            if (m != null) for (int i = 0; i < m.length(); i++) s.modes.add(m.optString(i));
        }
        if ((x = t.optJSONObject(TRAIT + "ThermostatEco")) != null) {
            s.eco = x.optString("mode", "OFF");
            s.ecoHeatC = x.optDouble("heatCelsius", Double.NaN);
            s.ecoCoolC = x.optDouble("coolCelsius", Double.NaN);
            JSONArray m = x.optJSONArray("availableModes");
            if (m != null) for (int i = 0; i < m.length(); i++) if ("MANUAL_ECO".equals(m.optString(i))) s.ecoAvailable = true;
        }
        if ((x = t.optJSONObject(TRAIT + "ThermostatHvac")) != null) s.hvac = x.optString("status", "OFF");
        if ((x = t.optJSONObject(TRAIT + "ThermostatTemperatureSetpoint")) != null) {
            s.heatC = x.optDouble("heatCelsius", Double.NaN);
            s.coolC = x.optDouble("coolCelsius", Double.NaN);
        }
        if ((x = t.optJSONObject(TRAIT + "Settings")) != null) s.scale = x.optString("temperatureScale", "CELSIUS");
        if ((x = t.optJSONObject(TRAIT + "Connectivity")) != null) s.connectivity = x.optString("status", "ONLINE");
        return s;
    }

    /** Sends an SDM command ("ThermostatMode.SetMode" etc.). Background thread only. */
    static void command(Context c, String device, String command, JSONObject params) throws IOException {
        try {
            request(c.getApplicationContext(), "POST", deviceUrl(device) + ":executeCommand",
                    new JSONObject().put("command", "sdm.devices.commands." + command).put("params", params));
        } catch (JSONException e) {
            throw new IOException("COULD NOT BUILD COMMAND");
        }
    }

    static void setMode(Context c, String device, String mode) throws IOException {
        try { command(c, device, "ThermostatMode.SetMode", new JSONObject().put("mode", mode)); }
        catch (JSONException e) { throw new IOException("COULD NOT BUILD COMMAND"); }
    }

    static void setEco(Context c, String device, boolean on) throws IOException {
        try { command(c, device, "ThermostatEco.SetMode", new JSONObject().put("mode", on ? "MANUAL_ECO" : "OFF")); }
        catch (JSONException e) { throw new IOException("COULD NOT BUILD COMMAND"); }
    }

    /** Sends the setpoint command that matches the mode (HEAT → SetHeat, COOL → SetCool, HEATCOOL → SetRange). */
    static void setTemps(Context c, String device, String mode, double heatC, double coolC) throws IOException {
        try {
            JSONObject p = new JSONObject();
            if ("HEAT".equals(mode)) {
                command(c, device, "ThermostatTemperatureSetpoint.SetHeat", p.put("heatCelsius", round1(heatC)));
            } else if ("COOL".equals(mode)) {
                command(c, device, "ThermostatTemperatureSetpoint.SetCool", p.put("coolCelsius", round1(coolC)));
            } else if ("HEATCOOL".equals(mode)) {
                command(c, device, "ThermostatTemperatureSetpoint.SetRange",
                        p.put("heatCelsius", round1(heatC)).put("coolCelsius", round1(coolC)));
            } else {
                throw new IOException("SETPOINTS CAN'T CHANGE WHILE THE SYSTEM IS OFF");
            }
        } catch (JSONException e) {
            throw new IOException("COULD NOT BUILD COMMAND");
        }
    }

    /** Rounds to one decimal place (the API's setpoint precision). */
    static double round1(double v) { return Math.round(v * 10.0) / 10.0; }
    static double toF(double c) { return Weather.cToF(c); }
    static double toC(double f) { return Weather.fToC(f); }

    // ---- HTTP -------------------------------------------------------------------------------------------

    /** An authorised SDM request; a stale token is refreshed once and the request retried. */
    private static JSONObject request(Context c, String method, String url, JSONObject body) throws IOException {
        String tok = token(c);
        try {
            return http(method, url, body == null ? null : body.toString(), "application/json", tok);
        } catch (IOException e) {
            if (!"UNAUTHENTICATED".equals(e.getMessage())) throw e;
            Prefs.get(c).put("nestAccessExp", 0L);
            return http(method, url, body == null ? null : body.toString(), "application/json", token(c));
        }
    }

    private static JSONObject post(String url, String form) throws IOException {
        return http("POST", url, form, "application/x-www-form-urlencoded", null);
    }

    private static JSONObject http(String method, String url, String body, String type, String bearer) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", type);
        headers.put("Accept", "application/json");
        if (bearer != null) headers.put("Authorization", "Bearer " + bearer);
        Http.Response r = Http.request(method, url, headers, body == null ? null : body.getBytes(StandardCharsets.UTF_8),
                Http.CONNECT_TIMEOUT_MS, Http.READ_TIMEOUT_MS);
        if (r.code >= 400) throw new IOException(errorText(r.code, r.body));
        try {
            return r.body.trim().isEmpty() ? new JSONObject() : new JSONObject(r.body);
        } catch (JSONException e) {
            throw new IOException("UNEXPECTED REPLY FROM GOOGLE");
        }
    }

    /** Turns Google's error JSON (SDM or OAuth style) into a short console message. */
    static String errorText(int code, String body) {
        String status = "", msg = "", oauth = "";
        try {
            JSONObject o = new JSONObject(body == null ? "" : body);
            JSONObject e = o.optJSONObject("error");
            if (e != null) { status = e.optString("status"); msg = e.optString("message"); }
            else oauth = o.optString("error") + " " + o.optString("error_description");
        } catch (JSONException ignored) {
            // not JSON: fall back to the status code
        }
        if (code == 401 || "UNAUTHENTICATED".equals(status)) return "UNAUTHENTICATED";
        if (!oauth.trim().isEmpty()) return oauth.trim();
        if (code == 429) return "GOOGLE RATE LIMIT • TRY AGAIN IN A MINUTE";
        if ("FAILED_PRECONDITION".equals(status)) return msg.isEmpty() ? "THERMOSTAT REJECTED THAT IN ITS CURRENT MODE" : msg.toUpperCase(Locale.US);
        if (code == 403 || "PERMISSION_DENIED".equals(status)) return "ACCESS DENIED • CHECK THE PROJECT ID AND THAT THE THERMOSTAT WAS SHARED WHEN LINKING";
        if (code == 404) return "NOT FOUND • CHECK THE DEVICE ACCESS PROJECT ID";
        return (msg.isEmpty() ? "GOOGLE ERROR " + code : msg).toUpperCase(Locale.US);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e);
        }
    }
}
