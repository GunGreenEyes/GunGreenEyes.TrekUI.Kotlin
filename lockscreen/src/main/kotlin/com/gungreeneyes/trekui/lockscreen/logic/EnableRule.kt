package com.gungreeneyes.trekui.lockscreen.logic

/**
 * Decides whether the wake screen may show, from the three switches that govern it.
 *
 * - The launcher's CONFIG › SUITE › LOCK SCREEN switch, read through the suite provider: `true` / `false`,
 *   or `null` when the launcher is not installed, untrusted or too old to publish it. A launcher that says
 *   `false` always wins: the owner turned the companion off there.
 * - The app's own WAKE SCREEN switch (default on). It applies in every case, so the app also works on its
 *   own without the launcher.
 * - Notification access: the screen-on trigger lives in the notification listener, so without access
 *   nothing can show it.
 *
 * Pure Kotlin, unit tested on the JVM.
 */
object EnableRule {
    /** The outcome, with the reason the settings screen explains. */
    enum class State {
        /** Shows on every screen-on while the device is locked. */
        ACTIVE,

        /** The app's own switch is off. */
        OFF_IN_APP,

        /** The launcher's SUITE › LOCK SCREEN switch is off. */
        OFF_IN_LAUNCHER,

        /** Switched on but notification access is missing (setup not finished). */
        NEEDS_ACCESS,
    }

    /**
     * @param launcherFlag the launcher's switch, or null when the launcher can't answer
     * @param ownSwitch the app's own WAKE SCREEN switch
     * @param hasAccess notification access is granted
     */
    fun resolve(launcherFlag: Boolean?, ownSwitch: Boolean, hasAccess: Boolean): State = when {
        launcherFlag == false -> State.OFF_IN_LAUNCHER
        !ownSwitch -> State.OFF_IN_APP
        !hasAccess -> State.NEEDS_ACCESS
        else -> State.ACTIVE
    }

    /** True if the wake screen should show. */
    fun enabled(launcherFlag: Boolean?, ownSwitch: Boolean, hasAccess: Boolean): Boolean =
        resolve(launcherFlag, ownSwitch, hasAccess) == State.ACTIVE
}
