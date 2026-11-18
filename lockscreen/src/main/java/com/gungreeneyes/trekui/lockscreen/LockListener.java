package com.gungreeneyes.trekui.lockscreen;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Build;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import androidx.core.content.ContextCompat;

import com.gungreeneyes.trekui.core.SuiteContract;
import com.gungreeneyes.trekui.lockscreen.logic.Redaction;
import com.gungreeneyes.trekui.lockscreen.logic.WakeTrigger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The app's notification listener, and the wake screen's trigger.
 *
 * <p><b>Why the trigger lives here (Play policy).</b> The wake screen has to appear when the screen turns
 * on, but {@code ACTION_SCREEN_ON} cannot be received by a manifest receiver; it needs a receiver
 * registered at run time by a component that stays alive. This listener is that component: once the user
 * grants notification access (which the app needs anyway, for its notification list and media controls),
 * the system keeps it bound, and while bound it registers a dynamic SCREEN_ON / SCREEN_OFF / USER_PRESENT
 * receiver. So the app needs no foreground service (no persistent notification, no FGS type to justify),
 * no AccessibilityService and no Device Admin. See docs/LOCKSCREEN.md.
 *
 * <p>On a screen-on it asks {@link WakeTrigger} (enabled, keyguard showing, no call, debounce) and starts
 * {@link WakeActivity}. The notification list is rebuilt on every posted / removed / ranking change and
 * handed to {@link LockHub}, already filtered and redacted ({@link Redaction}); nothing is stored on disk
 * or sent anywhere.
 */
public final class LockListener extends NotificationListenerService {
    /** Most notifications kept for the wake screen. */
    static final int MAX_ITEMS = 40;

    private static final WakeTrigger TRIGGER = new WakeTrigger();
    private static volatile LockListener connected;

    private final Map<String, String> labels = new HashMap<>();
    private BroadcastReceiver screenReceiver;
    private ContentObserver launcherObserver;

    /** True while the system has the listener bound (notification access granted and active). */
    public static boolean isConnected() {
        return connected != null;
    }

    /** Rebuilds the list now (settings change, wake screen resume); no-op while not connected. */
    public static void refreshNow() {
        LockListener l = connected;
        if (l != null) l.rebuild();
    }

    /** Lets the next screen-on through the debounce (after a settings change or a preview). */
    static void resetTrigger() {
        TRIGGER.reset();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        connected = this;
        registerScreenReceiver();
        observeLauncherSwitch();
        LockSettings.refreshLauncherFlag(this, null);
        rebuild();
    }

    @Override
    public void onListenerDisconnected() {
        unregisterScreenReceiver();
        stopObservingLauncherSwitch();
        if (connected == this) connected = null;
        LockHub.publish(new ArrayList<>());
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        unregisterScreenReceiver();
        stopObservingLauncherSwitch();
        if (connected == this) connected = null;
        super.onDestroy();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        rebuild();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        rebuild();
    }

    @Override
    public void onNotificationRankingUpdate(RankingMap rankingMap) {
        rebuild();
    }

    // ---- the screen-on trigger ----------------------------------------------------------------

    private void registerScreenReceiver() {
        if (screenReceiver != null) return;
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent.getAction();
                if (Intent.ACTION_SCREEN_ON.equals(a)) {
                    onScreenOn();
                } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                    // refresh the launcher's switch while the screen is dark, so the next wake reads it cached
                    LockSettings.refreshLauncherFlag(LockListener.this, null);
                } else if (Intent.ACTION_USER_PRESENT.equals(a)) {
                    WakeActivity.finishShowing(); // unlocked some other way (fingerprint, face, smart lock)
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        // protected system broadcasts still reach a not-exported receiver; no other app can send to it
        ContextCompat.registerReceiver(this, screenReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void unregisterScreenReceiver() {
        if (screenReceiver == null) return;
        try {
            unregisterReceiver(screenReceiver);
        } catch (IllegalArgumentException ignored) {
            // already gone
        }
        screenReceiver = null;
    }

    /**
     * Re-reads the launcher's SUITE › LOCK SCREEN switch as soon as the launcher announces a change
     * ({@code SuiteProvider.notifyChanged}); the screen-off refresh covers launchers that don't announce.
     */
    private void observeLauncherSwitch() {
        if (launcherObserver != null) return;
        launcherObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                LockSettings.refreshLauncherFlag(LockListener.this, null);
            }
        };
        String launcher = SuiteContract.launcherPackage(getPackageName());
        try {
            getContentResolver().registerContentObserver(
                    Uri.parse(SuiteContract.uriString(launcher, SuiteContract.PATH_LOCKSCREEN)), false, launcherObserver);
        } catch (RuntimeException e) {
            launcherObserver = null; // launcher not installed or not ours: nothing to observe
        }
    }

    private void stopObservingLauncherSwitch() {
        if (launcherObserver == null) return;
        try {
            getContentResolver().unregisterContentObserver(launcherObserver);
        } catch (RuntimeException ignored) {
            // already gone
        }
        launcherObserver = null;
    }

    private void onScreenOn() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        boolean locked = km != null && km.isKeyguardLocked();
        if (TRIGGER.onScreenOn(SystemClock.elapsedRealtime(), LockSettings.enabled(this), locked, inCall(),
                WakeActivity.isShowing())) {
            rebuild();
            WakeActivity.start(this);
        }
    }

    /** A call is ringing or active (phone or VoIP): the call screen owns the display. No permission needed. */
    private boolean inCall() {
        AudioManager am = getSystemService(AudioManager.class);
        if (am == null) return false;
        int mode = am.getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                || mode == AudioManager.MODE_RINGTONE;
    }

    // ---- the notification list ----------------------------------------------------------------

    /** Rebuilds the filtered, redacted list from the system and publishes it. */
    void rebuild() {
        StatusBarNotification[] active;
        RankingMap ranking;
        try {
            active = getActiveNotifications();
            ranking = getCurrentRanking();
        } catch (RuntimeException e) {
            return; // not bound (yet / any more)
        }
        if (active == null) return;
        KeyguardManager km = getSystemService(KeyguardManager.class);
        boolean secure = km == null || km.isDeviceSecure();
        boolean showOnLock = LockSettings.systemShowsOnLock(this);
        Boolean systemPrivate = LockSettings.systemAllowsPrivate(this);
        boolean userPrivate = LockSettings.showSensitive(this);
        String own = getPackageName();

        List<LockItem> out = new ArrayList<>();
        Ranking r = new Ranking();
        for (StatusBarNotification sbn : active) {
            Notification n = sbn.getNotification();
            if (n == null) continue;
            int importance = Redaction.IMPORTANCE_UNSPECIFIED;
            int channelVisibility = Redaction.NO_OVERRIDE;
            boolean suspended = false;
            if (ranking != null && ranking.getRanking(sbn.getKey(), r)) {
                importance = r.getImportance();
                NotificationChannel ch = r.getChannel();
                if (ch != null) channelVisibility = ch.getLockscreenVisibility();
                if (Build.VERSION.SDK_INT >= 28) suspended = r.isSuspended();
            }
            boolean media = n.extras != null && n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION);
            boolean summary = (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
            if (!Redaction.listable(own.equals(sbn.getPackageName()), summary, media, importance, suspended)) continue;

            Redaction.Show show = Redaction.decide(n.visibility, channelVisibility, secure, showOnLock,
                    systemPrivate, userPrivate);
            if (show == Redaction.Show.HIDDEN) continue;
            String title;
            String text;
            if (show == Redaction.Show.FULL) {
                title = str(n.extras, Notification.EXTRA_TITLE);
                text = firstNonEmpty(str(n.extras, Notification.EXTRA_BIG_TEXT), str(n.extras, Notification.EXTRA_TEXT));
            } else {
                Notification pub = n.publicVersion; // the app's own lock-screen-safe version, if it made one
                title = pub == null ? "" : str(pub.extras, Notification.EXTRA_TITLE);
                text = pub == null ? "" : str(pub.extras, Notification.EXTRA_TEXT);
            }
            out.add(new LockItem(sbn.getKey(), sbn.getPackageName(), label(sbn.getPackageName()), title, text,
                    sbn.getPostTime(), n.color, show, n.contentIntent));
        }
        out.sort((a, b) -> Long.compare(b.postTime, a.postTime));
        if (out.size() > MAX_ITEMS) out = new ArrayList<>(out.subList(0, MAX_ITEMS));
        LockHub.publish(out);
    }

    /** The posting app's display name (cached), or a readable form of its package name. */
    private String label(String pkg) {
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String name;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            name = String.valueOf(pm.getApplicationLabel(info));
        } catch (PackageManager.NameNotFoundException e) {
            int dot = pkg.lastIndexOf('.');
            name = (dot >= 0 ? pkg.substring(dot + 1) : pkg).toUpperCase(Locale.ROOT);
        }
        labels.put(pkg, name);
        return name;
    }

    private static String str(Bundle extras, String key) {
        if (extras == null) return "";
        CharSequence cs = extras.getCharSequence(key);
        return cs == null ? "" : cs.toString().trim();
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }
}
