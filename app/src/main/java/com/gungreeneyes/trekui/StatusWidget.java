package com.gungreeneyes.trekui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;

import java.util.HashMap;
import java.util.Map;

/**
 * Home-screen widget for OTHER launchers: live clock and date (TextClock, no polling) plus the
 * battery level (refreshed by Android's widget schedule, about every 30 minutes, and on resize).
 * Tapping it opens console.
 *
 * <p>Responsive: on Android 12+ the launcher picks between a compact layout (time and power only)
 * and the full one by the widget's live size; on older versions the size reported in the widget
 * options decides. The sizing rules are pure functions ({@link #showDetails}, {@link #clockSp}).
 */
public class StatusWidget extends AppWidgetProvider {
    /** Below this height (dp) there is room only for the clock row. */
    static final float COMPACT_HEIGHT_DP = 80f;
    /** Below this width (dp) the clock text shrinks. */
    static final float NARROW_WIDTH_DP = 180f;

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        int pct = batteryPercent(c);
        for (int id : ids) m.updateAppWidget(id, views(c, m.getAppWidgetOptions(id), pct));
    }

    @Override
    public void onAppWidgetOptionsChanged(Context c, AppWidgetManager m, int id, Bundle options) {
        m.updateAppWidget(id, views(c, options, batteryPercent(c)));
    }

    /** Views for the widget: a size map on Android 12+, one layout for the reported size before that. */
    private static RemoteViews views(Context c, Bundle options, int pct) {
        if (Build.VERSION.SDK_INT >= 31) {
            Map<SizeF, RemoteViews> sizes = new HashMap<>();
            sizes.put(new SizeF(110f, 40f), build(c, 110f, 40f, pct));
            sizes.put(new SizeF(NARROW_WIDTH_DP, 40f), build(c, NARROW_WIDTH_DP, 40f, pct));
            sizes.put(new SizeF(110f, COMPACT_HEIGHT_DP), build(c, 110f, COMPACT_HEIGHT_DP, pct));
            sizes.put(new SizeF(NARROW_WIDTH_DP, COMPACT_HEIGHT_DP), build(c, NARROW_WIDTH_DP, COMPACT_HEIGHT_DP, pct));
            return new RemoteViews(sizes);
        }
        float w = options == null ? 250f : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250);
        float h = options == null ? 90f : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90);
        return build(c, w, h, pct);
    }

    /** One layout for a widget of the given size in dp. */
    private static RemoteViews build(Context c, float widthDp, float heightDp, int pct) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_status);
        v.setTextViewText(R.id.battery, batteryText(pct));
        int details = showDetails(heightDp) ? View.VISIBLE : View.GONE;
        v.setViewVisibility(R.id.header, details);
        v.setViewVisibility(R.id.date, details);
        v.setTextViewTextSize(R.id.time, TypedValue.COMPLEX_UNIT_SP, clockSp(widthDp, heightDp));
        v.setOnClickPendingIntent(R.id.root, openConsole(c));
        return v;
    }

    /** Header and date lines fit only when the widget is at least {@link #COMPACT_HEIGHT_DP} tall. */
    static boolean showDetails(float heightDp) { return heightDp >= COMPACT_HEIGHT_DP; }

    /** Clock text size in sp: 40 normally, smaller on narrow or short widgets. */
    static float clockSp(float widthDp, float heightDp) {
        float sp = 40f;
        if (widthDp < NARROW_WIDTH_DP) sp = 28f;
        if (heightDp < 56f) sp = Math.min(sp, 26f);
        return sp;
    }

    /** "PWR 73%", or "PWR --%" when unknown. */
    static String batteryText(int pct) {
        return "PWR " + (pct < 0 ? "--" : String.valueOf(pct)) + "%";
    }

    /** Battery percentage from BatteryManager, falling back to the sticky battery broadcast; -1 if unknown. */
    private static int batteryPercent(Context c) {
        Context app = c.getApplicationContext();
        BatteryManager bm = (BatteryManager) app.getSystemService(Context.BATTERY_SERVICE);
        int pct = bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : Integer.MIN_VALUE;
        if (pct >= 0 && pct <= 100) return pct;
        Intent bi = app.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return bi == null ? -1 : MainActivity.batteryPct(bi);
    }

    /** Immutable PendingIntent that opens console (explicit component). */
    private static PendingIntent openConsole(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
