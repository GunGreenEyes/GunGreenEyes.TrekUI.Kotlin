package com.gungreeneyes.trekui.lockscreen;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.gungreeneyes.trekui.LockLook;
import com.gungreeneyes.trekui.ScreenMetrics;
import com.gungreeneyes.trekui.TrekPrefs;
import com.gungreeneyes.trekui.licensing.Licensing;
import com.gungreeneyes.trekui.lockscreen.logic.EnableRule;

/**
 * The app's main screen (its launcher icon): setup and settings for the wake screen, drawn in the active
 * interface's console frame.
 *
 * <p>Rows: WAKE SCREEN (the app's own switch, with the launcher's SUITE › LOCK SCREEN state explained),
 * NOTIFICATION ACCESS (setup flow: a prominent disclosure, then Android's notification-access screen),
 * DISPLAY OVER APPS (Android 10+: lets the listener start the wake screen from the background), SENSITIVE
 * CONTENT, INTERFACE / COLOR THEME (own picker only while the launcher is absent), PREVIEW and the licence.
 * Licensing runs here ({@link Licensing#init} / {@link Licensing#onAppResumed}), never on the wake screen.
 */
public final class SettingsActivity extends ComponentActivity {
    private static final float SIDEBAR_DP = 84f;

    private FrameLayout root;
    private ScreenMetrics metrics;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        Licensing.init(this);
        rebuild();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Licensing.onAppResumed(this);
        LockTheme.refresh(this, this::rebuild);
        LockSettings.refreshLauncherFlag(this, this::rebuild);
        LockListener.refreshNow();
        rebuild();
        if (!LockSettings.hasNotificationAccess(this) && !LockSettings.setupSeen(this)) {
            LockSettings.markSetupSeen(this);
            showAccessDisclosure();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rebuild();
    }

    /** Rebuilds the whole screen from the current state (cheap: a handful of rows). */
    private void rebuild() {
        if (isFinishing() || isDestroyed()) return;
        LockLook.load(this);
        metrics = ScreenMetrics.of(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(LockLook.bg());
        root.setOnApplyWindowInsetsListener((view, raw) -> {
            WindowInsetsCompat w = WindowInsetsCompat.toWindowInsetsCompat(raw, view);
            Insets bars = w.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            int side = metrics.isTwoPane() ? metrics.marginPx() : LockLook.dp(this, 8f);
            root.setPadding(bars.left + side, bars.top + LockLook.dp(this, 4f), bars.right + side,
                    bars.bottom + LockLook.dp(this, 6f));
            return raw;
        });

        TextView title = LockLook.oneLine(LockLook.bold(this, getString(R.string.settings_title), 28f, LockLook.accent(0)));
        title.setPadding(LockLook.dp(this, 4f), 0, LockLook.dp(this, 2f), 0);
        TextView footer = LockLook.oneLine(LockLook.text(this, getString(R.string.settings_footer, version()), 14f,
                LockLook.accent(1)));
        footer.setPadding(LockLook.dp(this, 4f), 0, LockLook.dp(this, 2f), 0);
        ViewGroup frame = LockLook.frame(this, title, footer, sidebar(), content(), SIDEBAR_DP);
        root.addView(frame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.requestApplyInsets();
    }

    private View sidebar() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        addSide(column, getString(R.string.settings_preview), 2, "side_preview", v -> openPreview());
        addSide(column, getString(R.string.settings_setup), 3, "side_setup", v -> showAccessDisclosure());
        addSide(column, getString(R.string.settings_unlock), 4, "side_unlock", v -> Licensing.openPurchaseScreen(this));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(column);
        return scroll;
    }

    private void addSide(LinearLayout column, String label, int accent, String tag, View.OnClickListener l) {
        int color = LockLook.accent(accent);
        TextView v = LockLook.button(this, label, color, LockLook.SQUARE, l);
        v.setBackground(LockLook.touchCell(this, color, LockLook.SQUARE, 0f));
        LockLook.setTextSp(v, 14f);
        v.setPadding(LockLook.dp(this, 4f), 0, LockLook.dp(this, 8f), LockLook.dp(this, 4f));
        v.setTag(tag);
        v.setContentDescription(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                LockLook.dp(this, 50f));
        if (column.getChildCount() > 0) lp.topMargin = LockLook.dp(this, LockLook.GUTTER);
        column.addView(v, lp);
    }

    // ---- rows ---------------------------------------------------------------------------------

    private View content() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(LockLook.dp(this, 4f), LockLook.dp(this, 4f), LockLook.dp(this, 4f), LockLook.dp(this, 12f));

        boolean own = LockSettings.wakeEnabled(this);
        row(list, "row_wake", getString(R.string.row_wake), stateText(LockSettings.state(this)),
                getString(own ? R.string.action_turn_off : R.string.action_turn_on), 0, v -> {
                    LockSettings.setWakeEnabled(this, !own);
                    LockListener.resetTrigger();
                    rebuild();
                });

        boolean access = LockSettings.hasNotificationAccess(this);
        row(list, "row_access", getString(R.string.row_access),
                getString(access ? R.string.access_on : R.string.access_off),
                getString(access ? R.string.action_manage : R.string.action_set_up), 1,
                v -> {
                    if (access) openAccessSettings();
                    else showAccessDisclosure();
                });

        if (Build.VERSION.SDK_INT >= 29) {
            boolean overlay = LockSettings.canDrawOverlays(this);
            row(list, "row_overlay", getString(R.string.row_overlay),
                    getString(overlay ? R.string.overlay_on : R.string.overlay_off),
                    getString(overlay ? R.string.action_manage : R.string.action_allow), 2, v -> openOverlaySettings());
        }

        boolean sensitive = LockSettings.showSensitive(this);
        row(list, "row_sensitive", getString(R.string.row_sensitive),
                getString(sensitive ? R.string.sensitive_on : R.string.sensitive_off),
                getString(sensitive ? R.string.action_hide : R.string.action_show), 3, v -> {
                    LockSettings.setShowSensitive(this, !sensitive);
                    LockListener.refreshNow();
                    rebuild();
                });

        int faction = TrekPrefs.get(this).faction();
        String[] names = LockLook.interfaceNames();
        String current = names[Math.max(0, Math.min(names.length - 1, faction))];
        if (LockTheme.followingLauncher()) {
            row(list, "row_interface", getString(R.string.row_interface),
                    getString(R.string.interface_following, current), null, 4, null);
        } else {
            row(list, "row_interface", getString(R.string.row_interface), current,
                    getString(R.string.action_change), 4, v -> LockLook.options(this,
                            getString(R.string.row_interface), names, which -> {
                                TrekPrefs.get(this).setFaction(which);
                                rebuild();
                            }));
            if (LockLook.hasSubThemes(faction)) {
                String[] subs = LockLook.subThemeNames(faction);
                int sub = TrekPrefs.get(this).subTheme(faction);
                row(list, "row_color", getString(R.string.row_color), subs[Math.max(0, Math.min(subs.length - 1, sub))],
                        getString(R.string.action_change), 5, v -> LockLook.options(this,
                                getString(R.string.row_color), subs, which -> {
                                    TrekPrefs.get(this).setSubTheme(faction, which);
                                    rebuild();
                                }));
            }
        }

        boolean unlocked = Licensing.isUnlocked(this);
        row(list, "row_licence", getString(R.string.row_licence),
                getString(unlocked ? R.string.licence_unlocked : R.string.licence_trial),
                unlocked ? null : getString(R.string.settings_unlock), 6, v -> Licensing.openPurchaseScreen(this));

        TextView note = LockLook.text(this, getString(R.string.settings_security_note), 14f, LockLook.textLo());
        note.setPadding(0, LockLook.dp(this, 12f), 0, 0);
        list.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(list);
        return scroll;
    }

    /** One setting: a title, a status line and an optional action key on the right. */
    private void row(LinearLayout list, String tag, String title, String status, String action, int accent,
                     View.OnClickListener l) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(tag);
        int pad = LockLook.dp(this, 10f);
        row.setPadding(pad, pad, pad, pad);
        row.setBackground(LockLook.outline(this, LockLook.surface(), LockLook.grid(), 8f, 1f));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(LockLook.oneLine(LockLook.bold(this, LockLook.up(this, title), 17f, LockLook.accent(accent))));
        TextView st = LockLook.text(this, status, 14f, LockLook.textHi());
        st.setLineSpacing(0, 1.1f);
        texts.addView(st);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (action != null && l != null) {
            int color = LockLook.accent(accent);
            TextView b = LockLook.button(this, LockLook.up(this, action), color, LockLook.PILL, l);
            LockLook.setTextSp(b, 14f);
            b.setTag(tag + "_action");
            b.setContentDescription(title + ": " + action);
            b.setMinWidth(LockLook.dp(this, 96f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    LockLook.dp(this, LockLook.MIN_TOUCH_DP));
            lp.leftMargin = LockLook.dp(this, 10f);
            row.addView(b, lp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = LockLook.dp(this, LockLook.S);
        list.addView(row, lp);
    }

    private String stateText(EnableRule.State s) {
        switch (s) {
            case ACTIVE:
                return getString(Build.VERSION.SDK_INT >= 29 && !LockSettings.canDrawOverlays(this)
                        ? R.string.state_active_needs_overlay : R.string.state_active);
            case OFF_IN_LAUNCHER:
                return getString(R.string.state_off_launcher);
            case NEEDS_ACCESS:
                return getString(R.string.state_needs_access);
            case OFF_IN_APP:
            default:
                return getString(R.string.state_off_app);
        }
    }

    // ---- setup flow ---------------------------------------------------------------------------

    /** The prominent disclosure shown before Android's notification-access screen. */
    private void showAccessDisclosure() {
        LockLook.message(this, getString(R.string.disclosure_title), getString(R.string.disclosure_body),
                new String[]{getString(R.string.disclosure_agree), getString(R.string.disclosure_decline)}, which -> {
                    if (which == 0) openAccessSettings();
                });
    }

    private void openAccessSettings() {
        ComponentName cn = new ComponentName(this, LockListener.class);
        if (Build.VERSION.SDK_INT >= 30) {
            Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString());
            if (tryStart(detail)) return;
        }
        tryStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    private void openOverlaySettings() {
        tryStart(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    private void openPreview() {
        LockListener.resetTrigger();
        WakeActivity.preview(this);
    }

    private boolean tryStart(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private String version() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return getPackageManager().getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0)).versionName;
            }
            return legacyVersion();
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    private String legacyVersion() throws PackageManager.NameNotFoundException {
        return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    }
}
