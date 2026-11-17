package com.gungreeneyes.trekui;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * Open-Meteo weather client (https://open-meteo.com: free, no API key, no account). Only the
 * coordinates (or the typed city name, for a search) are sent; nothing identifying.
 *
 * <p>The network calls ({@link #get}, {@link #search}) block and must run on {@link #IO}. Building
 * URLs, parsing replies, describing WMO weather codes and formatting values are pure functions
 * (no Android types) and are unit-tested on the JVM. {@link WeatherPanel} and the Pipe Lounge
 * scenes read the cached forecast JSON through {@link #parse}.
 */
final class Weather {
    /** Background executor for every weather and geocoding request (one at a time, in order). */
    static final ExecutorService IO = Http.newExecutor("trekui-weather");

    /** Forecast days requested (today plus three). */
    static final int FORECAST_DAYS = 4;
    /** Longest city query sent to the geocoder. */
    static final int MAX_QUERY_LENGTH = 100;
    /** Results asked of the geocoder. */
    static final int MAX_PLACES = 8;

    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
    private static final String GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search";

    /** One forecast day. Temperatures are in the unit requested; NaN when missing. */
    static final class Day {
        /** ISO date (yyyy-MM-dd) in the location's time zone. */
        String date;
        /** WMO weather code, and precipitation probability in percent (-1 when not reported). */
        int code, pop;
        double hi, lo;
    }

    /** Current conditions plus the daily forecast. */
    static final class Data {
        /** Air and apparent temperature (requested unit) and wind speed (mph or km/h). */
        double temp, feels, wind;
        /** WMO weather code and relative humidity in percent (-1 when not reported). */
        int code, humidity;
        /** {@code day}: the sun is up at the location; {@code fahrenheit}: units are °F / mph. */
        boolean day = true, fahrenheit;
        final List<Day> days = new ArrayList<>();
    }

    /** A geocoding result the person can pick as their weather location. */
    static final class Place {
        /** "City, Region, CC" display name. */
        String name;
        double lat, lon;
        @Override public String toString() { return name; }
    }

    private Weather() {}

    // ---- network (background thread only) ---------------------------------------------------------

    /** Fetches a URL and returns the body; any non-200 status is an {@link IOException}. */
    static String get(String url) throws IOException {
        Http.Response r = Http.get(url);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        return r.body;
    }

    /**
     * Looks up places by name with Open-Meteo's geocoder, in the device language.
     *
     * @return up to {@link #MAX_PLACES} matches (empty when nothing matches)
     */
    static List<Place> search(String name) throws IOException, JSONException {
        return parsePlaces(get(searchUrl(name, Locale.getDefault().getLanguage())));
    }

    // ---- URLs ------------------------------------------------------------------------------------

    /** True for a real latitude/longitude pair (not NaN, within ±90 / ±180). */
    static boolean validCoordinates(double lat, double lon) {
        return !Double.isNaN(lat) && !Double.isNaN(lon) && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    /**
     * Forecast request for current conditions and {@link #FORECAST_DAYS} days. Coordinates are
     * rounded to two decimals (about 1 km) so the exact position never leaves the device.
     *
     * @param f true for °F and mph, false for °C and km/h
     * @throws IllegalArgumentException for impossible coordinates
     */
    static String forecastUrl(double lat, double lon, boolean f) {
        if (!validCoordinates(lat, lon)) throw new IllegalArgumentException("bad coordinates");
        return String.format(Locale.US,
                FORECAST_URL + "?latitude=%.2f&longitude=%.2f"
                        + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                        + "&temperature_unit=%s&wind_speed_unit=%s&timezone=auto&forecast_days=%d",
                lat, lon, f ? "fahrenheit" : "celsius", f ? "mph" : "kmh", FORECAST_DAYS);
    }

    /** Geocoding request; the query is trimmed, capped at {@link #MAX_QUERY_LENGTH} and URL-encoded. */
    static String searchUrl(String name, String language) {
        String q = name == null ? "" : name.trim();
        if (q.length() > MAX_QUERY_LENGTH) q = q.substring(0, MAX_QUERY_LENGTH);
        String lang = language == null || !language.matches("[A-Za-z]{2,3}") ? "en" : language.toLowerCase(Locale.US);
        return GEOCODING_URL + "?count=" + MAX_PLACES + "&format=json&language=" + lang + "&name=" + encode(q);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError(e); // UTF-8 is always supported
        }
    }

    // ---- parsing ---------------------------------------------------------------------------------

    /**
     * Parses a forecast reply.
     *
     * @param f the unit system the reply was requested in
     * @throws JSONException when the reply has no {@code current} block or is not JSON
     */
    static Data parse(String json, boolean f) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONObject cur = o.getJSONObject("current");
        Data d = new Data();
        d.fahrenheit = f;
        d.temp = cur.optDouble("temperature_2m", Double.NaN);
        d.feels = cur.optDouble("apparent_temperature", d.temp);
        d.wind = cur.optDouble("wind_speed_10m", 0);
        d.humidity = cur.optInt("relative_humidity_2m", -1);
        d.code = cur.optInt("weather_code", 0);
        d.day = cur.optInt("is_day", 1) == 1;
        JSONObject daily = o.optJSONObject("daily");
        if (daily != null) {
            JSONArray t = daily.optJSONArray("time");
            JSONArray wc = daily.optJSONArray("weather_code");
            JSONArray hi = daily.optJSONArray("temperature_2m_max");
            JSONArray lo = daily.optJSONArray("temperature_2m_min");
            JSONArray pp = daily.optJSONArray("precipitation_probability_max");
            for (int i = 0; t != null && i < t.length(); i++) {
                Day day = new Day();
                day.date = t.optString(i);
                day.code = wc != null ? wc.optInt(i, 0) : 0;
                day.hi = hi != null ? hi.optDouble(i, Double.NaN) : Double.NaN;
                day.lo = lo != null ? lo.optDouble(i, Double.NaN) : Double.NaN;
                day.pop = pp != null && !pp.isNull(i) ? pp.optInt(i, -1) : -1;
                d.days.add(day);
            }
        }
        return d;
    }

    /**
     * Parses a geocoding reply into places named "City, Region, CC" (the region is left out
     * when it repeats the city). Results without valid coordinates are skipped.
     */
    static List<Place> parsePlaces(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        JSONArray r = o.optJSONArray("results");
        List<Place> out = new ArrayList<>();
        for (int i = 0; r != null && i < r.length(); i++) {
            JSONObject p = r.optJSONObject(i);
            if (p == null) continue;
            Place pl = new Place();
            String city = p.optString("name", "");
            StringBuilder sb = new StringBuilder(city);
            String admin = p.optString("admin1", "");
            if (!admin.isEmpty() && !admin.equals(city)) sb.append(", ").append(admin);
            String cc = p.optString("country_code", p.optString("country", ""));
            if (!cc.isEmpty()) sb.append(", ").append(cc);
            pl.name = sb.toString();
            pl.lat = p.optDouble("latitude", Double.NaN);
            pl.lon = p.optDouble("longitude", Double.NaN);
            if (!validCoordinates(pl.lat, pl.lon) || city.isEmpty()) continue;
            out.add(pl);
        }
        return out;
    }

    // ---- presentation ---------------------------------------------------------------------------

    /** WMO weather interpretation code, as used by Open-Meteo, in console words. */
    static String describe(int code) {
        switch (code) {
            case 0: return "CLEAR";
            case 1: return "MAINLY CLEAR";
            case 2: return "PARTLY CLOUDY";
            case 3: return "OVERCAST";
            case 45: case 48: return "FOG";
            case 51: case 53: case 55: return "DRIZZLE";
            case 56: case 57: return "FREEZING DRIZZLE";
            case 61: return "LIGHT RAIN";
            case 63: return "RAIN";
            case 65: return "HEAVY RAIN";
            case 66: case 67: return "FREEZING RAIN";
            case 71: return "LIGHT SNOW";
            case 73: return "SNOW";
            case 75: return "HEAVY SNOW";
            case 77: return "SNOW GRAINS";
            case 80: case 81: return "RAIN SHOWERS";
            case 82: return "VIOLENT SHOWERS";
            case 85: case 86: return "SNOW SHOWERS";
            case 95: return "THUNDERSTORM";
            case 96: case 99: return "STORM • HAIL";
            default: return "CONDITIONS " + code;
        }
    }

    /** Pictogram kinds drawn by {@link WeatherPanel.WxIcon} and the Pipe Lounge scenes. */
    static final int K_CLEAR = 0, K_PARTLY = 1, K_CLOUD = 2, K_FOG = 3, K_RAIN = 4, K_SNOW = 5, K_STORM = 6;

    /** Maps a WMO code to the pictogram kind ({@code K_*}). */
    static int kind(int code) {
        if (code <= 1) return K_CLEAR;
        if (code == 2) return K_PARTLY;
        if (code == 3) return K_CLOUD;
        if (code == 45 || code == 48) return K_FOG;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return K_SNOW;
        if (code >= 95) return K_STORM;
        return K_RAIN;
    }

    /** Celsius to Fahrenheit. */
    static double cToF(double c) { return c * 9.0 / 5.0 + 32.0; }

    /** Fahrenheit to Celsius. */
    static double fToC(double f) { return (f - 32.0) * 5.0 / 9.0; }

    /** Whole degrees with a degree sign ("21°"), or "--" when unknown. */
    static String degrees(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "--" : Math.round(v) + "°";
    }

    /** Wind speed unit matching the requested unit system. */
    static String windUnit(boolean fahrenheit) { return fahrenheit ? " MPH" : " KM/H"; }

    /**
     * Short upper-case day name for a forecast row: "TODAY" for the first row, else the weekday
     * of the ISO date in the given locale; the raw date if it can't be parsed.
     */
    static String dayName(String isoDate, int index, Locale locale) {
        if (index == 0) return "TODAY";
        if (isoDate == null) return "";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            in.setLenient(false);
            return new SimpleDateFormat("EEE", locale).format(in.parse(isoDate)).toUpperCase(locale);
        } catch (ParseException e) {
            return isoDate;
        }
    }
}
