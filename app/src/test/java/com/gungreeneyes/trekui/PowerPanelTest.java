package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import android.os.BatteryManager;

import org.junit.Test;

/** {@link PowerPanel} wording for battery states (pure JVM; BatteryManager constants are inlined). */
public class PowerPanelTest {

    @Test
    public void stateText() {
        assertThat(PowerPanel.stateText(100, BatteryManager.BATTERY_STATUS_FULL, BatteryManager.BATTERY_PLUGGED_AC))
                .isEqualTo("FULLY CHARGED");
        assertThat(PowerPanel.stateText(55, BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_PLUGGED_USB))
                .isEqualTo("CHARGING • USB");
        assertThat(PowerPanel.stateText(55, BatteryManager.BATTERY_STATUS_NOT_CHARGING, BatteryManager.BATTERY_PLUGGED_WIRELESS))
                .isEqualTo("CONNECTED • INDUCTION (WIRELESS)");
        assertThat(PowerPanel.stateText(10, BatteryManager.BATTERY_STATUS_DISCHARGING, 0)).isEqualTo("LOW POWER WARNING");
        assertThat(PowerPanel.stateText(80, BatteryManager.BATTERY_STATUS_DISCHARGING, 0)).isEqualTo("ON INTERNAL POWER");
        assertThat(PowerPanel.stateText(40, BatteryManager.BATTERY_STATUS_CHARGING, PowerPanel.PLUGGED_DOCK))
                .isEqualTo("CHARGING • DOCK");
    }

    @Test
    public void isLow() {
        assertThat(PowerPanel.isLow(15, 0)).isTrue();
        assertThat(PowerPanel.isLow(15, BatteryManager.BATTERY_PLUGGED_AC)).isFalse();
        assertThat(PowerPanel.isLow(-1, 0)).isFalse();
        assertThat(PowerPanel.isLow(16, 0)).isFalse();
    }

    @Test
    public void detailText() {
        assertThat(PowerPanel.detailText(312, 4200, BatteryManager.BATTERY_HEALTH_GOOD))
                .isEqualTo("TEMP 31.2°C  •  4.20 V  •  HEALTH NOMINAL");
        assertThat(PowerPanel.detailText(Integer.MIN_VALUE, -1, BatteryManager.BATTERY_HEALTH_OVERHEAT)).isEqualTo("OVERHEAT");
        assertThat(PowerPanel.detailText(Integer.MIN_VALUE, 4, -1)).isEqualTo("4.00 V  •  HEALTH UNKNOWN");
    }
}
