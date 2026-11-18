package com.gungreeneyes.trekui.keyboard.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShiftStateTest {
    @Test
    fun singleTapShiftsOneLetter() {
        val s = ShiftState()
        s.tap(1000)
        assertThat(s.state).isEqualTo(ShiftState.ONCE)
        assertThat(s.upper).isTrue()
        s.typed()
        assertThat(s.state).isEqualTo(ShiftState.OFF)
    }

    @Test
    fun doubleTapLocksCaps() {
        val s = ShiftState()
        s.tap(1000)
        s.tap(1000 + ShiftState.DOUBLE_TAP_MS - 1)
        assertThat(s.locked).isTrue()
        s.typed()
        s.typed()
        assertThat(s.locked).isTrue()
        s.tap(5000)
        assertThat(s.state).isEqualTo(ShiftState.OFF)
    }

    @Test
    fun slowSecondTapTurnsShiftOff() {
        val s = ShiftState()
        s.tap(1000)
        s.tap(1000 + ShiftState.DOUBLE_TAP_MS + 50)
        assertThat(s.state).isEqualTo(ShiftState.OFF)
    }

    @Test
    fun autoCapsComesAndGoes() {
        val s = ShiftState()
        s.setAuto(true)
        assertThat(s.state).isEqualTo(ShiftState.ONCE)
        s.setAuto(false)
        assertThat(s.state).isEqualTo(ShiftState.OFF)
    }

    @Test
    fun autoCapsNeverOverridesManualChoiceOrLock() {
        val s = ShiftState()
        s.tap(1000) // manual ONCE
        s.setAuto(false)
        assertThat(s.state).isEqualTo(ShiftState.ONCE)
        s.tap(1100) // lock
        s.setAuto(false)
        assertThat(s.locked).isTrue()
        s.setAuto(true)
        assertThat(s.locked).isTrue()
    }

    @Test
    fun tappingAnAutomaticCapitalTurnsItOffAndDoubleTapStillLocks() {
        val s = ShiftState()
        s.setAuto(true)
        s.tap(1000)
        assertThat(s.state).isEqualTo(ShiftState.OFF)
        s.tap(1200)
        assertThat(s.locked).isTrue()
    }

    @Test
    fun resetClearsEverything() {
        val s = ShiftState()
        s.tap(1000)
        s.tap(1100)
        s.reset()
        assertThat(s.state).isEqualTo(ShiftState.OFF)
    }
}
