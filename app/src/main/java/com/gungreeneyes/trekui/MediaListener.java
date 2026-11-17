package com.gungreeneyes.trekui;

import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Bound by Android only if the user grants console "notification access". It authorises reading
 * media sessions (audio panel), supplies notifications to the console shade while it is open (with
 * inline reply and actions, see {@link NotificationActions}) and keeps the unread counts behind
 * the home-screen badges ({@link NotificationBadges}) while the home screen is in front.
 * Nothing is stored, logged or transmitted: the shade asks for the current list and draws it, and
 * the counts live in memory only.
 */
public class MediaListener extends NotificationListenerService {
    /** Coalesce bursts of notification events into one shade refresh. */
    private static final long CHANGE_DEBOUNCE_MS = 120;

    /** The bound listener (a system-managed service, not an Activity), or null when unbound. */
    private static volatile MediaListener instance;
    private static volatile Runnable onChange;
    /** The resumed home screen's badge refresher; null while it is paused (no counting then). */
    private static volatile Runnable onBadges;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable dispatch = new Runnable() {
        @Override public void run() {
            Runnable b = onBadges;
            if (b != null) {
                MediaListener l = instance;
                if (l != null) NotificationBadges.update(l, l.getApplicationContext());
                else NotificationBadges.clear();
                b.run();
            }
            Runnable r = onChange;
            if (r != null) r.run();
        }
    };

    /** The connected listener, or null when notification access is off or not yet bound. */
    static MediaListener get() { return instance; }

    /** The open shade registers here; null when it closes (no work happens while it's closed). */
    static void setOnChange(Runnable r) { onChange = r; }

    /**
     * The home screen registers here while resumed (null on pause) to hear when badge counts
     * change; registering recounts straight away.
     */
    static void setOnBadges(Runnable r) {
        onBadges = r;
        if (r != null) {
            if (instance == null) NotificationBadges.clear();
            changed();
        }
    }

    private static void changed() {
        if (onChange == null && onBadges == null) return;
        main.removeCallbacks(dispatch);
        main.postDelayed(dispatch, CHANGE_DEBOUNCE_MS);
    }

    @Override public void onListenerConnected() { instance = this; changed(); }
    @Override public void onListenerDisconnected() { instance = null; NotificationBadges.clear(); changed(); }
    @Override public void onNotificationPosted(StatusBarNotification sbn) { changed(); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { changed(); }
    @Override public void onInterruptionFilterChanged(int filter) { changed(); }
    /** A channel's or app's "notification dot" setting may have changed. */
    @Override public void onNotificationRankingUpdate(RankingMap rankingMap) { changed(); }

    @Override
    public void onDestroy() {
        if (instance == this) {
            instance = null;
            NotificationBadges.clear();
        }
        super.onDestroy();
    }
}
