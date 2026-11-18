package com.gungreeneyes.trekui

import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Bound by Android only if the user grants console "notification access". It authorises reading
 * media sessions (audio panel), supplies notifications to the console shade while it is open (with
 * inline reply and actions, see [NotificationActions]) and keeps the unread counts behind
 * the home-screen badges ([NotificationBadges]) while the home screen is in front.
 * Nothing is stored, logged or transmitted: the shade asks for the current list and draws it, and
 * the counts live in memory only.
 */
class MediaListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        changed()
    }

    override fun onListenerDisconnected() {
        instance = null
        NotificationBadges.clear()
        changed()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = changed()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = changed()

    override fun onInterruptionFilterChanged(filter: Int) = changed()

    /** A channel's or app's "notification dot" setting may have changed. */
    override fun onNotificationRankingUpdate(rankingMap: NotificationListenerService.RankingMap?) = changed()

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            NotificationBadges.clear()
        }
        super.onDestroy()
    }

    companion object {
        /** Coalesce bursts of notification events into one shade refresh. */
        private const val CHANGE_DEBOUNCE_MS = 120L

        /** The bound listener (a system-managed service, not an Activity), or null when unbound. */
        @Volatile
        private var instance: MediaListener? = null

        @Volatile
        private var onChange: Runnable? = null

        /** The resumed home screen's badge refresher; null while it is paused (no counting then). */
        @Volatile
        private var onBadges: Runnable? = null

        private val main = Handler(Looper.getMainLooper())

        private val dispatch = Runnable {
            val b = onBadges
            if (b != null) {
                val l = instance
                if (l != null) NotificationBadges.update(l, l.applicationContext) else NotificationBadges.clear()
                b.run()
            }
            onChange?.run()
        }

        /** The connected listener, or null when notification access is off or not yet bound. */
        @JvmStatic
        fun get(): MediaListener? = instance

        /** The open shade registers here; null when it closes (no work happens while it's closed). */
        @JvmStatic
        fun setOnChange(r: Runnable?) {
            onChange = r
        }

        /**
         * The home screen registers here while resumed (null on pause) to hear when badge counts
         * change; registering recounts straight away.
         */
        @JvmStatic
        fun setOnBadges(r: Runnable?) {
            onBadges = r
            if (r != null) {
                if (instance == null) NotificationBadges.clear()
                changed()
            }
        }

        private fun changed() {
            if (onChange == null && onBadges == null) return
            main.removeCallbacks(dispatch)
            main.postDelayed(dispatch, CHANGE_DEBOUNCE_MS)
        }
    }
}
