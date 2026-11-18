package com.gungreeneyes.trekui.lockscreen.logic;

/**
 * Lock-screen privacy for one notification: show it in full, show only a redacted line, or leave it out.
 *
 * <p>The rules follow Android's own lock screen, and are never less private than it:
 * <ol>
 *   <li>If the system's "notifications on lock screen" setting is off, nothing is listed.</li>
 *   <li>The effective visibility is the more private of the notification's own
 *       {@code Notification.visibility} and its channel's lock-screen visibility (when the channel sets
 *       one; {@code VISIBILITY_NO_OVERRIDE} means "no opinion").</li>
 *   <li>{@code VISIBILITY_SECRET}: never listed while the device is locked.</li>
 *   <li>{@code VISIBILITY_PUBLIC}: listed in full.</li>
 *   <li>{@code VISIBILITY_PRIVATE} (the default): in full only if the keyguard is not secure (no
 *       PIN/pattern/password, so anyone can swipe it away anyway) or BOTH the user's SHOW SENSITIVE
 *       CONTENT switch in this app is on AND the system does not forbid sensitive content on the lock
 *       screen. Otherwise redacted: app name, the app's public version of the notification if it supplied
 *       one, else "Contents hidden".</li>
 * </ol>
 * The system "show sensitive content" setting is a hidden Secure setting that some devices do not let
 * apps read: {@code systemAllowsPrivate == null} means "unknown", and then only the app's own switch
 * (default off) decides.
 *
 * <p>The int values are the platform constants (inlined at compile time), so this stays Android-free and
 * JVM-tested.
 */
public final class Redaction {
    /** What the wake screen may show of a notification. */
    public enum Show { FULL, REDACTED, HIDDEN }

    /** {@code Notification.VISIBILITY_PUBLIC}. */
    public static final int PUBLIC = 1;
    /** {@code Notification.VISIBILITY_PRIVATE}. */
    public static final int PRIVATE = 0;
    /** {@code Notification.VISIBILITY_SECRET}. */
    public static final int SECRET = -1;
    /** {@code NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE}: the channel sets no lock-screen visibility. */
    public static final int NO_OVERRIDE = -1000;

    private Redaction() {}

    /**
     * The more private of a notification's visibility and its channel's lock-screen visibility. Unknown
     * values are treated as PRIVATE.
     */
    public static int effectiveVisibility(int notificationVisibility, int channelVisibility) {
        int n = normalise(notificationVisibility);
        if (channelVisibility == NO_OVERRIDE) return n;
        return Math.min(n, normalise(channelVisibility));
    }

    /**
     * @param notificationVisibility {@code Notification.visibility}
     * @param channelVisibility      the channel's {@code getLockscreenVisibility()}, or {@link #NO_OVERRIDE}
     * @param keyguardSecure         the device has a PIN, pattern, password or biometric lock
     * @param systemShowsOnLock      the system shows notifications on the lock screen at all
     * @param systemAllowsPrivate    the system lets sensitive content show on the lock screen, or null when
     *                               the setting cannot be read
     * @param userAllowsPrivate      this app's SHOW SENSITIVE CONTENT switch
     */
    public static Show decide(int notificationVisibility, int channelVisibility, boolean keyguardSecure,
                              boolean systemShowsOnLock, Boolean systemAllowsPrivate, boolean userAllowsPrivate) {
        if (!systemShowsOnLock) return Show.HIDDEN;
        int v = effectiveVisibility(notificationVisibility, channelVisibility);
        if (v == SECRET) return Show.HIDDEN;
        if (v == PUBLIC || !keyguardSecure) return Show.FULL;
        boolean allowed = userAllowsPrivate && !Boolean.FALSE.equals(systemAllowsPrivate);
        return allowed ? Show.FULL : Show.REDACTED;
    }

    /** {@code NotificationManager.IMPORTANCE_LOW}: below this a channel is "minimised" and stays off the lock screen. */
    public static final int IMPORTANCE_LOW = 2;
    /** {@code NotificationManager.IMPORTANCE_UNSPECIFIED}: no channel information. */
    public static final int IMPORTANCE_UNSPECIFIED = -1000;

    /**
     * Whether a notification belongs in the wake screen's list at all (before privacy is applied):
     * not this app's own, not a group summary (its children are listed instead), not a media-session
     * notification (shown by the media controls), not from a minimised channel, and not suspended.
     */
    public static boolean listable(boolean ownPackage, boolean groupSummary, boolean mediaSession, int importance,
                                   boolean suspended) {
        if (ownPackage || groupSummary || mediaSession || suspended) return false;
        return importance == IMPORTANCE_UNSPECIFIED || importance >= IMPORTANCE_LOW;
    }

    private static int normalise(int v) {
        if (v == PUBLIC || v == SECRET) return v;
        return PRIVATE;
    }
}
