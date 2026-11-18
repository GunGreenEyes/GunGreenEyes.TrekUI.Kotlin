package com.gungreeneyes.trekui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.RemoteViews

/**
 * Three-key console quick-launch widget for OTHER launchers: COMM (the phone dialer), console (this
 * launcher) and SYSTEM (Android settings). The keys share the width evenly, so every size the
 * widget can be resized to works with one layout.
 *
 * Each key has its own immutable PendingIntent (distinct request codes so they don't replace
 * one another).
 */
class QuickWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(c.packageName, R.layout.widget_quick)
        link(c, v, R.id.phone, Intent(Intent.ACTION_DIAL), REQ_DIAL)
        link(c, v, R.id.apps, Intent(c, MainActivity::class.java), REQ_LAUNCHER)
        link(c, v, R.id.settings, Intent(Settings.ACTION_SETTINGS), REQ_SETTINGS)
        for (id in ids) m.updateAppWidget(id, v)
    }

    private companion object {
        /** PendingIntent request codes, one per key. */
        const val REQ_DIAL = 1
        const val REQ_LAUNCHER = 2
        const val REQ_SETTINGS = 3

        /** Points a key at an activity intent through an immutable PendingIntent. */
        fun link(c: Context, v: RemoteViews, target: Int, i: Intent, code: Int) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(
                target,
                PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
    }
}
