package com.gungreeneyes.trekui

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.StatusBarManager
import android.app.WallpaperManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowInsetsCompat
import com.gungreeneyes.trekui.core.SuiteContract
import com.gungreeneyes.trekui.core.SuiteProvider
import com.gungreeneyes.trekui.licensing.Licensing
import java.io.File
import java.util.Locale

/**
 * CONFIG: the launcher's settings screen, opened from the home sidebar or any slot's long-press menu.
 *
 * One long scrolling list inside an console frame, with a sidebar of section shortcuts. Every change is
 * written straight to [Prefs]; [changed] bumps the preference version (so the home screen
 * rebuilds when it resumes) and rebuilds this list in place, keeping the scroll position.
 *
 * Sections, in order: default home (with UNLOCK console while unlicensed), interface (family, interface, colour theme, layout, background,
 * wallpapers), suite (companion apps), notification shade, app theming, smart devices (Govee, Nest), audio (effects, phone sounds,
 * library), chronometer, home layout, app drawer, weather, interface &amp; access (animations, cloak,
 * haptics, permissions) and about.
 *
 * Background work (sound import, gallery export, wallpaper, Govee/Nest network calls) runs on worker
 * threads and reports back through [runOnUiIfAlive], so a result that arrives after the
 * user left CONFIG is dropped instead of crashing on a dead window.
 *
 * Edge-to-edge like home; back uses the system (predictive) back animation and plays the close chirp
 * from [finish].
 */
class SettingsActivity : Activity() {

    private val anchors = ArrayList<Anchor>()
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private lateinit var list: LinearLayout
    private lateinit var pal: Palette
    private lateinit var prefs: Prefs
    private var previewPlayer: MediaPlayer? = null
    private lateinit var root: FrameLayout
    private var scroll: ScrollView? = null
    private lateinit var metrics: ScreenMetrics

    /** Set before finishing for a reason other than "back" (no close chirp). */
    private var quietFinish = false

    /** Bumped by every build; a pending section step from an older build stops itself. */
    private var buildGen = 0

    /** A sidebar shortcut: label and the section header it scrolls to. */
    private class Anchor(val label: String, val view: View)

    // =================================================================================================
    // Lifecycle
    // =================================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        MainActivity.setupEdgeToEdge(this)
        if (Build.VERSION.SDK_INT >= 34 && Ui.animate(this)) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)
        }
        Sfx.init(this)
        SoundUpdateCheck.maybeStart(this, true) // TUK-T21: opening the settings checks the sound update channel
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    /** Rotation, resize, fold/unfold: rebuild for the new window, keeping the scroll position. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuild()
    }

    /**
     * Back (classic or predictive) and every other way of leaving end here: play the close chirp and,
     * before API 34, the fade transition (API 34+ set it once in [onCreate]).
     */
    @Suppress("DEPRECATION")
    override fun finish() {
        if (!quietFinish) Sfx.play(this, Sfx.CLOSE)
        super.finish()
        if (Build.VERSION.SDK_INT < 34 && Ui.animate(this)) {
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    override fun onDestroy() {
        releasePreview()
        SoundDownloader.setListener(null) // a running sound download must not hold this activity
        super.onDestroy()
    }

    /** Runs [action] on the UI thread unless the activity is finishing or gone. */
    private fun runOnUiIfAlive(action: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) action()
        }
    }

    private fun toast(message: String, longer: Boolean) {
        Toast.makeText(this, message, if (longer) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    // =================================================================================================
    // Building the screen
    // =================================================================================================

    /** Rebuilds the list keeping the current scroll position. */
    private fun rebuild() {
        build(scroll?.scrollY ?: 0)
    }

    /** Preference changed: tell the home screen to rebuild on resume, and rebuild this list. */
    private fun changed() {
        prefs.bump()
        rebuild()
    }

    /** Builds the frame, the section list and the sidebar of section shortcuts. */
    private fun build(scrollY: Int) {
        pal = prefs.palette()
        metrics = ScreenMetrics.of(this)
        anchors.clear()
        root = FrameLayout(this).apply {
            id = R.id.settings_root
            tag = "settings_root"
            setBackgroundColor(Skin.bg())
        }

        val frame = ConsoleFrame(this).apply {
            sidebarW = Ui.dp(this@SettingsActivity, 80f)
            colorTop = pal.c(3)
            colorBot = pal.c(1)
            topBlocks = intArrayOf(pal.c(0))
            botBlocks = intArrayOf(pal.c(2), pal.c(4))
        }
        val title = Ui.bold(this, "CONFIGURATION", 30f, pal.c(0))
        title.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 2f), 0)
        frame.setTitle(title)
        val footer = Ui.text(this, "TREKUI LAUNCHER " + version(), 14f, pal.c(1))
        footer.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 2f), 0)
        frame.setFooter(footer)

        val scroll = ScrollView(this)
        this.scroll = scroll
        scroll.isVerticalScrollBarEnabled = false
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            id = R.id.settings_list
            tag = "settings_list"
            setPadding(0, 0, 0, Ui.dp(this@SettingsActivity, 24f))
        }
        scroll.addView(list)
        frame.setContent(scroll)

        // The first screenful is built now; the rest follows one section per main-thread message, so the
        // screen appears at once and input keeps flowing (building everything up front froze slow phones).
        val gen = ++buildGen
        val sections = sections()
        // A rebuild that restores a scroll position stays synchronous, so the list never jumps.
        val eager = if (scrollY > 0) sections.size else minOf(EAGER_SECTIONS, sections.size)
        for (i in 0 until eager) sections[i]()
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { view, raw ->
            val bars = WindowInsetsCompat.toWindowInsetsCompat(raw, view)
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insetTop = bars.top
            insetBottom = bars.bottom
            insetLeft = bars.left
            insetRight = bars.right
            val side = if (metrics.isTwoPane) Ui.rawDp(this, metrics.marginDp) else Ui.dp(this, 8f)
            root.setPadding(insetLeft + side, insetTop + Ui.dp(this, 4f), insetRight + side, insetBottom + Ui.dp(this, 6f))
            raw
        }
        setContentView(root)
        appendSections(gen, frame, sections, eager, scrollY)
    }

    /** Adds the sections from index `from` on, one per message, then the sidebar and the saved scroll position. */
    private fun appendSections(gen: Int, frame: ConsoleFrame, sections: Array<() -> Unit>, from: Int, scrollY: Int) {
        if (gen != buildGen || isFinishing || isDestroyed) return
        if (from >= sections.size) {
            frame.setSidebar(buildSidebar())
            scroll?.let { s -> s.post { s.scrollTo(0, scrollY) } }
            return
        }
        list.post {
            if (gen != buildGen || isFinishing || isDestroyed) return@post
            sections[from]()
            appendSections(gen, frame, sections, from + 1, scrollY)
        }
    }

    /** The sidebar: one button per anchored section, scrolling the list to it. */
    private fun buildSidebar(): View {
        val sideScroll = ScrollView(this)
        sideScroll.isVerticalScrollBarEnabled = false
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        anchors.forEachIndexed { i, anchor ->
            val color = pal.c(HomeLogic.accentIndex(i))
            val button = Ui.button(this, anchor.label, color, Ui.SQUARE) { scroll?.smoothScrollTo(0, anchor.view.top) }
            button.background = Ui.touch(Ui.shape(this, color, Ui.SQUARE, 0f))
            Ui.setTextSp(button, 14f)
            button.setPadding(Ui.dp(this, 4f), 0, Ui.dp(this, 8f), Ui.dp(this, 4f))
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50f))
            if (i > 0) params.topMargin = Ui.dp(this, 6f)
            column.addView(button, params)
        }
        sideScroll.addView(column)
        return sideScroll
    }

    /** All sections in display order. */
    private fun sections(): Array<() -> Unit> = arrayOf(
        ::homeSection, ::interfaceSection, ::suiteSection, ::shadeSection,
        ::appSection, ::devicesSection, ::audioSection, ::clockSection,
        ::layoutSection, ::groupsSection, ::gesturesSection, ::backupSection,
        ::drawerSection, ::weatherSection, ::accessSection, ::aboutSection,
    )

    private fun version(): String? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            legacyPackageInfo().versionName
        }
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

    @Suppress("DEPRECATION")
    private fun legacyPackageInfo(): PackageInfo = packageManager.getPackageInfo(packageName, 0)

    // =================================================================================================
    // Row builders
    // =================================================================================================

    /**
     * A section header (pill, title, rule).
     *
     * @param sidebarLabel label of the sidebar shortcut to this section, or null for none
     */
    private fun section(title: String, color: Int, sidebarLabel: String?) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, if (list.childCount == 0) 2f else 26f), 0, Ui.dp(this, 8f))
        val pill = View(this)
        pill.background = Ui.shape(this, color, Ui.LEFT, 12f)
        row.addView(pill, LinearLayout.LayoutParams(Ui.dp(this, 22f), Ui.dp(this, 24f)))
        val label = Ui.bold(this, title, 22f, color)
        label.setPadding(Ui.dp(this, 8f), 0, Ui.dp(this, 8f), 0)
        row.addView(label)
        val rule = View(this)
        rule.background = Skin.rule(this, color)
        row.addView(rule, LinearLayout.LayoutParams(0, Ui.dp(this, 6f), 1.0f))
        list.addView(row)
        if (sidebarLabel != null) anchors.add(Anchor(sidebarLabel, row))
    }

    /** Explanatory small text. */
    private fun note(text: String?): TextView {
        val view = Ui.text(this, text, 13f, pal.textLo)
        view.setLineSpacing(0f, 1.1f)
        view.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 8f))
        list.addView(view)
        return view
    }

    /** A sub-heading. */
    private fun label(text: String): TextView {
        val view = Ui.bold(this, text, 16f, pal.textHi)
        view.setPadding(0, Ui.dp(this, 12f), 0, Ui.dp(this, 6f))
        list.addView(view)
        return view
    }

    /** A labelled group of option buttons laid out as segmented console bars; [selected] is highlighted. */
    private fun choice(title: String, options: Array<String>, selected: Int, onChoice: (Int) -> Unit) {
        label(title)
        val columns = SettingsLogic.choiceColumns(options.size, metrics.isTwoPane)
        var row: LinearLayout? = null
        for (i in options.indices) {
            if (i % columns == 0) {
                val newRow = LinearLayout(this)
                val rowParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48f))
                if (i > 0) rowParams.topMargin = Ui.dp(this, 6f)
                list.addView(newRow, rowParams)
                row = newRow
            }
            val color = if (i == selected) pal.c(0) else Ui.darken(pal.c(1), 0.4f)
            val button = Ui.button(this, options[i], color, SettingsLogic.choiceCorner(i, columns, options.size)) { onChoice(i) }
            Ui.setTextSp(button, 15f)
            button.gravity = Gravity.CENTER
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f)
            if (i % columns != 0) params.leftMargin = Ui.dp(this, 4f)
            row!!.addView(button, params)
        }
        // Keep a partial last row's buttons the same width as full rows.
        val remainder = options.size % columns
        if (remainder != 0 && row != null) {
            for (i in remainder until columns) {
                row.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f))
            }
        }
    }

    /** A setting with an ONLINE / OFFLINE switch. */
    private fun toggle(title: String, description: String?, on: Boolean, onToggle: (Boolean) -> Unit) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
        val text = LinearLayout(this)
        text.orientation = LinearLayout.VERTICAL
        text.addView(Ui.bold(this, title, 16f, pal.textHi))
        if (description != null) {
            val desc = Ui.text(this, description, 12f, pal.textLo)
            desc.setPadding(0, Ui.dp(this, 2f), Ui.dp(this, 8f), 0)
            text.addView(desc)
        }
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
        val button = Ui.button(this, if (on) "ONLINE" else "OFFLINE", if (on) pal.c(0) else Ui.darken(pal.c(3), 0.4f), Ui.PILL) {
            onToggle(!on)
            Sfx.play(this, Sfx.TOGGLE_ON)
        }
        button.gravity = Gravity.CENTER
        Ui.setTextSp(button, 14f)
        button.contentDescription = title + if (on) ": on" else ": off"
        row.addView(button, LinearLayout.LayoutParams(Ui.dp(this, 96f), Ui.dp(this, 44f)))
        list.addView(row)
    }

    /** A full-width action button. */
    private fun action(text: String, color: Int, onClick: View.OnClickListener): TextView {
        val button = Ui.button(this, text, color, Ui.PILL, onClick)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52f))
        params.topMargin = Ui.dp(this, 6f)
        list.addView(button, params)
        return button
    }

    /** A compact pill button for use inside rows. */
    private fun smallButton(text: String, color: Int, onClick: View.OnClickListener): TextView {
        val button = Ui.button(this, text, color, Ui.PILL, onClick)
        button.gravity = Gravity.CENTER
        Ui.setTextSp(button, 13f)
        button.setPadding(0, 0, 0, 0)
        return button
    }

    /** LayoutParams for a weighted button in a horizontal row, with a left gap unless first. */
    private fun rowButtonParams(heightDp: Float, first: Boolean): LinearLayout.LayoutParams {
        val params = LinearLayout.LayoutParams(0, Ui.dp(this, heightDp), 1.0f)
        if (!first) params.leftMargin = Ui.dp(this, 6f)
        return params
    }

    /** A boxed card (outlined panel) for event / phone-sound / light rows. */
    private fun card(color: Int, borderAlpha: Float): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(this, 12f), Ui.dp(this, 8f), Ui.dp(this, 12f), Ui.dp(this, 12f))
        box.background = Ui.outline(this, pal.panel, Ui.alpha(color, borderAlpha), 14f, 1.5f)
        return box
    }

    /** A permission / access status row: GRANTED or NOT GRANTED. */
    private fun accessRow(title: String, granted: Boolean, description: String) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 4f))
        val text = LinearLayout(this)
        text.orientation = LinearLayout.VERTICAL
        text.addView(Ui.bold(this, title, 15f, pal.textHi))
        text.addView(Ui.text(this, description, 12f, pal.textLo))
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
        row.addView(Ui.bold(this, if (granted) "GRANTED" else "NOT GRANTED", 14f, if (granted) pal.c(2) else Ui.alpha(pal.textLo, 0.7f)))
        list.addView(row)
    }

    /** A colour swatch with its hex code and a COPY button (for Good Lock / Signal recipes). */
    private fun colorRow(name: String, color: Int) {
        val hex = SettingsLogic.hexColor(color)
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f))
        val swatch = View(this)
        swatch.background = Ui.outline(this, color, Ui.alpha(pal.c(1), 0.6f), 8f, 1.5f)
        row.addView(swatch, LinearLayout.LayoutParams(Ui.dp(this, 40f), Ui.dp(this, 32f)))
        val text = LinearLayout(this)
        text.orientation = LinearLayout.VERTICAL
        text.setPadding(Ui.dp(this, 12f), 0, 0, 0)
        text.addView(Ui.text(this, name, 12f, pal.textLo))
        text.addView(Ui.bold(this, hex, 17f, pal.textHi))
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
        val copy = Ui.button(this, "COPY", pal.c(2), Ui.PILL) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(name, hex))
            // Android 13+ shows its own clipboard confirmation; don't double up.
            if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.toast_copied, hex), false)
        }
        copy.gravity = Gravity.CENTER
        Ui.setTextSp(copy, 13f)
        row.addView(copy, LinearLayout.LayoutParams(Ui.dp(this, 80f), Ui.dp(this, 40f)))
        list.addView(row)
    }

    /** OPEN &lt;app&gt; when installed, else a note. */
    private fun appButton(name: String, pkg: String, color: Int) {
        if (!installed(pkg)) {
            note("$name is not installed.")
            return
        }
        action("OPEN $name", color) {
            packageManager.getLaunchIntentForPackage(pkg)?.let { open(it) }
        }
    }

    // =================================================================================================
    // Sections
    // =================================================================================================

    /** DEFAULT HOME: role status, Android's home settings and the system dark-mode hint. */
    private fun homeSection() {
        section("DEFAULT HOME", pal.c(0), "HOME")
        if (!Licensing.isUnlocked(this)) {
            action("UNLOCK TREKUI", pal.c(0)) { Licensing.openPurchaseScreen(this) }
        }
        val isDefault = MainActivity.isDefaultHome(this)
        list.addView(
            Ui.bold(
                this, if (isDefault) "STATUS: TREKUI IS YOUR DEFAULT HOME" else "STATUS: TREKUI IS NOT THE DEFAULT HOME",
                17f, if (isDefault) pal.c(2) else pal.alert,
            ),
        )
        if (!isDefault) {
            action("SET TREKUI AS DEFAULT HOME", pal.c(0)) { MainActivity.requestDefaultHome(this) }
        }
        action("ANDROID HOME APP SETTINGS", pal.c(1)) { open(Intent(Settings.ACTION_HOME_SETTINGS)) }
        action(getString(R.string.config_replay_tour), pal.c(2)) { // LCA-T16: replays on home
            Onboarding.requestReplay(this)
            finish()
        }.tag = "config_replay_tour"
        note(
            if (isSamsung()) {
                "To return to Samsung's launcher: Settings › Apps › Choose default apps › Home app › One UI Home."
            } else {
                "To return to your previous launcher: Settings › Apps › Default apps › Home app."
            },
        )
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        list.addView(Ui.bold(this, if (dark) "SYSTEM DARK MODE: ON" else "SYSTEM DARK MODE: OFF", 17f, if (dark) pal.c(2) else pal.alert))
        if (!dark) {
            note("Other apps, menus and pickers are light. Android doesn't let apps switch dark mode, so turn it on in Settings › Display › Dark. If it keeps turning itself off, check Display › Dark mode settings › Turn on as scheduled, and Modes and Routines.")
            action("OPEN DARK MODE SETTINGS", pal.c(4)) {
                // Not a public constant, but present on Android 10+ (Samsung included); fall back to Display.
                if (!tryStart(Intent("android.settings.DARK_THEME_SETTINGS"))) open(Intent(Settings.ACTION_DISPLAY_SETTINGS))
            }
        }
    }

    /** INTERFACE: interface cards, colour theme, layout, lettering, background and wallpapers. */
    private fun interfaceSection() {
        section("INTERFACE", pal.c(1), "THEME")
        note("Every function, slot, sound assignment and device control works the same in all five; only the geometry, lettering and colours change. CLASSIC CONSOLE (23rd-century style) and NEXT CONSOLE (25th-century style) keep the first interface's layouts (grid and cascade) and its sound profile.")
        for (faction in SettingsLogic.STARSHIP_FACTIONS) factionRow(faction)
        if (SubThemes.selectable(Skin.faction)) { // LCA-T35: every interface with more than one sub-theme
            section(getString(R.string.config_color_theme), pal.c(1), null)
            for (i in Palette.themesFor(Skin.faction).indices) themeRow(i)
        } else {
            note(fixedColoursNote())
        }
        if (Skin.imperial()) {
            choice("IMPERIAL LETTERING", arrayOf("MIXED", "ALL BLADE", "PLAIN"), prefs.i("imperialLettering", 0)) { which ->
                prefs.put("imperialLettering", which)
                Skin.lettering = which
                changed()
            }
            note("WARRIOR BLADE is this launcher's own Imperial-style lettering for English. MIXED: titles, labels and keys in Warrior Blade, longer text in Oxanium. ALL BLADE: Warrior Blade everywhere. PLAIN: Oxanium only.")
        }
        if (Skin.command()) {
            layoutChoice("HOME LAYOUT", "CONSOLE GRID", "CONSOLE CASCADE")
            note("CONSOLE CASCADE: one large console key; press it and a segmented spine drops down while the tile keys extend left and right out of it. Fits the screen, no scrolling; panels open from the key row at the bottom. Folds away after you open an app. Long-press the key for all apps.")
        }
        if (Skin.imperial()) {
            layoutChoice("HOME LAYOUT", "COMMAND PLATES", "TACTICAL DEPLOY")
            note("TACTICAL DEPLOY: one command plate; press it and a triangle spine drops down while the tile plates slide out from behind it, narrowing into an arrowhead. Fits the screen, no scrolling; panels open from the plate row at the bottom. Folds away after you open an app. Long-press the plate for all apps.")
        }
        if (Skin.collective()) {
            choice("COLLECTIVE LAYOUT", arrayOf("TRACE COLUMN", "HUB AND SPOKE"), if (prefs.collectiveHub()) 1 else 0) { which ->
                prefs.put("collectiveLayout", which)
                changed()
            }
            note("TRACE COLUMN: one node per row, linked top to bottom. HUB AND SPOKE: nodes of different sizes around a central hub, linked by traces of different lengths. The network stays folded into the hub until you tap it, and folds away again after you open an app. Long-press the hub for all apps.")
        }
        val backgrounds = arrayOf("BLACK", Skin.patternName(), "WALLPAPER")
        choice("BACKGROUND", backgrounds, prefs.bgMode()) { which ->
            prefs.put("bg", which)
            changed()
        }
        if (prefs.bgMode() == 2) {
            choice(
                "WALLPAPER DIMMING", arrayOf("LIGHT 30%", "MEDIUM 55%", "HEAVY 75%"),
                SettingsLogic.dimIndex(prefs.i("dim", 55)),
            ) { which ->
                prefs.put("dim", SettingsLogic.DIM_LEVELS[which])
                changed()
            }
        }
        wallpaperPicker()
    }

    /** Grid vs reveal layout choice (Command / Imperial). */
    private fun layoutChoice(title: String, grid: String, reveal: String) {
        choice(title, arrayOf(grid, reveal), if (prefs.revealMode()) 1 else 0) { which ->
            prefs.put(prefs.layoutKey(), which)
            changed()
        }
    }

    /** Switches to an interface (faction) and reloads its sound profile. */
    private fun selectFaction(faction: Int) {
        if (faction != prefs.faction()) LayoutSnapshots.snapshot(this, LayoutSnapshots.R_INTERFACE) // LCA-T16
        prefs.put("faction", faction)
        Skin.faction = faction
        Sfx.reloadProfile(this)
        Sfx.play(this, Sfx.CONFIRM)
        changed()
    }

    /** One interface card: name, three palette swatches and subtitle; tap selects it. */
    private fun factionRow(faction: Int) {
        // Skin.* answers for the current faction, so borrow this one while drawing its card.
        val saved = Skin.faction
        Skin.faction = faction
        val palette = Palette.of(faction, prefs.subTheme(faction)) // LCA-T35: the card shows its chosen sub-theme
        val selected = saved == faction
        val accent = factionAccent(faction)

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(Ui.dp(this, 12f), Ui.dp(this, 8f), Ui.dp(this, 12f), Ui.dp(this, 8f))
        var radius = when (faction) {
            0 -> 16f
            1 -> 1f
            else -> 3f
        }
        if (Skin.eraFaction(faction)) radius = if (faction == Skin.ERA23) 2f else 0f
        card.background = Ui.touch(
            Ui.outline(
                this, if (selected) Ui.alpha(accent, 0.14f) else Skin.bg(),
                if (selected) accent else Ui.alpha(accent, 0.35f), radius, if (selected) 2.5f else 1f,
            ),
        )
        val header = LinearLayout(this)
        header.gravity = Gravity.CENTER_VERTICAL
        header.addView(Ui.bold(this, Skin.NAMES[faction], 20f, accent), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
        val corners = intArrayOf(Ui.LEFT, Ui.SQUARE, Ui.RIGHT)
        for (i in corners.indices) {
            val swatch = View(this)
            swatch.background = Ui.shape(this, palette.c(i), corners[i], 10f)
            val params = LinearLayout.LayoutParams(Ui.dp(this, if (i == 1) 46f else 30f), Ui.dp(this, 28f))
            params.leftMargin = Ui.dp(this, 4f)
            header.addView(swatch, params)
        }
        card.addView(header)
        val subtitle: View = Ui.oneLine(Ui.text(this, Skin.SUBTITLES[faction], 12f, Ui.alpha(accent, 0.8f)))
        val subtitleParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        subtitleParams.topMargin = Ui.dp(this, 4f)
        card.addView(subtitle, subtitleParams)
        if (Skin.eraFaction(faction)) {
            // a miniature of the era's home screen, drawn in its own geometry
            val preview = View(this)
            preview.background = EraDraw.preview(this, faction, palette)
            preview.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            preview.tag = "interface_preview_$faction"
            val previewParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, EraDraw.PREVIEW_DP))
            previewParams.topMargin = Ui.dp(this, 6f)
            card.addView(preview, previewParams)
        }
        Skin.faction = saved

        card.contentDescription = Skin.NAMES[faction] + if (selected) ", selected" else ""
        card.setOnClickListener(Ui.feedback { selectFaction(faction) })
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = Ui.dp(this, 6f)
        list.addView(card, params)
    }

    /**
     * One colour theme (sub-theme) of the current interface: its name in its own primary colour and a
     * preview strip of its six accents on its own background; tap applies it at once (LCA-T35).
     */
    private fun themeRow(index: Int) {
        val faction = prefs.faction()
        val palette = Palette.themesFor(faction)[index]
        val selected = prefs.subTheme(faction) == index
        val row = LinearLayout(this)
        row.tag = "subtheme_${faction}_$index"
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(this, 12f), Ui.dp(this, 8f), Ui.dp(this, 12f), Ui.dp(this, 8f))
        val rest = if (palette.toned) palette.bg else Skin.bg()
        row.background = Ui.touch(
            Ui.outline(
                this, if (selected) Ui.alpha(palette.c(0), 0.15f) else rest,
                if (selected) palette.c(0) else Ui.alpha(palette.c(1), 0.35f), 16f, if (selected) 2.5f else 1f,
            ),
        )
        row.addView(Ui.bold(this, palette.name, 17f, palette.c(0)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
        for (i in 0 until 6) {
            val swatch = View(this)
            val corner = when (i) {
                0 -> Ui.LEFT
                5 -> Ui.RIGHT
                else -> Ui.SQUARE
            }
            swatch.background = Ui.shape(this, palette.c(i), corner, 8f)
            val params = LinearLayout.LayoutParams(Ui.dp(this, 18f), Ui.dp(this, 22f))
            params.leftMargin = Ui.dp(this, 4f)
            row.addView(swatch, params)
        }
        row.contentDescription = palette.name + if (selected) ", selected" else ""
        row.setOnClickListener(
            Ui.feedback {
                prefs.setSubTheme(faction, index)
                SuiteProvider.notifyChanged(this, SuiteContract.PATH_THEME) // companions follow at once
                Sfx.play(this, Sfx.CONFIRM)
                changed()
            },
        )
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 50f))
        params.bottomMargin = Ui.dp(this, 6f)
        list.addView(row, params)
    }

    /**
     * SUITE (LCA-T35): the companion apps. KEYBOARD shows the active keyboard and opens the picker /
     * enable screen; LOCK SCREEN switches the wake screen on or off (published on the suite provider);
     * MESSAGES makes TrekUI Messages the default SMS app or restores the previous one. A companion that
     * isn't installed shows INSTALL (its Play listing). Lookups and intents live in [SuiteSettings].
     */
    private fun suiteSection() {
        section(getString(R.string.suite_title), pal.c(2), getString(R.string.suite_sidebar))
        note(getString(R.string.suite_note))
        val own = packageName

        val keyboard = SuiteContract.packageFor(SuiteContract.KEYBOARD, own)
        label(getString(R.string.suite_keyboard)).tag = "suite_keyboard"
        val imeId = SuiteSettings.activeImeId(this)
        if (SuiteSettings.isSuiteKeyboard(imeId, keyboard)) {
            list.addView(Ui.bold(this, getString(R.string.suite_keyboard_in_use), 15f, pal.c(2)))
        } else {
            val imeName = SuiteSettings.imeLabel(this, imeId)
            note(getString(R.string.suite_keyboard_active, imeName ?: getString(R.string.suite_unknown)))
        }
        if (installed(keyboard)) {
            action(getString(R.string.suite_keyboard_choose), pal.c(0)) {
                if (!SuiteSettings.showImePicker(this)) open(SuiteSettings.imeSettingsIntent())
            }.tag = "suite_keyboard_choose"
            action(getString(R.string.suite_keyboard_enable), pal.c(1)) { open(SuiteSettings.imeSettingsIntent()) }
                .tag = "suite_keyboard_enable"
        } else {
            suiteInstall(keyboard, "suite_keyboard_install")
        }

        val lockscreen = SuiteContract.packageFor(SuiteContract.LOCKSCREEN, own)
        label(getString(R.string.suite_lockscreen)).tag = "suite_lockscreen"
        if (installed(lockscreen)) {
            toggle(getString(R.string.suite_lockscreen_switch), getString(R.string.suite_lockscreen_desc), prefs.lockscreenEnabled()) { on ->
                prefs.setLockscreenEnabled(on)
                SuiteProvider.notifyChanged(this, SuiteContract.PATH_LOCKSCREEN)
                rebuild()
            }
            action(getString(R.string.suite_open_lockscreen), pal.c(1)) {
                packageManager.getLaunchIntentForPackage(lockscreen)?.let { open(it) }
            }.tag = "suite_lockscreen_open"
        } else {
            suiteInstall(lockscreen, "suite_lockscreen_install")
        }

        val messages = SuiteContract.packageFor(SuiteContract.MESSAGES, own)
        label(getString(R.string.suite_messages)).tag = "suite_messages"
        if (installed(messages)) {
            val current = SuiteSettings.defaultSms(this)
            val isDefault = messages == current
            list.addView(
                Ui.bold(
                    this, getString(if (isDefault) R.string.suite_messages_default else R.string.suite_messages_not_default),
                    15f, if (isDefault) pal.c(2) else pal.alert,
                ),
            )
            if (!isDefault) {
                action(getString(R.string.suite_messages_set), pal.c(0)) {
                    if (current != null) prefs.put(SuiteSettings.KEY_PREV_SMS, current)
                    if (!tryStart(SuiteSettings.requestSmsIntent(messages))) open(SuiteSettings.defaultAppsIntent())
                }.tag = "suite_messages_set"
            } else {
                action(getString(R.string.suite_messages_restore), pal.c(3)) {
                    val previous = SuiteSettings.restoreTarget(prefs.s(SuiteSettings.KEY_PREV_SMS, null), messages)
                    if (!tryStart(SuiteSettings.restoreSmsIntent(previous))) open(SuiteSettings.defaultAppsIntent())
                }.tag = "suite_messages_restore"
            }
        } else {
            suiteInstall(messages, "suite_messages_install")
        }
    }

    /** INSTALL for a companion that isn't installed: its Play listing (store app, else the web). */
    private fun suiteInstall(pkg: String, tag: String) {
        action(getString(R.string.suite_install), pal.c(4)) {
            if (!tryStart(SuiteSettings.marketIntent(pkg))) open(SuiteSettings.webStoreIntent(pkg))
        }.tag = tag
    }

    /** console WALLPAPERS: three thumbnails; tap applies one to home, lock or both. */
    private fun wallpaperPicker() {
        label("TREKUI WALLPAPERS")
        note("Apply a theme-kit wallpaper to your phone's home and/or lock screen. It shows behind TrekUI when BACKGROUND is set to WALLPAPER.")
        val row = LinearLayout(this)
        for (i in WALLPAPERS.indices) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.addView(
                thumbView(thumbAsset("wallpapers/" + WALLPAPERS[i] + ".png", THUMB_SAMPLE), pal.c(i + 1)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 150f)),
            )
            val name = Ui.bold(this, WALLPAPER_NAMES[i], 14f, pal.c(i + 1))
            name.setPadding(0, Ui.dp(this, 4f), 0, 0)
            item.addView(name)
            item.setOnClickListener(Ui.feedback { applyWallpaper(i) })
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            if (i > 0) params.leftMargin = Ui.dp(this, 8f)
            row.addView(item, params)
        }
        list.addView(row)
    }

    /** A framed, centre-cropped thumbnail. */
    private fun thumbView(bitmap: Bitmap?, frameColor: Int): ImageView {
        val image = ImageView(this)
        image.scaleType = ImageView.ScaleType.CENTER_CROP
        image.setImageBitmap(bitmap)
        image.background = Ui.outline(this, Color.BLACK, frameColor, 10f, 2f)
        val pad = Ui.dp(this, 3f)
        image.setPadding(pad, pad, pad, pad)
        return image
    }

    /**
     * Asks where to apply wallpaper [index], then sets it on a worker thread. Android 6.0 has one wallpaper for
     * home and lock screen (separate lock wallpapers arrived in 7.0, API 24), so there it is set without asking.
     */
    private fun applyWallpaper(index: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            setWallpaper(index, SettingsLogic.wallpaperTarget(2))
            return
        }
        ConsoleDialog.options(this, "APPLY " + WALLPAPER_NAMES[index], arrayOf("HOME SCREEN", "LOCK SCREEN", "HOME + LOCK")) { which ->
            setWallpaper(index, SettingsLogic.wallpaperTarget(which))
        }
    }

    /** Sets wallpaper [index] on [target] (WallpaperManager FLAG_SYSTEM / FLAG_LOCK bits; API 24+ only). */
    private fun setWallpaper(index: Int, target: Int) {
        toast(getString(R.string.toast_applying), false)
        background("wallpaper") {
            val applied = try {
                assets.open("wallpapers/" + WALLPAPERS[index] + ".png").use { input ->
                    val wm = WallpaperManager.getInstance(applicationContext)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wm.setStream(input, null, true, target)
                    } else {
                        wm.setStream(input)
                    }
                }
                true
            } catch (e: Exception) {
                false
            }
            runOnUiIfAlive {
                Sfx.play(this, if (applied) Sfx.CONFIRM else Sfx.ERROR)
                val message = if (applied) R.string.toast_wallpaper_applied else R.string.toast_wallpaper_failed
                toast(getString(message), false)
            }
        }
    }

    /** NOTIFICATION SHADE: console shade toggles, system-settings access and the Samsung Quick Panel recipe. */
    private fun shadeSection() {
        section("NOTIFICATION SHADE", pal.c(2), "SHADE")
        val samsung = isSamsung()
        note(
            if (samsung) {
                "TREKUI SHADE: swipe down on the TrekUI home screen. Samsung's own panel (used in other apps) is themed separately below."
            } else {
                "TREKUI SHADE: swipe down on the TrekUI home screen. The system's own panel is used in other apps."
            },
        )
        toggle(
            "SWIPE DOWN OPENS TREKUI SHADE",
            if (samsung) "Off: swipe down opens Samsung's panel instead" else "Off: swipe down opens the system panel instead",
            prefs.b("shadeConsole", true),
        ) { on ->
            prefs.put("shadeConsole", on)
            rebuild()
        }
        toggle("SHOW NOTIFICATIONS IN TREKUI SHADE", "Uses notification access; nothing is stored or sent", prefs.b("shadeNotes", true)) { on ->
            prefs.put("shadeNotes", on)
            rebuild()
        }
        toggle(getString(R.string.badge_setting), getString(R.string.badge_setting_desc), prefs.b(NotificationBadges.PREF, true)) { on ->
            prefs.put(NotificationBadges.PREF, on) // LCA-T23: read again when the home screen resumes
            rebuild()
        }
        val canWrite = Settings.System.canWrite(this)
        accessRow("BRIGHTNESS & ROTATION CONTROL", canWrite, "“Modify system settings”: lets the shade's slider and ROTATE tile work")
        if (!canWrite) {
            action("ALLOW BRIGHTNESS & ROTATION CONTROL", pal.c(4)) { openWriteSettings() }
        }
        if (!samsung) return // the Quick Panel recipe needs Samsung's Good Lock
        label("SAMSUNG QUICK PANEL (ONE UI 9 + GOOD LOCK)")
        note("Android doesn't let a launcher restyle Samsung's panel, but Samsung's Good Lock can. Recipe for the " + pal.name + " theme:\n1. Install Good Lock (Galaxy Store), then add QuickStar and Theme Park inside it.\n2. QuickStar › Panel Theme: set the four colours below and transparency to 0%.\n3. Tap EXPORT below, then QuickStar › Edit panel style: give the button box, buttons, brightness slider, volume slider and media player the matching images from Pictures › TrekUI Quick Panel. Turn borders off.\n4. Optional: set a TrekUI wallpaper (THEME section) so Samsung's colour palette matches too.")
        colorRow("BACKGROUND", Color.BLACK)
        colorRow("ACTIVE CONTROLS", pal.c(0))
        colorRow("INACTIVE CONTROLS", Ui.darken(pal.c(1), 0.35f))
        colorRow("TEXT", pal.textHi)
        action("EXPORT TREKUI PANEL ART TO GALLERY", pal.c(0)) { exportPanelArt() }
        goodLockButton(pal.c(1))
    }

    private fun goodLockButton(color: Int) {
        action(if (installed(PKG_GOOD_LOCK)) "OPEN GOOD LOCK" else "GET GOOD LOCK (GALAXY STORE)", color) { openGoodLock() }
    }

    /** APP THEMING: exported backgrounds and recipes for Signal, Chrome, Outlook, keyboard and nav buttons. */
    private fun appSection() {
        section("APP THEMING", pal.c(5), "APPS")
        val theme = themeIndex()
        note("Android doesn't let one app restyle another (that would need root), so TrekUI reaches Signal, Chrome and Outlook through each app's own theme settings. Export the " + pal.name + " backgrounds, then follow each recipe. Their home-screen icons get TrekUI pictograms when ICON STYLE is TrekUI.")
        val previews = LinearLayout(this)
        val apps = arrayOf("signal", "chrome")
        val names = arrayOf("SIGNAL CHAT", "CHROME NEW TAB")
        for (i in apps.indices) {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.addView(
                thumbView(thumbAsset("appthemes/" + apps[i] + "/" + theme + ".png", THUMB_SAMPLE), pal.c(i + 1)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 190f)),
            )
            item.addView(Ui.bold(this, names[i], 14f, pal.c(i + 1)))
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            if (i > 0) params.leftMargin = Ui.dp(this, 12f)
            previews.addView(item, params)
        }
        list.addView(previews)
        val folder = "Pictures/TrekUI App Themes/" + themeFolder(theme) + "/"
        action("EXPORT APP BACKGROUNDS TO GALLERY", pal.c(0)) {
            exportImages(
                arrayOf(
                    arrayOf("appthemes/signal/$theme.png", "TrekUI Signal.png"),
                    arrayOf("appthemes/chrome/$theme.png", "TrekUI Chrome.png"),
                    arrayOf("appthemes/keyboard/$theme.png", "TrekUI Keyboard.png"),
                ),
                folder, "BACKGROUNDS EXPORTED",
                "Signal: Settings › Appearance › Chat color & wallpaper › Set wallpaper › choose TrekUI Signal.\nChrome: new tab › ⋮ › Customize new tab page › Appearance › Upload an image › choose TrekUI Chrome.\nKeyboard: Theme Park › Keyboard › pink picture button › choose TrekUI Keyboard.",
            )
        }

        val hsv = FloatArray(3)
        Color.colorToHSV(pal.c(0), hsv)
        val sliders = SettingsLogic.signalSliders(hsv)
        label("SIGNAL")
        note("1. Signal › your profile picture › Settings › Appearance › Theme: DARK.\n2. Appearance › Chat color & wallpaper › Set wallpaper › Choose from photos › TrekUI Signal. Turn “Dim wallpaper in dark theme” off; the art is already dimmed.\n3. Same screen › Chat color › + (custom) › SOLID. Signal uses sliders, not hex codes: hue " + sliders[0] + "° (about " + Math.round(sliders[0] / 3.6f) + "% along the hue slider), saturation " + sliders[1] + "% (about " + sliders[1] + "% along). Signal adjusts brightness itself for readable text, so the result is close to the swatch, not exact.")
        colorRow("OUTGOING BUBBLE (TARGET)", pal.c(0))
        appButton("SIGNAL", PKG_SIGNAL, pal.c(1))
        label("CHROME")
        note("1. Chrome › ⋮ › Settings › Appearance (THEME on older versions) › DARK.\n2. Open a new tab › ⋮ › Customize new tab page › Appearance › Upload an image › TrekUI Chrome. (Recent Chrome versions; this only changes the new tab page.)\n3. Chrome's toolbar and menus take their colour from your phone's palette: apply a TrekUI wallpaper (THEME section), then Android Settings › Wallpaper and style › Color palette and pick the swatch closest to the accent below.")
        colorRow("ACCENT", pal.c(0))
        appButton("CHROME", PKG_CHROME, pal.c(2))
        label("OUTLOOK")
        note("1. Outlook › your profile picture › Settings (gear) › Preferences/Appearance › Theme: DARK THEME (not “Set by Battery Saver”).\n2. If your version shows colour themes, pick the swatch closest to the accent above. Outlook has no custom backgrounds, so dark theme plus the TrekUI home icon is as far as it goes.")
        appButton("OUTLOOK", PKG_OUTLOOK, pal.c(3))
        if (!isSamsung()) { // Theme Park, ClockFace and NavStar are Samsung Good Lock modules
            label("KEYBOARD")
            note("Gboard › Settings › Theme › My image with TrekUI Keyboard (EXPORT above).")
            return
        }
        label("SAMSUNG KEYBOARD (GOOD LOCK › THEME PARK)")
        note("1. Good Lock › Theme Park › Keyboard › + (create). Tap the moon/sun icon so you're editing the dark version.\n2. Pink picture button: choose TrekUI Keyboard from Pictures › TrekUI App Themes (EXPORT above). The TrekUI frame shows around the keys.\n3. Go through the circles like reading a page: start top left, go left to right, then down a row. Tap each one and enter the colour below, in this order (skip the circle showing your picture, it's the background image).\n4. Download icon (top right) saves and applies.\nUsing Gboard instead? Gboard › Settings › Theme › My image with TrekUI Keyboard.")
        colorRow("1 · TOP ROW, LEFT: TYPING POP-UP", pal.c(1))
        colorRow("2 · TOP ROW, MIDDLE: TOOLBAR ICONS", pal.c(0))
        colorRow("3 · TOP ROW, RIGHT: TOOLBAR BACKGROUND", Color.BLACK)
        colorRow("4 · BY THE 3 KEY: POP-UP LETTER", Color.BLACK)
        colorRow("5 · UNDER THE W: PRESSED KEY", pal.c(2))
        colorRow("6 · BY THE ! KEY: FUNCTION KEY ICONS", Color.BLACK)
        colorRow("7 · BOTTOM ROW, LEFT: FUNCTION KEYS", pal.c(0))
        colorRow("8 · BOTTOM ROW, MIDDLE: LETTERS", Color.BLACK)
        colorRow("9 · BOTTOM ROW, RIGHT: LETTER KEYS", pal.c(4))
        goodLockButton(pal.c(4))
        label("ALWAYS ON DISPLAY")
        note("One UI 7 and later removed custom AOD images: the AOD shows your lock screen clock. For TrekUI colours there, Good Lock › ClockFace can style the clock (clock #FF9C00, date #B4A0F0).")
        label("NAVIGATION BUTTONS (GOOD LOCK › NAVSTAR)")
        val navRow = LinearLayout(this)
        navRow.setBackgroundColor(Skin.bg())
        navRow.gravity = Gravity.CENTER
        for (button in arrayOf("recents", "home", "back")) {
            val image = ImageView(this)
            image.setImageBitmap(thumbAsset("appthemes/nav/$theme/nav_$button.png", 1))
            navRow.addView(image, LinearLayout.LayoutParams(0, Ui.dp(this, 64f), 1.0f))
        }
        list.addView(navRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 64f)))
        note("TrekUI pills for the button navigation bar, in Samsung's order: recents · home · back.\n1. Tap EXPORT below.\n2. Good Lock › NavStar › Navigation buttons: make a new custom button style and, for each button, pick the matching image (TrekUI Nav Recents / Home / Back) from your Gallery.\n3. Set the navigation bar background to black (#000000).\nNavStar's screens vary between versions; if it isn't obvious which slot is which, use the same trick as the keyboard.")
        action("EXPORT NAVIGATION BUTTONS", pal.c(0)) {
            exportImages(
                arrayOf(
                    arrayOf("appthemes/nav/$theme/nav_recents.png", "TrekUI Nav Recents.png"),
                    arrayOf("appthemes/nav/$theme/nav_home.png", "TrekUI Nav Home.png"),
                    arrayOf("appthemes/nav/$theme/nav_back.png", "TrekUI Nav Back.png"),
                ),
                folder, "BUTTONS EXPORTED",
                "Good Lock › NavStar › Navigation buttons › custom style: give each button its TrekUI image.",
            )
        }
    }

    /** SMART DEVICES: Govee lights (LAN + cloud) and the Nest thermostat. */
    private fun devicesSection() {
        section("SMART DEVICES", pal.c(3), "DEVICES")
        label("GOVEE LIGHTS")
        note("Switch a Govee light on and off from any TrekUI segment, tile or dock icon. Two ways to connect; you can use both, and LAN is tried first.\nLAN (instant, stays on your Wi-Fi, no account): in the Govee Home app open the light › ⚙ › turn on LAN Control. Then, on the same Wi-Fi, tap SCAN NETWORK.\nCLOUD (works from anywhere, about a second): in Govee Home tap Profile › ⚙ Settings › Apply for API Key. The key arrives by email; enter it with API KEY, then tap FETCH DEVICES (this also brings in the names you gave your lights).")
        action("SCAN NETWORK (LAN)", pal.c(0)) {
            toast("SCANNING SUBSPACE FREQUENCIES…", false)
            Sfx.play(this, Sfx.SCAN_START)
            Govee.scanLan(this) { devices, error -> goveeFound(devices, error, false) }
        }
        action("ADD BY IP ADDRESS (LAN)", pal.c(4)) {
            ConsoleDialog.prompt(this, "LAMP IP ADDRESS", prefs.s("goveeLastIp", ""), "E.G. 192.168.1.42") { text -> goveeProbe(text.trim()) }
        }
        val key = Govee.key(prefs)
        action(if (key.isEmpty()) "API KEY (CLOUD)" else "API KEY • " + SettingsLogic.maskSecret(key), pal.c(1)) {
            ConsoleDialog.prompt(this, "GOVEE API KEY", key, "PASTE THE KEY FROM GOVEE'S EMAIL") { text ->
                prefs.put("goveeKey", text.trim())
                Sfx.play(this, Sfx.CONFIRM)
                rebuild()
            }
        }
        if (key.isNotEmpty()) {
            action("FETCH DEVICES (CLOUD)", pal.c(2)) {
                toast("CONTACTING GOVEE…", false)
                Sfx.play(this, Sfx.SCAN_START)
                Govee.fetchCloud(key) { devices, error -> goveeFound(devices, error, true) }
            }
        }
        val devices = Govee.devices(prefs)
        if (devices.isEmpty()) {
            note("No lights added yet.")
        } else {
            for (i in devices.indices) goveeRow(devices, i)
            note("To assign one: long-press any TrekUI segment, tile or dock icon › ASSIGN TREKUI FUNCTION › LIGHTS: <name>. Each tap toggles the light; the segment chirps and shows ON or OFF.")
        }
        nestSection()
        homeAssistantSection() // LCA-T23
    }

    /** Result of a Govee LAN scan, cloud fetch or IP probe: merge and save the lights, or explain the failure. */
    private fun goveeFound(found: List<Govee.Device>, error: String?, cloud: Boolean) {
        if (isFinishing || isDestroyed) return
        if (error != null) {
            Sfx.play(this, Sfx.ERROR)
            ConsoleDialog.message(this, if (cloud) "GOVEE CLOUD ERROR" else "SCAN FAILED", error, arrayOf("OK"), null)
            return
        }
        if (found.isEmpty()) {
            Sfx.play(this, Sfx.DENY)
            val message = if (cloud) {
                "Govee returned no lights with an on/off control for this key."
            } else {
                "No Govee light answered the scan (sent from " + Govee.lastInterface + ").\n\n• In Govee Home, open the lamp › ⚙ and check LAN Control is on. It can take a few minutes to appear after setup, and some models don't have it (then use the cloud API key).\n• The phone must be on the same Wi-Fi as the lamp, not a guest network. Mesh systems and routers with \"multicast/IGMP snooping\" or \"AP isolation\" can block discovery.\n• Workaround: ADD BY IP ADDRESS. Find the lamp in your router's device list (Govee lamps usually show up as \"ihoment_\" plus the model number) and enter its address."
            }
            ConsoleDialog.message(this, "NO LIGHTS FOUND", message, arrayOf("OK"), null)
            return
        }
        Govee.save(prefs, Govee.merge(Govee.devices(prefs), found, cloud))
        Sfx.play(this, Sfx.SCAN_DONE)
        toast(SettingsLogic.count(found.size, "LIGHT", "LIGHTS") + " FOUND", false)
        rebuild()
    }

    /** ADD BY IP ADDRESS: asks one address directly on Govee's LAN ports (worker thread). */
    private fun goveeProbe(address: String) {
        if (!SettingsLogic.isIpv4(address)) {
            Sfx.play(this, Sfx.DENY)
            toast(getString(R.string.toast_bad_ip), true)
            return
        }
        prefs.put("goveeLastIp", address)
        toast("HAILING $address…", false)
        Sfx.play(this, Sfx.SCAN_START)
        background("govee-probe") {
            val (answered, reason) = try {
                Govee.probe(applicationContext, address) to null
            } catch (e: Exception) {
                null to e.message
            }
            runOnUiIfAlive {
                if (answered != null) {
                    goveeFound(listOf(answered), null, false)
                    return@runOnUiIfAlive
                }
                Sfx.play(this, Sfx.ERROR)
                ConsoleDialog.message(
                    this, "NO ANSWER FROM $address",
                    (if (reason != null) reason + "\n\n" else "") +
                        "Nothing at that address answered on Govee's LAN ports. Check the address and that LAN Control is on for the lamp in Govee Home.",
                    arrayOf("OK"), null,
                )
            }
        }
    }

    /** One saved light: TOGGLE, RENAME, REMOVE. */
    private fun goveeRow(devices: MutableList<Govee.Device>, index: Int) {
        val device = devices[index]
        val color = pal.c(intArrayOf(0, 2, 3, 4, 1, 5)[index % 6])
        val box = card(color, 0.8f)
        box.addView(Ui.bold(this, device.title(), 16f, color))
        val route = Ui.text(this, device.sku + "  •  " + device.route(), 13f, pal.textHi)
        route.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 6f))
        box.addView(route)
        val buttons = LinearLayout(this)
        buttons.addView(
            smallButton("TOGGLE", pal.c(0)) {
                Govee.power(this, device.id, null) { ok, message, _ ->
                    Sfx.play(this, if (ok) Sfx.CONFIRM else Sfx.ERROR)
                    toast(message, true)
                }
            },
            rowButtonParams(40f, true),
        )
        buttons.addView(
            smallButton("RENAME", pal.c(1)) {
                ConsoleDialog.prompt(this, "RENAME LIGHT", device.name, "NAME") { text ->
                    device.name = text.trim().uppercase(Locale.getDefault())
                    Govee.save(prefs, devices)
                    rebuild()
                }
            },
            rowButtonParams(40f, false),
        )
        buttons.addView(
            smallButton("REMOVE", Ui.darken(pal.c(3), 0.8f)) {
                devices.removeAt(index)
                Govee.save(prefs, devices)
                Sfx.play(this, Sfx.CLOSE)
                rebuild()
            },
            rowButtonParams(40f, false),
        )
        box.addView(buttons)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = Ui.dp(this, 8f)
        list.addView(box, params)
    }

    /**
     * HOME ASSISTANT (LCA-T23): address and long-lived token (kept in [HomeAssistant.FILE],
     * excluded from backup and export), IMPORT ENTITIES, and the imported entities with CONTROL.
     */
    private fun homeAssistantSection() {
        label(getString(R.string.ha_section))
        note(getString(R.string.ha_section_note))
        val url = HomeAssistant.baseUrl(this)
        val token = HomeAssistant.token(this)
        action(getString(R.string.ha_address, if (url.isEmpty()) getString(R.string.ha_not_set) else SettingsLogic.abbreviate(url)), pal.c(0)) {
            HomeAssistantDialog.prompt(this, getString(R.string.ha_address_prompt), url, getString(R.string.ha_address_hint), false) { text ->
                val normalized = HomeAssistant.normalizeUrl(text)
                val kind = HomeAssistant.checkUrl(normalized)
                if (normalized.isNotEmpty() && kind != HomeAssistant.URL_OK && kind != HomeAssistant.URL_LOCAL_HTTP) {
                    Sfx.play(this, Sfx.DENY)
                    ConsoleDialog.message(
                        this, getString(R.string.ha_address_prompt),
                        getString(if (kind == HomeAssistant.URL_INSECURE) R.string.ha_url_insecure else R.string.ha_url_invalid),
                        arrayOf("OK"), null,
                    )
                    return@prompt
                }
                HomeAssistant.setBaseUrl(this, normalized)
                Sfx.play(this, Sfx.CONFIRM)
                rebuild()
            }
        }
        if (HomeAssistant.checkUrl(url) == HomeAssistant.URL_LOCAL_HTTP) {
            val warning = Ui.text(this, getString(R.string.ha_warn_cleartext), 13f, pal.alert)
            warning.tag = "ha_cleartext_warning"
            warning.setPadding(0, Ui.dp(this, 6f), 0, 0)
            list.addView(warning)
        }
        if (HomeAssistant.localNameMayFail(url, Build.VERSION.SDK_INT)) {
            val hint = Ui.text(this, getString(R.string.ha_local_name_hint), 13f, pal.textLo)
            hint.tag = "ha_local_name_hint"
            hint.setPadding(0, Ui.dp(this, 6f), 0, 0)
            list.addView(hint)
        }
        action(getString(R.string.ha_token, if (token.isEmpty()) getString(R.string.ha_not_set) else SettingsLogic.maskSecret(token)), pal.c(1)) {
            HomeAssistantDialog.prompt(this, getString(R.string.ha_token_prompt), "", getString(R.string.ha_token_hint), true) { text ->
                if (text.isEmpty()) return@prompt
                HomeAssistant.setToken(this, text)
                Sfx.play(this, Sfx.CONFIRM)
                rebuild()
            }
        }
        if (!HomeAssistant.configured(this)) return
        action(getString(R.string.ha_import), pal.c(2)) {
            toast(getString(R.string.ha_importing), false)
            Sfx.play(this, Sfx.SCAN_START)
            HomeAssistant.async({ HomeAssistant.importEntities(applicationContext) }) { found, error ->
                if (isFinishing || isDestroyed) return@async
                if (error != null) {
                    Sfx.play(this, Sfx.ERROR)
                    ConsoleDialog.message(this, getString(R.string.ha_import_failed), error, arrayOf("OK"), null)
                    return@async
                }
                val imported = found.orEmpty()
                Sfx.play(this, if (imported.isEmpty()) Sfx.DENY else Sfx.SCAN_DONE)
                toast(getString(R.string.ha_imported, imported.size), false)
                rebuild()
            }
        }
        val entities = HomeAssistant.entities(this)
        if (entities.isEmpty()) {
            note(getString(R.string.ha_none))
        } else {
            val shown = minOf(entities.size, HA_ROWS_SHOWN)
            for (i in 0 until shown) {
                val entity = entities[i]
                val color = pal.c(intArrayOf(0, 2, 3, 4, 1, 5)[i % 6])
                action(getString(R.string.ha_control) + " • " + entity.title() + "  (" + entity.domain().uppercase(Locale.US) + ")", color) {
                    if ("climate" == entity.domain()) {
                        ThermostatDialog.show(this, HomeAssistant.Climate(entity.id), entity.title())
                    } else {
                        HomeAssistantDialog.show(this, entity.id, entity.title())
                    }
                }
            }
            if (entities.size > shown) note(getString(R.string.ha_more, entities.size - shown))
            note(getString(R.string.ha_assign_note))
        }
        action(getString(R.string.ha_forget), Ui.darken(pal.c(3), 0.8f)) {
            HomeAssistant.forget(this)
            Sfx.play(this, Sfx.CLOSE)
            rebuild()
        }
    }

    /** NEST THERMOSTAT: Device Access setup, linking and the linked thermostats. */
    private fun nestSection() {
        label("NEST THERMOSTAT")
        val linked = Nest.linked(prefs)
        note(
            if (linked) {
                "Linked. Assign it: long-press any TrekUI segment, tile or dock icon › ASSIGN TREKUI FUNCTION › CLIMATE: <name>. Tapping it opens the TrekUI environmental control."
            } else {
                "Google only lets apps reach Nest through its Device Access program, so this needs a one-time setup (about 15 minutes, and Google charges a one-time US$5 Device Access registration fee):\n1. DEVICE ACCESS CONSOLE: register (accept the terms, pay the fee).\n2. GOOGLE CLOUD: create a project › enable “Smart Device Management API” › OAuth consent screen: External, add your Gmail as a test user, then PUBLISH APP (otherwise Google unlinks you every 7 days) › Credentials › Create OAuth client ID › Web application › Authorized redirect URI: https://www.google.com. Keep the client ID and secret.\n3. Back in the Device Access Console: Create project › paste the OAuth client ID › copy the Project ID.\n4. Enter the three values below, tap LINK ACCOUNT, sign in, choose your home and allow the thermostat. Google then shows its home page: copy the whole address from the address bar and tap PASTE GOOGLE LINK."
            },
        )
        if (!linked) {
            action("OPEN DEVICE ACCESS CONSOLE", pal.c(0)) { openUrl("https://console.nest.google.com/device-access") }
            action("OPEN GOOGLE CLOUD CREDENTIALS", pal.c(1)) { openUrl("https://console.cloud.google.com/apis/credentials") }
            nestField("DEVICE ACCESS PROJECT ID", "nestProject", false, pal.c(2))
            nestField("OAUTH CLIENT ID", "nestClientId", false, pal.c(3))
            nestField("OAUTH CLIENT SECRET", "nestSecret", true, pal.c(4))
            if (Nest.configured(prefs)) {
                action("LINK ACCOUNT (OPENS GOOGLE)", pal.c(0)) { open(Intent(Intent.ACTION_VIEW, Nest.authUri(prefs))) }
                action("PASTE GOOGLE LINK", pal.c(2)) { nestPaste() }
            }
            return
        }
        val thermostats = Nest.devices(prefs)
        thermostats.forEachIndexed { i, thermostat ->
            action("CONTROL • " + thermostat.label, pal.c(intArrayOf(0, 2, 3)[i % 3])) {
                ThermostatDialog.show(this, thermostat.name, thermostat.label)
            }
        }
        if (thermostats.isEmpty()) {
            note("No thermostats yet: tap FETCH THERMOSTATS. If none appear, link again and make sure the thermostat is switched on in Google's permission screen.")
        }
        action("FETCH THERMOSTATS", pal.c(1)) { nestFetch() }
        action("UNLINK NEST", Ui.darken(pal.c(3), 0.8f)) {
            Nest.unlink(prefs)
            Sfx.play(this, Sfx.CLOSE)
            rebuild()
        }
    }

    /** One Nest credential field (secrets are masked, long ids abbreviated). */
    private fun nestField(title: String, key: String, secret: Boolean, color: Int) {
        val value = prefs.s(key, "").orEmpty()
        val shown = when {
            value.isEmpty() -> "NOT SET"
            secret -> SettingsLogic.maskSecret(value)
            else -> SettingsLogic.abbreviate(value)
        }
        action("$title • $shown", color) {
            ConsoleDialog.prompt(this, title, prefs.s(key, ""), "PASTE IT HERE") { text ->
                prefs.put(key, text.trim())
                Sfx.play(this, Sfx.CONFIRM)
                rebuild()
            }
        }
    }

    /** PASTE GOOGLE LINK: pre-fills from the clipboard, extracts the code and links the account. */
    private fun nestPaste() {
        var clip = ""
        try {
            val primary = getSystemService(ClipboardManager::class.java)?.takeIf { it.hasPrimaryClip() }?.primaryClip
            if (primary != null && primary.itemCount > 0) {
                val text = primary.getItemAt(0).text
                if (text != null && text.toString().contains("code=")) clip = text.toString()
            }
        } catch (ignored: RuntimeException) {
            // clipboard unavailable
        }
        ConsoleDialog.prompt(this, "PASTE GOOGLE LINK", clip, "HTTPS://WWW.GOOGLE.COM/?CODE=…") { text ->
            val code = Nest.codeFrom(text)
            if (code.isEmpty()) {
                Sfx.play(this, Sfx.DENY)
                toast("NO CODE IN THAT LINK • COPY THE WHOLE ADDRESS AFTER ALLOWING ACCESS", true)
                return@prompt
            }
            toast("ESTABLISHING LINK…", false)
            Nest.async({
                Nest.link(applicationContext, code)
                Nest.fetchDevices(applicationContext)
            }) { thermostats, error -> nestResult(thermostats, error) }
        }
    }

    private fun nestFetch() {
        Sfx.play(this, Sfx.SCAN_START)
        Nest.async({ Nest.fetchDevices(applicationContext) }) { thermostats, error -> nestResult(thermostats, error) }
    }

    /** Result of linking or fetching thermostats. */
    private fun nestResult(thermostats: List<Nest.Device>?, error: String?) {
        if (isFinishing || isDestroyed) return
        if (error != null) {
            Sfx.play(this, Sfx.ERROR)
            ConsoleDialog.message(this, "NEST LINK FAILED", error, arrayOf("OK"), null)
        } else {
            val found = thermostats.orEmpty()
            Sfx.play(this, if (found.isEmpty()) Sfx.DENY else Sfx.SCAN_DONE)
            toast(
                if (found.isEmpty()) {
                    "LINKED • NO THERMOSTATS SHARED"
                } else {
                    SettingsLogic.count(found.size, "THERMOSTAT", "THERMOSTATS") + " ONLINE"
                },
                true,
            )
        }
        rebuild()
    }

    /** AUDIO SYSTEMS: effects on/off and volume, the eight sound events, phone sounds and the library. */
    private fun audioSection() {
        section("AUDIO SYSTEMS", pal.c(3), "AUDIO")
        val enabled = Sfx.enabled(this)
        toggle("TREKUI SOUND EFFECTS", "Plays the sounds you import below", enabled) { on ->
            prefs.put("sfx", on)
            if (on) Sfx.init(this) else Sfx.release()
            rebuild()
        }
        if (!enabled) {
            note("Sound effects are offline.")
            return
        }
        volumeSlider()
        note("Plays on Android's system-sounds volume (slider × system volume). Silent in Silent/Vibrate mode and during calls.")
        toggle("FOLLOW ANDROID TOUCH SOUNDS", "Mute TrekUI sounds whenever Android's “Touch interactions” sound is off", Sfx.followTouch(this)) { on ->
            prefs.put("sfxTouch", on)
            rebuild()
        }
        val assigned = Sfx.assignedEvents(this)
        label("SOUND LIBRARY  •  " + assigned + " / " + Sfx.EVENTS + " EVENTS WITH SOUND")
        if (assigned == 0) {
            note("This build has no built-in sounds. Import clips below; each file is decoded on the phone, trimmed of silence and gently level-matched. Pitch and timbre are never changed.").setTextColor(pal.c(0))
        } else if (Sfx.hasLibrary(this)) {
            note("Built-in: your recordings (chirp, deny beep, hail, viewscreen sequences, red alert). Tap CHOOSE SOUND to swap clips per event, import a different file, or silence an event.")
        }
        soundSetAndDownload()
        action("IMPORT MORE FILES (AUTO-MATCH)", pal.c(0)) { pickFiles(REQ_IMPORT_FILES, true, MIME_AUDIO) }
        action("IMPORT ZIP (AUTO-MATCH)", pal.c(2)) { pickFiles(REQ_IMPORT_ZIP, false, MIME_ZIP) }
        note("Auto-match: name files after the event (button, navigation, launch, confirm, error, comms, diagnostics, red_alert), or keep common source file names such as denybeep3, hailbeep_clean or “computer beep 12”. Up to 8 button files rotate. Anything unmatched can be assigned below.")
        for (event in 0 until Sfx.EVENTS) eventRow(event)
        phoneSounds()
        soundLibrary()
        val anyImported = (0 until Sfx.EVENTS).any { Sfx.variantCount(this, it) > 0 }
        if (anyImported) {
            action("REMOVE ALL IMPORTED SOUNDS", Ui.darken(pal.c(3), 0.7f)) {
                ConsoleDialog.message(
                    this, "REMOVE IMPORTED SOUNDS",
                    "Remove every imported sound? Events return to their built-in clips.", arrayOf("CLEAR", "CANCEL"),
                ) { which ->
                    if (which != 0) return@message
                    for (event in 0 until Sfx.EVENTS) SoundImporter.clear(this, event)
                    Sfx.reload(this)
                    rebuild()
                }
            }
        }
        note("Imported sounds are stored privately by TrekUI on this phone and are excluded from Android backups. Third-party recordings belong to their copyright holders: keep them for your personal use and don't redistribute them.")
        if (!Sfx.audioAllowed(this)) {
            note("NOTE: your phone is in Silent/Vibrate mode (or a call), so previews are muted.").setTextColor(pal.alert)
        }
    }

    /** EFFECTS VOLUME slider; plays a button sound when released. */
    private fun volumeSlider() {
        val title = label("EFFECTS VOLUME  •  " + Sfx.volume(this) + "%")
        val slider = SeekBar(this)
        slider.max = 100
        slider.progress = Sfx.volume(this)
        slider.contentDescription = "Effects volume"
        slider.progressTintList = ColorStateList.valueOf(pal.c(3))
        slider.thumbTintList = ColorStateList.valueOf(pal.c(0))
        slider.progressBackgroundTintList = ColorStateList.valueOf(Ui.alpha(pal.c(3), 0.3f))
        slider.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    prefs.put("sfxVol", progress)
                    title.text = "EFFECTS VOLUME  •  $progress%"
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    Sfx.playEvent(this@SettingsActivity, Sfx.E_BUTTON, true)
                }
            },
        )
        list.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44f)))
    }

    /** One sound event: what plays, a preview, CHOOSE SOUND and ON/OFF. */
    private fun eventRow(event: Int) {
        val variants = Sfx.variantCount(this, event)
        val on = Sfx.eventOn(this, event)
        val hasSound = Sfx.hasSound(this, event)
        val color = pal.c(event % 6)
        val box = card(color, if (hasSound) 0.8f else 0.3f)
        box.addView(Ui.bold(this, Sfx.EVENT_NAMES[event], 16f, if (hasSound) color else pal.textHi))
        val source = Ui.text(this, eventSource(event, variants), 13f, if (hasSound) pal.textHi else Ui.alpha(pal.textLo, 0.7f))
        source.setPadding(0, Ui.dp(this, 2f), 0, 0)
        box.addView(source)
        val description = Ui.text(this, Sfx.EVENT_DESC[event], 11f, Ui.alpha(pal.textLo, 0.8f))
        description.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 6f))
        box.addView(description)

        val buttons = LinearLayout(this)
        val play = Ui.button(this, "▶", pal.c(1), Ui.LEFT) { Sfx.playEvent(this, event, true) }
        play.gravity = Gravity.CENTER
        play.contentDescription = "Preview " + Sfx.EVENT_NAMES[event]
        if (!hasSound) play.alpha = 0.4f
        buttons.addView(play, LinearLayout.LayoutParams(0, Ui.dp(this, 42f), 0.8f))
        val choose = Ui.button(this, "CHOOSE SOUND", pal.c(0), Ui.SQUARE) { chooseSound(event) }
        choose.gravity = Gravity.CENTER
        Ui.setTextSp(choose, 13f)
        val chooseParams = LinearLayout.LayoutParams(0, Ui.dp(this, 42f), 2.2f)
        chooseParams.leftMargin = Ui.dp(this, 4f)
        buttons.addView(choose, chooseParams)
        val onOff = Ui.button(this, if (on) "ON" else "OFF", if (on) pal.c(2) else Ui.darken(pal.c(2), 0.4f), Ui.RIGHT) {
            prefs.put("sndOn$event", !Sfx.eventOn(this, event))
            rebuild()
        }
        onOff.gravity = Gravity.CENTER
        Ui.setTextSp(onOff, 13f)
        val onOffParams = LinearLayout.LayoutParams(0, Ui.dp(this, 42f), 0.9f)
        onOffParams.leftMargin = Ui.dp(this, 4f)
        buttons.addView(onOff, onOffParams)
        box.addView(buttons)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = Ui.dp(this, 8f)
        list.addView(box, params)
    }

    /** "IMPORTED: a, b", "LIBRARY: …", "BUILT-IN: …" or "SILENT…" for an event. */
    private fun eventSource(event: Int, variants: Int): String {
        if (variants > 0) {
            return buildString {
                append("IMPORTED: ").append(Sfx.variantName(this@SettingsActivity, event, 0))
                for (i in 1 until variants) append(", ").append(Sfx.variantName(this@SettingsActivity, event, i))
            }
        }
        val builtin = Sfx.builtinFor(this, event)
        if ("none" == builtin) return "SILENT"
        if (Sfx.hasSound(this, event)) {
            return (if (builtin.startsWith("lib:")) "LIBRARY: " else "BUILT-IN: ") + SoundLibrary.label(this, builtin)
        }
        return "SILENT • NOTHING ASSIGNED"
    }

    /** CHOOSE SOUND for an event: silent, tap rotation, any library clip, import, or remove the import. */
    private fun chooseSound(event: Int) {
        val current = if (Sfx.variantCount(this, event) == 0) Sfx.builtinFor(this, event) else null
        val labels = ArrayList<String>()
        val values = ArrayList<String>()
        labels.add((if ("none" == current) "● " else "") + "SILENT")
        values.add("none")
        if (Sfx.hasLibrary(this)) {
            labels.add((if ("#taps" == current) "● " else "") + "TAP ROTATION (7 SOUNDS)" + if ("#taps" == Sfx.defaultFor(event)) " (DEFAULT)" else "")
            values.add("#taps")
        }
        labels.add(if (event == Sfx.E_BUTTON) "IMPORT FILE(S)… (UP TO 8)" else "IMPORT A FILE…")
        values.add("#import")
        if (Sfx.variantCount(this, event) > 0) {
            labels.add("REMOVE IMPORTED FILE")
            values.add("#remove")
        }
        SoundPicker.pick(
            this, Sfx.EVENT_NAMES[event], labels.toTypedArray(), values.toTypedArray(),
            current, Sfx.defaultFor(event),
        ) { picked ->
            if ("#import" == picked) {
                pickFiles(REQ_ASSIGN + event, event == Sfx.E_BUTTON, MIME_AUDIO)
                return@pick
            }
            SoundImporter.clear(this, event)
            if ("#remove" != picked) prefs.put(Sfx.builtinKey(event), picked)
            Sfx.reload(this)
            Sfx.playEvent(this, event, true)
            rebuild()
        }
    }

    /** PHONE SOUNDS: export clips as the phone's ringtone, notification and alarm (Android 10+). */
    private fun phoneSounds() {
        if (!PhoneSounds.available(this)) return
        label("PHONE SOUNDS")
        note("Use the interface clips as your phone's own sounds. SET saves the sound to the phone's Ringtones, Notifications or Alarms folder and makes it the default. Ringtones chirp twice then pause; red alert loops like the ship's klaxon. Your previous sounds are remembered, and RESTORE puts them back.")
        if (!PhoneSounds.canSetDefault(this)) {
            note("To set defaults, TrekUI needs “Modify system settings” (the same switch the shade's brightness slider uses). Without it, SET only saves the sound so you can pick it in Settings › Sounds and vibration.").setTextColor(pal.c(4))
            action("ALLOW MODIFY SYSTEM SETTINGS", pal.c(4)) { openWriteSettings() }
        }
        for (type in PhoneSounds.NAMES.indices) phoneSoundRow(type)
        note("SAVE FOR SPECIFIC APPS saves the notification sound without changing the default, so you can give it to just Messages and Signal (for example INCOMING TRANSMISSION) while everything else keeps its sound.")
        note(
            "Dual SIM: this sets SIM 1's ringtone; pick TrekUI for SIM 2 in Settings › Sounds and vibration › Ringtone." +
                if (isSamsung()) " Samsung Clock keeps a sound per alarm: in Clock, open an alarm › Alarm sound and choose the TrekUI sound." else "",
        )
    }

    /** One phone sound (0 ringtone, 1 notification, 2 alarm): preview, CHOOSE, SET, RESTORE. */
    private fun phoneSoundRow(type: Int) {
        val clip = PhoneSounds.clip(this, type)
        val active = PhoneSounds.isOurs(this, type)
        val color = pal.c(intArrayOf(0, 2, 3)[type])
        val box = card(color, 0.8f)
        box.addView(Ui.bold(this, PhoneSounds.NAMES[type], 16f, color))
        val current = Ui.text(this, SoundLibrary.label(this, clip) + if (active) "  •  ACTIVE" else "", 13f, if (active) pal.c(2) else pal.textHi)
        current.setPadding(0, Ui.dp(this, 2f), 0, Ui.dp(this, 6f))
        box.addView(current)
        val buttons = LinearLayout(this)
        val preview = smallButton("▶", color) { previewClip(clip) }
        preview.contentDescription = "Preview " + PhoneSounds.NAMES[type]
        buttons.addView(preview, LinearLayout.LayoutParams(Ui.dp(this, 52f), Ui.dp(this, 40f)))
        buttons.addView(
            smallButton("CHOOSE", pal.c(1)) {
                SoundPicker.pick(this, PhoneSounds.NAMES[type] + " SOUND", arrayOf(), arrayOf(), PhoneSounds.clip(this, type), null) { picked ->
                    PhoneSounds.setClip(this, type, picked)
                    previewClip(picked)
                    rebuild()
                }
            },
            rowButtonParams(40f, false),
        )
        buttons.addView(
            smallButton(if (active) "SET AGAIN" else "SET", pal.c(0)) { installPhoneSound(type, true, PhoneSounds.clip(this, type)) },
            rowButtonParams(40f, false),
        )
        if (PhoneSounds.hasPrevious(this, type)) {
            buttons.addView(
                smallButton("RESTORE", Ui.darken(pal.c(3), 0.8f)) {
                    val restored = PhoneSounds.restore(this, type)
                    Sfx.play(this, if (restored) Sfx.CONFIRM else Sfx.ERROR)
                    toast(if (restored) "PREVIOUS " + PhoneSounds.NAMES[type] + " RESTORED" else "COULD NOT RESTORE", false)
                    rebuild()
                },
                rowButtonParams(40f, false),
            )
        }
        box.addView(buttons)
        if (type == 1) {
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 40f))
            params.topMargin = Ui.dp(this, 6f)
            box.addView(
                smallButton("SAVE FOR SPECIFIC APPS (KEEP DEFAULT)", pal.c(4)) { installPhoneSound(type, false, PhoneSounds.clip(this, type)) },
                params,
            )
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = Ui.dp(this, 8f)
        list.addView(box, params)
    }

    /**
     * Saves [clip] to the phone's sound folder for [type] on a worker thread and, when
     * [setDefault], makes it the default; then explains the result.
     */
    private fun installPhoneSound(type: Int, setDefault: Boolean, clip: String) {
        toast("SAVING " + PhoneSounds.NAMES[type] + "…", false)
        background("phone-sound") {
            var error: String? = null
            var isDefault = false
            try {
                isDefault = PhoneSounds.install(applicationContext, type, setDefault, clip)
                if (setDefault) PhoneSounds.setClip(applicationContext, type, clip)
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            runOnUiIfAlive {
                Sfx.play(this, if (error == null) Sfx.CONFIRM else Sfx.ERROR)
                val name = PhoneSounds.titleFor(this, clip)
                val title: String
                val message: String
                when {
                    error != null -> {
                        title = "COULD NOT SAVE"
                        message = "Error: $error"
                    }
                    isDefault -> {
                        title = PhoneSounds.NAMES[type] + " SET"
                        message = "“" + name + "” is now your " + PhoneSounds.NAMES[type].lowercase(Locale.US) + " sound."
                    }
                    !setDefault -> {
                        title = "SOUND SAVED"
                        message = "“" + name + "” is saved in your Notifications sounds. Pick it inside each app:\n" + (if (isSamsung()) "• Samsung Messages: ⋮ › Settings › Notifications › New messages › Sound\n" else "") + "• Signal: profile picture › Settings › Notifications › Messages › Sound\n• Any app: Android Settings › Notifications › App notifications › the app › its message category › Sound."
                    }
                    else -> {
                        title = "SOUND SAVED"
                        message = "“" + name + "” was saved. Allow “Modify system settings” to set it automatically, or pick it in Settings › Sounds and vibration."
                    }
                }
                ConsoleDialog.message(this, title, message, arrayOf("OK"), null)
                rebuild()
            }
        }
    }

    /** SOUND LIBRARY: import buttons and collapsible groups of every clip. */
    private fun soundLibrary() {
        label("SOUND LIBRARY")
        note("Every TrekUI sound on the phone. Long-press any TrekUI segment, tile or dock icon › ASSIGN SOUND to give it its own; EXPORT makes one your ringtone, notification or alarm. To add more, download sound files on the phone and import the files or a ZIP here.")
        val importRow = LinearLayout(this)
        importRow.addView(smallButton("IMPORT FILES", pal.c(0)) { pickFiles(REQ_LIB_FILES, true, MIME_AUDIO) }, rowButtonParams(42f, true))
        importRow.addView(smallButton("IMPORT ZIP", pal.c(1)) { pickFiles(REQ_LIB_ZIP, false, MIME_ZIP) }, rowButtonParams(42f, false))
        val importParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        importParams.topMargin = Ui.dp(this, 6f)
        list.addView(importRow, importParams)
        var groupIndex = 0
        for (group in SoundPicker.groups(this)) {
            val open = openSoundGroups.contains(group.name)
            val title = (if (open) "▾ " else "▸ ") + group.name + " • " + SettingsLogic.count(group.items.size, "SOUND", "SOUNDS")
            action(title, pal.c(HomeLogic.accentIndex(groupIndex++))) {
                if (open) openSoundGroups.remove(group.name) else openSoundGroups.add(group.name)
                rebuild()
            }
            if (open) {
                group.items.forEachIndexed { i, entry -> libraryRow(entry, i) }
            }
        }
    }

    /** One library clip: preview, EXPORT, and for imported clips a rename/delete menu. */
    private fun libraryRow(entry: SoundLibrary.Entry, position: Int) {
        val color = pal.c(HomeLogic.accentIndex(position))
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(this, 12f), Ui.dp(this, 4f), Ui.dp(this, 6f), Ui.dp(this, 4f))
        row.background = Ui.outline(this, pal.panel, Ui.alpha(color, if (entry.imported) 0.8f else 0.4f), 12f, 1.2f)
        val name = Ui.bold(this, SoundPicker.nameOf(entry), 14f, if (entry.imported) color else pal.textHi)
        name.isSingleLine = true
        name.ellipsize = TextUtils.TruncateAt.END
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
        val preview = smallButton("▶", color) { previewClip(entry.id) }
        preview.contentDescription = "Preview " + SoundPicker.nameOf(entry)
        row.addView(preview, LinearLayout.LayoutParams(Ui.dp(this, 46f), Ui.dp(this, 36f)))
        val exportParams = LinearLayout.LayoutParams(Ui.dp(this, if (entry.imported) 64f else 80f), Ui.dp(this, 36f))
        exportParams.leftMargin = Ui.dp(this, 6f)
        row.addView(smallButton("EXPORT", pal.c(2)) { exportMenu(entry) }, exportParams)
        if (entry.imported) {
            val moreParams = LinearLayout.LayoutParams(Ui.dp(this, 40f), Ui.dp(this, 36f))
            moreParams.leftMargin = Ui.dp(this, 6f)
            val more = smallButton("⋯", pal.c(3)) { importedClipMenu(entry) }
            more.contentDescription = "More options for " + SoundPicker.nameOf(entry)
            row.addView(more, moreParams)
        }
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.topMargin = Ui.dp(this, 6f)
        list.addView(row, params)
    }

    /** RENAME / DELETE for an imported library clip. */
    private fun importedClipMenu(entry: SoundLibrary.Entry) {
        ConsoleDialog.options(this, entry.label, arrayOf("RENAME", "DELETE")) { which ->
            if (which == 0) {
                ConsoleDialog.prompt(this, "RENAME SOUND", entry.label, "NAME") { text ->
                    if (text.trim().isNotEmpty()) SoundLibrary.rename(this, entry.id, text)
                    rebuild()
                }
                return@options
            }
            SoundLibrary.delete(this, entry.id)
            Sfx.reload(this)
            Sfx.play(this, Sfx.CLOSE)
            rebuild()
        }
    }

    /** EXPORT menu for a library clip (ringtone / notification / alarm, as default or file only). */
    private fun exportMenu(entry: SoundLibrary.Entry) {
        if (!PhoneSounds.available(this)) {
            toast(getString(R.string.toast_export_needs_q), true)
            return
        }
        val options = arrayOf(
            "SET AS RINGTONE", "SET AS NOTIFICATION SOUND", "SET AS ALARM",
            "SAVE FOR SPECIFIC APPS (NOTIFICATION)", "SAVE AS RINGTONE FILE ONLY",
        )
        ConsoleDialog.options(this, "EXPORT • " + entry.label, options) { which ->
            when (which) {
                0 -> installPhoneSound(0, true, entry.id)
                1 -> installPhoneSound(1, true, entry.id)
                2 -> installPhoneSound(2, true, entry.id)
                3 -> installPhoneSound(1, false, entry.id)
                else -> installPhoneSound(0, false, entry.id)
            }
        }
    }

    /**
     * SOUND SET (synthesized / downloaded library / silent) and DOWNLOAD SOUND LIBRARY. The app ships no
     * recordings; the library is downloaded on request from public pages (SoundDownloadUi, docs/SOUNDS.md).
     */
    private fun soundSetAndDownload() {
        val set = Sfx.soundSet(this)
        val current = Sfx.SET_KEYS.indexOf(set)
        val setNames = resources.getStringArray(R.array.sound_set_names)
        action(getString(R.string.sound_set_action, setNames[current]), pal.c(1)) {
            ConsoleDialog.options(this, getString(R.string.sound_set_title), setNames) { which ->
                Sfx.setSoundSet(this, Sfx.SET_KEYS[which])
                Sfx.playEvent(this, Sfx.E_CONFIRM, true)
                rebuild()
            }
        }
        note(resources.getStringArray(R.array.sound_set_descriptions)[current])
        note(SoundDownloadUi.status(this))
        action("DOWNLOAD SOUND LIBRARY", pal.c(0)) { SoundDownloadUi.start(this, false) { rebuild() } }
        action("DOWNLOAD CORE SOUNDS ONLY", pal.c(2)) { SoundDownloadUi.start(this, true) { rebuild() } }
        if (SoundCache.count(this) > 0) {
            action("DELETE DOWNLOADED SOUNDS", Ui.darken(pal.c(3), 0.7f)) { SoundDownloadUi.confirmDelete(this) { rebuild() } }
        }
    }

    /** Plays a clip once in the preview player (library files, resources, or the tap rotation). */
    private fun previewClip(clip: String) {
        try {
            releasePreview()
            when {
                clip.startsWith("lib:") -> previewPlayer = MediaPlayer.create(this, Uri.fromFile(SoundLibrary.file(this, clip)))
                "#taps" == clip -> Sfx.playEvent(this, Sfx.E_BUTTON, true)
                // downloaded, bundled or synthesized library clip (LCA-T8/T15): Sfx resolves cache → res/raw → assets
                else -> Sfx.playClip(this, clip, true)
            }
            previewPlayer?.start()
        } catch (ignored: RuntimeException) {
            // unplayable clip: stay silent
        }
    }

    private fun releasePreview() {
        previewPlayer?.release()
        previewPlayer = null
    }

    /** CHRONOMETER: time format, seconds, stardate and the header text. */
    private fun clockSection() {
        section("CHRONOMETER", pal.c(2), "CLOCK")
        choice("TIME FORMAT", arrayOf("SYSTEM", "12-HOUR", "24-HOUR"), prefs.clockMode()) { which ->
            prefs.put("clock", which)
            changed()
        }
        toggle("SHOW SECONDS", "Ticks every second while the home screen is visible", prefs.seconds()) { on ->
            prefs.put("seconds", on)
            changed()
        }
        toggle("SHOW STARDATE", "Stardate (1000 units per year, 41000.0 at the start of 1987) under the date", prefs.stardate()) { on ->
            prefs.put("stardate", on)
            changed()
        }
        action("EDIT HEADER TEXT: " + prefs.headerText()!!.uppercase(Locale.getDefault()), pal.c(2)) {
            ConsoleDialog.prompt(this, "HEADER TEXT", prefs.headerText(), prefs.headerDefault()) { text ->
                prefs.put(prefs.headerKey(), text)
                changed()
            }
        }
    }

    /** HOME LAYOUT: tiles, dock, panel order/visibility, dialer, console controls, reset. */
    private fun layoutSection() {
        section("HOME LAYOUT", pal.c(4), "LAYOUT")
        choice("SHORTCUT TILES", arrayOf("3", "6", "9", "12"), prefs.tileCount() / 3 - 1) { which ->
            prefs.put("tiles", (which + 1) * 3)
            changed()
        }
        choice("DOCK SLOTS", arrayOf("NONE", "4", "5"), SettingsLogic.dockIndex(prefs.dockCount())) { which ->
            prefs.put("dock", SettingsLogic.DOCK_SIZES[which])
            changed()
        }
        label("PANELS (ORDER & VISIBILITY)")
        val order = prefs.widgetOrder()
        val hidden = prefs.hiddenWidgets()
        for (i in order.indices) panelRow(order, i, hidden.contains(order[i]))
        action(getString(R.string.widget_add_panel_config), pal.c(2)) { WidgetPickActivity.start(this) }.tag = "config_add_widget_panel"
        note(getString(R.string.widget_config_note))
        note("Tip: long-press a tile, dock icon or panel title on the home screen to edit it directly.")
        toggle(
            "PHONE ICON OPENS TREKUI DIALER",
            "Keypad, recents and contacts in TrekUI; calls still use Samsung's in-call screen. Off: the phone icon opens Samsung Phone",
            prefs.b("trekuiDialer", true),
        ) { on ->
            prefs.put("trekuiDialer", on)
            rebuild()
        }
        consoleControls()
        action("RESET SHORTCUTS & DOCK TO DEFAULTS", pal.c(3)) {
            ConsoleDialog.message(
                this, "CONFIRM RESET",
                "Replace all tiles and dock slots with the defaults?", arrayOf("RESET", "CANCEL"),
            ) { which ->
                if (which != 0) return@message
                LayoutSnapshots.snapshot(this, LayoutSnapshots.R_RESET) // LCA-T16
                prefs.sp.edit().remove(MainActivity.KEY_TILES).remove(MainActivity.KEY_DOCK).apply()
                Sfx.play(this, Sfx.CONFIRM)
                changed()
            }
        }
    }

    /**
     * LAYOUT BACKUP (LCA-T16): export / import through the document picker, undo, snapshot now and the
     * automatic snapshots with RESTORE. The flows live in [LayoutBackupUi].
     */
    private fun backupSection() {
        section(getString(R.string.config_backup_section), pal.c(2), getString(R.string.config_backup_sidebar))
        note(getString(R.string.config_backup_note))
        val files = LinearLayout(this)
        files.addView(smallButton(getString(R.string.config_export_layout), pal.c(0)) { LayoutBackupUi.startExport(this) }, rowButtonParams(46f, true))
        files.addView(smallButton(getString(R.string.config_import_layout), pal.c(1)) { LayoutBackupUi.startImport(this) }, rowButtonParams(46f, false))
        files.getChildAt(0).tag = "config_export_layout"
        files.getChildAt(1).tag = "config_import_layout"
        list.addView(files)
        action(getString(R.string.config_undo_layout), pal.c(3)) { LayoutBackupUi.undo(this) { changed() } }.tag = "config_undo_layout"
        action(getString(R.string.config_snapshot_now), pal.c(4)) { LayoutBackupUi.snapshotNow(this) { rebuild() } }.tag = "config_snapshot_now"
        label(getString(R.string.config_snapshots_label))
        note(getString(R.string.config_snapshots_note, LayoutSnapshots.KEEP))
        val snaps = LayoutBackupUi.snapshots(this)
        if (snaps.isEmpty()) note(getString(R.string.config_snapshots_none))
        snaps.forEachIndexed { i, entry ->
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f))
            val name = Ui.text(this, LayoutBackupUi.rowLabel(entry), 15f, pal.textHi)
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
            val restore = smallButton(getString(R.string.config_restore), pal.c(HomeLogic.accentIndex(i))) {
                LayoutBackupUi.restore(this, entry) { changed() }
            }
            restore.tag = "config_restore_$i"
            restore.contentDescription = getString(R.string.config_restore) + " " + LayoutBackupUi.rowLabel(entry)
            row.addView(restore, LinearLayout.LayoutParams(Ui.dp(this, 110f), Ui.dp(this, 44f)))
            list.addView(row)
        }
    }

    /** One panel in PANELS: ▲ ▼ and SHOWN / HIDDEN. */
    private fun panelRow(order: MutableList<String>, index: Int, hidden: Boolean) {
        val id = order[index]
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(
            Ui.oneLine(Ui.bold(this, SettingsLogic.panelTitle(id), 15f, if (hidden) Ui.alpha(pal.textLo, 0.5f) else pal.textHi)),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f),
        )
        val up = Ui.button(this, "▲", pal.c(1), Ui.LEFT) {
            if (index == 0) return@button
            order.removeAt(index)
            order.add(index - 1, id)
            prefs.setWidgetOrder(order)
            rebuild()
        }
        up.gravity = Gravity.CENTER
        up.contentDescription = "Move " + SettingsLogic.panelTitle(id) + " up"
        val down = Ui.button(this, "▼", pal.c(1), Ui.SQUARE) {
            if (index >= order.size - 1) return@button
            order.removeAt(index)
            order.add(index + 1, id)
            prefs.setWidgetOrder(order)
            rebuild()
        }
        down.gravity = Gravity.CENTER
        down.contentDescription = "Move " + SettingsLogic.panelTitle(id) + " down"
        val visibility = Ui.button(this, if (hidden) "HIDDEN" else "SHOWN", if (hidden) Ui.darken(pal.c(3), 0.45f) else pal.c(0), Ui.RIGHT) {
            prefs.setWidgetHidden(id, !hidden)
            rebuild()
        }
        visibility.gravity = Gravity.CENTER
        Ui.setTextSp(visibility, 14f)
        row.addView(up, LinearLayout.LayoutParams(Ui.dp(this, 46f), Ui.dp(this, 44f)))
        val downParams = LinearLayout.LayoutParams(Ui.dp(this, 46f), Ui.dp(this, 44f))
        downParams.leftMargin = Ui.dp(this, 4f)
        row.addView(down, downParams)
        val visibilityParams = LinearLayout.LayoutParams(Ui.dp(this, 84f), Ui.dp(this, 44f))
        visibilityParams.leftMargin = Ui.dp(this, 4f)
        row.addView(visibility, visibilityParams)
        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = Ui.dp(this, 6f)
        list.addView(row, params)
    }

    /** console CONTROLS: every customised home-screen slot with a RESET button. */
    private fun consoleControls() {
        val customised = Tile.customisedSlots(prefs)
        label("TREKUI CONTROLS" + if (customised > 0) "  •  $customised CUSTOMIZED" else "")
        note("Every TrekUI element on the home screen can launch an app or function: the title, ship line, the four code blocks, clock, date, the elbows and their bar segments, the sidebar buttons, OPERATIONS title, footer and dock caps. Long-press one on the home screen to assign, rename or recolor it.")
        val repo = AppRepo.get(this)
        for (slotInfo in Tile.SLOTS) {
            val slot = slotInfo[0]
            val custom = Tile.slot(prefs, slot) ?: continue
            val summary = StringBuilder()
            val action = custom.action
            val comp = custom.comp
            if (action != null) {
                summary.append(Tile.actionLabel(action))
            } else if (comp != null) {
                val app = repo.find(comp, custom.user)
                summary.append(app?.label?.uppercase(Locale.getDefault()) ?: "APP NOT INSTALLED")
            }
            val label = custom.label
            if (!label.isNullOrEmpty()) {
                summary.append(if (summary.isNotEmpty()) " • " else "").append("“").append(label.uppercase(Locale.getDefault())).append("”")
            }
            if (custom.color >= 0) {
                summary.append(if (summary.isNotEmpty()) " • " else "").append("ACCENT ").append(custom.color + 1)
            }
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, Ui.dp(this, 4f), 0, Ui.dp(this, 4f))
            val text = LinearLayout(this)
            text.orientation = LinearLayout.VERTICAL
            text.addView(Ui.bold(this, Tile.slotName(slot), 15f, pal.textHi))
            text.addView(Ui.oneLine(Ui.text(this, summary.toString(), 12f, if (custom.color >= 0) pal.c(custom.color) else pal.c(2))))
            row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
            val reset = Ui.button(this, "RESET", pal.c(3), Ui.PILL) {
                Tile.putSlot(prefs, slot, null)
                rebuild()
            }
            reset.gravity = Gravity.CENTER
            Ui.setTextSp(reset, 13f)
            reset.contentDescription = "Reset " + Tile.slotName(slot)
            row.addView(reset, LinearLayout.LayoutParams(Ui.dp(this, 80f), Ui.dp(this, 40f)))
            list.addView(row)
        }
        if (customised > 0) {
            action("RESET ALL TREKUI CONTROLS", Ui.darken(pal.c(3), 0.7f)) {
                ConsoleDialog.message(
                    this, "RESET TREKUI CONTROLS",
                    "Return every TrekUI element to its default look and behavior?", arrayOf("RESET", "CANCEL"),
                ) { which ->
                    if (which != 0) return@message
                    LayoutSnapshots.snapshot(this, LayoutSnapshots.R_RESET) // LCA-T16
                    Tile.clearSlots(prefs)
                    rebuild()
                }
            }
        }
    }

    /** APP GROUPS (LCA-T20): create, edit, reorder and delete the app groups OPEN GROUP unfolds. */
    private fun groupsSection() {
        section(getString(R.string.cfg_groups_section), pal.c(3), getString(R.string.cfg_groups_sidebar))
        note(getString(R.string.cfg_groups_note))
        val groups = AppGroups.load(prefs)
        groups.forEachIndexed { i, group ->
            val edit = smallButton(getString(R.string.group_edit), pal.c(HomeLogic.accentIndex(i))) {
                GroupEditor.manage(this, group.id) { rebuild() }
            }
            edit.tag = "group_edit_" + group.id
            assignRow(group.name, getString(R.string.group_count, group.apps.size), edit)
        }
        action(getString(R.string.cfg_groups_new), pal.c(0)) { GroupEditor.create(this, { rebuild() }, null) }.tag = "group_new"
    }

    /** GESTURES (LCA-T20): assign home gestures, and set up the optional LOCK SCREEN service. */
    private fun gesturesSection() {
        section(getString(R.string.cfg_gestures_section), pal.c(4), getString(R.string.cfg_gestures_sidebar))
        note(getString(R.string.cfg_gestures_note))
        HomeGestures.ALL.forEachIndexed { i, gesture ->
            val assign = smallButton(getString(R.string.cfg_gesture_assign), pal.c(HomeLogic.accentIndex(i))) {
                GestureSettings.pick(this, gesture) { rebuild() }
            }
            assign.tag = "gesture_$gesture"
            assignRow(GestureSettings.label(this, gesture), GestureSettings.describe(this, gesture), assign)
        }
        if (!LockScreen.supported()) {
            note(getString(R.string.cfg_lock_unsupported))
            return
        }
        val on = LockService.running()
        accessRow(getString(R.string.cfg_lock_title), on, getString(if (on) R.string.cfg_lock_on else R.string.cfg_lock_off))
        action(getString(if (on) R.string.cfg_lock_manage else R.string.cfg_lock_setup), pal.c(if (on) 2 else 0)) {
            if (LockService.running()) LockScreen.openAccessibilitySettings(this) else LockScreen.showDisclosure(this)
        }.tag = "lock_setup"
    }

    /** A row with a title, a smaller description under it and a button on the right (groups, gestures). */
    private fun assignRow(title: String, description: String?, button: TextView) {
        val row = LinearLayout(this)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(this, 6f), 0, Ui.dp(this, 6f))
        val text = LinearLayout(this)
        text.orientation = LinearLayout.VERTICAL
        text.addView(Ui.bold(this, title, 16f, pal.textHi))
        val desc = Ui.text(this, description, 12f, pal.textLo)
        desc.setPadding(0, Ui.dp(this, 2f), Ui.dp(this, 8f), 0)
        text.addView(desc)
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f))
        button.contentDescription = "$title: $description"
        row.addView(button, LinearLayout.LayoutParams(Ui.dp(this, 96f), Ui.dp(this, 44f)))
        list.addView(row)
    }

    /** APP DRAWER: icon style and colour, vivid tiles, columns, uppercase labels and hidden apps. */
    private fun drawerSection() {
        section("APP DRAWER", pal.c(5), "DRAWER")
        val styles = if (Skin.command()) arrayOf("CONSOLE", "FRAMED", "NATIVE") else arrayOf("PICTOGRAM", "FRAMED", "NATIVE", "FACTION ART")
        choice("ICON STYLE", styles, prefs.iconStyle()) { which ->
            prefs.put("icons", which)
            changed()
        }
        note(iconStyleNote())
        iconPackSettings() // LCA-T22
        choice("ICON COLOR", arrayOf("CLASSIC", "VIVID", "VIVID SOLID"), prefs.iconTone()) { which ->
            prefs.put("iconTone", which)
            changed()
        }
        note("CLASSIC: pastel outlines. VIVID: high-saturation outlines and pictograms. VIVID SOLID: filled, high-saturation buttons with black pictograms. Applies to the dock and app drawer.")
        toggle("VIVID SHORTCUT TILES", "Also saturate the home screen's shortcut tiles (the TrekUI frame keeps its classic colors)", prefs.vividTiles()) { on ->
            prefs.put("vividTiles", on)
            changed()
        }
        choice("COLUMNS", arrayOf("3", "4", "5"), prefs.drawerCols() - 3) { which ->
            prefs.put("cols", which + 3)
            changed()
        }
        toggle("UPPERCASE LABELS", null, prefs.upperLabels()) { on ->
            prefs.put("upper", on)
            changed()
        }
        hiddenApps()
    }

    /**
     * ICON PACK (LCA-T22): NONE or an installed icon pack, a preview strip of the chosen pack's
     * icons for installed apps, and AUTO PICTOGRAMS. The pack list comes from a background scan
     * ([IconPacks.refreshInstalled]); the list is rebuilt once when the scan finds something new.
     */
    private fun iconPackSettings() {
        val packs = IconPacks.cachedInstalled().orEmpty()
        IconPacks.refreshInstalled(this) { changedPacks ->
            if (changedPacks == true && !isFinishing && !isDestroyed) rebuild()
        }
        val current = prefs.s(IconPacks.PREF_PACK, "").orEmpty()
        var selected = 0
        val names = Array(packs.size + 1) { i ->
            if (i == 0) {
                getString(R.string.icon_pack_none)
            } else {
                val pack = packs[i - 1]
                if (pack.pkg == current) selected = i
                pack.label.uppercase(Locale.getDefault())
            }
        }
        choice(getString(R.string.icon_pack_label), names, selected) { which ->
            prefs.put(IconPacks.PREF_PACK, if (which == 0) "" else packs[which - 1].pkg)
            IconPacks.clearCache()
            AppRepo.get(this).load()
            changed()
        }
        note(getString(if (packs.isEmpty()) R.string.icon_pack_note_none else R.string.icon_pack_note))
        if (selected > 0) iconPackPreview(current)
        toggle(getString(R.string.icon_auto_glyphs), getString(R.string.icon_auto_glyphs_note), prefs.b(IconPacks.PREF_AUTO_GLYPHS, true)) { on ->
            prefs.put(IconPacks.PREF_AUTO_GLYPHS, on)
            AppRepo.get(this).load()
            changed()
        }
    }

    /** A strip of the pack's icons for a few installed apps, loaded off the main thread. */
    private fun iconPackPreview(packPkg: String) {
        val strip = LinearLayout(this)
        strip.tag = "icon_pack_preview"
        strip.gravity = Gravity.CENTER_VERTICAL
        val count = if (metrics.isTwoPane) 10 else 6
        val cells = ArrayList<ImageView>()
        for (i in 0 until count) {
            val v = ImageView(this)
            v.scaleType = ImageView.ScaleType.FIT_CENTER
            val lp = LinearLayout.LayoutParams(0, Ui.dp(this, 52f), 1f)
            if (i > 0) lp.leftMargin = Ui.dp(this, 6f)
            strip.addView(v, lp)
            cells.add(v)
        }
        val sp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        sp.topMargin = Ui.dp(this, 4f)
        sp.bottomMargin = Ui.dp(this, 8f)
        list.addView(strip, sp)
        val app: Context = applicationContext
        val apps = ArrayList(AppRepo.get(this).apps())
        IconPacks.async({
            val r = IconPacks.load(app, packPkg)
            val out = ArrayList<Drawable>()
            for (a in apps) {
                if (out.size >= count) break
                if (!a.mainProfile) continue
                IconPacks.drawable(app, packPkg, r.drawableFor(a.pkg, a.cn.className))?.let { out.add(it) }
            }
            out
        }) { icons ->
            if (icons == null || isFinishing || isDestroyed) return@async
            for (i in 0 until minOf(icons.size, cells.size)) cells[i].setImageDrawable(icons[i])
        }
    }

    /** HIDDEN APPS with UNHIDE buttons. */
    private fun hiddenApps() {
        val hidden = prefs.hiddenApps()
        label("HIDDEN APPS" + if (hidden.isEmpty()) ": NONE" else "")
        if (hidden.isEmpty()) {
            note("Hide an app by long-pressing it in the drawer.")
            return
        }
        val repo = AppRepo.get(this)
        for (key in hidden) {
            val app = repo.apps().firstOrNull { it.key == key }
            val name = app?.label ?: SettingsLogic.hiddenAppName(key)
            val row = LinearLayout(this)
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(
                Ui.oneLine(Ui.bold(this, name.uppercase(Locale.getDefault()), 15f, pal.textHi)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f),
            )
            val unhide = Ui.button(this, "UNHIDE", pal.c(2), Ui.PILL) {
                prefs.setAppHidden(key, false)
                Sfx.play(this, Sfx.CONFIRM)
                changed()
            }
            unhide.gravity = Gravity.CENTER
            unhide.contentDescription = "Unhide $name"
            row.addView(unhide, LinearLayout.LayoutParams(Ui.dp(this, 96f), Ui.dp(this, 44f)))
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.bottomMargin = Ui.dp(this, 6f)
            list.addView(row, params)
        }
    }

    /** WEATHER: location summary, units and clearing the location. */
    private fun weatherSection() {
        section("WEATHER", pal.c(2), "WEATHER")
        val mode = prefs.i("wxMode", 0)
        note(
            if (mode == 0) {
                "LOCATION: NOT SET • Tap SET LOCATION on the home-screen weather panel."
            } else {
                "LOCATION: " + prefs.s("wxName", "?")!!.uppercase(Locale.getDefault()) + (if (mode == 1) " (DEVICE)" else " (CITY)") +
                    "\nChange it by long-pressing the WEATHER panel title."
            },
        )
        choice("UNITS", arrayOf("°F / MPH", "°C / KM/H"), if (prefs.fahrenheit()) 0 else 1) { which ->
            prefs.put("wxF", which == 0)
            prefs.put("wxTime", 0L)
            changed()
        }
        if (mode != 0) {
            action("CLEAR WEATHER LOCATION", pal.c(3)) {
                prefs.sp.edit().remove("wxMode").remove("wxLat").remove("wxLon").remove("wxName").remove("wxCache").remove("wxTime").apply()
                changed()
            }
        }
    }

    /** INTERFACE &amp; ACCESS: animations, startup sequence, cloak, haptics and permission status. */
    private fun accessSection() {
        section("INTERFACE & ACCESS", pal.c(0), "ACCESS")
        toggle("ANIMATIONS", "Elbow sweeps, data cascade, transitions (also follows Android's animation setting)", prefs.animations()) { on ->
            prefs.put("anim", on)
            changed()
        }
        toggle(
            "STARTUP SEQUENCE", "A three-second TrekUI power-up the first time the home screen loads after the phone restarts (tap to skip)",
            prefs.b("bootSeq", true),
        ) { on ->
            prefs.put("bootSeq", on)
            rebuild()
        }
        action("PREVIEW STARTUP SEQUENCE", pal.c(2)) {
            prefs.put("bootPreview", true)
            quietFinish = true
            finish()
        }
        cloakControls()
        redAlertControls()
        quickSettingsTiles()
        toggle("HAPTIC FEEDBACK", "Light vibration on TrekUI buttons", prefs.haptics()) { on ->
            prefs.put("haptics", on)
            changed()
        }
        accessRow("LOCATION (WEATHER)", granted(Manifest.permission.ACCESS_COARSE_LOCATION), "Optional • only if you choose device location")
        accessRow("CALENDAR (SCHEDULE)", granted(Manifest.permission.READ_CALENDAR), "Optional • read-only")
        accessRow("PHONE CALLS (TREKUI DIALER)", granted(Manifest.permission.CALL_PHONE), "Optional • without it the dialer hands numbers to Samsung Phone")
        accessRow("CONTACTS (TREKUI DIALER)", granted(Manifest.permission.READ_CONTACTS), "Optional • read-only")
        accessRow("CALL HISTORY (TREKUI DIALER)", granted(Manifest.permission.READ_CALL_LOG), "Optional • read-only")
        val listenerOn = notificationAccess()
        accessRow("NOTIFICATION ACCESS (TRACK INFO + TREKUI SHADE)", listenerOn, "Optional • media controls work without it")
        action("OPEN TREKUI APP INFO / PERMISSIONS", pal.c(1)) {
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        if (!listenerOn) {
            action("OPEN NOTIFICATION ACCESS", pal.c(2)) { openNotificationAccess() }
        }
    }

    /** CLOAK (NIGHT MODE): engage now, duration, brightness and sounds. */
    private fun cloakControls() {
        label("CLOAK (NIGHT MODE)")
        val active = Cloak.active(this)
        note("For browsing in bed: the TrekUI interface goes dark (black cells with coloured outlines and orange lettering), interface sounds drop to 10%, and the screen brightness goes right down. It disengages by itself after the time below and puts your brightness back (unless you changed it meanwhile). Assign it to a segment, tile or dock icon: long-press › ASSIGN TREKUI FUNCTION › CLOAK (NIGHT MODE). Tap again to decloak early.")
        action(
            if (active) "DISENGAGE CLOAK • " + SettingsLogic.minutesLeft(Cloak.remainingMs(this)) + " MIN LEFT" else "ENGAGE CLOAK NOW",
            if (active) pal.c(2) else pal.c(5),
        ) {
            Cloak.toggle(this)
            rebuild()
        }
        val durations = Array(Cloak.MINUTES.size) { Cloak.MINUTES[it].toString() + " MIN" }
        choice("CLOAK LASTS", durations, maxOf(0, Cloak.MINUTES.binarySearch(Cloak.minutes(prefs)))) { which ->
            prefs.put("cloakMinutes", Cloak.MINUTES[which])
            rebuild()
        }
        val levels = Array(Cloak.LEVELS.size) { Cloak.LEVELS[it].toString() + "%" }
        choice("CLOAKED SCREEN BRIGHTNESS", levels, maxOf(0, Cloak.LEVELS.binarySearch(Cloak.level(prefs)))) { which ->
            prefs.put("cloakLevel", Cloak.LEVELS[which])
            rebuild()
        }
        cloakSoundRow("ENGAGE SOUND", "cloakSoundOn", Cloak.engageSound(prefs))
        cloakSoundRow("DISENGAGE SOUND", "cloakSoundOff", Cloak.disengageSound(prefs))
        if (!Cloak.canDimSystem(this)) {
            note("Allow “Modify system settings” (ACCESS section) so cloak can dim the whole phone; without it, only the TrekUI screen dims. For even darker, add Samsung's Extra dim to your quick panel.").setTextColor(pal.c(4))
        }
    }

    /** RED ALERT: engage / stand down now, auto-timeout and the looping sound switch (LCA-T17). */
    private fun redAlertControls() {
        label(getString(R.string.red_alert))
        note(getString(R.string.red_alert_settings_note))
        val active = RedAlert.active(this)
        action(
            if (active) {
                getString(R.string.red_alert_stand_down_now, RedAlertLogic.countdown(RedAlert.remainingMs(this)))
            } else {
                getString(R.string.red_alert_engage_now)
            },
            if (active) pal.c(2) else pal.alert,
        ) {
            RedAlert.toggle(this)
            rebuild()
        }
        val timeouts = Array(RedAlertLogic.TIMEOUT_SECONDS.size) { RedAlertLogic.timeoutLabel(RedAlertLogic.TIMEOUT_SECONDS[it]) }
        choice(getString(R.string.red_alert_lasts), timeouts, RedAlertLogic.timeoutIndex(RedAlert.timeoutSeconds(prefs))) { which ->
            prefs.put(RedAlert.KEY_TIMEOUT, RedAlertLogic.TIMEOUT_SECONDS[which])
            rebuild()
        }
        toggle(getString(R.string.red_alert_sound), getString(R.string.red_alert_sound_desc), RedAlert.soundOn(prefs)) { on ->
            prefs.put(RedAlert.KEY_SOUND, on)
            RedAlert.check(this)
            rebuild()
        }
    }

    /** QUICK SETTINGS TILES: the system "add tile" prompt on Android 13+, instructions below. */
    private fun quickSettingsTiles() {
        label(getString(R.string.qs_section))
        note(getString(R.string.qs_section_note))
        if (!QsTiles.canRequestAdd()) {
            note(getString(R.string.qs_section_note_old))
            return
        }
        action(getString(R.string.qs_add_cloak), pal.c(5)) {
            QsTiles.requestAdd(this, CloakTileService::class.java, R.string.qs_cloak, R.drawable.ic_qs_cloak) { code -> tileAddResult(code) }
        }
        action(getString(R.string.qs_add_red_alert), pal.c(3)) {
            QsTiles.requestAdd(this, RedAlertTileService::class.java, R.string.qs_red_alert, R.drawable.ic_qs_red_alert) { code -> tileAddResult(code) }
        }
    }

    /** Toast for the result of [QsTiles.requestAdd] (StatusBarManager TILE_ADD_REQUEST_RESULT_* codes). */
    private fun tileAddResult(code: Int) {
        val message = when (code) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.qs_added
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.qs_already_added
            -1 -> R.string.toast_not_available
            else -> R.string.qs_not_added
        }
        toast(getString(message), false)
    }

    /** Sound picker row for cloak engage / disengage. */
    private fun cloakSoundRow(title: String, key: String, currentClip: String?) {
        action(title + " • " + SoundLibrary.label(this, currentClip), pal.c(3)) {
            val current = prefs.s(key, null)
            val labels = arrayOf((if ("none" == current) "● " else "") + "SILENT")
            SoundPicker.pick(this, title, labels, arrayOf("none"), current, null) { picked ->
                prefs.put(key, picked)
                if ("none" != picked) previewClip(picked)
                rebuild()
            }
        }
    }

    /** ABOUT: version, credits and privacy. */
    private fun aboutSection() {
        section("ABOUT", pal.c(1), "ABOUT")
        note("TrekUI Launcher " + version() + ". An original science-fiction console interface for Android.\n\nFonts: SIL Open Font License 1.1 (notices in the app's font folder). Weather data: Open-Meteo.com (CC BY 4.0). Sounds: synthesized by the app; optional recordings are downloaded or imported by you, for your personal use.\n\nPrivacy: no accounts, no analytics. The only network traffic is the weather request (coordinates only) and the services you connect yourself.")
    }

    // =================================================================================================
    // Files, export, import
    // =================================================================================================

    /** Opens the system document picker for audio files or a ZIP. */
    @Suppress("DEPRECATION")
    private fun pickFiles(requestCode: Int, multiple: Boolean, mimeType: String) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = mimeType
        if (MIME_ZIP == mimeType) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(MIME_ZIP, "application/x-zip-compressed", "application/octet-stream"))
        }
        if (multiple) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            startActivityForResult(intent, requestCode)
        } catch (e: RuntimeException) {
            toast(getString(R.string.toast_no_file_picker), false)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (LayoutBackupUi.onResult(this, requestCode, resultCode, data) { changed() }) return // LCA-T16
        if (requestCode == MainActivity.REQ_HOME_ROLE) {
            if (MainActivity.isDefaultHome(this)) Sfx.play(this, Sfx.STATUS_OK)
            return
        }
        if (resultCode != RESULT_OK || data == null) return
        val uris = pickedUris(data)
        if (uris.isEmpty()) return
        val progress = ConsoleDialog.message(this, "PROCESSING AUDIO", "Decoding, trimming and level-matching…", arrayOf(), null)
        progress.setCancelable(false)
        background("sound-import") {
            val report = importSounds(requestCode, uris)
            runOnUiIfAlive {
                try {
                    progress.dismiss()
                } catch (ignored: RuntimeException) {
                    // window already gone
                }
                Sfx.reload(this)
                showReport(report)
                rebuild()
            }
        }
    }

    /** Imports picked files on a worker thread, according to which button asked for them. */
    private fun importSounds(requestCode: Int, uris: List<Uri>): SoundImporter.Report {
        val report = SoundImporter.Report()
        try {
            if (requestCode == REQ_LIB_FILES || requestCode == REQ_LIB_ZIP) {
                val items = if (requestCode == REQ_LIB_ZIP) SoundImporter.unzip(this, uris[0]) else itemsFor(uris)
                val result = SoundLibrary.importItems(this, items)
                report.lines.addAll(result.lines)
                report.failed.addAll(result.failed)
                report.assigned = result.assigned
                if (requestCode == REQ_LIB_ZIP) SoundImporter.deleteTree(File(cacheDir, IMPORT_CACHE_DIR))
                if (report.assigned > 0) report.lines.add(0, "ADDED TO THE SOUND LIBRARY:")
            } else if (requestCode == REQ_IMPORT_ZIP) {
                val zip = uris[0]
                val result = SoundImporter.importAuto(this, SoundImporter.unzip(this, zip), SoundImporter.displayName(this, zip))
                copyReport(result, report)
                SoundImporter.deleteTree(File(cacheDir, IMPORT_CACHE_DIR))
            } else {
                val items = itemsFor(uris)
                val source = if (items.size == 1) items[0].name else "${items.size} SELECTED FILES"
                if (requestCode >= REQ_ASSIGN && requestCode < REQ_ASSIGN + Sfx.EVENTS) {
                    val event = requestCode - REQ_ASSIGN
                    val assigned = SoundImporter.assign(this, event, items, source, report)
                    if (assigned > 0) {
                        report.assigned = 1
                        report.lines.add(Sfx.EVENT_NAMES[event] + "  ←  " + items[0].name + if (assigned > 1) "  +" + (assigned - 1) else "")
                    }
                } else {
                    copyReport(SoundImporter.importAuto(this, items, source), report)
                }
            }
        } catch (t: Throwable) {
            report.failed.add(t.message ?: t.toString())
        }
        return report
    }

    private fun itemsFor(uris: List<Uri>): List<SoundImporter.Item> =
        uris.map { SoundImporter.Item(SoundImporter.displayName(this, it), it, null) }

    /** The import result dialog. */
    private fun showReport(report: SoundImporter.Report) {
        ConsoleDialog.message(
            this, if (report.assigned > 0) "IMPORT COMPLETE" else "IMPORT RESULT",
            SettingsLogic.importReport(report.lines, report.unmatched, report.failed), arrayOf("OK"), null,
        )
        if (report.assigned > 0) Sfx.playEvent(this, Sfx.E_CONFIRM, true)
    }

    /** EXPORT console PANEL ART: copies the theme's Quick Panel images to the gallery. */
    private fun exportPanelArt() {
        val theme = themeIndex()
        val listed: Array<String>? = try {
            assets.list("qp/$theme")
        } catch (e: Exception) {
            null
        }
        val names = listed ?: arrayOf()
        val files = Array(names.size) { arrayOf("qp/$theme/" + names[it], names[it]) }
        exportImages(
            files, "Pictures/TrekUI Quick Panel/" + themeFolder(theme) + "/", "ART EXPORTED",
            "In QuickStar › Edit panel style, pick each element and choose the matching image from your Gallery:\n• Button box → qp_button_box\n• Buttons → qp_button_1 … 8 (wide buttons → qp_button_wide)\n• Brightness → qp_brightness\n• Volume → qp_volume\n• Media player → qp_media_player",
        )
    }

    /**
     * Selected Command colour theme, as the index of the bundled app-theme / Quick Panel art set that
     * fits it (art exists for the first six themes; the 6.3 themes borrow the closest one).
     */
    private fun themeIndex(): Int = SubThemes.commandArtIndex(prefs.subTheme(Skin.COMMAND))

    /**
     * Copies asset images into the shared gallery (MediaStore, API 29+) under [folder], replacing
     * earlier exports with the same name, then shows [doneTitle] / [doneHelp].
     *
     * @param files pairs of {asset path, display name}
     */
    private fun exportImages(files: Array<Array<String>>, folder: String, doneTitle: String, doneHelp: String) {
        if (Build.VERSION.SDK_INT < 29) {
            toast(getString(R.string.toast_export_needs_q), true)
            return
        }
        toast(getString(R.string.toast_exporting), false)
        background("export") {
            var count = 0
            var error: String? = null
            val resolver = contentResolver
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            for (file in files) {
                try {
                    if (exportImage(resolver, collection, file[0], file[1], folder)) count++
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                }
            }
            runOnUiIfAlive {
                Sfx.play(this, if (count > 0) Sfx.CONFIRM else Sfx.ERROR)
                val message = if (count > 0) {
                    SettingsLogic.count(count, "image", "images") + " saved to " + folder + "\n\n" + doneHelp
                } else {
                    "Could not save images" + if (error != null) ": $error" else "."
                }
                ConsoleDialog.message(this, if (count > 0) doneTitle else "EXPORT FAILED", message, arrayOf("OK"), null)
            }
        }
    }

    /**
     * Writes one asset image into MediaStore as a pending item, publishing it when complete. A failed
     * copy removes its half-written entry instead of leaving a broken pending image behind.
     */
    @TargetApi(29)
    private fun exportImage(resolver: ContentResolver, collection: Uri, asset: String, name: String, folder: String): Boolean {
        try {
            resolver.delete(
                collection, MediaStore.MediaColumns.RELATIVE_PATH + "=? AND " + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                arrayOf(folder, name),
            )
        } catch (ignored: RuntimeException) {
            // nothing to replace, or not ours to delete
        }
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".gif")) "image/gif" else "image/png")
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
        values.put(MediaStore.MediaColumns.IS_PENDING, 1)
        val item = resolver.insert(collection, values) ?: return false
        var complete = false
        try {
            assets.open(asset).use { input ->
                val out = resolver.openOutputStream(item) ?: return false
                out.use {
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        it.write(buffer, 0, read)
                    }
                }
            }
            complete = true
        } finally {
            if (!complete) {
                try {
                    resolver.delete(item, null, null)
                } catch (ignored: RuntimeException) {
                    // best effort
                }
            }
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(item, values, null, null)
        return true
    }

    // =================================================================================================
    // Small helpers
    // =================================================================================================

    /** Opens Good Lock, or its Galaxy Store page (app, then web). */
    private fun openGoodLock() {
        val launch = packageManager.getLaunchIntentForPackage(PKG_GOOD_LOCK)
        if (launch != null) {
            open(launch)
        } else if (!tryStart(Intent(Intent.ACTION_VIEW, Uri.parse("samsungapps://ProductDetail/$PKG_GOOD_LOCK")))) {
            openUrl("https://galaxystore.samsung.com/detail/$PKG_GOOD_LOCK")
        }
    }

    /** "Modify system settings" for this app. */
    private fun openWriteSettings() {
        open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    /** Notification access: straight to console's own toggle on API 30+, else the full list. */
    private fun openNotificationAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(this, MediaListener::class.java).flattenToString(),
                )
            if (tryStart(detail)) return
        }
        open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun openUrl(url: String) {
        open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    /** Starts an activity; false when nothing handles the intent. */
    private fun tryStart(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: RuntimeException) {
        false
    }

    /** Starts an activity or explains that the device has nothing for it. */
    private fun open(intent: Intent) {
        if (!tryStart(intent)) {
            Sfx.play(this, Sfx.ERROR)
            toast(getString(R.string.toast_not_available), false)
        }
    }

    /** True when a package is installed and visible to us (see the manifest's &lt;queries&gt;). */
    private fun installed(pkg: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            legacyApplicationInfo(pkg)
        }
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun legacyApplicationInfo(pkg: String) = packageManager.getApplicationInfo(pkg, 0)

    private fun granted(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** True when the user granted notification access to [MediaListener]. */
    private fun notificationAccess(): Boolean = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    /** A down-sampled asset image (cached), or null when it is missing. */
    private fun thumbAsset(path: String, sampleSize: Int): Bitmap? {
        val key = "$path@$sampleSize"
        THUMBS.get(key)?.let { return it }
        return try {
            assets.open(path).use { input ->
                val options = BitmapFactory.Options()
                options.inSampleSize = sampleSize
                BitmapFactory.decodeStream(input, null, options)?.also { THUMBS.put(key, it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        internal const val PKG_CHROME = "com.android.chrome"
        internal const val PKG_OUTLOOK = "com.microsoft.office.outlook"
        internal const val PKG_SIGNAL = "org.thoughtcrime.securesms"
        private const val PKG_GOOD_LOCK = "com.samsung.android.goodlock"

        // ---- activity request codes ----------------------------------------------------------------------
        private const val REQ_IMPORT_FILES = 300
        private const val REQ_IMPORT_ZIP = 301

        /** REQ_ASSIGN + event index: import a file for one sound event. */
        private const val REQ_ASSIGN = 310
        private const val REQ_LIB_FILES = 320
        private const val REQ_LIB_ZIP = 321

        private const val MIME_AUDIO = "audio/*"
        private const val MIME_ZIP = "application/zip"
        private const val IMPORT_CACHE_DIR = "trekui_import"

        private val WALLPAPERS = arrayOf("command", "minimal", "stellar")
        private val WALLPAPER_NAMES = arrayOf("COMMAND", "MINIMAL", "STELLAR")
        private const val THUMB_SAMPLE = 8

        /** Decoded asset thumbnails; bitmaps only (no Activity reference), so a static cache is safe. */
        private val THUMBS = LruCache<String, Bitmap>(24)

        /** Sound-library groups the user has expanded (kept for the process lifetime). */
        private val openSoundGroups = HashSet<String>()

        /** Accent colours of the interface cards (Command, Imperial, Collective). */
        private const val COMMAND_ACCENT = 0xFFF0B279.toInt()
        private const val IMPERIAL_ACCENT = 0xFFE6A15C.toInt()
        private const val COLLECTIVE_ACCENT = 0xFF84D88E.toInt()

        /** Interface-card accents of the 23rd-century (console amber) and 25th-century (fleet steel blue) eras. */
        private const val ERA23_ACCENT = 0xFFF2C230.toInt()
        private const val ERA25_ACCENT = 0xFF8FB4D8.toInt()

        /** Sections built before the first frame: what fills the first screen. */
        private const val EAGER_SECTIONS = 3

        /** Home Assistant entities listed with a CONTROL key in CONFIG (all are offered when assigning tiles). */
        private const val HA_ROWS_SHOWN = 24

        /** Starts a named background worker. */
        private fun background(name: String, work: () -> Unit) {
            Thread({ work() }, "trekui-config-$name").start()
        }

        /** Note shown instead of the theme list for interfaces whose colours are fixed. */
        private fun fixedColoursNote(): String = if (Skin.imperial()) {
            "Imperial colours are fixed: red, orange, gold and copper on near-black."
        } else {
            "Collective colours are fixed: matrix greens on near-black."
        }

        /** Explanation of the ICON STYLE options for the current interface. */
        private fun iconStyleNote(): String {
            if (Skin.command()) {
                return "CONSOLE: themed pictograms for every app the launcher recognises (AUTO PICTOGRAMS), console frames for the rest. FRAMED: every app icon in a console frame. NATIVE: original icons."
            }
            return "PICTOGRAM: themed pictograms for every app the launcher recognises in " + (if (Skin.imperial()) "octagon" else "disk") + " frames, framed icons for the rest. FRAMED: every app icon framed. NATIVE: original icons. FACTION ART: the lettered badges from the " + (if (Skin.imperial()) "Imperial" else "Collective") + " artwork for the 24 core apps (PH, ME, BR…), framed icons for the rest."
        }

        /** Accent colour of an interface card. */
        private fun factionAccent(faction: Int): Int = when (faction) {
            0 -> COMMAND_ACCENT
            1 -> IMPERIAL_ACCENT
            2 -> COLLECTIVE_ACCENT
            Skin.ERA23 -> ERA23_ACCENT
            Skin.ERA25 -> ERA25_ACCENT
            else -> COMMAND_ACCENT
        }

        /** All URIs returned by a document picker (multi-select or single). */
        private fun pickedUris(data: Intent): List<Uri> {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            val single = data.data
            if (clip != null) {
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            } else if (single != null) {
                uris.add(single)
            }
            return uris
        }

        private fun copyReport(from: SoundImporter.Report, to: SoundImporter.Report) {
            to.lines.addAll(from.lines)
            to.unmatched.addAll(from.unmatched)
            to.failed.addAll(from.failed)
            to.assigned = from.assigned
        }

        private fun themeFolder(theme: Int): String = Palette.ALL[theme].name.replace('-', ' ')

        /** Samsung / One UI device: gates the Samsung-only (One UI, Good Lock) notes and recipes. */
        private fun isSamsung(): Boolean = "samsung".equals(Build.MANUFACTURER, ignoreCase = true)
    }
}
