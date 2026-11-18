package com.gungreeneyes.trekui

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * RED ALERT mode: an assignable launcher function and a Quick Settings tile. While on alert the home
 * screen of every interface shows a pulsing alarm frame ([RedAlertOverlay]) with MUTE and
 * STAND DOWN controls, and the RED ALERT sound event ([Sfx.E_RED]) loops. It stands down by
 * itself after the timeout chosen in CONFIG.
 *
 * It never gets in the way: the frame passes every touch through except its two buttons, the
 * klaxon stays silent in Silent/Vibrate, during calls, with Do Not Disturb on, with interface sounds
 * off, or when no RED ALERT sound is available (production builds ship no recordings).
 *
 * The state lives in [Prefs] (`redAlertSince`, `redAlertUntil`,
 * `redAlertMuted`) so the home screen, CONFIG and the tile agree, and the decisions are in
 * [RedAlertLogic]. Everything runs on the main thread (tile clicks, receivers and views all do).
 *
 * [Sfx.onRelease] is wired to [soundReleased] in this object's initializer, which runs before any
 * member is used, so it is in place whenever the klaxon can be playing (only [syncSound] starts it).
 */
internal object RedAlert {

    /** Told whenever the alert engages, mutes, unmutes or stands down. */
    fun interface Listener {
        fun onRedAlertChanged()
    }

    /** Broadcast action and PendingIntent request code of the stand-down timer. */
    const val ACTION_STAND_DOWN = "com.gungreeneyes.trekui.RED_ALERT_STAND_DOWN"
    private const val REQ_TIMER = 48

    /** Retry delay while the klaxon clip is still loading into the SoundPool. */
    private const val SOUND_RETRY_MS = 250L

    /** Give up waiting for the clip after this many retries (it is then silent). */
    private const val SOUND_RETRIES = 12

    const val KEY_SINCE = "redAlertSince"
    const val KEY_UNTIL = "redAlertUntil"
    const val KEY_MUTED = "redAlertMuted"
    const val KEY_TIMEOUT = "redAlertTimeout"
    const val KEY_SOUND = "redAlertSound"

    private val listeners: MutableSet<Listener> = CopyOnWriteArraySet()
    private val handler = Handler(Looper.getMainLooper())

    /** SoundPool stream of the looping klaxon, 0 when silent. */
    private var stream = 0
    private var soundRetries = 0

    /** Application context for the timers (never an Activity). */
    private var app: Context? = null

    private val timeout = Runnable { app?.let { check(it) } }
    private val retrySound = Runnable { app?.let { syncSound(it) } }

    init {
        Sfx.onRelease = Runnable { soundReleased() } // the klaxon stream only exists once this object is initialised
    }

    // ---- state ----------------------------------------------------------------------------------

    @JvmStatic
    fun state(c: Context): RedAlertLogic.State {
        val p = Prefs.get(c)
        return RedAlertLogic.State(p.l(KEY_SINCE, 0L), p.l(KEY_UNTIL, 0L), p.b(KEY_MUTED, false))
    }

    @JvmStatic
    fun active(c: Context): Boolean = state(c).active(System.currentTimeMillis())

    @JvmStatic
    fun muted(c: Context): Boolean = state(c).phase(System.currentTimeMillis()) == RedAlertLogic.MUTED

    @JvmStatic
    fun remainingMs(c: Context): Long = state(c).remainingMs(System.currentTimeMillis())

    /** Auto-timeout in seconds (CONFIG › RED ALERT LASTS). */
    @JvmStatic
    fun timeoutSeconds(p: Prefs): Int = RedAlertLogic.clampTimeout(p.i(KEY_TIMEOUT, RedAlertLogic.DEFAULT_TIMEOUT_SECONDS))

    /** CONFIG › RED ALERT SOUND: loop the klaxon at all. */
    @JvmStatic
    fun soundOn(p: Prefs): Boolean = p.b(KEY_SOUND, true)

    @JvmStatic
    fun addListener(l: Listener) {
        listeners.add(l)
    }

    @JvmStatic
    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // ---- transitions ------------------------------------------------------------------------------

    /** Engages when standing down, stands down when on alert (function tiles and the QS tile). */
    @JvmStatic
    fun toggle(c: Context) {
        if (active(c)) standDown(c) else engage(c)
    }

    /** Goes to red alert (or extends a running one) for the configured timeout. */
    @JvmStatic
    fun engage(c: Context) {
        val now = System.currentTimeMillis()
        save(c, state(c).engage(now, timeoutSeconds(Prefs.get(c))))
    }

    /** Ends the alert: frame off, klaxon off, timer cancelled. */
    @JvmStatic
    fun standDown(c: Context) {
        save(c, RedAlertLogic.State.NONE)
    }

    /** MUTE / UNMUTE: silences the klaxon but keeps the frame until stand down or timeout. */
    @JvmStatic
    fun toggleMute(c: Context) {
        save(c, state(c).toggleMute(System.currentTimeMillis()))
    }

    /**
     * Brings everything in line with the stored state: stands down an expired alert, and starts or
     * stops the klaxon when the ringer mode or Do Not Disturb changed. Called by the overlay about once
     * a second, by the timers, and when the home screen or a tile wakes up.
     */
    @JvmStatic
    fun check(c: Context) {
        val s = state(c)
        if (s.expired(System.currentTimeMillis())) {
            standDown(c)
            return
        }
        syncSound(c)
    }

    /** Stores a new state, re-arms timers and sound, and tells the views and the QS tile. */
    private fun save(c: Context, s: RedAlertLogic.State) {
        app = c.applicationContext
        val p = Prefs.get(c)
        p.put(KEY_SINCE, s.since)
        p.put(KEY_UNTIL, s.until)
        p.put(KEY_MUTED, s.muted)
        handler.removeCallbacks(timeout)
        val now = System.currentTimeMillis()
        if (s.active(now)) {
            handler.postDelayed(timeout, s.until - now + 50L)
            schedule(c, s.until)
        } else {
            cancel(c)
        }
        syncSound(c)
        for (l in listeners) l.onRedAlertChanged()
        QsTiles.refresh(c)
    }

    // ---- sound ------------------------------------------------------------------------------------

    /** False with Do Not Disturb on (any interruption filter other than "all"). */
    @JvmStatic
    fun dndOff(c: Context): Boolean {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager? ?: return true
        val filter = nm.currentInterruptionFilter
        return filter == NotificationManager.INTERRUPTION_FILTER_ALL || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** Starts or stops the looping klaxon to match the state and the phone's sound settings. */
    private fun syncSound(c: Context) {
        handler.removeCallbacks(retrySound)
        val appContext = c.applicationContext
        app = appContext
        val want = RedAlertLogic.soundAllowed(
            state(c), System.currentTimeMillis(),
            soundOn(Prefs.get(c)), Sfx.audioAllowed(c), dndOff(c),
        )
        if (!want) {
            stopSound()
            soundRetries = 0
            return
        }
        if (stream > 0) return // already looping
        val started = Sfx.loopEvent(appContext, Sfx.E_RED)
        if (started > 0) {
            stream = started
            soundRetries = 0
        } else if (started < 0 && soundRetries++ < SOUND_RETRIES) {
            handler.postDelayed(retrySound, SOUND_RETRY_MS) // clip still loading
        }
    }

    private fun stopSound() {
        if (stream > 0) Sfx.stopLoop(stream)
        stream = 0
    }

    /** Forgets the klaxon stream after Sfx released its SoundPool (the stream died with it). */
    @JvmStatic
    fun soundReleased() {
        stream = 0
    }

    // ---- timer ------------------------------------------------------------------------------------

    private fun pending(c: Context): PendingIntent {
        val i = Intent(c, Receiver::class.java).setAction(ACTION_STAND_DOWN)
        return PendingIntent.getBroadcast(c, REQ_TIMER, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Backup stand-down alarm, for when the process was asleep or killed (the Handler covers the rest). */
    private fun schedule(c: Context, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager?
        am?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c))
    }

    private fun cancel(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager?
        am?.cancel(pending(c))
    }

    /** Stands the alert down when the backup timer fires. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent?) {
            if (intent == null || ACTION_STAND_DOWN != intent.action) return
            val s = state(c)
            if (s.since <= 0L) return
            if (s.expired(System.currentTimeMillis())) {
                standDown(c)
            } else {
                schedule(c, s.until) // fired early: try again at the right time
            }
        }
    }
}
