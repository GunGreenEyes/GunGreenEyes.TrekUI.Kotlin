package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [Nest]: OAuth code extraction, validation, SDM parsing and error text (Robolectric for real org.json). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NestTest {

    @Test
    fun codeFrom_pastedAddress() {
        assertThat(Nest.codeFrom("https://www.google.com/?code=4/0Abc%2Fdef&scope=x")).isEqualTo("4/0Abc/def")
        assertThat(Nest.codeFrom("https://www.google.com/?state=1&code=4/xyz#frag")).isEqualTo("4/xyz")
    }

    @Test
    fun codeFrom_bareCodeAndRejects() {
        assertThat(Nest.codeFrom("  4/0AbcDef  ")).isEqualTo("4/0AbcDef")
        assertThat(Nest.codeFrom("plainCode123")).isEqualTo("plainCode123")
        assertThat(Nest.codeFrom("https://www.google.com/")).isEmpty()
        assertThat(Nest.codeFrom("two words")).isEmpty()
        assertThat(Nest.codeFrom(null)).isEmpty()
    }

    @Test
    fun validation() {
        assertThat(Nest.validDeviceName(NAME)).isTrue()
        assertThat(Nest.validDeviceName("enterprises/p/devices/../../x")).isFalse()
        assertThat(Nest.validDeviceName("https://evil/devices/x")).isFalse()
        assertThat(Nest.validProject("0a1b2c3d-1111-2222-3333-444455556666")).isTrue()
        assertThat(Nest.validProject("a/b")).isFalse()
        assertThat(Nest.validProject("")).isFalse()
    }

    @Test
    fun authUrl_encodesParameters() {
        val url = Nest.authUrl("proj-1", "client id&x")
        assertThat(url).startsWith("https://nestservices.google.com/partnerconnections/proj-1/auth?")
        assertThat(url).contains("client_id=client+id%26x")
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fwww.google.com")
    }

    @Test
    fun accessExpiry_refreshesEarlyButNotTooEarly() {
        assertThat(Nest.accessExpiry(1000, 3599)).isEqualTo(1000 + (3599 - 120) * 1000L)
        assertThat(Nest.accessExpiry(0, 30)).isEqualTo(60_000L)
    }

    @Test
    fun parseDevices_keepsThermostatsWithValidNames() {
        val json = "{\"devices\":[" +
            "{\"name\":\"" + NAME + "\",\"type\":\"sdm.devices.types.THERMOSTAT\"," +
            "\"traits\":{\"sdm.devices.traits.Info\":{\"customName\":\"Hall\"}}}," +
            "{\"name\":\"" + NAME + "2\",\"type\":\"sdm.devices.types.CAMERA\"}," +
            "{\"name\":\"bad name\",\"type\":\"sdm.devices.types.THERMOSTAT\"}]}"
        val list = Nest.parseDevices(JSONObject(json))
        assertThat(list).hasSize(1)
        assertThat(list[0].label).isEqualTo("HALL")
        assertThat(Nest.parseStoredDevices("[{\"name\":\"" + NAME + "\",\"label\":\"HALL\"},{\"name\":\"x\"}]")).hasSize(1)
        assertThat(Nest.parseStoredDevices("oops")).isEmpty()
    }

    @Test
    fun labelOf_fallsBackToRoomThenDefault() {
        val room = JSONObject("{\"parentRelations\":[{\"displayName\":\"Kitchen\"}]}")
        assertThat(Nest.labelOf(room)).isEqualTo("KITCHEN")
        assertThat(Nest.labelOf(JSONObject())).isEqualTo("THERMOSTAT")
    }

    @Test
    fun parse_readsTraits() {
        val json = "{\"traits\":{" +
            "\"sdm.devices.traits.Temperature\":{\"ambientTemperatureCelsius\":20.5}," +
            "\"sdm.devices.traits.Humidity\":{\"ambientHumidityPercent\":41.6}," +
            "\"sdm.devices.traits.ThermostatMode\":{\"mode\":\"HEATCOOL\",\"availableModes\":[\"HEAT\",\"COOL\",\"HEATCOOL\",\"OFF\"]}," +
            "\"sdm.devices.traits.ThermostatEco\":{\"mode\":\"OFF\",\"availableModes\":[\"OFF\",\"MANUAL_ECO\"],\"heatCelsius\":15,\"coolCelsius\":28}," +
            "\"sdm.devices.traits.ThermostatHvac\":{\"status\":\"HEATING\"}," +
            "\"sdm.devices.traits.ThermostatTemperatureSetpoint\":{\"heatCelsius\":19,\"coolCelsius\":24}," +
            "\"sdm.devices.traits.Settings\":{\"temperatureScale\":\"FAHRENHEIT\"}," +
            "\"sdm.devices.traits.Connectivity\":{\"status\":\"OFFLINE\"}}}"
        val s = Nest.parse(JSONObject(json))
        assertThat(s.ambientC).isWithin(1e-9).of(20.5)
        assertThat(s.humidity).isEqualTo(42)
        assertThat(s.mode).isEqualTo("HEATCOOL")
        assertThat(s.modes).containsExactly("HEAT", "COOL", "HEATCOOL", "OFF").inOrder()
        assertThat(s.ecoAvailable).isTrue()
        assertThat(s.ecoOn()).isFalse()
        assertThat(s.hvac).isEqualTo("HEATING")
        assertThat(s.heatC).isWithin(1e-9).of(19.0)
        assertThat(s.coolC).isWithin(1e-9).of(24.0)
        assertThat(s.fahrenheit()).isTrue()
        assertThat(s.online()).isFalse()
    }

    @Test
    fun parse_emptyDeviceHasDefaults() {
        val s = Nest.parse(JSONObject())
        assertThat(s.ambientC.isNaN()).isTrue()
        assertThat(s.mode).isEqualTo("OFF")
        assertThat(s.online()).isTrue()
    }

    @Test
    fun errorText() {
        assertThat(Nest.errorText(401, "")).isEqualTo("UNAUTHENTICATED")
        assertThat(Nest.errorText(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Bad Request\"}"))
            .isEqualTo("invalid_grant Bad Request")
        assertThat(Nest.errorText(429, "{}")).startsWith("GOOGLE RATE LIMIT")
        assertThat(Nest.errorText(400, "{\"error\":{\"status\":\"FAILED_PRECONDITION\",\"message\":\"Thermostat is off\"}}"))
            .isEqualTo("THERMOSTAT IS OFF")
        assertThat(Nest.errorText(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}")).startsWith("ACCESS DENIED")
        assertThat(Nest.errorText(404, "not json")).startsWith("NOT FOUND")
        assertThat(Nest.errorText(500, null)).isEqualTo("GOOGLE ERROR 500")
    }

    @Test
    fun rounding() {
        assertThat(Nest.round1(21.26)).isWithin(1e-9).of(21.3)
        assertThat(Nest.toF(Nest.toC(70.0))).isWithin(1e-9).of(70.0)
    }

    private companion object {
        const val NAME = "enterprises/0a1b2c3d-1111-2222-3333-444455556666/devices/AVPHwEuXyz_-123"
    }
}
