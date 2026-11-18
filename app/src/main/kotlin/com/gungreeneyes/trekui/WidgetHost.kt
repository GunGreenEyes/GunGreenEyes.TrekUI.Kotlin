package com.gungreeneyes.trekui

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.Gravity
import android.view.View

/**
 * The launcher's [AppWidgetHost]: one per [MainActivity] instance (created in its
 * `onCreate`, listening between `onStart` and `onStop`) plus short-lived instances
 * in [WidgetPickActivity] to allocate, bind and delete widget ids. All instances share
 * [HOST_ID], so a widget id allocated by the picker is shown by the home screen's host.
 *
 * The host only holds the application context; the widget views themselves are created with the
 * activity context so they inherit its theme. Views it creates are [ConsoleHostView]s, which
 * replace Android's grey "Couldn't add widget" box with an console-styled offline notice.
 */
internal class WidgetHost(c: Context) {

    @JvmField
    val host: AppWidgetHost

    @JvmField
    val manager: AppWidgetManager

    private var listening = false

    init {
        val app = c.applicationContext
        host = ConsoleAppWidgetHost(app, HOST_ID)
        manager = AppWidgetManager.getInstance(app)
    }

    /** Starts receiving widget updates (MainActivity.onStart). Never throws. */
    fun start() {
        if (listening) return
        try {
            host.startListening()
            listening = true
        } catch (e: RuntimeException) {
            // e.g. TransactionTooLargeException wrapped by a busy system server: widgets show stale content
            Log.w(TAG, "startListening failed", e)
        }
    }

    /** Stops receiving widget updates (MainActivity.onStop). Never throws. */
    fun stop() {
        if (!listening) return
        listening = false
        try {
            host.stopListening()
        } catch (e: RuntimeException) {
            Log.w(TAG, "stopListening failed", e)
        }
    }

    /** Provider info of a bound widget, or null when it is not bound or its app is gone. */
    fun info(appWidgetId: Int): AppWidgetProviderInfo? {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        return try {
            manager.getAppWidgetInfo(appWidgetId)
        } catch (e: RuntimeException) {
            null
        }
    }

    /** Creates the view for a bound widget with the activity's context. */
    fun createView(activity: Context, appWidgetId: Int, info: AppWidgetProviderInfo?): AppWidgetHostView =
        host.createView(activity, appWidgetId, info)

    /** The host: creates [ConsoleHostView]s instead of plain AppWidgetHostViews. */
    private class ConsoleAppWidgetHost(c: Context, hostId: Int) : AppWidgetHost(c, hostId) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            ConsoleHostView(context)
    }

    /**
     * A widget view whose error state ("the provider's RemoteViews could not be inflated") is an console
     * notice instead of Android's grey box. The panel's long-press menu still offers REMOVE WIDGET.
     */
    class ConsoleHostView(context: Context) : AppWidgetHostView(context) {
        override fun getErrorView(): View {
            val pal = Prefs.get(context).palette()
            val v = Ui.text(context, context.getString(R.string.widget_offline_render), 14f, pal.textLo)
            v.gravity = Gravity.CENTER
            return v
        }
    }

    companion object {
        private const val TAG = "WidgetHost"

        /** Host id shared by every console host instance (any stable int unique within this app). */
        const val HOST_ID = 0x4C43

        /**
         * Tells the provider how big its widget is now (`widthDp` × `heightDp`). From API 31
         * the exact size goes in as the single entry of the size list (so responsive `RemoteViews`
         * pick the right layout); earlier releases get the min/max dp quartet with both set to it.
         */
        @JvmStatic
        fun updateSize(view: AppWidgetHostView?, widthDp: Int, heightDp: Int) {
            if (view == null || widthDp <= 0 || heightDp <= 0) return
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val sizes = listOf(SizeF(widthDp.toFloat(), heightDp.toFloat()))
                    view.updateAppWidgetSize(Bundle(), sizes)
                } else {
                    updateSizeLegacy(view, widthDp, heightDp)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "updateAppWidgetSize failed", e)
            }
        }

        /** API 26–30 size report: min and max both set to the actual size (portrait and landscape alike). */
        @Suppress("DEPRECATION")
        private fun updateSizeLegacy(view: AppWidgetHostView, widthDp: Int, heightDp: Int) {
            view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
        }

        /**
         * Removes a widget panel for good: deletes its widget id from the host (so the provider stops
         * updating it), drops the slot, its stored size and any hidden flag. The home screen rebuilds on
         * the next resume or when the caller rebuilds it.
         */
        @JvmStatic
        fun removeSlot(c: Context, slot: Int) {
            val prefs = Prefs.get(c)
            val slots = WidgetSlots.load(prefs)
            val appWidgetId = slots.remove(slot)
            if (appWidgetId != null) {
                try {
                    AppWidgetHost(c.applicationContext, HOST_ID).deleteAppWidgetId(appWidgetId)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "deleteAppWidgetId failed", e)
                }
            }
            prefs.remove(WidgetSlots.KEY_ROWS + slot)
            val panelId = WidgetSlots.panelId(slot)
            if (prefs.hiddenWidgets().contains(panelId)) prefs.setWidgetHidden(panelId, false)
            WidgetSlots.save(prefs, slots)
        }

        /**
         * Deletes widget ids this host still owns that no slot uses (an add flow killed half-way, or a
         * panel removed while the system was unreachable). Needs `AppWidgetHost.getAppWidgetIds`
         * (API 26, our minimum). Only called when no add flow is in progress (a fresh
         * [WidgetPickActivity]), so every unreferenced id is safe to delete.
         */
        @JvmStatic
        fun deleteOrphans(c: Context) {
            val prefs = Prefs.get(c)
            try {
                val h = AppWidgetHost(c.applicationContext, HOST_ID)
                for (id in WidgetSlots.orphans(h.appWidgetIds, WidgetSlots.load(prefs).values, WidgetSlots.INVALID_ID)) {
                    h.deleteAppWidgetId(id)
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "orphan cleanup failed", e)
            }
        }
    }
}
