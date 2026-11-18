package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/** JVM tests for the pure CONFIG-screen rules in {@link SettingsLogic}. */
public class SettingsLogicTest {

    @Test
    public void choiceColumns() {
        assertThat(SettingsLogic.choiceColumns(2, false)).isEqualTo(2);
        assertThat(SettingsLogic.choiceColumns(4, false)).isEqualTo(4);
        assertThat(SettingsLogic.choiceColumns(5, false)).isEqualTo(3);
        assertThat(SettingsLogic.choiceColumns(5, true)).isEqualTo(5);
        assertThat(SettingsLogic.choiceColumns(7, true)).isEqualTo(3);
    }

    @Test
    public void choiceCorner_segmentedBars() {
        assertThat(SettingsLogic.choiceCorner(0, 1, 1)).isEqualTo(Ui.PILL);
        assertThat(SettingsLogic.choiceCorner(0, 3, 3)).isEqualTo(Ui.LEFT);
        assertThat(SettingsLogic.choiceCorner(1, 3, 3)).isEqualTo(Ui.SQUARE);
        assertThat(SettingsLogic.choiceCorner(2, 3, 3)).isEqualTo(Ui.RIGHT);
        // last option of a partial row closes the bar
        assertThat(SettingsLogic.choiceCorner(4, 3, 5)).isEqualTo(Ui.RIGHT);
    }

    @Test
    public void dimAndDockIndexes() {
        assertThat(SettingsLogic.dimIndex(10)).isEqualTo(0);
        assertThat(SettingsLogic.dimIndex(55)).isEqualTo(1);
        assertThat(SettingsLogic.dimIndex(75)).isEqualTo(2);
        assertThat(SettingsLogic.dockIndex(0)).isEqualTo(0);
        assertThat(SettingsLogic.dockIndex(4)).isEqualTo(1);
        assertThat(SettingsLogic.dockIndex(5)).isEqualTo(2);
    }

    @Test
    public void wallpaperTarget() {
        assertThat(SettingsLogic.wallpaperTarget(0)).isEqualTo(1);
        assertThat(SettingsLogic.wallpaperTarget(1)).isEqualTo(2);
        assertThat(SettingsLogic.wallpaperTarget(2)).isEqualTo(3);
    }

    @Test
    public void isIpv4() {
        assertThat(SettingsLogic.isIpv4("192.168.1.42")).isTrue();
        assertThat(SettingsLogic.isIpv4("10.0.0.255")).isTrue();
        assertThat(SettingsLogic.isIpv4("256.1.1.1")).isFalse();
        assertThat(SettingsLogic.isIpv4("192.168.1")).isFalse();
        assertThat(SettingsLogic.isIpv4("lamp.local")).isFalse();
        assertThat(SettingsLogic.isIpv4(null)).isFalse();
    }

    @Test
    public void textHelpers() {
        assertThat(SettingsLogic.maskSecret("abcdef123")).isEqualTo("••••f123");
        assertThat(SettingsLogic.maskSecret("ab")).isEqualTo("••••ab");
        assertThat(SettingsLogic.abbreviate("short")).isEqualTo("short");
        assertThat(SettingsLogic.abbreviate("0123456789abcdefghijklmnopqrstuvwxyz")).isEqualTo("0123456789ab…stuvwxyz");
        assertThat(SettingsLogic.hexColor(0xFFFF9C00)).isEqualTo("#FF9C00");
        assertThat(SettingsLogic.hiddenAppName("com.example/.Main#10")).isEqualTo("com.example/.Main");
        assertThat(SettingsLogic.hiddenAppName("com.example/.Main")).isEqualTo("com.example/.Main");
        assertThat(SettingsLogic.panelTitle("power")).isEqualTo("POWER SYSTEMS (BATTERY)");
        assertThat(SettingsLogic.panelTitle("systems")).isEqualTo("SHIP SYSTEMS (DEVICE)");
        assertThat(SettingsLogic.count(1, "LIGHT", "LIGHTS")).isEqualTo("1 LIGHT");
        assertThat(SettingsLogic.count(3, "LIGHT", "LIGHTS")).isEqualTo("3 LIGHTS");
        assertThat(SettingsLogic.minutesLeft(1)).isEqualTo(1);
        assertThat(SettingsLogic.minutesLeft(60_000)).isEqualTo(1);
        assertThat(SettingsLogic.minutesLeft(60_001)).isEqualTo(2);
    }

    @Test
    public void importReport_emptyAndCapped() {
        List<String> none = Collections.emptyList();
        assertThat(SettingsLogic.importReport(none, none, none)).isEqualTo("No events were assigned.");
        List<String> unmatched = new ArrayList<>();
        for (int i = 0; i < 14; i++) unmatched.add("f" + i);
        String text = SettingsLogic.importReport(Arrays.asList("BUTTON  ←  a.mp3"), unmatched, Arrays.asList("bad.zip"));
        assertThat(text).startsWith("BUTTON  ←  a.mp3");
        assertThat(text).contains("• f11");
        assertThat(text).doesNotContain("• f12");
        assertThat(text).contains("• +2 more");
        assertThat(text).endsWith("• bad.zip");
    }

    @Test
    public void signalSliders() {
        assertThat(SettingsLogic.signalSliders(new float[]{36.4f, 0.995f, 1f})).asList().containsExactly(36, 100).inOrder();
    }
}
