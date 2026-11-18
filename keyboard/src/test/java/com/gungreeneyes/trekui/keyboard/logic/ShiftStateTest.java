package com.gungreeneyes.trekui.keyboard.logic;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

public class ShiftStateTest {
    @Test
    public void singleTapShiftsOneLetter() {
        ShiftState s = new ShiftState();
        s.tap(1000);
        assertThat(s.state()).isEqualTo(ShiftState.ONCE);
        assertThat(s.upper()).isTrue();
        s.typed();
        assertThat(s.state()).isEqualTo(ShiftState.OFF);
    }

    @Test
    public void doubleTapLocksCaps() {
        ShiftState s = new ShiftState();
        s.tap(1000);
        s.tap(1000 + ShiftState.DOUBLE_TAP_MS - 1);
        assertThat(s.locked()).isTrue();
        s.typed();
        s.typed();
        assertThat(s.locked()).isTrue();
        s.tap(5000);
        assertThat(s.state()).isEqualTo(ShiftState.OFF);
    }

    @Test
    public void slowSecondTapTurnsShiftOff() {
        ShiftState s = new ShiftState();
        s.tap(1000);
        s.tap(1000 + ShiftState.DOUBLE_TAP_MS + 50);
        assertThat(s.state()).isEqualTo(ShiftState.OFF);
    }

    @Test
    public void autoCapsComesAndGoes() {
        ShiftState s = new ShiftState();
        s.setAuto(true);
        assertThat(s.state()).isEqualTo(ShiftState.ONCE);
        s.setAuto(false);
        assertThat(s.state()).isEqualTo(ShiftState.OFF);
    }

    @Test
    public void autoCapsNeverOverridesManualChoiceOrLock() {
        ShiftState s = new ShiftState();
        s.tap(1000); // manual ONCE
        s.setAuto(false);
        assertThat(s.state()).isEqualTo(ShiftState.ONCE);
        s.tap(1100); // lock
        s.setAuto(false);
        assertThat(s.locked()).isTrue();
        s.setAuto(true);
        assertThat(s.locked()).isTrue();
    }

    @Test
    public void tappingAnAutomaticCapitalTurnsItOffAndDoubleTapStillLocks() {
        ShiftState s = new ShiftState();
        s.setAuto(true);
        s.tap(1000);
        assertThat(s.state()).isEqualTo(ShiftState.OFF);
        s.tap(1200);
        assertThat(s.locked()).isTrue();
    }

    @Test
    public void resetClearsEverything() {
        ShiftState s = new ShiftState();
        s.tap(1000);
        s.tap(1100);
        s.reset();
        assertThat(s.state()).isEqualTo(ShiftState.OFF);
    }
}
