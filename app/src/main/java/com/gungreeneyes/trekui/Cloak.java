package com.gungreeneyes.trekui;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * CLOAK: a timed night mode. While engaged the console interface goes dark (black cells with
 * coloured outlines, orange lettering, everything dimmed), interface sounds drop to 10 %, and
 * (with "Modify system settings") the phone's own screen brightness is turned down. It disengages
 * by itself after the chosen time (default one hour) and puts the brightness back, unless the
 * owner changed it in the meantime.
 */
final class Cloak {
    /** Durations offered in settings, in minutes. */
    static final int[] MINUTES = {30, 60, 90, 120};
    /** Screen brightness levels offered, in % of the 0–255 system setting. */
    static final int[] LEVELS = {3, 6, 10, 20};
    /** Interface sound volume while cloaked. */
    static final float SOUND_LEVEL = 0.10f;

    /** Broadcast action and PendingIntent request code of the disengage timer. */
    static final String ACTION_DECLOAK = "com.gungreeneyes.trekui.DECLOAK";
    private static final int REQ_TIMER = 47;
    /** The system brightness setting's maximum value. */
    private static final int MAX_BRIGHTNESS = 255;
    /** A timer that fires this close to the end time counts as on time. */
    private static final long EARLY_TOLERANCE_MS = 60_000L;

    private Cloak() {}

    /** True while a cloak is engaged and its end time hasn't passed. */
    static boolean active(Context c) { return Prefs.get(c).l("cloakUntil", 0L) > System.currentTimeMillis(); }

    /** Time left on the cloak timer, 0 when not cloaked. */
    static long remainingMs(Context c) { return Math.max(0, Prefs.get(c).l("cloakUntil", 0L) - System.currentTimeMillis()); }

    static int minutes(Prefs p) { return p.i("cloakMinutes", 60); }

    static int level(Prefs p) { return p.i("cloakLevel", 6); }

    static String engageSound(Prefs p) { return p.s("cloakSoundOn", "ui_screen_close"); }

    static String disengageSound(Prefs p) { return p.s("cloakSoundOff", "ui_screen_open"); }

    /** Engages the cloak, or disengages it (with sound) when it is on. */
    static void toggle(Context c) {
        if (active(c)) disengage(c, false); else engage(c);
    }

    /**
     * Starts (or extends) the cloak for the configured time: dims the screen once (when allowed),
     * arms the timer, switches the interface to its dark look and plays the engage sound.
     */
    static void engage(Context c) {
        Prefs p = Prefs.get(c);
        long until = System.currentTimeMillis() + minutes(p) * 60_000L;
        boolean already = p.l("cloakUntil", 0L) > 0;
        p.put("cloakUntil", until);
        if (!already) dimScreen(c, p);
        schedule(c, until);
        Ui.cloak = true;
        p.bump();
        QsTiles.refresh(c);
        Sfx.playClip(c, engageSound(p), true); // already at cloak volume
    }

    /** @param automatic the timer ran out (no sound: the owner is probably asleep) */
    static void disengage(Context c, boolean automatic) {
        Prefs p = Prefs.get(c);
        if (p.l("cloakUntil", 0L) == 0L) return;
        p.put("cloakUntil", 0L);
        restoreScreen(c, p);
        cancel(c);
        Ui.cloak = false;
        p.bump();
        QsTiles.refresh(c);
        if (!automatic) Sfx.playClip(c, disengageSound(p), true);
    }

    /** Called when the launcher wakes up: ends an expired cloak if the alarm hasn't yet. */
    static void check(Context c) {
        Prefs p = Prefs.get(c);
        long until = p.l("cloakUntil", 0L);
        if (until > 0 && until <= System.currentTimeMillis()) disengage(c, true);
        Ui.cloak = active(c);
    }

    /** True when "Modify system settings" is granted, so the screen itself can be dimmed. */
    static boolean canDimSystem(Context c) { return Settings.System.canWrite(c); }

    /** System brightness value (1..255) for a level in percent. */
    static int brightnessFor(int levelPercent) {
        int pct = Math.max(0, Math.min(100, levelPercent));
        return Math.max(1, Math.round(MAX_BRIGHTNESS * pct / 100f));
    }

    // ---- system brightness ------------------------------------------------------------------------

    /** Remembers the current brightness and mode, then sets manual mode at the cloak level. */
    private static void dimScreen(Context c, Prefs p) {
        if (!canDimSystem(c)) return;
        try {
            ContentResolver cr = c.getContentResolver();
            int mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
            int value = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128);
            int target = brightnessFor(level(p));
            p.put("cloakPrevMode", mode);
            p.put("cloakPrevValue", value);
            p.put("cloakSetValue", target);
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target);
        } catch (Exception ignored) {
            p.put("cloakSetValue", -1);
        }
    }

    /** Puts the remembered brightness back, unless the owner changed it while cloaked. */
    private static void restoreScreen(Context c, Prefs p) {
        int set = p.i("cloakSetValue", -1);
        p.put("cloakSetValue", -1);
        if (set < 0 || !canDimSystem(c)) return;
        try {
            ContentResolver cr = c.getContentResolver();
            int now = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, set);
            int mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            // the owner adjusted brightness while cloaked: leave their choice alone
            if (now != set || mode != Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) return;
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, p.i("cloakPrevValue", 128));
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
                    p.i("cloakPrevMode", Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC));
        } catch (Exception ignored) {
        }
    }

    // ---- timer ------------------------------------------------------------------------------------------

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, Receiver.class).setAction(ACTION_DECLOAK);
        return PendingIntent.getBroadcast(c, REQ_TIMER, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Arms the disengage timer for the given wall-clock time. */
    static void schedule(Context c, long at) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        // inexact and non-waking: it runs at the next wake-up after the time, which is all that's needed
        if (am != null) am.setAndAllowWhileIdle(AlarmManager.RTC, at, pending(c));
    }

    private static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pending(c));
    }

    /** Ends the cloak when the timer fires. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent intent) {
            if (intent == null || !ACTION_DECLOAK.equals(intent.getAction())) return;
            Prefs p = Prefs.get(c);
            long until = p.l("cloakUntil", 0L);
            if (until <= 0) return;
            if (until <= System.currentTimeMillis() + EARLY_TOLERANCE_MS) disengage(c, true);
            else schedule(c, until); // fired early: try again at the right time
        }
    }
}
