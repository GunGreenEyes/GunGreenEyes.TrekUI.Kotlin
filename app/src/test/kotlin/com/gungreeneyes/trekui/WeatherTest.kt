package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.json.JSONException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** [Weather]: URL building, reply parsing, WMO codes and formatting (Robolectric for real org.json). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WeatherTest {

    @Test
    fun forecastUrl_roundsCoordinatesToTwoDecimals() {
        val url = Weather.forecastUrl(43.61234, -116.39876, true)
        assertThat(url).startsWith("https://api.open-meteo.com/v1/forecast?")
        assertThat(url).contains("latitude=43.61&longitude=-116.40")
        assertThat(url).contains("temperature_unit=fahrenheit")
        assertThat(url).contains("wind_speed_unit=mph")
        assertThat(url).contains("forecast_days=" + Weather.FORECAST_DAYS)
    }

    @Test
    fun forecastUrl_metric() {
        val url = Weather.forecastUrl(0.0, 0.0, false)
        assertThat(url).contains("temperature_unit=celsius")
        assertThat(url).contains("wind_speed_unit=kmh")
    }

    @Test(expected = IllegalArgumentException::class)
    fun forecastUrl_rejectsImpossibleLatitude() {
        Weather.forecastUrl(91.0, 0.0, false)
    }

    @Test
    fun validCoordinates() {
        assertThat(Weather.validCoordinates(-90.0, 180.0)).isTrue()
        assertThat(Weather.validCoordinates(Double.NaN, 0.0)).isFalse()
        assertThat(Weather.validCoordinates(0.0, -180.5)).isFalse()
    }

    @Test
    fun searchUrl_encodesTrimsAndCapsTheQuery() {
        val url = Weather.searchUrl("  São Paulo & Co ", "pt")
        assertThat(url).startsWith("https://geocoding-api.open-meteo.com/v1/search?")
        assertThat(url).contains("language=pt")
        assertThat(url).endsWith("name=S%C3%A3o+Paulo+%26+Co")
        val longName = "x".repeat(300)
        val capped = Weather.searchUrl(longName, "en")
        assertThat(capped.substring(capped.indexOf("name=") + 5)).hasLength(Weather.MAX_QUERY_LENGTH)
    }

    @Test
    fun searchUrl_badLanguageFallsBackToEnglish() {
        assertThat(Weather.searchUrl("x", "e&n")).contains("language=en")
    }

    @Test
    fun parse_readsCurrentAndDaily() {
        val d = Weather.parse(FORECAST, false)
        assertThat(d.temp).isWithin(1e-9).of(21.4)
        assertThat(d.feels).isWithin(1e-9).of(20.1)
        assertThat(d.wind).isWithin(1e-9).of(12.6)
        assertThat(d.humidity).isEqualTo(40)
        assertThat(d.code).isEqualTo(61)
        assertThat(d.day).isFalse()
        assertThat(d.fahrenheit).isFalse()
        assertThat(d.days).hasSize(2)
        assertThat(d.days[0].date).isEqualTo("2026-11-17")
        assertThat(d.days[0].pop).isEqualTo(10)
        assertThat(d.days[1].code).isEqualTo(71)
        assertThat(d.days[1].lo).isWithin(1e-9).of(-1.2)
        assertThat(d.days[1].pop).isEqualTo(-1)
    }

    @Test
    fun parse_missingFieldsUseDefaults() {
        val d = Weather.parse("{\"current\":{}}", true)
        assertThat(d.temp.isNaN()).isTrue()
        assertThat(d.humidity).isEqualTo(-1)
        assertThat(d.day).isTrue()
        assertThat(d.days).isEmpty()
    }

    @Test(expected = JSONException::class)
    fun parse_withoutCurrentFails() {
        Weather.parse("{\"daily\":{}}", false)
    }

    @Test
    fun parsePlaces_buildsNamesAndSkipsBadResults() {
        val json = "{\"results\":[" +
            "{\"name\":\"Nampa\",\"admin1\":\"Idaho\",\"country_code\":\"US\",\"latitude\":43.54,\"longitude\":-116.56}," +
            "{\"name\":\"Singapore\",\"admin1\":\"Singapore\",\"country\":\"Singapore\",\"latitude\":1.29,\"longitude\":103.85}," +
            "{\"name\":\"Nowhere\",\"latitude\":200,\"longitude\":0}]}"
        val places = Weather.parsePlaces(json)
        assertThat(places).hasSize(2)
        assertThat(places[0].name).isEqualTo("Nampa, Idaho, US")
        assertThat(places[0].lat).isWithin(1e-9).of(43.54)
        assertThat(places[1].name).isEqualTo("Singapore, Singapore")
    }

    @Test
    fun parsePlaces_noResults() {
        assertThat(Weather.parsePlaces("{}")).isEmpty()
    }

    @Test
    fun describeAndKind() {
        assertThat(Weather.describe(0)).isEqualTo("CLEAR")
        assertThat(Weather.describe(99)).isEqualTo("STORM • HAIL")
        assertThat(Weather.describe(42)).isEqualTo("CONDITIONS 42")
        assertThat(Weather.kind(1)).isEqualTo(Weather.K_CLEAR)
        assertThat(Weather.kind(2)).isEqualTo(Weather.K_PARTLY)
        assertThat(Weather.kind(48)).isEqualTo(Weather.K_FOG)
        assertThat(Weather.kind(86)).isEqualTo(Weather.K_SNOW)
        assertThat(Weather.kind(95)).isEqualTo(Weather.K_STORM)
        assertThat(Weather.kind(63)).isEqualTo(Weather.K_RAIN)
    }

    @Test
    fun unitConversionRoundTrips() {
        assertThat(Weather.cToF(100.0)).isWithin(1e-9).of(212.0)
        assertThat(Weather.fToC(32.0)).isWithin(1e-9).of(0.0)
        assertThat(Weather.fToC(Weather.cToF(21.5))).isWithin(1e-9).of(21.5)
    }

    @Test
    fun formatting() {
        assertThat(Weather.degrees(21.6)).isEqualTo("22°")
        assertThat(Weather.degrees(Double.NaN)).isEqualTo("--")
        assertThat(Weather.windUnit(true)).isEqualTo(" MPH")
        assertThat(Weather.windUnit(false)).isEqualTo(" KM/H")
        assertThat(Weather.dayName("2026-11-18", 0, Locale.US)).isEqualTo("TODAY")
        assertThat(Weather.dayName("2026-11-18", 1, Locale.US)).isEqualTo("WED")
        assertThat(Weather.dayName("not-a-date", 2, Locale.US)).isEqualTo("not-a-date")
    }

    private companion object {
        const val FORECAST = "{\"current\":{\"temperature_2m\":21.4,\"relative_humidity_2m\":40," +
            "\"apparent_temperature\":20.1,\"is_day\":0,\"weather_code\":61,\"wind_speed_10m\":12.6}," +
            "\"daily\":{\"time\":[\"2026-11-17\",\"2026-11-18\"],\"weather_code\":[3,71]," +
            "\"temperature_2m_max\":[22.0,5.5],\"temperature_2m_min\":[10.0,-1.2]," +
            "\"precipitation_probability_max\":[10,null]}}"
    }
}
