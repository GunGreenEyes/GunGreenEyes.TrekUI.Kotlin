package com.gungreeneyes.trekui.lockscreen.logic;

/**
 * Decides, on each "screen turned on" broadcast, whether to bring up the wake screen.
 *
 * <p>Rules: the wake screen must be enabled ({@link EnableRule}), the device must be showing its keyguard
 * (an unlocked phone is never covered), no call may be in progress (the call screen owns the display),
 * the wake screen must not already be in front, and screen-ons closer together than
 * {@link #MIN_INTERVAL_MS} are folded into one (power-key bounces, proximity sensor flicker during a
 * pocket wake, the system turning the screen on twice for one notification).
 *
 * <p>Stateful but Android-free: the caller passes a monotonic clock ({@code SystemClock.elapsedRealtime}),
 * so the debounce is unit tested on the JVM.
 */
public final class WakeTrigger {
    /** Screen-ons within this many milliseconds of the last accepted one are ignored. */
    public static final long MIN_INTERVAL_MS = 1500L;

    private long lastAcceptedAt = Long.MIN_VALUE;

    /**
     * @param nowMs          monotonic time of this screen-on
     * @param enabled        {@link EnableRule#enabled}
     * @param keyguardLocked the keyguard is showing
     * @param inCall         a phone or VoIP call is active
     * @param wakeVisible    the wake screen is already in front
     * @return true if the wake screen should be started now (the attempt is then recorded)
     */
    public synchronized boolean onScreenOn(long nowMs, boolean enabled, boolean keyguardLocked, boolean inCall,
                                           boolean wakeVisible) {
        if (!enabled || !keyguardLocked || inCall || wakeVisible) return false;
        if (lastAcceptedAt != Long.MIN_VALUE && nowMs - lastAcceptedAt >= 0 && nowMs - lastAcceptedAt < MIN_INTERVAL_MS) {
            return false;
        }
        lastAcceptedAt = nowMs;
        return true;
    }

    /** Forgets the last accepted screen-on (a PREVIEW or a settings change: the next wake always counts). */
    public synchronized void reset() {
        lastAcceptedAt = Long.MIN_VALUE;
    }
}
