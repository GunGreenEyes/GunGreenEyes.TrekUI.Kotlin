package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** {@link ThermostatDialog} setpoint snapping and HEAT•COOL gap (pure JVM). */
public class ThermostatDialogTest {

    @Test
    public void snap_halfDegreesCelsius() {
        assertThat(ThermostatDialog.snap(20.3, false)).isWithin(1e-9).of(20.5);
        assertThat(ThermostatDialog.snap(20.2, false)).isWithin(1e-9).of(20.0);
    }

    @Test
    public void snap_wholeDegreesFahrenheit() {
        double c = ThermostatDialog.snap(Nest.toC(70.4), true);
        assertThat(Nest.toF(c)).isWithin(1e-9).of(70);
    }

    @Test
    public void snap_clampsToLimits() {
        assertThat(ThermostatDialog.snap(-5, false)).isWithin(1e-9).of(ThermostatDialog.MIN_C);
        assertThat(ThermostatDialog.snap(50, true)).isWithin(1e-9).of(ThermostatDialog.MAX_C);
    }

    @Test
    public void keepGap_movesTheEditedEnd() {
        assertThat(ThermostatDialog.keepGap(20, 21, true)).isWithin(1e-9).of(20 + ThermostatDialog.GAP_C);
        assertThat(ThermostatDialog.keepGap(20, 21, false)).isWithin(1e-9).of(21 - ThermostatDialog.GAP_C);
        assertThat(ThermostatDialog.keepGap(18, 25, true)).isWithin(1e-9).of(25);
        assertThat(ThermostatDialog.keepGap(Double.NaN, 21, true)).isWithin(1e-9).of(21);
    }
}
