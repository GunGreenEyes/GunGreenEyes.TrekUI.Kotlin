package com.gungreeneyes.trekui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/**
 * Home-screen widget for OTHER launchers: live clock and date (TextClock, no polling) plus the
 * battery level (refreshed by Android's widget schedule, about every 30 minutes, and on resize).
 * Tapping it opens console.
 *
 * Responsive: on Android 12+ the launcher picks between a compact layout (time and power only)
 * and the full one by the widget's live size; on older versions the size reported in the widget
 * options decides. The sizing rules are pure functions ([showDetails], [clockSp]).
 */
class StatusWidget : AppWidgetProvider() {

    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) {
        val pct = batteryPercent(c)
        for (id in ids) m.updateAppWidget(id, views(c, m.getAppWidgetOptions(id), pct))
    }

    override fun onAppWidgetOptionsChanged(c: Context, m: AppWidgetManager, id: Int, options: Bundle?) {
        m.updateAppWidget(id, views(c, options, batteryPercent(c)))
    }

    companion object {
        /** Below this height (dp) there is room only for the clock row. */
        const val COMPACT_HEIGHT_DP = 80f

        /** Below this width (dp) the clock text shrinks. */
        const val NARROW_WIDTH_DP = 180f

        /** Views for the widget: a size map on Android 12+, one layout for the reported size before that. */
        private fun views(c: Context, options: Bundle?, pct: Int): RemoteViews {
            if (Build.VERSION.SDK_INT >= 31) {
                val sizes = HashMap<SizeF, RemoteViews>()
                sizes[SizeF(110f, 40f)] = build(c, 110f, 40f, pct)
                sizes[SizeF(NARROW_WIDTH_DP, 40f)] = build(c, NARROW_WIDTH_DP, 40f, pct)
                sizes[SizeF(110f, COMPACT_HEIGHT_DP)] = build(c, 110f, COMPACT_HEIGHT_DP, pct)
                sizes[SizeF(NARROW_WIDTH_DP, COMPACT_HEIGHT_DP)] = build(c, NARROW_WIDTH_DP, COMPACT_HEIGHT_DP, pct)
                return RemoteViews(sizes)
            }
            val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)?.toFloat() ?: 250f
            val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)?.toFloat() ?: 90f
            return build(c, w, h, pct)
        }

        /** One layout for a widget of the given size in dp. */
        private fun build(c: Context, widthDp: Float, heightDp: Float, pct: Int): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_status)
            v.setTextViewText(R.id.battery, batteryText(pct))
            val details = if (showDetails(heightDp)) View.VISIBLE else View.GONE
            v.setViewVisibility(R.id.header, details)
            v.setViewVisibility(R.id.date, details)
            v.setTextViewTextSize(R.id.time, TypedValue.COMPLEX_UNIT_SP, clockSp(widthDp, heightDp))
            v.setOnClickPendingIntent(R.id.root, openConsole(c))
            return v
        }

        /** Header and date lines fit only when the widget is at least [COMPACT_HEIGHT_DP] tall. */
        fun showDetails(heightDp: Float): Boolean = heightDp >= COMPACT_HEIGHT_DP

        /** Clock text size in sp: 40 normally, smaller on narrow or short widgets. */
        fun clockSp(widthDp: Float, heightDp: Float): Float {
            var sp = 40f
            if (widthDp < NARROW_WIDTH_DP) sp = 28f
            if (heightDp < 56f) sp = Math.min(sp, 26f)
            return sp
        }

        /** "PWR 73%", or "PWR --%" when unknown. */
        fun batteryText(pct: Int): String = "PWR " + (if (pct < 0) "--" else pct.toString()) + "%"

        /** Battery percentage from BatteryManager, falling back to the sticky battery broadcast; -1 if unknown. */
        private fun batteryPercent(c: Context): Int {
            val app = c.applicationContext
            val bm = app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager?
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: Int.MIN_VALUE
            if (pct in 0..100) return pct
            val bi = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            return if (bi == null) -1 else MainActivity.batteryPct(bi)
        }

        /** Immutable PendingIntent that opens console (explicit component). */
        private fun openConsole(c: Context): PendingIntent {
            val i = Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
