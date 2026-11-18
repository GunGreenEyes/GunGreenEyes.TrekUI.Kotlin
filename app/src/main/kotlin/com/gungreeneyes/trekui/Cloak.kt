package com.gungreeneyes.trekui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * CLOAK: a timed night mode. While engaged the console interface goes dark (black cells with
 * coloured outlines, orange lettering, everything dimmed), interface sounds drop to 10 %, and
 * (with "Modify system settings") the phone's own screen brightness is turned down. It disengages
 * by itself after the chosen time (default one hour) and puts the brightness back, unless the
 * owner changed it in the meantime.
 */
internal object Cloak {
    /** Durations offered in settings, in minutes. */
    @JvmField
    val MINUTES: IntArray = intArrayOf(30, 60, 90, 120)

    /** Screen brightness levels offered, in % of the 0–255 system setting. */
    @JvmField
    val LEVELS: IntArray = intArrayOf(3, 6, 10, 20)

    /** Interface sound volume while cloaked. */
    const val SOUND_LEVEL = 0.10f

    /** Broadcast action and PendingIntent request code of the disengage timer. */
    const val ACTION_DECLOAK = "com.gungreeneyes.trekui.DECLOAK"
    private const val REQ_TIMER = 47

    /** The system brightness setting's maximum value. */
    private const val MAX_BRIGHTNESS = 255

    /** A timer that fires this close to the end time counts as on time. */
    private const val EARLY_TOLERANCE_MS = 60_000L

    /** True while a cloak is engaged and its end time hasn't passed. */
    @JvmStatic
    fun active(c: Context): Boolean = Prefs.get(c).l("cloakUntil", 0L) > System.currentTimeMillis()

    /** Time left on the cloak timer, 0 when not cloaked. */
    @JvmStatic
    fun remainingMs(c: Context): Long = Math.max(0L, Prefs.get(c).l("cloakUntil", 0L) - System.currentTimeMillis())

    @JvmStatic
    fun minutes(p: Prefs): Int = p.i("cloakMinutes", 60)

    @JvmStatic
    fun level(p: Prefs): Int = p.i("cloakLevel", 6)

    @JvmStatic
    fun engageSound(p: Prefs): String? = p.s("cloakSoundOn", "ui_screen_close")

    @JvmStatic
    fun disengageSound(p: Prefs): String? = p.s("cloakSoundOff", "ui_screen_open")

    /** Engages the cloak, or disengages it (with sound) when it is on. */
    @JvmStatic
    fun toggle(c: Context) {
        if (active(c)) disengage(c, false) else engage(c)
    }

    /**
     * Starts (or extends) the cloak for the configured time: dims the screen once (when allowed),
     * arms the timer, switches the interface to its dark look and plays the engage sound.
     */
    @JvmStatic
    fun engage(c: Context) {
        val p = Prefs.get(c)
        val until = System.currentTimeMillis() + minutes(p) * 60_000L
        val already = p.l("cloakUntil", 0L) > 0
        p.put("cloakUntil", until)
        if (!already) dimScreen(c, p)
        schedule(c, until)
        Ui.cloak = true
        p.bump()
        QsTiles.refresh(c)
        Sfx.playClip(c, engageSound(p), true) // already at cloak volume
    }

    /** @param automatic the timer ran out (no sound: the owner is probably asleep) */
    @JvmStatic
    fun disengage(c: Context, automatic: Boolean) {
        val p = Prefs.get(c)
        if (p.l("cloakUntil", 0L) == 0L) return
        p.put("cloakUntil", 0L)
        restoreScreen(c, p)
        cancel(c)
        Ui.cloak = false
        p.bump()
        QsTiles.refresh(c)
        if (!automatic) Sfx.playClip(c, disengageSound(p), true)
    }

    /** Called when the launcher wakes up: ends an expired cloak if the alarm hasn't yet. */
    @JvmStatic
    fun check(c: Context) {
        val p = Prefs.get(c)
        val until = p.l("cloakUntil", 0L)
        if (until > 0 && until <= System.currentTimeMillis()) disengage(c, true)
        Ui.cloak = active(c)
    }

    /** True when "Modify system settings" is granted, so the screen itself can be dimmed. */
    @JvmStatic
    fun canDimSystem(c: Context): Boolean = Settings.System.canWrite(c)

    /** System brightness value (1..255) for a level in percent. */
    @JvmStatic
    fun brightnessFor(levelPercent: Int): Int {
        val pct = Math.max(0, Math.min(100, levelPercent))
        return Math.max(1, Math.round(MAX_BRIGHTNESS * pct / 100f))
    }

    // ---- system brightness ------------------------------------------------------------------------

    /** Remembers the current brightness and mode, then sets manual mode at the cloak level. */
    private fun dimScreen(c: Context, p: Prefs) {
        if (!canDimSystem(c)) return
        try {
            val cr = c.contentResolver
            val mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            val value = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
            val target = brightnessFor(level(p))
            p.put("cloakPrevMode", mode)
            p.put("cloakPrevValue", value)
            p.put("cloakSetValue", target)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        } catch (ignored: Exception) {
            p.put("cloakSetValue", -1)
        }
    }

    /** Puts the remembered brightness back, unless the owner changed it while cloaked. */
    private fun restoreScreen(c: Context, p: Prefs) {
        val set = p.i("cloakSetValue", -1)
        p.put("cloakSetValue", -1)
        if (set < 0 || !canDimSystem(c)) return
        try {
            val cr = c.contentResolver
            val now = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, set)
            val mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            // the owner adjusted brightness while cloaked: leave their choice alone
            if (now != set || mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) return
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, p.i("cloakPrevValue", 128))
            Settings.System.putInt(
                cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                p.i("cloakPrevMode", Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC),
            )
        } catch (ignored: Exception) {
        }
    }

    // ---- timer ------------------------------------------------------------------------------------------

    private fun pending(c: Context): PendingIntent {
        val i = Intent(c, Receiver::class.java).setAction(ACTION_DECLOAK)
        return PendingIntent.getBroadcast(c, REQ_TIMER, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Arms the disengage timer for the given wall-clock time. */
    @JvmStatic
    fun schedule(c: Context, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager?
        // inexact and non-waking: it runs at the next wake-up after the time, which is all that's needed
        am?.setAndAllowWhileIdle(AlarmManager.RTC, at, pending(c))
    }

    private fun cancel(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager?
        am?.cancel(pending(c))
    }

    /** Ends the cloak when the timer fires. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent?) {
            if (intent == null || ACTION_DECLOAK != intent.action) return
            val p = Prefs.get(c)
            val until = p.l("cloakUntil", 0L)
            if (until <= 0) return
            if (until <= System.currentTimeMillis() + EARLY_TOLERANCE_MS) {
                disengage(c, true)
            } else {
                schedule(c, until) // fired early: try again at the right time
            }
        }
    }
}
