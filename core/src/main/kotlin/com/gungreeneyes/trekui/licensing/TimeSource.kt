package com.gungreeneyes.trekui.licensing

/**
 * Monotonic time and boot identity used by [TrialClock].
 *
 * On device this is [SystemTimeSource] (`SystemClock.elapsedRealtime()` plus `Settings.Global.BOOT_COUNT`);
 * unit tests supply a fake so session expiry and reboots can be simulated without waiting.
 */
interface TimeSource {
    /** Milliseconds since boot, including deep sleep; never jumps when the user changes the clock. */
    fun elapsedRealtime(): Long

    /** A number that changes on every reboot, or [UNKNOWN_BOOT]. */
    fun bootCount(): Long

    companion object {
        /** Returned by [bootCount] when the platform cannot tell boots apart. */
        const val UNKNOWN_BOOT: Long = -1L
    }
}
