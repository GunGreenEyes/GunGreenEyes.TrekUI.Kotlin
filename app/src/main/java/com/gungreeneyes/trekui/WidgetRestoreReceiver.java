package com.gungreeneyes.trekui;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import java.util.LinkedHashMap;

/**
 * Keeps hosted widget panels ({@link WidgetPanel}) working after Android restores a backup onto a new
 * device. The restore gives every widget the launcher hosted a new id and announces the old → new
 * mapping with {@link AppWidgetManager#ACTION_APPWIDGET_HOST_RESTORED}; without this receiver the
 * restored slots would point at ids that no longer exist and stay OFFLINE. The slot map is rewritten
 * with {@link WidgetSlots#remap} and the home layout is marked stale so it rebuilds on the next resume.
 *
 * <p>Provider-side {@code ACTION_APPWIDGET_RESTORED} needs no handling: the launcher's own widgets for
 * other launchers ({@link StatusWidget}, {@link QuickWidget}) keep no per-id state and are redrawn by
 * the {@code APPWIDGET_UPDATE} that follows a restore.
 */
public final class WidgetRestoreReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED.equals(intent.getAction())) return;
        if (intent.getIntExtra(AppWidgetManager.EXTRA_HOST_ID, -1) != WidgetHost.HOST_ID) return;
        int[] oldIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS);
        int[] newIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS);
        Prefs prefs = Prefs.get(context);
        LinkedHashMap<Integer, Integer> before = WidgetSlots.load(prefs);
        LinkedHashMap<Integer, Integer> after = WidgetSlots.remap(before, oldIds, newIds);
        if (!after.equals(before)) WidgetSlots.save(prefs, after);
    }
}
