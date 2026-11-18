package com.gungreeneyes.trekui.lockscreen

import android.app.KeyguardManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.Ranking
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.gungreeneyes.trekui.core.SuiteContract
import com.gungreeneyes.trekui.lockscreen.logic.Redaction
import com.gungreeneyes.trekui.lockscreen.logic.WakeTrigger
import java.util.Locale

/**
 * The app's notification listener, and the wake screen's trigger.
 *
 * **Why the trigger lives here (Play policy).** The wake screen has to appear when the screen turns on,
 * but `ACTION_SCREEN_ON` cannot be received by a manifest receiver; it needs a receiver registered at run
 * time by a component that stays alive. This listener is that component: once the user grants notification
 * access (which the app needs anyway, for its notification list and media controls), the system keeps it
 * bound, and while bound it registers a dynamic SCREEN_ON / SCREEN_OFF / USER_PRESENT receiver. So the app
 * needs no foreground service (no persistent notification, no FGS type to justify), no AccessibilityService
 * and no Device Admin. See docs/LOCKSCREEN.md.
 *
 * On a screen-on it asks [WakeTrigger] (enabled, keyguard showing, no call, debounce) and starts
 * [WakeActivity]. The notification list is rebuilt on every posted / removed / ranking change and handed
 * to [LockHub], already filtered and redacted ([Redaction]); nothing is stored on disk or sent anywhere.
 */
class LockListener : NotificationListenerService() {
    private val labels = HashMap<String, String>()
    private var screenReceiver: BroadcastReceiver? = null
    private var launcherObserver: ContentObserver? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = this
        registerScreenReceiver()
        observeLauncherSwitch()
        LockSettings.refreshLauncherFlag(this, null)
        rebuild()
    }

    override fun onListenerDisconnected() {
        unregisterScreenReceiver()
        stopObservingLauncherSwitch()
        if (connected === this) connected = null
        LockHub.publish(emptyList())
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        unregisterScreenReceiver()
        stopObservingLauncherSwitch()
        if (connected === this) connected = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        rebuild()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        rebuild()
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        rebuild()
    }

    // ---- the screen-on trigger --------------------------------------------------------------------

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                    // refresh the launcher's switch while the screen is dark, so the next wake reads it cached
                    Intent.ACTION_SCREEN_OFF -> LockSettings.refreshLauncherFlag(this@LockListener, null)
                    // unlocked some other way (fingerprint, face, smart lock)
                    Intent.ACTION_USER_PRESENT -> WakeActivity.finishShowing()
                }
            }
        }
        screenReceiver = receiver
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // protected system broadcasts still reach a not-exported receiver; no other app can send to it
        ContextCompat.registerReceiver(this, receiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver ?: return
        try {
            unregisterReceiver(receiver)
        } catch (ignored: IllegalArgumentException) {
            // already gone
        }
        screenReceiver = null
    }

    /**
     * Re-reads the launcher's SUITE › LOCK SCREEN switch as soon as the launcher announces a change
     * (`SuiteProvider.notifyChanged`); the screen-off refresh covers launchers that don't announce.
     */
    private fun observeLauncherSwitch() {
        if (launcherObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                LockSettings.refreshLauncherFlag(this@LockListener, null)
            }
        }
        launcherObserver = observer
        val launcher = SuiteContract.launcherPackage(packageName)
        try {
            contentResolver.registerContentObserver(
                Uri.parse(SuiteContract.uriString(launcher, SuiteContract.PATH_LOCKSCREEN)), false, observer,
            )
        } catch (e: RuntimeException) {
            launcherObserver = null // launcher not installed or not ours: nothing to observe
        }
    }

    private fun stopObservingLauncherSwitch() {
        val observer = launcherObserver ?: return
        try {
            contentResolver.unregisterContentObserver(observer)
        } catch (ignored: RuntimeException) {
            // already gone
        }
        launcherObserver = null
    }

    private fun onScreenOn() {
        val km = getSystemService(KeyguardManager::class.java)
        val locked = km != null && km.isKeyguardLocked
        if (TRIGGER.onScreenOn(
                SystemClock.elapsedRealtime(), LockSettings.enabled(this), locked, inCall(), WakeActivity.isShowing(),
            )
        ) {
            rebuild()
            WakeActivity.start(this)
        }
    }

    /** A call is ringing or active (phone or VoIP): the call screen owns the display. No permission needed. */
    private fun inCall(): Boolean {
        val am = getSystemService(AudioManager::class.java) ?: return false
        return when (am.mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE -> true
            else -> false
        }
    }

    // ---- the notification list --------------------------------------------------------------------

    /** Rebuilds the filtered, redacted list from the system and publishes it. */
    internal fun rebuild() {
        val active: Array<StatusBarNotification>?
        val ranking: RankingMap?
        try {
            active = activeNotifications
            ranking = currentRanking
        } catch (e: RuntimeException) {
            return // not bound (yet / any more)
        }
        if (active == null) return
        val km = getSystemService(KeyguardManager::class.java)
        val secure = km == null || km.isDeviceSecure
        val showOnLock = LockSettings.systemShowsOnLock(this)
        val systemPrivate = LockSettings.systemAllowsPrivate(this)
        val userPrivate = LockSettings.showSensitive(this)
        val own = packageName

        val out = ArrayList<LockItem>()
        val r = Ranking()
        for (sbn in active) {
            val n = sbn.notification ?: continue
            var importance = Redaction.IMPORTANCE_UNSPECIFIED
            var channelVisibility = Redaction.NO_OVERRIDE
            var suspended = false
            if (ranking != null && ranking.getRanking(sbn.key, r)) {
                importance = r.importance
                r.channel?.let { channelVisibility = it.lockscreenVisibility }
                if (Build.VERSION.SDK_INT >= 28) suspended = r.isSuspended
            }
            val media = n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
            val summary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            if (!Redaction.listable(own == sbn.packageName, summary, media, importance, suspended)) continue

            val show = Redaction.decide(n.visibility, channelVisibility, secure, showOnLock, systemPrivate, userPrivate)
            if (show == Redaction.Show.HIDDEN) continue
            val title: String
            val text: String
            if (show == Redaction.Show.FULL) {
                title = str(n.extras, Notification.EXTRA_TITLE)
                text = str(n.extras, Notification.EXTRA_BIG_TEXT).ifEmpty { str(n.extras, Notification.EXTRA_TEXT) }
            } else {
                // the app's own lock-screen-safe version, if it made one; never the private extras
                val pub: Notification? = n.publicVersion
                title = if (pub == null) "" else str(pub.extras, Notification.EXTRA_TITLE)
                text = if (pub == null) "" else str(pub.extras, Notification.EXTRA_TEXT)
            }
            out.add(
                LockItem(
                    sbn.key, sbn.packageName, label(sbn.packageName), title, text, sbn.postTime, n.color, show,
                    n.contentIntent,
                ),
            )
        }
        out.sortByDescending { it.postTime }
        LockHub.publish(if (out.size > MAX_ITEMS) out.subList(0, MAX_ITEMS) else out)
    }

    /** The posting app's display name (cached), or a readable form of its package name. */
    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        try {
            val pm = packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg.substringAfterLast('.').uppercase(Locale.ROOT)
        }
    }

    companion object {
        /** Most notifications kept for the wake screen. */
        internal const val MAX_ITEMS = 40

        private val TRIGGER = WakeTrigger()

        @Volatile
        private var connected: LockListener? = null

        /** True while the system has the listener bound (notification access granted and active). */
        fun isConnected(): Boolean = connected != null

        /** Rebuilds the list now (settings change, wake screen resume); no-op while not connected. */
        fun refreshNow() {
            connected?.rebuild()
        }

        /** Lets the next screen-on through the debounce (after a settings change or a preview). */
        internal fun resetTrigger() = TRIGGER.reset()

        private fun str(extras: Bundle?, key: String): String =
            extras?.getCharSequence(key)?.toString()?.trim().orEmpty()
    }
}
