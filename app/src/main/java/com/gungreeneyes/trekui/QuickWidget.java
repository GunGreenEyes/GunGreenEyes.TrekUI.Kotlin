package com.gungreeneyes.trekui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.RemoteViews;

/**
 * Three-key console quick-launch widget for OTHER launchers: COMM (the phone dialer), console (this
 * launcher) and SYSTEM (Android settings). The keys share the width evenly, so every size the
 * widget can be resized to works with one layout.
 *
 * <p>Each key has its own immutable PendingIntent (distinct request codes so they don't replace
 * one another).
 */
public class QuickWidget extends AppWidgetProvider {
    /** PendingIntent request codes, one per key. */
    private static final int REQ_DIAL = 1, REQ_LAUNCHER = 2, REQ_SETTINGS = 3;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_quick);
        link(c, v, R.id.phone, new Intent(Intent.ACTION_DIAL), REQ_DIAL);
        link(c, v, R.id.apps, new Intent(c, MainActivity.class), REQ_LAUNCHER);
        link(c, v, R.id.settings, new Intent(Settings.ACTION_SETTINGS), REQ_SETTINGS);
        for (int id : ids) m.updateAppWidget(id, v);
    }

    /** Points a key at an activity intent through an immutable PendingIntent. */
    private static void link(Context c, RemoteViews v, int target, Intent i, int code) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        v.setOnClickPendingIntent(target, PendingIntent.getActivity(c, code, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }
}
