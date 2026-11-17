package com.gungreeneyes.trekui;

import android.app.Activity;
import android.app.Dialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The ADD WIDGET PANEL flow, as a translucent activity over the home screen or CONFIG:
 * <ol>
 *   <li>lists every home-screen widget of every profile (work profile included) in an console dialog;</li>
 *   <li>allocates a widget id and binds it with {@code bindAppWidgetIdIfAllowed}, falling back to the
 *       system's {@code ACTION_APPWIDGET_BIND} consent screen when this app is not yet allowed;</li>
 *   <li>runs the provider's configuration activity if it has one
 *       ({@code AppWidgetHost.startAppWidgetConfigureActivityForResult}, which also works for
 *       non-exported configure activities), skipping it when the provider marks it optional;</li>
 *   <li>stores the id in the next free {@link WidgetSlots} slot and finishes; the home screen sees the
 *       bumped layout version on resume and rebuilds with the new {@link WidgetPanel}.</li>
 * </ol>
 * Any cancel or failure deletes the allocated id, so no half-bound widget is left behind. The id in
 * progress survives recreation through the saved instance state; if the process dies for good mid-flow,
 * the next fresh start of this activity deletes the leftover id ({@link WidgetHost#deleteOrphans}).
 */
public class WidgetPickActivity extends Activity {
    private static final String TAG = "WidgetPick";
    private static final int REQ_BIND = 1;
    private static final int REQ_CONFIGURE = 2;
    private static final String STATE_ID = "pendingId";
    /** AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL (API 31). */
    private static final int FEATURE_CONFIGURATION_OPTIONAL = 4;

    private AppWidgetHost host;
    private AppWidgetManager manager;
    private Prefs prefs;
    private int pendingId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private Dialog dialog;

    /** Opens the add-widget flow from any activity. */
    static void start(Context c) {
        Intent i = new Intent(c, WidgetPickActivity.class);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "picker missing", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        prefs.palette(); // syncs Skin.faction so the dialog is drawn in the active interface
        host = new AppWidgetHost(getApplicationContext(), WidgetHost.HOST_ID);
        manager = AppWidgetManager.getInstance(this);
        if (savedInstanceState != null) {
            // recreated while bind/configure was in front: wait for its onActivityResult
            pendingId = savedInstanceState.getInt(STATE_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
            if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) return;
        }
        WidgetHost.deleteOrphans(this);
        if (WidgetSlots.nextSlot(WidgetSlots.load(prefs).keySet()) < 0) {
            fail(getString(R.string.toast_widget_slots_full));
            return;
        }
        showPicker();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_ID, pendingId);
    }

    @Override
    protected void onDestroy() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        super.onDestroy();
    }

    /** One pickable widget: its provider info and the label shown in the list. */
    private static final class Choice {
        final AppWidgetProviderInfo info;
        final String label;

        Choice(AppWidgetProviderInfo info, String label) {
            this.info = info;
            this.label = label;
        }
    }

    /** Shows every home-screen widget of every profile, sorted by app then widget name. */
    private void showPicker() {
        List<Choice> choices = loadChoices();
        if (choices.isEmpty()) {
            fail(getString(R.string.toast_no_widgets));
            return;
        }
        String[] labels = new String[choices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = choices.get(i).label;
        dialog = ConsoleDialog.options(this, getString(R.string.widget_pick_title), labels, which -> bind(choices.get(which).info));
        dialog.setOnCancelListener(d -> finish());
    }

    /** Collects the installed home-screen widget providers of the user and their other profiles. */
    private List<Choice> loadChoices() {
        PackageManager pm = getPackageManager();
        List<UserHandle> profiles = new ArrayList<>();
        UserManager users = (UserManager) getSystemService(Context.USER_SERVICE);
        if (users != null) profiles.addAll(users.getUserProfiles());
        if (profiles.isEmpty()) profiles.add(android.os.Process.myUserHandle());
        List<Choice> out = new ArrayList<>();
        for (UserHandle profile : profiles) {
            List<AppWidgetProviderInfo> providers;
            try {
                providers = manager.getInstalledProvidersForProfile(profile);
            } catch (RuntimeException e) {
                continue;
            }
            for (AppWidgetProviderInfo info : providers) {
                if ((info.widgetCategory & AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN) == 0) continue;
                out.add(new Choice(info, choiceLabel(pm, info)));
            }
        }
        final Collator collator = Collator.getInstance(Locale.getDefault());
        Collections.sort(out, (x, y) -> collator.compare(x.label, y.label));
        return out;
    }

    /** "APP • WIDGET" (just "WIDGET" when the two names match). */
    private static String choiceLabel(PackageManager pm, AppWidgetProviderInfo info) {
        String widget;
        try {
            widget = String.valueOf(info.loadLabel(pm));
        } catch (RuntimeException e) {
            widget = info.provider.getShortClassName();
        }
        String app;
        try {
            app = String.valueOf(pm.getApplicationInfo(info.provider.getPackageName(), 0).loadLabel(pm));
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            app = info.provider.getPackageName();
        }
        String label = app.equalsIgnoreCase(widget) ? widget : app + " • " + widget;
        return label.toUpperCase(Locale.getDefault());
    }

    /** Allocates an id and binds it to {@code info}, asking the user through the system when needed. */
    private void bind(AppWidgetProviderInfo info) {
        try {
            setPending(host.allocateAppWidgetId());
            if (manager.bindAppWidgetIdIfAllowed(pendingId, info.getProfile(), info.provider, null)) {
                configure();
                return;
            }
            Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingId)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile());
            startActivityForResult(bind, REQ_BIND);
        } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "bind failed", e);
            cancelPending(getString(R.string.toast_widget_failed));
        }
    }

    /** Runs the provider's configuration screen when it has a mandatory one, else commits. */
    private void configure() {
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(pendingId);
        if (info == null) {
            cancelPending(getString(R.string.toast_widget_failed));
            return;
        }
        boolean optional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (info.widgetFeatures & FEATURE_CONFIGURATION_OPTIONAL) != 0;
        if (info.configure == null || optional) {
            commit();
            return;
        }
        try {
            host.startAppWidgetConfigureActivityForResult(this, pendingId, 0, REQ_CONFIGURE, null);
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "configure failed", e);
            cancelPending(getString(R.string.toast_widget_failed));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (pendingId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        if (resultCode != RESULT_OK) {
            cancelPending(null);
        } else if (requestCode == REQ_BIND) {
            configure();
        } else if (requestCode == REQ_CONFIGURE) {
            commit();
        }
    }

    /** Stores the bound widget in the next free slot and closes the flow. */
    private void commit() {
        Map<Integer, Integer> slots = WidgetSlots.load(prefs);
        int slot = WidgetSlots.nextSlot(slots.keySet());
        if (slot < 0) {
            cancelPending(getString(R.string.toast_widget_slots_full));
            return;
        }
        slots.put(slot, pendingId);
        WidgetSlots.save(prefs, slots);
        setPending(AppWidgetManager.INVALID_APPWIDGET_ID);
        Sfx.play(this, Sfx.CONFIRM);
        Toast.makeText(this, getString(R.string.toast_widget_added), Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    /** Deletes the id being added (cancel or failure) and closes the flow. */
    private void cancelPending(String message) {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            try {
                host.deleteAppWidgetId(pendingId);
            } catch (RuntimeException e) {
                Log.w(TAG, "deleteAppWidgetId failed", e);
            }
            setPending(AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (message != null) fail(message);
        else finish();
    }

    /** The widget id this flow is adding (INVALID when none). */
    private void setPending(int id) {
        pendingId = id;
    }

    /** Deny chirp, a toast and close. */
    private void fail(String message) {
        Sfx.play(this, Sfx.DENY);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finish();
    }
}
