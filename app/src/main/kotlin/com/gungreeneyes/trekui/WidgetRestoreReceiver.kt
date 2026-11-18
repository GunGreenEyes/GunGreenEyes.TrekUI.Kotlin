package com.gungreeneyes.trekui

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Keeps hosted widget panels ([WidgetPanel]) working after Android restores a backup onto a new
 * device. The restore gives every widget the launcher hosted a new id and announces the old → new
 * mapping with [AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED]; without this receiver the
 * restored slots would point at ids that no longer exist and stay OFFLINE. The slot map is rewritten
 * with [WidgetSlots.remap] and the home layout is marked stale so it rebuilds on the next resume.
 *
 * Provider-side `ACTION_APPWIDGET_RESTORED` needs no handling: the launcher's own widgets for
 * other launchers ([StatusWidget], [QuickWidget]) keep no per-id state and are redrawn by
 * the `APPWIDGET_UPDATE` that follows a restore.
 */
class WidgetRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED != intent.action) return
        if (intent.getIntExtra(AppWidgetManager.EXTRA_HOST_ID, -1) != WidgetHost.HOST_ID) return
        val oldIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS)
        val newIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
        val prefs = Prefs.get(context)
        val before = WidgetSlots.load(prefs)
        val after = WidgetSlots.remap(before, oldIds, newIds)
        if (after != before) WidgetSlots.save(prefs, after)
    }
}
