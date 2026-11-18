package com.gungreeneyes.trekui.lockscreen;

import android.app.PendingIntent;

import com.gungreeneyes.trekui.lockscreen.logic.Redaction;

/**
 * One notification as the wake screen lists it: already filtered and privacy-redacted by
 * {@link LockListener}, so the drawing code never sees text it must not show. A REDACTED item carries
 * only the app name and the app's own public text (or none); its private title and text are dropped
 * here, never just hidden at draw time.
 */
public final class LockItem {
    public final String key;
    public final String packageName;
    public final String appName;
    /** Title to draw (public version or empty when redacted). */
    public final String title;
    /** Body line to draw (public version or empty when redacted). */
    public final String text;
    public final long postTime;
    /** The notification's accent colour, or 0 for "use the interface's". */
    public final int color;
    public final Redaction.Show show;
    /** Opened after the user unlocks (may be null). */
    public final PendingIntent contentIntent;

    public LockItem(String key, String packageName, String appName, String title, String text, long postTime,
                    int color, Redaction.Show show, PendingIntent contentIntent) {
        this.key = key;
        this.packageName = packageName;
        this.appName = appName == null ? "" : appName;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.postTime = postTime;
        this.color = color;
        this.show = show;
        this.contentIntent = contentIntent;
    }

    /** True if only the redacted form may be shown. */
    public boolean redacted() {
        return show == Redaction.Show.REDACTED;
    }
}
