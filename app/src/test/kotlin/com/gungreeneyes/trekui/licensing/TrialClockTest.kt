package com.gungreeneyes.trekui.licensing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** JVM tests for [TrialClock]: expiry, process death, reboots and relaunch rules. */
class TrialClockTest {

    /** Controllable clock: uptime and boot count. */
    private class FakeTime : TimeSource {
        var now = 1_000_000L
        var boot = 7L

        override fun elapsedRealtime(): Long = now

        override fun bootCount(): Long = boot
    }

    private lateinit var store: MemoryKeyValueStore
    private lateinit var time: FakeTime

    @Before
    fun setUp() {
        store = MemoryKeyValueStore()
        time = FakeTime()
    }

    private fun newProcess(): TrialClock {
        val clock = TrialClock(store, time, LIMIT)
        clock.onProcessStart()
        return clock
    }

    @Test
    fun firstRunStartsSessionWithFullAllowance() {
        val clock = newProcess()
        assertEquals(1L, clock.sessionCount())
        assertEquals(LIMIT, clock.remainingMillis())
        assertFalse(clock.isExpired())
    }

    @Test
    fun expiresAfterLimit() {
        val clock = newProcess()
        time.now += LIMIT - 1
        assertFalse(clock.isExpired())
        assertEquals(1L, clock.remainingMillis())
        time.now += 1
        assertTrue(clock.isExpired())
        assertEquals(0L, clock.remainingMillis())
    }

    @Test
    fun sessionSurvivesProcessDeath() {
        newProcess()
        time.now += 40_000L
        val second = newProcess() // system killed and restarted the launcher
        assertEquals(1L, second.sessionCount())
        assertEquals(20_000L, second.remainingMillis())
    }

    @Test
    fun expiredSessionNotResetByProcessDeathBeforeScreenShown() {
        newProcess()
        time.now += LIMIT + 5
        val second = newProcess()
        assertTrue(second.isExpired())
        assertEquals(1L, second.sessionCount())
    }

    @Test
    fun relaunchAfterExpiryScreenStartsNewSession() {
        val clock = newProcess()
        time.now += LIMIT + 5
        assertTrue(clock.isExpired())
        clock.markExpiryShown()
        val relaunched = newProcess()
        assertFalse(relaunched.isExpired())
        assertEquals(2L, relaunched.sessionCount())
        assertFalse(relaunched.wasExpiryShown())
    }

    @Test
    fun explicitNewSessionResetsAllowance() {
        val clock = newProcess()
        time.now += LIMIT * 3
        clock.markExpiryShown()
        clock.startNewSession()
        assertEquals(LIMIT, clock.remainingMillis())
        assertFalse(clock.wasExpiryShown())
    }

    @Test
    fun rebootDetectedByUptimeGoingBackwards() {
        val clock = newProcess()
        time.now += LIMIT + 5
        assertTrue(clock.isExpired())
        time.now = 5_000L // device rebooted
        time.boot = TimeSource.UNKNOWN_BOOT // and boot count is unavailable
        assertFalse(clock.isExpired())
        assertEquals(2L, clock.sessionCount())
    }

    @Test
    fun rebootDetectedByBootCountEvenWithLongUptime() {
        val clock = newProcess()
        time.now += LIMIT + 5
        assertTrue(clock.isExpired())
        time.boot += 1 // rebooted and uptime already larger than stored start
        time.now += 10 * LIMIT
        assertFalse(clock.isExpired())
        assertEquals(2L, clock.sessionCount())
    }

    @Test
    fun unknownBootCountDoesNotResetSession() {
        val clock = newProcess()
        time.boot = TimeSource.UNKNOWN_BOOT
        time.now += LIMIT + 1
        assertTrue(clock.isExpired())
    }

    @Test
    fun nonPositiveLimitNeverExpires() {
        val clock = TrialClock(store, time, 0L)
        clock.onProcessStart()
        time.now += 365L * 24 * 3_600_000L
        assertFalse(clock.isExpired())
        assertEquals(Long.MAX_VALUE, clock.remainingMillis())
    }

    @Test
    fun changingLimitKeepsSessionStart() {
        val clock = newProcess()
        time.now += 30_000L
        clock.setLimitMillis(10_000L)
        assertTrue(clock.isExpired())
        clock.setLimitMillis(LIMIT)
        assertEquals(30_000L, clock.remainingMillis())
    }

    private companion object {
        const val LIMIT = 60_000L
    }
}
