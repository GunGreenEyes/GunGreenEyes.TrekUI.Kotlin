package com.gungreeneyes.trekui.licensing;

/**
 * Monotonic time and boot identity used by {@link TrialClock}.
 *
 * <p>On device this is {@link SystemTimeSource} ({@code SystemClock.elapsedRealtime()} plus
 * {@code Settings.Global.BOOT_COUNT}); unit tests supply a fake so session expiry and reboots can
 * be simulated without waiting.
 */
interface TimeSource {
    /** Returned by {@link #bootCount()} when the platform cannot tell boots apart. */
    long UNKNOWN_BOOT = -1L;

    /** Milliseconds since boot, including deep sleep; never jumps when the user changes the clock. */
    long elapsedRealtime();

    /** A number that changes on every reboot, or {@link #UNKNOWN_BOOT}. */
    long bootCount();
}
