package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import org.junit.Test;

/** JVM tests for the pure home-screen rules in {@link HomeLogic}. */
public class HomeLogicTest {

    @Test
    public void tileColumns_phoneKeepsBase() {
        assertThat(HomeLogic.tileColumns(3, 360f)).isEqualTo(3);
        assertThat(HomeLogic.tileColumns(2, 411f)).isEqualTo(2);
        assertThat(HomeLogic.tileColumns(1, 320f)).isEqualTo(1);
    }

    @Test
    public void tileColumns_growsWithWidthUpToDouble() {
        assertThat(HomeLogic.tileColumns(3, 600f)).isEqualTo(5);
        assertThat(HomeLogic.tileColumns(3, 1280f)).isEqualTo(6);
        assertThat(HomeLogic.tileColumns(1, 900f)).isEqualTo(2);
    }

    @Test
    public void tileColumns_neverBelowOne() {
        assertThat(HomeLogic.tileColumns(0, 100f)).isEqualTo(1);
    }

    @Test
    public void shortLabel_trimsAndCaps() {
        assertThat(HomeLogic.shortLabel("  Maps  ")).isEqualTo("Maps");
        assertThat(HomeLogic.shortLabel("Samsung Internet Browser")).isEqualTo("Samsung Intern");
        assertThat(HomeLogic.shortLabel("Google Play St")).isEqualTo("Google Play St");
        assertThat(HomeLogic.shortLabel(null)).isEmpty();
    }

    @Test
    public void actionGlyph_mapsEveryFunction() {
        assertThat(HomeLogic.actionGlyph(Tile.A_SEARCH)).isEqualTo("search");
        assertThat(HomeLogic.actionGlyph(Tile.A_CONFIG)).isEqualTo("system");
        assertThat(HomeLogic.actionGlyph(Tile.A_SHADE)).isEqualTo("system");
        assertThat(HomeLogic.actionGlyph(Tile.A_CASCADE)).isEqualTo("system");
        assertThat(HomeLogic.actionGlyph(Tile.A_ANDROID)).isEqualTo("settings");
        assertThat(HomeLogic.actionGlyph(Tile.A_PANEL)).isEqualTo("settings");
        assertThat(HomeLogic.actionGlyph(Tile.A_DIALER)).isEqualTo("phone");
        assertThat(HomeLogic.actionGlyph(Tile.A_CLOAK)).isEqualTo("secure");
        assertThat(HomeLogic.actionGlyph(Tile.A_GOVEE)).isEqualTo("weather");
        assertThat(HomeLogic.actionGlyph(Tile.A_NEST)).isEqualTo("home");
        assertThat(HomeLogic.actionGlyph(Tile.A_APPS)).isEqualTo("apps");
        assertThat(HomeLogic.actionGlyph("unknown")).isEqualTo("apps");
        assertThat(HomeLogic.actionGlyph(null)).isEqualTo("apps");
    }

    @Test
    public void panelNamesAndGlyphs() {
        assertThat(HomeLogic.shortPanelName("weather")).isEqualTo("WEATHER");
        assertThat(HomeLogic.panelGlyph("MEDIA")).isEqualTo("music");
        assertThat(HomeLogic.panelGlyph("POWER")).isEqualTo("alert");
        assertThat(HomeLogic.panelGlyph("SYSTEMS")).isEqualTo("computer");
        assertThat(HomeLogic.panelGlyph("OTHER")).isEqualTo("apps");
    }

    @Test
    public void accentIndex_cyclesSix() {
        assertThat(HomeLogic.accentIndex(0)).isEqualTo(0);
        assertThat(HomeLogic.accentIndex(2)).isEqualTo(3);
        assertThat(HomeLogic.accentIndex(6)).isEqualTo(0);
        assertThat(HomeLogic.accentIndex(-1)).isEqualTo(5);
    }

    @Test
    public void isCommsApp_rules() {
        assertThat(HomeLogic.isCommsApp("org.thoughtcrime.securesms", "Signal", false, null, null)).isTrue();
        assertThat(HomeLogic.isCommsApp("com.example.dialer", "Phone", false, "com.example.dialer", null)).isTrue();
        assertThat(HomeLogic.isCommsApp("com.example.sms", "Texts", false, null, "com.example.sms")).isTrue();
        // e-mail apps never count, even when categorised as COMM
        assertThat(HomeLogic.isCommsApp("com.microsoft.office.outlook", "Outlook", true, null, null)).isFalse();
        assertThat(HomeLogic.isCommsApp("com.example.talk", "Team Chat", true, null, null)).isTrue();
        assertThat(HomeLogic.isCommsApp("com.example.talk", "Team Chat", false, null, null)).isFalse();
    }

    @Test
    public void stardate_startOf1987Is41000() {
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(1987, Calendar.JANUARY, 1, 0, 0);
        assertThat(HomeLogic.stardate(c)).isEqualTo("41000.0");
    }

    @Test
    public void stardate_midYear() {
        Calendar c = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        c.clear();
        c.set(2024, Calendar.JULY, 2, 0, 0); // day 184 of a 366-day year
        double expected = (183.0 / 366.0) * 1000 + 37 * 1000 + 41000;
        assertThat(HomeLogic.stardate(c)).isEqualTo(String.format(java.util.Locale.US, "%.1f", expected));
    }

    @Test
    public void batteryPercent() {
        assertThat(HomeLogic.batteryPercent(50, 100)).isEqualTo(50);
        assertThat(HomeLogic.batteryPercent(1, 3)).isEqualTo(33);
        assertThat(HomeLogic.batteryPercent(-1, 100)).isEqualTo(-1);
        assertThat(HomeLogic.batteryPercent(10, 0)).isEqualTo(-1);
    }

    @Test
    public void nextTickDelay() {
        assertThat(HomeLogic.nextTickDelayMs(60_000L * 5 + 30_000L, false, false)).isEqualTo(30_000L + HomeLogic.TICK_SLACK_MS);
        assertThat(HomeLogic.nextTickDelayMs(1_250L, true, false)).isEqualTo(750L + HomeLogic.TICK_SLACK_MS);
        assertThat(HomeLogic.nextTickDelayMs(0L, false, true)).isEqualTo(HomeLogic.CASCADE_PERIOD_MS + HomeLogic.TICK_SLACK_MS);
    }

    @Test
    public void textHelpers() {
        assertThat(HomeLogic.codeText(7, 42)).isEqualTo("07-0042");
        assertThat(HomeLogic.tileNumber(0)).isEqualTo("01");
        assertThat(HomeLogic.tileNumber(11)).isEqualTo("12");
        assertThat(HomeLogic.framePartSlot("h", "elbowT")).isEqualTo("hElbowT");
    }

    @Test
    public void batteryWatch_plugInChirpsOnlyOnChange() {
        HomeLogic.BatteryWatch w = new HomeLogic.BatteryWatch();
        assertThat(w.update(0, 80, true)).isEqualTo(HomeLogic.BatteryAlert.NONE);
        assertThat(w.update(1, 80, true)).isEqualTo(HomeLogic.BatteryAlert.PLUGGED_IN);
        assertThat(w.update(1, 81, true)).isEqualTo(HomeLogic.BatteryAlert.NONE);
    }

    @Test
    public void batteryWatch_firstUpdateNeverChirpsForPlugIn() {
        HomeLogic.BatteryWatch w = new HomeLogic.BatteryWatch();
        assertThat(w.update(1, 50, true)).isEqualTo(HomeLogic.BatteryAlert.NONE);
    }

    @Test
    public void batteryWatch_lowWarningsRepeatEveryFourPercent() {
        HomeLogic.BatteryWatch w = new HomeLogic.BatteryWatch();
        assertThat(w.update(0, 16, true)).isEqualTo(HomeLogic.BatteryAlert.NONE);
        assertThat(w.update(0, 15, true)).isEqualTo(HomeLogic.BatteryAlert.LOW);
        assertThat(w.update(0, 14, true)).isEqualTo(HomeLogic.BatteryAlert.NONE);
        assertThat(w.update(0, 11, true)).isEqualTo(HomeLogic.BatteryAlert.NONE);
        assertThat(w.update(0, 10, true)).isEqualTo(HomeLogic.BatteryAlert.LOW);
        assertThat(w.update(0, 5, true)).isEqualTo(HomeLogic.BatteryAlert.CRITICAL);
        assertThat(w.update(0, 4, true)).isEqualTo(HomeLogic.BatteryAlert.NONE);
    }

    @Test
    public void batteryWatch_quietUpdatesDoNotAnnounceButTrackState() {
        HomeLogic.BatteryWatch w = new HomeLogic.BatteryWatch();
        assertThat(w.update(0, 3, false)).isEqualTo(HomeLogic.BatteryAlert.NONE);
        assertThat(w.update(0, 3, true)).isEqualTo(HomeLogic.BatteryAlert.CRITICAL);
        // plugging in re-arms the warnings
        w.update(1, 3, true);
        assertThat(w.update(0, 3, true)).isEqualTo(HomeLogic.BatteryAlert.CRITICAL);
    }
}
