package com.gungreeneyes.trekui;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.provider.Telephony;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.gungreeneyes.trekui.licensing.Licensing;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * The launcher's home screen (the HOME activity) and the hub every other home-screen class talks to.
 *
 * <p>Everything is built in code, no XML layouts. {@link #buildUi()} rebuilds the whole view tree
 * whenever preferences change ({@link Prefs#version()}), the configuration changes (rotation, window
 * resize, fold/unfold, multi-window) or cloak mode flips. The tree is:
 * <pre>
 *   GestureRoot (swipe up = drawer, swipe down = console shade)
 *     home       header ConsoleFrame (title, code blocks, clock, date, info line)
 *                main ConsoleFrame   (APPS / SEARCH / CONFIG sidebar, tiles + panels or a reveal hub)
 *                dock              (left cap, dock icons, right cap)
 *     DrawerView, ShadeView, DialerView   (full-screen overlays, hidden until opened)
 * </pre>
 * Panels and overlays reach back into this class's package-visible fields and helpers (slotColor,
 * bindSlot, tiles, …), so those names are part of the internal API and must not be renamed casually.
 *
 * <p><b>Layout.</b> Sizes adapt through {@link ScreenMetrics}: wide landscape windows (landscape phones,
 * landscape tablets, unfolded foldables, large multi-window panes) put the header and main frames side by
 * side, and wider windows give the tile grid more columns. The window is edge-to-edge on every API level; system-bar and
 * display-cutout insets are applied as padding (see {@link #applyInsets()}).
 *
 * <p><b>Back.</b> Home never finishes on back: back closes the topmost overlay (group panel, hub panel,
 * dialer, shade, drawer) or scrolls home to the top. On API 33+ this is an
 * {@link OnBackInvokedCallback} (predictive back is enabled in the manifest); below, {@link #onBackPressed()}.
 *
 * <h2>TEST HOOKS</h2>
 * Views get stable resource IDs (res/values/ids.xml) and the same string as their view tag, so
 * UiAutomator ({@code By.res(Pattern.compile(".*:id/home_root"))}) and Espresso ({@code withId} /
 * {@code withTagValue}) can find them in every interface:
 * <pre>
 *   home_root          the GestureRoot (whole home window)            all interfaces
 *   home_header        header ConsoleFrame                               all interfaces
 *   home_main          main ConsoleFrame                                 all interfaces
 *   home_tiles         tile container (grid or reveal hub)             console homes
 *   home_dock          dock row                                        when dock slots &gt; 0
 *   home_clock         clock row (slot "clock")                        all interfaces
 *   home_date          date line (slot "date")                         all interfaces
 *   drawer_button      sidebar APPS (slot "nav0")                      all interfaces
 *   search_button      sidebar SEARCH (slot "nav1")                    all interfaces
 *   settings_button    sidebar CONFIG (slot "nav2")                    all interfaces
 *   dock_apps_button   dock left cap (slot "capL", opens all apps)     when dock slots &gt; 0
 *   drawer_view / shade_view / dialer_view   the overlays (GONE until opened)
 *   hub_panel_overlay  reveal-layout panel overlay                     reveal layouts
 * </pre>
 * Tiles have content descriptions (their label, or "Assign tile N" when empty).
 *
 * <h2>LICENSING HOOKS</h2>
 * {@code Licensing.init} in {@link #onCreate} and {@code Licensing.onHomeResumed} in {@link #onResume},
 * marked {@code // HOOK(licensing)}.
 */
public class MainActivity extends Activity implements AppRepo.Listener {

    // ---- request codes (shared with panels and the dialer) -----------------------------------------
    static final int REQ_LOCATION = 11;
    static final int REQ_CALENDAR = 12;
    static final int REQ_HOME_ROLE = 13;
    static final int REQ_CALL = 14;
    static final int REQ_CONTACTS = 15;
    static final int REQ_CALLLOG = 16;

    // ---- preference keys -----------------------------------------------------------------------------
    static final String KEY_TILES = "tilesJson";
    static final String KEY_DOCK = "dockJson";
    private static final String KEY_BOOT_SEQUENCE = "bootSeq";
    private static final String KEY_BOOT_SEEN = "bootSeen";
    private static final String KEY_BOOT_PREVIEW = "bootPreview";
    private static final String KEY_WELCOMED = "welcomed";
    private static final String KEY_CONSOLE_DIALER = "trekuiDialer";
    private static final String KEY_CONSOLE_SHADE = "shadeConsole";
    private static final String KEY_WALLPAPER_DIM = "dim";
    private static final String KEY_STATION_SOUND = "stnSnd_";
    private static final String KEY_PERMISSION_ASKED = "permAsked_";

    // ---- timing --------------------------------------------------------------------------------------
    /** After a reboot without a boot counter, the startup sequence only plays this soon after boot. */
    private static final long BOOT_WINDOW_MS = 5 * 60_000L;
    /** Returning from a comms app within this long plays the "channel closed" chirp. */
    private static final long COMMS_RETURN_WINDOW_MS = 60 * 60_000L;
    private static final long WELCOME_DELAY_MS = 900L;
    private static final long ALARM_LOOKAHEAD_MS = 24 * 60 * 60_000L;

    /** Background mode values from {@link Prefs#bgMode()}. */
    private static final int BG_BLACK = 0, BG_PATTERN = 1, BG_WALLPAPER = 2;
    private static final int DEFAULT_WALLPAPER_DIM = 55;
    /** Icon styles from {@link Prefs#iconStyle()}. */
    private static final int ICON_STYLE_NATIVE = 2, ICON_STYLE_FACTION_ART = 3;
    /** Collective tiles draw their ink in the dark matrix green, except while cloaked. */
    private static final int COLLECTIVE_INK = 0xFF06100C;
    /** Header clock size in sp before {@link #fitClock()} shrinks it to fit. */
    private static final float CLOCK_SP = 70f;
    /** Minimum width (dp) for the side-by-side header/main layout. */
    private static final float TWO_PANE_MIN_WIDTH_DP = 600f;
    /** Header and main frame weights in the side-by-side layout. */
    private static final float HEADER_PANE_WEIGHT = 2f, MAIN_PANE_WEIGHT = 3f;
    /** Widest the reveal-hub panel card gets on tablets. */
    private static final float HUB_PANEL_MAX_WIDTH_DP = 560f;

    static boolean startupPlayed;

    // ---- views and state used by panels (package-private API) -----------------------------------
    TextView ampmView;
    View clockRowView;
    TextView clockView;
    TextView dateView;
    TextView infoView;
    TextView shipView;
    ConsoleFrame headerFrame;
    ConsoleFrame mainFrame;
    ScrollView homeScroll;
    LinearLayout tilesBox;
    LinearLayout dockIcons;
    List<Tile> tiles;
    List<Tile> dock;
    Palette pal;
    Prefs prefs;
    /** Hosts other apps' widgets shown in {@link WidgetPanel}s; listens between onStart and onStop. */
    WidgetHost widgetHost;
    AppRepo repo;
    ShadeView shade;
    final List<Panel> panels = new ArrayList<>();
    final Handler handler = new Handler(Looper.getMainLooper());
    /** System-bar + cutout insets in px, read in {@link #readInsets}. */
    int insetTop, insetBottom, insetLeft, insetRight;
    /** Extra bottom inset taken by the soft keyboard beyond the navigation bar. */
    int insetIme;

    // ---- private state -------------------------------------------------------------------------------
    private DialerView dialer;
    private DrawerView drawer;
    private LinearLayout home;
    private GestureRoot root;
    private Dialog openDialog;
    private ScreenMetrics metrics;
    private String dialerPkg;
    private Intent lastBattery;
    private long commLaunchAt;
    private long lastCascade;
    private long lastMinute = -1;
    private int builtVersion = -1;
    private boolean resumed;
    private final HomeLogic.BatteryWatch batteryWatch = new HomeLogic.BatteryWatch();
    private final Random rnd = new Random();
    /** Text views whose numbers the data cascade shuffles. */
    private final List<TextView> codes = new ArrayList<>();
    private OnBackInvokedCallback backCallback;

    // ---- reveal-hub state (console CASCADE / TACTICAL DEPLOY / HUB AND SPOKE layouts) ------------------
    private FrameLayout hubOverlay;
    private FrameLayout hubPanelHolder;
    private int hubPanelCount;
    private int hubPage = HomeLogic.HUB_PAGE_TILES;
    private int hubPanelOpen = -1;
    private final List<View> hubPanelViews = new ArrayList<>();
    private final List<Panel> hubPanelObjs = new ArrayList<>();
    private final List<String> hubPanelNames = new ArrayList<>();
    private final List<Integer> hubPanelColors = new ArrayList<>();
    private final List<Stations.Station> hubStations = new ArrayList<>();

    /** LCA-T23: re-reads unread counts into every badged tile, hub node and dock key. */
    private final Runnable badgeRefresh = () -> NotificationBadges.refresh(getWindow().getDecorView());

    /** Updates the clock every minute (or second), ticks panels once a minute and drives the cascade. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            updateClock();
            long minute = now / 60_000L;
            if (minute != lastMinute) {
                if (lastMinute != -1) {
                    for (Panel panel : panels) panel.tick();
                }
                lastMinute = minute;
                if (Ui.cloak != Cloak.active(MainActivity.this)) {
                    Cloak.check(MainActivity.this);
                    buildUi();
                }
                updateInfo();
            }
            boolean animate = Ui.animate(MainActivity.this);
            if (animate && now - lastCascade > HomeLogic.CASCADE_PERIOD_MS) {
                cascadeStep();
                lastCascade = now;
            }
            handler.postDelayed(this, HomeLogic.nextTickDelayMs(now, prefs.seconds(), animate));
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // The sticky broadcast replayed on registration is not a change: don't announce it.
            onBattery(intent, isInitialStickyBroadcast());
        }
    };

    /** Callback for {@link #pickFunction}: the chosen console function, its argument and default label. */
    interface FunctionPicked {
        void picked(String action, String arg, String label);
    }

    /** Callback for {@link #pickSound}: the chosen clip id, or null for the default sound. */
    interface SoundPicked {
        void picked(String clip);
    }

    // =================================================================================================
    // Lifecycle
    // =================================================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Licensing.init(this); // HOOK(licensing): trial clock / purchase screen
        prefs = Prefs.get(this);
        prefs.dropRetiredData(this); // the interfaces removed in 44 leave records and keys behind
        LayoutSnapshots.checkUpgrade(this); // LCA-T16: snapshot the layout when the versionCode changed
        repo = AppRepo.get(this);
        widgetHost = new WidgetHost(this);
        repo.addListener(this);
        setupEdgeToEdge(this);
        registerBackHandler();
        Sfx.init(this);
        buildUi();
        repo.load();
        if (!startupPlayed) {
            startupPlayed = true;
            if (!bootSequence(false)) {
                Sfx.play(this, Sfx.STARTUP);
            }
        }
    }

    /** Hosted widgets receive updates only while the home is visible (widget panels, LCA-T19). */
    @Override
    protected void onStart() {
        super.onStart();
        widgetHost.start();
    }

    @Override
    protected void onStop() {
        widgetHost.stop();
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Licensing.onHomeResumed(this); // HOOK(licensing): session check on every return home
        MediaListener.setOnBadges(badgeRefresh); // LCA-T23: unread badges while home is in front
        Cloak.check(this);
        if (prefs.version() != builtVersion) {
            buildUi();
        }
        resumed = true;
        Sfx.init(this);
        preloadElementSounds();
        lastBattery = ContextCompat.registerReceiver(this, batteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        if (lastBattery != null) {
            onBattery(lastBattery, true);
        }
        for (Panel panel : panels) panel.resume();
        if (shade != null && shade.isOpen()) {
            shade.startUpdates();
        }
        handler.removeCallbacks(ticker);
        ticker.run();
        if (commLaunchAt > 0) {
            if (System.currentTimeMillis() - commLaunchAt < COMMS_RETURN_WINDOW_MS) {
                Sfx.play(this, Sfx.CHANNEL_CLOSE);
            }
            commLaunchAt = 0L;
        }
        if (prefs.b(KEY_BOOT_PREVIEW, false)) {
            prefs.put(KEY_BOOT_PREVIEW, false);
            bootSequence(true);
        }
        Onboarding.maybeStart(this); // LCA-T16: first-run tour (after the default-home welcome) or a replay
        if (!prefs.b(KEY_WELCOMED, false)) {
            prefs.put(KEY_WELCOMED, true);
            handler.postDelayed(() -> {
                if (resumed && !isDefaultHome(this)) welcome();
            }, WELCOME_DELAY_MS);
        }
    }

    @Override
    protected void onPause() {
        if (hubPage != HomeLogic.HUB_PAGE_TILES) {
            hubPage = HomeLogic.HUB_PAGE_TILES;
            refreshTiles();
        }
        resumed = false;
        MediaListener.setOnBadges(null); // LCA-T23
        handler.removeCallbacks(ticker);
        try {
            unregisterReceiver(batteryReceiver);
        } catch (IllegalArgumentException notRegistered) {
            // onResume failed before registering; nothing to undo
        }
        for (Panel panel : panels) panel.pause();
        if (shade != null) {
            shade.stopUpdates();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        repo.removeListener(this);
        handler.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        Sfx.release();
        super.onDestroy();
    }

    /** Rotation, window resize, fold/unfold, density or font change: rebuild for the new window. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        buildUi();
    }

    /** Pressing Home while already home closes whatever is open, or scrolls back to the top. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent); // keep getIntent() current (licensing debug extras read it)
        if (!Intent.ACTION_MAIN.equals(intent.getAction())) return;
        if (openDialog != null && openDialog.isShowing()) {
            openDialog.dismiss();
        }
        if (GroupPanel.close(this)) return; // LCA-T20
        if (closeHubPanel()) return;
        if (dialer != null && dialer.isOpen()) {
            dialer.close(resumed);
        } else if (shade != null && shade.isOpen()) {
            shade.close(resumed);
        } else if (drawer != null && drawer.isOpen()) {
            drawer.close(resumed);
        } else if (homeScroll != null) {
            if (resumed) homeScroll.smoothScrollTo(0, 0);
            else homeScroll.scrollTo(0, 0);
        }
    }

    /** Back before API 33 (predictive back replaces this from 33 on; see {@link #registerBackHandler()}). */
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("GestureBackNavigation") // API 33+ gestures go to backCallback
    @Override
    public void onBackPressed() {
        handleBack();
    }

    /**
     * Registers the predictive-back callback on API 33+. Home always consumes back (it must never
     * finish), so the callback stays registered for the activity's lifetime.
     */
    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
    }

    /** Closes the topmost home overlay, or scrolls the home screen back to the top. */
    private void handleBack() {
        if (GroupPanel.close(this)) return; // LCA-T20
        if (closeHubPanel()) return;
        if (dialer != null && dialer.isOpen()) {
            dialer.back();
        } else if (shade != null && shade.isOpen()) {
            shade.close(true);
        } else if (drawer != null && drawer.isOpen()) {
            drawer.back();
        } else if (homeScroll != null) {
            homeScroll.smoothScrollTo(0, 0);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_HOME_ROLE && isDefaultHome(this)) {
            Sfx.play(this, Sfx.STATUS_OK);
        }
    }

    /** Forwards runtime-permission results to every panel and the dialer (they own the requests). */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // An empty result means the request was interrupted (e.g. the dialog was dismissed): treat as denied.
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Sfx.play(this, granted ? Sfx.CONFIRM : Sfx.DENY);
        for (Panel panel : panels) panel.onPermissionResult(requestCode, granted);
        if (dialer != null) {
            dialer.onPermission(requestCode, granted);
        }
    }

    /**
     * Requests one runtime permission with the full modern flow (API 26–37): if it is already granted the
     * result is delivered straight away; if Android will no longer show the dialog (denied with "don't ask
     * again", or twice on API 30+) the app's permission settings are opened instead; otherwise the system
     * dialog is shown. The result arrives in {@link #onRequestPermissionsResult} with {@code requestCode}.
     */
    void requestRuntimePermission(String permission, int requestCode) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            onRequestPermissionsResult(requestCode, new String[]{permission}, new int[]{PackageManager.PERMISSION_GRANTED});
            return;
        }
        String askedKey = KEY_PERMISSION_ASKED + permission;
        if (prefs.b(askedKey, false) && !shouldShowRequestPermissionRationale(permission)) {
            toast(getString(R.string.toast_permission_settings));
            startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)), null);
            return;
        }
        prefs.put(askedKey, true);
        requestPermissions(new String[]{permission}, requestCode);
    }

    @Override
    public void onAppsChanged() {
        if (tiles == null || dock == null) {
            loadTiles();
        }
        refreshTiles();
        refreshDock();
        if (drawer != null) {
            drawer.onAppsChanged();
        }
    }

    /** Remembers the dialog on screen so Home / opening an overlay can dismiss it. */
    void showing(Dialog dialog) {
        openDialog = dialog;
    }

    // =================================================================================================
    // Window, insets, background
    // =================================================================================================

    /**
     * Makes an activity window edge-to-edge on every supported API level: content draws behind the
     * transparent status and navigation bars and into display cutouts; callers apply the insets as
     * padding. On API 35+ the system enforces edge-to-edge and ignores bar colours, so those calls are
     * only made below 35.
     */
    @SuppressWarnings("deprecation")
    static void setupEdgeToEdge(Activity activity) {
        Window window = activity.getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        if (Build.VERSION.SDK_INT < 35) {
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(attributes);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
    }

    /** Reads system-bar, display-cutout and keyboard insets (px) from a window-insets dispatch. */
    private void readInsets(WindowInsetsCompat insets) {
        Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
        insetTop = bars.top;
        insetBottom = bars.bottom;
        insetLeft = bars.left;
        insetRight = bars.right;
        insetIme = Math.max(0, ime.bottom - bars.bottom);
    }

    /**
     * Applies the current insets (all four sides, so the landscape navigation bar and side cutouts are
     * cleared too) as home padding and to each overlay, which draw their own backgrounds behind the bars.
     */
    private void applyInsets() {
        int side = metrics.isTwoPane() ? Ui.rawDp(this, metrics.marginDp) : Ui.dp(this, 8f);
        home.setPadding(insetLeft + side, insetTop + Ui.dp(this, 4f), insetRight + side, insetBottom + Ui.dp(this, 6f));
        drawer.setInsets(insetLeft, insetTop, insetRight, insetBottom, insetIme);
        shade.setInsets(insetLeft, insetTop, insetRight, insetBottom);
        dialer.setInsets(insetLeft, insetTop, insetRight, insetBottom, insetIme);
    }

    /**
     * Sets the window/root background: black, the interface's pattern (starfield, grid, network) or the
     * system wallpaper dimmed by the "dim" preference.
     */
    void applyBackground() {
        Window window = getWindow();
        int bgMode = prefs.bgMode();
        if (bgMode == BG_WALLPAPER) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            root.setBackgroundColor(Ui.alpha(Skin.bg(), prefs.i(KEY_WALLPAPER_DIM, DEFAULT_WALLPAPER_DIM) / 100.0f));
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
            window.setBackgroundDrawable(new ColorDrawable(Skin.bg()));
            if (bgMode == BG_PATTERN) {
                root.setBackground(Skin.command() ? new Starfield(pal) : Skin.backdrop(this));
            } else {
                root.setBackgroundColor(Skin.bg());
            }
        }
    }

    /** Applies cloak mode's window brightness (only when cloak can't dim the whole phone itself). */
    void applyCloakBrightness() {
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        float brightness = (!Ui.cloak || Cloak.canDimSystem(this))
                ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                : Math.max(0.01f, Cloak.level(prefs) / 100.0f);
        if (attributes.screenBrightness != brightness) {
            attributes.screenBrightness = brightness;
            getWindow().setAttributes(attributes);
        }
    }

    /** Gives a view a stable resource id and tag for UI tests (see TEST HOOKS in the class doc). */
    private static void markForTests(View view, int id, String tag) {
        if (view.getId() == View.NO_ID) view.setId(id);
        view.setTag(tag);
    }

    // =================================================================================================
    // Building the UI
    // =================================================================================================

    /**
     * Throws away and rebuilds the entire home view tree for the current preferences, interface,
     * palette, cloak state and window size. Panels are paused and recreated; an open drawer reopens.
     */
    void buildUi() {
        if (resumed) {
            for (Panel panel : panels) panel.pause();
        }
        panels.clear();
        codes.clear();
        tiles = null;
        dock = null;
        Cloak.check(this);
        builtVersion = prefs.version();
        pal = prefs.palette();
        metrics = ScreenMetrics.of(this);
        boolean drawerWasOpen = drawer != null && drawer.isOpen();

        root = new GestureRoot(this);
        markForTests(root, R.id.home_root, "home_root");
        root.setContentDescription(getString(R.string.cd_home));
        applyBackground();
        home = buildHome();
        arrangePanes(home);
        if (Ui.cloak) {
            home.setAlpha(0.8f);
        }
        root.addView(home, matchParent());
        applyCloakBrightness();

        drawer = new DrawerView(this);
        drawer.setVisibility(View.GONE);
        markForTests(drawer, R.id.drawer_view, "drawer_view");
        root.addView(drawer, matchParent());
        shade = new ShadeView(this);
        shade.setVisibility(View.GONE);
        markForTests(shade, R.id.shade_view, "shade_view");
        root.addView(shade, matchParent());
        dialer = new DialerView(this);
        dialer.setVisibility(View.GONE);
        markForTests(dialer, R.id.dialer_view, "dialer_view");
        root.addView(dialer, matchParent());
        RedAlertOverlay.attach(this, root); // pulsing RED ALERT frame, hidden unless on alert

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            readInsets(WindowInsetsCompat.toWindowInsetsCompat(insets, view));
            applyInsets();
            return insets;
        });
        setContentView(root);
        if (repo.isLoaded()) {
            onAppsChanged();
        }
        if (resumed) {
            for (Panel panel : panels) panel.resume();
        }
        if (drawerWasOpen) {
            drawer.open(null, false, false);
        }
        if (headerFrame != null) headerFrame.reveal();
        if (mainFrame != null) mainFrame.reveal();
        updateClock();
        updateInfo();
        if (lastBattery != null) {
            onBattery(lastBattery, true);
        }
    }

    /**
     * Plays the startup sequence over the home screen.
     *
     * @param force true for the CONFIG preview; false plays it only once per device boot
     * @return true if a sequence is (now) showing
     */
    boolean bootSequence(boolean force) {
        if (root == null) return false;
        if (!force) {
            if (!prefs.b(KEY_BOOT_SEQUENCE, true) || !prefs.animations()) return false;
            int bootCount = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            if (bootCount >= 0) {
                if (bootCount == prefs.i(KEY_BOOT_SEEN, -1)) return false;
                prefs.put(KEY_BOOT_SEEN, bootCount);
            } else if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) {
                return false;
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child instanceof BootSequence) return true;
        }
        Runnable reveal = () -> {
            if (headerFrame != null) headerFrame.reveal();
            if (mainFrame != null) mainFrame.reveal();
        };
        View sequence = new BootSequence(this, pal, prefs.headerText(), reveal);
        root.addView(sequence, matchParent());
        return true;
    }

    /** Builds the home column for the current interface. */
    LinearLayout buildHome() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipToPadding(false);
        column.addView(buildHeaderFrame(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams mainParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        mainParams.topMargin = Ui.dp(this, 6f);
        column.addView(buildMainFrame(), mainParams);
        addDock(column);
        return column;
    }

    /**
     * On wide windows (landscape phones, tablets, unfolded foldables) moves the header and main frames
     * side by side, header on the left. It only re-parents {@link #headerFrame} and {@link #mainFrame}.
     */
    private void arrangePanes(LinearLayout column) {
        if (headerFrame == null || mainFrame == null) return;
        // Portrait tablets keep the stacked layout (and get more tile columns instead).
        if (metrics.widthDp < TWO_PANE_MIN_WIDTH_DP || !metrics.isLandscape()) return;
        if (headerFrame.getParent() != column || mainFrame.getParent() != column) return;
        int index = column.indexOfChild(headerFrame);
        column.removeView(headerFrame);
        column.removeView(mainFrame);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, HEADER_PANE_WEIGHT);
        headerParams.gravity = Gravity.TOP;
        row.addView(headerFrame, headerParams);
        LinearLayout.LayoutParams mainParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, MAIN_PANE_WEIGHT);
        mainParams.leftMargin = Ui.dp(this, metrics.gutterDp);
        row.addView(mainFrame, mainParams);
        column.addView(row, index, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
    }

    /** True while the header and main frames are side by side (see {@link #arrangePanes}). */
    private boolean isSideBySide() {
        return headerFrame != null && headerFrame.getParent() != null && headerFrame.getParent() != home;
    }

    /** Header frame: title, four code blocks in the sidebar, ship line, clock, date and info line. */
    private ConsoleFrame buildHeaderFrame() {
        Runnable cascade = this::cascade;
        headerFrame = new ConsoleFrame(this);
        markForTests(headerFrame, R.id.home_header, "home_header");
        headerFrame.topH = Ui.dp(this, 16f);
        headerFrame.botH = Ui.dp(this, 11f);
        headerFrame.ext = Ui.dp(this, 14f);
        headerFrame.colorTop = slotColor("hElbowT", pal.c(1));
        headerFrame.colorBot = slotColor("hElbowB", pal.c(1));
        headerFrame.topBlocks = new int[]{slotColor("hBlockT0", pal.c(0))};
        headerFrame.botBlocks = new int[]{slotColor("hBlockB0", pal.c(0)), slotColor("hBlockB1", pal.c(3))};
        headerFrame.setPartListener(partListener("h"));

        TextView title = Ui.bold(this, slotLabel("title", Skin.title(), false), 34f, slotColor("title", pal.c(0)));
        title.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 2f), 0);
        bindSlot(title, "title", cascade);
        headerFrame.setTitle(title);

        LinearLayout codeColumn = new LinearLayout(this);
        codeColumn.setOrientation(LinearLayout.VERTICAL);
        int[] codeColors = {pal.c(0), pal.c(1), pal.c(0), pal.c(3)};
        for (int i = 0; i < codeColors.length; i++) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
            if (i > 0) params.topMargin = Ui.dp(this, 6f);
            codeColumn.addView(codeBlock("code" + i, code(), codeColors[i], cascade), params);
        }
        headerFrame.setSidebar(codeColumn);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        shipView = Ui.oneLine(Ui.text(this, prefs.headerText().toUpperCase(Locale.getDefault()), 14f, slotColor("ship", pal.c(0))));
        shipView.setGravity(Gravity.END);
        bindSlot(shipView, "ship", null);
        content.addView(shipView, matchWidth());
        content.addView(buildClockRow(), matchWidth());

        dateView = Ui.bold(this, "", 20f, slotColor("date", pal.c(1)));
        dateView.setGravity(Gravity.CENTER);
        bindSlot(dateView, "date", () -> startSafe(new Intent(Intent.ACTION_VIEW,
                CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                        .appendPath(String.valueOf(System.currentTimeMillis())).build()), dateView));
        content.addView(dateView, matchWidth());

        infoView = Ui.oneLine(Ui.text(this, "", 14f, pal.textLo));
        infoView.setGravity(Gravity.CENTER);
        infoView.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 2f));
        content.addView(infoView, matchWidth());
        headerFrame.setContent(content);
        return headerFrame;
    }

    /** The big clock with its AM/PM suffix; tapping opens the alarms app. */
    private View buildClockRow() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        clockView = Ui.bold(this, "--:--", CLOCK_SP, slotColor("clock", pal.textHi));
        clockView.setLetterSpacing(0.01f);
        row.addView(clockView);
        ampmView = Ui.bold(this, "", 20f, pal.c(1));
        ampmView.setPadding(Ui.dp(this, 6f), 0, 0, Ui.dp(this, 8f));
        LinearLayout.LayoutParams ampmParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ampmParams.gravity = Gravity.BOTTOM;
        row.addView(ampmView, ampmParams);
        bindSlot(row, "clock", () -> startSafe(new Intent(AlarmClock.ACTION_SHOW_ALARMS), clockView));
        Drawable ornament = Skin.headerOrnament(this, pal);
        if (ornament != null) {
            row.setBackground(ornament);
        }
        if (Skin.imperial()) {
            row.setPadding(Ui.dp(this, 34f), Ui.dp(this, 4f), Ui.dp(this, 34f), Ui.dp(this, 20f));
        }
        clockRowView = row;
        row.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) fitClock();
        });
        return row;
    }

    /** Main frame: APPS / SEARCH / CONFIG sidebar and either the scrolling tiles + panels or the reveal hub. */
    private ConsoleFrame buildMainFrame() {
        Runnable cascade = this::cascade;
        mainFrame = new ConsoleFrame(this);
        markForTests(mainFrame, R.id.home_main, "home_main");
        mainFrame.topH = Ui.dp(this, 12f);
        mainFrame.botH = Ui.dp(this, 16f);
        mainFrame.colorTop = slotColor("mElbowT", pal.c(0));
        mainFrame.colorBot = slotColor("mElbowB", pal.c(3));
        mainFrame.topBlocks = new int[]{slotColor("mBlockT0", pal.c(4))};
        mainFrame.botBlocks = new int[]{slotColor("mBlockB0", pal.c(2))};
        mainFrame.setPartListener(partListener("m"));

        TextView ops = Ui.bold(this, slotLabel("ops", Skin.ops(), false), 19f, slotColor("ops", pal.c(4)));
        ops.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 2f), 0);
        bindSlot(ops, "ops", null);
        mainFrame.setTitle(ops);
        TextView footer = Ui.text(this, slotLabel("foot", Skin.foot(), false), 13f, slotColor("foot", pal.c(2)));
        footer.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 2f), 0);
        bindSlot(footer, "foot", null);
        mainFrame.setFooter(footer);

        LinearLayout sidebar = new LinearLayout(this) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                    fitNavButtons(this, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom());
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        sidebar.setOrientation(LinearLayout.VERTICAL);
        addNav(sidebar, "nav0", "APPS", pal.c(0), () -> openDrawer(null, false));
        addNav(sidebar, "nav1", "SEARCH", pal.c(2), () -> openDrawer(null, true));
        addNav(sidebar, "nav2", "CONFIG", pal.c(3), this::openConfig);
        LinearLayout.LayoutParams blockParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        blockParams.topMargin = Ui.dp(this, 6f);
        sidebar.addView(codeBlock("nav3", "47", pal.c(1), cascade), blockParams);
        mainFrame.setSidebar(sidebar);

        if (prefs.revealMode()) {
            homeScroll = null;
            mainFrame.setContent(buildHubArea());
        } else {
            homeScroll = new ScrollView(this);
            homeScroll.setVerticalScrollBarEnabled(false);
            homeScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            homeScroll.setFillViewport(true);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            tilesBox = Skin.collective() ? new CollectiveDraw.TraceColumn(this, pal.c(0)) : new LinearLayout(this);
            tilesBox.setOrientation(LinearLayout.VERTICAL);
            markForTests(tilesBox, R.id.home_tiles, "home_tiles");
            if (Skin.collective()) {
                tilesBox.setPadding(0, 0, 0, Ui.dp(this, 24f));
            }
            content.addView(tilesBox, matchWidth());
            buildPanels(content);
            homeScroll.addView(content);
            mainFrame.setContent(homeScroll);
        }
        return mainFrame;
    }

    /** Adds the dock row (left cap = all apps, dock icons, right cap) to a home column, if enabled. */
    void addDock(LinearLayout column) {
        if (prefs.dockCount() <= 0) {
            dockIcons = null;
            return;
        }
        int rowHeight = Ui.dp(this, 60f);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        markForTests(row, R.id.home_dock, "home_dock");

        FrameLayout leftCap = new FrameLayout(this);
        leftCap.setBackground(Ui.touch(Ui.shape(this, slotColor("capL", pal.c(0)), Ui.LEFT, 20f)));
        ImageView capIcon = new ImageView(this);
        Tile capSlot = Tile.slot(prefs, "capL");
        AppInfo capApp = (capSlot == null || capSlot.comp == null) ? null : repo.find(capSlot.comp, capSlot.user);
        if (capApp != null && capApp.rawIcon != null && (capApp.glyph == null || prefs.iconStyle() == ICON_STYLE_NATIVE)) {
            capIcon.setImageDrawable(IconFactory.copy(capApp.rawIcon));
        } else {
            String glyph;
            if (capApp != null && capApp.glyph != null) {
                glyph = capApp.glyph;
            } else {
                glyph = (capSlot == null || capSlot.action == null) ? "apps" : HomeLogic.actionGlyph(capSlot.action);
            }
            capIcon.setImageBitmap(IconFactory.glyph(this, glyph));
            capIcon.setColorFilter(Ui.ink(), PorterDuff.Mode.SRC_IN);
        }
        int capIconSize = Ui.dp(this, 30f);
        leftCap.addView(capIcon, new FrameLayout.LayoutParams(capIconSize, capIconSize, Gravity.CENTER));
        leftCap.setContentDescription(getString(R.string.cd_all_apps));
        bindSlot(leftCap, "capL", () -> openDrawer(null, false));
        row.addView(leftCap, new LinearLayout.LayoutParams(Ui.dp(this, 64f), rowHeight));

        dockIcons = new LinearLayout(this);
        dockIcons.setGravity(Gravity.CENTER_VERTICAL);
        dockIcons.setBackground(Ui.outline(this, Ui.alpha(pal.panel, 0.85f), Ui.alpha(pal.c(1), 0.5f), Skin.trayRadius(4f), 1.5f));
        LinearLayout.LayoutParams iconsParams = new LinearLayout.LayoutParams(0, rowHeight, 1.0f);
        iconsParams.leftMargin = Ui.dp(this, 6f);
        iconsParams.rightMargin = Ui.dp(this, 6f);
        row.addView(dockIcons, iconsParams);

        View rightCap = new View(this);
        rightCap.setBackground(Ui.touch(Ui.shape(this, slotColor("capR", pal.c(3)), Ui.RIGHT, 20f)));
        bindSlot(rightCap, "capR", null);
        row.addView(rightCap, new LinearLayout.LayoutParams(Ui.dp(this, 22f), rowHeight));

        LinearLayout.LayoutParams rowParams = matchWidth();
        rowParams.topMargin = Ui.dp(this, 6f);
        column.addView(row, rowParams);
    }

    /** Adds one sidebar button (APPS / SEARCH / CONFIG), which is also an assignable slot. */
    private void addNav(LinearLayout sidebar, String slot, String defaultLabel, int color, Runnable action) {
        TextView button = Ui.button(this, slotLabel(slot, defaultLabel, true), slotColor(slot, color), Ui.SQUARE, null);
        button.setBackground(Ui.touch(Ui.shape(this, slotColor(slot, color), Ui.SQUARE, 0f)));
        Ui.setTextSp(button, Skin.imperial() ? 13f : 15f);
        if (Skin.imperial()) {
            button.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 16f), Ui.dp(this, 12f));
        } else {
            button.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 8f), Ui.dp(this, 4f));
        }
        bindSlot(button, slot, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, NAV_BUTTON_DP));
        if (sidebar.getChildCount() > 0) params.topMargin = Ui.dp(this, 6f);
        sidebar.addView(button, params);
    }

    private static final float NAV_BUTTON_DP = 62f;
    private static final float NAV_BUTTON_MIN_DP = 32f;
    private static final int NAV_BUTTONS = 3;

    /**
     * Shrinks the APPS / SEARCH / CONFIG buttons evenly when the sidebar is shorter than their natural
     * height (large "display size" settings, short landscape frames), so CONFIG never falls off-screen;
     * restores the natural height whenever there is room.
     */
    private void fitNavButtons(LinearLayout sidebar, int available) {
        if (available <= 0 || sidebar.getChildCount() < NAV_BUTTONS) return;
        int natural = Ui.dp(this, NAV_BUTTON_DP);
        int margins = 0;
        for (int i = 0; i < sidebar.getChildCount(); i++) {
            margins += ((LinearLayout.LayoutParams) sidebar.getChildAt(i).getLayoutParams()).topMargin;
        }
        int height = Math.min(natural, Math.max(Ui.dp(this, NAV_BUTTON_MIN_DP), (available - margins) / NAV_BUTTONS));
        for (int i = 0; i < NAV_BUTTONS; i++) {
            ViewGroup.LayoutParams lp = sidebar.getChildAt(i).getLayoutParams();
            if (lp.height != height) lp.height = height;
        }
    }

    /**
     * One console code block (header sidebar or the "47" block under the nav buttons). Shows a random
     * code that the data cascade shuffles, or the slot's assigned label with a small code in the corner.
     */
    private View codeBlock(String slot, String defaultText, int color, Runnable action) {
        boolean hasLabel = slotHasLabel(slot);
        boolean angular = !Skin.command();
        boolean isBlock47 = "nav3".equals(slot);
        FrameLayout block = new FrameLayout(this);
        block.setBackground(Ui.touch(Ui.shape(this, slotColor(slot, color), Ui.SQUARE, 0f)));

        TextView main = Ui.bold(this, hasLabel ? slotLabel(slot, "", true) : defaultText, angular ? 11f : 13f, Ui.ink());
        if (angular) {
            main.setGravity((isBlock47 ? Gravity.BOTTOM : Gravity.CENTER_VERTICAL) | Gravity.END);
            main.setPadding(Ui.dp(this, 8f), 0, Ui.dp(this, 12f), isBlock47 ? Ui.dp(this, 12f) : 0);
        } else {
            main.setGravity(Gravity.BOTTOM | Gravity.END);
            main.setPadding(Ui.dp(this, 2f), 0, Ui.dp(this, 8f), Ui.dp(this, 4f));
        }
        main.setSingleLine(true);
        main.setEllipsize(TextUtils.TruncateAt.END);
        Ui.fitText(main, true); // short/narrow blocks (480x800 mdpi, landscape block 47) shrink the code to fit
        block.addView(main, matchParent());

        if (hasLabel && (!angular || isBlock47)) {
            String small = "47".equals(defaultText) ? defaultText : code();
            TextView corner = Ui.text(this, small, 10f, Ui.alpha(Ui.ink(), 0.6f));
            corner.setSingleLine(true);
            corner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            if (angular) {
                corner.setPadding(Ui.dp(this, 16f), Ui.dp(this, 8f), Ui.dp(this, 4f), 0);
            } else {
                corner.setPadding(Ui.dp(this, 6f), Ui.dp(this, 2f), Ui.dp(this, 4f), 0);
            }
            block.addView(corner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
            codes.add(corner);
        } else if (!hasLabel) {
            codes.add(main);
        }
        block.setContentDescription(hasLabel ? slotLabel(slot, "", true) : Tile.slotName(slot));
        bindSlot(block, slot, action);
        return block;
    }

    // =================================================================================================
    // Slots: every console element can be assigned an app, function, label, colour or sound
    // =================================================================================================

    /** The slot's custom accent colour, or {@code fallback} when it has none. */
    int slotColor(String slot, int fallback) {
        Tile custom = Tile.slot(prefs, slot);
        return (custom == null || custom.color < 0) ? fallback : pal.c(custom.color);
    }

    private boolean slotHasLabel(String slot) {
        Tile custom = Tile.slot(prefs, slot);
        return custom != null && ((custom.label != null && !custom.label.isEmpty()) || !custom.isEmpty());
    }

    /**
     * The text a slot shows: its custom label; with {@code useAssignment}, else the name of the app or
     * function assigned to it; else {@code fallback}.
     */
    String slotLabel(String slot, String fallback, boolean useAssignment) {
        Tile custom = Tile.slot(prefs, slot);
        if (custom == null) return fallback;
        if (custom.label != null && !custom.label.isEmpty()) {
            return custom.label.toUpperCase(Locale.getDefault());
        }
        if (useAssignment && !custom.isEmpty()) {
            if (custom.action != null) return Tile.actionLabel(custom.action);
            AppInfo app = repo.find(custom.comp, custom.user);
            return app != null ? HomeLogic.shortLabel(app.label).toUpperCase(Locale.getDefault()) : fallback;
        }
        return fallback;
    }

    /**
     * Wires a view as an assignable slot: tap runs the assignment (or {@code defaultAction}), long-press
     * opens the slot editor. Known navigation slots also get their test IDs here.
     */
    void bindSlot(View view, final String slot, final Runnable defaultAction) {
        switch (slot) {
            case "nav0": markForTests(view, R.id.drawer_button, "drawer_button"); break;
            case "nav1": markForTests(view, R.id.search_button, "search_button"); break;
            case "nav2": markForTests(view, R.id.settings_button, "settings_button"); break;
            case "capL": markForTests(view, R.id.dock_apps_button, "dock_apps_button"); break;
            case "clock": markForTests(view, R.id.home_clock, "home_clock"); break;
            case "date": markForTests(view, R.id.home_date, "home_date"); break;
            default: break;
        }
        view.setOnClickListener(v -> runSlot(slot, v, defaultAction));
        view.setOnLongClickListener(v -> {
            Ui.longPress(v);
            editSlot(slot);
            return true;
        });
    }

    /** Tap/long-press listener for an ConsoleFrame's elbows and bar segments ({@code prefix} "h" or "m"). */
    ConsoleFrame.PartListener partListener(final String prefix) {
        return (part, view, longPress) -> {
            String slot = HomeLogic.framePartSlot(prefix, part);
            if (longPress) {
                Ui.longPress(view);
                editSlot(slot);
            } else {
                runSlot(slot, view, null);
            }
        };
    }

    /** Runs a slot: its assigned app/function, else its sound plus the default action. */
    private void runSlot(String slot, View view, Runnable defaultAction) {
        Tile custom = Tile.slot(prefs, slot);
        if (custom != null && !custom.isEmpty()) {
            launchTile(custom, view);
            return;
        }
        if (custom != null && custom.sound != null) {
            haptic(view);
            Sfx.playClip(this, custom.sound, false);
        } else {
            Ui.tap(view);
        }
        if (defaultAction != null) defaultAction.run();
    }

    /** The long-press editor for a slot: assign app/function, rename, recolour, sound, reset. */
    private void editSlot(final String slot) {
        Tile existing = Tile.slot(prefs, slot);
        final Tile tile = existing != null ? existing : new Tile();
        final String slotName = Tile.slotName(slot);
        Menu menu = new Menu();
        menu.add("ASSIGN APP", () -> openDrawer(appPick("ASSIGN " + slotName, app -> {
            assignApp(tile, app);
            saveSlot(slot, tile);
        }), false));
        menu.add("ASSIGN TREKUI FUNCTION", () -> pickFunction(slotName, (action, arg, label) -> {
            tile.comp = null;
            tile.action = action;
            tile.arg = arg;
            if (Tile.A_GOVEE.equals(action) && Tile.slotHasText(slot) && (tile.label == null || tile.label.isEmpty())) {
                tile.label = label;
            }
            saveSlot(slot, tile);
        }));
        if (Tile.slotHasText(slot)) {
            menu.add("RENAME", () -> renameSlot(slot, slotName, tile));
        }
        menu.add("CHANGE COLOR", () -> showing(ConsoleDialog.options(this, "COLOR • " + slotName,
                accentChoices("DEFAULT"), choice -> {
                    tile.color = choice == 0 ? -1 : choice - 1;
                    saveSlot(slot, tile);
                })));
        menu.add("ASSIGN SOUND", () -> pickSound(slotName, tile.sound, clip -> {
            tile.sound = clip;
            saveSlot(slot, tile);
        }));
        if (existing != null) {
            menu.add("RESET TO DEFAULT", () -> {
                Tile.putSlot(prefs, slot, null);
                Sfx.play(this, Sfx.CLOSE);
                buildUi();
            });
        }
        menu.add("OPEN TREKUI CONFIG", this::openConfig);
        String title = slotName + (tile.isEmpty() ? "" : " • " + slotLabel(slot, "", true));
        showing(ConsoleDialog.options(this, title, menu.labels(), menu::run));
    }

    /** RENAME for a slot; the ship line edits the header text preference instead. */
    private void renameSlot(String slot, String slotName, Tile tile) {
        boolean isShipLine = "ship".equals(slot);
        showing(ConsoleDialog.prompt(this, "RENAME " + slotName, isShipLine ? prefs.headerText() : tile.label,
                "LABEL (EMPTY = DEFAULT)", text -> {
                    if (!isShipLine) {
                        tile.label = text;
                        saveSlot(slot, tile);
                        return;
                    }
                    prefs.put(prefs.headerKey(), text.isEmpty() ? prefs.headerDefault() : text);
                    prefs.bump();
                    buildUi();
                }));
    }

    /** Saves a slot customisation (or removes it when nothing is customised) and rebuilds. */
    private void saveSlot(String slot, Tile tile) {
        boolean untouched = tile.isEmpty() && tile.color < 0 && (tile.label == null || tile.label.isEmpty()) && tile.sound == null;
        Tile.putSlot(prefs, slot, untouched ? null : tile);
        Sfx.play(this, Sfx.CONFIRM);
        buildUi();
    }

    /** "DEFAULT"/"AUTOMATIC" followed by ACCENT 1..6, for the colour pickers. */
    private static String[] accentChoices(String first) {
        return new String[]{first, "ACCENT 1", "ACCENT 2", "ACCENT 3", "ACCENT 4", "ACCENT 5", "ACCENT 6"};
    }

    /** Points a tile at an app (main profile apps are stored without a user serial). */
    private static void assignApp(Tile tile, AppInfo app) {
        tile.comp = app.cn.flattenToShortString();
        tile.user = app.mainProfile ? -1L : app.serial;
        tile.action = null;
    }

    /** Lambda-friendly adapter for {@link DrawerView.Pick}. */
    private interface AppPicked {
        void picked(AppInfo app);
    }

    private static DrawerView.Pick appPick(final String title, final AppPicked onPicked) {
        return new DrawerView.Pick() {
            @Override
            public String title() {
                return title;
            }

            @Override
            public void picked(AppInfo app) {
                onPicked.picked(app);
            }
        };
    }

    /** A dialog menu built as label + action pairs, so the pick handler needs no string comparisons. */
    private static final class Menu {
        private final List<String> labels = new ArrayList<>();
        private final List<Runnable> actions = new ArrayList<>();

        void add(String label, Runnable action) {
            labels.add(label);
            actions.add(action);
        }

        String[] labels() {
            return labels.toArray(new String[0]);
        }

        void run(int index) {
            if (index >= 0 && index < actions.size()) actions.get(index).run();
        }
    }

    /**
     * Lists the assignable console functions plus one entry per Govee light and Nest thermostat (or a
     * "set up" entry when none are configured, which leads to CONFIG).
     */
    private void pickFunction(String title, final FunctionPicked onPicked) {
        final List<Govee.Device> lights = Govee.devices(prefs);
        final List<Nest.Device> thermostats = Nest.devices(prefs);
        List<String> labels = new ArrayList<>(Arrays.asList(Tile.ACTION_LABELS));
        final int firstLight = Tile.ACTIONS.length;
        if (lights.isEmpty()) labels.add("GOVEE LIGHT (SET UP…)");
        for (Govee.Device light : lights) labels.add("LIGHTS: " + light.title());
        final int firstThermostat = labels.size();
        if (thermostats.isEmpty()) labels.add("NEST THERMOSTAT (SET UP…)");
        for (Nest.Device thermostat : thermostats) labels.add("CLIMATE: " + thermostat.label);
        final int homeAssistantEntry = labels.size(); // LCA-T23
        labels.add(getString(HomeAssistant.configured(this) ? R.string.ha_pick_entry : R.string.ha_pick_setup));
        final List<AppGroups.Group> groups = AppGroups.load(prefs); // LCA-T20: OPEN GROUP entries + NEW GROUP…
        final int firstGroup = labels.size();
        for (AppGroups.Group g : groups) labels.add(getString(R.string.fn_group_entry, g.name));
        labels.add(getString(R.string.fn_new_group));

        showing(ConsoleDialog.options(this, title, labels.toArray(new String[0]), which -> {
            if (which >= firstGroup) {
                if (which - firstGroup < groups.size()) {
                    AppGroups.Group g = groups.get(which - firstGroup);
                    onPicked.picked(Tile.A_GROUP, g.id, g.name);
                } else {
                    GroupEditor.create(this, null, g -> onPicked.picked(Tile.A_GROUP, g.id, g.name));
                }
            } else if (which == homeAssistantEntry) {
                HomeAssistantDialog.pick(this, (arg, label) -> onPicked.picked(Tile.A_HA, arg, label));
            } else if (which < firstLight) {
                onPicked.picked(Tile.ACTIONS[which], null, Tile.ACTION_LABELS[which]);
            } else if (which < firstThermostat) {
                if (lights.isEmpty()) {
                    toast(getString(R.string.toast_add_light_first));
                    openConfig();
                } else {
                    Govee.Device light = lights.get(which - firstLight);
                    onPicked.picked(Tile.A_GOVEE, light.id, light.title());
                }
            } else if (thermostats.isEmpty()) {
                toast(getString(R.string.toast_link_nest_first));
                openConfig();
            } else {
                onPicked.picked(Tile.A_NEST, thermostats.get(which - firstThermostat).name, "CLIMATE");
            }
        }));
    }

    /**
     * Sound picker for a slot, tile or station: default, silent, the tap rotation or any library clip.
     * Without a sound library the third entry leads to CONFIG › AUDIO instead.
     */
    private void pickSound(String title, String current, final SoundPicked onPicked) {
        boolean hasLibrary = !SoundLibrary.all(this).isEmpty();
        String[] labels;
        String[] values;
        if (hasLibrary) {
            labels = new String[]{
                    (current == null ? "● " : "") + "DEFAULT (USUAL SOUNDS)",
                    ("none".equals(current) ? "● " : "") + "SILENT",
                    ("#taps".equals(current) ? "● " : "") + "TAP ROTATION (7 SOUNDS)"};
            values = new String[]{"#default", "none", "#taps"};
        } else {
            labels = new String[]{"DEFAULT (USUAL SOUNDS)", "SILENT", "IMPORT SOUNDS IN CONFIG › AUDIO…"};
            values = new String[]{"#default", "none", "#config"};
        }
        SoundPicker.pick(this, "SOUND • " + title, labels, values, current, null, picked -> {
            if ("#config".equals(picked)) {
                openConfig();
                return;
            }
            String clip = "#default".equals(picked) ? null : picked;
            if (clip != null) Sfx.playClip(this, clip, true);
            onPicked.picked(clip);
            preloadElementSounds();
        });
    }

    /** Preloads every custom sound assigned to a slot, tile or dock icon so taps play instantly. */
    private void preloadElementSounds() {
        List<String> clips = new ArrayList<>();
        for (String[] slot : Tile.SLOTS) {
            Tile custom = Tile.slot(prefs, slot[0]);
            if (custom != null && custom.sound != null) clips.add(custom.sound);
        }
        if (tiles != null) {
            for (Tile tile : tiles) if (tile.sound != null) clips.add(tile.sound);
        }
        if (dock != null) {
            for (Tile tile : dock) if (tile.sound != null) clips.add(tile.sound);
        }
        if (!clips.isEmpty()) Sfx.preload(this, clips);
    }

    // =================================================================================================
    // Panels and the reveal hub
    // =================================================================================================

    /** Creates the information panel (or hosted widget panel) with the given id, or null for an unknown id. */
    Panel createPanel(String id, int color) {
        if (WidgetSlots.isSlotId(id)) return new WidgetPanel(this, color, id);
        switch (id) {
            case "weather": return new WeatherPanel(this, color);
            case "calendar": return new CalendarPanel(this, color);
            case "media": return new MediaPanel(this, color);
            case "power": return new PowerPanel(this, color);
            case "systems": return new SystemsPanel(this, color);
            default: return null;
        }
    }

    /** Creates and registers a visible panel, wiring long-press on its header and bracket to its menu. */
    private View createPanelView(Panel panel) {
        panels.add(panel);
        View view = panel.create();
        View.OnLongClickListener menu = v -> {
            panelMenu(panel);
            return true;
        };
        panel.card.header.setOnLongClickListener(menu);
        panel.card.bracket.setOnLongClickListener(menu);
        return view;
    }

    /** Adds every visible panel (in the user's order) below the tiles, then the customise hint. */
    void buildPanels(LinearLayout column) {
        Set<String> hidden = prefs.hiddenWidgets();
        int colorIndex = 0;
        for (String id : prefs.widgetOrder()) {
            if (hidden.contains(id)) continue;
            Panel panel = createPanel(id, pal.c(colorIndex++));
            if (panel == null) continue;
            LinearLayout.LayoutParams params = matchWidth();
            params.topMargin = Ui.dp(this, 16f);
            column.addView(createPanelView(panel), params);
        }
        TextView hint = Ui.text(this, getString(R.string.hint_customize_console),
                11f, Ui.alpha(pal.textLo, 0.6f));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 16f), 0, Ui.dp(this, 8f));
        column.addView(hint, matchWidth());
    }

    /**
     * Content of the main frame in the reveal layouts: the hub (tiles box) plus a hidden overlay in which
     * one panel or station at a time is shown as a card.
     */
    private View buildHubArea() {
        FrameLayout area = new FrameLayout(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        tilesBox = new LinearLayout(this);
        tilesBox.setOrientation(LinearLayout.VERTICAL);
        markForTests(tilesBox, R.id.home_tiles, "home_tiles");
        column.addView(tilesBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        hubPanelViews.clear();
        hubPanelObjs.clear();
        hubPanelNames.clear();
        hubPanelColors.clear();
        hubPanelOpen = -1;
        Set<String> hidden = prefs.hiddenWidgets();
        int colorIndex = 0;
        for (String id : prefs.widgetOrder()) {
            if (hidden.contains(id)) continue;
            int color = pal.c(colorIndex++);
            Panel panel = createPanel(id, color);
            if (panel == null) continue;
            hubPanelViews.add(createPanelView(panel));
            hubPanelObjs.add(panel);
            hubPanelNames.add(HomeLogic.shortPanelName(id));
            hubPanelColors.add(color);
        }
        hubPanelCount = hubPanelViews.size();
        hubStations.clear();
        hubStations.addAll(Stations.build(this, pal, (stationId, stationName) ->
                pickSound(stationName, prefs.s(KEY_STATION_SOUND + stationId, null), clip -> {
                    if (clip != null) prefs.put(KEY_STATION_SOUND + stationId, clip);
                    else prefs.remove(KEY_STATION_SOUND + stationId);
                })));
        for (Stations.Station station : hubStations) hubPanelViews.add(station.view);
        area.addView(column, matchParent());

        hubOverlay = new FrameLayout(this);
        markForTests(hubOverlay, R.id.hub_panel_overlay, "hub_panel_overlay");
        hubOverlay.setBackgroundColor(Ui.alpha(Skin.bg(), 0.82f));
        hubOverlay.setClickable(true);
        hubOverlay.setOnClickListener(v -> showHubPanel(-1));
        hubOverlay.setVisibility(View.GONE);
        ScrollView cardScroll = new ScrollView(this);
        cardScroll.setVerticalScrollBarEnabled(false);
        cardScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hubPanelHolder = new FrameLayout(this);
        hubPanelHolder.setBackground(Ui.outline(this, Ui.alpha(Skin.surface(), 0.96f), Ui.alpha(pal.c(0), 0.45f), Skin.trayRadius(16f), 1.2f));
        int pad = Ui.dp(this, 8f);
        hubPanelHolder.setPadding(pad, pad, pad, pad);
        cardScroll.addView(hubPanelHolder, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // On tablets a full-width card is unreadably wide: cap it and centre it.
        int cardWidth = metrics.widthDp > HUB_PANEL_MAX_WIDTH_DP + 80f ? Ui.dp(this, HUB_PANEL_MAX_WIDTH_DP) : ViewGroup.LayoutParams.MATCH_PARENT;
        hubOverlay.addView(cardScroll, new FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        area.addView(hubOverlay, matchParent());
        return area;
    }

    /** Shows hub card {@code index} (panels first, then stations) over the hub; -1 hides the card. */
    private void showHubPanel(int index) {
        if (hubOverlay == null) return;
        RevealHost host = currentHost();
        boolean animate = Ui.animate(this);
        if (index < 0 || index >= hubPanelViews.size()) {
            if (hubPanelOpen < 0) return;
            hubPanelOpen = -1;
            if (host != null) host.setDim(1.0f);
            Sfx.play(this, Sfx.CLOSE);
            if (animate) {
                hubOverlay.animate().alpha(0f).setDuration(180L).withEndAction(() -> {
                    if (hubPanelOpen < 0) hubOverlay.setVisibility(View.GONE);
                }).start();
            } else {
                hubOverlay.setVisibility(View.GONE);
            }
            return;
        }
        hubPanelOpen = index;
        if (host != null) host.setDim(0.15f);
        View card = hubPanelViews.get(index);
        if (card.getParent() instanceof ViewGroup) {
            ((ViewGroup) card.getParent()).removeView(card);
        }
        hubPanelHolder.removeAllViews();
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(Ui.dp(this, 4f), Ui.dp(this, 12f), Ui.dp(this, 4f), Ui.dp(this, 12f));
        hubPanelHolder.addView(card, params);
        card.setClickable(true);
        Sfx.play(this, Sfx.OPEN);
        hubOverlay.setVisibility(View.VISIBLE);
        if (animate) {
            hubOverlay.setAlpha(0f);
            hubOverlay.animate().alpha(1f).setDuration(200L).start();
            card.setPivotX(hubOverlay.getWidth() / 2.0f);
            card.setPivotY(hubOverlay.getHeight());
            card.setScaleX(0.6f);
            card.setScaleY(0.6f);
            card.animate().scaleX(1f).scaleY(1f).setDuration(260L).setInterpolator(new DecelerateInterpolator(1.5f)).start();
        } else {
            hubOverlay.setAlpha(1f);
        }
    }

    /** Closes the hub card if one is open; returns whether it did. */
    boolean closeHubPanel() {
        if (hubPanelOpen < 0) return false;
        showHubPanel(-1);
        return true;
    }

    /** Long-press menu of a panel: its own entries, then MOVE UP / MOVE DOWN / HIDE PANEL. */
    private void panelMenu(final Panel panel) {
        Ui.longPress(panel.card);
        final String[] extra = panel.extraMenu();
        Menu menu = new Menu();
        for (int i = 0; i < extra.length; i++) {
            final int entry = i;
            menu.add(extra[i], () -> panel.onExtraMenu(entry));
        }
        menu.add("MOVE UP", () -> movePanel(panel, -1));
        menu.add("MOVE DOWN", () -> movePanel(panel, +1));
        menu.add(getString(R.string.widget_add_panel), () -> WidgetPickActivity.start(this));
        menu.add("HIDE PANEL", () -> {
            prefs.setWidgetHidden(panel.id(), true);
            toast(getString(R.string.toast_panel_hidden));
            Sfx.play(this, Sfx.CONFIRM);
            buildUi();
        });
        showing(ConsoleDialog.options(this, panel.title(), menu.labels(), menu::run));
    }

    /** Moves a panel one place up (-1) or down (+1) in the panel order and rebuilds. */
    private void movePanel(Panel panel, int direction) {
        List<String> order = prefs.widgetOrder();
        int from = order.indexOf(panel.id());
        int to = from + direction;
        if (from >= 0 && to >= 0 && to < order.size()) {
            order.remove(from);
            order.add(to, panel.id());
            prefs.setWidgetOrder(order);
        }
        Sfx.play(this, Sfx.CONFIRM);
        buildUi();
    }

    // =================================================================================================
    // Tiles and dock
    // =================================================================================================

    /** Loads tiles and dock from preferences, creating the defaults once the app list is loaded. */
    private void loadTiles() {
        tiles = Tile.load(prefs, KEY_TILES);
        if (tiles == null && repo.isLoaded()) {
            tiles = Tile.defaultTiles(this, repo);
            Tile.save(prefs, KEY_TILES, tiles);
        }
        dock = Tile.load(prefs, KEY_DOCK);
        if (dock == null && repo.isLoaded()) {
            dock = Tile.defaultDock(this, repo);
            Tile.save(prefs, KEY_DOCK, dock);
        }
    }

    private static void ensureSize(List<Tile> list, int size) {
        while (list.size() < size) list.add(new Tile());
    }

    /** Rebuilds the tile area: the reveal hub in reveal layouts, otherwise the tile grid. */
    void refreshTiles() {
        if (tilesBox == null || tiles == null) return;
        tilesBox.removeAllViews();
        int tileCount = prefs.tileCount();
        ensureSize(tiles, tileCount);
        if (prefs.revealMode()) {
            buildRevealHub(tileCount);
        } else {
            buildTileGrid(tileCount);
        }
    }

    /** Builds the reveal hub (cascade / deploy / hub-and-spoke) for the current hub page. */
    private void buildRevealHub(int tileCount) {
        View.OnLongClickListener openDrawer = v -> {
            Ui.longPress(v);
            openDrawer(null, false);
            return true;
        };
        RevealHost host;
        if (Skin.collective()) {
            host = new CollectiveHub(this, pal, openDrawer);
        } else if (Skin.imperial()) {
            host = new ImperialDeploy(this, pal, openDrawer);
        } else {
            host = new CommandCascade(this, pal, openDrawer);
        }
        boolean hasInfo = hubPanelCount + hubStations.size() > 0;
        if (hubPage == HomeLogic.HUB_PAGE_STATIONS && !hubStations.isEmpty()) {
            for (int i = 0; i < hubStations.size(); i++) addStationNode(host, i);
            addReturnNode(host, HomeLogic.HUB_PAGE_INFO);
            host.setPinned(true);
            host.setCount(hubStations.size());
            host.setMasterTitle(Stations.pageTitle());
            host.setMasterAction(() -> switchHubPage(HomeLogic.HUB_PAGE_INFO));
        } else if (hubPage >= HomeLogic.HUB_PAGE_INFO && hasInfo) {
            hubPage = HomeLogic.HUB_PAGE_INFO;
            for (int i = 0; i < hubPanelCount; i++) addInfoNode(host, i);
            if (!hubStations.isEmpty()) addStationsEntry(host, hubPanelCount);
            addReturnNode(host, HomeLogic.HUB_PAGE_TILES);
            host.setPinned(true);
            host.setCount(hubPanelCount);
            host.setMasterTitle(Skin.imperial() ? "INTEL" : Skin.collective() ? "INFORMATION NODES" : "INFORMATION");
            host.setMasterAction(() -> switchHubPage(HomeLogic.HUB_PAGE_TILES));
        } else {
            hubPage = HomeLogic.HUB_PAGE_TILES;
            for (int i = 0; i < tileCount; i++) addHubNode(host, i);
            if (hasInfo) addInfoEntry(host, tileCount);
            host.setCount(tileCount);
        }
        tilesBox.addView((View) host, matchParent());
    }

    /** Tile columns on a 360dp phone for the current interface. */
    private static int baseTileColumns() {
        if (Skin.command()) return 3;
        return Skin.imperial() ? 2 : 1;
    }

    /** Tile row height in dp for the current interface. */
    private static int tileRowHeightDp() {
        if (Skin.command()) return 78;
        return Skin.imperial() ? 62 : 50;
    }

    /** Builds the scrolling tile grid; wider windows get more columns (see {@link HomeLogic#tileColumns}). */
    private void buildTileGrid(int tileCount) {
        float gridWidthDp = metrics.widthDp;
        if (isSideBySide()) gridWidthDp *= MAIN_PANE_WEIGHT / (HEADER_PANE_WEIGHT + MAIN_PANE_WEIGHT);
        int columns = HomeLogic.tileColumns(baseTileColumns(), gridWidthDp);
        int gap = Ui.dp(this, 6f);
        boolean animate = Ui.animate(this);
        LinearLayout row = null;
        for (int i = 0; i < tileCount; i++) {
            if (i % columns == 0) {
                row = new LinearLayout(this);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, tileRowHeightDp()));
                if (i > 0) rowParams.topMargin = gap;
                if (Skin.collective()) {
                    // Collective trace column: every other row is indented to zig-zag along the trace.
                    rowParams.leftMargin = (i / columns) % 2 == 1 ? Ui.dp(this, 24f) : 0;
                }
                tilesBox.addView(row, rowParams);
            }
            View tile = tileView(i);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
            if (i % columns != 0) params.leftMargin = gap;
            row.addView(tile, params);
            if (animate) {
                tile.setAlpha(0f);
                tile.setTranslationY(Ui.dp(this, 10f));
                tile.animate().alpha(1f).translationY(0f).setStartDelay(60 + i * 35L).setDuration(260L)
                        .setInterpolator(new DecelerateInterpolator()).start();
            }
        }
        // Pad a partial last row so its tiles keep the same width as the rows above.
        int remainder = tileCount % columns;
        if (row != null && remainder != 0) {
            for (int i = remainder; i < columns; i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
                params.leftMargin = gap;
                row.addView(new View(this), params);
            }
        }
    }

    /** One shortcut tile for the grid layouts. */
    private View tileView(int position) {
        Tile tile = tiles.get(position);
        int color = tileColor(tile, position);
        FrameLayout frame = new FrameLayout(this);
        TextView number = Ui.bold(this, HomeLogic.tileNumber(position), 11f, Ui.alpha(Ui.ink(), 0.7f));
        if (tile.isEmpty()) {
            frame.setBackground(Ui.touch(Skin.command()
                    ? Ui.outline(this, Ui.alpha(pal.panel, 0.8f), Ui.alpha(color, 0.6f), 18f, 1.5f)
                    : Ui.shape(this, Ui.alpha(color, 0.22f), Ui.SQUARE, 18f)));
            frame.addView(Ui.bold(this, "+ ASSIGN", 15f, Ui.alpha(color, 0.8f)),
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
            number.setTextColor(Ui.alpha(color, 0.6f));
            frame.setContentDescription(getString(R.string.cd_assign_tile, position + 1));
        } else {
            frame.setBackground(Ui.touch(Ui.shape(this, color, Ui.SQUARE, 18f)));
            AppInfo app = tile.comp != null ? repo.find(tile.comp, tile.user) : null;
            int ink = (Skin.command() || Ui.cloak) ? Ui.ink() : Skin.collective() ? COLLECTIVE_INK : Skin.glow(color);
            Bitmap factionArt = prefs.iconStyle() == ICON_STYLE_FACTION_ART ? IconFactory.factionArt(this, tileGlyph(tile, app)) : null;
            ImageView icon = tileIcon(tile, app, ink, factionArt);
            String label = tileLabel(tile, app);
            TextView caption = Ui.oneLine(Ui.bold(this, label.toUpperCase(Locale.getDefault()), 16f, Ui.ink()));
            if (Skin.command()) {
                int iconSize = Ui.dp(this, 30f);
                FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.TOP | Gravity.START);
                iconParams.setMargins(Ui.dp(this, 8f), Ui.dp(this, 8f), 0, 0);
                frame.addView(icon, iconParams);
                caption.setGravity(Gravity.END);
                Ui.fitText(caption, false); // long labels (NAVIGATION) shrink instead of truncating on mdpi landscape
                FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
                captionParams.setMargins(Ui.dp(this, 8f), 0, Ui.dp(this, 12f), Ui.dp(this, 8f));
                frame.addView(caption, captionParams);
            } else {
                int badgeSize = Ui.dp(this, 40f);
                int iconSize = factionArt != null ? badgeSize : Ui.dp(this, 24f);
                int indent = Ui.dp(this, Skin.imperial() ? 24f : 12f);
                FrameLayout badge = new FrameLayout(this);
                Drawable iconFrame = Skin.iconFrame(this, color);
                if (iconFrame != null && factionArt == null && (app == null || app.glyph != null || prefs.iconStyle() != ICON_STYLE_NATIVE)) {
                    badge.setBackground(iconFrame);
                }
                badge.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.CENTER_VERTICAL | Gravity.START);
                badgeParams.leftMargin = indent;
                frame.addView(badge, badgeParams);
                caption.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.START);
                captionParams.setMargins(indent + badgeSize + Ui.dp(this, 8f), Ui.dp(this, 4f), Ui.dp(this, 12f), 0);
                frame.addView(caption, captionParams);
            }
            frame.setContentDescription(label);
        }
        FrameLayout.LayoutParams numberParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        numberParams.setMargins(0, Ui.dp(this, 8f), Ui.dp(this, 12f), 0);
        frame.addView(number, numberParams);
        wireTile(frame, tile, position);
        NotificationBadges.attach(frame, tile, Skin.command() ? NotificationBadges.BOTTOM_START : NotificationBadges.TOP_END, 0); // LCA-T23
        return frame;
    }

    /** Glyph used for faction art: the app's pictogram, else the function's, else null. */
    private static String tileGlyph(Tile tile, AppInfo app) {
        if (app != null && app.glyph != null) return app.glyph;
        return tile.action != null ? HomeLogic.actionGlyph(tile.action) : null;
    }

    /** Caption of an assigned tile: custom label, app label, or function name. */
    private static String tileLabel(Tile tile, AppInfo app) {
        if (tile.label != null && !tile.label.isEmpty()) return tile.label;
        return app != null ? app.label : Tile.actionLabel(tile.action);
    }

    /** Tap launches (or edits an empty tile), long-press edits. */
    void wireTile(View view, final Tile tile, final int position) {
        view.setOnClickListener(v -> {
            if (tile.isEmpty()) {
                Ui.tap(v);
                editTile(tiles, KEY_TILES, position, true);
            } else {
                launchTile(tile, v);
            }
        });
        view.setOnLongClickListener(v -> {
            Ui.longPress(v);
            editTile(tiles, KEY_TILES, position, true);
            return true;
        });
    }

    /** A tile's colour: its custom accent or the position's accent, saturated when VIVID TILES is on. */
    int tileColor(Tile tile, int position) {
        int color = tile.color >= 0 ? pal.c(tile.color) : pal.c(HomeLogic.accentIndex(position));
        return prefs.vividTiles() ? Ui.vivid(color) : color;
    }

    /** Ink colour for pictograms on hub nodes of the given colour. */
    private int hubInk(int color) {
        if (Skin.collective()) return Ui.cloak ? Skin.glow(color) : COLLECTIVE_INK;
        return Skin.command() ? Ui.ink() : Skin.glow(color);
    }

    private ImageView glyphView(String glyph, int color) {
        ImageView view = new ImageView(this);
        view.setImageBitmap(IconFactory.glyph(this, glyph));
        view.setColorFilter(hubInk(color), PorterDuff.Mode.SRC_IN);
        return view;
    }

    private int hubColor(int position) {
        return pal.c(HomeLogic.accentIndex(position));
    }

    /** The INFO node on the tiles page, which switches to the information page. */
    private void addInfoEntry(RevealHost host, int position) {
        int color = hubColor(position);
        host.addNode(glyphView("radio", color), "INFO", color, false).setOnClickListener(v -> {
            Ui.tap(v);
            switchHubPage(HomeLogic.HUB_PAGE_INFO);
        });
    }

    /** One information-panel node; tap shows the panel card, long-press its menu. */
    private void addInfoNode(RevealHost host, final int index) {
        int color = hubPanelColors.get(index);
        String name = hubPanelNames.get(index);
        View node = host.addNode(glyphView(HomeLogic.panelGlyph(name), color), name, color, false);
        node.setOnClickListener(v -> {
            Ui.tap(v);
            showHubPanel(index);
        });
        node.setOnLongClickListener(v -> {
            panelMenu(hubPanelObjs.get(index));
            return true;
        });
    }

    /** RETURN node leading back to {@code page}. */
    private void addReturnNode(RevealHost host, final int page) {
        int color = hubColor(page == HomeLogic.HUB_PAGE_TILES ? hubPanelCount + 1 : hubStations.size());
        host.addNode(glyphView("apps", color), "RETURN", color, false).setOnClickListener(v -> {
            Ui.tap(v);
            switchHubPage(page);
        });
    }

    /** The stations entry on the information page. */
    private void addStationsEntry(RevealHost host, int position) {
        int color = hubColor(position + 2);
        host.addNode(glyphView("computer", color), Stations.entryName(), color, false).setOnClickListener(v -> {
            Ui.tap(v);
            switchHubPage(HomeLogic.HUB_PAGE_STATIONS);
        });
    }

    private void addStationNode(RevealHost host, final int index) {
        Stations.Station station = hubStations.get(index);
        host.addNode(glyphView(station.glyph, station.color), station.name, station.color, false).setOnClickListener(v -> {
            Ui.tap(v);
            showHubPanel(hubPanelCount + index);
        });
    }

    /** The reveal host currently in the tiles box, or null in grid layouts. */
    private RevealHost currentHost() {
        if (tilesBox == null || tilesBox.getChildCount() == 0 || !(tilesBox.getChildAt(0) instanceof RevealHost)) return null;
        return (RevealHost) tilesBox.getChildAt(0);
    }

    /** Folds the hub, swaps to another page and unfolds it again. */
    private void switchHubPage(final int page) {
        if (page == hubPage) return;
        closeHubPanel();
        RevealHost oldHost = currentHost();
        Sfx.play(this, page == HomeLogic.HUB_PAGE_INFO ? Sfx.OPEN : Sfx.CLOSE);
        Runnable swap = () -> {
            hubPage = page;
            refreshTiles();
            final RevealHost newHost = currentHost();
            if (newHost != null) {
                newHost.setPinned(true);
                newHost.setOpen(true, true, page == HomeLogic.HUB_PAGE_TILES ? () -> newHost.setPinned(false) : null);
            }
        };
        if (oldHost != null) {
            oldHost.setPinned(true);
            oldHost.setOpen(false, true, swap);
        } else {
            swap.run();
        }
    }

    /** One tile as a reveal-hub node. */
    private void addHubNode(RevealHost host, int position) {
        Tile tile = tiles.get(position);
        int color = tileColor(tile, position);
        View content;
        String label;
        if (tile.isEmpty()) {
            TextView plus = Ui.bold(this, "+", 18f, Ui.alpha(Skin.glow(color), 0.8f));
            plus.setGravity(Gravity.CENTER);
            content = plus;
            label = "ASSIGN";
        } else {
            AppInfo app = tile.comp != null ? repo.find(tile.comp, tile.user) : null;
            Bitmap factionArt = prefs.iconStyle() == ICON_STYLE_FACTION_ART ? IconFactory.factionArt(this, tileGlyph(tile, app)) : null;
            content = tileIcon(tile, app, hubInk(color), factionArt);
            label = tileLabel(tile, app);
        }
        View node = host.addNode(content, label, color, tile.isEmpty());
        wireTile(node, tile, position);
        NotificationBadges.attach(node, tile, NotificationBadges.TOP_END, 0); // LCA-T23
    }

    /**
     * Icon for a tile: faction art, the app's TrekUI pictogram, its native icon, the function's pictogram,
     * or an alert glyph for a missing app.
     */
    ImageView tileIcon(Tile tile, AppInfo app, int ink, Bitmap factionArt) {
        ImageView view = new ImageView(this);
        if (factionArt != null) {
            view.setImageBitmap(factionArt);
            if (Ui.cloak) view.setAlpha(0.6f);
        } else if (app != null && app.glyph != null && prefs.iconStyle() != ICON_STYLE_NATIVE) {
            view.setImageBitmap(IconFactory.glyph(this, app.glyph));
            view.setColorFilter(ink, PorterDuff.Mode.SRC_IN);
        } else if (app != null && app.rawIcon != null) {
            view.setImageDrawable(IconFactory.copy(app.rawIcon));
            if (Ui.cloak) view.setAlpha(0.6f);
        } else {
            view.setImageBitmap(IconFactory.glyph(this, tile.action != null ? HomeLogic.actionGlyph(tile.action) : "alert"));
            view.setColorFilter(ink, PorterDuff.Mode.SRC_IN);
        }
        return view;
    }

    /** Fills the dock icons. */
    void refreshDock() {
        if (dockIcons == null || dock == null) return;
        dockIcons.removeAllViews();
        int dockCount = prefs.dockCount();
        ensureSize(dock, dockCount);
        int iconStyle = prefs.iconStyle();
        int iconSize = Ui.dp(this, 46f);
        for (int i = 0; i < dockCount; i++) {
            final int position = i;
            final Tile tile = dock.get(i);
            FrameLayout slot = new FrameLayout(this);
            ImageView icon = new ImageView(this);
            AppInfo app = tile.comp != null ? repo.find(tile.comp, tile.user) : null;
            if (app != null) {
                icon.setImageDrawable(IconFactory.style(this, app, pal, iconStyle, prefs.iconTone()));
                slot.setContentDescription(app.label);
            } else if (tile.action != null) {
                icon.setImageBitmap(IconFactory.glyph(this, HomeLogic.actionGlyph(tile.action)));
                int color = prefs.iconTone() != 0 ? Ui.vivid(pal.c(i)) : pal.c(i);
                icon.setColorFilter(Skin.glow(color), PorterDuff.Mode.SRC_IN);
                slot.setContentDescription(Tile.actionLabel(tile.action));
            } else {
                TextView plus = Ui.bold(this, "+", 26f, Ui.alpha(pal.c(1), 0.6f));
                plus.setGravity(Gravity.CENTER);
                slot.addView(plus, matchParent());
                slot.setContentDescription("Assign dock slot " + (i + 1));
            }
            slot.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
            slot.setBackground(Ui.touchArea(this, 16f));
            slot.setOnClickListener(v -> {
                if (tile.isEmpty()) {
                    Ui.tap(v);
                    editTile(dock, KEY_DOCK, position, false);
                } else {
                    launchTile(tile, v);
                }
            });
            slot.setOnLongClickListener(v -> {
                Ui.longPress(v);
                editTile(dock, KEY_DOCK, position, false);
                return true;
            });
            NotificationBadges.attach(slot, tile, NotificationBadges.ICON, iconSize / 2); // LCA-T23
            dockIcons.addView(slot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
        }
    }

    /**
     * The long-press editor for a tile ({@code isTile}) or dock slot: assign, rename, recolour, sound, clear.
     *
     * @param list the tiles or dock list; {@code key} its preference key
     */
    void editTile(final List<Tile> list, final String key, final int position, final boolean isTile) {
        final Tile tile = list.get(position);
        final String name = (isTile ? "TILE " : "DOCK ") + HomeLogic.tileNumber(position);
        Menu menu = new Menu();
        menu.add("ASSIGN APP", () -> openDrawer(appPick("ASSIGN " + name, app -> {
            assignApp(tile, app);
            if (isTile) tile.label = HomeLogic.shortLabel(app.label);
            saveTiles(list, key);
            Sfx.play(this, Sfx.CONFIRM);
        }), false));
        menu.add("ASSIGN TREKUI FUNCTION", () -> pickFunction(name, (action, arg, label) -> {
            tile.comp = null;
            tile.action = action;
            tile.arg = arg;
            tile.label = label;
            saveTiles(list, key);
            Sfx.play(this, Sfx.CONFIRM);
        }));
        if (!tile.isEmpty()) {
            if (isTile) {
                menu.add("RENAME", () -> showing(ConsoleDialog.prompt(this, "RENAME " + name, tile.label, "LABEL", text -> {
                    tile.label = text;
                    saveTiles(list, key);
                })));
                menu.add("CHANGE COLOR", () -> showing(ConsoleDialog.options(this, "COLOR " + name, accentChoices("AUTOMATIC"), choice -> {
                    tile.color = choice == 0 ? -1 : choice - 1;
                    saveTiles(list, key);
                    Sfx.play(this, Sfx.CONFIRM);
                })));
            }
            menu.add("ASSIGN SOUND", () -> pickSound(name, tile.sound, clip -> {
                tile.sound = clip;
                saveTiles(list, key);
            }));
            menu.add("CLEAR", () -> {
                list.set(position, new Tile());
                saveTiles(list, key);
                Sfx.play(this, Sfx.CLOSE);
            });
        }
        showing(ConsoleDialog.options(this, name, menu.labels(), menu::run));
    }

    /** Persists the tiles or dock and refreshes whichever changed. */
    private void saveTiles(List<Tile> list, String key) {
        Tile.save(prefs, key, list);
        if (KEY_DOCK.equals(key)) refreshDock();
        else refreshTiles();
    }

    /** Puts an app on the first empty tile (or dock slot), from the drawer's long-press menu. */
    void addToHome(AppInfo app, boolean toDock) {
        List<Tile> list = toDock ? dock : tiles;
        if (list == null) return;
        int size = toDock ? prefs.dockCount() : prefs.tileCount();
        ensureSize(list, size);
        int free = -1;
        for (int i = 0; i < size; i++) {
            if (list.get(i).isEmpty()) {
                free = i;
                break;
            }
        }
        if (free < 0) {
            Sfx.play(this, Sfx.DENY);
            toast(getString(toDock ? R.string.toast_dock_full : R.string.toast_tiles_full));
            return;
        }
        Tile tile = new Tile();
        assignApp(tile, app);
        tile.label = toDock ? "" : HomeLogic.shortLabel(app.label);
        list.set(free, tile);
        saveTiles(list, toDock ? KEY_DOCK : KEY_TILES);
        Sfx.play(this, Sfx.CONFIRM);
        toast(toDock ? getString(R.string.toast_added_to_dock) : getString(R.string.toast_added_to_tile, HomeLogic.tileNumber(free)));
    }

    // =================================================================================================
    // Launching
    // =================================================================================================

    /** Runs a tile, dock slot or slot assignment: an console function or an app. */
    void launchTile(Tile tile, View view) {
        if (tile.sound != null) {
            Sfx.playClip(this, tile.sound, false);
        }
        if (tile.action == null) {
            AppInfo app = repo.find(tile.comp, tile.user);
            if (app == null) {
                Sfx.play(this, Sfx.ERROR);
                toast(getString(R.string.toast_app_not_found));
            } else {
                launchApp(app, view);
            }
            return;
        }
        if (tile.sound == null && !Tile.A_CLOAK.equals(tile.action) && !Tile.A_RED_ALERT.equals(tile.action)) {
            Ui.tap(view);
        } else {
            haptic(view);
        }
        switch (tile.action) {
            case Tile.A_APPS: openDrawer(null, false); break;
            case Tile.A_SEARCH: openDrawer(null, true); break;
            case Tile.A_CONFIG: openConfig(); break;
            case Tile.A_ANDROID: startSafe(new Intent(Settings.ACTION_SETTINGS), view); break;
            case Tile.A_SHADE: if (shade != null) shade.open(Ui.animate(this)); break;
            case Tile.A_PANEL: expandNotifications(); break;
            case Tile.A_CASCADE: cascade(); break;
            case Tile.A_DIALER: openDialer(); break;
            case Tile.A_CLOAK: toggleCloak(); break;
            case Tile.A_RED_ALERT: RedAlert.toggle(this); break;
            case Tile.A_GROUP: GroupPanel.open(this, tile.arg); break; // LCA-T20
            case Tile.A_LOCK: LockScreen.lock(this); break; // LCA-T20
            case Tile.A_GOVEE: goveeToggle(tile.arg, view); break;
            case Tile.A_HA: HomeAssistantDialog.launch(this, tile.arg, view); break;
            case Tile.A_NEST: {
                Nest.Device thermostat = Nest.find(prefs, tile.arg);
                if (thermostat != null) {
                    ThermostatDialog.show(this, thermostat.name, thermostat.label);
                } else {
                    Sfx.play(this, Sfx.ERROR);
                    toast(getString(R.string.toast_thermostat_not_linked));
                }
                break;
            }
            default: break;
        }
    }

    /** Toggles a Govee light and reports the result with a chirp and a toast. */
    private void goveeToggle(String deviceId, final View view) {
        Govee.power(this, deviceId, null, (ok, message, on) -> {
            Sfx.play(this, ok ? (Boolean.FALSE.equals(on) ? Sfx.CLOSE : Sfx.CONFIRM) : Sfx.ERROR);
            if (ok) haptic(view);
            toast(message);
        });
    }

    /** Engages or disengages cloak (night) mode and rebuilds the UI in its colours. */
    void toggleCloak() {
        boolean engaging = !Cloak.active(this);
        Cloak.toggle(this);
        if (engaging) {
            int minutes = Cloak.minutes(prefs);
            toast(getString(Cloak.canDimSystem(this) ? R.string.toast_cloak_engaged : R.string.toast_cloak_engaged_no_dim, minutes));
        } else {
            toast(getString(R.string.toast_cloak_disengaged));
        }
        buildUi();
    }

    void launchApp(AppInfo app, View view) {
        launchApp(app, view, -1);
    }

    /** True when launching this app should play the comms chirp (messengers, dialers, SMS). */
    private boolean isCommsApp(AppInfo app) {
        String smsPkg = null;
        try {
            smsPkg = Telephony.Sms.getDefaultSmsPackage(this);
        } catch (RuntimeException noTelephony) {
            // devices without telephony
        }
        return HomeLogic.isCommsApp(app.pkg, app.label, app.category == AppInfo.CAT_COMM, dialerPackage(), smsPkg);
    }

    /**
     * Launches an app (any profile) with a clip-reveal animation from {@code view}. The default dialer
     * opens the console dialer instead when enabled; a paused work profile is asked to resume.
     *
     * @param sound Sfx sound id, or -1 for the launch / comms chirp
     */
    void launchApp(AppInfo app, View view, int sound) {
        haptic(view);
        if (app.mainProfile && prefs.b(KEY_CONSOLE_DIALER, true) && app.pkg.equals(dialerPackage())) {
            openDialer();
            return;
        }
        boolean comms = isCommsApp(app);
        Sfx.play(this, sound >= 0 ? sound : comms ? Sfx.COMM : Sfx.LAUNCH);
        commLaunchAt = comms ? System.currentTimeMillis() : 0L;
        try {
            UserManager users = repo.users();
            if (!app.mainProfile && Build.VERSION.SDK_INT >= 28 && users.isQuietModeEnabled(app.user)) {
                users.requestQuietModeEnabled(false, app.user);
                return;
            }
            Rect bounds = null;
            Bundle options = null;
            if (view != null) {
                int[] location = new int[2];
                view.getLocationOnScreen(location);
                bounds = new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
                options = ActivityOptions.makeClipRevealAnimation(view, 0, 0, view.getWidth(), view.getHeight()).toBundle();
            }
            repo.launcherApps().startMainActivity(app.cn, app.user, bounds, options);
        } catch (RuntimeException e) {
            Sfx.play(this, Sfx.ERROR);
            String label = app.label == null ? app.pkg : app.label;
            toast(getString(R.string.toast_unable_to_launch, label.toUpperCase(Locale.getDefault())));
        }
    }

    /**
     * Starts an activity in a new task with a clip-reveal from {@code view}; plays the error chirp and
     * toasts when nothing handles the intent.
     */
    boolean startSafe(Intent intent, View view) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Bundle options = view == null ? null
                    : ActivityOptions.makeClipRevealAnimation(view, 0, 0, view.getWidth(), view.getHeight()).toBundle();
            startActivity(intent, options);
            return true;
        } catch (RuntimeException e) {
            Sfx.play(this, Sfx.ERROR);
            toast(getString(R.string.toast_no_app));
            return false;
        }
    }

    /** Package of the default phone app (cached), or "" when there is none. */
    private String dialerPackage() {
        if (dialerPkg == null) {
            try {
                TelecomManager telecom = getSystemService(TelecomManager.class);
                dialerPkg = telecom != null ? telecom.getDefaultDialerPackage() : null;
            } catch (RuntimeException ignored) {
                // no telecom service
            }
            if (dialerPkg == null) {
                ResolveInfo info = resolveDefault(getPackageManager(), new Intent(Intent.ACTION_DIAL));
                dialerPkg = (info == null || info.activityInfo == null) ? "" : info.activityInfo.packageName;
            }
        }
        return dialerPkg;
    }

    /** {@link PackageManager#resolveActivity} with MATCH_DEFAULT_ONLY, using the non-deprecated API on 33+. */
    @SuppressWarnings("deprecation")
    private static ResolveInfo resolveDefault(PackageManager pm, Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY));
        }
        return pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
    }

    /** Closes other overlays and opens the console dialer. */
    void openDialer() {
        if (openDialog != null && openDialog.isShowing()) openDialog.dismiss();
        if (shade != null && shade.isOpen()) shade.close(false);
        if (drawer != null && drawer.isOpen()) drawer.close(false);
        dialer.open(Ui.animate(this));
    }

    /**
     * Closes other overlays and opens the app drawer.
     *
     * @param pick   non-null to use the drawer as an app picker
     * @param search true to open with the search field focused
     */
    void openDrawer(DrawerView.Pick pick, boolean search) {
        if (openDialog != null && openDialog.isShowing()) openDialog.dismiss();
        if (shade != null && shade.isOpen()) shade.close(false);
        if (dialer != null && dialer.isOpen()) dialer.close(false);
        drawer.open(pick, search, Ui.animate(this));
    }

    /** Opens CONFIG (SettingsActivity); on API 34+ SettingsActivity sets its own fade transition. */
    @SuppressWarnings("deprecation")
    void openConfig() {
        startActivity(new Intent(this, SettingsActivity.class));
        if (Build.VERSION.SDK_INT < 34 && Ui.animate(this)) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        }
    }

    void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /** Light haptic tick for a view when haptics are enabled. */
    private void haptic(View view) {
        if (view != null && prefs.haptics()) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        }
    }

    // =================================================================================================
    // Clock, info line, cascade, battery
    // =================================================================================================

    /** A random code block text, "NN-NNNN". */
    String code() {
        return HomeLogic.codeText(rnd.nextInt(100), rnd.nextInt(10000));
    }

    /** Shuffles one random code block (the last one, block 47's corner, shows two digits). */
    private void cascadeStep() {
        if (codes.isEmpty()) return;
        TextView view = codes.get(rnd.nextInt(codes.size()));
        view.setText(view == codes.get(codes.size() - 1) ? String.valueOf(rnd.nextInt(90) + 10) : code());
    }

    /** The data cascade: every code block flickers to a new code in turn. */
    private void cascade() {
        Sfx.play(this, Sfx.SCANNER);
        for (int i = 0; i < codes.size(); i++) {
            final TextView view = codes.get(i);
            handler.postDelayed(() -> {
                view.setText(code());
                view.animate().alpha(0.3f).setDuration(80L)
                        .withEndAction(() -> view.animate().alpha(1f).setDuration(160L).start()).start();
            }, i * 70L);
        }
    }

    /** 24-hour clock per the CONFIG setting (SYSTEM / 12-HOUR / 24-HOUR). */
    boolean use24h() {
        switch (prefs.clockMode()) {
            case 2: return true;
            case 1: return false;
            default: return DateFormat.is24HourFormat(this);
        }
    }

    /** Shrinks the clock text so it fits its row (long times, seconds, narrow panes). */
    void fitClock() {
        if (clockView == null || clockRowView == null || clockRowView.getWidth() == 0) return;
        float fullSize = Ui.px(this, CLOCK_SP);
        Paint paint = new Paint(clockView.getPaint());
        paint.setTextSize(fullSize);
        float textWidth = paint.measureText(String.valueOf(clockView.getText()));
        float ampmWidth = (ampmView == null || ampmView.length() == 0) ? 0f
                : ampmView.getPaint().measureText(String.valueOf(ampmView.getText())) + ampmView.getPaddingLeft();
        float available = clockRowView.getWidth() - clockRowView.getPaddingLeft() - clockRowView.getPaddingRight() - ampmWidth - Ui.dp(this, 4f);
        float size = (textWidth <= available || textWidth <= 0f) ? fullSize : Math.max(0.55f * fullSize, available * fullSize / textWidth);
        if (Math.abs(clockView.getTextSize() - size) > 0.5f) {
            clockView.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        }
    }

    /** Updates the clock, AM/PM and date views. */
    private void updateClock() {
        if (clockView == null) return;
        Date now = new Date();
        boolean use24h = use24h();
        String pattern = (use24h ? "HH:mm" : "h:mm") + (prefs.seconds() ? ":ss" : "");
        clockView.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(now));
        if (ampmView != null) {
            ampmView.setText(use24h ? "" : new SimpleDateFormat("a", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        }
        fitClock();
        if (dateView != null) {
            String datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMdyyyy");
            dateView.setText(new SimpleDateFormat(datePattern, Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()));
        }
    }

    /** Stardate for a calendar time (see {@link HomeLogic#stardate}). */
    static String stardate(Calendar calendar) {
        return HomeLogic.stardate(calendar);
    }

    /** The info line under the date: cloak time left, stardate, battery and the next alarm within a day. */
    private void updateInfo() {
        if (infoView == null) return;
        String separator = "  •  ";
        StringBuilder sb = new StringBuilder();
        if (Ui.cloak) {
            sb.append("CLOAKED ").append(Math.max(1L, (Cloak.remainingMs(this) + 59_999) / 60_000)).append(" MIN").append(separator);
        }
        if (prefs.stardate()) {
            sb.append("STARDATE ").append(stardate(Calendar.getInstance()));
        }
        if (lastBattery != null) {
            int percent = batteryPct(lastBattery);
            if (percent >= 0) {
                if (sb.length() > 0) sb.append(separator);
                sb.append("PWR ").append(percent).append('%');
                if (lastBattery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) sb.append(" +");
            }
        }
        AlarmManager alarms = getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo next = alarms != null ? alarms.getNextAlarmClock() : null;
        if (next != null && next.getTriggerTime() - System.currentTimeMillis() < ALARM_LOOKAHEAD_MS) {
            if (sb.length() > 0) sb.append(separator);
            String pattern = use24h() ? "HH:mm" : "h:mm a";
            sb.append("ALARM ").append(new SimpleDateFormat(pattern, Locale.getDefault())
                    .format(new Date(next.getTriggerTime())).toUpperCase(Locale.getDefault()));
        }
        infoView.setText(sb.toString());
    }

    /** Battery percentage from a BATTERY_CHANGED intent, or -1. */
    static int batteryPct(Intent intent) {
        return HomeLogic.batteryPercent(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
    }

    /**
     * Handles a battery update: plays the plug-in / low / critical sounds (unless {@code quiet}),
     * refreshes the info line and forwards it to the panels.
     */
    private void onBattery(Intent intent, boolean quiet) {
        lastBattery = intent;
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        switch (batteryWatch.update(plugged, batteryPct(intent), !quiet && resumed)) {
            case PLUGGED_IN: Sfx.play(this, Sfx.POWER); break;
            case LOW: Sfx.play(this, Sfx.WARNING); break;
            case CRITICAL: Sfx.play(this, Sfx.CRITICAL); break;
            default: break;
        }
        updateInfo();
        for (Panel panel : panels) panel.onBattery(intent);
    }

    /** The last BATTERY_CHANGED intent, or null before the first resume. */
    Intent battery() {
        return lastBattery;
    }

    /**
     * Sends a PendingIntent (media session / notification actions), allowing it to start an activity
     * from the home screen where Android 14+ requires that to be explicit.
     */
    @SuppressWarnings("deprecation")
    boolean sendPending(PendingIntent pendingIntent) {
        if (pendingIntent == null) return false;
        try {
            Bundle options = null;
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions basic = ActivityOptions.makeBasic();
                basic.setPendingIntentBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                        ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                options = basic.toBundle();
            }
            pendingIntent.send(this, 0, null, null, null, null, options);
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Pulls down the system notification shade. There is no public API for this; the hidden
     * StatusBarManager method still works for apps holding EXPAND_STATUS_BAR, and failure is harmless.
     */
    @android.annotation.SuppressLint("WrongConstant") // "statusbar" is the hidden service name, on purpose
    void expandNotifications() {
        try {
            Object statusBar = getSystemService("statusbar");
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(statusBar);
        } catch (Throwable ignored) {
            // blocked on this device / Android version
        }
    }

    // =================================================================================================
    // Default home
    // =================================================================================================

    /** True when this launcher is the default home app (the HOME role on API 29+). */
    static boolean isDefaultHome(Context context) {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager roles = context.getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return roles.isRoleHeld(RoleManager.ROLE_HOME);
            }
        }
        ResolveInfo info = resolveDefault(context.getPackageManager(),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
        return info != null && info.activityInfo != null && context.getPackageName().equals(info.activityInfo.packageName);
    }

    /**
     * Asks to become the default home: the HOME role dialog on API 29+ (result {@link #REQ_HOME_ROLE}),
     * otherwise Android's home-app settings, otherwise Settings.
     */
    static void requestDefaultHome(Activity activity) {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager roles = activity.getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME_ROLE);
                    return;
                } catch (RuntimeException ignored) {
                    // fall through to the settings screens
                }
            }
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (RuntimeException e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException ignored) {
                // nothing to open
            }
        }
    }

    /** First-run dialog offering to make console the default home. */
    private void welcome() {
        Sfx.play(this, Sfx.ALERT);
        showing(ConsoleDialog.message(this, getString(R.string.welcome_title), getString(R.string.welcome_message),
                new String[]{getString(R.string.welcome_set_default), getString(R.string.welcome_later)}, which -> {
                    if (which == 0) requestDefaultHome(this);
                }));
    }

    boolean isResumedNow() {
        return resumed;
    }

    Handler handler() {
        return handler;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // =================================================================================================
    // Gestures
    // =================================================================================================

    /**
     * Root view of the home window. Watches every touch (without consuming it) for vertical flings:
     * up from the bottom of home opens the drawer, down from the top opens the console shade (or the
     * system shade); with the console shade open, up from its bottom closes it and down from its top
     * opens the system shade. Cancelled gestures (ACTION_CANCEL) are dropped.
     */
    final class GestureRoot extends FrameLayout {
        /** Minimum vertical travel for a fling, in dp, and how much more vertical than horizontal it must be. */
        private static final float MIN_TRAVEL_DP = 70f;
        private static final float VERTICAL_DOMINANCE = 1.5f;
        /** Minimum fling speed in px/s (unchanged from the original release). */
        private static final float MIN_VELOCITY = 900f;
        /** A swipe up starting this close to the bottom (dp) counts as "at the bottom" even mid-scroll. */
        private static final float BOTTOM_ZONE_DP = 90f;

        private final int touchSlop;
        private VelocityTracker velocity;
        private float downX, downY;
        private boolean eligible, shadeMode, atTop, atBottom;

        GestureRoot(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        /** LCA-T20: the assignable gestures (double-tap, two-finger swipes, pinch, swipe left/right). */
        private final HomeGestureDetector gestures = new HomeGestureDetector(MainActivity.this, new HomeGestureDetector.Host() {
            @Override public boolean homeIdle() {
                return drawer != null && !drawer.isOpen() && (dialer == null || !dialer.isOpen()) && (shade == null || !shade.isOpen());
            }

            @Override public View homeView() {
                return home;
            }

            @Override public ViewGroup rootView() {
                return GestureRoot.this;
            }
        });

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            HomeGestureDetector.Result taken = gestures.onEvent(event); // LCA-T20
            if (taken != HomeGestureDetector.Result.PASS) {
                recycleTracker();
                if (taken == HomeGestureDetector.Result.CANCEL_CHILDREN) {
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
                return true;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    onDown(event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (velocity != null) velocity.addMovement(event);
                    break;
                case MotionEvent.ACTION_UP:
                    if (velocity != null && eligible) {
                        velocity.addMovement(event);
                        velocity.computeCurrentVelocity(1000);
                        onFling(event.getRawX() - downX, event.getRawY() - downY, velocity.getYVelocity());
                    }
                    recycleTracker();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    recycleTracker();
                    break;
                default:
                    break;
            }
            return super.dispatchTouchEvent(event);
        }

        private void onDown(MotionEvent event) {
            downX = event.getRawX();
            downY = event.getRawY();
            eligible = drawer != null && !drawer.isOpen() && (dialer == null || !dialer.isOpen());
            shadeMode = shade != null && shade.isOpen();
            if (shadeMode) {
                atTop = shade.atTop();
                atBottom = shade.atBottom();
            } else {
                atTop = homeScroll == null || !homeScroll.canScrollVertically(-1);
                atBottom = homeScroll == null || !homeScroll.canScrollVertically(1)
                        || downY > getHeight() - insetBottom - Ui.dp(getContext(), BOTTOM_ZONE_DP);
            }
            recycleTracker();
            velocity = VelocityTracker.obtain();
            velocity.addMovement(event);
        }

        private void onFling(float dx, float dy, float vy) {
            float travel = Math.abs(dy);
            if (travel <= Math.max(Ui.dp(getContext(), MIN_TRAVEL_DP), Math.abs(dx) * VERTICAL_DOMINANCE) || travel <= touchSlop) {
                return;
            }
            boolean flingUp = dy < 0 && vy < -MIN_VELOCITY && atBottom;
            boolean flingDown = dy > 0 && vy > MIN_VELOCITY && atTop;
            if (shadeMode) {
                if (flingUp) shade.close(true);
                else if (flingDown) expandNotifications();
            } else if (flingUp) {
                openDrawer(null, false);
            } else if (flingDown) {
                if (prefs.b(KEY_CONSOLE_SHADE, true)) shade.open(Ui.animate(MainActivity.this));
                else expandNotifications();
            }
        }

        private void recycleTracker() {
            if (velocity != null) {
                velocity.recycle();
                velocity = null;
            }
        }
    }
}
