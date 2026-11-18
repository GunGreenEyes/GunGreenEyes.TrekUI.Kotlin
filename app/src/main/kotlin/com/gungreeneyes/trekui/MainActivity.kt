package com.gungreeneyes.trekui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.Dialog
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gungreeneyes.trekui.licensing.Licensing
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The launcher's home screen (the HOME activity) and the hub every other home-screen class talks to.
 *
 * Everything is built in code, no XML layouts. [buildUi] rebuilds the whole view tree whenever
 * preferences change ([Prefs.version]), the configuration changes (rotation, window resize,
 * fold/unfold, multi-window) or cloak mode flips. The tree is:
 * ```
 *   GestureRoot (swipe up = drawer, swipe down = console shade)
 *     home       header ConsoleFrame (title, code blocks, clock, date, info line)
 *                main ConsoleFrame   (APPS / SEARCH / CONFIG sidebar, tiles + panels or a reveal hub)
 *                dock              (left cap, dock icons, right cap)
 *     DrawerView, ShadeView, DialerView   (full-screen overlays, hidden until opened)
 * ```
 * Panels and overlays reach back into this class's non-private properties and helpers (slotColor,
 * bindSlot, tiles, …), so those names are part of the internal API and must not be renamed casually.
 *
 * **Layout.** Sizes adapt through [ScreenMetrics]: wide landscape windows (landscape phones,
 * landscape tablets, unfolded foldables, large multi-window panes) put the header and main frames side by
 * side, and wider windows give the tile grid more columns. The window is edge-to-edge on every API level;
 * system-bar and display-cutout insets are applied as padding (see [applyInsets]).
 *
 * **Back.** Home never finishes on back: back closes the topmost overlay (group panel, hub panel,
 * dialer, shade, drawer) or scrolls home to the top. On API 33+ this is an [OnBackInvokedCallback]
 * (predictive back is enabled in the manifest); below, [onBackPressed].
 *
 * ## TEST HOOKS
 * Views get stable resource IDs (res/values/ids.xml) and the same string as their view tag, so
 * UiAutomator (`By.res(Pattern.compile(".*:id/home_root"))`) and Espresso (`withId` /
 * `withTagValue`) can find them in every interface:
 * ```
 *   home_root          the GestureRoot (whole home window)            all interfaces
 *   home_header        header ConsoleFrame                               all interfaces
 *   home_main          main ConsoleFrame                                 all interfaces
 *   home_tiles         tile container (grid or reveal hub)             console homes
 *   home_dock          dock row                                        when dock slots > 0
 *   home_clock         clock row (slot "clock")                        all interfaces
 *   home_date          date line (slot "date")                         all interfaces
 *   drawer_button      sidebar APPS (slot "nav0")                      all interfaces
 *   search_button      sidebar SEARCH (slot "nav1")                    all interfaces
 *   settings_button    sidebar CONFIG (slot "nav2")                    all interfaces
 *   dock_apps_button   dock left cap (slot "capL", opens all apps)     when dock slots > 0
 *   drawer_view / shade_view / dialer_view   the overlays (GONE until opened)
 *   hub_panel_overlay  reveal-layout panel overlay                     reveal layouts
 * ```
 * Tiles have content descriptions (their label, or "Assign tile N" when empty).
 *
 * The instrumented tests reach `openDrawer`, `openConfig`, the `drawer` field and the static
 * `requestDefaultHome` by reflection (`AppHooks`), so those keep their JVM names: none of them is
 * `internal` (Kotlin would mangle the name) and `requestDefaultHome` is [JvmStatic].
 *
 * ## LICENSING HOOKS
 * `Licensing.init` in [onCreate] and `Licensing.onHomeResumed` in [onResume], marked `// HOOK(licensing)`.
 */
internal class MainActivity : Activity(), AppRepo.Listener {

    // ---- views and state used by panels (the module-internal API) ---------------------------------
    var ampmView: TextView? = null
    var clockRowView: View? = null
    var clockView: TextView? = null
    var dateView: TextView? = null
    var infoView: TextView? = null
    var shipView: TextView? = null
    var headerFrame: ConsoleFrame? = null
    var mainFrame: ConsoleFrame? = null
    var homeScroll: ScrollView? = null
    var tilesBox: LinearLayout? = null
    var dockIcons: LinearLayout? = null
    var tiles: MutableList<Tile>? = null
    var dock: MutableList<Tile>? = null
    lateinit var pal: Palette
    lateinit var prefs: Prefs

    /** Hosts other apps' widgets shown in [WidgetPanel]s; listens between onStart and onStop. */
    lateinit var widgetHost: WidgetHost
    lateinit var repo: AppRepo
    var shade: ShadeView? = null
    val panels: MutableList<Panel> = ArrayList()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** System-bar + cutout insets in px, read in [readInsets]. */
    var insetTop = 0
    var insetBottom = 0
    var insetLeft = 0
    var insetRight = 0

    /** Extra bottom inset taken by the soft keyboard beyond the navigation bar. */
    var insetIme = 0

    // ---- private state -------------------------------------------------------------------------------
    private var dialer: DialerView? = null
    private var drawer: DrawerView? = null
    private var home: LinearLayout? = null
    private var root: GestureRoot? = null
    private var openDialog: Dialog? = null
    private lateinit var metrics: ScreenMetrics
    private var dialerPkg: String? = null
    private var lastBattery: Intent? = null
    private var commLaunchAt = 0L
    private var lastCascade = 0L
    private var lastMinute = -1L
    private var builtVersion = -1
    private var resumed = false
    private val batteryWatch = HomeLogic.BatteryWatch()
    private val rnd = Random()

    /** Text views whose numbers the data cascade shuffles. */
    private val codes: MutableList<TextView> = ArrayList()
    private var backCallback: OnBackInvokedCallback? = null

    // ---- reveal-hub state (console CASCADE / TACTICAL DEPLOY / HUB AND SPOKE layouts) ------------------
    private var hubOverlay: FrameLayout? = null
    private var hubPanelHolder: FrameLayout? = null
    private var hubPanelCount = 0
    private var hubPage = HomeLogic.HUB_PAGE_TILES
    private var hubPanelOpen = -1
    private val hubPanelViews: MutableList<View> = ArrayList()
    private val hubPanelObjs: MutableList<Panel> = ArrayList()
    private val hubPanelNames: MutableList<String> = ArrayList()
    private val hubPanelColors: MutableList<Int> = ArrayList()
    private val hubStations: MutableList<Stations.Station> = ArrayList()

    /** LCA-T23: re-reads unread counts into every badged tile, hub node and dock key. */
    private val badgeRefresh = Runnable { NotificationBadges.refresh(window.decorView) }

    /** Updates the clock every minute (or second), ticks panels once a minute and drives the cascade. */
    private val ticker: Runnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            updateClock()
            val minute = now / 60_000L
            if (minute != lastMinute) {
                if (lastMinute != -1L) {
                    for (panel in panels) panel.tick()
                }
                lastMinute = minute
                if (Ui.cloak != Cloak.active(this@MainActivity)) {
                    Cloak.check(this@MainActivity)
                    buildUi()
                }
                updateInfo()
            }
            val animate = Ui.animate(this@MainActivity)
            if (animate && now - lastCascade > HomeLogic.CASCADE_PERIOD_MS) {
                cascadeStep()
                lastCascade = now
            }
            mainHandler.postDelayed(this, HomeLogic.nextTickDelayMs(now, prefs.seconds(), animate))
        }
    }

    private val batteryReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The sticky broadcast replayed on registration is not a change: don't announce it.
            onBattery(intent, isInitialStickyBroadcast())
        }
    }

    /** Callback for [pickFunction]: the chosen console function, its argument and default label. */
    fun interface FunctionPicked {
        fun picked(action: String, arg: String?, label: String)
    }

    /** Callback for [pickSound]: the chosen clip id, or null for the default sound. */
    fun interface SoundPicked {
        fun picked(clip: String?)
    }

    // =================================================================================================
    // Lifecycle
    // =================================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Licensing.init(this) // HOOK(licensing): trial clock / purchase screen
        prefs = Prefs.get(this)
        prefs.dropRetiredData(this) // the interfaces removed in 44 leave records and keys behind
        LayoutSnapshots.checkUpgrade(this) // LCA-T16: snapshot the layout when the versionCode changed
        repo = AppRepo.get(this)
        widgetHost = WidgetHost(this)
        repo.addListener(this)
        setupEdgeToEdge(this)
        registerBackHandler()
        Sfx.init(this)
        buildUi()
        repo.load()
        if (!startupPlayed) {
            startupPlayed = true
            if (!bootSequence(false)) {
                Sfx.play(this, Sfx.STARTUP)
            }
        }
    }

    /** Hosted widgets receive updates only while the home is visible (widget panels, LCA-T19). */
    override fun onStart() {
        super.onStart()
        widgetHost.start()
    }

    override fun onStop() {
        widgetHost.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Licensing.onHomeResumed(this) // HOOK(licensing): session check on every return home
        MediaListener.setOnBadges(badgeRefresh) // LCA-T23: unread badges while home is in front
        Cloak.check(this)
        if (prefs.version() != builtVersion) {
            buildUi()
        }
        resumed = true
        Sfx.init(this)
        preloadElementSounds()
        lastBattery = ContextCompat.registerReceiver(
            this, batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lastBattery?.let { onBattery(it, true) }
        for (panel in panels) panel.resume()
        if (shade?.isOpen() == true) {
            shade?.startUpdates()
        }
        mainHandler.removeCallbacks(ticker)
        ticker.run()
        if (commLaunchAt > 0) {
            if (System.currentTimeMillis() - commLaunchAt < COMMS_RETURN_WINDOW_MS) {
                Sfx.play(this, Sfx.CHANNEL_CLOSE)
            }
            commLaunchAt = 0L
        }
        if (prefs.b(KEY_BOOT_PREVIEW, false)) {
            prefs.put(KEY_BOOT_PREVIEW, false)
            bootSequence(true)
        }
        Onboarding.maybeStart(this) // LCA-T16: first-run tour (after the default-home welcome) or a replay
        if (!prefs.b(KEY_WELCOMED, false)) {
            prefs.put(KEY_WELCOMED, true)
            mainHandler.postDelayed({
                if (resumed && !isDefaultHome(this)) welcome()
            }, WELCOME_DELAY_MS)
        }
    }

    override fun onPause() {
        if (hubPage != HomeLogic.HUB_PAGE_TILES) {
            hubPage = HomeLogic.HUB_PAGE_TILES
            refreshTiles()
        }
        resumed = false
        MediaListener.setOnBadges(null) // LCA-T23
        mainHandler.removeCallbacks(ticker)
        try {
            unregisterReceiver(batteryReceiver)
        } catch (notRegistered: IllegalArgumentException) {
            // onResume failed before registering; nothing to undo
        }
        for (panel in panels) panel.pause()
        shade?.stopUpdates()
        super.onPause()
    }

    override fun onDestroy() {
        repo.removeListener(this)
        mainHandler.removeCallbacksAndMessages(null)
        val callback = backCallback
        if (Build.VERSION.SDK_INT >= 33 && callback != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
        Sfx.release()
        super.onDestroy()
    }

    /** Rotation, window resize, fold/unfold, density or font change: rebuild for the new window. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buildUi()
    }

    /** Pressing Home while already home closes whatever is open, or scrolls back to the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // keep getIntent() current (licensing debug extras read it)
        if (Intent.ACTION_MAIN != intent.action) return
        openDialog?.let { if (it.isShowing) it.dismiss() }
        if (GroupPanel.close(this)) return // LCA-T20
        if (closeHubPanel()) return
        val dialer = dialer
        val shade = shade
        val drawer = drawer
        val homeScroll = homeScroll
        if (dialer != null && dialer.isOpen()) {
            dialer.close(resumed)
        } else if (shade != null && shade.isOpen()) {
            shade.close(resumed)
        } else if (drawer != null && drawer.isOpen()) {
            drawer.close(resumed)
        } else if (homeScroll != null) {
            if (resumed) homeScroll.smoothScrollTo(0, 0) else homeScroll.scrollTo(0, 0)
        }
    }

    /** Back before API 33 (predictive back replaces this from 33 on; see [registerBackHandler]). */
    @Deprecated("Back before API 33; OnBackInvokedCallback handles 33+")
    @Suppress("DEPRECATION")
    @SuppressLint("GestureBackNavigation") // API 33+ gestures go to backCallback
    override fun onBackPressed() {
        handleBack()
    }

    /**
     * Registers the predictive-back callback on API 33+. Home always consumes back (it must never
     * finish), so the callback stays registered for the activity's lifetime.
     */
    private fun registerBackHandler() {
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = OnBackInvokedCallback { handleBack() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }

    /** Closes the topmost home overlay, or scrolls the home screen back to the top. */
    private fun handleBack() {
        if (GroupPanel.close(this)) return // LCA-T20
        if (closeHubPanel()) return
        val dialer = dialer
        val shade = shade
        val drawer = drawer
        val homeScroll = homeScroll
        if (dialer != null && dialer.isOpen()) {
            dialer.back()
        } else if (shade != null && shade.isOpen()) {
            shade.close(true)
        } else if (drawer != null && drawer.isOpen()) {
            drawer.back()
        } else homeScroll?.smoothScrollTo(0, 0)
    }

    @Deprecated("Activity results still arrive here for the HOME role request")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_HOME_ROLE && isDefaultHome(this)) {
            Sfx.play(this, Sfx.STATUS_OK)
        }
    }

    /** Forwards runtime-permission results to every panel and the dialer (they own the requests). */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // An empty result means the request was interrupted (e.g. the dialog was dismissed): treat as denied.
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        Sfx.play(this, if (granted) Sfx.CONFIRM else Sfx.DENY)
        for (panel in panels) panel.onPermissionResult(requestCode, granted)
        dialer?.onPermission(requestCode, granted)
    }

    /**
     * Requests one runtime permission with the full modern flow (API 26–37): if it is already granted the
     * result is delivered straight away; if Android will no longer show the dialog (denied with "don't ask
     * again", or twice on API 30+) the app's permission settings are opened instead; otherwise the system
     * dialog is shown. The result arrives in [onRequestPermissionsResult] with `requestCode`.
     */
    fun requestRuntimePermission(permission: String, requestCode: Int) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            onRequestPermissionsResult(requestCode, arrayOf(permission), intArrayOf(PackageManager.PERMISSION_GRANTED))
            return
        }
        val askedKey = KEY_PERMISSION_ASKED + permission
        if (prefs.b(askedKey, false) && !shouldShowRequestPermissionRationale(permission)) {
            toast(getString(R.string.toast_permission_settings))
            startSafe(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                null,
            )
            return
        }
        prefs.put(askedKey, true)
        requestPermissions(arrayOf(permission), requestCode)
    }

    override fun onAppsChanged() {
        if (tiles == null || dock == null) {
            loadTiles()
        }
        refreshTiles()
        refreshDock()
        drawer?.onAppsChanged()
    }

    /** Remembers the dialog on screen so Home / opening an overlay can dismiss it. */
    fun showing(dialog: Dialog?) {
        openDialog = dialog
    }

    // =================================================================================================
    // Window, insets, background
    // =================================================================================================

    /** Reads system-bar, display-cutout and keyboard insets (px) from a window-insets dispatch. */
    private fun readInsets(insets: WindowInsetsCompat) {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        insetTop = bars.top
        insetBottom = bars.bottom
        insetLeft = bars.left
        insetRight = bars.right
        insetIme = max(0, ime.bottom - bars.bottom)
    }

    /**
     * Applies the current insets (all four sides, so the landscape navigation bar and side cutouts are
     * cleared too) as home padding and to each overlay, which draw their own backgrounds behind the bars.
     */
    private fun applyInsets() {
        val side = if (metrics.isTwoPane) Ui.rawDp(this, metrics.marginDp) else Ui.dp(this, 8f)
        home?.setPadding(insetLeft + side, insetTop + Ui.dp(this, 4f), insetRight + side, insetBottom + Ui.dp(this, 6f))
        drawer?.setInsets(insetLeft, insetTop, insetRight, insetBottom, insetIme)
        shade?.setInsets(insetLeft, insetTop, insetRight, insetBottom)
        dialer?.setInsets(insetLeft, insetTop, insetRight, insetBottom, insetIme)
    }

    /**
     * Sets the window/root background: black, the interface's pattern (starfield, grid, network) or the
     * system wallpaper dimmed by the "dim" preference.
     */
    fun applyBackground() {
        val bgMode = prefs.bgMode()
        if (bgMode == BG_WALLPAPER) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            root?.setBackgroundColor(Ui.alpha(Skin.bg(), prefs.i(KEY_WALLPAPER_DIM, DEFAULT_WALLPAPER_DIM) / 100.0f))
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            window.setBackgroundDrawable(ColorDrawable(Skin.bg()))
            if (bgMode == BG_PATTERN) {
                root?.background = if (Skin.command()) Starfield(pal) else Skin.backdrop(this)
            } else {
                root?.setBackgroundColor(Skin.bg())
            }
        }
    }

    /** Applies cloak mode's window brightness (only when cloak can't dim the whole phone itself). */
    fun applyCloakBrightness() {
        val attributes = window.attributes
        val brightness = if (!Ui.cloak || Cloak.canDimSystem(this)) {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            max(0.01f, Cloak.level(prefs) / 100.0f)
        }
        if (attributes.screenBrightness != brightness) {
            attributes.screenBrightness = brightness
            window.attributes = attributes
        }
    }

    // =================================================================================================
    // Building the UI
    // =================================================================================================

    /**
     * Throws away and rebuilds the entire home view tree for the current preferences, interface,
     * palette, cloak state and window size. Panels are paused and recreated; an open drawer reopens.
     */
    fun buildUi() {
        if (resumed) {
            for (panel in panels) panel.pause()
        }
        panels.clear()
        codes.clear()
        tiles = null
        dock = null
        Cloak.check(this)
        builtVersion = prefs.version()
        pal = prefs.palette()
        metrics = ScreenMetrics.of(this)
        val drawerWasOpen = drawer?.isOpen() == true

        val newRoot = GestureRoot(this)
        root = newRoot
        markForTests(newRoot, R.id.home_root, "home_root")
        newRoot.contentDescription = getString(R.string.cd_home)
        applyBackground()
        val newHome = buildHome()
        home = newHome
        arrangePanes(newHome)
        if (Ui.cloak) {
            newHome.alpha = 0.8f
        }
        newRoot.addView(newHome, matchParent())
        applyCloakBrightness()

        val newDrawer = DrawerView(this)
        drawer = newDrawer
        newDrawer.visibility = View.GONE
        markForTests(newDrawer, R.id.drawer_view, "drawer_view")
        newRoot.addView(newDrawer, matchParent())
        val newShade = ShadeView(this)
        shade = newShade
        newShade.visibility = View.GONE
        markForTests(newShade, R.id.shade_view, "shade_view")
        newRoot.addView(newShade, matchParent())
        val newDialer = DialerView(this)
        dialer = newDialer
        newDialer.visibility = View.GONE
        markForTests(newDialer, R.id.dialer_view, "dialer_view")
        newRoot.addView(newDialer, matchParent())
        RedAlertOverlay.attach(this, newRoot) // pulsing RED ALERT frame, hidden unless on alert

        newRoot.setOnApplyWindowInsetsListener { view, insets ->
            readInsets(WindowInsetsCompat.toWindowInsetsCompat(insets, view))
            applyInsets()
            insets
        }
        setContentView(newRoot)
        if (repo.isLoaded) {
            onAppsChanged()
        }
        if (resumed) {
            for (panel in panels) panel.resume()
        }
        if (drawerWasOpen) {
            newDrawer.open(null, false, false)
        }
        headerFrame?.reveal()
        mainFrame?.reveal()
        updateClock()
        updateInfo()
        lastBattery?.let { onBattery(it, true) }
    }

    /**
     * Plays the startup sequence over the home screen.
     *
     * @param force true for the CONFIG preview; false plays it only once per device boot
     * @return true if a sequence is (now) showing
     */
    fun bootSequence(force: Boolean): Boolean {
        val root = root ?: return false
        if (!force) {
            if (!prefs.b(KEY_BOOT_SEQUENCE, true) || !prefs.animations()) return false
            val bootCount = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
            if (bootCount >= 0) {
                if (bootCount == prefs.i(KEY_BOOT_SEEN, -1)) return false
                prefs.put(KEY_BOOT_SEEN, bootCount)
            } else if (SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) {
                return false
            }
        }
        for (i in 0 until root.childCount) {
            if (root.getChildAt(i) is BootSequence) return true
        }
        val reveal = Runnable {
            headerFrame?.reveal()
            mainFrame?.reveal()
        }
        val sequence: View = BootSequence(this, pal, prefs.headerText().orEmpty(), reveal)
        root.addView(sequence, matchParent())
        return true
    }

    /** Builds the home column for the current interface. */
    fun buildHome(): LinearLayout {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.clipToPadding = false
        column.addView(
            buildHeaderFrame(),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        val mainParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
        mainParams.topMargin = Ui.dp(this, 6f)
        column.addView(buildMainFrame(), mainParams)
        addDock(column)
        return column
    }

    /**
     * On wide windows (landscape phones, tablets, unfolded foldables) moves the header and main frames
     * side by side, header on the left. It only re-parents [headerFrame] and [mainFrame].
     */
    private fun arrangePanes(column: LinearLayout) {
        val header = headerFrame ?: return
        val main = mainFrame ?: return
        // Portrait tablets keep the stacked layout (and get more tile columns instead).
        if (metrics.widthDp < TWO_PANE_MIN_WIDTH_DP || !metrics.isLandscape) return
        if (header.parent !== column || main.parent !== column) return
        val index = column.indexOfChild(header)
        column.removeView(header)
        column.removeView(main)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.clipToPadding = false
        val headerParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, HEADER_PANE_WEIGHT)
        headerParams.gravity = Gravity.TOP
        row.addView(header, headerParams)
        val mainParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, MAIN_PANE_WEIGHT)
        mainParams.leftMargin = Ui.dp(this, metrics.gutterDp)
        row.addView(main, mainParams)
        column.addView(row, index, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f))
    }

    /** True while the header and main frames are side by side (see [arrangePanes]). */
    private fun isSideBySide(): Boolean {
        val parent = headerFrame?.parent
        return parent != null && parent !== home
    }

    /** Header frame: title, four code blocks in the sidebar, ship line, clock, date and info line. */
    private fun buildHeaderFrame(): ConsoleFrame {
        val cascade = Runnable { cascade() }
        val frame = ConsoleFrame(this)
        headerFrame = frame
        markForTests(frame, R.id.home_header, "home_header")
        frame.topH = Ui.dp(this, 16f)
        frame.botH = Ui.dp(this, 11f)
        frame.ext = Ui.dp(this, 14f)
        frame.colorTop = slotColor("hElbowT", pal.c(1))
        frame.colorBot = slotColor("hElbowB", pal.c(1))
        frame.topBlocks = intArrayOf(slotColor("hBlockT0", pal.c(0)))
        frame.botBlocks = intArrayOf(slotColor("hBlockB0", pal.c(0)), slotColor("hBlockB1", pal.c(3)))
        frame.setPartListener(partListener("h"))

        val title = Ui.bold(this, slotLabel("title", Skin.title(), false), 34f, slotColor("title", pal.c(0)))
        title.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 2f), 0)
        bindSlot(title, "title", cascade)
        frame.setTitle(title)

        val codeColumn = LinearLayout(this)
        codeColumn.orientation = LinearLayout.VERTICAL
        val codeColors = intArrayOf(pal.c(0), pal.c(1), pal.c(0), pal.c(3))
        for (i in codeColors.indices) {
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
            if (i > 0) params.topMargin = Ui.dp(this, 6f)
            codeColumn.addView(codeBlock("code$i", code(), codeColors[i], cascade), params)
        }
        frame.setSidebar(codeColumn)

        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        val ship = Ui.oneLine(
            Ui.text(this, prefs.headerText().orEmpty().uppercase(Locale.getDefault()), 14f, slotColor("ship", pal.c(0))),
        )
        shipView = ship
        ship.gravity = Gravity.END
        bindSlot(ship, "ship", null)
        content.addView(ship, matchWidth())
        content.addView(buildClockRow(), matchWidth())

        val date = Ui.bold(this, "", 20f, slotColor("date", pal.c(1)))
        dateView = date
        date.gravity = Gravity.CENTER
        bindSlot(date, "date") {
            startSafe(
                Intent(
                    Intent.ACTION_VIEW,
                    CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
                        .appendPath(System.currentTimeMillis().toString()).build(),
                ),
                dateView,
            )
        }
        content.addView(date, matchWidth())

        val info = Ui.oneLine(Ui.text(this, "", 14f, pal.textLo))
        infoView = info
        info.gravity = Gravity.CENTER
        info.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 2f))
        content.addView(info, matchWidth())
        frame.setContent(content)
        return frame
    }

    /** The big clock with its AM/PM suffix; tapping opens the alarms app. */
    private fun buildClockRow(): View {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER
        val clock = Ui.bold(this, "--:--", CLOCK_SP, slotColor("clock", pal.textHi))
        clockView = clock
        clock.letterSpacing = 0.01f
        row.addView(clock)
        val ampm = Ui.bold(this, "", 20f, pal.c(1))
        ampmView = ampm
        ampm.setPadding(Ui.dp(this, 6f), 0, 0, Ui.dp(this, 8f))
        val ampmParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ampmParams.gravity = Gravity.BOTTOM
        row.addView(ampm, ampmParams)
        bindSlot(row, "clock") { startSafe(Intent(AlarmClock.ACTION_SHOW_ALARMS), clockView) }
        val ornament = Skin.headerOrnament(this, pal)
        if (ornament != null) {
            row.background = ornament
        }
        if (Skin.imperial()) {
            row.setPadding(Ui.dp(this, 34f), Ui.dp(this, 4f), Ui.dp(this, 34f), Ui.dp(this, 20f))
        }
        clockRowView = row
        row.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) fitClock()
        }
        return row
    }

    /** Main frame: APPS / SEARCH / CONFIG sidebar and either the scrolling tiles + panels or the reveal hub. */
    private fun buildMainFrame(): ConsoleFrame {
        val cascade = Runnable { cascade() }
        val frame = ConsoleFrame(this)
        mainFrame = frame
        markForTests(frame, R.id.home_main, "home_main")
        frame.topH = Ui.dp(this, 12f)
        frame.botH = Ui.dp(this, 16f)
        frame.colorTop = slotColor("mElbowT", pal.c(0))
        frame.colorBot = slotColor("mElbowB", pal.c(3))
        frame.topBlocks = intArrayOf(slotColor("mBlockT0", pal.c(4)))
        frame.botBlocks = intArrayOf(slotColor("mBlockB0", pal.c(2)))
        frame.setPartListener(partListener("m"))

        val ops = Ui.bold(this, slotLabel("ops", Skin.ops(), false), 19f, slotColor("ops", pal.c(4)))
        ops.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 2f), 0)
        bindSlot(ops, "ops", null)
        frame.setTitle(ops)
        val footer = Ui.text(this, slotLabel("foot", Skin.foot(), false), 13f, slotColor("foot", pal.c(2)))
        footer.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 2f), 0)
        bindSlot(footer, "foot", null)
        frame.setFooter(footer)

        val sidebar = object : LinearLayout(this) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                    fitNavButtons(this, MeasureSpec.getSize(heightSpec) - paddingTop - paddingBottom)
                }
                super.onMeasure(widthSpec, heightSpec)
            }
        }
        sidebar.orientation = LinearLayout.VERTICAL
        addNav(sidebar, "nav0", "APPS", pal.c(0)) { openDrawer(null, false) }
        addNav(sidebar, "nav1", "SEARCH", pal.c(2)) { openDrawer(null, true) }
        addNav(sidebar, "nav2", "CONFIG", pal.c(3)) { openConfig() }
        val blockParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
        blockParams.topMargin = Ui.dp(this, 6f)
        sidebar.addView(codeBlock("nav3", "47", pal.c(1), cascade), blockParams)
        frame.setSidebar(sidebar)

        if (prefs.revealMode()) {
            homeScroll = null
            frame.setContent(buildHubArea())
        } else {
            val scroll = ScrollView(this)
            homeScroll = scroll
            scroll.isVerticalScrollBarEnabled = false
            scroll.overScrollMode = View.OVER_SCROLL_NEVER
            scroll.isFillViewport = true
            val content = LinearLayout(this)
            content.orientation = LinearLayout.VERTICAL
            val box: LinearLayout = if (Skin.collective()) CollectiveDraw.TraceColumn(this, pal.c(0)) else LinearLayout(this)
            tilesBox = box
            box.orientation = LinearLayout.VERTICAL
            markForTests(box, R.id.home_tiles, "home_tiles")
            if (Skin.collective()) {
                box.setPadding(0, 0, 0, Ui.dp(this, 24f))
            }
            content.addView(box, matchWidth())
            buildPanels(content)
            scroll.addView(content)
            frame.setContent(scroll)
        }
        return frame
    }

    /** Adds the dock row (left cap = all apps, dock icons, right cap) to a home column, if enabled. */
    fun addDock(column: LinearLayout) {
        if (prefs.dockCount() <= 0) {
            dockIcons = null
            return
        }
        val rowHeight = Ui.dp(this, 60f)
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        markForTests(row, R.id.home_dock, "home_dock")

        val leftCap = FrameLayout(this)
        leftCap.background = Ui.touch(Ui.shape(this, slotColor("capL", pal.c(0)), Ui.LEFT, 20f))
        val capIcon = ImageView(this)
        val capSlot = Tile.slot(prefs, "capL")
        val capComp = capSlot?.comp
        val capApp = if (capSlot == null || capComp == null) null else repo.find(capComp, capSlot.user)
        val capRawIcon = capApp?.rawIcon
        if (capApp != null && capRawIcon != null && (capApp.glyph == null || prefs.iconStyle() == ICON_STYLE_NATIVE)) {
            capIcon.setImageDrawable(IconFactory.copy(capRawIcon))
        } else {
            val capGlyph = capApp?.glyph
            val capAction = capSlot?.action
            val glyph = when {
                capGlyph != null -> capGlyph
                capAction == null -> "apps"
                else -> HomeLogic.actionGlyph(capAction)
            }
            capIcon.setImageBitmap(IconFactory.glyph(this, glyph))
            capIcon.setColorFilter(Ui.ink(), PorterDuff.Mode.SRC_IN)
        }
        val capIconSize = Ui.dp(this, 30f)
        leftCap.addView(capIcon, FrameLayout.LayoutParams(capIconSize, capIconSize, Gravity.CENTER))
        leftCap.contentDescription = getString(R.string.cd_all_apps)
        bindSlot(leftCap, "capL") { openDrawer(null, false) }
        row.addView(leftCap, LinearLayout.LayoutParams(Ui.dp(this, 64f), rowHeight))

        val icons = LinearLayout(this)
        dockIcons = icons
        icons.gravity = Gravity.CENTER_VERTICAL
        icons.background = Ui.outline(this, Ui.alpha(pal.panel, 0.85f), Ui.alpha(pal.c(1), 0.5f), Skin.trayRadius(4f), 1.5f)
        val iconsParams = LinearLayout.LayoutParams(0, rowHeight, 1.0f)
        iconsParams.leftMargin = Ui.dp(this, 6f)
        iconsParams.rightMargin = Ui.dp(this, 6f)
        row.addView(icons, iconsParams)

        val rightCap = View(this)
        rightCap.background = Ui.touch(Ui.shape(this, slotColor("capR", pal.c(3)), Ui.RIGHT, 20f))
        bindSlot(rightCap, "capR", null)
        row.addView(rightCap, LinearLayout.LayoutParams(Ui.dp(this, 22f), rowHeight))

        val rowParams = matchWidth()
        rowParams.topMargin = Ui.dp(this, 6f)
        column.addView(row, rowParams)
    }

    /** Adds one sidebar button (APPS / SEARCH / CONFIG), which is also an assignable slot. */
    private fun addNav(sidebar: LinearLayout, slot: String, defaultLabel: String, color: Int, action: Runnable) {
        val button = Ui.button(this, slotLabel(slot, defaultLabel, true), slotColor(slot, color), Ui.SQUARE, null)
        button.background = Ui.touch(Ui.shape(this, slotColor(slot, color), Ui.SQUARE, 0f))
        Ui.setTextSp(button, if (Skin.imperial()) 13f else 15f)
        if (Skin.imperial()) {
            button.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 16f), Ui.dp(this, 12f))
        } else {
            button.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 8f), Ui.dp(this, 4f))
        }
        bindSlot(button, slot, action)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, NAV_BUTTON_DP))
        if (sidebar.childCount > 0) params.topMargin = Ui.dp(this, 6f)
        sidebar.addView(button, params)
    }

    /**
     * Shrinks the APPS / SEARCH / CONFIG buttons evenly when the sidebar is shorter than their natural
     * height (large "display size" settings, short landscape frames), so CONFIG never falls off-screen;
     * restores the natural height whenever there is room.
     */
    private fun fitNavButtons(sidebar: LinearLayout, available: Int) {
        if (available <= 0 || sidebar.childCount < NAV_BUTTONS) return
        val natural = Ui.dp(this, NAV_BUTTON_DP)
        var margins = 0
        for (i in 0 until sidebar.childCount) {
            margins += (sidebar.getChildAt(i).layoutParams as LinearLayout.LayoutParams).topMargin
        }
        val height = min(natural, max(Ui.dp(this, NAV_BUTTON_MIN_DP), (available - margins) / NAV_BUTTONS))
        for (i in 0 until NAV_BUTTONS) {
            val lp = sidebar.getChildAt(i).layoutParams
            if (lp.height != height) lp.height = height
        }
    }

    /**
     * One console code block (header sidebar or the "47" block under the nav buttons). Shows a random
     * code that the data cascade shuffles, or the slot's assigned label with a small code in the corner.
     */
    private fun codeBlock(slot: String, defaultText: String, color: Int, action: Runnable?): View {
        val hasLabel = slotHasLabel(slot)
        val angular = !Skin.command()
        val isBlock47 = "nav3" == slot
        val block = FrameLayout(this)
        block.background = Ui.touch(Ui.shape(this, slotColor(slot, color), Ui.SQUARE, 0f))

        val main = Ui.bold(this, if (hasLabel) slotLabel(slot, "", true) else defaultText, if (angular) 11f else 13f, Ui.ink())
        if (angular) {
            main.gravity = (if (isBlock47) Gravity.BOTTOM else Gravity.CENTER_VERTICAL) or Gravity.END
            main.setPadding(Ui.dp(this, 8f), 0, Ui.dp(this, 12f), if (isBlock47) Ui.dp(this, 12f) else 0)
        } else {
            main.gravity = Gravity.BOTTOM or Gravity.END
            main.setPadding(Ui.dp(this, 2f), 0, Ui.dp(this, 8f), Ui.dp(this, 4f))
        }
        main.setSingleLine(true)
        main.ellipsize = TextUtils.TruncateAt.END
        Ui.fitText(main, true) // short/narrow blocks (480x800 mdpi, landscape block 47) shrink the code to fit
        block.addView(main, matchParent())

        if (hasLabel && (!angular || isBlock47)) {
            val small = if ("47" == defaultText) defaultText else code()
            val corner = Ui.text(this, small, 10f, Ui.alpha(Ui.ink(), 0.6f))
            corner.setSingleLine(true)
            corner.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (angular) {
                corner.setPadding(Ui.dp(this, 16f), Ui.dp(this, 8f), Ui.dp(this, 4f), 0)
            } else {
                corner.setPadding(Ui.dp(this, 6f), Ui.dp(this, 2f), Ui.dp(this, 4f), 0)
            }
            block.addView(
                corner,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
                ),
            )
            codes.add(corner)
        } else if (!hasLabel) {
            codes.add(main)
        }
        block.contentDescription = if (hasLabel) slotLabel(slot, "", true) else Tile.slotName(slot)
        bindSlot(block, slot, action)
        return block
    }

    // =================================================================================================
    // Slots: every console element can be assigned an app, function, label, colour or sound
    // =================================================================================================

    /** The slot's custom accent colour, or `fallback` when it has none. */
    fun slotColor(slot: String, fallback: Int): Int {
        val custom = Tile.slot(prefs, slot)
        return if (custom == null || custom.color < 0) fallback else pal.c(custom.color)
    }

    private fun slotHasLabel(slot: String): Boolean {
        val custom = Tile.slot(prefs, slot)
        return custom != null && (!custom.label.isNullOrEmpty() || !custom.isEmpty())
    }

    /**
     * The text a slot shows: its custom label; with `useAssignment`, else the name of the app or
     * function assigned to it; else `fallback`.
     */
    fun slotLabel(slot: String, fallback: String, useAssignment: Boolean): String {
        val custom = Tile.slot(prefs, slot) ?: return fallback
        val label = custom.label
        if (!label.isNullOrEmpty()) {
            return label.uppercase(Locale.getDefault())
        }
        if (useAssignment && !custom.isEmpty()) {
            if (custom.action != null) return Tile.actionLabel(custom.action)
            val app = repo.find(custom.comp, custom.user)
            return if (app != null) HomeLogic.shortLabel(app.label).uppercase(Locale.getDefault()) else fallback
        }
        return fallback
    }

    /**
     * Wires a view as an assignable slot: tap runs the assignment (or `defaultAction`), long-press
     * opens the slot editor. Known navigation slots also get their test IDs here.
     */
    fun bindSlot(view: View, slot: String, defaultAction: Runnable?) {
        when (slot) {
            "nav0" -> markForTests(view, R.id.drawer_button, "drawer_button")
            "nav1" -> markForTests(view, R.id.search_button, "search_button")
            "nav2" -> markForTests(view, R.id.settings_button, "settings_button")
            "capL" -> markForTests(view, R.id.dock_apps_button, "dock_apps_button")
            "clock" -> markForTests(view, R.id.home_clock, "home_clock")
            "date" -> markForTests(view, R.id.home_date, "home_date")
            else -> {}
        }
        view.setOnClickListener { v -> runSlot(slot, v, defaultAction) }
        view.setOnLongClickListener { v ->
            Ui.longPress(v)
            editSlot(slot)
            true
        }
    }

    /** Tap/long-press listener for an ConsoleFrame's elbows and bar segments (`prefix` "h" or "m"). */
    fun partListener(prefix: String): ConsoleFrame.PartListener = ConsoleFrame.PartListener { part, view, longPress ->
        val slot = HomeLogic.framePartSlot(prefix, part)
        if (longPress) {
            Ui.longPress(view)
            editSlot(slot)
        } else {
            runSlot(slot, view, null)
        }
    }

    /** Runs a slot: its assigned app/function, else its sound plus the default action. */
    private fun runSlot(slot: String, view: View, defaultAction: Runnable?) {
        val custom = Tile.slot(prefs, slot)
        if (custom != null && !custom.isEmpty()) {
            launchTile(custom, view)
            return
        }
        val sound = custom?.sound
        if (sound != null) {
            haptic(view)
            Sfx.playClip(this, sound, false)
        } else {
            Ui.tap(view)
        }
        defaultAction?.run()
    }

    /** The long-press editor for a slot: assign app/function, rename, recolour, sound, reset. */
    private fun editSlot(slot: String) {
        val existing = Tile.slot(prefs, slot)
        val tile = existing ?: Tile()
        val slotName = Tile.slotName(slot)
        val menu = Menu()
        menu.add("ASSIGN APP") {
            openDrawer(appPick("ASSIGN $slotName") { app ->
                assignApp(tile, app)
                saveSlot(slot, tile)
            }, false)
        }
        menu.add("ASSIGN TREKUI FUNCTION") {
            pickFunction(slotName) { action, arg, label ->
                tile.comp = null
                tile.action = action
                tile.arg = arg
                if (Tile.A_GOVEE == action && Tile.slotHasText(slot) && tile.label.isNullOrEmpty()) {
                    tile.label = label
                }
                saveSlot(slot, tile)
            }
        }
        if (Tile.slotHasText(slot)) {
            menu.add("RENAME") { renameSlot(slot, slotName, tile) }
        }
        menu.add("CHANGE COLOR") {
            showing(ConsoleDialog.options(this, "COLOR • $slotName", accentChoices("DEFAULT")) { choice ->
                tile.color = if (choice == 0) -1 else choice - 1
                saveSlot(slot, tile)
            })
        }
        menu.add("ASSIGN SOUND") {
            pickSound(slotName, tile.sound) { clip ->
                tile.sound = clip
                saveSlot(slot, tile)
            }
        }
        if (existing != null) {
            menu.add("RESET TO DEFAULT") {
                Tile.putSlot(prefs, slot, null)
                Sfx.play(this, Sfx.CLOSE)
                buildUi()
            }
        }
        menu.add("OPEN TREKUI CONFIG") { openConfig() }
        val title = slotName + if (tile.isEmpty()) "" else " • " + slotLabel(slot, "", true)
        showing(ConsoleDialog.options(this, title, menu.labels()) { menu.run(it) })
    }

    /** RENAME for a slot; the ship line edits the header text preference instead. */
    private fun renameSlot(slot: String, slotName: String, tile: Tile) {
        val isShipLine = "ship" == slot
        showing(
            ConsoleDialog.prompt(
                this, "RENAME $slotName", if (isShipLine) prefs.headerText() else tile.label, "LABEL (EMPTY = DEFAULT)",
            ) { text ->
                if (!isShipLine) {
                    tile.label = text
                    saveSlot(slot, tile)
                    return@prompt
                }
                prefs.put(prefs.headerKey(), if (text.isEmpty()) prefs.headerDefault() else text)
                prefs.bump()
                buildUi()
            },
        )
    }

    /** Saves a slot customisation (or removes it when nothing is customised) and rebuilds. */
    private fun saveSlot(slot: String, tile: Tile) {
        val untouched = tile.isEmpty() && tile.color < 0 && tile.label.isNullOrEmpty() && tile.sound == null
        Tile.putSlot(prefs, slot, if (untouched) null else tile)
        Sfx.play(this, Sfx.CONFIRM)
        buildUi()
    }

    /** A dialog menu built as label + action pairs, so the pick handler needs no string comparisons. */
    private class Menu {
        private val labels = ArrayList<String>()
        private val actions = ArrayList<() -> Unit>()

        fun add(label: String, action: () -> Unit) {
            labels.add(label)
            actions.add(action)
        }

        fun labels(): Array<String> = labels.toTypedArray()

        fun run(index: Int) {
            if (index >= 0 && index < actions.size) actions[index]()
        }
    }

    /**
     * Lists the assignable console functions plus one entry per Govee light and Nest thermostat (or a
     * "set up" entry when none are configured, which leads to CONFIG).
     */
    private fun pickFunction(title: String, onPicked: FunctionPicked) {
        val lights = Govee.devices(prefs)
        val thermostats = Nest.devices(prefs)
        val labels = ArrayList(Tile.ACTION_LABELS.asList())
        val firstLight = Tile.ACTIONS.size
        if (lights.isEmpty()) labels.add("GOVEE LIGHT (SET UP…)")
        for (light in lights) labels.add("LIGHTS: " + light.title())
        val firstThermostat = labels.size
        if (thermostats.isEmpty()) labels.add("NEST THERMOSTAT (SET UP…)")
        for (thermostat in thermostats) labels.add("CLIMATE: " + thermostat.label)
        val homeAssistantEntry = labels.size // LCA-T23
        labels.add(getString(if (HomeAssistant.configured(this)) R.string.ha_pick_entry else R.string.ha_pick_setup))
        val groups = AppGroups.load(prefs) // LCA-T20: OPEN GROUP entries + NEW GROUP…
        val firstGroup = labels.size
        for (g in groups) labels.add(getString(R.string.fn_group_entry, g.name))
        labels.add(getString(R.string.fn_new_group))

        showing(ConsoleDialog.options(this, title, labels.toTypedArray()) { which ->
            if (which >= firstGroup) {
                if (which - firstGroup < groups.size) {
                    val g = groups[which - firstGroup]
                    onPicked.picked(Tile.A_GROUP, g.id, g.name)
                } else {
                    GroupEditor.create(this, null) { g -> onPicked.picked(Tile.A_GROUP, g.id, g.name) }
                }
            } else if (which == homeAssistantEntry) {
                HomeAssistantDialog.pick(this) { arg, label -> onPicked.picked(Tile.A_HA, arg, label) }
            } else if (which < firstLight) {
                onPicked.picked(Tile.ACTIONS[which], null, Tile.ACTION_LABELS[which])
            } else if (which < firstThermostat) {
                if (lights.isEmpty()) {
                    toast(getString(R.string.toast_add_light_first))
                    openConfig()
                } else {
                    val light = lights[which - firstLight]
                    onPicked.picked(Tile.A_GOVEE, light.id, light.title())
                }
            } else if (thermostats.isEmpty()) {
                toast(getString(R.string.toast_link_nest_first))
                openConfig()
            } else {
                onPicked.picked(Tile.A_NEST, thermostats[which - firstThermostat].name, "CLIMATE")
            }
        })
    }

    /**
     * Sound picker for a slot, tile or station: default, silent, the tap rotation or any library clip.
     * Without a sound library the third entry leads to CONFIG › AUDIO instead.
     */
    private fun pickSound(title: String, current: String?, onPicked: SoundPicked) {
        val hasLibrary = SoundLibrary.all(this).isNotEmpty()
        val labels: Array<String>
        val values: Array<String>
        if (hasLibrary) {
            labels = arrayOf(
                (if (current == null) "● " else "") + "DEFAULT (USUAL SOUNDS)",
                (if ("none" == current) "● " else "") + "SILENT",
                (if ("#taps" == current) "● " else "") + "TAP ROTATION (7 SOUNDS)",
            )
            values = arrayOf("#default", "none", "#taps")
        } else {
            labels = arrayOf("DEFAULT (USUAL SOUNDS)", "SILENT", "IMPORT SOUNDS IN CONFIG › AUDIO…")
            values = arrayOf("#default", "none", "#config")
        }
        SoundPicker.pick(this, "SOUND • $title", labels, values, current, null) { picked ->
            if ("#config" == picked) {
                openConfig()
                return@pick
            }
            val clip = if ("#default" == picked) null else picked
            if (clip != null) Sfx.playClip(this, clip, true)
            onPicked.picked(clip)
            preloadElementSounds()
        }
    }

    /** Preloads every custom sound assigned to a slot, tile or dock icon so taps play instantly. */
    private fun preloadElementSounds() {
        val clips = ArrayList<String>()
        for (slot in Tile.SLOTS) {
            Tile.slot(prefs, slot[0])?.sound?.let { clips.add(it) }
        }
        tiles?.forEach { tile -> tile.sound?.let { clips.add(it) } }
        dock?.forEach { tile -> tile.sound?.let { clips.add(it) } }
        if (clips.isNotEmpty()) Sfx.preload(this, clips)
    }

    // =================================================================================================
    // Panels and the reveal hub
    // =================================================================================================

    /** Creates the information panel (or hosted widget panel) with the given id, or null for an unknown id. */
    fun createPanel(id: String, color: Int): Panel? {
        if (WidgetSlots.isSlotId(id)) return WidgetPanel(this, color, id)
        return when (id) {
            "weather" -> WeatherPanel(this, color)
            "calendar" -> CalendarPanel(this, color)
            "media" -> MediaPanel(this, color)
            "power" -> PowerPanel(this, color)
            "systems" -> SystemsPanel(this, color)
            else -> null
        }
    }

    /** Creates and registers a visible panel, wiring long-press on its header and bracket to its menu. */
    private fun createPanelView(panel: Panel): View {
        panels.add(panel)
        val view = panel.create()
        val menu = View.OnLongClickListener {
            panelMenu(panel)
            true
        }
        panel.card?.header?.setOnLongClickListener(menu)
        panel.card?.bracket?.setOnLongClickListener(menu)
        return view
    }

    /** Adds every visible panel (in the user's order) below the tiles, then the customise hint. */
    fun buildPanels(column: LinearLayout) {
        val hidden = prefs.hiddenWidgets()
        var colorIndex = 0
        for (id in prefs.widgetOrder()) {
            if (hidden.contains(id)) continue
            val panel = createPanel(id, pal.c(colorIndex++)) ?: continue
            val params = matchWidth()
            params.topMargin = Ui.dp(this, 16f)
            column.addView(createPanelView(panel), params)
        }
        val hint = Ui.text(this, getString(R.string.hint_customize_console), 11f, Ui.alpha(pal.textLo, 0.6f))
        hint.gravity = Gravity.CENTER
        hint.setPadding(0, Ui.dp(this, 16f), 0, Ui.dp(this, 8f))
        column.addView(hint, matchWidth())
    }

    /**
     * Content of the main frame in the reveal layouts: the hub (tiles box) plus a hidden overlay in which
     * one panel or station at a time is shown as a card.
     */
    private fun buildHubArea(): View {
        val area = FrameLayout(this)
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        val box = LinearLayout(this)
        tilesBox = box
        box.orientation = LinearLayout.VERTICAL
        markForTests(box, R.id.home_tiles, "home_tiles")
        column.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f))

        hubPanelViews.clear()
        hubPanelObjs.clear()
        hubPanelNames.clear()
        hubPanelColors.clear()
        hubPanelOpen = -1
        val hidden = prefs.hiddenWidgets()
        var colorIndex = 0
        for (id in prefs.widgetOrder()) {
            if (hidden.contains(id)) continue
            val color = pal.c(colorIndex++)
            val panel = createPanel(id, color) ?: continue
            hubPanelViews.add(createPanelView(panel))
            hubPanelObjs.add(panel)
            hubPanelNames.add(HomeLogic.shortPanelName(id))
            hubPanelColors.add(color)
        }
        hubPanelCount = hubPanelViews.size
        hubStations.clear()
        hubStations.addAll(
            Stations.build(this, pal) { stationId, stationName ->
                pickSound(stationName, prefs.s(KEY_STATION_SOUND + stationId, null)) { clip ->
                    if (clip != null) prefs.put(KEY_STATION_SOUND + stationId, clip) else prefs.remove(KEY_STATION_SOUND + stationId)
                }
            },
        )
        for (station in hubStations) hubPanelViews.add(station.view)
        area.addView(column, matchParent())

        val overlay = FrameLayout(this)
        hubOverlay = overlay
        markForTests(overlay, R.id.hub_panel_overlay, "hub_panel_overlay")
        overlay.setBackgroundColor(Ui.alpha(Skin.bg(), 0.82f))
        overlay.isClickable = true
        overlay.setOnClickListener { showHubPanel(-1) }
        overlay.visibility = View.GONE
        val cardScroll = ScrollView(this)
        cardScroll.isVerticalScrollBarEnabled = false
        cardScroll.overScrollMode = View.OVER_SCROLL_NEVER
        val holder = FrameLayout(this)
        hubPanelHolder = holder
        holder.background = Ui.outline(
            this, Ui.alpha(Skin.surface(), 0.96f), Ui.alpha(pal.c(0), 0.45f), Skin.trayRadius(16f), 1.2f,
        )
        val pad = Ui.dp(this, 8f)
        holder.setPadding(pad, pad, pad, pad)
        cardScroll.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // On tablets a full-width card is unreadably wide: cap it and centre it.
        val cardWidth = if (metrics.widthDp > HUB_PANEL_MAX_WIDTH_DP + 80f) {
            Ui.dp(this, HUB_PANEL_MAX_WIDTH_DP)
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        overlay.addView(cardScroll, FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        area.addView(overlay, matchParent())
        return area
    }

    /** Shows hub card `index` (panels first, then stations) over the hub; -1 hides the card. */
    private fun showHubPanel(index: Int) {
        val overlay = hubOverlay ?: return
        val host = currentHost()
        val animate = Ui.animate(this)
        if (index < 0 || index >= hubPanelViews.size) {
            if (hubPanelOpen < 0) return
            hubPanelOpen = -1
            host?.setDim(1.0f)
            Sfx.play(this, Sfx.CLOSE)
            if (animate) {
                overlay.animate().alpha(0f).setDuration(180L).withEndAction {
                    if (hubPanelOpen < 0) hubOverlay?.visibility = View.GONE
                }.start()
            } else {
                overlay.visibility = View.GONE
            }
            return
        }
        hubPanelOpen = index
        host?.setDim(0.15f)
        val card = hubPanelViews[index]
        (card.parent as? ViewGroup)?.removeView(card)
        val holder = hubPanelHolder
        holder?.removeAllViews()
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.setMargins(Ui.dp(this, 4f), Ui.dp(this, 12f), Ui.dp(this, 4f), Ui.dp(this, 12f))
        holder?.addView(card, params)
        card.isClickable = true
        Sfx.play(this, Sfx.OPEN)
        overlay.visibility = View.VISIBLE
        if (animate) {
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(200L).start()
            card.pivotX = overlay.width / 2.0f
            card.pivotY = overlay.height.toFloat()
            card.scaleX = 0.6f
            card.scaleY = 0.6f
            card.animate().scaleX(1f).scaleY(1f).setDuration(260L).setInterpolator(DecelerateInterpolator(1.5f)).start()
        } else {
            overlay.alpha = 1f
        }
    }

    /** Closes the hub card if one is open; returns whether it did. */
    fun closeHubPanel(): Boolean {
        if (hubPanelOpen < 0) return false
        showHubPanel(-1)
        return true
    }

    /** Long-press menu of a panel: its own entries, then MOVE UP / MOVE DOWN / HIDE PANEL. */
    private fun panelMenu(panel: Panel) {
        panel.card?.let { Ui.longPress(it) }
        val extra = panel.extraMenu()
        val menu = Menu()
        for (entry in extra.indices) {
            menu.add(extra[entry]) { panel.onExtraMenu(entry) }
        }
        menu.add("MOVE UP") { movePanel(panel, -1) }
        menu.add("MOVE DOWN") { movePanel(panel, +1) }
        menu.add(getString(R.string.widget_add_panel)) { WidgetPickActivity.start(this) }
        menu.add("HIDE PANEL") {
            prefs.setWidgetHidden(panel.id(), true)
            toast(getString(R.string.toast_panel_hidden))
            Sfx.play(this, Sfx.CONFIRM)
            buildUi()
        }
        showing(ConsoleDialog.options(this, panel.title(), menu.labels()) { menu.run(it) })
    }

    /** Moves a panel one place up (-1) or down (+1) in the panel order and rebuilds. */
    private fun movePanel(panel: Panel, direction: Int) {
        val order = prefs.widgetOrder()
        val from = order.indexOf(panel.id())
        val to = from + direction
        if (from >= 0 && to >= 0 && to < order.size) {
            order.removeAt(from)
            order.add(to, panel.id())
            prefs.setWidgetOrder(order)
        }
        Sfx.play(this, Sfx.CONFIRM)
        buildUi()
    }

    // =================================================================================================
    // Tiles and dock
    // =================================================================================================

    /** Loads tiles and dock from preferences, creating the defaults once the app list is loaded. */
    private fun loadTiles() {
        tiles = Tile.load(prefs, KEY_TILES)
        if (tiles == null && repo.isLoaded) {
            tiles = Tile.defaultTiles(this, repo).also { Tile.save(prefs, KEY_TILES, it) }
        }
        dock = Tile.load(prefs, KEY_DOCK)
        if (dock == null && repo.isLoaded) {
            dock = Tile.defaultDock(this, repo).also { Tile.save(prefs, KEY_DOCK, it) }
        }
    }

    /** Rebuilds the tile area: the reveal hub in reveal layouts, otherwise the tile grid. */
    fun refreshTiles() {
        val box = tilesBox ?: return
        val list = tiles ?: return
        box.removeAllViews()
        val tileCount = prefs.tileCount()
        ensureSize(list, tileCount)
        if (prefs.revealMode()) {
            buildRevealHub(box, list, tileCount)
        } else {
            buildTileGrid(box, tileCount)
        }
    }

    /** Builds the reveal hub (cascade / deploy / hub-and-spoke) for the current hub page. */
    private fun buildRevealHub(box: LinearLayout, list: List<Tile>, tileCount: Int) {
        val openDrawerOnLong = View.OnLongClickListener { v ->
            Ui.longPress(v)
            openDrawer(null, false)
            true
        }
        val host: RevealHost = when {
            Skin.collective() -> CollectiveHub(this, pal, openDrawerOnLong)
            Skin.imperial() -> ImperialDeploy(this, pal, openDrawerOnLong)
            else -> CommandCascade(this, pal, openDrawerOnLong)
        }
        val hasInfo = hubPanelCount + hubStations.size > 0
        if (hubPage == HomeLogic.HUB_PAGE_STATIONS && hubStations.isNotEmpty()) {
            for (i in hubStations.indices) addStationNode(host, i)
            addReturnNode(host, HomeLogic.HUB_PAGE_INFO)
            host.setPinned(true)
            host.setCount(hubStations.size)
            host.setMasterTitle(Stations.pageTitle())
            host.setMasterAction { switchHubPage(HomeLogic.HUB_PAGE_INFO) }
        } else if (hubPage >= HomeLogic.HUB_PAGE_INFO && hasInfo) {
            hubPage = HomeLogic.HUB_PAGE_INFO
            for (i in 0 until hubPanelCount) addInfoNode(host, i)
            if (hubStations.isNotEmpty()) addStationsEntry(host, hubPanelCount)
            addReturnNode(host, HomeLogic.HUB_PAGE_TILES)
            host.setPinned(true)
            host.setCount(hubPanelCount)
            host.setMasterTitle(if (Skin.imperial()) "INTEL" else if (Skin.collective()) "INFORMATION NODES" else "INFORMATION")
            host.setMasterAction { switchHubPage(HomeLogic.HUB_PAGE_TILES) }
        } else {
            hubPage = HomeLogic.HUB_PAGE_TILES
            for (i in 0 until tileCount) addHubNode(host, list, i)
            if (hasInfo) addInfoEntry(host, tileCount)
            host.setCount(tileCount)
        }
        box.addView(host as View, matchParent())
    }

    /** Builds the scrolling tile grid; wider windows get more columns (see [HomeLogic.tileColumns]). */
    private fun buildTileGrid(box: LinearLayout, tileCount: Int) {
        var gridWidthDp = metrics.widthDp
        if (isSideBySide()) gridWidthDp *= MAIN_PANE_WEIGHT / (HEADER_PANE_WEIGHT + MAIN_PANE_WEIGHT)
        val columns = HomeLogic.tileColumns(baseTileColumns(), gridWidthDp)
        val gap = Ui.dp(this, 6f)
        val animate = Ui.animate(this)
        var row: LinearLayout? = null
        for (i in 0 until tileCount) {
            if (i % columns == 0) {
                val newRow = LinearLayout(this)
                row = newRow
                val rowParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, tileRowHeightDp().toFloat()))
                if (i > 0) rowParams.topMargin = gap
                if (Skin.collective()) {
                    // Collective trace column: every other row is indented to zig-zag along the trace.
                    rowParams.leftMargin = if ((i / columns) % 2 == 1) Ui.dp(this, 24f) else 0
                }
                box.addView(newRow, rowParams)
            }
            val tile = tileView(i)
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
            if (i % columns != 0) params.leftMargin = gap
            row?.addView(tile, params)
            if (animate) {
                tile.alpha = 0f
                tile.translationY = Ui.dp(this, 10f).toFloat()
                tile.animate().alpha(1f).translationY(0f).setStartDelay(60 + i * 35L).setDuration(260L)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
        // Pad a partial last row so its tiles keep the same width as the rows above.
        val remainder = tileCount % columns
        if (row != null && remainder != 0) {
            for (i in remainder until columns) {
                val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
                params.leftMargin = gap
                row.addView(View(this), params)
            }
        }
    }

    /** One shortcut tile for the grid layouts. */
    private fun tileView(position: Int): View {
        val tile = tiles!![position]
        val color = tileColor(tile, position)
        val frame = FrameLayout(this)
        val number = Ui.bold(this, HomeLogic.tileNumber(position), 11f, Ui.alpha(Ui.ink(), 0.7f))
        if (tile.isEmpty()) {
            frame.background = Ui.touch(
                if (Skin.command()) {
                    Ui.outline(this, Ui.alpha(pal.panel, 0.8f), Ui.alpha(color, 0.6f), 18f, 1.5f)
                } else {
                    Ui.shape(this, Ui.alpha(color, 0.22f), Ui.SQUARE, 18f)
                },
            )
            frame.addView(
                Ui.bold(this, "+ ASSIGN", 15f, Ui.alpha(color, 0.8f)),
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
            number.setTextColor(Ui.alpha(color, 0.6f))
            frame.contentDescription = getString(R.string.cd_assign_tile, position + 1)
        } else {
            frame.background = Ui.touch(Ui.shape(this, color, Ui.SQUARE, 18f))
            val app = if (tile.comp != null) repo.find(tile.comp, tile.user) else null
            val ink = when {
                Skin.command() || Ui.cloak -> Ui.ink()
                Skin.collective() -> COLLECTIVE_INK
                else -> Skin.glow(color)
            }
            val factionArt = if (prefs.iconStyle() == ICON_STYLE_FACTION_ART) IconFactory.factionArt(this, tileGlyph(tile, app)) else null
            val icon = tileIcon(tile, app, ink, factionArt)
            val label = tileLabel(tile, app)
            val caption = Ui.oneLine(Ui.bold(this, label.uppercase(Locale.getDefault()), 16f, Ui.ink()))
            if (Skin.command()) {
                val iconSize = Ui.dp(this, 30f)
                val iconParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.TOP or Gravity.START)
                iconParams.setMargins(Ui.dp(this, 8f), Ui.dp(this, 8f), 0, 0)
                frame.addView(icon, iconParams)
                caption.gravity = Gravity.END
                Ui.fitText(caption, false) // long labels (NAVIGATION) shrink instead of truncating on mdpi landscape
                val captionParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
                )
                captionParams.setMargins(Ui.dp(this, 8f), 0, Ui.dp(this, 12f), Ui.dp(this, 8f))
                frame.addView(caption, captionParams)
            } else {
                val badgeSize = Ui.dp(this, 40f)
                val iconSize = if (factionArt != null) badgeSize else Ui.dp(this, 24f)
                val indent = Ui.dp(this, if (Skin.imperial()) 24f else 12f)
                val badge = FrameLayout(this)
                val iconFrame = Skin.iconFrame(this, color)
                if (iconFrame != null && factionArt == null &&
                    (app == null || app.glyph != null || prefs.iconStyle() != ICON_STYLE_NATIVE)
                ) {
                    badge.background = iconFrame
                }
                badge.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
                val badgeParams = FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.CENTER_VERTICAL or Gravity.START)
                badgeParams.leftMargin = indent
                frame.addView(badge, badgeParams)
                caption.gravity = Gravity.CENTER_VERTICAL or Gravity.START
                val captionParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START,
                )
                captionParams.setMargins(indent + badgeSize + Ui.dp(this, 8f), Ui.dp(this, 4f), Ui.dp(this, 12f), 0)
                frame.addView(caption, captionParams)
            }
            frame.contentDescription = label
        }
        val numberParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
        )
        numberParams.setMargins(0, Ui.dp(this, 8f), Ui.dp(this, 12f), 0)
        frame.addView(number, numberParams)
        wireTile(frame, tile, position)
        NotificationBadges.attach(
            frame, tile, if (Skin.command()) NotificationBadges.BOTTOM_START else NotificationBadges.TOP_END, 0,
        ) // LCA-T23
        return frame
    }

    /** Tap launches (or edits an empty tile), long-press edits. */
    fun wireTile(view: View, tile: Tile, position: Int) {
        view.setOnClickListener { v ->
            if (tile.isEmpty()) {
                Ui.tap(v)
                tiles?.let { editTile(it, KEY_TILES, position, true) }
            } else {
                launchTile(tile, v)
            }
        }
        view.setOnLongClickListener { v ->
            Ui.longPress(v)
            tiles?.let { editTile(it, KEY_TILES, position, true) }
            true
        }
    }

    /** A tile's colour: its custom accent or the position's accent, saturated when VIVID TILES is on. */
    fun tileColor(tile: Tile, position: Int): Int {
        val color = if (tile.color >= 0) pal.c(tile.color) else pal.c(HomeLogic.accentIndex(position))
        return if (prefs.vividTiles()) Ui.vivid(color) else color
    }

    /** Ink colour for pictograms on hub nodes of the given colour. */
    private fun hubInk(color: Int): Int {
        if (Skin.collective()) return if (Ui.cloak) Skin.glow(color) else COLLECTIVE_INK
        return if (Skin.command()) Ui.ink() else Skin.glow(color)
    }

    private fun glyphView(glyph: String, color: Int): ImageView {
        val view = ImageView(this)
        view.setImageBitmap(IconFactory.glyph(this, glyph))
        view.setColorFilter(hubInk(color), PorterDuff.Mode.SRC_IN)
        return view
    }

    private fun hubColor(position: Int): Int = pal.c(HomeLogic.accentIndex(position))

    /** The INFO node on the tiles page, which switches to the information page. */
    private fun addInfoEntry(host: RevealHost, position: Int) {
        val color = hubColor(position)
        host.addNode(glyphView("radio", color), "INFO", color, false).setOnClickListener { v ->
            Ui.tap(v)
            switchHubPage(HomeLogic.HUB_PAGE_INFO)
        }
    }

    /** One information-panel node; tap shows the panel card, long-press its menu. */
    private fun addInfoNode(host: RevealHost, index: Int) {
        val color = hubPanelColors[index]
        val name = hubPanelNames[index]
        val node = host.addNode(glyphView(HomeLogic.panelGlyph(name), color), name, color, false)
        node.setOnClickListener { v ->
            Ui.tap(v)
            showHubPanel(index)
        }
        node.setOnLongClickListener {
            panelMenu(hubPanelObjs[index])
            true
        }
    }

    /** RETURN node leading back to `page`. */
    private fun addReturnNode(host: RevealHost, page: Int) {
        val color = hubColor(if (page == HomeLogic.HUB_PAGE_TILES) hubPanelCount + 1 else hubStations.size)
        host.addNode(glyphView("apps", color), "RETURN", color, false).setOnClickListener { v ->
            Ui.tap(v)
            switchHubPage(page)
        }
    }

    /** The stations entry on the information page. */
    private fun addStationsEntry(host: RevealHost, position: Int) {
        val color = hubColor(position + 2)
        host.addNode(glyphView("computer", color), Stations.entryName(), color, false).setOnClickListener { v ->
            Ui.tap(v)
            switchHubPage(HomeLogic.HUB_PAGE_STATIONS)
        }
    }

    private fun addStationNode(host: RevealHost, index: Int) {
        val station = hubStations[index]
        host.addNode(glyphView(station.glyph, station.color), station.name, station.color, false).setOnClickListener { v ->
            Ui.tap(v)
            showHubPanel(hubPanelCount + index)
        }
    }

    /** The reveal host currently in the tiles box, or null in grid layouts. */
    private fun currentHost(): RevealHost? {
        val box = tilesBox
        if (box == null || box.childCount == 0) return null
        return box.getChildAt(0) as? RevealHost
    }

    /** Folds the hub, swaps to another page and unfolds it again. */
    private fun switchHubPage(page: Int) {
        if (page == hubPage) return
        closeHubPanel()
        val oldHost = currentHost()
        Sfx.play(this, if (page == HomeLogic.HUB_PAGE_INFO) Sfx.OPEN else Sfx.CLOSE)
        val swap = Runnable {
            hubPage = page
            refreshTiles()
            val newHost = currentHost()
            if (newHost != null) {
                newHost.setPinned(true)
                newHost.setOpen(true, true, if (page == HomeLogic.HUB_PAGE_TILES) Runnable { newHost.setPinned(false) } else null)
            }
        }
        if (oldHost != null) {
            oldHost.setPinned(true)
            oldHost.setOpen(false, true, swap)
        } else {
            swap.run()
        }
    }

    /** One tile as a reveal-hub node. */
    private fun addHubNode(host: RevealHost, list: List<Tile>, position: Int) {
        val tile = list[position]
        val color = tileColor(tile, position)
        val content: View
        val label: String
        if (tile.isEmpty()) {
            val plus = Ui.bold(this, "+", 18f, Ui.alpha(Skin.glow(color), 0.8f))
            plus.gravity = Gravity.CENTER
            content = plus
            label = "ASSIGN"
        } else {
            val app = if (tile.comp != null) repo.find(tile.comp, tile.user) else null
            val factionArt = if (prefs.iconStyle() == ICON_STYLE_FACTION_ART) IconFactory.factionArt(this, tileGlyph(tile, app)) else null
            content = tileIcon(tile, app, hubInk(color), factionArt)
            label = tileLabel(tile, app)
        }
        val node = host.addNode(content, label, color, tile.isEmpty())
        wireTile(node, tile, position)
        NotificationBadges.attach(node, tile, NotificationBadges.TOP_END, 0) // LCA-T23
    }

    /**
     * Icon for a tile: faction art, the app's TrekUI pictogram, its native icon, the function's pictogram,
     * or an alert glyph for a missing app.
     */
    fun tileIcon(tile: Tile, app: AppInfo?, ink: Int, factionArt: Bitmap?): ImageView {
        val view = ImageView(this)
        val rawIcon = app?.rawIcon
        val appGlyph = app?.glyph
        if (factionArt != null) {
            view.setImageBitmap(factionArt)
            if (Ui.cloak) view.alpha = 0.6f
        } else if (appGlyph != null && prefs.iconStyle() != ICON_STYLE_NATIVE) {
            view.setImageBitmap(IconFactory.glyph(this, appGlyph))
            view.setColorFilter(ink, PorterDuff.Mode.SRC_IN)
        } else if (rawIcon != null) {
            view.setImageDrawable(IconFactory.copy(rawIcon))
            if (Ui.cloak) view.alpha = 0.6f
        } else {
            view.setImageBitmap(IconFactory.glyph(this, if (tile.action != null) HomeLogic.actionGlyph(tile.action) else "alert"))
            view.setColorFilter(ink, PorterDuff.Mode.SRC_IN)
        }
        return view
    }

    /** Fills the dock icons. */
    fun refreshDock() {
        val icons = dockIcons ?: return
        val list = dock ?: return
        icons.removeAllViews()
        val dockCount = prefs.dockCount()
        ensureSize(list, dockCount)
        val iconStyle = prefs.iconStyle()
        val iconSize = Ui.dp(this, 46f)
        for (i in 0 until dockCount) {
            val position = i
            val tile = list[i]
            val slot = FrameLayout(this)
            val icon = ImageView(this)
            val app = if (tile.comp != null) repo.find(tile.comp, tile.user) else null
            if (app != null) {
                icon.setImageDrawable(IconFactory.style(this, app, pal, iconStyle, prefs.iconTone()))
                slot.contentDescription = app.label
            } else if (tile.action != null) {
                icon.setImageBitmap(IconFactory.glyph(this, HomeLogic.actionGlyph(tile.action)))
                val color = if (prefs.iconTone() != 0) Ui.vivid(pal.c(i)) else pal.c(i)
                icon.setColorFilter(Skin.glow(color), PorterDuff.Mode.SRC_IN)
                slot.contentDescription = Tile.actionLabel(tile.action)
            } else {
                val plus = Ui.bold(this, "+", 26f, Ui.alpha(pal.c(1), 0.6f))
                plus.gravity = Gravity.CENTER
                slot.addView(plus, matchParent())
                slot.contentDescription = "Assign dock slot " + (i + 1)
            }
            slot.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
            slot.background = Ui.touchArea(this, 16f)
            slot.setOnClickListener { v ->
                if (tile.isEmpty()) {
                    Ui.tap(v)
                    dock?.let { editTile(it, KEY_DOCK, position, false) }
                } else {
                    launchTile(tile, v)
                }
            }
            slot.setOnLongClickListener { v ->
                Ui.longPress(v)
                dock?.let { editTile(it, KEY_DOCK, position, false) }
                true
            }
            NotificationBadges.attach(slot, tile, NotificationBadges.ICON, iconSize / 2) // LCA-T23
            icons.addView(slot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f))
        }
    }

    /**
     * The long-press editor for a tile (`isTile`) or dock slot: assign, rename, recolour, sound, clear.
     *
     * @param list the tiles or dock list; `key` its preference key
     */
    fun editTile(list: MutableList<Tile>, key: String, position: Int, isTile: Boolean) {
        val tile = list[position]
        val name = (if (isTile) "TILE " else "DOCK ") + HomeLogic.tileNumber(position)
        val menu = Menu()
        menu.add("ASSIGN APP") {
            openDrawer(appPick("ASSIGN $name") { app ->
                assignApp(tile, app)
                if (isTile) tile.label = HomeLogic.shortLabel(app.label)
                saveTiles(list, key)
                Sfx.play(this, Sfx.CONFIRM)
            }, false)
        }
        menu.add("ASSIGN TREKUI FUNCTION") {
            pickFunction(name) { action, arg, label ->
                tile.comp = null
                tile.action = action
                tile.arg = arg
                tile.label = label
                saveTiles(list, key)
                Sfx.play(this, Sfx.CONFIRM)
            }
        }
        if (!tile.isEmpty()) {
            if (isTile) {
                menu.add("RENAME") {
                    showing(ConsoleDialog.prompt(this, "RENAME $name", tile.label, "LABEL") { text ->
                        tile.label = text
                        saveTiles(list, key)
                    })
                }
                menu.add("CHANGE COLOR") {
                    showing(ConsoleDialog.options(this, "COLOR $name", accentChoices("AUTOMATIC")) { choice ->
                        tile.color = if (choice == 0) -1 else choice - 1
                        saveTiles(list, key)
                        Sfx.play(this, Sfx.CONFIRM)
                    })
                }
            }
            menu.add("ASSIGN SOUND") {
                pickSound(name, tile.sound) { clip ->
                    tile.sound = clip
                    saveTiles(list, key)
                }
            }
            menu.add("CLEAR") {
                list[position] = Tile()
                saveTiles(list, key)
                Sfx.play(this, Sfx.CLOSE)
            }
        }
        showing(ConsoleDialog.options(this, name, menu.labels()) { menu.run(it) })
    }

    /** Persists the tiles or dock and refreshes whichever changed. */
    private fun saveTiles(list: List<Tile>, key: String) {
        Tile.save(prefs, key, list)
        if (KEY_DOCK == key) refreshDock() else refreshTiles()
    }

    /** Puts an app on the first empty tile (or dock slot), from the drawer's long-press menu. */
    fun addToHome(app: AppInfo, toDock: Boolean) {
        val list = (if (toDock) dock else tiles) ?: return
        val size = if (toDock) prefs.dockCount() else prefs.tileCount()
        ensureSize(list, size)
        var free = -1
        for (i in 0 until size) {
            if (list[i].isEmpty()) {
                free = i
                break
            }
        }
        if (free < 0) {
            Sfx.play(this, Sfx.DENY)
            toast(getString(if (toDock) R.string.toast_dock_full else R.string.toast_tiles_full))
            return
        }
        val tile = Tile()
        assignApp(tile, app)
        tile.label = if (toDock) "" else HomeLogic.shortLabel(app.label)
        list[free] = tile
        saveTiles(list, if (toDock) KEY_DOCK else KEY_TILES)
        Sfx.play(this, Sfx.CONFIRM)
        toast(if (toDock) getString(R.string.toast_added_to_dock) else getString(R.string.toast_added_to_tile, HomeLogic.tileNumber(free)))
    }

    // =================================================================================================
    // Launching
    // =================================================================================================

    /** Runs a tile, dock slot or slot assignment: an console function or an app. */
    fun launchTile(tile: Tile, view: View) {
        if (tile.sound != null) {
            Sfx.playClip(this, tile.sound, false)
        }
        val action = tile.action
        if (action == null) {
            val app = repo.find(tile.comp, tile.user)
            if (app == null) {
                Sfx.play(this, Sfx.ERROR)
                toast(getString(R.string.toast_app_not_found))
            } else {
                launchApp(app, view)
            }
            return
        }
        if (tile.sound == null && Tile.A_CLOAK != action && Tile.A_RED_ALERT != action) {
            Ui.tap(view)
        } else {
            haptic(view)
        }
        when (action) {
            Tile.A_APPS -> openDrawer(null, false)
            Tile.A_SEARCH -> openDrawer(null, true)
            Tile.A_CONFIG -> openConfig()
            Tile.A_ANDROID -> startSafe(Intent(Settings.ACTION_SETTINGS), view)
            Tile.A_SHADE -> shade?.open(Ui.animate(this))
            Tile.A_PANEL -> expandNotifications()
            Tile.A_CASCADE -> cascade()
            Tile.A_DIALER -> openDialer()
            Tile.A_CLOAK -> toggleCloak()
            Tile.A_RED_ALERT -> RedAlert.toggle(this)
            Tile.A_GROUP -> GroupPanel.open(this, tile.arg) // LCA-T20
            Tile.A_LOCK -> LockScreen.lock(this) // LCA-T20
            Tile.A_GOVEE -> goveeToggle(tile.arg, view)
            Tile.A_HA -> HomeAssistantDialog.launch(this, tile.arg, view)
            Tile.A_NEST -> {
                val thermostat = Nest.find(prefs, tile.arg)
                if (thermostat != null) {
                    ThermostatDialog.show(this, thermostat.name, thermostat.label)
                } else {
                    Sfx.play(this, Sfx.ERROR)
                    toast(getString(R.string.toast_thermostat_not_linked))
                }
            }
            else -> {}
        }
    }

    /** Toggles a Govee light and reports the result with a chirp and a toast. */
    private fun goveeToggle(deviceId: String?, view: View) {
        Govee.power(this, deviceId, null) { ok, message, on ->
            Sfx.play(this, if (ok) (if (on == false) Sfx.CLOSE else Sfx.CONFIRM) else Sfx.ERROR)
            if (ok) haptic(view)
            toast(message)
        }
    }

    /** Engages or disengages cloak (night) mode and rebuilds the UI in its colours. */
    fun toggleCloak() {
        val engaging = !Cloak.active(this)
        Cloak.toggle(this)
        if (engaging) {
            val minutes = Cloak.minutes(prefs)
            toast(getString(if (Cloak.canDimSystem(this)) R.string.toast_cloak_engaged else R.string.toast_cloak_engaged_no_dim, minutes))
        } else {
            toast(getString(R.string.toast_cloak_disengaged))
        }
        buildUi()
    }

    fun launchApp(app: AppInfo, view: View?) {
        launchApp(app, view, -1)
    }

    /** True when launching this app should play the comms chirp (messengers, dialers, SMS). */
    private fun isCommsApp(app: AppInfo): Boolean {
        var smsPkg: String? = null
        try {
            smsPkg = Telephony.Sms.getDefaultSmsPackage(this)
        } catch (noTelephony: RuntimeException) {
            // devices without telephony
        }
        return HomeLogic.isCommsApp(app.pkg, app.label, app.category == AppInfo.CAT_COMM, dialerPackage(), smsPkg)
    }

    /**
     * Launches an app (any profile) with a clip-reveal animation from `view`. The default dialer
     * opens the console dialer instead when enabled; a paused work profile is asked to resume.
     *
     * @param sound Sfx sound id, or -1 for the launch / comms chirp
     */
    fun launchApp(app: AppInfo, view: View?, sound: Int) {
        haptic(view)
        if (app.mainProfile && prefs.b(KEY_CONSOLE_DIALER, true) && app.pkg == dialerPackage()) {
            openDialer()
            return
        }
        val comms = isCommsApp(app)
        Sfx.play(this, if (sound >= 0) sound else if (comms) Sfx.COMM else Sfx.LAUNCH)
        commLaunchAt = if (comms) System.currentTimeMillis() else 0L
        try {
            val users = repo.users()
            if (!app.mainProfile && Build.VERSION.SDK_INT >= 28 && users.isQuietModeEnabled(app.user)) {
                users.requestQuietModeEnabled(false, app.user!!)
                return
            }
            var bounds: Rect? = null
            var options: Bundle? = null
            if (view != null) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                bounds = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                options = ActivityOptions.makeClipRevealAnimation(view, 0, 0, view.width, view.height).toBundle()
            }
            repo.launcherApps().startMainActivity(app.cn, app.user, bounds, options)
        } catch (e: RuntimeException) {
            Sfx.play(this, Sfx.ERROR)
            toast(getString(R.string.toast_unable_to_launch, app.label.uppercase(Locale.getDefault())))
        }
    }

    /**
     * Starts an activity in a new task with a clip-reveal from `view`; plays the error chirp and
     * toasts when nothing handles the intent.
     */
    fun startSafe(intent: Intent, view: View?): Boolean {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val options = view?.let { ActivityOptions.makeClipRevealAnimation(it, 0, 0, it.width, it.height).toBundle() }
            startActivity(intent, options)
            true
        } catch (e: RuntimeException) {
            Sfx.play(this, Sfx.ERROR)
            toast(getString(R.string.toast_no_app))
            false
        }
    }

    /** Package of the default phone app (cached), or "" when there is none. */
    private fun dialerPackage(): String? {
        if (dialerPkg == null) {
            try {
                val telecom = getSystemService(TelecomManager::class.java)
                dialerPkg = telecom?.defaultDialerPackage
            } catch (ignored: RuntimeException) {
                // no telecom service
            }
            if (dialerPkg == null) {
                val info = resolveDefault(packageManager, Intent(Intent.ACTION_DIAL))
                dialerPkg = info?.activityInfo?.packageName ?: ""
            }
        }
        return dialerPkg
    }

    /** Closes other overlays and opens the console dialer. */
    fun openDialer() {
        openDialog?.let { if (it.isShowing) it.dismiss() }
        shade?.let { if (it.isOpen()) it.close(false) }
        drawer?.let { if (it.isOpen()) it.close(false) }
        dialer?.open(Ui.animate(this))
    }

    /**
     * Closes other overlays and opens the app drawer.
     *
     * @param pick   non-null to use the drawer as an app picker
     * @param search true to open with the search field focused
     */
    fun openDrawer(pick: DrawerView.Pick?, search: Boolean) {
        openDialog?.let { if (it.isShowing) it.dismiss() }
        shade?.let { if (it.isOpen()) it.close(false) }
        dialer?.let { if (it.isOpen()) it.close(false) }
        drawer?.open(pick, search, Ui.animate(this))
    }

    /** Opens CONFIG (SettingsActivity); on API 34+ SettingsActivity sets its own fade transition. */
    @Suppress("DEPRECATION")
    fun openConfig() {
        startActivity(Intent(this, SettingsActivity::class.java))
        if (Build.VERSION.SDK_INT < 34 && Ui.animate(this)) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    fun toast(message: String?) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Light haptic tick for a view when haptics are enabled. */
    private fun haptic(view: View?) {
        if (view != null && prefs.haptics()) {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    // =================================================================================================
    // Clock, info line, cascade, battery
    // =================================================================================================

    /** A random code block text, "NN-NNNN". */
    fun code(): String = HomeLogic.codeText(rnd.nextInt(100), rnd.nextInt(10000))

    /** Shuffles one random code block (the last one, block 47's corner, shows two digits). */
    private fun cascadeStep() {
        if (codes.isEmpty()) return
        val view = codes[rnd.nextInt(codes.size)]
        view.text = if (view === codes[codes.size - 1]) (rnd.nextInt(90) + 10).toString() else code()
    }

    /** The data cascade: every code block flickers to a new code in turn. */
    private fun cascade() {
        Sfx.play(this, Sfx.SCANNER)
        for (i in codes.indices) {
            val view = codes[i]
            mainHandler.postDelayed({
                view.text = code()
                view.animate().alpha(0.3f).setDuration(80L)
                    .withEndAction { view.animate().alpha(1f).setDuration(160L).start() }.start()
            }, i * 70L)
        }
    }

    /** 24-hour clock per the CONFIG setting (SYSTEM / 12-HOUR / 24-HOUR). */
    fun use24h(): Boolean = when (prefs.clockMode()) {
        2 -> true
        1 -> false
        else -> DateFormat.is24HourFormat(this)
    }

    /** Shrinks the clock text so it fits its row (long times, seconds, narrow panes). */
    fun fitClock() {
        val clock = clockView ?: return
        val row = clockRowView ?: return
        if (row.width == 0) return
        val fullSize = Ui.px(this, CLOCK_SP)
        val paint = Paint(clock.paint)
        paint.textSize = fullSize
        val textWidth = paint.measureText(clock.text.toString())
        val ampm = ampmView
        val ampmWidth = if (ampm == null || ampm.length() == 0) {
            0f
        } else {
            ampm.paint.measureText(ampm.text.toString()) + ampm.paddingLeft
        }
        val available = row.width - row.paddingLeft - row.paddingRight - ampmWidth - Ui.dp(this, 4f)
        val size = if (textWidth <= available || textWidth <= 0f) fullSize else max(0.55f * fullSize, available * fullSize / textWidth)
        if (abs(clock.textSize - size) > 0.5f) {
            clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }
    }

    /** Updates the clock, AM/PM and date views. */
    private fun updateClock() {
        val clock = clockView ?: return
        val now = Date()
        val use24h = use24h()
        val pattern = (if (use24h) "HH:mm" else "h:mm") + if (prefs.seconds()) ":ss" else ""
        clock.text = SimpleDateFormat(pattern, Locale.getDefault()).format(now)
        ampmView?.text = if (use24h) "" else SimpleDateFormat("a", Locale.getDefault()).format(now).uppercase(Locale.getDefault())
        fitClock()
        dateView?.let { date ->
            val datePattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMdyyyy")
            date.text = SimpleDateFormat(datePattern, Locale.getDefault()).format(now).uppercase(Locale.getDefault())
        }
    }

    /** The info line under the date: cloak time left, stardate, battery and the next alarm within a day. */
    private fun updateInfo() {
        val info = infoView ?: return
        val separator = "  •  "
        val sb = StringBuilder()
        if (Ui.cloak) {
            sb.append("CLOAKED ").append(max(1L, (Cloak.remainingMs(this) + 59_999) / 60_000)).append(" MIN").append(separator)
        }
        if (prefs.stardate()) {
            sb.append("STARDATE ").append(stardate(Calendar.getInstance()))
        }
        val battery = lastBattery
        if (battery != null) {
            val percent = batteryPct(battery)
            if (percent >= 0) {
                if (sb.isNotEmpty()) sb.append(separator)
                sb.append("PWR ").append(percent).append('%')
                if (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0) sb.append(" +")
            }
        }
        val alarms = getSystemService(AlarmManager::class.java)
        val next = alarms?.nextAlarmClock
        if (next != null && next.triggerTime - System.currentTimeMillis() < ALARM_LOOKAHEAD_MS) {
            if (sb.isNotEmpty()) sb.append(separator)
            val pattern = if (use24h()) "HH:mm" else "h:mm a"
            sb.append("ALARM ").append(
                SimpleDateFormat(pattern, Locale.getDefault()).format(Date(next.triggerTime)).uppercase(Locale.getDefault()),
            )
        }
        info.text = sb.toString()
    }

    /**
     * Handles a battery update: plays the plug-in / low / critical sounds (unless `quiet`),
     * refreshes the info line and forwards it to the panels.
     */
    private fun onBattery(intent: Intent, quiet: Boolean) {
        lastBattery = intent
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        when (batteryWatch.update(plugged, batteryPct(intent), !quiet && resumed)) {
            HomeLogic.BatteryAlert.PLUGGED_IN -> Sfx.play(this, Sfx.POWER)
            HomeLogic.BatteryAlert.LOW -> Sfx.play(this, Sfx.WARNING)
            HomeLogic.BatteryAlert.CRITICAL -> Sfx.play(this, Sfx.CRITICAL)
            else -> {}
        }
        updateInfo()
        for (panel in panels) panel.onBattery(intent)
    }

    /** The last BATTERY_CHANGED intent, or null before the first resume. */
    fun battery(): Intent? = lastBattery

    /**
     * Sends a PendingIntent (media session / notification actions), allowing it to start an activity
     * from the home screen where Android 14+ requires that to be explicit.
     */
    @Suppress("DEPRECATION")
    fun sendPending(pendingIntent: PendingIntent?): Boolean {
        if (pendingIntent == null) return false
        return try {
            var options: Bundle? = null
            if (Build.VERSION.SDK_INT >= 34) {
                val basic = ActivityOptions.makeBasic()
                basic.setPendingIntentBackgroundActivityStartMode(
                    if (Build.VERSION.SDK_INT >= 36) {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    } else {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    },
                )
                options = basic.toBundle()
            }
            pendingIntent.send(this, 0, null, null, null, null, options)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    /**
     * Pulls down the system notification shade. There is no public API for this; the hidden
     * StatusBarManager method still works for apps holding EXPAND_STATUS_BAR, and failure is harmless.
     */
    @SuppressLint("WrongConstant") // "statusbar" is the hidden service name, on purpose
    fun expandNotifications() {
        try {
            val statusBar = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(statusBar)
        } catch (ignored: Throwable) {
            // blocked on this device / Android version
        }
    }

    // =================================================================================================
    // Default home
    // =================================================================================================

    /** First-run dialog offering to make console the default home. */
    private fun welcome() {
        Sfx.play(this, Sfx.ALERT)
        showing(
            ConsoleDialog.message(
                this, getString(R.string.welcome_title), getString(R.string.welcome_message),
                arrayOf(getString(R.string.welcome_set_default), getString(R.string.welcome_later)),
            ) { which ->
                if (which == 0) requestDefaultHome(this)
            },
        )
    }

    fun isResumedNow(): Boolean = resumed

    fun handler(): Handler = mainHandler

    // =================================================================================================
    // Gestures
    // =================================================================================================

    /**
     * Root view of the home window. Watches every touch (without consuming it) for vertical flings:
     * up from the bottom of home opens the drawer, down from the top opens the console shade (or the
     * system shade); with the console shade open, up from its bottom closes it and down from its top
     * opens the system shade. Cancelled gestures (ACTION_CANCEL) are dropped.
     */
    inner class GestureRoot(context: Context) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var velocity: VelocityTracker? = null
        private var downX = 0f
        private var downY = 0f
        private var eligible = false
        private var shadeMode = false
        private var atTop = false
        private var atBottom = false

        /** LCA-T20: the assignable gestures (double-tap, two-finger swipes, pinch, swipe left/right). */
        private val gestures = HomeGestureDetector(
            this@MainActivity,
            object : HomeGestureDetector.Host {
                override fun homeIdle(): Boolean =
                    drawer?.isOpen() == false && dialer?.isOpen() != true && shade?.isOpen() != true

                override fun homeView(): View = home!!

                override fun rootView(): ViewGroup = this@GestureRoot
            },
        )

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val taken = gestures.onEvent(event) // LCA-T20
            if (taken != HomeGestureDetector.Result.PASS) {
                recycleTracker()
                if (taken == HomeGestureDetector.Result.CANCEL_CHILDREN) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> onDown(event)
                MotionEvent.ACTION_MOVE -> velocity?.addMovement(event)
                MotionEvent.ACTION_UP -> {
                    val tracker = velocity
                    if (tracker != null && eligible) {
                        tracker.addMovement(event)
                        tracker.computeCurrentVelocity(1000)
                        onFling(event.rawX - downX, event.rawY - downY, tracker.yVelocity)
                    }
                    recycleTracker()
                }
                MotionEvent.ACTION_CANCEL -> recycleTracker()
                else -> {}
            }
            return super.dispatchTouchEvent(event)
        }

        private fun onDown(event: MotionEvent) {
            downX = event.rawX
            downY = event.rawY
            eligible = drawer?.isOpen() == false && dialer?.isOpen() != true
            val openShade = shade?.takeIf { it.isOpen() }
            shadeMode = openShade != null
            if (openShade != null) {
                atTop = openShade.atTop()
                atBottom = openShade.atBottom()
            } else {
                atTop = homeScroll?.canScrollVertically(-1) != true
                atBottom = homeScroll?.canScrollVertically(1) != true ||
                    downY > height - insetBottom - Ui.dp(context, BOTTOM_ZONE_DP)
            }
            recycleTracker()
            velocity = VelocityTracker.obtain().also { it.addMovement(event) }
        }

        private fun onFling(dx: Float, dy: Float, vy: Float) {
            val travel = abs(dy)
            if (travel <= max(Ui.dp(context, MIN_TRAVEL_DP).toFloat(), abs(dx) * VERTICAL_DOMINANCE) || travel <= touchSlop) {
                return
            }
            val flingUp = dy < 0 && vy < -MIN_VELOCITY && atBottom
            val flingDown = dy > 0 && vy > MIN_VELOCITY && atTop
            if (shadeMode) {
                if (flingUp) shade?.close(true) else if (flingDown) expandNotifications()
            } else if (flingUp) {
                openDrawer(null, false)
            } else if (flingDown) {
                if (prefs.b(KEY_CONSOLE_SHADE, true)) shade?.open(Ui.animate(this@MainActivity)) else expandNotifications()
            }
        }

        private fun recycleTracker() {
            velocity?.recycle()
            velocity = null
        }
    }

    companion object {
        // ---- request codes (shared with panels and the dialer) -----------------------------------------
        const val REQ_LOCATION = 11
        const val REQ_CALENDAR = 12
        const val REQ_HOME_ROLE = 13
        const val REQ_CALL = 14
        const val REQ_CONTACTS = 15
        const val REQ_CALLLOG = 16

        // ---- preference keys -----------------------------------------------------------------------------
        const val KEY_TILES = "tilesJson"
        const val KEY_DOCK = "dockJson"
        private const val KEY_BOOT_SEQUENCE = "bootSeq"
        private const val KEY_BOOT_SEEN = "bootSeen"
        private const val KEY_BOOT_PREVIEW = "bootPreview"
        private const val KEY_WELCOMED = "welcomed"
        private const val KEY_CONSOLE_DIALER = "trekuiDialer"
        private const val KEY_CONSOLE_SHADE = "shadeConsole"
        private const val KEY_WALLPAPER_DIM = "dim"
        private const val KEY_STATION_SOUND = "stnSnd_"
        private const val KEY_PERMISSION_ASKED = "permAsked_"

        // ---- timing --------------------------------------------------------------------------------------
        /** After a reboot without a boot counter, the startup sequence only plays this soon after boot. */
        private const val BOOT_WINDOW_MS = 5 * 60_000L

        /** Returning from a comms app within this long plays the "channel closed" chirp. */
        private const val COMMS_RETURN_WINDOW_MS = 60 * 60_000L
        private const val WELCOME_DELAY_MS = 900L
        private const val ALARM_LOOKAHEAD_MS = 24 * 60 * 60_000L

        /** Background mode values from [Prefs.bgMode]. */
        private const val BG_PATTERN = 1
        private const val BG_WALLPAPER = 2
        private const val DEFAULT_WALLPAPER_DIM = 55

        /** Icon styles from [Prefs.iconStyle]. */
        private const val ICON_STYLE_NATIVE = 2
        private const val ICON_STYLE_FACTION_ART = 3

        /** Collective tiles draw their ink in the dark matrix green, except while cloaked. */
        private const val COLLECTIVE_INK = 0xFF06100C.toInt()

        /** Header clock size in sp before [fitClock] shrinks it to fit. */
        private const val CLOCK_SP = 70f

        /** Minimum width (dp) for the side-by-side header/main layout. */
        private const val TWO_PANE_MIN_WIDTH_DP = 600f

        /** Header and main frame weights in the side-by-side layout. */
        private const val HEADER_PANE_WEIGHT = 2f
        private const val MAIN_PANE_WEIGHT = 3f

        /** Widest the reveal-hub panel card gets on tablets. */
        private const val HUB_PANEL_MAX_WIDTH_DP = 560f

        private const val NAV_BUTTON_DP = 62f
        private const val NAV_BUTTON_MIN_DP = 32f
        private const val NAV_BUTTONS = 3

        // ---- GestureRoot ---------------------------------------------------------------------------------
        /** Minimum vertical travel for a fling, in dp, and how much more vertical than horizontal it must be. */
        private const val MIN_TRAVEL_DP = 70f
        private const val VERTICAL_DOMINANCE = 1.5f

        /** Minimum fling speed in px/s (unchanged from the original release). */
        private const val MIN_VELOCITY = 900f

        /** A swipe up starting this close to the bottom (dp) counts as "at the bottom" even mid-scroll. */
        private const val BOTTOM_ZONE_DP = 90f

        var startupPlayed = false

        /**
         * Makes an activity window edge-to-edge on every supported API level: content draws behind the
         * transparent status and navigation bars and into display cutouts; callers apply the insets as
         * padding. On API 35+ the system enforces edge-to-edge and ignores bar colours, so those calls are
         * only made below 35.
         */
        @JvmStatic
        @Suppress("DEPRECATION")
        fun setupEdgeToEdge(activity: Activity) {
            val window = activity.window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT < 35) {
                window.statusBarColor = Color.TRANSPARENT
                window.navigationBarColor = Color.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val attributes = window.attributes
                attributes.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                window.attributes = attributes
            }
            if (Build.VERSION.SDK_INT >= 29) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }

        /** Gives a view a stable resource id and tag for UI tests (see TEST HOOKS in the class doc). */
        private fun markForTests(view: View, id: Int, tag: String) {
            if (view.id == View.NO_ID) view.id = id
            view.tag = tag
        }

        /** "DEFAULT"/"AUTOMATIC" followed by ACCENT 1..6, for the colour pickers. */
        private fun accentChoices(first: String): Array<String> =
            arrayOf(first, "ACCENT 1", "ACCENT 2", "ACCENT 3", "ACCENT 4", "ACCENT 5", "ACCENT 6")

        /** Points a tile at an app (main profile apps are stored without a user serial). */
        private fun assignApp(tile: Tile, app: AppInfo) {
            tile.comp = app.cn.flattenToShortString()
            tile.user = if (app.mainProfile) -1L else app.serial
            tile.action = null
        }

        private fun appPick(title: String, onPicked: (AppInfo) -> Unit): DrawerView.Pick = object : DrawerView.Pick {
            override fun title(): String = title

            override fun picked(app: AppInfo) {
                onPicked(app)
            }
        }

        private fun ensureSize(list: MutableList<Tile>, size: Int) {
            while (list.size < size) list.add(Tile())
        }

        /** Tile columns on a 360dp phone for the current interface. */
        private fun baseTileColumns(): Int {
            if (Skin.command()) return 3
            return if (Skin.imperial()) 2 else 1
        }

        /** Tile row height in dp for the current interface. */
        private fun tileRowHeightDp(): Int {
            if (Skin.command()) return 78
            return if (Skin.imperial()) 62 else 50
        }

        /** Glyph used for faction art: the app's pictogram, else the function's, else null. */
        private fun tileGlyph(tile: Tile, app: AppInfo?): String? {
            app?.glyph?.let { return it }
            val action = tile.action
            return if (action != null) HomeLogic.actionGlyph(action) else null
        }

        /** Caption of an assigned tile: custom label, app label, or function name. */
        private fun tileLabel(tile: Tile, app: AppInfo?): String {
            val label = tile.label
            if (!label.isNullOrEmpty()) return label
            return app?.label ?: Tile.actionLabel(tile.action)
        }

        /** Stardate for a calendar time (see [HomeLogic.stardate]). */
        @JvmStatic
        fun stardate(calendar: Calendar): String = HomeLogic.stardate(calendar)

        /** Battery percentage from a BATTERY_CHANGED intent, or -1. */
        @JvmStatic
        fun batteryPct(intent: Intent): Int = HomeLogic.batteryPercent(
            intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
        )

        /** [PackageManager.resolveActivity] with MATCH_DEFAULT_ONLY, using the non-deprecated API on 33+. */
        @Suppress("DEPRECATION")
        private fun resolveDefault(pm: PackageManager, intent: Intent): ResolveInfo? {
            if (Build.VERSION.SDK_INT >= 33) {
                return pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            }
            return pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }

        /** True when this launcher is the default home app (the HOME role on API 29+). */
        @JvmStatic
        fun isDefaultHome(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= 29) {
                val roles = context.getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    return roles.isRoleHeld(RoleManager.ROLE_HOME)
                }
            }
            val info = resolveDefault(context.packageManager, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            val activityInfo = info?.activityInfo
            return activityInfo != null && context.packageName == activityInfo.packageName
        }

        /**
         * Asks to become the default home: the HOME role dialog on API 29+ (result [REQ_HOME_ROLE]),
         * otherwise Android's home-app settings, otherwise Settings.
         */
        @JvmStatic
        @Suppress("DEPRECATION")
        fun requestDefaultHome(activity: Activity) {
            if (Build.VERSION.SDK_INT >= 29) {
                val roles = activity.getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    try {
                        activity.startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), REQ_HOME_ROLE)
                        return
                    } catch (ignored: RuntimeException) {
                        // fall through to the settings screens
                    }
                }
            }
            try {
                activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e: RuntimeException) {
                try {
                    activity.startActivity(Intent(Settings.ACTION_SETTINGS))
                } catch (ignored: RuntimeException) {
                    // nothing to open
                }
            }
        }

        private fun matchParent(): FrameLayout.LayoutParams =
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        private fun matchWidth(): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
