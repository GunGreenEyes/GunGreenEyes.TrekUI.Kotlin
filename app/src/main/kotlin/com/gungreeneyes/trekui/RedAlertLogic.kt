package com.gungreeneyes.trekui

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos

/**
 * Pure (Android-free) rules of RED ALERT mode, so they can be unit-tested on the JVM
 * (see `RedAlertLogicTest`). [RedAlert] keeps the Android side (prefs, timers, sound,
 * Quick Settings tile); this object decides.
 *
 * The state machine has three states:
 * ```
 *   STANDING DOWN --engage--> ALERT --mute--> ALERT (MUTED)
 *        ^                      |  ^--unmute--'      |
 *        '----stand down / timeout-------------------'
 * ```
 * Engaging again while on alert restarts the timeout (and keeps the mute choice). Every alert ends
 * by itself after its timeout, so a forgotten alert never keeps sounding.
 */
internal object RedAlertLogic {

    /** Auto-timeouts offered in CONFIG, in seconds. */
    @JvmField
    val TIMEOUT_SECONDS: IntArray = intArrayOf(15, 30, 60, 120, 300, 600)

    /** Default auto-timeout: two minutes. */
    const val DEFAULT_TIMEOUT_SECONDS = 120

    /** One full pulse of the alert frame (bright → dim → bright), in ms. */
    const val PULSE_PERIOD_MS = 1600L

    /** Lowest brightness of the pulsing frame (0..1), so it never vanishes completely. */
    const val PULSE_FLOOR = 0.25f

    /** Not on alert. */
    const val IDLE = 0

    /** On alert, klaxon sounding (when sound is allowed). */
    const val ALERT = 1

    /** On alert, klaxon muted by the owner. */
    const val MUTED = 2

    /** Clamps a stored timeout to one of [TIMEOUT_SECONDS] (unknown values give the default). */
    @JvmStatic
    fun clampTimeout(seconds: Int): Int = if (seconds in TIMEOUT_SECONDS) seconds else DEFAULT_TIMEOUT_SECONDS

    /** Index of a timeout in [TIMEOUT_SECONDS], for the CONFIG choice row. */
    @JvmStatic
    fun timeoutIndex(seconds: Int): Int = maxOf(0, TIMEOUT_SECONDS.indexOf(clampTimeout(seconds)))

    /** Caption for a timeout choice: "15 SEC", "2 MIN". */
    @JvmStatic
    fun timeoutLabel(seconds: Int): String = if (seconds < 60) "$seconds SEC" else "${seconds / 60} MIN"

    /** Remaining time as m:ss ("1:05"); never negative. */
    @JvmStatic
    fun countdown(remainingMs: Long): String {
        val s = (maxOf(0L, remainingMs) + 999L) / 1000L
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /**
     * Brightness of the pulsing frame at `now`: a smooth cosine between [PULSE_FLOOR]
     * and 1, starting bright at the moment the alert began. Without animations it stays at 1.
     */
    @JvmStatic
    fun pulse(since: Long, now: Long, animate: Boolean): Float {
        if (!animate) return 1f
        val t = Math.floorMod(now - since, PULSE_PERIOD_MS)
        val wave = 0.5 + 0.5 * cos(2 * PI * t / PULSE_PERIOD_MS) // 1 → 0 → 1
        return (PULSE_FLOOR + (1f - PULSE_FLOOR) * wave).toFloat()
    }

    /**
     * Whether the klaxon may loop right now.
     *
     * @param soundSetting  the RED ALERT SOUND switch in CONFIG
     * @param ringerNormal  phone not in Silent/Vibrate and not in a call
     * @param dndOff        Do Not Disturb is off (interruption filter "all")
     */
    @JvmStatic
    fun soundAllowed(s: State, now: Long, soundSetting: Boolean, ringerNormal: Boolean, dndOff: Boolean): Boolean =
        s.phase(now) == ALERT && soundSetting && ringerNormal && dndOff

    /**
     * Snapshot of the alert: when it began, when it times out, and whether it is muted. Immutable;
     * each transition returns a new state. `since == 0` means not on alert.
     */
    class State(
        /** Wall-clock start of the alert (ms), 0 when idle. */
        @JvmField val since: Long,
        /** Wall-clock time the alert stands down by itself (ms), 0 when idle. */
        @JvmField val until: Long,
        /** Klaxon muted (the frame keeps pulsing). */
        @JvmField val muted: Boolean,
    ) {
        /** [IDLE], [ALERT] or [MUTED] at `now` (an expired alert is idle). */
        fun phase(now: Long): Int = when {
            since <= 0L || now >= until -> IDLE
            muted -> MUTED
            else -> ALERT
        }

        fun active(now: Long): Boolean = phase(now) != IDLE

        /** True when an alert was engaged and its timeout has passed (time to stand down). */
        fun expired(now: Long): Boolean = since > 0L && now >= until

        fun remainingMs(now: Long): Long = if (active(now)) until - now else 0L

        /**
         * Goes to (or stays on) alert for `timeoutSeconds` from now. A fresh alert starts unmuted;
         * re-engaging a running alert extends it and keeps its start time and mute choice.
         */
        fun engage(now: Long, timeoutSeconds: Int): State {
            val end = now + clampTimeout(timeoutSeconds) * 1000L
            return if (active(now)) State(since, end, muted) else State(now, end, false)
        }

        /** Flips the mute; does nothing while idle. */
        fun toggleMute(now: Long): State = if (active(now)) State(since, until, !muted) else this

        /** Ends the alert. */
        fun standDown(): State = NONE

        /** QS tile / function toggle: engage when idle, stand down when on alert. */
        fun toggle(now: Long, timeoutSeconds: Int): State = if (active(now)) standDown() else engage(now, timeoutSeconds)

        companion object {
            @JvmField
            val NONE = State(0L, 0L, false)
        }
    }
}
