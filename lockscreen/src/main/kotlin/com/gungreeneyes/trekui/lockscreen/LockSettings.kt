package com.gungreeneyes.trekui.lockscreen

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.gungreeneyes.trekui.core.SuiteClient
import com.gungreeneyes.trekui.lockscreen.logic.EnableRule
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The Lock Screen app's own settings and the device state its rules read: the WAKE SCREEN and SHOW
 * SENSITIVE CONTENT switches, the cached answer of the launcher's SUITE › LOCK SCREEN switch, notification
 * access, and the system's lock-screen notification settings. The look-and-sound choice lives in :core's
 * `TrekPrefs` instead.
 *
 * The launcher's switch is read from its suite provider (a blocking cross-process query), so it is
 * refreshed in the background ([refreshLauncherFlag]: on listener connect, on every screen-off and when
 * settings open) and the screen-on decision uses the cached value; a wake never waits on IPC.
 */
internal object LockSettings {
    private const val FILE = "lockscreen"
    private const val KEY_WAKE = "wake_enabled"
    private const val KEY_SENSITIVE = "show_sensitive"
    private const val KEY_LAUNCHER_FLAG = "launcher_flag"
    private const val KEY_SETUP_SEEN = "setup_seen"
    private const val FLAG_UNKNOWN = -1

    /** Hidden Secure setting names (stable since Android 5) read for lock-screen privacy; may be unreadable. */
    const val SECURE_SHOW_ON_LOCK = "lock_screen_show_notifications"
    const val SECURE_ALLOW_PRIVATE = "lock_screen_allow_private_notifications"

    private val IO: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "lock-settings").apply { isDaemon = true }
    }
    private val MAIN = Handler(Looper.getMainLooper())

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The app's own WAKE SCREEN switch (default on). */
    fun wakeEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_WAKE, true)

    fun setWakeEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_WAKE, on).apply()

    /** SHOW SENSITIVE CONTENT: list private notifications in full on a secure lock screen (default off). */
    fun showSensitive(c: Context): Boolean = prefs(c).getBoolean(KEY_SENSITIVE, false)

    fun setShowSensitive(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_SENSITIVE, on).apply()

    /** True once the notification-access disclosure was shown automatically (it is not forced again). */
    fun setupSeen(c: Context): Boolean = prefs(c).getBoolean(KEY_SETUP_SEEN, false)

    fun markSetupSeen(c: Context) = prefs(c).edit().putBoolean(KEY_SETUP_SEEN, true).apply()

    /** The launcher's SUITE › LOCK SCREEN switch as last read: true / false, or null (no launcher answer). */
    fun launcherFlag(c: Context): Boolean? {
        val v = prefs(c).getInt(KEY_LAUNCHER_FLAG, FLAG_UNKNOWN)
        return if (v == FLAG_UNKNOWN) null else v == 1
    }

    /** Re-reads the launcher's switch in the background; [done] (may be null) runs on the main thread. */
    fun refreshLauncherFlag(context: Context, done: (() -> Unit)?) {
        val app = context.applicationContext
        IO.execute {
            val flag = SuiteClient.launcherLockscreenEnabled(app)
            val stored = when (flag) {
                null -> FLAG_UNKNOWN
                true -> 1
                false -> 0
            }
            prefs(app).edit().putInt(KEY_LAUNCHER_FLAG, stored).apply()
            if (done != null) MAIN.post { done() }
        }
    }

    /** True if the user granted this app notification access (the wake trigger lives in the listener). */
    fun hasNotificationAccess(c: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    /** True if "display over other apps" is granted (lets the listener start the wake screen on Android 10+). */
    fun canDrawOverlays(c: Context): Boolean = Settings.canDrawOverlays(c)

    /** The combined state of the launcher switch, the app switch and notification access. */
    fun state(c: Context): EnableRule.State =
        EnableRule.resolve(launcherFlag(c), wakeEnabled(c), hasNotificationAccess(c))

    /** True if the wake screen should show on the next screen-on. */
    fun enabled(c: Context): Boolean = state(c) == EnableRule.State.ACTIVE

    /** The system shows notifications on the lock screen at all (unreadable: assumed yes). */
    fun systemShowsOnLock(c: Context): Boolean = readSecure(c, SECURE_SHOW_ON_LOCK) ?: true

    /** The system allows sensitive content on the lock screen, or null when the setting can't be read. */
    fun systemAllowsPrivate(c: Context): Boolean? = readSecure(c, SECURE_ALLOW_PRIVATE)

    private fun readSecure(c: Context, name: String): Boolean? = try {
        Settings.Secure.getInt(c.contentResolver, name) != 0
    } catch (e: Settings.SettingNotFoundException) {
        null // not set on this device
    } catch (e: SecurityException) {
        null // not readable for apps on this Android version
    }
}
