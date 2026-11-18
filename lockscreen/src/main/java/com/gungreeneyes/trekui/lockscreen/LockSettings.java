package com.gungreeneyes.trekui.lockscreen;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.NotificationManagerCompat;

import com.gungreeneyes.trekui.core.SuiteClient;
import com.gungreeneyes.trekui.lockscreen.logic.EnableRule;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Lock Screen app's own settings and the device state its rules read: the WAKE SCREEN and SHOW
 * SENSITIVE CONTENT switches, the cached answer of the launcher's SUITE › LOCK SCREEN switch, notification
 * access, and the system's lock-screen notification settings. The look-and-sound choice lives in :core's
 * {@code TrekPrefs} instead.
 *
 * <p>The launcher's switch is read from its suite provider (a blocking cross-process query), so it is
 * refreshed in the background ({@link #refreshLauncherFlag}: on listener connect, on every screen-off and
 * when settings open) and the screen-on decision uses the cached value; a wake never waits on IPC.
 */
public final class LockSettings {
    private static final String FILE = "lockscreen";
    private static final String KEY_WAKE = "wake_enabled";
    private static final String KEY_SENSITIVE = "show_sensitive";
    private static final String KEY_LAUNCHER_FLAG = "launcher_flag";
    private static final String KEY_SETUP_SEEN = "setup_seen";
    private static final int FLAG_UNKNOWN = -1;

    /** Hidden Secure setting names (stable since Android 5) read for lock-screen privacy; may be unreadable. */
    static final String SECURE_SHOW_ON_LOCK = "lock_screen_show_notifications";
    static final String SECURE_ALLOW_PRIVATE = "lock_screen_allow_private_notifications";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "lock-settings");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private LockSettings() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** The app's own WAKE SCREEN switch (default on). */
    public static boolean wakeEnabled(Context c) { return prefs(c).getBoolean(KEY_WAKE, true); }

    public static void setWakeEnabled(Context c, boolean on) { prefs(c).edit().putBoolean(KEY_WAKE, on).apply(); }

    /** SHOW SENSITIVE CONTENT: list private notifications in full on a secure lock screen (default off). */
    public static boolean showSensitive(Context c) { return prefs(c).getBoolean(KEY_SENSITIVE, false); }

    public static void setShowSensitive(Context c, boolean on) { prefs(c).edit().putBoolean(KEY_SENSITIVE, on).apply(); }

    /** True once the notification-access disclosure was shown automatically (it is not forced again). */
    public static boolean setupSeen(Context c) { return prefs(c).getBoolean(KEY_SETUP_SEEN, false); }

    public static void markSetupSeen(Context c) { prefs(c).edit().putBoolean(KEY_SETUP_SEEN, true).apply(); }

    /** The launcher's SUITE › LOCK SCREEN switch as last read: TRUE / FALSE, or null (no launcher answer). */
    public static Boolean launcherFlag(Context c) {
        int v = prefs(c).getInt(KEY_LAUNCHER_FLAG, FLAG_UNKNOWN);
        return v == FLAG_UNKNOWN ? null : v == 1;
    }

    /** Re-reads the launcher's switch in the background; {@code done} (may be null) runs on the main thread. */
    public static void refreshLauncherFlag(Context context, Runnable done) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            Boolean flag = SuiteClient.launcherLockscreenEnabled(app);
            prefs(app).edit().putInt(KEY_LAUNCHER_FLAG, flag == null ? FLAG_UNKNOWN : flag ? 1 : 0).apply();
            if (done != null) MAIN.post(done);
        });
    }

    /** True if the user granted this app notification access (the wake trigger lives in the listener). */
    public static boolean hasNotificationAccess(Context c) {
        return NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.getPackageName());
    }

    /** True if "display over other apps" is granted (lets the listener start the wake screen on Android 10+). */
    public static boolean canDrawOverlays(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** The combined state of the launcher switch, the app switch and notification access. */
    public static EnableRule.State state(Context c) {
        return EnableRule.resolve(launcherFlag(c), wakeEnabled(c), hasNotificationAccess(c));
    }

    /** True if the wake screen should show on the next screen-on. */
    public static boolean enabled(Context c) {
        return state(c) == EnableRule.State.ACTIVE;
    }

    /** The system shows notifications on the lock screen at all (unreadable: assumed yes). */
    static boolean systemShowsOnLock(Context c) {
        Boolean v = readSecure(c, SECURE_SHOW_ON_LOCK);
        return v == null || v;
    }

    /** The system allows sensitive content on the lock screen, or null when the setting can't be read. */
    static Boolean systemAllowsPrivate(Context c) {
        return readSecure(c, SECURE_ALLOW_PRIVATE);
    }

    private static Boolean readSecure(Context c, String name) {
        try {
            return Settings.Secure.getInt(c.getContentResolver(), name) != 0;
        } catch (Settings.SettingNotFoundException | SecurityException e) {
            return null; // not set on this device, or not readable for apps on this Android version
        }
    }
}
