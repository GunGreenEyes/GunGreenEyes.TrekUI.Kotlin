package com.gungreeneyes.trekui.licensing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/** JVM tests for {@link TrialClock}: expiry, process death, reboots and relaunch rules. */
public class TrialClockTest {
    private static final long LIMIT = 60_000L;

    /** Controllable clock: uptime and boot count. */
    private static final class FakeTime implements TimeSource {
        long now = 1_000_000L;
        long boot = 7L;

        @Override public long elapsedRealtime() { return now; }

        @Override public long bootCount() { return boot; }
    }

    private MemoryKeyValueStore store;
    private FakeTime time;

    @Before
    public void setUp() {
        store = new MemoryKeyValueStore();
        time = new FakeTime();
    }

    private TrialClock newProcess() {
        TrialClock clock = new TrialClock(store, time, LIMIT);
        clock.onProcessStart();
        return clock;
    }

    @Test
    public void firstRunStartsSessionWithFullAllowance() {
        TrialClock clock = newProcess();
        assertEquals(1L, clock.sessionCount());
        assertEquals(LIMIT, clock.remainingMillis());
        assertFalse(clock.isExpired());
    }

    @Test
    public void expiresAfterLimit() {
        TrialClock clock = newProcess();
        time.now += LIMIT - 1;
        assertFalse(clock.isExpired());
        assertEquals(1L, clock.remainingMillis());
        time.now += 1;
        assertTrue(clock.isExpired());
        assertEquals(0L, clock.remainingMillis());
    }

    @Test
    public void sessionSurvivesProcessDeath() {
        newProcess();
        time.now += 40_000L;
        TrialClock second = newProcess(); // system killed and restarted the launcher
        assertEquals(1L, second.sessionCount());
        assertEquals(20_000L, second.remainingMillis());
    }

    @Test
    public void expiredSessionNotResetByProcessDeathBeforeScreenShown() {
        newProcess();
        time.now += LIMIT + 5;
        TrialClock second = newProcess();
        assertTrue(second.isExpired());
        assertEquals(1L, second.sessionCount());
    }

    @Test
    public void relaunchAfterExpiryScreenStartsNewSession() {
        TrialClock clock = newProcess();
        time.now += LIMIT + 5;
        assertTrue(clock.isExpired());
        clock.markExpiryShown();
        TrialClock relaunched = newProcess();
        assertFalse(relaunched.isExpired());
        assertEquals(2L, relaunched.sessionCount());
        assertFalse(relaunched.wasExpiryShown());
    }

    @Test
    public void explicitNewSessionResetsAllowance() {
        TrialClock clock = newProcess();
        time.now += LIMIT * 3;
        clock.markExpiryShown();
        clock.startNewSession();
        assertEquals(LIMIT, clock.remainingMillis());
        assertFalse(clock.wasExpiryShown());
    }

    @Test
    public void rebootDetectedByUptimeGoingBackwards() {
        TrialClock clock = newProcess();
        time.now += LIMIT + 5;
        assertTrue(clock.isExpired());
        time.now = 5_000L;               // device rebooted
        time.boot = TimeSource.UNKNOWN_BOOT; // and boot count is unavailable
        assertFalse(clock.isExpired());
        assertEquals(2L, clock.sessionCount());
    }

    @Test
    public void rebootDetectedByBootCountEvenWithLongUptime() {
        TrialClock clock = newProcess();
        time.now += LIMIT + 5;
        assertTrue(clock.isExpired());
        time.boot += 1;                  // rebooted and uptime already larger than stored start
        time.now += 10 * LIMIT;
        assertFalse(clock.isExpired());
        assertEquals(2L, clock.sessionCount());
    }

    @Test
    public void unknownBootCountDoesNotResetSession() {
        TrialClock clock = newProcess();
        time.boot = TimeSource.UNKNOWN_BOOT;
        time.now += LIMIT + 1;
        assertTrue(clock.isExpired());
    }

    @Test
    public void nonPositiveLimitNeverExpires() {
        TrialClock clock = new TrialClock(store, time, 0L);
        clock.onProcessStart();
        time.now += 365L * 24 * 3_600_000L;
        assertFalse(clock.isExpired());
        assertEquals(Long.MAX_VALUE, clock.remainingMillis());
    }

    @Test
    public void changingLimitKeepsSessionStart() {
        TrialClock clock = newProcess();
        time.now += 30_000L;
        clock.setLimitMillis(10_000L);
        assertTrue(clock.isExpired());
        clock.setLimitMillis(LIMIT);
        assertEquals(30_000L, clock.remainingMillis());
    }
}
