package com.gungreeneyes.trekui;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * RED ALERT mode: an assignable launcher function and a Quick Settings tile. While on alert the home
 * screen of every interface shows a pulsing alarm frame ({@link RedAlertOverlay}) with MUTE and
 * STAND DOWN controls, and the RED ALERT sound event ({@link Sfx#E_RED}) loops. It stands down by
 * itself after the timeout chosen in CONFIG.
 *
 * <p>It never gets in the way: the frame passes every touch through except its two buttons, the
 * klaxon stays silent in Silent/Vibrate, during calls, with Do Not Disturb on, with interface sounds
 * off, or when no RED ALERT sound is available (production builds ship no recordings).
 *
 * <p>The state lives in {@link Prefs} ({@code redAlertSince}, {@code redAlertUntil},
 * {@code redAlertMuted}) so the home screen, CONFIG and the tile agree, and the decisions are in
 * {@link RedAlertLogic}. Everything runs on the main thread (tile clicks, receivers and views all do).
 */
final class RedAlert {

    /** Told whenever the alert engages, mutes, unmutes or stands down. */
    interface Listener {
        void onRedAlertChanged();
    }

    /** Broadcast action and PendingIntent request code of the stand-down timer. */
    static final String ACTION_STAND_DOWN = "com.gungreeneyes.trekui.RED_ALERT_STAND_DOWN";
    private static final int REQ_TIMER = 48;
    /** Retry delay while the klaxon clip is still loading into the SoundPool. */
    private static final long SOUND_RETRY_MS = 250L;
    /** Give up waiting for the clip after this many retries (it is then silent). */
    private static final int SOUND_RETRIES = 12;

    static final String KEY_SINCE = "redAlertSince";
    static final String KEY_UNTIL = "redAlertUntil";
    static final String KEY_MUTED = "redAlertMuted";
    static final String KEY_TIMEOUT = "redAlertTimeout";
    static final String KEY_SOUND = "redAlertSound";

    private static final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    /** SoundPool stream of the looping klaxon, 0 when silent. */
    private static int stream;
    private static int soundRetries;
    /** Application context for the timers (never an Activity). */
    private static Context app;

    private static final Runnable timeout = () -> { if (app != null) check(app); };
    private static final Runnable retrySound = () -> { if (app != null) syncSound(app); };

    private RedAlert() {}

    // ---- state ----------------------------------------------------------------------------------

    static RedAlertLogic.State state(Context c) {
        Prefs p = Prefs.get(c);
        return new RedAlertLogic.State(p.l(KEY_SINCE, 0L), p.l(KEY_UNTIL, 0L), p.b(KEY_MUTED, false));
    }

    static boolean active(Context c) { return state(c).active(System.currentTimeMillis()); }

    static boolean muted(Context c) { return state(c).phase(System.currentTimeMillis()) == RedAlertLogic.MUTED; }

    static long remainingMs(Context c) { return state(c).remainingMs(System.currentTimeMillis()); }

    /** Auto-timeout in seconds (CONFIG › RED ALERT LASTS). */
    static int timeoutSeconds(Prefs p) { return RedAlertLogic.clampTimeout(p.i(KEY_TIMEOUT, RedAlertLogic.DEFAULT_TIMEOUT_SECONDS)); }

    /** CONFIG › RED ALERT SOUND: loop the klaxon at all. */
    static boolean soundOn(Prefs p) { return p.b(KEY_SOUND, true); }

    static void addListener(Listener l) { listeners.add(l); }

    static void removeListener(Listener l) { listeners.remove(l); }

    // ---- transitions ------------------------------------------------------------------------------

    /** Engages when standing down, stands down when on alert (function tiles and the QS tile). */
    static void toggle(Context c) {
        if (active(c)) standDown(c); else engage(c);
    }

    /** Goes to red alert (or extends a running one) for the configured timeout. */
    static void engage(Context c) {
        long now = System.currentTimeMillis();
        save(c, state(c).engage(now, timeoutSeconds(Prefs.get(c))));
    }

    /** Ends the alert: frame off, klaxon off, timer cancelled. */
    static void standDown(Context c) {
        save(c, RedAlertLogic.State.NONE);
    }

    /** MUTE / UNMUTE: silences the klaxon but keeps the frame until stand down or timeout. */
    static void toggleMute(Context c) {
        save(c, state(c).toggleMute(System.currentTimeMillis()));
    }

    /**
     * Brings everything in line with the stored state: stands down an expired alert, and starts or
     * stops the klaxon when the ringer mode or Do Not Disturb changed. Called by the overlay about once
     * a second, by the timers, and when the home screen or a tile wakes up.
     */
    static void check(Context c) {
        RedAlertLogic.State s = state(c);
        if (s.expired(System.currentTimeMillis())) {
            standDown(c);
            return;
        }
        syncSound(c);
    }

    /** Stores a new state, re-arms timers and sound, and tells the views and the QS tile. */
    private static void save(Context c, RedAlertLogic.State s) {
        app = c.getApplicationContext();
        Prefs p = Prefs.get(c);
        p.put(KEY_SINCE, s.since);
        p.put(KEY_UNTIL, s.until);
        p.put(KEY_MUTED, s.muted);
        handler.removeCallbacks(timeout);
        long now = System.currentTimeMillis();
        if (s.active(now)) {
            handler.postDelayed(timeout, s.until - now + 50L);
            schedule(c, s.until);
        } else {
            cancel(c);
        }
        syncSound(c);
        for (Listener l : listeners) l.onRedAlertChanged();
        QsTiles.refresh(c);
    }

    // ---- sound ------------------------------------------------------------------------------------

    /** False with Do Not Disturb on (any interruption filter other than "all"). */
    static boolean dndOff(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return true;
        int filter = nm.getCurrentInterruptionFilter();
        return filter == NotificationManager.INTERRUPTION_FILTER_ALL || filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    }

    /** Starts or stops the looping klaxon to match the state and the phone's sound settings. */
    private static void syncSound(Context c) {
        handler.removeCallbacks(retrySound);
        app = c.getApplicationContext();
        boolean want = RedAlertLogic.soundAllowed(state(c), System.currentTimeMillis(),
                soundOn(Prefs.get(c)), Sfx.audioAllowed(c), dndOff(c));
        if (!want) {
            stopSound();
            soundRetries = 0;
            return;
        }
        if (stream > 0) return; // already looping
        int started = Sfx.loopEvent(app, Sfx.E_RED);
        if (started > 0) {
            stream = started;
            soundRetries = 0;
        } else if (started < 0 && soundRetries++ < SOUND_RETRIES) {
            handler.postDelayed(retrySound, SOUND_RETRY_MS); // clip still loading
        }
    }

    private static void stopSound() {
        if (stream > 0) Sfx.stopLoop(stream);
        stream = 0;
    }

    /** Forgets the klaxon stream after Sfx released its SoundPool (the stream died with it). */
    static void soundReleased() {
        stream = 0;
    }

    // ---- timer ------------------------------------------------------------------------------------

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, Receiver.class).setAction(ACTION_STAND_DOWN);
        return PendingIntent.getBroadcast(c, REQ_TIMER, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Backup stand-down alarm, for when the process was asleep or killed (the Handler covers the rest). */
    private static void schedule(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(c));
    }

    private static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c));
    }

    /** Stands the alert down when the backup timer fires. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            if (intent == null || !ACTION_STAND_DOWN.equals(intent.getAction())) return;
            RedAlertLogic.State s = state(c);
            if (s.since <= 0L) return;
            if (s.expired(System.currentTimeMillis())) standDown(c);
            else schedule(c, s.until); // fired early: try again at the right time
        }
    }
}
