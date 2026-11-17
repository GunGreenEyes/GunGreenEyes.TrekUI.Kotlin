package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/** {@link Nest}: OAuth code extraction, validation, SDM parsing and error text (Robolectric for real org.json). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class NestTest {

    private static final String NAME = "enterprises/0a1b2c3d-1111-2222-3333-444455556666/devices/AVPHwEuXyz_-123";

    @Test
    public void codeFrom_pastedAddress() {
        assertThat(Nest.codeFrom("https://www.google.com/?code=4/0Abc%2Fdef&scope=x")).isEqualTo("4/0Abc/def");
        assertThat(Nest.codeFrom("https://www.google.com/?state=1&code=4/xyz#frag")).isEqualTo("4/xyz");
    }

    @Test
    public void codeFrom_bareCodeAndRejects() {
        assertThat(Nest.codeFrom("  4/0AbcDef  ")).isEqualTo("4/0AbcDef");
        assertThat(Nest.codeFrom("plainCode123")).isEqualTo("plainCode123");
        assertThat(Nest.codeFrom("https://www.google.com/")).isEmpty();
        assertThat(Nest.codeFrom("two words")).isEmpty();
        assertThat(Nest.codeFrom(null)).isEmpty();
    }

    @Test
    public void validation() {
        assertThat(Nest.validDeviceName(NAME)).isTrue();
        assertThat(Nest.validDeviceName("enterprises/p/devices/../../x")).isFalse();
        assertThat(Nest.validDeviceName("https://evil/devices/x")).isFalse();
        assertThat(Nest.validProject("0a1b2c3d-1111-2222-3333-444455556666")).isTrue();
        assertThat(Nest.validProject("a/b")).isFalse();
        assertThat(Nest.validProject("")).isFalse();
    }

    @Test
    public void authUrl_encodesParameters() {
        String url = Nest.authUrl("proj-1", "client id&x");
        assertThat(url).startsWith("https://nestservices.google.com/partnerconnections/proj-1/auth?");
        assertThat(url).contains("client_id=client+id%26x");
        assertThat(url).contains("redirect_uri=https%3A%2F%2Fwww.google.com");
    }

    @Test
    public void accessExpiry_refreshesEarlyButNotTooEarly() {
        assertThat(Nest.accessExpiry(1000, 3599)).isEqualTo(1000 + (3599 - 120) * 1000L);
        assertThat(Nest.accessExpiry(0, 30)).isEqualTo(60_000L);
    }

    @Test
    public void parseDevices_keepsThermostatsWithValidNames() throws Exception {
        String json = "{\"devices\":["
                + "{\"name\":\"" + NAME + "\",\"type\":\"sdm.devices.types.THERMOSTAT\","
                + "\"traits\":{\"sdm.devices.traits.Info\":{\"customName\":\"Hall\"}}},"
                + "{\"name\":\"" + NAME + "2\",\"type\":\"sdm.devices.types.CAMERA\"},"
                + "{\"name\":\"bad name\",\"type\":\"sdm.devices.types.THERMOSTAT\"}]}";
        List<Nest.Device> list = Nest.parseDevices(new JSONObject(json));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).label).isEqualTo("HALL");
        assertThat(Nest.parseStoredDevices("[{\"name\":\"" + NAME + "\",\"label\":\"HALL\"},{\"name\":\"x\"}]")).hasSize(1);
        assertThat(Nest.parseStoredDevices("oops")).isEmpty();
    }

    @Test
    public void labelOf_fallsBackToRoomThenDefault() throws Exception {
        JSONObject room = new JSONObject("{\"parentRelations\":[{\"displayName\":\"Kitchen\"}]}");
        assertThat(Nest.labelOf(room)).isEqualTo("KITCHEN");
        assertThat(Nest.labelOf(new JSONObject())).isEqualTo("THERMOSTAT");
    }

    @Test
    public void parse_readsTraits() throws Exception {
        String json = "{\"traits\":{"
                + "\"sdm.devices.traits.Temperature\":{\"ambientTemperatureCelsius\":20.5},"
                + "\"sdm.devices.traits.Humidity\":{\"ambientHumidityPercent\":41.6},"
                + "\"sdm.devices.traits.ThermostatMode\":{\"mode\":\"HEATCOOL\",\"availableModes\":[\"HEAT\",\"COOL\",\"HEATCOOL\",\"OFF\"]},"
                + "\"sdm.devices.traits.ThermostatEco\":{\"mode\":\"OFF\",\"availableModes\":[\"OFF\",\"MANUAL_ECO\"],\"heatCelsius\":15,\"coolCelsius\":28},"
                + "\"sdm.devices.traits.ThermostatHvac\":{\"status\":\"HEATING\"},"
                + "\"sdm.devices.traits.ThermostatTemperatureSetpoint\":{\"heatCelsius\":19,\"coolCelsius\":24},"
                + "\"sdm.devices.traits.Settings\":{\"temperatureScale\":\"FAHRENHEIT\"},"
                + "\"sdm.devices.traits.Connectivity\":{\"status\":\"OFFLINE\"}}}";
        Nest.State s = Nest.parse(new JSONObject(json));
        assertThat(s.ambientC).isWithin(1e-9).of(20.5);
        assertThat(s.humidity).isEqualTo(42);
        assertThat(s.mode).isEqualTo("HEATCOOL");
        assertThat(s.modes).containsExactly("HEAT", "COOL", "HEATCOOL", "OFF").inOrder();
        assertThat(s.ecoAvailable).isTrue();
        assertThat(s.ecoOn()).isFalse();
        assertThat(s.hvac).isEqualTo("HEATING");
        assertThat(s.heatC).isWithin(1e-9).of(19);
        assertThat(s.coolC).isWithin(1e-9).of(24);
        assertThat(s.fahrenheit()).isTrue();
        assertThat(s.online()).isFalse();
    }

    @Test
    public void parse_emptyDeviceHasDefaults() throws Exception {
        Nest.State s = Nest.parse(new JSONObject());
        assertThat(Double.isNaN(s.ambientC)).isTrue();
        assertThat(s.mode).isEqualTo("OFF");
        assertThat(s.online()).isTrue();
    }

    @Test
    public void errorText() {
        assertThat(Nest.errorText(401, "")).isEqualTo("UNAUTHENTICATED");
        assertThat(Nest.errorText(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Bad Request\"}")).isEqualTo("invalid_grant Bad Request");
        assertThat(Nest.errorText(429, "{}")).startsWith("GOOGLE RATE LIMIT");
        assertThat(Nest.errorText(400, "{\"error\":{\"status\":\"FAILED_PRECONDITION\",\"message\":\"Thermostat is off\"}}"))
                .isEqualTo("THERMOSTAT IS OFF");
        assertThat(Nest.errorText(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\"}}")).startsWith("ACCESS DENIED");
        assertThat(Nest.errorText(404, "not json")).startsWith("NOT FOUND");
        assertThat(Nest.errorText(500, null)).isEqualTo("GOOGLE ERROR 500");
    }

    @Test
    public void rounding() {
        assertThat(Nest.round1(21.26)).isWithin(1e-9).of(21.3);
        assertThat(Nest.toF(Nest.toC(70))).isWithin(1e-9).of(70);
    }
}
