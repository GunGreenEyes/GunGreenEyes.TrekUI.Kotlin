package com.gungreeneyes.trekui.licensing

/**
 * Measures the current trial session of an unlicensed copy.
 *
 * A session starts the first time the launcher runs, after every reboot, and after an explicit
 * relaunch once the "session complete" screen has been shown. Its length is measured with the
 * monotonic [TimeSource.elapsedRealtime] clock, so changing the date or time does not end or extend
 * it. The start point is persisted, so the session survives the launcher process being killed by the
 * system (a home app's process is recycled routinely) and continues where it left off.
 *
 * Rules, in order:
 * 1. No stored session, a different boot ([TimeSource.bootCount] changed) or an uptime smaller than
 *    the stored start (the device rebooted) → a new session starts.
 * 2. The session is expired once `elapsed >= limit`. A limit <= 0 disables expiry.
 * 3. Once expired and the expiry screen has been shown ([markExpiryShown]), the next process start
 *    ([onProcessStart]) or an explicit [startNewSession] begins a fresh session. A process death
 *    *before* the screen was shown does not reset it.
 *
 * Thread-safe; all state lives in the [KeyValueStore].
 *
 * Constructor and [setLimitMillis] are public (package-private in Java) because `:app`'s JVM tests use them.
 *
 * @param store persistent state
 * @param time clock and boot identity
 * @param limitMillis session length; <= 0 means sessions never expire
 */
class TrialClock(
    private val store: KeyValueStore,
    private val time: TimeSource,
    limitMillis: Long,
) {
    @Volatile
    private var limit: Long = limitMillis

    /** Changes the session length (debug override or a new configured value) without resetting. */
    fun setLimitMillis(limitMillis: Long) {
        limit = limitMillis
    }

    /** The configured session length in milliseconds (<= 0: unlimited). */
    fun limitMillis(): Long = limit

    /**
     * Starts a session if none is valid for this boot.
     *
     * @return true if a new session was started
     */
    @Synchronized
    fun ensureSession(): Boolean {
        val now = time.elapsedRealtime()
        val start = store.getLong(KEY_START, -1L)
        val storedBoot = store.getLong(KEY_BOOT, TimeSource.UNKNOWN_BOOT)
        val currentBoot = time.bootCount()
        val differentBoot = currentBoot != TimeSource.UNKNOWN_BOOT &&
            storedBoot != TimeSource.UNKNOWN_BOOT &&
            currentBoot != storedBoot
        if (start < 0 || now < start || differentBoot) {
            startNewSession()
            return true
        }
        return false
    }

    /**
     * Called once per launcher process. Continues a running session (process death is not a
     * relaunch), but a session whose expiry screen was already shown is replaced by a new one:
     * restarting the launcher after the screen is the intended way to keep using the trial.
     *
     * @return true if a new session was started
     */
    @Synchronized
    fun onProcessStart(): Boolean {
        if (ensureSession()) return true
        if (wasExpiryShown() && isExpired()) {
            startNewSession()
            return true
        }
        return false
    }

    /** Begins a fresh session now (explicit relaunch from the expiry screen, or a new boot). */
    @Synchronized
    fun startNewSession() {
        store.putLong(KEY_START, time.elapsedRealtime())
        store.putLong(KEY_BOOT, time.bootCount())
        store.putBoolean(KEY_EXPIRY_SHOWN, false)
        store.putLong(KEY_SESSIONS, store.getLong(KEY_SESSIONS, 0L) + 1L)
    }

    /** Milliseconds spent in the current session. */
    @Synchronized
    fun elapsedMillis(): Long {
        ensureSession()
        return maxOf(0L, time.elapsedRealtime() - store.getLong(KEY_START, time.elapsedRealtime()))
    }

    /** Milliseconds left in the current session; [Long.MAX_VALUE] when expiry is disabled. */
    @Synchronized
    fun remainingMillis(): Long {
        if (limit <= 0) return Long.MAX_VALUE
        return maxOf(0L, limit - elapsedMillis())
    }

    /** True once the current session has used its whole allowance. */
    @Synchronized
    fun isExpired(): Boolean = limit > 0 && remainingMillis() == 0L

    /** Records that the user has been shown the expiry screen for this session. */
    @Synchronized
    fun markExpiryShown() {
        store.putBoolean(KEY_EXPIRY_SHOWN, true)
    }

    /** True if the expiry screen was shown during the current session. */
    @Synchronized
    fun wasExpiryShown(): Boolean = store.getBoolean(KEY_EXPIRY_SHOWN, false)

    /** How many sessions have been started on this install (informational). */
    @Synchronized
    fun sessionCount(): Long = store.getLong(KEY_SESSIONS, 0L)

    companion object {
        const val KEY_START: String = "trial.session_start_elapsed"
        const val KEY_BOOT: String = "trial.session_boot"
        const val KEY_EXPIRY_SHOWN: String = "trial.expiry_shown"
        const val KEY_SESSIONS: String = "trial.session_count"
    }
}
