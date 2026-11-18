package com.gungreeneyes.trekui.licensing

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings

/**
 * Real [TimeSource]: [SystemClock.elapsedRealtime] for session length (immune to wall-clock changes)
 * and [Settings.Global.BOOT_COUNT] to recognise a reboot even when the new uptime has already passed
 * the stored session start. Android 6.0 has no boot count (API 24): it reports [TimeSource.UNKNOWN_BOOT],
 * and [TrialClock] then tells reboots apart by the uptime alone.
 */
internal class SystemTimeSource(context: Context) : TimeSource {
    private val appContext: Context = context.applicationContext

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Long =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            TimeSource.UNKNOWN_BOOT
        } else {
            try {
                Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT).toLong()
            } catch (e: Settings.SettingNotFoundException) {
                TimeSource.UNKNOWN_BOOT
            } catch (e: SecurityException) {
                TimeSource.UNKNOWN_BOOT
            }
        }
}
