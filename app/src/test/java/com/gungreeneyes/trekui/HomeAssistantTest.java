package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link HomeAssistant}: address checks (HTTPS, local-name cleartext), /api/states parsing,
 * climate mapping onto {@link Nest.State}, service-call building and error text; plus the
 * {@link Http} local-cleartext rule it relies on. Robolectric only for the real org.json and Looper.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HomeAssistantTest {

    private static final String STATES = "["
            + "{\"entity_id\":\"sensor.outside\",\"state\":\"12\",\"attributes\":{\"friendly_name\":\"Outside\"}},"
            + "{\"entity_id\":\"scene.movie_night\",\"state\":\"2024-01-01\",\"attributes\":{\"friendly_name\":\"Movie Night\"}},"
            + "{\"entity_id\":\"light.kitchen\",\"state\":\"on\",\"attributes\":{\"friendly_name\":\"Kitchen\",\"brightness\":128,"
            + "\"supported_color_modes\":[\"hs\",\"color_temp\"]}},"
            + "{\"entity_id\":\"light.attic\",\"state\":\"off\",\"attributes\":{\"supported_color_modes\":[\"onoff\"]}},"
            + "{\"entity_id\":\"climate.hall\",\"state\":\"heat\",\"attributes\":{\"friendly_name\":\"Hall\"}},"
            + "{\"entity_id\":\"switch.fan_plug\",\"state\":\"off\",\"attributes\":{\"friendly_name\":\"Fan plug\"}},"
            + "{\"entity_id\":\"BAD ID\",\"state\":\"on\"},"
            + "\"not an object\""
            + "]";

    // ---- addresses ------------------------------------------------------------------------------

    @Test
    public void normalizeUrl() {
        assertThat(HomeAssistant.normalizeUrl("  ha.example.org/  ")).isEqualTo("https://ha.example.org");
        assertThat(HomeAssistant.normalizeUrl("https://x.ui.nabu.casa/api/")).isEqualTo("https://x.ui.nabu.casa");
        assertThat(HomeAssistant.normalizeUrl("http://homeassistant.local:8123")).isEqualTo("http://homeassistant.local:8123");
        assertThat(HomeAssistant.normalizeUrl("")).isEmpty();
        assertThat(HomeAssistant.normalizeUrl(null)).isEmpty();
    }

    @Test
    public void checkUrl_httpsAndLocalNamesOnly() {
        assertThat(HomeAssistant.checkUrl("https://x.ui.nabu.casa")).isEqualTo(HomeAssistant.URL_OK);
        assertThat(HomeAssistant.checkUrl("https://192.168.1.10:8123")).isEqualTo(HomeAssistant.URL_OK);
        assertThat(HomeAssistant.checkUrl("http://homeassistant.local:8123")).isEqualTo(HomeAssistant.URL_LOCAL_HTTP);
        assertThat(HomeAssistant.checkUrl("http://ha.home.arpa")).isEqualTo(HomeAssistant.URL_LOCAL_HTTP);
        assertThat(HomeAssistant.checkUrl("http://192.168.1.10:8123")).isEqualTo(HomeAssistant.URL_INSECURE);
        assertThat(HomeAssistant.checkUrl("http://example.com")).isEqualTo(HomeAssistant.URL_INSECURE);
        assertThat(HomeAssistant.checkUrl("http://local")).isEqualTo(HomeAssistant.URL_INSECURE);
        assertThat(HomeAssistant.checkUrl("http://evil.local.example.com")).isEqualTo(HomeAssistant.URL_INSECURE);
        assertThat(HomeAssistant.checkUrl("ftp://ha.local")).isEqualTo(HomeAssistant.URL_INVALID);
        assertThat(HomeAssistant.checkUrl("https://user:pw@ha.example.org")).isEqualTo(HomeAssistant.URL_INVALID);
        assertThat(HomeAssistant.checkUrl("https://ha.example.org/?x=1")).isEqualTo(HomeAssistant.URL_INVALID);
        assertThat(HomeAssistant.checkUrl("")).isEqualTo(HomeAssistant.URL_INVALID);
    }

    @Test
    public void http_localCleartextMatchesTheNetworkSecurityConfig() {
        assertThat(Http.isLocalCleartext("http://homeassistant.local:8123/api/states")).isTrue();
        assertThat(Http.isLocalCleartext("http://HA.Home.Arpa/api/")).isTrue();
        assertThat(Http.isLocalCleartext("http://homeassistant.local./api")).isTrue();
        assertThat(Http.isLocalCleartext("http://10.0.0.5/api")).isFalse();
        assertThat(Http.isLocalCleartext("http://[fe80::1]/api")).isFalse();
        assertThat(Http.isLocalCleartext("http://.local/api")).isFalse();
        assertThat(Http.isLocalCleartext("https://homeassistant.local/api")).isFalse(); // https needs no exception
        assertThat(Http.isLocalCleartext("http://example.com/api")).isFalse();
        assertThat(Http.isLocalCleartext(null)).isFalse();
    }

    @Test
    public void http_refusesPlainHttpOutsideLocalNames() {
        IOException e = assertThrows(IOException.class, () ->
                Http.requestAllowingLocalHttp("GET", "http://192.168.1.2/api/", null, null, 1000, 1000, 1024));
        assertThat(e).hasMessageThat().contains("INSECURE");
        assertThrows(IOException.class, () -> Http.request("GET", "http://homeassistant.local/api/", null, null, 1000, 1000));
    }

    // ---- parsing --------------------------------------------------------------------------------

    @Test
    public void parseStates_keepsControllableSortedByDomain() throws IOException {
        List<HomeAssistant.Entity> list = HomeAssistant.parseStates(STATES);
        List<String> ids = new ArrayList<>();
        for (HomeAssistant.Entity e : list) ids.add(e.id);
        // lights (attic before kitchen by name), switch, climate, scene; sensor and bad entries dropped
        assertThat(ids).containsExactly("light.attic", "light.kitchen", "switch.fan_plug", "climate.hall", "scene.movie_night").inOrder();
        HomeAssistant.Entity kitchen = list.get(1);
        assertThat(kitchen.name).isEqualTo("Kitchen");
        assertThat(kitchen.on()).isTrue();
        assertThat(kitchen.brightnessPercent()).isEqualTo(50);
        assertThat(kitchen.supportsColor()).isTrue();
        assertThat(kitchen.supportsBrightness()).isTrue();
        HomeAssistant.Entity attic = list.get(0);
        assertThat(attic.name).isEqualTo("attic"); // no friendly_name: the object id
        assertThat(attic.supportsColor()).isFalse();
        assertThat(attic.supportsBrightness()).isFalse();
    }

    @Test
    public void parseStates_rejectsNonList() {
        assertThrows(IOException.class, () -> HomeAssistant.parseStates("{\"message\":\"nope\"}"));
    }

    @Test
    public void storedEntities_roundTripWithoutLiveState() throws IOException {
        List<HomeAssistant.Entity> list = HomeAssistant.parseStates(STATES);
        String json = HomeAssistant.storeJson(list);
        assertThat(json).doesNotContain("brightness");
        List<HomeAssistant.Entity> back = HomeAssistant.parseStored(json);
        assertThat(back).hasSize(list.size());
        assertThat(back.get(1).id).isEqualTo("light.kitchen");
        assertThat(back.get(1).name).isEqualTo("Kitchen");
        assertThat(HomeAssistant.parseStored("[{\"id\":\"../x\"}, 5]")).isEmpty();
        assertThat(HomeAssistant.parseStored("corrupt")).isEmpty();
    }

    @Test
    public void configFahrenheit() {
        assertThat(HomeAssistant.configFahrenheit("{\"unit_system\":{\"temperature\":\"°F\"}}")).isTrue();
        assertThat(HomeAssistant.configFahrenheit("{\"unit_system\":{\"temperature\":\"°C\"}}")).isFalse();
        assertThat(HomeAssistant.configFahrenheit("not json")).isFalse();
    }

    @Test
    public void climateState_heatCoolInFahrenheit() throws Exception {
        HomeAssistant.Entity e = HomeAssistant.parseState(new JSONObject("{\"entity_id\":\"climate.hall\",\"state\":\"heat_cool\","
                + "\"attributes\":{\"friendly_name\":\"Hall\",\"current_temperature\":68,\"target_temp_low\":65,\"target_temp_high\":75,"
                + "\"hvac_modes\":[\"off\",\"heat\",\"cool\",\"heat_cool\"],\"hvac_action\":\"cooling\",\"current_humidity\":41.6,"
                + "\"preset_modes\":[\"none\",\"eco\"],\"preset_mode\":\"none\"}}"));
        Nest.State s = HomeAssistant.climateState(e, true);
        assertThat(s.mode).isEqualTo("HEATCOOL");
        assertThat(s.modes).containsExactly("OFF", "HEAT", "COOL", "HEATCOOL").inOrder();
        assertThat(s.ambientC).isWithin(0.01).of(20.0);
        assertThat(s.heatC).isWithin(0.01).of(Weather.fToC(65));
        assertThat(s.coolC).isWithin(0.01).of(Weather.fToC(75));
        assertThat(s.hvac).isEqualTo("COOLING");
        assertThat(s.humidity).isEqualTo(42);
        assertThat(s.fahrenheit()).isTrue();
        assertThat(s.ecoAvailable).isTrue();
        assertThat(s.ecoOn()).isFalse();
        assertThat(s.online()).isTrue();
        assertThat(s.label).isEqualTo("HALL");
    }

    @Test
    public void climateState_singleSetpointEcoAndOffline() throws Exception {
        HomeAssistant.Entity cool = HomeAssistant.parseState(new JSONObject("{\"entity_id\":\"climate.den\",\"state\":\"cool\","
                + "\"attributes\":{\"temperature\":24.5,\"preset_mode\":\"eco\",\"preset_modes\":[\"eco\",\"away\"]}}"));
        Nest.State s = HomeAssistant.climateState(cool, false);
        assertThat(s.mode).isEqualTo("COOL");
        assertThat(s.coolC).isWithin(0.001).of(24.5);
        assertThat(Double.isNaN(s.heatC)).isTrue();
        assertThat(s.ecoOn()).isTrue();
        assertThat(s.humidity).isEqualTo(-1);
        HomeAssistant.Entity gone = HomeAssistant.parseState(new JSONObject("{\"entity_id\":\"climate.den\",\"state\":\"unavailable\"}"));
        assertThat(HomeAssistant.climateState(gone, false).online()).isFalse();
    }

    @Test
    public void modeMapping() throws Exception {
        assertThat(HomeAssistant.consoleMode("auto", true)).isEqualTo("HEATCOOL");
        assertThat(HomeAssistant.consoleMode("auto", false)).isEqualTo("AUTO");
        assertThat(HomeAssistant.consoleMode("fan_only", false)).isEqualTo("FAN_ONLY");
        assertThat(HomeAssistant.haMode("HEATCOOL", Arrays.asList("off", "auto"))).isEqualTo("auto");
        assertThat(HomeAssistant.haMode("HEATCOOL", Arrays.asList("off", "heat_cool", "auto"))).isEqualTo("heat_cool");
        assertThat(HomeAssistant.haMode("HEAT", null)).isEqualTo("heat");
        assertThat(HomeAssistant.nonEcoPreset(new JSONArray("[\"eco\",\"none\"]"))).isEqualTo("none");
        assertThat(HomeAssistant.nonEcoPreset(new JSONArray("[\"eco\",\"away\"]"))).isEqualTo("away");
        assertThat(HomeAssistant.nonEcoPreset(null)).isEqualTo("none");
    }

    // ---- service calls --------------------------------------------------------------------------

    @Test
    public void primary_togglesOrActivates() {
        HomeAssistant.Call light = HomeAssistant.primary("light.kitchen");
        assertThat(light.path()).isEqualTo("/api/services/light/toggle");
        assertThat(light.body.optString("entity_id")).isEqualTo("light.kitchen");
        assertThat(HomeAssistant.primary("input_boolean.guest").path()).isEqualTo("/api/services/input_boolean/toggle");
        assertThat(HomeAssistant.primary("scene.movie").path()).isEqualTo("/api/services/scene/turn_on");
        assertThat(HomeAssistant.primary("script.goodnight").path()).isEqualTo("/api/services/script/turn_on");
        assertThat(HomeAssistant.primary("climate.hall")).isNull();
        assertThat(HomeAssistant.primary("lock.front")).isNull();
    }

    @Test
    public void light_brightnessAndColour() {
        HomeAssistant.Call c = HomeAssistant.light("light.kitchen", 140, 0xFF9C00);
        assertThat(c.path()).isEqualTo("/api/services/light/turn_on");
        assertThat(c.body.optInt("brightness_pct")).isEqualTo(100);
        JSONArray rgb = c.body.optJSONArray("rgb_color");
        assertThat(rgb.optInt(0)).isEqualTo(255);
        assertThat(rgb.optInt(1)).isEqualTo(0x9C);
        assertThat(rgb.optInt(2)).isEqualTo(0);
        HomeAssistant.Call dim = HomeAssistant.light("light.kitchen", 30, -1);
        assertThat(dim.body.has("rgb_color")).isFalse();
        assertThat(dim.body.optInt("brightness_pct")).isEqualTo(30);
        assertThat(HomeAssistant.power("switch.plug", false).path()).isEqualTo("/api/services/switch/turn_off");
    }

    @Test
    public void climateCalls() throws IOException {
        HomeAssistant.Call range = HomeAssistant.temperature("climate.hall", "HEATCOOL", 18.0, 24.0, true);
        assertThat(range.path()).isEqualTo("/api/services/climate/set_temperature");
        assertThat(range.body.optDouble("target_temp_low")).isEqualTo(64.0);
        assertThat(range.body.optDouble("target_temp_high")).isEqualTo(75.0);
        HomeAssistant.Call heat = HomeAssistant.temperature("climate.hall", "HEAT", 20.3, Double.NaN, false);
        assertThat(heat.body.optDouble("temperature")).isEqualTo(20.5);
        assertThrows(IOException.class, () -> HomeAssistant.temperature("climate.hall", "OFF", 20, 22, false));
        assertThat(HomeAssistant.hvacMode("climate.hall", "cool").body.optString("hvac_mode")).isEqualTo("cool");
        assertThat(HomeAssistant.preset("climate.hall", "eco").path()).isEqualTo("/api/services/climate/set_preset_mode");
    }

    @Test
    public void callsRejectUnsafeNames() {
        assertThrows(IllegalArgumentException.class, () -> HomeAssistant.primary("light.kitchen/../x"));
        assertThrows(IllegalArgumentException.class, () -> HomeAssistant.servicePath("light", "turn_on?x=1"));
        assertThrows(IllegalArgumentException.class, () -> HomeAssistant.servicePath("../api", "x"));
    }

    @Test
    public void tileArguments() {
        assertThat(HomeAssistant.entityOfArg("light.kitchen#panel")).isEqualTo("light.kitchen");
        assertThat(HomeAssistant.panelArg("light.kitchen#panel")).isTrue();
        assertThat(HomeAssistant.panelArg("light.kitchen")).isFalse();
        assertThat(HomeAssistant.entityOfArg(null)).isEmpty();
    }

    @Test
    public void headersAndReplies() {
        assertThat(HomeAssistant.headers("abc").get("Authorization")).isEqualTo("Bearer abc");
        assertThat(HomeAssistant.stateAfter("[{\"entity_id\":\"light.a\",\"state\":\"on\"},{\"entity_id\":\"light.b\",\"state\":\"off\"}]", "light.b"))
                .isEqualTo("off");
        assertThat(HomeAssistant.stateAfter("[]", "light.b")).isEmpty();
        assertThat(HomeAssistant.stateAfter("", "light.b")).isEmpty();
    }

    @Test
    public void errorText() {
        assertThat(HomeAssistant.errorText(401, "")).contains("TOKEN");
        assertThat(HomeAssistant.errorText(404, "")).contains("NOT FOUND");
        assertThat(HomeAssistant.errorText(400, "{\"message\":\"Invalid JSON\"}")).isEqualTo("INVALID JSON");
        assertThat(HomeAssistant.errorText(302, "")).contains("REDIRECTED");
        assertThat(HomeAssistant.errorText(500, "oops")).isEqualTo("HOME ASSISTANT ERROR 500");
    }

    @Test
    public void localNameHintOnlyBeforeAndroid13() {
        assertThat(HomeAssistant.localNameMayFail("http://homeassistant.local:8123", 32)).isTrue();
        assertThat(HomeAssistant.localNameMayFail("https://HA.LOCAL", 26)).isTrue();
        assertThat(HomeAssistant.localNameMayFail("http://homeassistant.local:8123", 33)).isFalse();
        assertThat(HomeAssistant.localNameMayFail("http://ha.home.arpa", 26)).isFalse();
        assertThat(HomeAssistant.localNameMayFail("https://192.168.1.5:8123", 26)).isFalse();
        assertThat(HomeAssistant.localNameMayFail("", 26)).isFalse();
        assertThat(HomeAssistant.localNameMayFail("http://bad host", 26)).isFalse();
    }
}
