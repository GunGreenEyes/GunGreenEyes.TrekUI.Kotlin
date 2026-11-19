package com.gungreeneyes.trekui

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.util.Locale

/**
 * A home panel that hosts any installed Android app widget (the owner's competitor research, gap A4). The widget
 * sits inside the usual [ConsoleCard], so it gets the active interface's bracket (console "[",
 * Imperial bevel or Collective trace) and title strip, plus an outlined widget area.
 *
 * Each panel is one slot of [WidgetSlots] with panel id `"appwidget<slot>"`; MainActivity
 * creates it from `MainActivity.createPanel` like the built-in panels, so it takes part in the
 * panel order, HIDE PANEL, CONFIG › PANELS and the reveal-hub info nodes. Widgets are added through
 * [WidgetPickActivity]; updates arrive through MainActivity's [WidgetHost], which listens
 * between onStart and onStop. The widget area is sized by [WidgetSlots.heightDp] from the
 * window's [ScreenMetrics], and the real laid-out size is reported to the provider every time it
 * changes (rotation, fold, multi-window, opening the card in the reveal hub).
 *
 * When the widget cannot be shown (its app was uninstalled, the id is no longer bound, or the view
 * cannot be created) the card shows a WIDGET OFFLINE notice with a REMOVE key instead.
 */
internal class WidgetPanel(a: MainActivity, accent: Int, private val panelId: String) : Panel(a, accent) {

    private val slot: Int = WidgetSlots.slotOf(panelId)
    private val appWidgetId: Int = WidgetSlots.load(a.prefs)[slot] ?: WidgetSlots.INVALID_ID
    private val info: AppWidgetProviderInfo? = a.widgetHost?.info(appWidgetId)
    private var hostView: AppWidgetHostView? = null
    private var reportedWidthDp = 0
    private var reportedHeightDp = 0

    /** Actions of [extraMenu]'s entries, in the same order (rebuilt with it). */
    private val menuActions = ArrayList<Runnable>()

    override fun id(): String = panelId

    override fun title(): String {
        val label = providerLabel()
        return label?.uppercase(Locale.getDefault()) ?: WidgetSlots.fallbackName(slot)
    }

    /** The widget's own label ("Clock", "Gmail"), or null when the widget is offline. */
    private fun providerLabel(): String? {
        if (info == null) return null
        return try {
            val label: CharSequence? = info.loadLabel(a.packageManager)
            if (label.isNullOrEmpty()) null else label.toString()
        } catch (e: RuntimeException) {
            null
        }
    }

    override fun build() {
        val card = card!!
        if (info == null) {
            offline(a.getString(R.string.widget_offline_missing))
            return
        }
        val view: AppWidgetHostView = try {
            a.widgetHost.createView(a, appWidgetId, info)
        } catch (e: RuntimeException) {
            Log.w(TAG, "createView failed for slot $slot", e)
            offline(a.getString(R.string.widget_offline_render))
            return
        }
        hostView = view
        view.contentDescription = title()
        val m = ScreenMetrics.of(a)
        val heightDp = WidgetSlots.heightDp(rows(info), m.uiScale, m.heightDp)

        val frame = FrameLayout(a)
        frame.tag = "widget_panel_$slot"
        frame.background = Ui.outline(a, Ui.alpha(Skin.surface(), 0.35f), Ui.alpha(accent, 0.5f), Skin.trayRadius(10f), 1.2f)
        frame.clipToOutline = true
        val pad = Ui.dp(a, Ui.XS.toFloat())
        frame.setPadding(pad, pad, pad, pad)
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> reportSize() }
        card.body.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, m.px(heightDp)))
        card.status.text = appLabel(info)
    }

    /** The widget's height in rows: the user's WIDGET SIZE choice, else derived from its minimum height. */
    private fun rows(info: AppWidgetProviderInfo): Int {
        val stored = WidgetSlots.storedRows(a.prefs, slot)
        if (stored > 0) return WidgetSlots.clampRows(stored)
        val density = a.resources.displayMetrics.density
        return WidgetSlots.rowsFor(info.minHeight / density)
    }

    /** Reports the widget view's current size to its provider when it changed (dp, rounded). */
    private fun reportSize() {
        val view = hostView ?: return
        val size = WidgetSlots.sizeDp(view.width, view.height, a.resources.displayMetrics.density)
        if (size[0] <= 0 || size[1] <= 0) return
        if (size[0] == reportedWidthDp && size[1] == reportedHeightDp) return
        reportedWidthDp = size[0]
        reportedHeightDp = size[1]
        // post: updateAppWidgetSize may trigger a provider update, which must not re-enter layout
        view.post { WidgetHost.updateSize(hostView, reportedWidthDp, reportedHeightDp) }
    }

    /** Name of the app that provides the widget, for the title strip's status text. */
    private fun appLabel(info: AppWidgetProviderInfo): String =
        try {
            @Suppress("DEPRECATION")
            val label = a.packageManager.getApplicationInfo(info.provider.packageName, 0).loadLabel(a.packageManager)
            label.toString().uppercase(Locale.getDefault())
        } catch (e: Exception) {
            ""
        }

    /** Fills the card with the WIDGET OFFLINE notice and a REMOVE key. */
    private fun offline(reason: String) {
        val card = card!!
        card.status.text = a.getString(R.string.widget_offline)
        val box = LinearLayout(a)
        box.tag = "widget_panel_offline_$slot"
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        val pad = Ui.dp(a, Ui.M.toFloat())
        box.setPadding(pad, pad, pad, pad)
        box.background = Ui.outline(a, Color.TRANSPARENT, Ui.alpha(accent, 0.45f), Skin.trayRadius(10f), 1.2f)
        val text = Ui.text(a, reason, 14f, pal.textLo)
        text.gravity = Gravity.CENTER
        box.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val remove = Ui.button(a, a.getString(R.string.widget_remove), pal.c(3), Ui.PILL) { remove() }
        remove.gravity = Gravity.CENTER
        remove.contentDescription = a.getString(R.string.widget_remove_cd, WidgetSlots.fallbackName(slot))
        val lp = LinearLayout.LayoutParams(Ui.dp(a, 140f), ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = Ui.dp(a, Ui.M.toFloat())
        box.addView(remove, lp)
        card.body.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    override fun extraMenu(): Array<String> {
        menuActions.clear()
        val labels = ArrayList<String>()
        if (info != null) {
            labels.add(a.getString(R.string.widget_size))
            menuActions.add(Runnable { chooseSize() })
            if (reconfigurable()) {
                labels.add(a.getString(R.string.widget_reconfigure))
                menuActions.add(Runnable { reconfigure() })
            }
        }
        labels.add(a.getString(R.string.widget_remove_panel))
        menuActions.add(Runnable { confirmRemove() })
        return labels.toTypedArray()
    }

    override fun onExtraMenu(which: Int) {
        if (which >= 0 && which < menuActions.size) menuActions[which].run()
    }

    /** True when the provider has a configuration screen that may be opened again after placement. */
    private fun reconfigurable(): Boolean {
        if (info == null || info.configure == null) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return (Api28.widgetFeatures(info) and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0
        }
        return true
    }

    /** API 28 field reads, kept out of this class so ART on Android 6 never resolves them. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private object Api28 {
        @JvmStatic
        fun widgetFeatures(info: AppWidgetProviderInfo): Int = info.widgetFeatures
    }

    /** WIDGET SIZE: 1–5 rows; the choice is stored per slot and the home rebuilds. */
    private fun chooseSize() {
        val items = Array(WidgetSlots.MAX_ROWS - WidgetSlots.MIN_ROWS + 1) { i ->
            a.getString(R.string.widget_rows, WidgetSlots.MIN_ROWS + i)
        }
        a.showing(
            ConsoleDialog.options(a, a.getString(R.string.widget_size), items) { which ->
                a.prefs.put(WidgetSlots.KEY_ROWS + slot, WidgetSlots.MIN_ROWS + which)
                a.prefs.bump()
                Sfx.play(a, Sfx.CONFIRM)
                a.buildUi()
            },
        )
    }

    /** RECONFIGURE: reopens the provider's configuration screen for this widget. */
    private fun reconfigure() {
        try {
            a.widgetHost.host.startAppWidgetConfigureActivityForResult(a, appWidgetId, 0, REQ_RECONFIGURE, null)
        } catch (e: ActivityNotFoundException) {
            reconfigureFailed()
        } catch (e: SecurityException) {
            reconfigureFailed()
        }
    }

    private fun reconfigureFailed() {
        Sfx.play(a, Sfx.DENY)
        a.toast(a.getString(R.string.toast_no_app))
    }

    /** REMOVE WIDGET from the menu: asks first, since the widget's own setup is lost. */
    private fun confirmRemove() {
        a.showing(
            ConsoleDialog.message(
                a,
                a.getString(R.string.widget_remove_panel),
                a.getString(R.string.widget_remove_confirm, title()),
                arrayOf(a.getString(R.string.widget_remove), a.getString(R.string.widget_cancel)),
            ) { which ->
                if (which == 0) remove()
            },
        )
    }

    /** Deletes the widget id and the slot, then rebuilds the home screen without this panel. */
    private fun remove() {
        WidgetHost.removeSlot(a, slot)
        Sfx.play(a, Sfx.CONFIRM)
        a.toast(a.getString(R.string.toast_widget_removed))
        a.buildUi()
    }

    companion object {
        private const val TAG = "WidgetPanel"

        /** Request code of RECONFIGURE (the result needs no handling: a cancel keeps the old setup). */
        const val REQ_RECONFIGURE = 41
    }
}
