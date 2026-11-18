package com.gungreeneyes.trekui.lockscreen

import android.app.PendingIntent
import com.gungreeneyes.trekui.lockscreen.logic.Redaction

/**
 * One notification as the wake screen lists it: already filtered and privacy-redacted by [LockListener], so
 * the drawing code never sees text it must not show. A REDACTED item carries only the app name and the
 * app's own public text (or none); its private title and text are dropped here, never just hidden at draw
 * time.
 */
class LockItem(
    val key: String,
    val packageName: String,
    appName: String?,
    title: String?,
    text: String?,
    val postTime: Long,
    /** The notification's accent colour, or 0 for "use the interface's". */
    val color: Int,
    val show: Redaction.Show,
    /** Opened after the user unlocks (may be null). */
    val contentIntent: PendingIntent?,
) {
    val appName: String = appName.orEmpty()

    /** Title to draw (public version or empty when redacted). */
    val title: String = title.orEmpty()

    /** Body line to draw (public version or empty when redacted). */
    val text: String = text.orEmpty()

    /** True if only the redacted form may be shown. */
    fun redacted(): Boolean = show == Redaction.Show.REDACTED
}
