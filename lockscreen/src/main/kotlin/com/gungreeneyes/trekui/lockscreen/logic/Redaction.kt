package com.gungreeneyes.trekui.lockscreen.logic

import kotlin.math.min

/**
 * Lock-screen privacy for one notification: show it in full, show only a redacted line, or leave it out.
 *
 * The rules follow Android's own lock screen, and are never less private than it:
 * 1. If the system's "notifications on lock screen" setting is off, nothing is listed.
 * 2. The effective visibility is the more private of the notification's own `Notification.visibility` and
 *    its channel's lock-screen visibility (when the channel sets one; `VISIBILITY_NO_OVERRIDE` means
 *    "no opinion").
 * 3. `VISIBILITY_SECRET`: never listed while the device is locked.
 * 4. `VISIBILITY_PUBLIC`: listed in full.
 * 5. `VISIBILITY_PRIVATE` (the default): in full only if the keyguard is not secure (no PIN/pattern/password,
 *    so anyone can swipe it away anyway) or BOTH the user's SHOW SENSITIVE CONTENT switch in this app is on
 *    AND the system does not forbid sensitive content on the lock screen. Otherwise redacted: app name, the
 *    app's public version of the notification if it supplied one, else "Contents hidden".
 *
 * The system "show sensitive content" setting is a hidden Secure setting that some devices do not let apps
 * read: `systemAllowsPrivate == null` means "unknown", and then only the app's own switch (default off)
 * decides.
 *
 * The int values are the platform constants (inlined at compile time), so this stays Android-free and
 * JVM-tested.
 */
object Redaction {
    /** What the wake screen may show of a notification. */
    enum class Show { FULL, REDACTED, HIDDEN }

    /** `Notification.VISIBILITY_PUBLIC`. */
    const val PUBLIC = 1

    /** `Notification.VISIBILITY_PRIVATE`. */
    const val PRIVATE = 0

    /** `Notification.VISIBILITY_SECRET`. */
    const val SECRET = -1

    /** `NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE`: the channel sets no lock-screen visibility. */
    const val NO_OVERRIDE = -1000

    /** `NotificationManager.IMPORTANCE_LOW`: below this a channel is "minimised" and stays off the lock screen. */
    const val IMPORTANCE_LOW = 2

    /** `NotificationManager.IMPORTANCE_UNSPECIFIED`: no channel information. */
    const val IMPORTANCE_UNSPECIFIED = -1000

    /**
     * The more private of a notification's visibility and its channel's lock-screen visibility. Unknown
     * values are treated as PRIVATE.
     */
    fun effectiveVisibility(notificationVisibility: Int, channelVisibility: Int): Int {
        val n = normalise(notificationVisibility)
        if (channelVisibility == NO_OVERRIDE) return n
        return min(n, normalise(channelVisibility))
    }

    /**
     * @param notificationVisibility `Notification.visibility`
     * @param channelVisibility the channel's `getLockscreenVisibility()`, or [NO_OVERRIDE]
     * @param keyguardSecure the device has a PIN, pattern, password or biometric lock
     * @param systemShowsOnLock the system shows notifications on the lock screen at all
     * @param systemAllowsPrivate the system lets sensitive content show on the lock screen, or null when the
     *   setting cannot be read
     * @param userAllowsPrivate this app's SHOW SENSITIVE CONTENT switch
     */
    fun decide(
        notificationVisibility: Int,
        channelVisibility: Int,
        keyguardSecure: Boolean,
        systemShowsOnLock: Boolean,
        systemAllowsPrivate: Boolean?,
        userAllowsPrivate: Boolean,
    ): Show {
        if (!systemShowsOnLock) return Show.HIDDEN
        val v = effectiveVisibility(notificationVisibility, channelVisibility)
        if (v == SECRET) return Show.HIDDEN
        if (v == PUBLIC || !keyguardSecure) return Show.FULL
        val allowed = userAllowsPrivate && systemAllowsPrivate != false
        return if (allowed) Show.FULL else Show.REDACTED
    }

    /**
     * Whether a notification belongs in the wake screen's list at all (before privacy is applied): not this
     * app's own, not a group summary (its children are listed instead), not a media-session notification
     * (shown by the media controls), not from a minimised channel, and not suspended.
     */
    fun listable(
        ownPackage: Boolean,
        groupSummary: Boolean,
        mediaSession: Boolean,
        importance: Int,
        suspended: Boolean,
    ): Boolean {
        if (ownPackage || groupSummary || mediaSession || suspended) return false
        return importance == IMPORTANCE_UNSPECIFIED || importance >= IMPORTANCE_LOW
    }

    private fun normalise(v: Int): Int = if (v == PUBLIC || v == SECRET) v else PRIVATE
}
