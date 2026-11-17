package com.gungreeneyes.trekui;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ActivityNotFoundException;
import android.graphics.Color;
import android.os.Build;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A home panel that hosts any installed Android app widget (the owner's competitor research, gap A4). The widget
 * sits inside the usual {@link ConsoleCard}, so it gets the active interface's bracket (console "[",
 * Imperial bevel, Collective trace or Pipe material) and title strip, plus an outlined widget area.
 *
 * <p>Each panel is one slot of {@link WidgetSlots} with panel id {@code "appwidget<slot>"}; MainActivity
 * creates it from {@link MainActivity#createPanel} like the built-in panels, so it takes part in the
 * panel order, HIDE PANEL, CONFIG › PANELS and the reveal-hub info nodes. Widgets are added through
 * {@link WidgetPickActivity}; updates arrive through MainActivity's {@link WidgetHost}, which listens
 * between onStart and onStop. The widget area is sized by {@link WidgetSlots#heightDp} from the
 * window's {@link ScreenMetrics}, and the real laid-out size is reported to the provider every time it
 * changes (rotation, fold, multi-window, opening the card in the reveal hub).
 *
 * <p>When the widget cannot be shown (its app was uninstalled, the id is no longer bound, or the view
 * cannot be created) the card shows a WIDGET OFFLINE notice with a REMOVE key instead.
 */
final class WidgetPanel extends Panel {
    private static final String TAG = "WidgetPanel";
    /** Request code of RECONFIGURE (the result needs no handling: a cancel keeps the old setup). */
    static final int REQ_RECONFIGURE = 41;

    private final String panelId;
    private final int slot;
    private final int appWidgetId;
    private final AppWidgetProviderInfo info;
    private AppWidgetHostView hostView;
    private int reportedWidthDp, reportedHeightDp;
    /** Actions of {@link #extraMenu()}'s entries, in the same order (rebuilt with it). */
    private final List<Runnable> menuActions = new ArrayList<>();

    WidgetPanel(MainActivity a, int accent, String panelId) {
        super(a, accent);
        this.panelId = panelId;
        this.slot = WidgetSlots.slotOf(panelId);
        Integer id = WidgetSlots.load(a.prefs).get(slot);
        this.appWidgetId = id == null ? WidgetSlots.INVALID_ID : id;
        this.info = a.widgetHost == null ? null : a.widgetHost.info(appWidgetId);
    }

    @Override
    String id() {
        return panelId;
    }

    @Override
    String title() {
        String label = providerLabel();
        return label == null ? WidgetSlots.fallbackName(slot) : label.toUpperCase(Locale.getDefault());
    }

    /** The widget's own label ("Clock", "Gmail"), or null when the widget is offline. */
    private String providerLabel() {
        if (info == null) return null;
        try {
            CharSequence label = info.loadLabel(a.getPackageManager());
            return label == null || label.length() == 0 ? null : label.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    void build() {
        if (info == null) {
            offline(a.getString(R.string.widget_offline_missing));
            return;
        }
        try {
            hostView = a.widgetHost.createView(a, appWidgetId, info);
        } catch (RuntimeException e) {
            Log.w(TAG, "createView failed for slot " + slot, e);
            offline(a.getString(R.string.widget_offline_render));
            return;
        }
        hostView.setContentDescription(title());
        ScreenMetrics m = ScreenMetrics.of(a);
        float heightDp = WidgetSlots.heightDp(rows(), m.uiScale, m.heightDp);

        FrameLayout frame = new FrameLayout(a);
        frame.setTag("widget_panel_" + slot);
        frame.setBackground(Ui.outline(a, Ui.alpha(Skin.surface(), 0.35f), Ui.alpha(accent, 0.5f), Skin.trayRadius(10f), 1.2f));
        frame.setClipToOutline(true);
        int pad = Ui.dp(a, Ui.XS);
        frame.setPadding(pad, pad, pad, pad);
        frame.addView(hostView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> reportSize());
        card.body.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, m.px(heightDp)));
        card.status.setText(appLabel());
    }

    /** The widget's height in rows: the user's WIDGET SIZE choice, else derived from its minimum height. */
    private int rows() {
        int stored = WidgetSlots.storedRows(a.prefs, slot);
        if (stored > 0) return WidgetSlots.clampRows(stored);
        float density = a.getResources().getDisplayMetrics().density;
        return WidgetSlots.rowsFor(info.minHeight / density);
    }

    /** Reports the widget view's current size to its provider when it changed (dp, rounded). */
    private void reportSize() {
        if (hostView == null) return;
        int[] size = WidgetSlots.sizeDp(hostView.getWidth(), hostView.getHeight(), a.getResources().getDisplayMetrics().density);
        if (size[0] <= 0 || size[1] <= 0) return;
        if (size[0] == reportedWidthDp && size[1] == reportedHeightDp) return;
        reportedWidthDp = size[0];
        reportedHeightDp = size[1];
        // post: updateAppWidgetSize may trigger a provider update, which must not re-enter layout
        hostView.post(() -> WidgetHost.updateSize(hostView, reportedWidthDp, reportedHeightDp));
    }

    /** Name of the app that provides the widget, for the title strip's status text. */
    private String appLabel() {
        try {
            CharSequence label = a.getPackageManager()
                    .getApplicationInfo(info.provider.getPackageName(), 0).loadLabel(a.getPackageManager());
            return label.toString().toUpperCase(Locale.getDefault());
        } catch (Exception e) {
            return "";
        }
    }

    /** Fills the card with the WIDGET OFFLINE notice and a REMOVE key. */
    private void offline(String reason) {
        card.status.setText(a.getString(R.string.widget_offline));
        LinearLayout box = new LinearLayout(a);
        box.setTag("widget_panel_offline_" + slot);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(a, Ui.M);
        box.setPadding(pad, pad, pad, pad);
        box.setBackground(Ui.outline(a, Color.TRANSPARENT, Ui.alpha(accent, 0.45f), Skin.trayRadius(10f), 1.2f));
        TextView text = Ui.text(a, reason, 14f, pal.textLo);
        text.setGravity(Gravity.CENTER);
        box.addView(text, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView remove = Ui.button(a, a.getString(R.string.widget_remove), pal.c(3), Ui.PILL, v -> remove());
        remove.setGravity(Gravity.CENTER);
        remove.setContentDescription(a.getString(R.string.widget_remove_cd, WidgetSlots.fallbackName(slot)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(a, 140f), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(a, Ui.M);
        box.addView(remove, lp);
        card.body.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    String[] extraMenu() {
        menuActions.clear();
        List<String> labels = new ArrayList<>();
        if (info != null) {
            labels.add(a.getString(R.string.widget_size));
            menuActions.add(this::chooseSize);
            if (reconfigurable()) {
                labels.add(a.getString(R.string.widget_reconfigure));
                menuActions.add(this::reconfigure);
            }
        }
        labels.add(a.getString(R.string.widget_remove_panel));
        menuActions.add(this::confirmRemove);
        return labels.toArray(new String[0]);
    }

    @Override
    void onExtraMenu(int which) {
        if (which >= 0 && which < menuActions.size()) menuActions.get(which).run();
    }

    /** True when the provider has a configuration screen that may be opened again after placement. */
    private boolean reconfigurable() {
        if (info == null || info.configure == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return (info.widgetFeatures & AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE) != 0;
        }
        return true;
    }

    /** WIDGET SIZE: 1–5 rows; the choice is stored per slot and the home rebuilds. */
    private void chooseSize() {
        String[] items = new String[WidgetSlots.MAX_ROWS - WidgetSlots.MIN_ROWS + 1];
        for (int i = 0; i < items.length; i++) {
            items[i] = a.getString(R.string.widget_rows, WidgetSlots.MIN_ROWS + i);
        }
        a.showing(ConsoleDialog.options(a, a.getString(R.string.widget_size), items, which -> {
            a.prefs.put(WidgetSlots.KEY_ROWS + slot, WidgetSlots.MIN_ROWS + which);
            a.prefs.bump();
            Sfx.play(a, Sfx.CONFIRM);
            a.buildUi();
        }));
    }

    /** RECONFIGURE: reopens the provider's configuration screen for this widget. */
    private void reconfigure() {
        try {
            a.widgetHost.host.startAppWidgetConfigureActivityForResult(a, appWidgetId, 0, REQ_RECONFIGURE, null);
        } catch (ActivityNotFoundException | SecurityException e) {
            Sfx.play(a, Sfx.DENY);
            a.toast(a.getString(R.string.toast_no_app));
        }
    }

    /** REMOVE WIDGET from the menu: asks first, since the widget's own setup is lost. */
    private void confirmRemove() {
        a.showing(ConsoleDialog.message(a, a.getString(R.string.widget_remove_panel),
                a.getString(R.string.widget_remove_confirm, title()),
                new String[]{a.getString(R.string.widget_remove), a.getString(R.string.widget_cancel)}, which -> {
                    if (which == 0) remove();
                }));
    }

    /** Deletes the widget id and the slot, then rebuilds the home screen without this panel. */
    private void remove() {
        WidgetHost.removeSlot(a, slot);
        Sfx.play(a, Sfx.CONFIRM);
        a.toast(a.getString(R.string.toast_widget_removed));
        a.buildUi();
    }
}
