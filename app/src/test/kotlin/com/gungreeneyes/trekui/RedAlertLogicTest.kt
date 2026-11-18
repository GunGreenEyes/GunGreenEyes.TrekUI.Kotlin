package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** JVM tests for the RED ALERT state machine, timeouts and pulse in [RedAlertLogic]. */
class RedAlertLogicTest {

    @Test
    fun idle_isNotActive() {
        val s = RedAlertLogic.State.NONE
        assertThat(s.phase(T0)).isEqualTo(RedAlertLogic.IDLE)
        assertThat(s.active(T0)).isFalse()
        assertThat(s.expired(T0)).isFalse()
        assertThat(s.remainingMs(T0)).isEqualTo(0L)
    }

    @Test
    fun engage_startsUnmutedAlertForTimeout() {
        val s = RedAlertLogic.State.NONE.engage(T0, 60)
        assertThat(s.phase(T0)).isEqualTo(RedAlertLogic.ALERT)
        assertThat(s.since).isEqualTo(T0)
        assertThat(s.until).isEqualTo(T0 + 60_000L)
        assertThat(s.remainingMs(T0 + 15_000L)).isEqualTo(45_000L)
    }

    @Test
    fun timeout_endsAlertAndReportsExpired() {
        val s = RedAlertLogic.State.NONE.engage(T0, 30)
        assertThat(s.active(T0 + 29_999L)).isTrue()
        assertThat(s.expired(T0 + 29_999L)).isFalse()
        assertThat(s.active(T0 + 30_000L)).isFalse()
        assertThat(s.expired(T0 + 30_000L)).isTrue()
        assertThat(s.phase(T0 + 60_000L)).isEqualTo(RedAlertLogic.IDLE)
    }

    @Test
    fun mute_togglesOnlyWhileActive() {
        val s = RedAlertLogic.State.NONE.engage(T0, 60).toggleMute(T0 + 1)
        assertThat(s.phase(T0 + 1)).isEqualTo(RedAlertLogic.MUTED)
        assertThat(s.toggleMute(T0 + 2).phase(T0 + 2)).isEqualTo(RedAlertLogic.ALERT)
        val idle = RedAlertLogic.State.NONE.toggleMute(T0)
        assertThat(idle.muted).isFalse()
        assertThat(idle.active(T0)).isFalse()
    }

    @Test
    fun reEngage_extendsAndKeepsMute() {
        val s = RedAlertLogic.State.NONE.engage(T0, 30).toggleMute(T0)
        val again = s.engage(T0 + 20_000L, 30)
        assertThat(again.since).isEqualTo(T0)
        assertThat(again.until).isEqualTo(T0 + 50_000L)
        assertThat(again.muted).isTrue()
    }

    @Test
    fun engageAfterExpiry_startsFreshUnmuted() {
        val old = RedAlertLogic.State.NONE.engage(T0, 15).toggleMute(T0)
        val fresh = old.engage(T0 + 20_000L, 15)
        assertThat(fresh.since).isEqualTo(T0 + 20_000L)
        assertThat(fresh.muted).isFalse()
    }

    @Test
    fun standDown_andToggle() {
        val on = RedAlertLogic.State.NONE.toggle(T0, 60)
        assertThat(on.active(T0)).isTrue()
        val off = on.toggle(T0 + 1000L, 60)
        assertThat(off.active(T0 + 1000L)).isFalse()
        assertThat(off.since).isEqualTo(0L)
        assertThat(on.standDown()).isSameInstanceAs(RedAlertLogic.State.NONE)
    }

    @Test
    fun timeouts_clampToOfferedValues() {
        assertThat(RedAlertLogic.clampTimeout(30)).isEqualTo(30)
        assertThat(RedAlertLogic.clampTimeout(0)).isEqualTo(RedAlertLogic.DEFAULT_TIMEOUT_SECONDS)
        assertThat(RedAlertLogic.clampTimeout(-5)).isEqualTo(RedAlertLogic.DEFAULT_TIMEOUT_SECONDS)
        assertThat(RedAlertLogic.clampTimeout(99_999)).isEqualTo(RedAlertLogic.DEFAULT_TIMEOUT_SECONDS)
        assertThat(RedAlertLogic.TIMEOUT_SECONDS[RedAlertLogic.timeoutIndex(300)]).isEqualTo(300)
        assertThat(RedAlertLogic.TIMEOUT_SECONDS[RedAlertLogic.timeoutIndex(7)]).isEqualTo(RedAlertLogic.DEFAULT_TIMEOUT_SECONDS)
        // a bad stored value can never make an endless alert
        val s = RedAlertLogic.State.NONE.engage(T0, 0)
        assertThat(s.until - s.since).isEqualTo(RedAlertLogic.DEFAULT_TIMEOUT_SECONDS * 1000L)
    }

    @Test
    fun labels() {
        assertThat(RedAlertLogic.timeoutLabel(15)).isEqualTo("15 SEC")
        assertThat(RedAlertLogic.timeoutLabel(120)).isEqualTo("2 MIN")
        assertThat(RedAlertLogic.countdown(65_000L)).isEqualTo("1:05")
        assertThat(RedAlertLogic.countdown(64_001L)).isEqualTo("1:05")
        assertThat(RedAlertLogic.countdown(-10L)).isEqualTo("0:00")
    }

    @Test
    fun pulse_staysWithinFloorAndOne() {
        assertThat(RedAlertLogic.pulse(T0, T0, true)).isWithin(1e-6f).of(1f)
        assertThat(RedAlertLogic.pulse(T0, T0 + RedAlertLogic.PULSE_PERIOD_MS / 2, true))
            .isWithin(1e-6f).of(RedAlertLogic.PULSE_FLOOR)
        for (t in 0L until 5000L step 37) {
            val p = RedAlertLogic.pulse(T0, T0 + t, true)
            assertThat(p).isAtLeast(RedAlertLogic.PULSE_FLOOR - 1e-6f)
            assertThat(p).isAtMost(1f + 1e-6f)
        }
        assertThat(RedAlertLogic.pulse(T0, T0 + 777, false)).isEqualTo(1f)
    }

    @Test
    fun sound_respectsMuteSettingRingerAndDnd() {
        val on = RedAlertLogic.State.NONE.engage(T0, 60)
        assertThat(RedAlertLogic.soundAllowed(on, T0, true, true, true)).isTrue()
        assertThat(RedAlertLogic.soundAllowed(on.toggleMute(T0), T0, true, true, true)).isFalse()
        assertThat(RedAlertLogic.soundAllowed(on, T0, false, true, true)).isFalse()
        assertThat(RedAlertLogic.soundAllowed(on, T0, true, false, true)).isFalse()
        assertThat(RedAlertLogic.soundAllowed(on, T0, true, true, false)).isFalse()
        assertThat(RedAlertLogic.soundAllowed(on, T0 + 60_000L, true, true, true)).isFalse()
        assertThat(RedAlertLogic.soundAllowed(RedAlertLogic.State.NONE, T0, true, true, true)).isFalse()
    }

    private companion object {
        const val T0 = 1_000_000L
    }
}
