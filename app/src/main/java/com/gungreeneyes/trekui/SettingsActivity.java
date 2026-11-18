package com.gungreeneyes.trekui;

import android.app.Activity;
import android.app.Dialog;
import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationManagerCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import com.gungreeneyes.trekui.core.SuiteContract;
import com.gungreeneyes.trekui.core.SuiteProvider;
import com.gungreeneyes.trekui.licensing.Licensing;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CONFIG: the launcher's settings screen, opened from the home sidebar or any slot's long-press menu.
 *
 * <p>One long scrolling list inside an console frame, with a sidebar of section shortcuts. Every change is
 * written straight to {@link Prefs}; {@link #changed()} bumps the preference version (so the home screen
 * rebuilds when it resumes) and rebuilds this list in place, keeping the scroll position.
 *
 * <p>Sections, in order: default home (with UNLOCK console while unlicensed), interface (family, interface, colour theme, layout, background,
 * wallpapers), suite (companion apps), notification shade, app theming, smart devices (Govee, Nest), audio (effects, phone sounds,
 * library), chronometer, home layout, app drawer, weather, interface &amp; access (animations, cloak,
 * haptics, permissions) and about.
 *
 * <p>Background work (sound import, gallery export, wallpaper, Govee/Nest network calls) runs on worker
 * threads and reports back through {@link #runOnUiIfAlive(Runnable)}, so a result that arrives after the
 * user left CONFIG is dropped instead of crashing on a dead window.
 *
 * <p>Edge-to-edge like home; back uses the system (predictive) back animation and plays the close chirp
 * from {@link #finish()}.
 */
public class SettingsActivity extends Activity {

    static final String PKG_CHROME = "com.android.chrome";
    static final String PKG_OUTLOOK = "com.microsoft.office.outlook";
    static final String PKG_SIGNAL = "org.thoughtcrime.securesms";
    private static final String PKG_GOOD_LOCK = "com.samsung.android.goodlock";

    // ---- activity request codes ----------------------------------------------------------------------
    private static final int REQ_IMPORT_FILES = 300;
    private static final int REQ_IMPORT_ZIP = 301;
    /** REQ_ASSIGN + event index: import a file for one sound event. */
    private static final int REQ_ASSIGN = 310;
    private static final int REQ_LIB_FILES = 320;
    private static final int REQ_LIB_ZIP = 321;

    private static final String MIME_AUDIO = "audio/*";
    private static final String MIME_ZIP = "application/zip";
    private static final String IMPORT_CACHE_DIR = "trekui_import";

    private static final String[] WALLPAPERS = {"command", "minimal", "stellar"};
    private static final String[] WALLPAPER_NAMES = {"COMMAND", "MINIMAL", "STELLAR"};
    private static final int THUMB_SAMPLE = 8;
    /** Decoded asset thumbnails; bitmaps only (no Activity reference), so a static cache is safe. */
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<>(24);
    /** Sound-library groups the user has expanded (kept for the process lifetime). */
    private static final Set<String> openSoundGroups = new HashSet<>();

    /** Accent colours of the interface cards (Command, Imperial, Collective, then the pipe materials). */
    private static final int COMMAND_ACCENT = 0xFFF0B279, IMPERIAL_ACCENT = 0xFFE6A15C, COLLECTIVE_ACCENT = 0xFF84D88E;
    /** Interface-card accents of the 23rd-century (console amber) and 25th-century (fleet steel blue) eras. */
    private static final int ERA23_ACCENT = 0xFFF2C230, ERA25_ACCENT = 0xFF8FB4D8;
    private static final int PIPE_BRASS = 0xFFC9A04E, PIPE_GILT = 0xFFD4AF37, PIPE_KRAFT = 0xFFC9A063;

    private final List<Anchor> anchors = new ArrayList<>();
    private int insetTop, insetBottom, insetLeft, insetRight;
    private LinearLayout list;
    private Palette pal;
    private Prefs prefs;
    private MediaPlayer previewPlayer;
    private FrameLayout root;
    private ScrollView scroll;
    private ScreenMetrics metrics;
    /** Set before finishing for a reason other than "back" (no close chirp). */
    private boolean quietFinish;

    /** A sidebar shortcut: label and the section header it scrolls to. */
    private static final class Anchor {
        final String label;
        final View view;

        Anchor(String label, View view) {
            this.label = label;
            this.view = view;
        }
    }

    /** Selection callback for {@link #choice}. */
    interface Choice {
        void chose(int index);
    }

    /** Callback for {@link #toggle}. */
    interface Toggle {
        void set(boolean on);
    }

    // =================================================================================================
    // Lifecycle
    // =================================================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = Prefs.get(this);
        MainActivity.setupEdgeToEdge(this);
        if (Build.VERSION.SDK_INT >= 34 && Ui.animate(this)) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out);
        }
        Sfx.init(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    /** Rotation, resize, fold/unfold: rebuild for the new window, keeping the scroll position. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        rebuild();
    }

    /**
     * Back (classic or predictive) and every other way of leaving end here: play the close chirp and,
     * before API 34, the fade transition (API 34+ set it once in {@link #onCreate}).
     */
    @SuppressWarnings("deprecation")
    @Override
    public void finish() {
        if (!quietFinish) Sfx.play(this, Sfx.CLOSE);
        super.finish();
        if (Build.VERSION.SDK_INT < 34 && Ui.animate(this)) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    @Override
    protected void onDestroy() {
        releasePreview();
        SoundDownloader.setListener(null); // a running sound download must not hold this activity
        super.onDestroy();
    }

    /** Runs {@code action} on the UI thread unless the activity is finishing or gone. */
    private void runOnUiIfAlive(Runnable action) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) action.run();
        });
    }

    /** Starts a named background worker. */
    private static void background(String name, Runnable work) {
        new Thread(work, "trekui-config-" + name).start();
    }

    private void toast(String message, boolean longer) {
        Toast.makeText(this, message, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    // =================================================================================================
    // Building the screen
    // =================================================================================================

    /** Rebuilds the list keeping the current scroll position. */
    private void rebuild() {
        build(scroll != null ? scroll.getScrollY() : 0);
    }

    /** Preference changed: tell the home screen to rebuild on resume, and rebuild this list. */
    private void changed() {
        prefs.bump();
        rebuild();
    }

    /** Builds the frame, the section list and the sidebar of section shortcuts. */
    private void build(final int scrollY) {
        pal = prefs.palette();
        metrics = ScreenMetrics.of(this);
        anchors.clear();
        root = new FrameLayout(this);
        root.setId(R.id.settings_root);
        root.setTag("settings_root");
        root.setBackgroundColor(Skin.bg());

        final ConsoleFrame frame = new ConsoleFrame(this);
        frame.sidebarW = Ui.dp(this, 80f);
        frame.colorTop = pal.c(3);
        frame.colorBot = pal.c(1);
        frame.topBlocks = new int[]{pal.c(0)};
        frame.botBlocks = new int[]{pal.c(2), pal.c(4)};
        TextView title = Ui.bold(this, "CONFIGURATION", 30f, pal.c(0));
        title.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 2f), 0);
        frame.setTitle(title);
        TextView footer = Ui.text(this, "TREKUI LAUNCHER " + version(), 14f, pal.c(1));
        footer.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 2f), 0);
        frame.setFooter(footer);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setId(R.id.settings_list);
        list.setTag("settings_list");
        list.setPadding(0, 0, 0, Ui.dp(this, 24f));
        scroll.addView(list);
        frame.setContent(scroll);

        addSections();
        frame.setSidebar(buildSidebar());
        root.addView(frame, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((view, raw) -> {
            Insets bars = WindowInsetsCompat.toWindowInsetsCompat(raw, view)
                    .getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            insetTop = bars.top;
            insetBottom = bars.bottom;
            insetLeft = bars.left;
            insetRight = bars.right;
            int side = metrics.isTwoPane() ? Ui.rawDp(this, metrics.marginDp) : Ui.dp(this, 8f);
            root.setPadding(insetLeft + side, insetTop + Ui.dp(this, 4f), insetRight + side, insetBottom + Ui.dp(this, 6f));
            return raw;
        });
        setContentView(root);
        scroll.post(() -> scroll.scrollTo(0, scrollY));
    }

    /** The sidebar: one button per anchored section, scrolling the list to it. */
    private View buildSidebar() {
        ScrollView sideScroll = new ScrollView(this);
        sideScroll.setVerticalScrollBarEnabled(false);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < anchors.size(); i++) {
            final Anchor anchor = anchors.get(i);
            int color = pal.c(HomeLogic.accentIndex(i));
            TextView button = Ui.button(this, anchor.label, color, Ui.SQUARE, v -> scroll.smoothScrollTo(0, anchor.view.getTop()));
            button.setBackground(Ui.touch(Ui.shape(this, color, Ui.SQUARE, 0f)));
            Ui.setTextSp(button, 14f);
            button.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 8f), Ui.dp(this, 4f));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50f));
            if (i > 0) params.topMargin = Ui.dp(this, 6f);
            column.addView(button, params);
        }
        sideScroll.addView(column);
        return sideScroll;
    }

    /** All sections in display order. */
    private void addSections() {
        homeSection();
        interfaceSection();
        suiteSection(); // LCA-T35
        shadeSection();
        appSection();
        devicesSection();
        audioSection();
        clockSection();
        layoutSection();
        groupsSection(); // LCA-T20
        gesturesSection(); // LCA-T20
        backupSection();
        drawerSection();
        weatherSection();
        accessSection();
        aboutSection();
    }

    private String version() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return getPackageManager().getPackageInfo(getPackageName(), PackageManager.PackageInfoFlags.of(0)).versionName;
            }
            return legacyPackageInfo().versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    @SuppressWarnings("deprecation")
    private android.content.pm.PackageInfo legacyPackageInfo() throws PackageManager.NameNotFoundException {
        return getPackageManager().getPackageInfo(getPackageName(), 0);
    }

    // =================================================================================================
    // Row builders
    // =================================================================================================

    /**
     * A section header (pill, title, rule).
     *
     * @param sidebarLabel label of the sidebar shortcut to this section, or null for none
     */
    private void section(String title, int color, String sidebarLabel) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, list.getChildCount() == 0 ? 2f : 26f), 0, Ui.dp(this, 8f));
        View pill = new View(this);
        pill.setBackground(Ui.shape(this, color, Ui.LEFT, 12f));
        row.addView(pill, new LinearLayout.LayoutParams(Ui.dp(this, 22f), Ui.dp(this, 24f)));
        TextView label = Ui.bold(this, title, 22f, color);
        label.setPadding(Ui.dp(this, 8f), 0, Ui.dp(this, 8f), 0);
        row.addView(label);
        View rule = new View(this);
        rule.setBackground(Skin.rule(this, color));
        row.addView(rule, new LinearLayout.LayoutParams(0, Ui.dp(this, 6f), 1.0f));
        list.addView(row);
        if (sidebarLabel != null) anchors.add(new Anchor(sidebarLabel, row));
    }

    /** Explanatory small text. */
    private TextView note(String text) {
        TextView view = Ui.text(this, text, 13f, pal.textLo);
        view.setLineSpacing(0f, 1.1f);
        view.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 8f));
        list.addView(view);
        return view;
    }

    /** A sub-heading. */
    private TextView label(String text) {
        TextView view = Ui.bold(this, text, 16f, pal.textHi);
        view.setPadding(0, Ui.dp(this, 12f), 0, Ui.dp(this, 6f));
        list.addView(view);
        return view;
    }

    /** A labelled group of option buttons laid out as segmented console bars; {@code selected} is highlighted. */
    private void choice(String title, String[] options, int selected, final Choice onChoice) {
        label(title);
        int columns = SettingsLogic.choiceColumns(options.length, metrics.isTwoPane());
        LinearLayout row = null;
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            if (i % columns == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48f));
                if (i > 0) rowParams.topMargin = Ui.dp(this, 6f);
                list.addView(row, rowParams);
            }
            int color = i == selected ? pal.c(0) : Ui.darken(pal.c(1), 0.4f);
            TextView button = Ui.button(this, options[i], color, SettingsLogic.choiceCorner(i, columns, options.length),
                    v -> onChoice.chose(index));
            Ui.setTextSp(button, 15f);
            button.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
            if (i % columns != 0) params.leftMargin = Ui.dp(this, 4f);
            row.addView(button, params);
        }
        // Keep a partial last row's buttons the same width as full rows.
        int remainder = options.length % columns;
        if (remainder != 0 && row != null) {
            for (int i = remainder; i < columns; i++) {
                row.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
            }
        }
    }

    /** A setting with an ONLINE / OFFLINE switch. */
    private void toggle(String title, String description, final boolean on, final Toggle onToggle) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Ui.bold(this, title, 16f, pal.textHi));
        if (description != null) {
            TextView desc = Ui.text(this, description, 12f, pal.textLo);
            desc.setPadding(0, Ui.dp(this, 2f), Ui.dp(this, 8f), 0);
            text.addView(desc);
        }
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        TextView button = Ui.button(this, on ? "ONLINE" : "OFFLINE", on ? pal.c(0) : Ui.darken(pal.c(3), 0.4f), Ui.PILL, v -> {
            onToggle.set(!on);
            Sfx.play(this, Sfx.TOGGLE_ON);
        });
        button.setGravity(Gravity.CENTER);
        Ui.setTextSp(button, 14f);
        button.setContentDescription(title + (on ? ": on" : ": off"));
        row.addView(button, new LinearLayout.LayoutParams(Ui.dp(this, 96f), Ui.dp(this, 44f)));
        list.addView(row);
    }

    /** A full-width action button. */
    private TextView action(String text, int color, View.OnClickListener onClick) {
        TextView button = Ui.button(this, text, color, Ui.PILL, onClick);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52f));
        params.topMargin = Ui.dp(this, 6f);
        list.addView(button, params);
        return button;
    }

    /** A compact pill button for use inside rows. */
    private TextView smallButton(String text, int color, View.OnClickListener onClick) {
        TextView button = Ui.button(this, text, color, Ui.PILL, onClick);
        button.setGravity(Gravity.CENTER);
        Ui.setTextSp(button, 13f);
        button.setPadding(0, 0, 0, 0);
        return button;
    }

    /** LayoutParams for a weighted button in a horizontal row, with a left gap unless first. */
    private LinearLayout.LayoutParams rowButtonParams(float heightDp, boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, heightDp), 1.0f);
        if (!first) params.leftMargin = Ui.dp(this, 6f);
        return params;
    }

    /** A boxed card (outlined panel) for event / phone-sound / light rows. */
    private LinearLayout card(int color, float borderAlpha) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 12f), Ui.dp(this, 8f), Ui.dp(this, 12f), Ui.dp(this, 12f));
        box.setBackground(Ui.outline(this, pal.panel, Ui.alpha(color, borderAlpha), 14f, 1.5f));
        return box;
    }

    /** A permission / access status row: GRANTED or NOT GRANTED. */
    private void accessRow(String title, boolean granted, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 4f));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Ui.bold(this, title, 15f, pal.textHi));
        text.addView(Ui.text(this, description, 12f, pal.textLo));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        row.addView(Ui.bold(this, granted ? "GRANTED" : "NOT GRANTED", 14f, granted ? pal.c(2) : Ui.alpha(pal.textLo, 0.7f)));
        list.addView(row);
    }

    /** A colour swatch with its hex code and a COPY button (for Good Lock / Signal recipes). */
    private void colorRow(final String name, int color) {
        final String hex = SettingsLogic.hexColor(color);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f));
        View swatch = new View(this);
        swatch.setBackground(Ui.outline(this, color, Ui.alpha(pal.c(1), 0.6f), 8f, 1.5f));
        row.addView(swatch, new LinearLayout.LayoutParams(Ui.dp(this, 40f), Ui.dp(this, 32f)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(Ui.dp(this, 12f), 0, 0, 0);
        text.addView(Ui.text(this, name, 12f, pal.textLo));
        text.addView(Ui.bold(this, hex, 17f, pal.textHi));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        TextView copy = Ui.button(this, "COPY", pal.c(2), Ui.PILL, v -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText(name, hex));
            // Android 13+ shows its own clipboard confirmation; don't double up.
            if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.toast_copied, hex), false);
        });
        copy.setGravity(Gravity.CENTER);
        Ui.setTextSp(copy, 13f);
        row.addView(copy, new LinearLayout.LayoutParams(Ui.dp(this, 80f), Ui.dp(this, 40f)));
        list.addView(row);
    }

    /** OPEN &lt;app&gt; when installed, else a note. */
    private void appButton(String name, final String pkg, int color) {
        if (!installed(pkg)) {
            note(name + " is not installed.");
            return;
        }
        action("OPEN " + name, color, v -> {
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch != null) open(launch);
        });
    }

    // =================================================================================================
    // Sections
    // =================================================================================================

    /** DEFAULT HOME: role status, Android's home settings and the system dark-mode hint. */
    private void homeSection() {
        section("DEFAULT HOME", pal.c(0), "HOME");
        if (!Licensing.isUnlocked(this)) {
            action("UNLOCK TREKUI", pal.c(0), v -> Licensing.openPurchaseScreen(this));
        }
        boolean isDefault = MainActivity.isDefaultHome(this);
        list.addView(Ui.bold(this, isDefault ? "STATUS: TREKUI IS YOUR DEFAULT HOME" : "STATUS: TREKUI IS NOT THE DEFAULT HOME",
                17f, isDefault ? pal.c(2) : pal.alert));
        if (!isDefault) {
            action("SET TREKUI AS DEFAULT HOME", pal.c(0), v -> MainActivity.requestDefaultHome(this));
        }
        action("ANDROID HOME APP SETTINGS", pal.c(1), v -> open(new Intent(Settings.ACTION_HOME_SETTINGS)));
        action(getString(R.string.config_replay_tour), pal.c(2), v -> { // LCA-T16: replays on home
            Onboarding.requestReplay(this);
            finish();
        }).setTag("config_replay_tour");
        note(isSamsung() ? "To return to Samsung's launcher: Settings › Apps › Choose default apps › Home app › One UI Home."
                : "To return to your previous launcher: Settings › Apps › Default apps › Home app.");
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        list.addView(Ui.bold(this, dark ? "SYSTEM DARK MODE: ON" : "SYSTEM DARK MODE: OFF", 17f, dark ? pal.c(2) : pal.alert));
        if (!dark) {
            note("Other apps, menus and pickers are light. Android doesn't let apps switch dark mode, so turn it on in Settings › Display › Dark. If it keeps turning itself off, check Display › Dark mode settings › Turn on as scheduled, and Modes and Routines.");
            action("OPEN DARK MODE SETTINGS", pal.c(4), v -> {
                // Not a public constant, but present on Android 10+ (Samsung included); fall back to Display.
                if (!tryStart(new Intent("android.settings.DARK_THEME_SETTINGS"))) open(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
            });
        }
    }

    /** INTERFACE: family, interface cards, colour theme, layout, lettering, background and wallpapers. */
    private void interfaceSection() {
        section("INTERFACE", pal.c(1), "THEME");
        choice("INTERFACE FAMILY", new String[]{"STARSHIP", "PIPE LOUNGE"}, Skin.pipe() ? 1 : 0, which -> {
            boolean toPipe = which == 1;
            if (toPipe == Skin.pipe()) return;
            int current = prefs.faction();
            prefs.put(Skin.pipeFaction(current) ? "lastPipe" : "lastStarship", current);
            int target = SettingsLogic.familyTarget(toPipe,
                    prefs.i("lastPipe", SettingsLogic.FIRST_PIPE_FACTION), prefs.i("lastStarship", 0));
            selectFaction(target);
        });
        if (Skin.pipe()) {
            note("Six interfaces built around the pipe: three drawn from materials, and three painted scenes (Ember & Briar, the Tobacconist's Ledger, Midnight Ember). The first three are the Victorian tobacconist's cabinet, the Art Deco smoking room and the tobacco-barn journal. Every tile, slot, panel, sound assignment and device control carries over from your starship layout and back again. Switch back with STARSHIP above. Tap LORE (or the title) for a page of pipe craft.");
        } else {
            note("Every function, slot, sound assignment and device control works the same in all five; only the geometry, lettering and colours change. CLASSIC CONSOLE (23rd-century style) and NEXT CONSOLE (25th-century style) keep the first interface's layouts (grid and cascade) and its sound profile. PIPE LOUNGE above switches to the pipe interfaces.");
        }
        int first = Skin.pipe() ? SettingsLogic.FIRST_PIPE_FACTION : 0;
        int count = Skin.pipe() ? 6 : SettingsLogic.STARSHIP_FACTIONS.length;
        for (int n = 0; n < count; n++) {
            int faction = Skin.pipe() ? first + n : SettingsLogic.STARSHIP_FACTIONS[n];
            if (faction == 6) {
                label("PAINTED SCENES");
                note("Three homes painted as complete scenes: the fireside study, the merchant's folio and the Art Deco night lounge. Their plaques, tabs and keys are live controls and can be reassigned with a long press like any console slot. Clock, date, weather, battery, the session clock and your current pipe and blend are drawn live over the painting. The pipe register, blend catalogue, journal, ritual checklist and session clock open from the scene.");
            }
            factionRow(faction);
        }
        if (SubThemes.selectable(Skin.faction)) { // LCA-T35: every interface with more than one sub-theme
            section(getString(R.string.config_color_theme), pal.c(1), null);
            for (int i = 0; i < Palette.themesFor(Skin.faction).length; i++) themeRow(i);
        } else {
            note(fixedColoursNote());
        }
        if (Skin.imperial()) {
            choice("IMPERIAL LETTERING", new String[]{"MIXED", "ALL BLADE", "PLAIN"}, prefs.i("imperialLettering", 0), which -> {
                prefs.put("imperialLettering", which);
                Skin.lettering = which;
                changed();
            });
            note("WARRIOR BLADE is this launcher's own Imperial-style lettering for English. MIXED: titles, labels and keys in Warrior Blade, longer text in Oxanium. ALL BLADE: Warrior Blade everywhere. PLAIN: Oxanium only.");
        }
        if (Skin.command()) {
            layoutChoice("HOME LAYOUT", "CONSOLE GRID", "CONSOLE CASCADE");
            note("CONSOLE CASCADE: one large console key; press it and a segmented spine drops down while the tile keys extend left and right out of it. Fits the screen, no scrolling; panels open from the key row at the bottom. Folds away after you open an app. Long-press the key for all apps.");
        }
        if (Skin.imperial()) {
            layoutChoice("HOME LAYOUT", "COMMAND PLATES", "TACTICAL DEPLOY");
            note("TACTICAL DEPLOY: one command plate; press it and a triangle spine drops down while the tile plates slide out from behind it, narrowing into an arrowhead. Fits the screen, no scrolling; panels open from the plate row at the bottom. Folds away after you open an app. Long-press the plate for all apps.");
        }
        if (Skin.collective()) {
            choice("COLLECTIVE LAYOUT", new String[]{"TRACE COLUMN", "HUB AND SPOKE"}, prefs.collectiveHub() ? 1 : 0, which -> {
                prefs.put("collectiveLayout", which);
                changed();
            });
            note("TRACE COLUMN: one node per row, linked top to bottom. HUB AND SPOKE: nodes of different sizes around a central hub, linked by traces of different lengths. The network stays folded into the hub until you tap it, and folds away again after you open an app. Long-press the hub for all apps.");
        }
        String[] backgrounds = Skin.pipe()
                ? new String[]{Skin.patternName(), "PLAIN DARK", "WALLPAPER"}
                : new String[]{"BLACK", Skin.patternName(), "WALLPAPER"};
        choice("BACKGROUND", backgrounds, prefs.bgMode(), which -> {
            prefs.put("bg", which);
            changed();
        });
        if (prefs.bgMode() == 2) {
            choice("WALLPAPER DIMMING", new String[]{"LIGHT 30%", "MEDIUM 55%", "HEAVY 75%"},
                    SettingsLogic.dimIndex(prefs.i("dim", 55)), which -> {
                        prefs.put("dim", SettingsLogic.DIM_LEVELS[which]);
                        changed();
                    });
        }
        wallpaperPicker();
    }

    /** Grid vs reveal layout choice (Command / Imperial). */
    private void layoutChoice(String title, String grid, String reveal) {
        choice(title, new String[]{grid, reveal}, prefs.revealMode() ? 1 : 0, which -> {
            prefs.put(prefs.layoutKey(), which);
            changed();
        });
    }

    /** Note shown instead of the theme list for interfaces whose colours are fixed. */
    private static String fixedColoursNote() {
        if (Skin.scene()) {
            String materials = Skin.mTob() ? "walnut and brass" : Skin.mLounge() ? "lacquer and gilt" : "leather and kraft";
            return "The colours of the painted scenes come from the paintings themselves; the drawer, CONFIG and dialogs use matching materials (" + materials + ").";
        }
        if (Skin.pipe()) {
            if (Skin.tob()) return "Tobacconist colours are its materials: walnut, brass, oxblood leather and parchment.";
            if (Skin.lounge()) return "Ember Lounge colours are its materials: black lacquer, gilt, lacquer red and the ember glow.";
            return "Blend Journal colours are its materials: tobacco leather, kraft paper, saddle stitching and red stamp ink.";
        }
        return Skin.imperial() ? "Imperial colours are fixed: red, orange, gold and copper on near-black."
                : "Collective colours are fixed: matrix greens on near-black.";
    }

    /** Switches to an interface (faction), remembers it per family and reloads its sound profile. */
    private void selectFaction(int faction) {
        if (faction != prefs.faction()) LayoutSnapshots.snapshot(this, LayoutSnapshots.R_INTERFACE); // LCA-T16
        prefs.put("faction", faction);
        prefs.put(Skin.pipeFaction(faction) ? "lastPipe" : "lastStarship", faction);
        Skin.faction = faction;
        Sfx.reloadProfile(this);
        Sfx.play(this, Sfx.CONFIRM);
        changed();
    }

    /** Accent colour of an interface card. */
    private static int factionAccent(int faction) {
        switch (faction) {
            case 0: return COMMAND_ACCENT;
            case 1: return IMPERIAL_ACCENT;
            case 2: return COLLECTIVE_ACCENT;
            case Skin.ERA23: return ERA23_ACCENT;
            case Skin.ERA25: return ERA25_ACCENT;
            case 3: case 6: return PIPE_BRASS;
            case 4: case 8: return PIPE_GILT;
            default: return PIPE_KRAFT;
        }
    }

    /** One interface card: name, three palette swatches and subtitle; tap selects it. */
    private void factionRow(final int faction) {
        // Skin.* answers for the current faction, so borrow this one while drawing its card.
        int saved = Skin.faction;
        Skin.faction = faction;
        Palette palette;
        if (Skin.pipeFaction(faction)) palette = Skin.pipePalette(faction);
        else palette = Palette.of(faction, prefs.subTheme(faction)); // LCA-T35: the card shows its chosen sub-theme
        boolean selected = saved == faction;
        int accent = factionAccent(faction);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 12f), Ui.dp(this, 8f), Ui.dp(this, 12f), Ui.dp(this, 8f));
        float radius = faction == 0 ? 16f : faction == 1 ? 1f : 3f;
        if (Skin.eraFaction(faction)) radius = faction == Skin.ERA23 ? 2f : 0f;
        card.setBackground(Ui.touch(Ui.outline(this, selected ? Ui.alpha(accent, 0.14f) : Skin.bg(),
                selected ? accent : Ui.alpha(accent, 0.35f), radius, selected ? 2.5f : 1f)));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(Ui.bold(this, Skin.NAMES[faction], 20f, accent), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        int[] corners = {Ui.LEFT, Ui.SQUARE, Ui.RIGHT};
        for (int i = 0; i < corners.length; i++) {
            View swatch = new View(this);
            swatch.setBackground(Ui.shape(this, palette.c(i), corners[i], 10f));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, i == 1 ? 46f : 30f), Ui.dp(this, 28f));
            params.leftMargin = Ui.dp(this, 4f);
            header.addView(swatch, params);
        }
        card.addView(header);
        View subtitle = Ui.oneLine(Ui.text(this, Skin.SUBTITLES[faction], 12f, Ui.alpha(accent, 0.8f)));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = Ui.dp(this, 4f);
        card.addView(subtitle, subtitleParams);
        if (Skin.eraFaction(faction)) {
            // a miniature of the era's home screen, drawn in its own geometry
            View preview = new View(this);
            preview.setBackground(EraDraw.preview(this, faction, palette));
            preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            preview.setTag("interface_preview_" + faction);
            LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, EraDraw.PREVIEW_DP));
            previewParams.topMargin = Ui.dp(this, 6f);
            card.addView(preview, previewParams);
        }
        Skin.faction = saved;

        card.setContentDescription(Skin.NAMES[faction] + (selected ? ", selected" : ""));
        card.setOnClickListener(Ui.feedback(v -> selectFaction(faction)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 6f);
        list.addView(card, params);
    }

    /**
     * One colour theme (sub-theme) of the current interface: its name in its own primary colour and a
     * preview strip of its six accents on its own background; tap applies it at once (LCA-T35).
     */
    private void themeRow(final int index) {
        final int faction = prefs.faction();
        Palette palette = Palette.themesFor(faction)[index];
        boolean selected = prefs.subTheme(faction) == index;
        LinearLayout row = new LinearLayout(this);
        row.setTag("subtheme_" + faction + "_" + index);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 12f), Ui.dp(this, 8f), Ui.dp(this, 12f), Ui.dp(this, 8f));
        int rest = palette.toned ? palette.bg : Skin.bg();
        row.setBackground(Ui.touch(Ui.outline(this, selected ? Ui.alpha(palette.c(0), 0.15f) : rest,
                selected ? palette.c(0) : Ui.alpha(palette.c(1), 0.35f), 16f, selected ? 2.5f : 1f)));
        row.addView(Ui.bold(this, palette.name, 17f, palette.c(0)), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        for (int i = 0; i < 6; i++) {
            View swatch = new View(this);
            int corner = i == 0 ? Ui.LEFT : i == 5 ? Ui.RIGHT : Ui.SQUARE;
            swatch.setBackground(Ui.shape(this, palette.c(i), corner, 8f));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(Ui.dp(this, 18f), Ui.dp(this, 22f));
            params.leftMargin = Ui.dp(this, 4f);
            row.addView(swatch, params);
        }
        row.setContentDescription(palette.name + (selected ? ", selected" : ""));
        row.setOnClickListener(Ui.feedback(v -> {
            prefs.setSubTheme(faction, index);
            SuiteProvider.notifyChanged(this, SuiteContract.PATH_THEME); // companions follow at once
            Sfx.play(this, Sfx.CONFIRM);
            changed();
        }));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50f));
        params.bottomMargin = Ui.dp(this, 6f);
        list.addView(row, params);
    }

    /**
     * SUITE (LCA-T35): the companion apps. KEYBOARD shows the active keyboard and opens the picker /
     * enable screen; LOCK SCREEN switches the wake screen on or off (published on the suite provider);
     * MESSAGES makes TrekUI Messages the default SMS app or restores the previous one. A companion that
     * isn't installed shows INSTALL (its Play listing). Lookups and intents live in {@link SuiteSettings}.
     */
    private void suiteSection() {
        section(getString(R.string.suite_title), pal.c(2), getString(R.string.suite_sidebar));
        note(getString(R.string.suite_note));
        String own = getPackageName();

        String keyboard = SuiteContract.packageFor(SuiteContract.KEYBOARD, own);
        label(getString(R.string.suite_keyboard)).setTag("suite_keyboard");
        String imeId = SuiteSettings.activeImeId(this);
        if (SuiteSettings.isSuiteKeyboard(imeId, keyboard)) {
            list.addView(Ui.bold(this, getString(R.string.suite_keyboard_in_use), 15f, pal.c(2)));
        } else {
            String imeName = SuiteSettings.imeLabel(this, imeId);
            note(getString(R.string.suite_keyboard_active, imeName != null ? imeName : getString(R.string.suite_unknown)));
        }
        if (installed(keyboard)) {
            action(getString(R.string.suite_keyboard_choose), pal.c(0), v -> {
                if (!SuiteSettings.showImePicker(this)) open(SuiteSettings.imeSettingsIntent());
            }).setTag("suite_keyboard_choose");
            action(getString(R.string.suite_keyboard_enable), pal.c(1), v -> open(SuiteSettings.imeSettingsIntent()))
                    .setTag("suite_keyboard_enable");
        } else {
            suiteInstall(keyboard, "suite_keyboard_install");
        }

        String lockscreen = SuiteContract.packageFor(SuiteContract.LOCKSCREEN, own);
        label(getString(R.string.suite_lockscreen)).setTag("suite_lockscreen");
        if (installed(lockscreen)) {
            toggle(getString(R.string.suite_lockscreen_switch), getString(R.string.suite_lockscreen_desc),
                    prefs.lockscreenEnabled(), on -> {
                        prefs.setLockscreenEnabled(on);
                        SuiteProvider.notifyChanged(this, SuiteContract.PATH_LOCKSCREEN);
                        rebuild();
                    });
            action(getString(R.string.suite_open_lockscreen), pal.c(1), v -> {
                Intent launch = getPackageManager().getLaunchIntentForPackage(lockscreen);
                if (launch != null) open(launch);
            }).setTag("suite_lockscreen_open");
        } else {
            suiteInstall(lockscreen, "suite_lockscreen_install");
        }

        String messages = SuiteContract.packageFor(SuiteContract.MESSAGES, own);
        label(getString(R.string.suite_messages)).setTag("suite_messages");
        if (installed(messages)) {
            String current = SuiteSettings.defaultSms(this);
            boolean isDefault = messages.equals(current);
            list.addView(Ui.bold(this, getString(isDefault ? R.string.suite_messages_default : R.string.suite_messages_not_default),
                    15f, isDefault ? pal.c(2) : pal.alert));
            if (!isDefault) {
                action(getString(R.string.suite_messages_set), pal.c(0), v -> {
                    if (current != null) prefs.put(SuiteSettings.KEY_PREV_SMS, current);
                    if (!tryStart(SuiteSettings.requestSmsIntent(messages))) open(SuiteSettings.defaultAppsIntent());
                }).setTag("suite_messages_set");
            } else {
                action(getString(R.string.suite_messages_restore), pal.c(3), v -> {
                    String previous = SuiteSettings.restoreTarget(prefs.s(SuiteSettings.KEY_PREV_SMS, null), messages);
                    if (!tryStart(SuiteSettings.restoreSmsIntent(previous))) open(SuiteSettings.defaultAppsIntent());
                }).setTag("suite_messages_restore");
            }
        } else {
            suiteInstall(messages, "suite_messages_install");
        }
    }

    /** INSTALL for a companion that isn't installed: its Play listing (store app, else the web). */
    private void suiteInstall(String pkg, String tag) {
        action(getString(R.string.suite_install), pal.c(4), v -> {
            if (!tryStart(SuiteSettings.marketIntent(pkg))) open(SuiteSettings.webStoreIntent(pkg));
        }).setTag(tag);
    }

    /** console WALLPAPERS: three thumbnails; tap applies one to home, lock or both. */
    private void wallpaperPicker() {
        label("TREKUI WALLPAPERS");
        note("Apply a theme-kit wallpaper to your phone's home and/or lock screen. It shows behind TrekUI when BACKGROUND is set to WALLPAPER.");
        LinearLayout row = new LinearLayout(this);
        for (int i = 0; i < WALLPAPERS.length; i++) {
            final int index = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.addView(thumbView(thumbAsset("wallpapers/" + WALLPAPERS[i] + ".png", THUMB_SAMPLE), pal.c(i + 1)),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 150f)));
            TextView name = Ui.bold(this, WALLPAPER_NAMES[i], 14f, pal.c(i + 1));
            name.setPadding(0, Ui.dp(this, 4f), 0, 0);
            item.addView(name);
            item.setOnClickListener(Ui.feedback(v -> applyWallpaper(index)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            if (i > 0) params.leftMargin = Ui.dp(this, 8f);
            row.addView(item, params);
        }
        list.addView(row);
    }

    /** A framed, centre-cropped thumbnail. */
    private ImageView thumbView(Bitmap bitmap, int frameColor) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(bitmap);
        image.setBackground(Ui.outline(this, Color.BLACK, frameColor, 10f, 2f));
        int pad = Ui.dp(this, 3f);
        image.setPadding(pad, pad, pad, pad);
        return image;
    }

    /** Asks where to apply wallpaper {@code index}, then sets it on a worker thread. */
    private void applyWallpaper(final int index) {
        ConsoleDialog.options(this, "APPLY " + WALLPAPER_NAMES[index], new String[]{"HOME SCREEN", "LOCK SCREEN", "HOME + LOCK"}, which -> {
            final int target = SettingsLogic.wallpaperTarget(which);
            toast(getString(R.string.toast_applying), false);
            background("wallpaper", () -> {
                boolean ok;
                try (InputStream in = getAssets().open("wallpapers/" + WALLPAPERS[index] + ".png")) {
                    WallpaperManager.getInstance(getApplicationContext()).setStream(in, null, true, target);
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                }
                final boolean applied = ok;
                runOnUiIfAlive(() -> {
                    Sfx.play(this, applied ? Sfx.CONFIRM : Sfx.ERROR);
                    toast(getString(applied ? R.string.toast_wallpaper_applied : R.string.toast_wallpaper_failed), false);
                });
            });
        });
    }

    /** NOTIFICATION SHADE: console shade toggles, system-settings access and the Samsung Quick Panel recipe. */
    private void shadeSection() {
        section("NOTIFICATION SHADE", pal.c(2), "SHADE");
        boolean samsung = isSamsung();
        note(samsung ? "TREKUI SHADE: swipe down on the TrekUI home screen. Samsung's own panel (used in other apps) is themed separately below."
                : "TREKUI SHADE: swipe down on the TrekUI home screen. The system's own panel is used in other apps.");
        toggle("SWIPE DOWN OPENS TREKUI SHADE", samsung ? "Off: swipe down opens Samsung's panel instead" : "Off: swipe down opens the system panel instead", prefs.b("shadeConsole", true), on -> {
            prefs.put("shadeConsole", on);
            rebuild();
        });
        toggle("SHOW NOTIFICATIONS IN TREKUI SHADE", "Uses notification access; nothing is stored or sent", prefs.b("shadeNotes", true), on -> {
            prefs.put("shadeNotes", on);
            rebuild();
        });
        toggle(getString(R.string.badge_setting), getString(R.string.badge_setting_desc), prefs.b(NotificationBadges.PREF, true), on -> {
            prefs.put(NotificationBadges.PREF, on); // LCA-T23: read again when the home screen resumes
            rebuild();
        });
        boolean canWrite = Settings.System.canWrite(this);
        accessRow("BRIGHTNESS & ROTATION CONTROL", canWrite, "“Modify system settings”: lets the shade's slider and ROTATE tile work");
        if (!canWrite) {
            action("ALLOW BRIGHTNESS & ROTATION CONTROL", pal.c(4), v -> openWriteSettings());
        }
        if (!samsung) return; // the Quick Panel recipe needs Samsung's Good Lock
        label("SAMSUNG QUICK PANEL (ONE UI 9 + GOOD LOCK)");
        note("Android doesn't let a launcher restyle Samsung's panel, but Samsung's Good Lock can. Recipe for the " + pal.name + " theme:\n1. Install Good Lock (Galaxy Store), then add QuickStar and Theme Park inside it.\n2. QuickStar › Panel Theme: set the four colours below and transparency to 0%.\n3. Tap EXPORT below, then QuickStar › Edit panel style: give the button box, buttons, brightness slider, volume slider and media player the matching images from Pictures › TrekUI Quick Panel. Turn borders off.\n4. Optional: set a TrekUI wallpaper (THEME section) so Samsung's colour palette matches too.");
        colorRow("BACKGROUND", Color.BLACK);
        colorRow("ACTIVE CONTROLS", pal.c(0));
        colorRow("INACTIVE CONTROLS", Ui.darken(pal.c(1), 0.35f));
        colorRow("TEXT", pal.textHi);
        action("EXPORT TREKUI PANEL ART TO GALLERY", pal.c(0), v -> exportPanelArt());
        goodLockButton(pal.c(1));
    }

    private void goodLockButton(int color) {
        action(installed(PKG_GOOD_LOCK) ? "OPEN GOOD LOCK" : "GET GOOD LOCK (GALAXY STORE)", color, v -> openGoodLock());
    }

    /** APP THEMING: exported backgrounds and recipes for Signal, Chrome, Outlook, keyboard and nav buttons. */
    private void appSection() {
        section("APP THEMING", pal.c(5), "APPS");
        final int theme = themeIndex();
        note("Android doesn't let one app restyle another (that would need root), so TrekUI reaches Signal, Chrome and Outlook through each app's own theme settings. Export the " + pal.name + " backgrounds, then follow each recipe. Their home-screen icons get TrekUI pictograms when ICON STYLE is TrekUI.");
        LinearLayout previews = new LinearLayout(this);
        String[] apps = {"signal", "chrome"};
        String[] names = {"SIGNAL CHAT", "CHROME NEW TAB"};
        for (int i = 0; i < apps.length; i++) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.addView(thumbView(thumbAsset("appthemes/" + apps[i] + "/" + theme + ".png", THUMB_SAMPLE), pal.c(i + 1)),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 190f)));
            item.addView(Ui.bold(this, names[i], 14f, pal.c(i + 1)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            if (i > 0) params.leftMargin = Ui.dp(this, 12f);
            previews.addView(item, params);
        }
        list.addView(previews);
        final String folder = "Pictures/TrekUI App Themes/" + themeFolder(theme) + "/";
        action("EXPORT APP BACKGROUNDS TO GALLERY", pal.c(0), v -> exportImages(new String[][]{
                {"appthemes/signal/" + theme + ".png", "TrekUI Signal.png"},
                {"appthemes/chrome/" + theme + ".png", "TrekUI Chrome.png"},
                {"appthemes/keyboard/" + theme + ".png", "TrekUI Keyboard.png"}}, folder, "BACKGROUNDS EXPORTED",
                "Signal: Settings › Appearance › Chat color & wallpaper › Set wallpaper › choose TrekUI Signal.\nChrome: new tab › ⋮ › Customize new tab page › Appearance › Upload an image › choose TrekUI Chrome.\nKeyboard: Theme Park › Keyboard › pink picture button › choose TrekUI Keyboard."));

        float[] hsv = new float[3];
        Color.colorToHSV(pal.c(0), hsv);
        int[] sliders = SettingsLogic.signalSliders(hsv);
        label("SIGNAL");
        note("1. Signal › your profile picture › Settings › Appearance › Theme: DARK.\n2. Appearance › Chat color & wallpaper › Set wallpaper › Choose from photos › TrekUI Signal. Turn “Dim wallpaper in dark theme” off; the art is already dimmed.\n3. Same screen › Chat color › + (custom) › SOLID. Signal uses sliders, not hex codes: hue " + sliders[0] + "° (about " + Math.round(sliders[0] / 3.6f) + "% along the hue slider), saturation " + sliders[1] + "% (about " + sliders[1] + "% along). Signal adjusts brightness itself for readable text, so the result is close to the swatch, not exact.");
        colorRow("OUTGOING BUBBLE (TARGET)", pal.c(0));
        appButton("SIGNAL", PKG_SIGNAL, pal.c(1));
        label("CHROME");
        note("1. Chrome › ⋮ › Settings › Appearance (THEME on older versions) › DARK.\n2. Open a new tab › ⋮ › Customize new tab page › Appearance › Upload an image › TrekUI Chrome. (Recent Chrome versions; this only changes the new tab page.)\n3. Chrome's toolbar and menus take their colour from your phone's palette: apply a TrekUI wallpaper (THEME section), then Android Settings › Wallpaper and style › Color palette and pick the swatch closest to the accent below.");
        colorRow("ACCENT", pal.c(0));
        appButton("CHROME", PKG_CHROME, pal.c(2));
        label("OUTLOOK");
        note("1. Outlook › your profile picture › Settings (gear) › Preferences/Appearance › Theme: DARK THEME (not “Set by Battery Saver”).\n2. If your version shows colour themes, pick the swatch closest to the accent above. Outlook has no custom backgrounds, so dark theme plus the TrekUI home icon is as far as it goes.");
        appButton("OUTLOOK", PKG_OUTLOOK, pal.c(3));
        if (!isSamsung()) { // Theme Park, ClockFace and NavStar are Samsung Good Lock modules
            label("KEYBOARD");
            note("Gboard › Settings › Theme › My image with TrekUI Keyboard (EXPORT above).");
            return;
        }
        label("SAMSUNG KEYBOARD (GOOD LOCK › THEME PARK)");
        note("1. Good Lock › Theme Park › Keyboard › + (create). Tap the moon/sun icon so you're editing the dark version.\n2. Pink picture button: choose TrekUI Keyboard from Pictures › TrekUI App Themes (EXPORT above). The TrekUI frame shows around the keys.\n3. Go through the circles like reading a page: start top left, go left to right, then down a row. Tap each one and enter the colour below, in this order (skip the circle showing your picture, it's the background image).\n4. Download icon (top right) saves and applies.\nUsing Gboard instead? Gboard › Settings › Theme › My image with TrekUI Keyboard.");
        colorRow("1 · TOP ROW, LEFT: TYPING POP-UP", pal.c(1));
        colorRow("2 · TOP ROW, MIDDLE: TOOLBAR ICONS", pal.c(0));
        colorRow("3 · TOP ROW, RIGHT: TOOLBAR BACKGROUND", Color.BLACK);
        colorRow("4 · BY THE 3 KEY: POP-UP LETTER", Color.BLACK);
        colorRow("5 · UNDER THE W: PRESSED KEY", pal.c(2));
        colorRow("6 · BY THE ! KEY: FUNCTION KEY ICONS", Color.BLACK);
        colorRow("7 · BOTTOM ROW, LEFT: FUNCTION KEYS", pal.c(0));
        colorRow("8 · BOTTOM ROW, MIDDLE: LETTERS", Color.BLACK);
        colorRow("9 · BOTTOM ROW, RIGHT: LETTER KEYS", pal.c(4));
        goodLockButton(pal.c(4));
        label("ALWAYS ON DISPLAY");
        note("One UI 7 and later removed custom AOD images: the AOD shows your lock screen clock. For TrekUI colours there, Good Lock › ClockFace can style the clock (clock #FF9C00, date #B4A0F0).");
        label("NAVIGATION BUTTONS (GOOD LOCK › NAVSTAR)");
        LinearLayout navRow = new LinearLayout(this);
        navRow.setBackgroundColor(Skin.bg());
        navRow.setGravity(Gravity.CENTER);
        for (String button : new String[]{"recents", "home", "back"}) {
            ImageView image = new ImageView(this);
            image.setImageBitmap(thumbAsset("appthemes/nav/" + theme + "/nav_" + button + ".png", 1));
            navRow.addView(image, new LinearLayout.LayoutParams(0, Ui.dp(this, 64f), 1.0f));
        }
        list.addView(navRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64f)));
        note("TrekUI pills for the button navigation bar, in Samsung's order: recents · home · back.\n1. Tap EXPORT below.\n2. Good Lock › NavStar › Navigation buttons: make a new custom button style and, for each button, pick the matching image (TrekUI Nav Recents / Home / Back) from your Gallery.\n3. Set the navigation bar background to black (#000000).\nNavStar's screens vary between versions; if it isn't obvious which slot is which, use the same trick as the keyboard.");
        action("EXPORT NAVIGATION BUTTONS", pal.c(0), v -> exportImages(new String[][]{
                {"appthemes/nav/" + theme + "/nav_recents.png", "TrekUI Nav Recents.png"},
                {"appthemes/nav/" + theme + "/nav_home.png", "TrekUI Nav Home.png"},
                {"appthemes/nav/" + theme + "/nav_back.png", "TrekUI Nav Back.png"}}, folder, "BUTTONS EXPORTED",
                "Good Lock › NavStar › Navigation buttons › custom style: give each button its TrekUI image."));
    }

    /** SMART DEVICES: Govee lights (LAN + cloud) and the Nest thermostat. */
    private void devicesSection() {
        section("SMART DEVICES", pal.c(3), "DEVICES");
        label("GOVEE LIGHTS");
        note("Switch a Govee light on and off from any TrekUI segment, tile or dock icon. Two ways to connect; you can use both, and LAN is tried first.\nLAN (instant, stays on your Wi-Fi, no account): in the Govee Home app open the light › ⚙ › turn on LAN Control. Then, on the same Wi-Fi, tap SCAN NETWORK.\nCLOUD (works from anywhere, about a second): in Govee Home tap Profile › ⚙ Settings › Apply for API Key. The key arrives by email; enter it with API KEY, then tap FETCH DEVICES (this also brings in the names you gave your lights).");
        action("SCAN NETWORK (LAN)", pal.c(0), v -> {
            toast("SCANNING SUBSPACE FREQUENCIES…", false);
            Sfx.play(this, Sfx.SCAN_START);
            Govee.scanLan(this, (devices, error) -> goveeFound(devices, error, false));
        });
        action("ADD BY IP ADDRESS (LAN)", pal.c(4), v -> ConsoleDialog.prompt(this, "LAMP IP ADDRESS", prefs.s("goveeLastIp", ""),
                "E.G. 192.168.1.42", text -> goveeProbe(text.trim())));
        final String key = Govee.key(prefs);
        action(key.isEmpty() ? "API KEY (CLOUD)" : "API KEY • " + SettingsLogic.maskSecret(key), pal.c(1), v ->
                ConsoleDialog.prompt(this, "GOVEE API KEY", key, "PASTE THE KEY FROM GOVEE'S EMAIL", text -> {
                    prefs.put("goveeKey", text.trim());
                    Sfx.play(this, Sfx.CONFIRM);
                    rebuild();
                }));
        if (!key.isEmpty()) {
            action("FETCH DEVICES (CLOUD)", pal.c(2), v -> {
                toast("CONTACTING GOVEE…", false);
                Sfx.play(this, Sfx.SCAN_START);
                Govee.fetchCloud(key, (devices, error) -> goveeFound(devices, error, true));
            });
        }
        List<Govee.Device> devices = Govee.devices(prefs);
        if (devices.isEmpty()) {
            note("No lights added yet.");
        } else {
            for (int i = 0; i < devices.size(); i++) goveeRow(devices, i);
            note("To assign one: long-press any TrekUI segment, tile or dock icon › ASSIGN TREKUI FUNCTION › LIGHTS: <name>. Each tap toggles the light; the segment chirps and shows ON or OFF.");
        }
        nestSection();
        homeAssistantSection(); // LCA-T23
    }

    /** Result of a Govee LAN scan, cloud fetch or IP probe: merge and save the lights, or explain the failure. */
    private void goveeFound(List<Govee.Device> found, String error, boolean cloud) {
        if (isFinishing() || isDestroyed()) return;
        if (error != null) {
            Sfx.play(this, Sfx.ERROR);
            ConsoleDialog.message(this, cloud ? "GOVEE CLOUD ERROR" : "SCAN FAILED", error, new String[]{"OK"}, null);
            return;
        }
        if (found.isEmpty()) {
            Sfx.play(this, Sfx.DENY);
            String message = cloud
                    ? "Govee returned no lights with an on/off control for this key."
                    : "No Govee light answered the scan (sent from " + Govee.lastInterface + ").\n\n• In Govee Home, open the lamp › ⚙ and check LAN Control is on. It can take a few minutes to appear after setup, and some models don't have it (then use the cloud API key).\n• The phone must be on the same Wi-Fi as the lamp, not a guest network. Mesh systems and routers with \"multicast/IGMP snooping\" or \"AP isolation\" can block discovery.\n• Workaround: ADD BY IP ADDRESS. Find the lamp in your router's device list (Govee lamps usually show up as \"ihoment_\" plus the model number) and enter its address.";
            ConsoleDialog.message(this, "NO LIGHTS FOUND", message, new String[]{"OK"}, null);
            return;
        }
        Govee.save(prefs, Govee.merge(Govee.devices(prefs), found, cloud));
        Sfx.play(this, Sfx.SCAN_DONE);
        toast(SettingsLogic.count(found.size(), "LIGHT", "LIGHTS") + " FOUND", false);
        rebuild();
    }

    /** ADD BY IP ADDRESS: asks one address directly on Govee's LAN ports (worker thread). */
    private void goveeProbe(final String address) {
        if (!SettingsLogic.isIpv4(address)) {
            Sfx.play(this, Sfx.DENY);
            toast(getString(R.string.toast_bad_ip), true);
            return;
        }
        prefs.put("goveeLastIp", address);
        toast("HAILING " + address + "…", false);
        Sfx.play(this, Sfx.SCAN_START);
        background("govee-probe", () -> {
            Govee.Device device = null;
            String failure = null;
            try {
                device = Govee.probe(getApplicationContext(), address);
            } catch (Exception e) {
                failure = e.getMessage();
            }
            final Govee.Device answered = device;
            final String reason = failure;
            runOnUiIfAlive(() -> {
                if (answered != null) {
                    goveeFound(Collections.singletonList(answered), null, false);
                    return;
                }
                Sfx.play(this, Sfx.ERROR);
                ConsoleDialog.message(this, "NO ANSWER FROM " + address, (reason != null ? reason + "\n\n" : "")
                        + "Nothing at that address answered on Govee's LAN ports. Check the address and that LAN Control is on for the lamp in Govee Home.",
                        new String[]{"OK"}, null);
            });
        });
    }

    /** One saved light: TOGGLE, RENAME, REMOVE. */
    private void goveeRow(final List<Govee.Device> devices, final int index) {
        final Govee.Device device = devices.get(index);
        int color = pal.c(new int[]{0, 2, 3, 4, 1, 5}[index % 6]);
        LinearLayout box = card(color, 0.8f);
        box.addView(Ui.bold(this, device.title(), 16f, color));
        TextView route = Ui.text(this, device.sku + "  •  " + device.route(), 13f, pal.textHi);
        route.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 6f));
        box.addView(route);
        LinearLayout buttons = new LinearLayout(this);
        buttons.addView(smallButton("TOGGLE", pal.c(0), v -> Govee.power(this, device.id, null, (ok, message, on) -> {
            Sfx.play(this, ok ? Sfx.CONFIRM : Sfx.ERROR);
            toast(message, true);
        })), rowButtonParams(40f, true));
        buttons.addView(smallButton("RENAME", pal.c(1), v -> ConsoleDialog.prompt(this, "RENAME LIGHT", device.name, "NAME", text -> {
            device.name = text.trim().toUpperCase(Locale.getDefault());
            Govee.save(prefs, devices);
            rebuild();
        })), rowButtonParams(40f, false));
        buttons.addView(smallButton("REMOVE", Ui.darken(pal.c(3), 0.8f), v -> {
            devices.remove(index);
            Govee.save(prefs, devices);
            Sfx.play(this, Sfx.CLOSE);
            rebuild();
        }), rowButtonParams(40f, false));
        box.addView(buttons);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 8f);
        list.addView(box, params);
    }

    /**
     * HOME ASSISTANT (LCA-T23): address and long-lived token (kept in {@link HomeAssistant#FILE},
     * excluded from backup and export), IMPORT ENTITIES, and the imported entities with CONTROL.
     */
    private void homeAssistantSection() {
        label(getString(R.string.ha_section));
        note(getString(R.string.ha_section_note));
        final String url = HomeAssistant.baseUrl(this);
        final String token = HomeAssistant.token(this);
        action(getString(R.string.ha_address, url.isEmpty() ? getString(R.string.ha_not_set) : SettingsLogic.abbreviate(url)), pal.c(0), v ->
                HomeAssistantDialog.prompt(this, getString(R.string.ha_address_prompt), url, getString(R.string.ha_address_hint), false, text -> {
                    String normalized = HomeAssistant.normalizeUrl(text);
                    int kind = HomeAssistant.checkUrl(normalized);
                    if (!normalized.isEmpty() && kind != HomeAssistant.URL_OK && kind != HomeAssistant.URL_LOCAL_HTTP) {
                        Sfx.play(this, Sfx.DENY);
                        ConsoleDialog.message(this, getString(R.string.ha_address_prompt), getString(kind == HomeAssistant.URL_INSECURE
                                ? R.string.ha_url_insecure : R.string.ha_url_invalid), new String[]{"OK"}, null);
                        return;
                    }
                    HomeAssistant.setBaseUrl(this, normalized);
                    Sfx.play(this, Sfx.CONFIRM);
                    rebuild();
                }));
        if (HomeAssistant.checkUrl(url) == HomeAssistant.URL_LOCAL_HTTP) {
            TextView warning = Ui.text(this, getString(R.string.ha_warn_cleartext), 13f, pal.alert);
            warning.setTag("ha_cleartext_warning");
            warning.setPadding(0, Ui.dp(this, 6f), 0, 0);
            list.addView(warning);
        }
        if (HomeAssistant.localNameMayFail(url, android.os.Build.VERSION.SDK_INT)) {
            TextView hint = Ui.text(this, getString(R.string.ha_local_name_hint), 13f, pal.textLo);
            hint.setTag("ha_local_name_hint");
            hint.setPadding(0, Ui.dp(this, 6f), 0, 0);
            list.addView(hint);
        }
        action(getString(R.string.ha_token, token.isEmpty() ? getString(R.string.ha_not_set) : SettingsLogic.maskSecret(token)), pal.c(1), v ->
                HomeAssistantDialog.prompt(this, getString(R.string.ha_token_prompt), "", getString(R.string.ha_token_hint), true, text -> {
                    if (text.isEmpty()) return;
                    HomeAssistant.setToken(this, text);
                    Sfx.play(this, Sfx.CONFIRM);
                    rebuild();
                }));
        if (!HomeAssistant.configured(this)) return;
        action(getString(R.string.ha_import), pal.c(2), v -> {
            toast(getString(R.string.ha_importing), false);
            Sfx.play(this, Sfx.SCAN_START);
            HomeAssistant.async(() -> HomeAssistant.importEntities(getApplicationContext()), (found, error) -> {
                if (isFinishing() || isDestroyed()) return;
                if (error != null) {
                    Sfx.play(this, Sfx.ERROR);
                    ConsoleDialog.message(this, getString(R.string.ha_import_failed), error, new String[]{"OK"}, null);
                    return;
                }
                Sfx.play(this, found.isEmpty() ? Sfx.DENY : Sfx.SCAN_DONE);
                toast(getString(R.string.ha_imported, found.size()), false);
                rebuild();
            });
        });
        List<HomeAssistant.Entity> entities = HomeAssistant.entities(this);
        if (entities.isEmpty()) {
            note(getString(R.string.ha_none));
        } else {
            int shown = Math.min(entities.size(), HA_ROWS_SHOWN);
            for (int i = 0; i < shown; i++) {
                final HomeAssistant.Entity entity = entities.get(i);
                int color = pal.c(new int[]{0, 2, 3, 4, 1, 5}[i % 6]);
                action(getString(R.string.ha_control) + " • " + entity.title() + "  (" + entity.domain().toUpperCase(Locale.US) + ")", color, v -> {
                    if ("climate".equals(entity.domain())) ThermostatDialog.show(this, new HomeAssistant.Climate(entity.id), entity.title());
                    else HomeAssistantDialog.show(this, entity.id, entity.title());
                });
            }
            if (entities.size() > shown) note(getString(R.string.ha_more, entities.size() - shown));
            note(getString(R.string.ha_assign_note));
        }
        action(getString(R.string.ha_forget), Ui.darken(pal.c(3), 0.8f), v -> {
            HomeAssistant.forget(this);
            Sfx.play(this, Sfx.CLOSE);
            rebuild();
        });
    }

    /** Home Assistant entities listed with a CONTROL key in CONFIG (all are offered when assigning tiles). */
    private static final int HA_ROWS_SHOWN = 24;

    /** NEST THERMOSTAT: Device Access setup, linking and the linked thermostats. */
    private void nestSection() {
        label("NEST THERMOSTAT");
        boolean linked = Nest.linked(prefs);
        note(linked ? "Linked. Assign it: long-press any TrekUI segment, tile or dock icon › ASSIGN TREKUI FUNCTION › CLIMATE: <name>. Tapping it opens the TrekUI environmental control." : "Google only lets apps reach Nest through its Device Access program, so this needs a one-time setup (about 15 minutes, and Google charges a one-time US$5 Device Access registration fee):\n1. DEVICE ACCESS CONSOLE: register (accept the terms, pay the fee).\n2. GOOGLE CLOUD: create a project › enable “Smart Device Management API” › OAuth consent screen: External, add your Gmail as a test user, then PUBLISH APP (otherwise Google unlinks you every 7 days) › Credentials › Create OAuth client ID › Web application › Authorized redirect URI: https://www.google.com. Keep the client ID and secret.\n3. Back in the Device Access Console: Create project › paste the OAuth client ID › copy the Project ID.\n4. Enter the three values below, tap LINK ACCOUNT, sign in, choose your home and allow the thermostat. Google then shows its home page: copy the whole address from the address bar and tap PASTE GOOGLE LINK.");
        if (!linked) {
            action("OPEN DEVICE ACCESS CONSOLE", pal.c(0), v -> openUrl("https://console.nest.google.com/device-access"));
            action("OPEN GOOGLE CLOUD CREDENTIALS", pal.c(1), v -> openUrl("https://console.cloud.google.com/apis/credentials"));
            nestField("DEVICE ACCESS PROJECT ID", "nestProject", false, pal.c(2));
            nestField("OAUTH CLIENT ID", "nestClientId", false, pal.c(3));
            nestField("OAUTH CLIENT SECRET", "nestSecret", true, pal.c(4));
            if (Nest.configured(prefs)) {
                action("LINK ACCOUNT (OPENS GOOGLE)", pal.c(0), v -> open(new Intent(Intent.ACTION_VIEW, Nest.authUri(prefs))));
                action("PASTE GOOGLE LINK", pal.c(2), v -> nestPaste());
            }
            return;
        }
        List<Nest.Device> thermostats = Nest.devices(prefs);
        for (int i = 0; i < thermostats.size(); i++) {
            final Nest.Device thermostat = thermostats.get(i);
            action("CONTROL • " + thermostat.label, pal.c(new int[]{0, 2, 3}[i % 3]),
                    v -> ThermostatDialog.show(this, thermostat.name, thermostat.label));
        }
        if (thermostats.isEmpty()) {
            note("No thermostats yet: tap FETCH THERMOSTATS. If none appear, link again and make sure the thermostat is switched on in Google's permission screen.");
        }
        action("FETCH THERMOSTATS", pal.c(1), v -> nestFetch());
        action("UNLINK NEST", Ui.darken(pal.c(3), 0.8f), v -> {
            Nest.unlink(prefs);
            Sfx.play(this, Sfx.CLOSE);
            rebuild();
        });
    }

    /** One Nest credential field (secrets are masked, long ids abbreviated). */
    private void nestField(final String title, final String key, boolean secret, int color) {
        String value = prefs.s(key, "");
        String shown = value.isEmpty() ? "NOT SET" : secret ? SettingsLogic.maskSecret(value) : SettingsLogic.abbreviate(value);
        action(title + " • " + shown, color, v -> ConsoleDialog.prompt(this, title, prefs.s(key, ""), "PASTE IT HERE", text -> {
            prefs.put(key, text.trim());
            Sfx.play(this, Sfx.CONFIRM);
            rebuild();
        }));
    }

    /** PASTE GOOGLE LINK: pre-fills from the clipboard, extracts the code and links the account. */
    private void nestPaste() {
        String clip = "";
        try {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip() != null
                    && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
                if (text != null && text.toString().contains("code=")) clip = text.toString();
            }
        } catch (RuntimeException ignored) {
            // clipboard unavailable
        }
        ConsoleDialog.prompt(this, "PASTE GOOGLE LINK", clip, "HTTPS://WWW.GOOGLE.COM/?CODE=…", text -> {
            final String code = Nest.codeFrom(text);
            if (code.isEmpty()) {
                Sfx.play(this, Sfx.DENY);
                toast("NO CODE IN THAT LINK • COPY THE WHOLE ADDRESS AFTER ALLOWING ACCESS", true);
                return;
            }
            toast("ESTABLISHING LINK…", false);
            Nest.async(() -> {
                Nest.link(getApplicationContext(), code);
                return Nest.fetchDevices(getApplicationContext());
            }, this::nestResult);
        });
    }

    private void nestFetch() {
        Sfx.play(this, Sfx.SCAN_START);
        Nest.async(() -> Nest.fetchDevices(getApplicationContext()), this::nestResult);
    }

    /** Result of linking or fetching thermostats. */
    private void nestResult(List<Nest.Device> thermostats, String error) {
        if (isFinishing() || isDestroyed()) return;
        if (error != null) {
            Sfx.play(this, Sfx.ERROR);
            ConsoleDialog.message(this, "NEST LINK FAILED", error, new String[]{"OK"}, null);
        } else {
            Sfx.play(this, thermostats.isEmpty() ? Sfx.DENY : Sfx.SCAN_DONE);
            toast(thermostats.isEmpty() ? "LINKED • NO THERMOSTATS SHARED"
                    : SettingsLogic.count(thermostats.size(), "THERMOSTAT", "THERMOSTATS") + " ONLINE", true);
        }
        rebuild();
    }

    /** AUDIO SYSTEMS: effects on/off and volume, the eight sound events, phone sounds and the library. */
    private void audioSection() {
        section("AUDIO SYSTEMS", pal.c(3), "AUDIO");
        boolean enabled = Sfx.enabled(this);
        toggle("TREKUI SOUND EFFECTS", "Plays the sounds you import below", enabled, on -> {
            prefs.put("sfx", on);
            if (on) Sfx.init(this);
            else Sfx.release();
            rebuild();
        });
        if (!enabled) {
            note("Sound effects are offline.");
            return;
        }
        volumeSlider();
        note("Plays on Android's system-sounds volume (slider × system volume). Silent in Silent/Vibrate mode and during calls.");
        toggle("FOLLOW ANDROID TOUCH SOUNDS", "Mute TrekUI sounds whenever Android's “Touch interactions” sound is off", Sfx.followTouch(this), on -> {
            prefs.put("sfxTouch", on);
            rebuild();
        });
        int assigned = Sfx.assignedEvents(this);
        label("SOUND LIBRARY  •  " + assigned + " / " + Sfx.EVENTS + " EVENTS WITH SOUND");
        if (assigned == 0) {
            note("This build has no built-in sounds. Import clips below; each file is decoded on the phone, trimmed of silence and gently level-matched. Pitch and timbre are never changed.").setTextColor(pal.c(0));
        } else if (Sfx.hasLibrary(this)) {
            note("Built-in: your recordings (chirp, deny beep, hail, viewscreen sequences, red alert). Tap CHOOSE SOUND to swap clips per event, import a different file, or silence an event.");
        }
        soundSetAndDownload();
        action("IMPORT MORE FILES (AUTO-MATCH)", pal.c(0), v -> pickFiles(REQ_IMPORT_FILES, true, MIME_AUDIO));
        action("IMPORT ZIP (AUTO-MATCH)", pal.c(2), v -> pickFiles(REQ_IMPORT_ZIP, false, MIME_ZIP));
        note("Auto-match: name files after the event (button, navigation, launch, confirm, error, comms, diagnostics, red_alert), or keep common source file names such as denybeep3, hailbeep_clean or “computer beep 12”. Up to 8 button files rotate. Anything unmatched can be assigned below.");
        for (int event = 0; event < Sfx.EVENTS; event++) eventRow(event);
        phoneSounds();
        soundLibrary();
        boolean anyImported = false;
        for (int event = 0; event < Sfx.EVENTS; event++) {
            if (Sfx.variantCount(this, event) > 0) anyImported = true;
        }
        if (anyImported) {
            action("REMOVE ALL IMPORTED SOUNDS", Ui.darken(pal.c(3), 0.7f), v -> ConsoleDialog.message(this, "REMOVE IMPORTED SOUNDS",
                    "Remove every imported sound? Events return to their built-in clips.", new String[]{"CLEAR", "CANCEL"}, which -> {
                        if (which != 0) return;
                        for (int event = 0; event < Sfx.EVENTS; event++) SoundImporter.clear(this, event);
                        Sfx.reload(this);
                        rebuild();
                    }));
        }
        note("Imported sounds are stored privately by TrekUI on this phone and are excluded from Android backups. Third-party recordings belong to their copyright holders: keep them for your personal use and don't redistribute them.");
        if (!Sfx.audioAllowed(this)) {
            note("NOTE: your phone is in Silent/Vibrate mode (or a call), so previews are muted.").setTextColor(pal.alert);
        }
    }

    /** EFFECTS VOLUME slider; plays a button sound when released. */
    private void volumeSlider() {
        final TextView title = label("EFFECTS VOLUME  •  " + Sfx.volume(this) + "%");
        SeekBar slider = new SeekBar(this);
        slider.setMax(100);
        slider.setProgress(Sfx.volume(this));
        slider.setContentDescription("Effects volume");
        slider.setProgressTintList(ColorStateList.valueOf(pal.c(3)));
        slider.setThumbTintList(ColorStateList.valueOf(pal.c(0)));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.alpha(pal.c(3), 0.3f)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                prefs.put("sfxVol", progress);
                title.setText("EFFECTS VOLUME  •  " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                Sfx.playEvent(SettingsActivity.this, Sfx.E_BUTTON, true);
            }
        });
        list.addView(slider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44f)));
    }

    /** One sound event: what plays, a preview, CHOOSE SOUND and ON/OFF. */
    private void eventRow(final int event) {
        int variants = Sfx.variantCount(this, event);
        boolean on = Sfx.eventOn(this, event);
        boolean hasSound = Sfx.hasSound(this, event);
        int color = pal.c(event % 6);
        LinearLayout box = card(color, hasSound ? 0.8f : 0.3f);
        box.addView(Ui.bold(this, Sfx.EVENT_NAMES[event], 16f, hasSound ? color : pal.textHi));
        TextView source = Ui.text(this, eventSource(event, variants), 13f, hasSound ? pal.textHi : Ui.alpha(pal.textLo, 0.7f));
        source.setPadding(0, Ui.dp(this, 2f), 0, 0);
        box.addView(source);
        TextView description = Ui.text(this, Sfx.EVENT_DESC[event], 11f, Ui.alpha(pal.textLo, 0.8f));
        description.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 6f));
        box.addView(description);

        LinearLayout buttons = new LinearLayout(this);
        TextView play = Ui.button(this, "▶", pal.c(1), Ui.LEFT, v -> Sfx.playEvent(this, event, true));
        play.setGravity(Gravity.CENTER);
        play.setContentDescription("Preview " + Sfx.EVENT_NAMES[event]);
        if (!hasSound) play.setAlpha(0.4f);
        buttons.addView(play, new LinearLayout.LayoutParams(0, Ui.dp(this, 42f), 0.8f));
        TextView choose = Ui.button(this, "CHOOSE SOUND", pal.c(0), Ui.SQUARE, v -> chooseSound(event));
        choose.setGravity(Gravity.CENTER);
        Ui.setTextSp(choose, 13f);
        LinearLayout.LayoutParams chooseParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 42f), 2.2f);
        chooseParams.leftMargin = Ui.dp(this, 4f);
        buttons.addView(choose, chooseParams);
        TextView onOff = Ui.button(this, on ? "ON" : "OFF", on ? pal.c(2) : Ui.darken(pal.c(2), 0.4f), Ui.RIGHT, v -> {
            prefs.put("sndOn" + event, !Sfx.eventOn(this, event));
            rebuild();
        });
        onOff.setGravity(Gravity.CENTER);
        Ui.setTextSp(onOff, 13f);
        LinearLayout.LayoutParams onOffParams = new LinearLayout.LayoutParams(0, Ui.dp(this, 42f), 0.9f);
        onOffParams.leftMargin = Ui.dp(this, 4f);
        buttons.addView(onOff, onOffParams);
        box.addView(buttons);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 8f);
        list.addView(box, params);
    }

    /** "IMPORTED: a, b", "LIBRARY: …", "BUILT-IN: …" or "SILENT…" for an event. */
    private String eventSource(int event, int variants) {
        if (variants > 0) {
            StringBuilder sb = new StringBuilder("IMPORTED: ").append(Sfx.variantName(this, event, 0));
            for (int i = 1; i < variants; i++) sb.append(", ").append(Sfx.variantName(this, event, i));
            return sb.toString();
        }
        String builtin = Sfx.builtinFor(this, event);
        if ("none".equals(builtin)) return "SILENT";
        if (Sfx.hasSound(this, event)) {
            return (builtin.startsWith("lib:") ? "LIBRARY: " : "BUILT-IN: ") + SoundLibrary.label(this, builtin);
        }
        return "SILENT • NOTHING ASSIGNED";
    }

    /** CHOOSE SOUND for an event: silent, tap rotation, any library clip, import, or remove the import. */
    private void chooseSound(final int event) {
        String current = Sfx.variantCount(this, event) == 0 ? Sfx.builtinFor(this, event) : null;
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        labels.add(("none".equals(current) ? "● " : "") + "SILENT");
        values.add("none");
        if (Sfx.hasLibrary(this)) {
            labels.add(("#taps".equals(current) ? "● " : "") + "TAP ROTATION (7 SOUNDS)" + ("#taps".equals(Sfx.defaultFor(event)) ? " (DEFAULT)" : ""));
            values.add("#taps");
        }
        labels.add(event == Sfx.E_BUTTON ? "IMPORT FILE(S)… (UP TO 8)" : "IMPORT A FILE…");
        values.add("#import");
        if (Sfx.variantCount(this, event) > 0) {
            labels.add("REMOVE IMPORTED FILE");
            values.add("#remove");
        }
        SoundPicker.pick(this, Sfx.EVENT_NAMES[event], labels.toArray(new String[0]), values.toArray(new String[0]),
                current, Sfx.defaultFor(event), picked -> {
                    if ("#import".equals(picked)) {
                        pickFiles(REQ_ASSIGN + event, event == Sfx.E_BUTTON, MIME_AUDIO);
                        return;
                    }
                    SoundImporter.clear(this, event);
                    if (!"#remove".equals(picked)) prefs.put(Sfx.builtinKey(event), picked);
                    Sfx.reload(this);
                    Sfx.playEvent(this, event, true);
                    rebuild();
                });
    }

    /** PHONE SOUNDS: export clips as the phone's ringtone, notification and alarm (Android 10+). */
    private void phoneSounds() {
        if (!PhoneSounds.available(this)) return;
        label("PHONE SOUNDS");
        note("Use the interface clips as your phone's own sounds. SET saves the sound to the phone's Ringtones, Notifications or Alarms folder and makes it the default. Ringtones chirp twice then pause; red alert loops like the ship's klaxon. Your previous sounds are remembered, and RESTORE puts them back.");
        if (!PhoneSounds.canSetDefault(this)) {
            note("To set defaults, TrekUI needs “Modify system settings” (the same switch the shade's brightness slider uses). Without it, SET only saves the sound so you can pick it in Settings › Sounds and vibration.").setTextColor(pal.c(4));
            action("ALLOW MODIFY SYSTEM SETTINGS", pal.c(4), v -> openWriteSettings());
        }
        for (int type = 0; type < PhoneSounds.NAMES.length; type++) phoneSoundRow(type);
        note("SAVE FOR SPECIFIC APPS saves the notification sound without changing the default, so you can give it to just Messages and Signal (for example INCOMING TRANSMISSION) while everything else keeps its sound.");
        note("Dual SIM: this sets SIM 1's ringtone; pick TrekUI for SIM 2 in Settings › Sounds and vibration › Ringtone."
                + (isSamsung() ? " Samsung Clock keeps a sound per alarm: in Clock, open an alarm › Alarm sound and choose the TrekUI sound." : ""));
    }

    /** One phone sound (0 ringtone, 1 notification, 2 alarm): preview, CHOOSE, SET, RESTORE. */
    private void phoneSoundRow(final int type) {
        final String clip = PhoneSounds.clip(this, type);
        boolean active = PhoneSounds.isOurs(this, type);
        int color = pal.c(new int[]{0, 2, 3}[type]);
        LinearLayout box = card(color, 0.8f);
        box.addView(Ui.bold(this, PhoneSounds.NAMES[type], 16f, color));
        TextView current = Ui.text(this, SoundLibrary.label(this, clip) + (active ? "  •  ACTIVE" : ""), 13f, active ? pal.c(2) : pal.textHi);
        current.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 6f));
        box.addView(current);
        LinearLayout buttons = new LinearLayout(this);
        TextView preview = smallButton("▶", color, v -> previewClip(clip));
        preview.setContentDescription("Preview " + PhoneSounds.NAMES[type]);
        buttons.addView(preview, new LinearLayout.LayoutParams(Ui.dp(this, 52f), Ui.dp(this, 40f)));
        buttons.addView(smallButton("CHOOSE", pal.c(1), v -> SoundPicker.pick(this, PhoneSounds.NAMES[type] + " SOUND",
                new String[0], new String[0], PhoneSounds.clip(this, type), null, picked -> {
                    PhoneSounds.setClip(this, type, picked);
                    previewClip(picked);
                    rebuild();
                })), rowButtonParams(40f, false));
        buttons.addView(smallButton(active ? "SET AGAIN" : "SET", pal.c(0), v -> installPhoneSound(type, true, PhoneSounds.clip(this, type))),
                rowButtonParams(40f, false));
        if (PhoneSounds.hasPrevious(this, type)) {
            buttons.addView(smallButton("RESTORE", Ui.darken(pal.c(3), 0.8f), v -> {
                boolean restored = PhoneSounds.restore(this, type);
                Sfx.play(this, restored ? Sfx.CONFIRM : Sfx.ERROR);
                toast(restored ? "PREVIOUS " + PhoneSounds.NAMES[type] + " RESTORED" : "COULD NOT RESTORE", false);
                rebuild();
            }), rowButtonParams(40f, false));
        }
        box.addView(buttons);
        if (type == 1) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40f));
            params.topMargin = Ui.dp(this, 6f);
            box.addView(smallButton("SAVE FOR SPECIFIC APPS (KEEP DEFAULT)", pal.c(4),
                    v -> installPhoneSound(type, false, PhoneSounds.clip(this, type))), params);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 8f);
        list.addView(box, params);
    }

    /**
     * Saves {@code clip} to the phone's sound folder for {@code type} on a worker thread and, when
     * {@code setDefault}, makes it the default; then explains the result.
     */
    private void installPhoneSound(final int type, final boolean setDefault, final String clip) {
        toast("SAVING " + PhoneSounds.NAMES[type] + "…", false);
        background("phone-sound", () -> {
            String failure = null;
            boolean madeDefault = false;
            try {
                madeDefault = PhoneSounds.install(getApplicationContext(), type, setDefault, clip);
                if (setDefault) PhoneSounds.setClip(getApplicationContext(), type, clip);
            } catch (Exception e) {
                failure = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final String error = failure;
            final boolean isDefault = madeDefault;
            runOnUiIfAlive(() -> {
                Sfx.play(this, error == null ? Sfx.CONFIRM : Sfx.ERROR);
                String name = PhoneSounds.titleFor(this, clip);
                String title;
                String message;
                if (error != null) {
                    title = "COULD NOT SAVE";
                    message = "Error: " + error;
                } else if (isDefault) {
                    title = PhoneSounds.NAMES[type] + " SET";
                    message = "“" + name + "” is now your " + PhoneSounds.NAMES[type].toLowerCase(Locale.US) + " sound.";
                } else if (!setDefault) {
                    title = "SOUND SAVED";
                    message = "“" + name + "” is saved in your Notifications sounds. Pick it inside each app:\n" + (isSamsung() ? "• Samsung Messages: ⋮ › Settings › Notifications › New messages › Sound\n" : "") + "• Signal: profile picture › Settings › Notifications › Messages › Sound\n• Any app: Android Settings › Notifications › App notifications › the app › its message category › Sound.";
                } else {
                    title = "SOUND SAVED";
                    message = "“" + name + "” was saved. Allow “Modify system settings” to set it automatically, or pick it in Settings › Sounds and vibration.";
                }
                ConsoleDialog.message(this, title, message, new String[]{"OK"}, null);
                rebuild();
            });
        });
    }

    /** SOUND LIBRARY: import buttons and collapsible groups of every clip. */
    private void soundLibrary() {
        label("SOUND LIBRARY");
        note("Every TrekUI sound on the phone. Long-press any TrekUI segment, tile or dock icon › ASSIGN SOUND to give it its own; EXPORT makes one your ringtone, notification or alarm. To add more, download sound files on the phone and import the files or a ZIP here.");
        LinearLayout importRow = new LinearLayout(this);
        importRow.addView(smallButton("IMPORT FILES", pal.c(0), v -> pickFiles(REQ_LIB_FILES, true, MIME_AUDIO)), rowButtonParams(42f, true));
        importRow.addView(smallButton("IMPORT ZIP", pal.c(1), v -> pickFiles(REQ_LIB_ZIP, false, MIME_ZIP)), rowButtonParams(42f, false));
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        importParams.topMargin = Ui.dp(this, 6f);
        list.addView(importRow, importParams);
        int groupIndex = 0;
        for (final SoundPicker.Group group : SoundPicker.groups(this)) {
            final boolean open = openSoundGroups.contains(group.name);
            String title = (open ? "▾ " : "▸ ") + group.name + " • " + SettingsLogic.count(group.items.size(), "SOUND", "SOUNDS");
            action(title, pal.c(HomeLogic.accentIndex(groupIndex++)), v -> {
                if (open) openSoundGroups.remove(group.name);
                else openSoundGroups.add(group.name);
                rebuild();
            });
            if (open) {
                for (int i = 0; i < group.items.size(); i++) libraryRow(group.items.get(i), i);
            }
        }
    }

    /** One library clip: preview, EXPORT, and for imported clips a rename/delete menu. */
    private void libraryRow(final SoundLibrary.Entry entry, int position) {
        int color = pal.c(HomeLogic.accentIndex(position));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 12f), Ui.dp(this, 4f), Ui.dp(this, 6f), Ui.dp(this, 4f));
        row.setBackground(Ui.outline(this, pal.panel, Ui.alpha(color, entry.imported ? 0.8f : 0.4f), 12f, 1.2f));
        TextView name = Ui.bold(this, SoundPicker.nameOf(entry), 14f, entry.imported ? color : pal.textHi);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        TextView preview = smallButton("▶", color, v -> previewClip(entry.id));
        preview.setContentDescription("Preview " + SoundPicker.nameOf(entry));
        row.addView(preview, new LinearLayout.LayoutParams(Ui.dp(this, 46f), Ui.dp(this, 36f)));
        LinearLayout.LayoutParams exportParams = new LinearLayout.LayoutParams(Ui.dp(this, entry.imported ? 64f : 80f), Ui.dp(this, 36f));
        exportParams.leftMargin = Ui.dp(this, 6f);
        row.addView(smallButton("EXPORT", pal.c(2), v -> exportMenu(entry)), exportParams);
        if (entry.imported) {
            LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(Ui.dp(this, 40f), Ui.dp(this, 36f));
            moreParams.leftMargin = Ui.dp(this, 6f);
            TextView more = smallButton("⋯", pal.c(3), v -> importedClipMenu(entry));
            more.setContentDescription("More options for " + SoundPicker.nameOf(entry));
            row.addView(more, moreParams);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 6f);
        list.addView(row, params);
    }

    /** RENAME / DELETE for an imported library clip. */
    private void importedClipMenu(final SoundLibrary.Entry entry) {
        ConsoleDialog.options(this, entry.label, new String[]{"RENAME", "DELETE"}, which -> {
            if (which == 0) {
                ConsoleDialog.prompt(this, "RENAME SOUND", entry.label, "NAME", text -> {
                    if (!text.trim().isEmpty()) SoundLibrary.rename(this, entry.id, text);
                    rebuild();
                });
                return;
            }
            SoundLibrary.delete(this, entry.id);
            Sfx.reload(this);
            Sfx.play(this, Sfx.CLOSE);
            rebuild();
        });
    }

    /** EXPORT menu for a library clip (ringtone / notification / alarm, as default or file only). */
    private void exportMenu(final SoundLibrary.Entry entry) {
        if (!PhoneSounds.available(this)) {
            toast(getString(R.string.toast_export_needs_q), true);
            return;
        }
        String[] options = {"SET AS RINGTONE", "SET AS NOTIFICATION SOUND", "SET AS ALARM",
                "SAVE FOR SPECIFIC APPS (NOTIFICATION)", "SAVE AS RINGTONE FILE ONLY"};
        ConsoleDialog.options(this, "EXPORT • " + entry.label, options, which -> {
            switch (which) {
                case 0: installPhoneSound(0, true, entry.id); break;
                case 1: installPhoneSound(1, true, entry.id); break;
                case 2: installPhoneSound(2, true, entry.id); break;
                case 3: installPhoneSound(1, false, entry.id); break;
                default: installPhoneSound(0, false, entry.id); break;
            }
        });
    }

    /**
     * SOUND SET (synthesized / downloaded library / silent) and DOWNLOAD SOUND LIBRARY. The app ships no
     * recordings; the library is downloaded on request from public pages (SoundDownloadUi, docs/SOUNDS.md).
     */
    private void soundSetAndDownload() {
        String set = Sfx.soundSet(this);
        int current = java.util.Arrays.asList(Sfx.SET_KEYS).indexOf(set);
        String[] setNames = getResources().getStringArray(R.array.sound_set_names);
        action(getString(R.string.sound_set_action, setNames[current]), pal.c(1), v -> ConsoleDialog.options(this, getString(R.string.sound_set_title), setNames, which -> {
            Sfx.setSoundSet(this, Sfx.SET_KEYS[which]);
            Sfx.playEvent(this, Sfx.E_CONFIRM, true);
            rebuild();
        }));
        note(getResources().getStringArray(R.array.sound_set_descriptions)[current]);
        note(SoundDownloadUi.status(this));
        action("DOWNLOAD SOUND LIBRARY", pal.c(0), v -> SoundDownloadUi.start(this, false, this::rebuild));
        action("DOWNLOAD CORE SOUNDS ONLY", pal.c(2), v -> SoundDownloadUi.start(this, true, this::rebuild));
        if (SoundCache.count(this) > 0) {
            action("DELETE DOWNLOADED SOUNDS", Ui.darken(pal.c(3), 0.7f), v -> SoundDownloadUi.confirmDelete(this, this::rebuild));
        }
    }

    /** Plays a clip once in the preview player (library files, resources, or the tap rotation). */
    private void previewClip(String clip) {
        try {
            releasePreview();
            if (clip.startsWith("lib:")) {
                previewPlayer = MediaPlayer.create(this, Uri.fromFile(SoundLibrary.file(this, clip)));
            } else if ("#taps".equals(clip)) {
                Sfx.playEvent(this, Sfx.E_BUTTON, true);
            } else {
                // downloaded, bundled or synthesized library clip (LCA-T8/T15): Sfx resolves cache → res/raw → assets
                Sfx.playClip(this, clip, true);
            }
            if (previewPlayer != null) previewPlayer.start();
        } catch (RuntimeException ignored) {
            // unplayable clip: stay silent
        }
    }

    private void releasePreview() {
        if (previewPlayer != null) {
            previewPlayer.release();
            previewPlayer = null;
        }
    }

    /** CHRONOMETER: time format, seconds, stardate and the header text. */
    private void clockSection() {
        section("CHRONOMETER", pal.c(2), "CLOCK");
        choice("TIME FORMAT", new String[]{"SYSTEM", "12-HOUR", "24-HOUR"}, prefs.clockMode(), which -> {
            prefs.put("clock", which);
            changed();
        });
        toggle("SHOW SECONDS", "Ticks every second while the home screen is visible", prefs.seconds(), on -> {
            prefs.put("seconds", on);
            changed();
        });
        toggle("SHOW STARDATE", "Stardate (1000 units per year, 41000.0 at the start of 1987) under the date", prefs.stardate(), on -> {
            prefs.put("stardate", on);
            changed();
        });
        action("EDIT HEADER TEXT: " + prefs.headerText().toUpperCase(Locale.getDefault()), pal.c(2), v ->
                ConsoleDialog.prompt(this, "HEADER TEXT", prefs.headerText(), prefs.headerDefault(), text -> {
                    prefs.put(prefs.headerKey(), text);
                    changed();
                }));
    }

    /** HOME LAYOUT: tiles, dock, panel order/visibility, dialer, console controls, reset. */
    private void layoutSection() {
        section("HOME LAYOUT", pal.c(4), "LAYOUT");
        choice("SHORTCUT TILES", new String[]{"3", "6", "9", "12"}, prefs.tileCount() / 3 - 1, which -> {
            prefs.put("tiles", (which + 1) * 3);
            changed();
        });
        choice("DOCK SLOTS", new String[]{"NONE", "4", "5"}, SettingsLogic.dockIndex(prefs.dockCount()), which -> {
            prefs.put("dock", SettingsLogic.DOCK_SIZES[which]);
            changed();
        });
        label("PANELS (ORDER & VISIBILITY)");
        List<String> order = prefs.widgetOrder();
        Set<String> hidden = prefs.hiddenWidgets();
        for (int i = 0; i < order.size(); i++) panelRow(order, i, hidden.contains(order.get(i)));
        action(getString(R.string.widget_add_panel_config), pal.c(2), v -> WidgetPickActivity.start(this)).setTag("config_add_widget_panel");
        note(getString(R.string.widget_config_note));
        note("Tip: long-press a tile, dock icon or panel title on the home screen to edit it directly.");
        toggle("PHONE ICON OPENS TREKUI DIALER", "Keypad, recents and contacts in TrekUI; calls still use Samsung's in-call screen. Off: the phone icon opens Samsung Phone",
                prefs.b("trekuiDialer", true), on -> {
                    prefs.put("trekuiDialer", on);
                    rebuild();
                });
        consoleControls();
        action("RESET SHORTCUTS & DOCK TO DEFAULTS", pal.c(3), v -> ConsoleDialog.message(this, "CONFIRM RESET",
                "Replace all tiles and dock slots with the defaults?", new String[]{"RESET", "CANCEL"}, which -> {
                    if (which != 0) return;
                    LayoutSnapshots.snapshot(this, LayoutSnapshots.R_RESET); // LCA-T16
                    prefs.sp.edit().remove(MainActivity.KEY_TILES).remove(MainActivity.KEY_DOCK).apply();
                    Sfx.play(this, Sfx.CONFIRM);
                    changed();
                }));
    }

    /**
     * LAYOUT BACKUP (LCA-T16): export / import through the document picker, undo, snapshot now and the
     * automatic snapshots with RESTORE. The flows live in {@link LayoutBackupUi}.
     */
    private void backupSection() {
        section(getString(R.string.config_backup_section), pal.c(2), getString(R.string.config_backup_sidebar));
        note(getString(R.string.config_backup_note));
        LinearLayout files = new LinearLayout(this);
        files.addView(smallButton(getString(R.string.config_export_layout), pal.c(0), v -> LayoutBackupUi.startExport(this)),
                rowButtonParams(46f, true));
        files.addView(smallButton(getString(R.string.config_import_layout), pal.c(1), v -> LayoutBackupUi.startImport(this)),
                rowButtonParams(46f, false));
        files.getChildAt(0).setTag("config_export_layout");
        files.getChildAt(1).setTag("config_import_layout");
        list.addView(files);
        action(getString(R.string.config_undo_layout), pal.c(3), v -> LayoutBackupUi.undo(this, this::changed)).setTag("config_undo_layout");
        action(getString(R.string.config_snapshot_now), pal.c(4), v -> LayoutBackupUi.snapshotNow(this, this::rebuild)).setTag("config_snapshot_now");
        label(getString(R.string.config_snapshots_label));
        note(getString(R.string.config_snapshots_note, LayoutSnapshots.KEEP));
        List<LayoutSnapshots.Entry> snaps = LayoutBackupUi.snapshots(this);
        if (snaps.isEmpty()) note(getString(R.string.config_snapshots_none));
        for (int i = 0; i < snaps.size(); i++) {
            final LayoutSnapshots.Entry entry = snaps.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f));
            TextView name = Ui.text(this, LayoutBackupUi.rowLabel(entry), 15f, pal.textHi);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            TextView restore = smallButton(getString(R.string.config_restore), pal.c(HomeLogic.accentIndex(i)),
                    v -> LayoutBackupUi.restore(this, entry, this::changed));
            restore.setTag("config_restore_" + i);
            restore.setContentDescription(getString(R.string.config_restore) + " " + LayoutBackupUi.rowLabel(entry));
            row.addView(restore, new LinearLayout.LayoutParams(Ui.dp(this, 110f), Ui.dp(this, 44f)));
            list.addView(row);
        }
    }

    /** One panel in PANELS: ▲ ▼ and SHOWN / HIDDEN. */
    private void panelRow(final List<String> order, final int index, final boolean hidden) {
        final String id = order.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(Ui.oneLine(Ui.bold(this, SettingsLogic.panelTitle(id), 15f, hidden ? Ui.alpha(pal.textLo, 0.5f) : pal.textHi)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        TextView up = Ui.button(this, "▲", pal.c(1), Ui.LEFT, v -> {
            if (index == 0) return;
            order.remove(index);
            order.add(index - 1, id);
            prefs.setWidgetOrder(order);
            rebuild();
        });
        up.setGravity(Gravity.CENTER);
        up.setContentDescription("Move " + SettingsLogic.panelTitle(id) + " up");
        TextView down = Ui.button(this, "▼", pal.c(1), Ui.SQUARE, v -> {
            if (index >= order.size() - 1) return;
            order.remove(index);
            order.add(index + 1, id);
            prefs.setWidgetOrder(order);
            rebuild();
        });
        down.setGravity(Gravity.CENTER);
        down.setContentDescription("Move " + SettingsLogic.panelTitle(id) + " down");
        TextView visibility = Ui.button(this, hidden ? "HIDDEN" : "SHOWN", hidden ? Ui.darken(pal.c(3), 0.45f) : pal.c(0), Ui.RIGHT, v -> {
            prefs.setWidgetHidden(id, !hidden);
            rebuild();
        });
        visibility.setGravity(Gravity.CENTER);
        Ui.setTextSp(visibility, 14f);
        row.addView(up, new LinearLayout.LayoutParams(Ui.dp(this, 46f), Ui.dp(this, 44f)));
        LinearLayout.LayoutParams downParams = new LinearLayout.LayoutParams(Ui.dp(this, 46f), Ui.dp(this, 44f));
        downParams.leftMargin = Ui.dp(this, 4f);
        row.addView(down, downParams);
        LinearLayout.LayoutParams visibilityParams = new LinearLayout.LayoutParams(Ui.dp(this, 84f), Ui.dp(this, 44f));
        visibilityParams.leftMargin = Ui.dp(this, 4f);
        row.addView(visibility, visibilityParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 6f);
        list.addView(row, params);
    }

    /** console CONTROLS: every customised home-screen slot with a RESET button. */
    private void consoleControls() {
        int customised = Tile.customisedSlots(prefs);
        label("TREKUI CONTROLS" + (customised > 0 ? "  •  " + customised + " CUSTOMIZED" : ""));
        note("Every TrekUI element on the home screen can launch an app or function: the title, ship line, the four code blocks, clock, date, the elbows and their bar segments, the sidebar buttons, OPERATIONS title, footer and dock caps. Long-press one on the home screen to assign, rename or recolor it.");
        AppRepo repo = AppRepo.get(this);
        for (String[] slotInfo : Tile.SLOTS) {
            final String slot = slotInfo[0];
            Tile custom = Tile.slot(prefs, slot);
            if (custom == null) continue;
            StringBuilder summary = new StringBuilder();
            if (custom.action != null) {
                summary.append(Tile.actionLabel(custom.action));
            } else if (custom.comp != null) {
                AppInfo app = repo.find(custom.comp, custom.user);
                summary.append(app != null ? app.label.toUpperCase(Locale.getDefault()) : "APP NOT INSTALLED");
            }
            if (custom.label != null && !custom.label.isEmpty()) {
                summary.append(summary.length() > 0 ? " • " : "").append("“").append(custom.label.toUpperCase(Locale.getDefault())).append("”");
            }
            if (custom.color >= 0) {
                summary.append(summary.length() > 0 ? " • " : "").append("ACCENT ").append(custom.color + 1);
            }
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f));
            LinearLayout text = new LinearLayout(this);
            text.setOrientation(LinearLayout.VERTICAL);
            text.addView(Ui.bold(this, Tile.slotName(slot), 15f, pal.textHi));
            text.addView(Ui.oneLine(Ui.text(this, summary.toString(), 12f, custom.color >= 0 ? pal.c(custom.color) : pal.c(2))));
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            TextView reset = Ui.button(this, "RESET", pal.c(3), Ui.PILL, v -> {
                Tile.putSlot(prefs, slot, null);
                rebuild();
            });
            reset.setGravity(Gravity.CENTER);
            Ui.setTextSp(reset, 13f);
            reset.setContentDescription("Reset " + Tile.slotName(slot));
            row.addView(reset, new LinearLayout.LayoutParams(Ui.dp(this, 80f), Ui.dp(this, 40f)));
            list.addView(row);
        }
        if (customised > 0) {
            action("RESET ALL TREKUI CONTROLS", Ui.darken(pal.c(3), 0.7f), v -> ConsoleDialog.message(this, "RESET TREKUI CONTROLS",
                    "Return every TrekUI element to its default look and behavior?", new String[]{"RESET", "CANCEL"}, which -> {
                        if (which != 0) return;
                        LayoutSnapshots.snapshot(this, LayoutSnapshots.R_RESET); // LCA-T16
                        Tile.clearSlots(prefs);
                        rebuild();
                    }));
        }
    }

    /** APP GROUPS (LCA-T20): create, edit, reorder and delete the app groups OPEN GROUP unfolds. */
    private void groupsSection() {
        section(getString(R.string.cfg_groups_section), pal.c(3), getString(R.string.cfg_groups_sidebar));
        note(getString(R.string.cfg_groups_note));
        List<AppGroups.Group> groups = AppGroups.load(prefs);
        for (int i = 0; i < groups.size(); i++) {
            final AppGroups.Group group = groups.get(i);
            TextView edit = smallButton(getString(R.string.group_edit), pal.c(HomeLogic.accentIndex(i)),
                    v -> GroupEditor.manage(this, group.id, this::rebuild));
            edit.setTag("group_edit_" + group.id);
            assignRow(group.name, getString(R.string.group_count, group.apps.size()), edit);
        }
        action(getString(R.string.cfg_groups_new), pal.c(0), v -> GroupEditor.create(this, this::rebuild, null)).setTag("group_new");
    }

    /** GESTURES (LCA-T20): assign home gestures, and set up the optional LOCK SCREEN service. */
    private void gesturesSection() {
        section(getString(R.string.cfg_gestures_section), pal.c(4), getString(R.string.cfg_gestures_sidebar));
        note(getString(R.string.cfg_gestures_note));
        for (int i = 0; i < HomeGestures.ALL.length; i++) {
            final String gesture = HomeGestures.ALL[i];
            TextView assign = smallButton(getString(R.string.cfg_gesture_assign), pal.c(HomeLogic.accentIndex(i)),
                    v -> GestureSettings.pick(this, gesture, this::rebuild));
            assign.setTag("gesture_" + gesture);
            assignRow(GestureSettings.label(this, gesture), GestureSettings.describe(this, gesture), assign);
        }
        if (!LockScreen.supported()) {
            note(getString(R.string.cfg_lock_unsupported));
            return;
        }
        boolean on = LockService.running();
        accessRow(getString(R.string.cfg_lock_title), on, getString(on ? R.string.cfg_lock_on : R.string.cfg_lock_off));
        action(getString(on ? R.string.cfg_lock_manage : R.string.cfg_lock_setup), pal.c(on ? 2 : 0), v -> {
            if (LockService.running()) LockScreen.openAccessibilitySettings(this);
            else LockScreen.showDisclosure(this);
        }).setTag("lock_setup");
    }

    /** A row with a title, a smaller description under it and a button on the right (groups, gestures). */
    private void assignRow(String title, String description, TextView button) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(Ui.bold(this, title, 16f, pal.textHi));
        TextView desc = Ui.text(this, description, 12f, pal.textLo);
        desc.setPadding(0, Ui.dp(this, 2f), Ui.dp(this, 8f), 0);
        text.addView(desc);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        button.setContentDescription(title + ": " + description);
        row.addView(button, new LinearLayout.LayoutParams(Ui.dp(this, 96f), Ui.dp(this, 44f)));
        list.addView(row);
    }

    /** APP DRAWER: icon style and colour, vivid tiles, columns, uppercase labels and hidden apps. */
    private void drawerSection() {
        section("APP DRAWER", pal.c(5), "DRAWER");
        String[] styles = Skin.command() ? new String[]{"CONSOLE", "FRAMED", "NATIVE"} : new String[]{"PICTOGRAM", "FRAMED", "NATIVE", "FACTION ART"};
        choice("ICON STYLE", styles, prefs.iconStyle(), which -> {
            prefs.put("icons", which);
            changed();
        });
        note(iconStyleNote());
        iconPackSettings(); // LCA-T22
        choice("ICON COLOR", new String[]{"CLASSIC", "VIVID", "VIVID SOLID"}, prefs.iconTone(), which -> {
            prefs.put("iconTone", which);
            changed();
        });
        note("CLASSIC: pastel outlines. VIVID: high-saturation outlines and pictograms. VIVID SOLID: filled, high-saturation buttons with black pictograms. Applies to the dock and app drawer.");
        toggle("VIVID SHORTCUT TILES", "Also saturate the home screen's shortcut tiles (the TrekUI frame keeps its classic colors)", prefs.vividTiles(), on -> {
            prefs.put("vividTiles", on);
            changed();
        });
        choice("COLUMNS", new String[]{"3", "4", "5"}, prefs.drawerCols() - 3, which -> {
            prefs.put("cols", which + 3);
            changed();
        });
        toggle("UPPERCASE LABELS", null, prefs.upperLabels(), on -> {
            prefs.put("upper", on);
            changed();
        });
        hiddenApps();
    }

    /**
     * ICON PACK (LCA-T22): NONE or an installed icon pack, a preview strip of the chosen pack's
     * icons for installed apps, and AUTO PICTOGRAMS. The pack list comes from a background scan
     * ({@link IconPacks#refreshInstalled}); the list is rebuilt once when the scan finds something new.
     */
    private void iconPackSettings() {
        List<IconPacks.Pack> packs = IconPacks.cachedInstalled();
        IconPacks.refreshInstalled(this, changedPacks -> {
            if (changedPacks && !isFinishing() && !isDestroyed()) rebuild();
        });
        if (packs == null) packs = new ArrayList<>();
        final String current = prefs.s(IconPacks.PREF_PACK, "");
        String[] names = new String[packs.size() + 1];
        names[0] = getString(R.string.icon_pack_none);
        int selected = 0;
        for (int i = 0; i < packs.size(); i++) {
            names[i + 1] = packs.get(i).label.toUpperCase(Locale.getDefault());
            if (packs.get(i).pkg.equals(current)) selected = i + 1;
        }
        final List<IconPacks.Pack> choices = packs;
        choice(getString(R.string.icon_pack_label), names, selected, which -> {
            prefs.put(IconPacks.PREF_PACK, which == 0 ? "" : choices.get(which - 1).pkg);
            IconPacks.clearCache();
            AppRepo.get(this).load();
            changed();
        });
        if (packs.isEmpty()) {
            note(getString(R.string.icon_pack_note_none));
        } else {
            note(getString(R.string.icon_pack_note));
        }
        if (selected > 0) iconPackPreview(current);
        toggle(getString(R.string.icon_auto_glyphs), getString(R.string.icon_auto_glyphs_note),
                prefs.b(IconPacks.PREF_AUTO_GLYPHS, true), on -> {
                    prefs.put(IconPacks.PREF_AUTO_GLYPHS, on);
                    AppRepo.get(this).load();
                    changed();
                });
    }

    /** A strip of the pack's icons for a few installed apps, loaded off the main thread. */
    private void iconPackPreview(final String packPkg) {
        final LinearLayout strip = new LinearLayout(this);
        strip.setTag("icon_pack_preview");
        strip.setGravity(Gravity.CENTER_VERTICAL);
        final int count = metrics.isTwoPane() ? 10 : 6;
        final List<ImageView> cells = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ImageView v = new ImageView(this);
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 52f), 1f);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6f);
            strip.addView(v, lp);
            cells.add(v);
        }
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = Ui.dp(this, 4f);
        sp.bottomMargin = Ui.dp(this, 8f);
        list.addView(strip, sp);
        final android.content.Context app = getApplicationContext();
        final List<AppInfo> apps = new ArrayList<>(AppRepo.get(this).apps());
        IconPacks.async(() -> {
            IconPackParser.Result r = IconPacks.load(app, packPkg);
            List<android.graphics.drawable.Drawable> out = new ArrayList<>();
            for (AppInfo a : apps) {
                if (out.size() >= count) break;
                if (!a.mainProfile) continue;
                android.graphics.drawable.Drawable d = IconPacks.drawable(app, packPkg, r.drawableFor(a.pkg, a.cn.getClassName()));
                if (d != null) out.add(d);
            }
            return out;
        }, icons -> {
            if (icons == null || isFinishing() || isDestroyed()) return;
            for (int i = 0; i < icons.size() && i < cells.size(); i++) cells.get(i).setImageDrawable(icons.get(i));
        });
    }

    /** Explanation of the ICON STYLE options for the current interface. */
    private static String iconStyleNote() {
        if (Skin.pipe()) {
            String engraving = Skin.tob() ? "engraved in brass" : Skin.lounge() ? "in gilt Deco line" : "in red rubber stamp";
            String badges = Skin.tob() ? "brass roundels" : Skin.lounge() ? "gilt octagons" : "kraft labels";
            return "PICTOGRAM: hand-drawn pipe-room pictograms for every app the launcher recognises (a candlestick telephone, a wax-sealed letter, a pocket watch, a tobacco tin…) " + engraving + ", framed icons for the rest. FRAMED: every app icon framed. NATIVE: original icons. FACTION ART: full badges for the 24 core apps (" + badges + "), framed icons for the rest.";
        }
        if (Skin.command()) {
            return "CONSOLE: themed pictograms for every app the launcher recognises (AUTO PICTOGRAMS), console frames for the rest. FRAMED: every app icon in a console frame. NATIVE: original icons.";
        }
        return "PICTOGRAM: themed pictograms for every app the launcher recognises in " + (Skin.imperial() ? "octagon" : "disk") + " frames, framed icons for the rest. FRAMED: every app icon framed. NATIVE: original icons. FACTION ART: the lettered badges from the " + (Skin.imperial() ? "Imperial" : "Collective") + " artwork for the 24 core apps (PH, ME, BR…), framed icons for the rest.";
    }

    /** HIDDEN APPS with UNHIDE buttons. */
    private void hiddenApps() {
        Set<String> hidden = prefs.hiddenApps();
        label("HIDDEN APPS" + (hidden.isEmpty() ? ": NONE" : ""));
        if (hidden.isEmpty()) {
            note("Hide an app by long-pressing it in the drawer.");
            return;
        }
        AppRepo repo = AppRepo.get(this);
        for (final String key : hidden) {
            AppInfo app = null;
            for (AppInfo candidate : repo.apps()) {
                if (candidate.key.equals(key)) {
                    app = candidate;
                    break;
                }
            }
            String name = app != null ? app.label : SettingsLogic.hiddenAppName(key);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(Ui.oneLine(Ui.bold(this, name.toUpperCase(Locale.getDefault()), 15f, pal.textHi)),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
            TextView unhide = Ui.button(this, "UNHIDE", pal.c(2), Ui.PILL, v -> {
                prefs.setAppHidden(key, false);
                Sfx.play(this, Sfx.CONFIRM);
                changed();
            });
            unhide.setGravity(Gravity.CENTER);
            unhide.setContentDescription("Unhide " + name);
            row.addView(unhide, new LinearLayout.LayoutParams(Ui.dp(this, 96f), Ui.dp(this, 44f)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = Ui.dp(this, 6f);
            list.addView(row, params);
        }
    }

    /** WEATHER: location summary, units and clearing the location. */
    private void weatherSection() {
        section("WEATHER", pal.c(2), "WEATHER");
        int mode = prefs.i("wxMode", 0);
        note(mode == 0 ? "LOCATION: NOT SET • Tap SET LOCATION on the home-screen weather panel."
                : "LOCATION: " + prefs.s("wxName", "?").toUpperCase(Locale.getDefault()) + (mode == 1 ? " (DEVICE)" : " (CITY)")
                + "\nChange it by long-pressing the WEATHER panel title.");
        choice("UNITS", new String[]{"°F / MPH", "°C / KM/H"}, prefs.fahrenheit() ? 0 : 1, which -> {
            prefs.put("wxF", which == 0);
            prefs.put("wxTime", 0L);
            changed();
        });
        if (mode != 0) {
            action("CLEAR WEATHER LOCATION", pal.c(3), v -> {
                prefs.sp.edit().remove("wxMode").remove("wxLat").remove("wxLon").remove("wxName").remove("wxCache").remove("wxTime").apply();
                changed();
            });
        }
    }

    /** INTERFACE &amp; ACCESS: animations, startup sequence, cloak, haptics and permission status. */
    private void accessSection() {
        section("INTERFACE & ACCESS", pal.c(0), "ACCESS");
        toggle("ANIMATIONS", "Elbow sweeps, data cascade, transitions (also follows Android's animation setting)", prefs.animations(), on -> {
            prefs.put("anim", on);
            changed();
        });
        toggle("STARTUP SEQUENCE", "A three-second TrekUI power-up the first time the home screen loads after the phone restarts (tap to skip)",
                prefs.b("bootSeq", true), on -> {
                    prefs.put("bootSeq", on);
                    rebuild();
                });
        action("PREVIEW STARTUP SEQUENCE", pal.c(2), v -> {
            prefs.put("bootPreview", true);
            quietFinish = true;
            finish();
        });
        cloakControls();
        redAlertControls();
        quickSettingsTiles();
        toggle("HAPTIC FEEDBACK", "Light vibration on TrekUI buttons", prefs.haptics(), on -> {
            prefs.put("haptics", on);
            changed();
        });
        accessRow("LOCATION (WEATHER)", granted(android.Manifest.permission.ACCESS_COARSE_LOCATION), "Optional • only if you choose device location");
        accessRow("CALENDAR (SCHEDULE)", granted(android.Manifest.permission.READ_CALENDAR), "Optional • read-only");
        accessRow("PHONE CALLS (TREKUI DIALER)", granted(android.Manifest.permission.CALL_PHONE), "Optional • without it the dialer hands numbers to Samsung Phone");
        accessRow("CONTACTS (TREKUI DIALER)", granted(android.Manifest.permission.READ_CONTACTS), "Optional • read-only");
        accessRow("CALL HISTORY (TREKUI DIALER)", granted(android.Manifest.permission.READ_CALL_LOG), "Optional • read-only");
        boolean listenerOn = notificationAccess();
        accessRow("NOTIFICATION ACCESS (TRACK INFO + TREKUI SHADE)", listenerOn, "Optional • media controls work without it");
        action("OPEN TREKUI APP INFO / PERMISSIONS", pal.c(1), v -> open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null))));
        if (!listenerOn) {
            action("OPEN NOTIFICATION ACCESS", pal.c(2), v -> openNotificationAccess());
        }
    }

    /** CLOAK (NIGHT MODE): engage now, duration, brightness and sounds. */
    private void cloakControls() {
        label("CLOAK (NIGHT MODE)");
        boolean active = Cloak.active(this);
        note("For browsing in bed: the TrekUI interface goes dark (black cells with coloured outlines and orange lettering), interface sounds drop to 10%, and the screen brightness goes right down. It disengages by itself after the time below and puts your brightness back (unless you changed it meanwhile). Assign it to a segment, tile or dock icon: long-press › ASSIGN TREKUI FUNCTION › CLOAK (NIGHT MODE). Tap again to decloak early.");
        action(active ? "DISENGAGE CLOAK • " + SettingsLogic.minutesLeft(Cloak.remainingMs(this)) + " MIN LEFT" : "ENGAGE CLOAK NOW",
                active ? pal.c(2) : pal.c(5), v -> {
                    Cloak.toggle(this);
                    rebuild();
                });
        String[] durations = new String[Cloak.MINUTES.length];
        for (int i = 0; i < durations.length; i++) durations[i] = Cloak.MINUTES[i] + " MIN";
        choice("CLOAK LASTS", durations, Math.max(0, Arrays.binarySearch(Cloak.MINUTES, Cloak.minutes(prefs))), which -> {
            prefs.put("cloakMinutes", Cloak.MINUTES[which]);
            rebuild();
        });
        String[] levels = new String[Cloak.LEVELS.length];
        for (int i = 0; i < levels.length; i++) levels[i] = Cloak.LEVELS[i] + "%";
        choice("CLOAKED SCREEN BRIGHTNESS", levels, Math.max(0, Arrays.binarySearch(Cloak.LEVELS, Cloak.level(prefs))), which -> {
            prefs.put("cloakLevel", Cloak.LEVELS[which]);
            rebuild();
        });
        cloakSoundRow("ENGAGE SOUND", "cloakSoundOn", Cloak.engageSound(prefs));
        cloakSoundRow("DISENGAGE SOUND", "cloakSoundOff", Cloak.disengageSound(prefs));
        if (!Cloak.canDimSystem(this)) {
            note("Allow “Modify system settings” (ACCESS section) so cloak can dim the whole phone; without it, only the TrekUI screen dims. For even darker, add Samsung's Extra dim to your quick panel.").setTextColor(pal.c(4));
        }
    }

    /** RED ALERT: engage / stand down now, auto-timeout and the looping sound switch (LCA-T17). */
    private void redAlertControls() {
        label(getString(R.string.red_alert));
        note(getString(R.string.red_alert_settings_note));
        boolean active = RedAlert.active(this);
        action(active ? getString(R.string.red_alert_stand_down_now, RedAlertLogic.countdown(RedAlert.remainingMs(this)))
                        : getString(R.string.red_alert_engage_now),
                active ? pal.c(2) : pal.alert, v -> {
                    RedAlert.toggle(this);
                    rebuild();
                });
        String[] timeouts = new String[RedAlertLogic.TIMEOUT_SECONDS.length];
        for (int i = 0; i < timeouts.length; i++) timeouts[i] = RedAlertLogic.timeoutLabel(RedAlertLogic.TIMEOUT_SECONDS[i]);
        choice(getString(R.string.red_alert_lasts), timeouts, RedAlertLogic.timeoutIndex(RedAlert.timeoutSeconds(prefs)), which -> {
            prefs.put(RedAlert.KEY_TIMEOUT, RedAlertLogic.TIMEOUT_SECONDS[which]);
            rebuild();
        });
        toggle(getString(R.string.red_alert_sound), getString(R.string.red_alert_sound_desc), RedAlert.soundOn(prefs), on -> {
            prefs.put(RedAlert.KEY_SOUND, on);
            RedAlert.check(this);
            rebuild();
        });
    }

    /** QUICK SETTINGS TILES: the system "add tile" prompt on Android 13+, instructions below. */
    private void quickSettingsTiles() {
        label(getString(R.string.qs_section));
        note(getString(R.string.qs_section_note));
        if (!QsTiles.canRequestAdd()) {
            note(getString(R.string.qs_section_note_old));
            return;
        }
        action(getString(R.string.qs_add_cloak), pal.c(5), v -> QsTiles.requestAdd(this, CloakTileService.class,
                R.string.qs_cloak, R.drawable.ic_qs_cloak, this::tileAddResult));
        action(getString(R.string.qs_add_red_alert), pal.c(3), v -> QsTiles.requestAdd(this, RedAlertTileService.class,
                R.string.qs_red_alert, R.drawable.ic_qs_red_alert, this::tileAddResult));
    }

    /** Toast for the result of {@link QsTiles#requestAdd} (StatusBarManager TILE_ADD_REQUEST_RESULT_* codes). */
    private void tileAddResult(int code) {
        if (code == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) toast(getString(R.string.qs_added), false);
        else if (code == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) toast(getString(R.string.qs_already_added), false);
        else if (code == -1) toast(getString(R.string.toast_not_available), false);
        else toast(getString(R.string.qs_not_added), false);
    }

    /** Sound picker row for cloak engage / disengage. */
    private void cloakSoundRow(final String title, final String key, String currentClip) {
        action(title + " • " + SoundLibrary.label(this, currentClip), pal.c(3), v -> {
            String current = prefs.s(key, null);
            String[] labels = {("none".equals(current) ? "● " : "") + "SILENT"};
            SoundPicker.pick(this, title, labels, new String[]{"none"}, current, null, picked -> {
                prefs.put(key, picked);
                if (!"none".equals(picked)) previewClip(picked);
                rebuild();
            });
        });
    }

    /** ABOUT: version, credits and privacy. */
    private void aboutSection() {
        section("ABOUT", pal.c(1), "ABOUT");
        note("TrekUI Launcher " + version() + ". An original science-fiction console interface for Android.\n\nFonts: SIL Open Font License 1.1 (notices in the app's font folder). Weather data: Open-Meteo.com (CC BY 4.0). Sounds: synthesized by the app; optional recordings are downloaded or imported by you, for your personal use.\n\nPrivacy: no accounts, no analytics. The only network traffic is the weather request (coordinates only) and the services you connect yourself.");
    }

    // =================================================================================================
    // Files, export, import
    // =================================================================================================

    /** Opens the system document picker for audio files or a ZIP. */
    private void pickFiles(int requestCode, boolean multiple, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mimeType);
        if (MIME_ZIP.equals(mimeType)) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{MIME_ZIP, "application/x-zip-compressed", "application/octet-stream"});
        }
        if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, requestCode);
        } catch (RuntimeException e) {
            toast(getString(R.string.toast_no_file_picker), false);
        }
    }

    /** All URIs returned by a document picker (multi-select or single). */
    private static List<Uri> pickedUris(Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        return uris;
    }

    @Override
    protected void onActivityResult(final int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (LayoutBackupUi.onResult(this, requestCode, resultCode, data, this::changed)) return; // LCA-T16
        if (requestCode == MainActivity.REQ_HOME_ROLE) {
            if (MainActivity.isDefaultHome(this)) Sfx.play(this, Sfx.STATUS_OK);
            return;
        }
        if (resultCode != RESULT_OK || data == null) return;
        final List<Uri> uris = pickedUris(data);
        if (uris.isEmpty()) return;
        final Dialog progress = ConsoleDialog.message(this, "PROCESSING AUDIO", "Decoding, trimming and level-matching…", new String[0], null);
        progress.setCancelable(false);
        background("sound-import", () -> {
            final SoundImporter.Report report = importSounds(requestCode, uris);
            runOnUiIfAlive(() -> {
                try {
                    progress.dismiss();
                } catch (RuntimeException ignored) {
                    // window already gone
                }
                Sfx.reload(this);
                showReport(report);
                rebuild();
            });
        });
    }

    /** Imports picked files on a worker thread, according to which button asked for them. */
    private SoundImporter.Report importSounds(int requestCode, List<Uri> uris) {
        SoundImporter.Report report = new SoundImporter.Report();
        try {
            if (requestCode == REQ_LIB_FILES || requestCode == REQ_LIB_ZIP) {
                List<SoundImporter.Item> items = requestCode == REQ_LIB_ZIP ? SoundImporter.unzip(this, uris.get(0)) : itemsFor(uris);
                SoundImporter.Report result = SoundLibrary.importItems(this, items);
                report.lines.addAll(result.lines);
                report.failed.addAll(result.failed);
                report.assigned = result.assigned;
                if (requestCode == REQ_LIB_ZIP) SoundImporter.deleteTree(new File(getCacheDir(), IMPORT_CACHE_DIR));
                if (report.assigned > 0) report.lines.add(0, "ADDED TO THE SOUND LIBRARY:");
            } else if (requestCode == REQ_IMPORT_ZIP) {
                Uri zip = uris.get(0);
                SoundImporter.Report result = SoundImporter.importAuto(this, SoundImporter.unzip(this, zip), SoundImporter.displayName(this, zip));
                copyReport(result, report);
                SoundImporter.deleteTree(new File(getCacheDir(), IMPORT_CACHE_DIR));
            } else {
                List<SoundImporter.Item> items = itemsFor(uris);
                String source = items.size() == 1 ? items.get(0).name : items.size() + " SELECTED FILES";
                if (requestCode >= REQ_ASSIGN && requestCode < REQ_ASSIGN + Sfx.EVENTS) {
                    int event = requestCode - REQ_ASSIGN;
                    int assigned = SoundImporter.assign(this, event, items, source, report);
                    if (assigned > 0) {
                        report.assigned = 1;
                        report.lines.add(Sfx.EVENT_NAMES[event] + "  ←  " + items.get(0).name + (assigned > 1 ? "  +" + (assigned - 1) : ""));
                    }
                } else {
                    copyReport(SoundImporter.importAuto(this, items, source), report);
                }
            }
        } catch (Throwable t) {
            report.failed.add(t.getMessage() != null ? t.getMessage() : t.toString());
        }
        return report;
    }

    private List<SoundImporter.Item> itemsFor(List<Uri> uris) {
        List<SoundImporter.Item> items = new ArrayList<>();
        for (Uri uri : uris) items.add(new SoundImporter.Item(SoundImporter.displayName(this, uri), uri, null));
        return items;
    }

    private static void copyReport(SoundImporter.Report from, SoundImporter.Report to) {
        to.lines.addAll(from.lines);
        to.unmatched.addAll(from.unmatched);
        to.failed.addAll(from.failed);
        to.assigned = from.assigned;
    }

    /** The import result dialog. */
    private void showReport(SoundImporter.Report report) {
        ConsoleDialog.message(this, report.assigned > 0 ? "IMPORT COMPLETE" : "IMPORT RESULT",
                SettingsLogic.importReport(report.lines, report.unmatched, report.failed), new String[]{"OK"}, null);
        if (report.assigned > 0) Sfx.playEvent(this, Sfx.E_CONFIRM, true);
    }

    /** EXPORT console PANEL ART: copies the theme's Quick Panel images to the gallery. */
    private void exportPanelArt() {
        int theme = themeIndex();
        String[] names;
        try {
            names = getAssets().list("qp/" + theme);
        } catch (Exception e) {
            names = null;
        }
        if (names == null) names = new String[0];
        String[][] files = new String[names.length][];
        for (int i = 0; i < names.length; i++) files[i] = new String[]{"qp/" + theme + "/" + names[i], names[i]};
        exportImages(files, "Pictures/TrekUI Quick Panel/" + themeFolder(theme) + "/", "ART EXPORTED",
                "In QuickStar › Edit panel style, pick each element and choose the matching image from your Gallery:\n• Button box → qp_button_box\n• Buttons → qp_button_1 … 8 (wide buttons → qp_button_wide)\n• Brightness → qp_brightness\n• Volume → qp_volume\n• Media player → qp_media_player");
    }

    /**
     * Selected Command colour theme, as the index of the bundled app-theme / Quick Panel art set that
     * fits it (art exists for the first six themes; the 6.3 themes borrow the closest one).
     */
    private int themeIndex() {
        return SubThemes.commandArtIndex(prefs.subTheme(Skin.COMMAND));
    }

    private static String themeFolder(int theme) {
        return Palette.ALL[theme].name.replace('-', ' ');
    }

    /**
     * Copies asset images into the shared gallery (MediaStore, API 29+) under {@code folder}, replacing
     * earlier exports with the same name, then shows {@code doneTitle} / {@code doneHelp}.
     *
     * @param files pairs of {asset path, display name}
     */
    private void exportImages(final String[][] files, final String folder, final String doneTitle, final String doneHelp) {
        if (Build.VERSION.SDK_INT < 29) {
            toast(getString(R.string.toast_export_needs_q), true);
            return;
        }
        toast(getString(R.string.toast_exporting), false);
        background("export", () -> {
            int saved = 0;
            String failure = null;
            ContentResolver resolver = getContentResolver();
            Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            for (String[] file : files) {
                try {
                    if (exportImage(resolver, collection, file[0], file[1], folder)) saved++;
                } catch (Exception e) {
                    failure = e.getMessage() != null ? e.getMessage() : e.toString();
                }
            }
            final int count = saved;
            final String error = failure;
            runOnUiIfAlive(() -> {
                Sfx.play(this, count > 0 ? Sfx.CONFIRM : Sfx.ERROR);
                String message = count > 0
                        ? SettingsLogic.count(count, "image", "images") + " saved to " + folder + "\n\n" + doneHelp
                        : "Could not save images" + (error != null ? ": " + error : ".");
                ConsoleDialog.message(this, count > 0 ? doneTitle : "EXPORT FAILED", message, new String[]{"OK"}, null);
            });
        });
    }

    /**
     * Writes one asset image into MediaStore as a pending item, publishing it when complete. A failed
     * copy removes its half-written entry instead of leaving a broken pending image behind.
     */
    @android.annotation.TargetApi(29)
    private boolean exportImage(ContentResolver resolver, Uri collection, String asset, String name, String folder) throws Exception {
        try {
            resolver.delete(collection, MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{folder, name});
        } catch (RuntimeException ignored) {
            // nothing to replace, or not ours to delete
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, name.endsWith(".gif") ? "image/gif" : "image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri item = resolver.insert(collection, values);
        if (item == null) return false;
        boolean complete = false;
        try (InputStream in = getAssets().open(asset); OutputStream out = resolver.openOutputStream(item)) {
            if (out == null) return false;
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            complete = true;
        } finally {
            if (!complete) {
                try {
                    resolver.delete(item, null, null);
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(item, values, null, null);
        return true;
    }

    // =================================================================================================
    // Small helpers
    // =================================================================================================

    /** Opens Good Lock, or its Galaxy Store page (app, then web). */
    private void openGoodLock() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(PKG_GOOD_LOCK);
        if (launch != null) {
            open(launch);
        } else if (!tryStart(new Intent(Intent.ACTION_VIEW, Uri.parse("samsungapps://ProductDetail/" + PKG_GOOD_LOCK)))) {
            openUrl("https://galaxystore.samsung.com/detail/" + PKG_GOOD_LOCK);
        }
    }

    /** "Modify system settings" for this app. */
    private void openWriteSettings() {
        open(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", getPackageName(), null)));
    }

    /** Notification access: straight to console's own toggle on API 30+, else the full list. */
    private void openNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new ComponentName(this, MediaListener.class).flattenToString());
            if (tryStart(detail)) return;
        }
        open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    private void openUrl(String url) {
        open(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    /** Starts an activity; false when nothing handles the intent. */
    private boolean tryStart(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Starts an activity or explains that the device has nothing for it. */
    private void open(Intent intent) {
        if (!tryStart(intent)) {
            Sfx.play(this, Sfx.ERROR);
            toast(getString(R.string.toast_not_available), false);
        }
    }

    /** True when a package is installed and visible to us (see the manifest's &lt;queries&gt;). */
    @SuppressWarnings("deprecation")
    /** Samsung / One UI device: gates the Samsung-only (One UI, Good Lock) notes and recipes. */
    private static boolean isSamsung() {
        return "samsung".equalsIgnoreCase(Build.MANUFACTURER);
    }

    private boolean installed(String pkg) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                getPackageManager().getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0));
            } else {
                getPackageManager().getApplicationInfo(pkg, 0);
            }
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private boolean granted(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /** True when the user granted notification access to {@link MediaListener}. */
    private boolean notificationAccess() {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(getPackageName());
    }

    /** A down-sampled asset image (cached), or null when it is missing. */
    private Bitmap thumbAsset(String path, int sampleSize) {
        String key = path + "@" + sampleSize;
        Bitmap cached = THUMBS.get(key);
        if (cached != null) return cached;
        try (InputStream in = getAssets().open(path)) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize;
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, options);
            if (bitmap != null) THUMBS.put(key, bitmap);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}
