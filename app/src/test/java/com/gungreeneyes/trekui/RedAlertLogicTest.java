package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** JVM tests for the RED ALERT state machine, timeouts and pulse in {@link RedAlertLogic}. */
public class RedAlertLogicTest {

    private static final long T0 = 1_000_000L;

    @Test
    public void idle_isNotActive() {
        RedAlertLogic.State s = RedAlertLogic.State.NONE;
        assertThat(s.phase(T0)).isEqualTo(RedAlertLogic.IDLE);
        assertThat(s.active(T0)).isFalse();
        assertThat(s.expired(T0)).isFalse();
        assertThat(s.remainingMs(T0)).isEqualTo(0L);
    }

    @Test
    public void engage_startsUnmutedAlertForTimeout() {
        RedAlertLogic.State s = RedAlertLogic.State.NONE.engage(T0, 60);
        assertThat(s.phase(T0)).isEqualTo(RedAlertLogic.ALERT);
        assertThat(s.since).isEqualTo(T0);
        assertThat(s.until).isEqualTo(T0 + 60_000L);
        assertThat(s.remainingMs(T0 + 15_000L)).isEqualTo(45_000L);
    }

    @Test
    public void timeout_endsAlertAndReportsExpired() {
        RedAlertLogic.State s = RedAlertLogic.State.NONE.engage(T0, 30);
        assertThat(s.active(T0 + 29_999L)).isTrue();
        assertThat(s.expired(T0 + 29_999L)).isFalse();
        assertThat(s.active(T0 + 30_000L)).isFalse();
        assertThat(s.expired(T0 + 30_000L)).isTrue();
        assertThat(s.phase(T0 + 60_000L)).isEqualTo(RedAlertLogic.IDLE);
    }

    @Test
    public void mute_togglesOnlyWhileActive() {
        RedAlertLogic.State s = RedAlertLogic.State.NONE.engage(T0, 60).toggleMute(T0 + 1);
        assertThat(s.phase(T0 + 1)).isEqualTo(RedAlertLogic.MUTED);
        assertThat(s.toggleMute(T0 + 2).phase(T0 + 2)).isEqualTo(RedAlertLogic.ALERT);
        RedAlertLogic.State idle = RedAlertLogic.State.NONE.toggleMute(T0);
        assertThat(idle.muted).isFalse();
        assertThat(idle.active(T0)).isFalse();
    }

    @Test
    public void reEngage_extendsAndKeepsMute() {
        RedAlertLogic.State s = RedAlertLogic.State.NONE.engage(T0, 30).toggleMute(T0);
        RedAlertLogic.State again = s.engage(T0 + 20_000L, 30);
        assertThat(again.since).isEqualTo(T0);
        assertThat(again.until).isEqualTo(T0 + 50_000L);
        assertThat(again.muted).isTrue();
    }

    @Test
    public void engageAfterExpiry_startsFreshUnmuted() {
        RedAlertLogic.State old = RedAlertLogic.State.NONE.engage(T0, 15).toggleMute(T0);
        RedAlertLogic.State fresh = old.engage(T0 + 20_000L, 15);
        assertThat(fresh.since).isEqualTo(T0 + 20_000L);
        assertThat(fresh.muted).isFalse();
    }

    @Test
    public void standDown_andToggle() {
        RedAlertLogic.State on = RedAlertLogic.State.NONE.toggle(T0, 60);
        assertThat(on.active(T0)).isTrue();
        RedAlertLogic.State off = on.toggle(T0 + 1000L, 60);
        assertThat(off.active(T0 + 1000L)).isFalse();
        assertThat(off.since).isEqualTo(0L);
        assertThat(on.standDown()).isSameInstanceAs(RedAlertLogic.State.NONE);
    }

    @Test
    public void timeouts_clampToOfferedValues() {
        assertThat(RedAlertLogic.clampTimeout(30)).isEqualTo(30);
        assertThat(RedAlertLogic.clampTimeout(0)).isEqualTo(RedAlertLogic.DEFAULT_TIMEOUT_SECONDS);
        assertThat(RedAlertLogic.clampTimeout(-5)).isEqualTo(RedAlertLogic.DEFAULT_TIMEOUT_SECONDS);
        assertThat(RedAlertLogic.clampTimeout(99_999)).isEqualTo(RedAlertLogic.DEFAULT_TIMEOUT_SECONDS);
        assertThat(RedAlertLogic.TIMEOUT_SECONDS[RedAlertLogic.timeoutIndex(300)]).isEqualTo(300);
        assertThat(RedAlertLogic.TIMEOUT_SECONDS[RedAlertLogic.timeoutIndex(7)]).isEqualTo(RedAlertLogic.DEFAULT_TIMEOUT_SECONDS);
        // a bad stored value can never make an endless alert
        RedAlertLogic.State s = RedAlertLogic.State.NONE.engage(T0, 0);
        assertThat(s.until - s.since).isEqualTo(RedAlertLogic.DEFAULT_TIMEOUT_SECONDS * 1000L);
    }

    @Test
    public void labels() {
        assertThat(RedAlertLogic.timeoutLabel(15)).isEqualTo("15 SEC");
        assertThat(RedAlertLogic.timeoutLabel(120)).isEqualTo("2 MIN");
        assertThat(RedAlertLogic.countdown(65_000L)).isEqualTo("1:05");
        assertThat(RedAlertLogic.countdown(64_001L)).isEqualTo("1:05");
        assertThat(RedAlertLogic.countdown(-10L)).isEqualTo("0:00");
    }

    @Test
    public void pulse_staysWithinFloorAndOne() {
        assertThat(RedAlertLogic.pulse(T0, T0, true)).isWithin(1e-6f).of(1f);
        assertThat(RedAlertLogic.pulse(T0, T0 + RedAlertLogic.PULSE_PERIOD_MS / 2, true))
                .isWithin(1e-6f).of(RedAlertLogic.PULSE_FLOOR);
        for (long t = 0; t < 5000; t += 37) {
            float p = RedAlertLogic.pulse(T0, T0 + t, true);
            assertThat(p).isAtLeast(RedAlertLogic.PULSE_FLOOR - 1e-6f);
            assertThat(p).isAtMost(1f + 1e-6f);
        }
        assertThat(RedAlertLogic.pulse(T0, T0 + 777, false)).isEqualTo(1f);
    }

    @Test
    public void sound_respectsMuteSettingRingerAndDnd() {
        RedAlertLogic.State on = RedAlertLogic.State.NONE.engage(T0, 60);
        assertThat(RedAlertLogic.soundAllowed(on, T0, true, true, true)).isTrue();
        assertThat(RedAlertLogic.soundAllowed(on.toggleMute(T0), T0, true, true, true)).isFalse();
        assertThat(RedAlertLogic.soundAllowed(on, T0, false, true, true)).isFalse();
        assertThat(RedAlertLogic.soundAllowed(on, T0, true, false, true)).isFalse();
        assertThat(RedAlertLogic.soundAllowed(on, T0, true, true, false)).isFalse();
        assertThat(RedAlertLogic.soundAllowed(on, T0 + 60_000L, true, true, true)).isFalse();
        assertThat(RedAlertLogic.soundAllowed(RedAlertLogic.State.NONE, T0, true, true, true)).isFalse();
    }
}
