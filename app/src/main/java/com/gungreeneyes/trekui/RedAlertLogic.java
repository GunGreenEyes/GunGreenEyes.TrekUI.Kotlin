package com.gungreeneyes.trekui;

import java.util.Locale;

/**
 * Pure (Android-free) rules of RED ALERT mode, so they can be unit-tested on the JVM
 * (see {@code RedAlertLogicTest}). {@link RedAlert} keeps the Android side (prefs, timers, sound,
 * Quick Settings tile); this class decides.
 *
 * <p>The state machine has three states:
 * <pre>
 *   STANDING DOWN --engage--> ALERT --mute--> ALERT (MUTED)
 *        ^                      |  ^--unmute--'      |
 *        '----stand down / timeout-------------------'
 * </pre>
 * Engaging again while on alert restarts the timeout (and keeps the mute choice). Every alert ends
 * by itself after its timeout, so a forgotten alert never keeps sounding.
 */
final class RedAlertLogic {

    private RedAlertLogic() {}

    /** Auto-timeouts offered in CONFIG, in seconds. */
    static final int[] TIMEOUT_SECONDS = {15, 30, 60, 120, 300, 600};
    /** Default auto-timeout: two minutes. */
    static final int DEFAULT_TIMEOUT_SECONDS = 120;
    /** One full pulse of the alert frame (bright → dim → bright), in ms. */
    static final long PULSE_PERIOD_MS = 1600L;
    /** Lowest brightness of the pulsing frame (0..1), so it never vanishes completely. */
    static final float PULSE_FLOOR = 0.25f;

    /** Not on alert. */
    static final int IDLE = 0;
    /** On alert, klaxon sounding (when sound is allowed). */
    static final int ALERT = 1;
    /** On alert, klaxon muted by the owner. */
    static final int MUTED = 2;

    /** Clamps a stored timeout to one of {@link #TIMEOUT_SECONDS} (unknown values give the default). */
    static int clampTimeout(int seconds) {
        for (int t : TIMEOUT_SECONDS) if (t == seconds) return t;
        return DEFAULT_TIMEOUT_SECONDS;
    }

    /** Index of a timeout in {@link #TIMEOUT_SECONDS}, for the CONFIG choice row. */
    static int timeoutIndex(int seconds) {
        int clamped = clampTimeout(seconds);
        for (int i = 0; i < TIMEOUT_SECONDS.length; i++) if (TIMEOUT_SECONDS[i] == clamped) return i;
        return 0;
    }

    /** Caption for a timeout choice: "15 SEC", "2 MIN". */
    static String timeoutLabel(int seconds) {
        return seconds < 60 ? seconds + " SEC" : (seconds / 60) + " MIN";
    }

    /** Remaining time as m:ss ("1:05"); never negative. */
    static String countdown(long remainingMs) {
        long s = (Math.max(0L, remainingMs) + 999L) / 1000L;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /**
     * Brightness of the pulsing frame at {@code now}: a smooth cosine between {@link #PULSE_FLOOR}
     * and 1, starting bright at the moment the alert began. Without animations it stays at 1.
     */
    static float pulse(long since, long now, boolean animate) {
        if (!animate) return 1f;
        long t = Math.floorMod(now - since, PULSE_PERIOD_MS);
        double wave = 0.5 + 0.5 * Math.cos(2 * Math.PI * t / PULSE_PERIOD_MS); // 1 → 0 → 1
        return (float) (PULSE_FLOOR + (1f - PULSE_FLOOR) * wave);
    }

    /**
     * Whether the klaxon may loop right now.
     *
     * @param soundSetting  the RED ALERT SOUND switch in CONFIG
     * @param ringerNormal  phone not in Silent/Vibrate and not in a call
     * @param dndOff        Do Not Disturb is off (interruption filter "all")
     */
    static boolean soundAllowed(State s, long now, boolean soundSetting, boolean ringerNormal, boolean dndOff) {
        return s.phase(now) == ALERT && soundSetting && ringerNormal && dndOff;
    }

    /**
     * Snapshot of the alert: when it began, when it times out, and whether it is muted. Immutable;
     * each transition returns a new state. {@code since == 0} means not on alert.
     */
    static final class State {
        /** Wall-clock start of the alert (ms), 0 when idle. */
        final long since;
        /** Wall-clock time the alert stands down by itself (ms), 0 when idle. */
        final long until;
        /** Klaxon muted (the frame keeps pulsing). */
        final boolean muted;

        static final State NONE = new State(0L, 0L, false);

        State(long since, long until, boolean muted) {
            this.since = since;
            this.until = until;
            this.muted = muted;
        }

        /** {@link #IDLE}, {@link #ALERT} or {@link #MUTED} at {@code now} (an expired alert is idle). */
        int phase(long now) {
            if (since <= 0L || now >= until) return IDLE;
            return muted ? MUTED : ALERT;
        }

        boolean active(long now) { return phase(now) != IDLE; }

        /** True when an alert was engaged and its timeout has passed (time to stand down). */
        boolean expired(long now) { return since > 0L && now >= until; }

        long remainingMs(long now) { return active(now) ? until - now : 0L; }

        /**
         * Goes to (or stays on) alert for {@code timeoutSeconds} from now. A fresh alert starts unmuted;
         * re-engaging a running alert extends it and keeps its start time and mute choice.
         */
        State engage(long now, int timeoutSeconds) {
            long end = now + clampTimeout(timeoutSeconds) * 1000L;
            return active(now) ? new State(since, end, muted) : new State(now, end, false);
        }

        /** Flips the mute; does nothing while idle. */
        State toggleMute(long now) {
            return active(now) ? new State(since, until, !muted) : this;
        }

        /** Ends the alert. */
        State standDown() { return NONE; }

        /** QS tile / function toggle: engage when idle, stand down when on alert. */
        State toggle(long now, int timeoutSeconds) {
            return active(now) ? standDown() : engage(now, timeoutSeconds);
        }
    }
}
