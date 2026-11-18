package com.gungreeneyes.trekui

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * Open-Meteo weather client (https://open-meteo.com: free, no API key, no account). Only the
 * coordinates (or the typed city name, for a search) are sent; nothing identifying.
 *
 * The network calls ([get], [search]) block and must run on [IO]. Building
 * URLs, parsing replies, describing WMO weather codes and formatting values are pure functions
 * (no Android types) and are unit-tested on the JVM. [WeatherPanel] reads the cached forecast
 * JSON through [parse].
 */
internal object Weather {
    /** Background executor for every weather and geocoding request (one at a time, in order). */
    @JvmField
    val IO: ExecutorService = Http.newExecutor("trekui-weather")

    /** Forecast days requested (today plus three). */
    const val FORECAST_DAYS = 4

    /** Longest city query sent to the geocoder. */
    const val MAX_QUERY_LENGTH = 100

    /** Results asked of the geocoder. */
    const val MAX_PLACES = 8

    private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
    private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"

    private val LANGUAGE = Regex("[A-Za-z]{2,3}")

    /** One forecast day. Temperatures are in the unit requested; NaN when missing. */
    class Day {
        /** ISO date (yyyy-MM-dd) in the location's time zone. */
        var date: String? = null

        /** WMO weather code. */
        var code = 0

        /** Precipitation probability in percent (-1 when not reported). */
        var pop = 0
        var hi = 0.0
        var lo = 0.0
    }

    /** Current conditions plus the daily forecast. */
    class Data {
        /** Air temperature (requested unit). */
        var temp = 0.0

        /** Apparent temperature (requested unit). */
        var feels = 0.0

        /** Wind speed (mph or km/h). */
        var wind = 0.0

        /** WMO weather code. */
        var code = 0

        /** Relative humidity in percent (-1 when not reported). */
        var humidity = 0

        /** The sun is up at the location. */
        var day = true

        /** Units are °F / mph. */
        var fahrenheit = false
        val days: MutableList<Day> = ArrayList()
    }

    /** A geocoding result the person can pick as their weather location. */
    class Place {
        /** "City, Region, CC" display name. */
        var name: String? = null
        var lat = 0.0
        var lon = 0.0

        override fun toString(): String = name.toString()
    }

    // ---- network (background thread only) ---------------------------------------------------------

    /** Fetches a URL and returns the body; any non-200 status is an [IOException]. */
    @Throws(IOException::class)
    fun get(url: String): String {
        val r = Http.get(url)
        if (r.code != 200) throw IOException("HTTP " + r.code)
        return r.body
    }

    /**
     * Looks up places by name with Open-Meteo's geocoder, in the device language.
     *
     * @return up to [MAX_PLACES] matches (empty when nothing matches)
     */
    @Throws(IOException::class, JSONException::class)
    fun search(name: String?): List<Place> = parsePlaces(get(searchUrl(name, Locale.getDefault().language)))

    // ---- URLs ------------------------------------------------------------------------------------

    /** True for a real latitude/longitude pair (not NaN, within ±90 / ±180). */
    fun validCoordinates(lat: Double, lon: Double): Boolean =
        !lat.isNaN() && !lon.isNaN() && lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180

    /**
     * Forecast request for current conditions and [FORECAST_DAYS] days. Coordinates are
     * rounded to two decimals (about 1 km) so the exact position never leaves the device.
     *
     * @param f true for °F and mph, false for °C and km/h
     * @throws IllegalArgumentException for impossible coordinates
     */
    fun forecastUrl(lat: Double, lon: Double, f: Boolean): String {
        require(validCoordinates(lat, lon)) { "bad coordinates" }
        return String.format(
            Locale.US,
            FORECAST_URL + "?latitude=%.2f&longitude=%.2f" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&temperature_unit=%s&wind_speed_unit=%s&timezone=auto&forecast_days=%d",
            lat,
            lon,
            if (f) "fahrenheit" else "celsius",
            if (f) "mph" else "kmh",
            FORECAST_DAYS,
        )
    }

    /** Geocoding request; the query is trimmed, capped at [MAX_QUERY_LENGTH] and URL-encoded. */
    fun searchUrl(name: String?, language: String?): String {
        var q = name?.trim() ?: ""
        if (q.length > MAX_QUERY_LENGTH) q = q.substring(0, MAX_QUERY_LENGTH)
        val lang = if (language == null || !LANGUAGE.matches(language)) "en" else language.lowercase(Locale.US)
        return "$GEOCODING_URL?count=$MAX_PLACES&format=json&language=$lang&name=" + URLEncoder.encode(q, "UTF-8")
    }

    // ---- parsing ---------------------------------------------------------------------------------

    /**
     * Parses a forecast reply.
     *
     * @param f the unit system the reply was requested in
     * @throws JSONException when the reply has no `current` block or is not JSON
     */
    @Throws(JSONException::class)
    fun parse(json: String, f: Boolean): Data {
        val o = JSONObject(json)
        val cur = o.getJSONObject("current")
        val d = Data()
        d.fahrenheit = f
        d.temp = cur.optDouble("temperature_2m", Double.NaN)
        d.feels = cur.optDouble("apparent_temperature", d.temp)
        d.wind = cur.optDouble("wind_speed_10m", 0.0)
        d.humidity = cur.optInt("relative_humidity_2m", -1)
        d.code = cur.optInt("weather_code", 0)
        d.day = cur.optInt("is_day", 1) == 1
        val daily = o.optJSONObject("daily")
        if (daily != null) {
            val t = daily.optJSONArray("time")
            val wc = daily.optJSONArray("weather_code")
            val hi = daily.optJSONArray("temperature_2m_max")
            val lo = daily.optJSONArray("temperature_2m_min")
            val pp = daily.optJSONArray("precipitation_probability_max")
            var i = 0
            while (t != null && i < t.length()) {
                val day = Day()
                day.date = t.optString(i)
                day.code = wc?.optInt(i, 0) ?: 0
                day.hi = hi?.optDouble(i, Double.NaN) ?: Double.NaN
                day.lo = lo?.optDouble(i, Double.NaN) ?: Double.NaN
                day.pop = if (pp != null && !pp.isNull(i)) pp.optInt(i, -1) else -1
                d.days.add(day)
                i++
            }
        }
        return d
    }

    /**
     * Parses a geocoding reply into places named "City, Region, CC" (the region is left out
     * when it repeats the city). Results without valid coordinates are skipped.
     */
    @Throws(JSONException::class)
    fun parsePlaces(json: String): List<Place> {
        val o = JSONObject(json)
        val r = o.optJSONArray("results")
        val out = ArrayList<Place>()
        var i = 0
        while (r != null && i < r.length()) {
            val p = r.optJSONObject(i++) ?: continue
            val pl = Place()
            val city = p.optString("name", "")
            val sb = StringBuilder(city)
            val admin = p.optString("admin1", "")
            if (admin.isNotEmpty() && admin != city) sb.append(", ").append(admin)
            val cc = p.optString("country_code", p.optString("country", ""))
            if (cc.isNotEmpty()) sb.append(", ").append(cc)
            pl.name = sb.toString()
            pl.lat = p.optDouble("latitude", Double.NaN)
            pl.lon = p.optDouble("longitude", Double.NaN)
            if (!validCoordinates(pl.lat, pl.lon) || city.isEmpty()) continue
            out.add(pl)
        }
        return out
    }

    // ---- presentation ---------------------------------------------------------------------------

    /** WMO weather interpretation code, as used by Open-Meteo, in console words. */
    fun describe(code: Int): String =
        when (code) {
            0 -> "CLEAR"
            1 -> "MAINLY CLEAR"
            2 -> "PARTLY CLOUDY"
            3 -> "OVERCAST"
            45, 48 -> "FOG"
            51, 53, 55 -> "DRIZZLE"
            56, 57 -> "FREEZING DRIZZLE"
            61 -> "LIGHT RAIN"
            63 -> "RAIN"
            65 -> "HEAVY RAIN"
            66, 67 -> "FREEZING RAIN"
            71 -> "LIGHT SNOW"
            73 -> "SNOW"
            75 -> "HEAVY SNOW"
            77 -> "SNOW GRAINS"
            80, 81 -> "RAIN SHOWERS"
            82 -> "VIOLENT SHOWERS"
            85, 86 -> "SNOW SHOWERS"
            95 -> "THUNDERSTORM"
            96, 99 -> "STORM • HAIL"
            else -> "CONDITIONS $code"
        }

    /** Pictogram kinds drawn by [WeatherPanel.WxIcon]. */
    const val K_CLEAR = 0
    const val K_PARTLY = 1
    const val K_CLOUD = 2
    const val K_FOG = 3
    const val K_RAIN = 4
    const val K_SNOW = 5
    const val K_STORM = 6

    /** Maps a WMO code to the pictogram kind (`K_*`). */
    fun kind(code: Int): Int {
        if (code <= 1) return K_CLEAR
        if (code == 2) return K_PARTLY
        if (code == 3) return K_CLOUD
        if (code == 45 || code == 48) return K_FOG
        if (code in 71..77 || code == 85 || code == 86) return K_SNOW
        if (code >= 95) return K_STORM
        return K_RAIN
    }

    /** Celsius to Fahrenheit. */
    fun cToF(c: Double): Double = c * 9.0 / 5.0 + 32.0

    /** Fahrenheit to Celsius. */
    fun fToC(f: Double): Double = (f - 32.0) * 5.0 / 9.0

    /** Whole degrees with a degree sign ("21°"), or "--" when unknown. */
    fun degrees(v: Double): String = if (v.isNaN() || v.isInfinite()) "--" else Math.round(v).toString() + "°"

    /** Wind speed unit matching the requested unit system. */
    fun windUnit(fahrenheit: Boolean): String = if (fahrenheit) " MPH" else " KM/H"

    /**
     * Short upper-case day name for a forecast row: "TODAY" for the first row, else the weekday
     * of the ISO date in the given locale; the raw date if it can't be parsed.
     */
    fun dayName(isoDate: String?, index: Int, locale: Locale): String {
        if (index == 0) return "TODAY"
        if (isoDate == null) return ""
        return try {
            val inFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            inFmt.isLenient = false
            SimpleDateFormat("EEE", locale).format(inFmt.parse(isoDate)!!).uppercase(locale)
        } catch (e: ParseException) {
            isoDate
        }
    }
}
