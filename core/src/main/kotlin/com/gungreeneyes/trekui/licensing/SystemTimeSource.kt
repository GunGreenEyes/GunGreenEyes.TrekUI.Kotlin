package com.gungreeneyes.trekui.licensing

import android.content.Context
import android.os.SystemClock
import android.provider.Settings

/**
 * Real [TimeSource]: [SystemClock.elapsedRealtime] for session length (immune to wall-clock changes)
 * and [Settings.Global.BOOT_COUNT] to recognise a reboot even when the new uptime has already passed
 * the stored session start.
 */
internal class SystemTimeSource(context: Context) : TimeSource {
    private val appContext: Context = context.applicationContext

    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override fun bootCount(): Long =
        try {
            Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT).toLong()
        } catch (e: Settings.SettingNotFoundException) {
            TimeSource.UNKNOWN_BOOT
        } catch (e: SecurityException) {
            TimeSource.UNKNOWN_BOOT
        }
}
