package com.gungreeneyes.trekui.lockscreen.logic;

/**
 * Decides whether the wake screen may show, from the three switches that govern it.
 *
 * <ul>
 *   <li>The launcher's CONFIG › SUITE › LOCK SCREEN switch, read through the suite provider:
 *       TRUE / FALSE, or {@code null} when the launcher is not installed, untrusted or too old to publish
 *       it. A launcher that says FALSE always wins: the owner turned the companion off there.</li>
 *   <li>The app's own WAKE SCREEN switch (default on). It applies in every case, so the app also works
 *       on its own without the launcher.</li>
 *   <li>Notification access: the screen-on trigger lives in the notification listener, so without
 *       access nothing can show it.</li>
 * </ul>
 *
 * Pure Java, unit tested on the JVM.
 */
public final class EnableRule {
    /** The outcome, with the reason the settings screen explains. */
    public enum State {
        /** Shows on every screen-on while the device is locked. */
        ACTIVE,
        /** The app's own switch is off. */
        OFF_IN_APP,
        /** The launcher's SUITE › LOCK SCREEN switch is off. */
        OFF_IN_LAUNCHER,
        /** Switched on but notification access is missing (setup not finished). */
        NEEDS_ACCESS
    }

    private EnableRule() {}

    /**
     * @param launcherFlag the launcher's switch, or null when the launcher can't answer
     * @param ownSwitch    the app's own WAKE SCREEN switch
     * @param hasAccess    notification access is granted
     */
    public static State resolve(Boolean launcherFlag, boolean ownSwitch, boolean hasAccess) {
        if (Boolean.FALSE.equals(launcherFlag)) return State.OFF_IN_LAUNCHER;
        if (!ownSwitch) return State.OFF_IN_APP;
        if (!hasAccess) return State.NEEDS_ACCESS;
        return State.ACTIVE;
    }

    /** True if the wake screen should show. */
    public static boolean enabled(Boolean launcherFlag, boolean ownSwitch, boolean hasAccess) {
        return resolve(launcherFlag, ownSwitch, hasAccess) == State.ACTIVE;
    }
}
