package com.gungreeneyes.trekui.lockscreen

import android.app.Activity
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gungreeneyes.trekui.licensing.Licensing
import com.gungreeneyes.trekui.lockscreen.logic.DecimalDate
import java.lang.ref.WeakReference
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The wake screen: shown over the keyguard when the screen turns on ([LockListener] starts it), or as a
 * PREVIEW from settings. It draws [WakeView] and wires its controls to the system.
 *
 * **Security.** This activity is a cosmetic layer, never a lock. It is shown with `setShowWhenLocked`
 * (window flags on Android 8.0), which leaves the real keyguard in place underneath; it never calls a
 * "dismiss without credentials" API. Unlocking is always [KeyguardManager.requestDismissKeyguard]: the
 * system asks for the PIN, pattern, password or biometric and only then lets anything behind it show. A
 * notification's intent, the purchase screen and the non-secure camera are only opened after that
 * succeeds. CAMERA uses [MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE] (the camera app's locked mode,
 * which cannot open the gallery), and TORCH only switches the flash. BACK simply reveals the system
 * keyguard.
 *
 * **Licensing.** The wake screen never blocks: when the trial session is over it only shows a small UNLOCK
 * APP chip, which opens the purchase screen after the device has been unlocked.
 */
class WakeActivity : Activity(), WakeView.Actions, LockHub.Observer {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var view: WakeView
    private var preview = false
    private var started = false
    private var receiver: BroadcastReceiver? = null
    private var batteryPercent = -1
    private var charging = false
    private var sessions: MediaSessionManager? = null
    private var controller: MediaController? = null
    private val watched = ArrayList<MediaController>()
    private var cameras: CameraManager? = null
    private var torchCamera: String? = null
    private var torchOn = false

    // ---- lifecycle --------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverKeyguard()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        view = WakeView(this, this)
        view.setOnApplyWindowInsetsListener { v, raw ->
            val bars = WindowInsetsCompat.toWindowInsetsCompat(raw, v)
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setSafeInsets(bars.left, bars.top, bars.right, bars.bottom)
            raw
        }
        setContentView(view)
        view.requestApplyInsets()
        cameras = getSystemService(CameraManager::class.java)
        torchCamera = findTorchCamera()
    }

    /** Shows the window over the keyguard and turns the screen on (attributes on 8.1+, flags on 8.0). */
    @Suppress("DEPRECATION")
    private fun showOverKeyguard() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        LockListener.refreshNow()
        update()
    }

    override fun onStart() {
        super.onStart()
        started = true
        registerReceivers()
        watchMedia()
        val cams = cameras
        if (cams != null && torchCamera != null) cams.registerTorchCallback(torchCallback, main)
        LockHub.observe(this)
        LockListener.refreshNow()
        LockTheme.refresh(this) {
            view.reloadLook()
            update()
        }
        update()
    }

    override fun onResume() {
        super.onResume()
        val km = getSystemService(KeyguardManager::class.java)
        if (!preview && km != null && !km.isKeyguardLocked) {
            finish() // already unlocked: never sit on top of an unlocked phone
            return
        }
        showing = WeakReference(this)
        view.reloadLook()
        update()
    }

    override fun onPause() {
        if (showing.get() === this) showing = WeakReference(null)
        super.onPause()
    }

    override fun onStop() {
        started = false
        LockHub.unobserve(this)
        unwatchMedia()
        val cams = cameras
        if (cams != null && torchCamera != null) cams.unregisterTorchCallback(torchCallback)
        receiver?.let {
            unregisterReceiver(it)
            receiver = null
        }
        super.onStop()
    }

    override fun onItemsChanged() {
        if (started) update()
    }

    // ---- the model --------------------------------------------------------------------------------

    /** Rebuilds the view model from the clock, battery, media, torch, licence and notification list. */
    private fun update() {
        val m = WakeView.Model()
        val now = Date()
        val h24 = DateFormat.is24HourFormat(this)
        val locale = Locale.getDefault()
        m.time = DateFormat.format(if (h24) "HH:mm" else "h:mm", now).toString()
        m.amPm = if (h24) "" else DateFormat.format("a", now).toString().uppercase(locale)
        m.date = DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), now).toString()
            .uppercase(locale)
        val cal = Calendar.getInstance()
        val second = cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
        m.decimalDate = DecimalDate.format(cal.get(Calendar.YEAR), cal.get(Calendar.DAY_OF_YEAR), second)
        m.alarm = nextAlarm(h24)
        m.batteryPercent = batteryPercent
        m.charging = charging

        controller?.let { mc ->
            val meta = mc.metadata
            val state = mc.playbackState
            m.hasMedia = meta != null || state != null
            if (meta != null) {
                m.mediaTitle = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                m.mediaArtist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            }
            if (m.mediaTitle.isEmpty()) m.mediaTitle = getString(R.string.wake_media_unknown)
            m.mediaPlaying = state?.state == PlaybackState.STATE_PLAYING
        }

        m.torchAvailable = torchCamera != null
        m.torchOn = torchOn
        m.trialChip = !Licensing.isUnlocked(this) && Licensing.trialRemainingMillis(this) <= 0
        m.listenerOn = LockListener.isConnected()
        m.items = LockHub.items
        view.setModel(m)
    }

    private fun nextAlarm(h24: Boolean): String {
        val next = getSystemService(AlarmManager::class.java)?.nextAlarmClock ?: return ""
        val t = next.triggerTime
        val pattern = if (t - System.currentTimeMillis() > 24L * 60 * 60 * 1000) {
            if (h24) "EEE HH:mm" else "EEE h:mm a"
        } else {
            if (h24) "HH:mm" else "h:mm a"
        }
        return DateFormat.format(pattern, t).toString().uppercase(Locale.getDefault())
    }

    // ---- receivers: battery and time --------------------------------------------------------------

    private fun registerReceivers() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent)
                update()
            }
        }
        receiver = r
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        ContextCompat.registerReceiver(this, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)?.let { readBattery(it) }
    }

    private fun readBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        batteryPercent = if (level < 0 || scale <= 0) -1 else (level * 100f / scale).roundToInt()
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }

    // ---- media (through the notification listener's access) ---------------------------------------

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = pickController()
        override fun onMetadataChanged(metadata: MediaMetadata?) = update()
        override fun onSessionDestroyed() = pickController()
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pickController() }

    private fun listenerComponent() = ComponentName(this, LockListener::class.java)

    private fun watchMedia() {
        sessions = getSystemService(MediaSessionManager::class.java)
        val s = sessions ?: return
        try {
            s.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent(), main)
        } catch (e: SecurityException) {
            sessions = null // no notification access: no media controls
            return
        }
        pickController()
    }

    private fun unwatchMedia() {
        sessions?.let {
            try {
                it.removeOnActiveSessionsChangedListener(sessionsChanged)
            } catch (ignored: RuntimeException) {
                // already gone
            }
        }
        watched.forEach { it.unregisterCallback(mediaCallback) }
        watched.clear()
        controller = null
    }

    /** The session to show: the one playing, else the most recent with something loaded. */
    private fun pickController() {
        val s = sessions ?: return
        val list: List<MediaController> = try {
            s.getActiveSessions(listenerComponent())
        } catch (e: SecurityException) {
            emptyList()
        }
        watched.forEach { it.unregisterCallback(mediaCallback) }
        watched.clear()
        var best: MediaController? = null
        for (c in list) {
            c.registerCallback(mediaCallback, main)
            watched.add(c)
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                best = c
                break
            }
            if (best == null && c.metadata != null) best = c
        }
        controller = best
        if (started) update()
    }

    override fun media(command: Int) {
        val c = controller ?: return
        val t = c.transportControls
        when {
            command < 0 -> t.skipToPrevious()
            command > 0 -> t.skipToNext()
            c.playbackState?.state == PlaybackState.STATE_PLAYING -> t.pause()
            else -> t.play()
        }
    }

    // ---- torch ------------------------------------------------------------------------------------

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCamera) {
                torchOn = enabled
                if (started) update()
            }
        }
    }

    /** The id of a camera with a flash unit (back camera preferred), or null. */
    private fun findTorchCamera(): String? {
        val cams = cameras ?: return null
        return try {
            var any: String? = null
            for (id in cams.cameraIdList) {
                val ch = cams.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
                if (ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
                if (any == null) any = id
            }
            any
        } catch (e: CameraAccessException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    override fun torch() {
        val cams = cameras ?: return
        val id = torchCamera ?: return
        try {
            cams.setTorchMode(id, !torchOn) // needs no CAMERA permission
        } catch (e: CameraAccessException) {
            Toast.makeText(this, R.string.wake_torch_busy, Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            Toast.makeText(this, R.string.wake_torch_busy, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- camera, unlock, notifications, purchase --------------------------------------------------

    override fun camera() {
        try {
            startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE))
        } catch (e: ActivityNotFoundException) {
            // no secure-camera mode on this device: only open the regular camera after a real unlock
            unlockThen {
                try {
                    startActivity(
                        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (none: ActivityNotFoundException) {
                    Toast.makeText(applicationContext, R.string.wake_no_camera, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun unlock() {
        unlockThen(null)
    }

    override fun openItem(item: LockItem) {
        val pi = item.contentIntent
        if (pi == null) {
            unlockThen(null)
            return
        }
        unlockThen {
            try {
                pi.send()
            } catch (ignored: PendingIntent.CanceledException) {
                // the app withdrew it
            }
        }
    }

    override fun purchase() {
        unlockThen { Licensing.openPurchaseScreen(applicationContext) }
    }

    /**
     * Hands over to the system keyguard; [after] runs only once the user has really unlocked (the system
     * verified the PIN / pattern / password / biometric). Cancelling leaves the wake screen up.
     */
    private fun unlockThen(after: (() -> Unit)?) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            after?.invoke()
            finish()
            return
        }
        km.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    after?.invoke()
                    finish()
                }
            },
        )
    }

    companion object {
        /** Boolean extra: started from settings to preview (the device is unlocked; UNLOCK just closes it). */
        internal const val EXTRA_PREVIEW = "preview"

        private var showing = WeakReference<WakeActivity>(null)

        /** Starts (or brings back) the wake screen from the listener. Never throws. */
        internal fun start(context: Context) {
            val i = Intent(context, WakeActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION,
            )
            try {
                context.startActivity(i)
            } catch (e: RuntimeException) {
                // blocked by background-start rules (no overlay permission on Android 10+); the keyguard shows as usual
            }
        }

        /** Opens the wake screen as a preview (from settings, device unlocked). */
        internal fun preview(context: Context) {
            context.startActivity(Intent(context, WakeActivity::class.java).putExtra(EXTRA_PREVIEW, true))
        }

        /** True while the wake screen is in front. */
        internal fun isShowing(): Boolean = showing.get() != null

        /** Closes the wake screen if it is up (the user unlocked some other way). */
        internal fun finishShowing() {
            val a = showing.get()
            if (a != null && !a.preview) a.finish()
        }
    }
}
