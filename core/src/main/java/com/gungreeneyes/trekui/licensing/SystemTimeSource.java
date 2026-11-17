package com.gungreeneyes.trekui.licensing;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Real {@link TimeSource}: {@link SystemClock#elapsedRealtime()} for session length (immune to
 * wall-clock changes) and {@link Settings.Global#BOOT_COUNT} to recognise a reboot even when the
 * new uptime has already passed the stored session start.
 */
final class SystemTimeSource implements TimeSource {
    private final Context appContext;

    SystemTimeSource(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override public long elapsedRealtime() {
        return SystemClock.elapsedRealtime();
    }

    @Override public long bootCount() {
        try {
            return Settings.Global.getInt(appContext.getContentResolver(), Settings.Global.BOOT_COUNT);
        } catch (Settings.SettingNotFoundException | SecurityException e) {
            return UNKNOWN_BOOT;
        }
    }
}
