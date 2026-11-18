package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [ThermostatDialog] setpoint snapping and HEAT•COOL gap (pure JVM). */
class ThermostatDialogTest {

    @Test
    fun snap_halfDegreesCelsius() {
        assertThat(ThermostatDialog.snap(20.3, false)).isWithin(1e-9).of(20.5)
        assertThat(ThermostatDialog.snap(20.2, false)).isWithin(1e-9).of(20.0)
    }

    @Test
    fun snap_wholeDegreesFahrenheit() {
        val c = ThermostatDialog.snap(Nest.toC(70.4), true)
        assertThat(Nest.toF(c)).isWithin(1e-9).of(70.0)
    }

    @Test
    fun snap_clampsToLimits() {
        assertThat(ThermostatDialog.snap(-5.0, false)).isWithin(1e-9).of(ThermostatDialog.MIN_C)
        assertThat(ThermostatDialog.snap(50.0, true)).isWithin(1e-9).of(ThermostatDialog.MAX_C)
    }

    @Test
    fun keepGap_movesTheEditedEnd() {
        assertThat(ThermostatDialog.keepGap(20.0, 21.0, true)).isWithin(1e-9).of(20 + ThermostatDialog.GAP_C)
        assertThat(ThermostatDialog.keepGap(20.0, 21.0, false)).isWithin(1e-9).of(21 - ThermostatDialog.GAP_C)
        assertThat(ThermostatDialog.keepGap(18.0, 25.0, true)).isWithin(1e-9).of(25.0)
        assertThat(ThermostatDialog.keepGap(Double.NaN, 21.0, true)).isWithin(1e-9).of(21.0)
    }
}
