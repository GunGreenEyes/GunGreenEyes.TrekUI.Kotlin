package com.gungreeneyes.trekui

import android.app.Activity
import android.app.Dialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import java.text.Collator
import java.util.Locale

/**
 * The ADD WIDGET PANEL flow, as a translucent activity over the home screen or CONFIG:
 *  1. lists every home-screen widget of every profile (work profile included) in an console dialog;
 *  2. allocates a widget id and binds it with `bindAppWidgetIdIfAllowed`, falling back to the
 *     system's `ACTION_APPWIDGET_BIND` consent screen when this app is not yet allowed;
 *  3. runs the provider's configuration activity if it has one
 *     (`AppWidgetHost.startAppWidgetConfigureActivityForResult`, which also works for
 *     non-exported configure activities), skipping it when the provider marks it optional;
 *  4. stores the id in the next free [WidgetSlots] slot and finishes; the home screen sees the
 *     bumped layout version on resume and rebuilds with the new [WidgetPanel].
 *
 * Any cancel or failure deletes the allocated id, so no half-bound widget is left behind. The id in
 * progress survives recreation through the saved instance state; if the process dies for good mid-flow,
 * the next fresh start of this activity deletes the leftover id ([WidgetHost.deleteOrphans]).
 */
class WidgetPickActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private lateinit var manager: AppWidgetManager
    private lateinit var prefs: Prefs

    /** The widget id this flow is adding (INVALID when none). */
    private var pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var dialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        prefs.palette() // syncs Skin.faction so the dialog is drawn in the active interface
        host = AppWidgetHost(applicationContext, WidgetHost.HOST_ID)
        manager = AppWidgetManager.getInstance(this)
        if (savedInstanceState != null) {
            // recreated while bind/configure was in front: wait for its onActivityResult
            pendingId = savedInstanceState.getInt(STATE_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) return
        }
        WidgetHost.deleteOrphans(this)
        if (WidgetSlots.nextSlot(WidgetSlots.load(prefs).keys) < 0) {
            fail(getString(R.string.toast_widget_slots_full))
            return
        }
        showPicker()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(STATE_ID, pendingId)
    }

    override fun onDestroy() {
        dialog?.let { if (it.isShowing) it.dismiss() }
        super.onDestroy()
    }

    /** One pickable widget: its provider info and the label shown in the list. */
    private class Choice(val info: AppWidgetProviderInfo, val label: String)

    /** Shows every home-screen widget of every profile, sorted by app then widget name. */
    private fun showPicker() {
        val choices = loadChoices()
        if (choices.isEmpty()) {
            fail(getString(R.string.toast_no_widgets))
            return
        }
        val labels = Array(choices.size) { choices[it].label }
        val d = ConsoleDialog.options(this, getString(R.string.widget_pick_title), labels) { which -> bind(choices[which].info) }
        dialog = d
        d.setOnCancelListener { finish() }
    }

    /** Collects the installed home-screen widget providers of the user and their other profiles. */
    private fun loadChoices(): List<Choice> {
        val pm = packageManager
        val profiles = ArrayList<UserHandle>()
        val users = getSystemService(Context.USER_SERVICE) as UserManager?
        if (users != null) profiles.addAll(users.userProfiles)
        if (profiles.isEmpty()) profiles.add(Process.myUserHandle())
        val out = ArrayList<Choice>()
        for (profile in profiles) {
            val providers: List<AppWidgetProviderInfo> = try {
                manager.getInstalledProvidersForProfile(profile)
            } catch (e: RuntimeException) {
                continue
            }
            for (info in providers) {
                if ((info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) == 0) continue
                out.add(Choice(info, choiceLabel(pm, info)))
            }
        }
        val collator = Collator.getInstance(Locale.getDefault())
        out.sortWith { x, y -> collator.compare(x.label, y.label) }
        return out
    }

    /** Allocates an id and binds it to `info`, asking the user through the system when needed. */
    private fun bind(info: AppWidgetProviderInfo) {
        try {
            setPending(host.allocateAppWidgetId())
            if (manager.bindAppWidgetIdIfAllowed(pendingId, info.profile, info.provider, null)) {
                configure()
                return
            }
            val bind = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            startActivityForResult(bind, REQ_BIND)
        } catch (e: ActivityNotFoundException) {
            bindFailed(e)
        } catch (e: SecurityException) {
            bindFailed(e)
        } catch (e: IllegalArgumentException) {
            bindFailed(e)
        }
    }

    private fun bindFailed(e: RuntimeException) {
        Log.w(TAG, "bind failed", e)
        cancelPending(getString(R.string.toast_widget_failed))
    }

    /** Runs the provider's configuration screen when it has a mandatory one, else commits. */
    private fun configure() {
        val info = manager.getAppWidgetInfo(pendingId)
        if (info == null) {
            cancelPending(getString(R.string.toast_widget_failed))
            return
        }
        val optional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            (Api28.widgetFeatures(info) and FEATURE_CONFIGURATION_OPTIONAL) != 0
        if (info.configure == null || optional) {
            commit()
            return
        }
        try {
            host.startAppWidgetConfigureActivityForResult(this, pendingId, 0, REQ_CONFIGURE, null)
        } catch (e: ActivityNotFoundException) {
            configureFailed(e)
        } catch (e: SecurityException) {
            configureFailed(e)
        }
    }

    /** API 28 field reads, kept out of this class so ART on Android 6 never resolves them. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private object Api28 {
        @JvmStatic
        fun widgetFeatures(info: AppWidgetProviderInfo): Int = info.widgetFeatures
    }

    private fun configureFailed(e: RuntimeException) {
        Log.w(TAG, "configure failed", e)
        cancelPending(getString(R.string.toast_widget_failed))
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (pendingId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        if (resultCode != RESULT_OK) {
            cancelPending(null)
        } else if (requestCode == REQ_BIND) {
            configure()
        } else if (requestCode == REQ_CONFIGURE) {
            commit()
        }
    }

    /** Stores the bound widget in the next free slot and closes the flow. */
    private fun commit() {
        val slots = WidgetSlots.load(prefs)
        val slot = WidgetSlots.nextSlot(slots.keys)
        if (slot < 0) {
            cancelPending(getString(R.string.toast_widget_slots_full))
            return
        }
        slots[slot] = pendingId
        WidgetSlots.save(prefs, slots)
        setPending(AppWidgetManager.INVALID_APPWIDGET_ID)
        Sfx.play(this, Sfx.CONFIRM)
        Toast.makeText(this, getString(R.string.toast_widget_added), Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    /** Deletes the id being added (cancel or failure) and closes the flow. */
    private fun cancelPending(message: String?) {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            try {
                host.deleteAppWidgetId(pendingId)
            } catch (e: RuntimeException) {
                Log.w(TAG, "deleteAppWidgetId failed", e)
            }
            setPending(AppWidgetManager.INVALID_APPWIDGET_ID)
        }
        if (message != null) fail(message) else finish()
    }

    /** The widget id this flow is adding (INVALID when none). */
    private fun setPending(id: Int) {
        pendingId = id
    }

    /** Deny chirp, a toast and close. */
    private fun fail(message: String) {
        Sfx.play(this, Sfx.DENY)
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        private const val TAG = "WidgetPick"
        private const val REQ_BIND = 1
        private const val REQ_CONFIGURE = 2
        private const val STATE_ID = "pendingId"

        /** AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL (API 31). */
        private const val FEATURE_CONFIGURATION_OPTIONAL = 4

        /** Opens the add-widget flow from any activity. */
        @JvmStatic
        fun start(c: Context) {
            val i = Intent(c, WidgetPickActivity::class.java)
            if (c !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                c.startActivity(i)
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "picker missing", e)
            }
        }

        /** "APP • WIDGET" (just "WIDGET" when the two names match). */
        private fun choiceLabel(pm: PackageManager, info: AppWidgetProviderInfo): String {
            val widget: String = try {
                val label: CharSequence? = info.loadLabel(pm)
                label.toString()
            } catch (e: RuntimeException) {
                info.provider.shortClassName
            }
            val app: String = try {
                @Suppress("DEPRECATION")
                val label: CharSequence? = pm.getApplicationInfo(info.provider.packageName, 0).loadLabel(pm)
                label.toString()
            } catch (e: PackageManager.NameNotFoundException) {
                info.provider.packageName
            } catch (e: RuntimeException) {
                info.provider.packageName
            }
            val label = if (app.equals(widget, ignoreCase = true)) widget else "$app • $widget"
            return label.uppercase(Locale.getDefault())
        }
    }
}
