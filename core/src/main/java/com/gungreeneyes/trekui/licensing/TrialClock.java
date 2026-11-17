package com.gungreeneyes.trekui.licensing;

/**
 * Measures the current trial session of an unlicensed copy.
 *
 * <p>A session starts the first time the launcher runs, after every reboot, and after an
 * explicit relaunch once the "session complete" screen has been shown. Its length is measured
 * with the monotonic {@link TimeSource#elapsedRealtime()} clock, so changing the date or time
 * does not end or extend it. The start point is persisted, so the session survives the launcher
 * process being killed by the system (a home app's process is recycled routinely) and continues
 * where it left off.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>No stored session, a different boot ({@link TimeSource#bootCount()} changed) or an uptime
 *       smaller than the stored start (the device rebooted) &rarr; a new session starts.</li>
 *   <li>The session is expired once {@code elapsed >= limit}. A limit &lt;= 0 disables expiry.</li>
 *   <li>Once expired and the expiry screen has been shown ({@link #markExpiryShown()}), the next
 *       process start ({@link #onProcessStart()}) or an explicit {@link #startNewSession()} begins
 *       a fresh session. A process death <em>before</em> the screen was shown does not reset it.</li>
 * </ol>
 * Thread-safe; all state lives in the {@link KeyValueStore}.
 */
public final class TrialClock {
    static final String KEY_START = "trial.session_start_elapsed";
    static final String KEY_BOOT = "trial.session_boot";
    static final String KEY_EXPIRY_SHOWN = "trial.expiry_shown";
    static final String KEY_SESSIONS = "trial.session_count";

    private final KeyValueStore store;
    private final TimeSource time;
    private volatile long limitMillis;

    /**
     * @param store       persistent state
     * @param time        clock and boot identity
     * @param limitMillis session length; &lt;= 0 means sessions never expire
     */
    TrialClock(KeyValueStore store, TimeSource time, long limitMillis) {
        this.store = store;
        this.time = time;
        this.limitMillis = limitMillis;
    }

    /** Changes the session length (debug override or a new configured value) without resetting. */
    void setLimitMillis(long limitMillis) {
        this.limitMillis = limitMillis;
    }

    /** The configured session length in milliseconds (&lt;= 0: unlimited). */
    public long limitMillis() {
        return limitMillis;
    }

    /**
     * Starts a session if none is valid for this boot.
     *
     * @return true if a new session was started
     */
    public synchronized boolean ensureSession() {
        long now = time.elapsedRealtime();
        long start = store.getLong(KEY_START, -1L);
        long storedBoot = store.getLong(KEY_BOOT, TimeSource.UNKNOWN_BOOT);
        long currentBoot = time.bootCount();
        boolean differentBoot = currentBoot != TimeSource.UNKNOWN_BOOT
                && storedBoot != TimeSource.UNKNOWN_BOOT
                && currentBoot != storedBoot;
        if (start < 0 || now < start || differentBoot) {
            startNewSession();
            return true;
        }
        return false;
    }

    /**
     * Called once per launcher process. Continues a running session (process death is not a
     * relaunch), but a session whose expiry screen was already shown is replaced by a new one:
     * restarting the launcher after the screen is the intended way to keep using the trial.
     *
     * @return true if a new session was started
     */
    public synchronized boolean onProcessStart() {
        if (ensureSession()) return true;
        if (wasExpiryShown() && isExpired()) {
            startNewSession();
            return true;
        }
        return false;
    }

    /** Begins a fresh session now (explicit relaunch from the expiry screen, or a new boot). */
    public synchronized void startNewSession() {
        store.putLong(KEY_START, time.elapsedRealtime());
        store.putLong(KEY_BOOT, time.bootCount());
        store.putBoolean(KEY_EXPIRY_SHOWN, false);
        store.putLong(KEY_SESSIONS, store.getLong(KEY_SESSIONS, 0L) + 1L);
    }

    /** Milliseconds spent in the current session. */
    public synchronized long elapsedMillis() {
        ensureSession();
        return Math.max(0L, time.elapsedRealtime() - store.getLong(KEY_START, time.elapsedRealtime()));
    }

    /** Milliseconds left in the current session; {@link Long#MAX_VALUE} when expiry is disabled. */
    public synchronized long remainingMillis() {
        if (limitMillis <= 0) return Long.MAX_VALUE;
        return Math.max(0L, limitMillis - elapsedMillis());
    }

    /** True once the current session has used its whole allowance. */
    public synchronized boolean isExpired() {
        return limitMillis > 0 && remainingMillis() == 0L;
    }

    /** Records that the user has been shown the expiry screen for this session. */
    public synchronized void markExpiryShown() {
        store.putBoolean(KEY_EXPIRY_SHOWN, true);
    }

    /** True if the expiry screen was shown during the current session. */
    public synchronized boolean wasExpiryShown() {
        return store.getBoolean(KEY_EXPIRY_SHOWN, false);
    }

    /** How many sessions have been started on this install (informational). */
    public synchronized long sessionCount() {
        return store.getLong(KEY_SESSIONS, 0L);
    }
}
