package com.gungreeneyes.trekui;

import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.util.SizeF;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The launcher's {@link AppWidgetHost}: one per {@link MainActivity} instance (created in its
 * {@code onCreate}, listening between {@code onStart} and {@code onStop}) plus short-lived instances
 * in {@link WidgetPickActivity} to allocate, bind and delete widget ids. All instances share
 * {@link #HOST_ID}, so a widget id allocated by the picker is shown by the home screen's host.
 *
 * <p>The host only holds the application context; the widget views themselves are created with the
 * activity context so they inherit its theme. Views it creates are {@link ConsoleHostView}s, which
 * replace Android's grey "Couldn't add widget" box with an console-styled offline notice.
 */
final class WidgetHost {
    private static final String TAG = "WidgetHost";
    /** Host id shared by every console host instance (any stable int unique within this app). */
    static final int HOST_ID = 0x4C43;

    final AppWidgetHost host;
    final AppWidgetManager manager;
    private boolean listening;

    WidgetHost(Context c) {
        Context app = c.getApplicationContext();
        host = new ConsoleAppWidgetHost(app, HOST_ID);
        manager = AppWidgetManager.getInstance(app);
    }

    /** Starts receiving widget updates (MainActivity.onStart). Never throws. */
    void start() {
        if (listening) return;
        try {
            host.startListening();
            listening = true;
        } catch (RuntimeException e) {
            // e.g. TransactionTooLargeException wrapped by a busy system server: widgets show stale content
            Log.w(TAG, "startListening failed", e);
        }
    }

    /** Stops receiving widget updates (MainActivity.onStop). Never throws. */
    void stop() {
        if (!listening) return;
        listening = false;
        try {
            host.stopListening();
        } catch (RuntimeException e) {
            Log.w(TAG, "stopListening failed", e);
        }
    }

    /** Provider info of a bound widget, or null when it is not bound or its app is gone. */
    AppWidgetProviderInfo info(int appWidgetId) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null;
        try {
            return manager.getAppWidgetInfo(appWidgetId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Creates the view for a bound widget with the activity's context. */
    AppWidgetHostView createView(Context activity, int appWidgetId, AppWidgetProviderInfo info) {
        return host.createView(activity, appWidgetId, info);
    }

    /**
     * Tells the provider how big its widget is now ({@code widthDp} × {@code heightDp}). From API 31
     * the exact size goes in as the single entry of the size list (so responsive {@code RemoteViews}
     * pick the right layout); earlier releases get the min/max dp quartet with both set to it.
     */
    static void updateSize(AppWidgetHostView view, int widthDp, int heightDp) {
        if (view == null || widthDp <= 0 || heightDp <= 0) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                List<SizeF> sizes = Collections.singletonList(new SizeF(widthDp, heightDp));
                view.updateAppWidgetSize(new Bundle(), sizes);
            } else {
                updateSizeLegacy(view, widthDp, heightDp);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "updateAppWidgetSize failed", e);
        }
    }

    /** API 26–30 size report: min and max both set to the actual size (portrait and landscape alike). */
    @SuppressWarnings("deprecation")
    private static void updateSizeLegacy(AppWidgetHostView view, int widthDp, int heightDp) {
        view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp);
    }

    /**
     * Removes a widget panel for good: deletes its widget id from the host (so the provider stops
     * updating it), drops the slot, its stored size and any hidden flag. The home screen rebuilds on
     * the next resume or when the caller rebuilds it.
     */
    static void removeSlot(Context c, int slot) {
        Prefs prefs = Prefs.get(c);
        Map<Integer, Integer> slots = WidgetSlots.load(prefs);
        Integer appWidgetId = slots.remove(slot);
        if (appWidgetId != null) {
            try {
                new AppWidgetHost(c.getApplicationContext(), HOST_ID).deleteAppWidgetId(appWidgetId);
            } catch (RuntimeException e) {
                Log.w(TAG, "deleteAppWidgetId failed", e);
            }
        }
        prefs.remove(WidgetSlots.KEY_ROWS + slot);
        String panelId = WidgetSlots.panelId(slot);
        if (prefs.hiddenWidgets().contains(panelId)) prefs.setWidgetHidden(panelId, false);
        WidgetSlots.save(prefs, slots);
    }

    /**
     * Deletes widget ids this host still owns that no slot uses (an add flow killed half-way, or a
     * panel removed while the system was unreachable). Needs {@code AppWidgetHost.getAppWidgetIds}
     * (API 26, our minimum). Only called when no add flow is in progress (a fresh
     * {@link WidgetPickActivity}), so every unreferenced id is safe to delete.
     */
    static void deleteOrphans(Context c) {
        Prefs prefs = Prefs.get(c);
        try {
            AppWidgetHost h = new AppWidgetHost(c.getApplicationContext(), HOST_ID);
            for (int id : WidgetSlots.orphans(h.getAppWidgetIds(), WidgetSlots.load(prefs).values(), WidgetSlots.INVALID_ID)) {
                h.deleteAppWidgetId(id);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "orphan cleanup failed", e);
        }
    }

    /** The host: creates {@link ConsoleHostView}s instead of plain AppWidgetHostViews. */
    private static final class ConsoleAppWidgetHost extends AppWidgetHost {
        ConsoleAppWidgetHost(Context c, int hostId) {
            super(c, hostId);
        }

        @Override
        protected AppWidgetHostView onCreateView(Context context, int appWidgetId, AppWidgetProviderInfo appWidget) {
            return new ConsoleHostView(context);
        }
    }

    /**
     * A widget view whose error state ("the provider's RemoteViews could not be inflated") is an console
     * notice instead of Android's grey box. The panel's long-press menu still offers REMOVE WIDGET.
     */
    static final class ConsoleHostView extends AppWidgetHostView {
        ConsoleHostView(Context context) {
            super(context);
        }

        @Override
        protected View getErrorView() {
            Palette pal = Prefs.get(getContext()).palette();
            TextView v = Ui.text(getContext(), getContext().getString(R.string.widget_offline_render), 14f, pal.textLo);
            v.setGravity(Gravity.CENTER);
            return v;
        }
    }
}
