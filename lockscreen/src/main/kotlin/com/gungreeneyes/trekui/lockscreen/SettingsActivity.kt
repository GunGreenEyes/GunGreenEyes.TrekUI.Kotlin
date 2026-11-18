package com.gungreeneyes.trekui.lockscreen

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gungreeneyes.trekui.LockLook
import com.gungreeneyes.trekui.ScreenMetrics
import com.gungreeneyes.trekui.TrekPrefs
import com.gungreeneyes.trekui.licensing.Licensing
import com.gungreeneyes.trekui.lockscreen.logic.EnableRule
import kotlin.math.max
import kotlin.math.min

/**
 * The app's main screen (its launcher icon): setup and settings for the wake screen, drawn in the active
 * interface's console frame.
 *
 * Rows: WAKE SCREEN (the app's own switch, with the launcher's SUITE › LOCK SCREEN state explained),
 * NOTIFICATION ACCESS (setup flow: a prominent disclosure, then Android's notification-access screen),
 * DISPLAY OVER APPS (Android 10+: lets the listener start the wake screen from the background), SENSITIVE
 * CONTENT, INTERFACE / COLOR THEME (own picker only while the launcher is absent), PREVIEW and the licence.
 * Licensing runs here ([Licensing.init] / [Licensing.onAppResumed]), never on the wake screen.
 */
class SettingsActivity : ComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var metrics: ScreenMetrics

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        Licensing.init(this)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        Licensing.onAppResumed(this)
        LockTheme.refresh(this, ::rebuild)
        LockSettings.refreshLauncherFlag(this, ::rebuild)
        LockListener.refreshNow()
        rebuild()
        if (!LockSettings.hasNotificationAccess(this) && !LockSettings.setupSeen(this)) {
            LockSettings.markSetupSeen(this)
            showAccessDisclosure()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuild()
    }

    /** Rebuilds the whole screen from the current state (cheap: a handful of rows). */
    private fun rebuild() {
        if (isFinishing || isDestroyed) return
        LockLook.load(this)
        metrics = ScreenMetrics.of(this)
        root = FrameLayout(this)
        root.setBackgroundColor(LockLook.bg())
        root.setOnApplyWindowInsetsListener { view, raw ->
            val bars = WindowInsetsCompat.toWindowInsetsCompat(raw, view)
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val side = if (metrics.isTwoPane) metrics.marginPx() else LockLook.dp(this, 8f)
            root.setPadding(
                bars.left + side, bars.top + LockLook.dp(this, 4f), bars.right + side,
                bars.bottom + LockLook.dp(this, 6f),
            )
            raw
        }

        val title = LockLook.oneLine(LockLook.bold(this, getString(R.string.settings_title), 28f, LockLook.accent(0)))
        title.setPadding(LockLook.dp(this, 4f), 0, LockLook.dp(this, 2f), 0)
        val footer = LockLook.oneLine(
            LockLook.text(this, getString(R.string.settings_footer, version()), 14f, LockLook.accent(1)),
        )
        footer.setPadding(LockLook.dp(this, 4f), 0, LockLook.dp(this, 2f), 0)
        val frame = LockLook.frame(this, title, footer, sidebar(), content(), SIDEBAR_DP)
        root.addView(
            frame,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun sidebar(): View {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        addSide(column, getString(R.string.settings_preview), 2, "side_preview") { openPreview() }
        addSide(column, getString(R.string.settings_setup), 3, "side_setup") { showAccessDisclosure() }
        addSide(column, getString(R.string.settings_unlock), 4, "side_unlock") { Licensing.openPurchaseScreen(this) }
        val scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(column)
        return scroll
    }

    private fun addSide(column: LinearLayout, label: String, accent: Int, tag: String, l: View.OnClickListener) {
        val color = LockLook.accent(accent)
        val v = LockLook.button(this, label, color, LockLook.SQUARE, l)
        v.background = LockLook.touchCell(this, color, LockLook.SQUARE, 0f)
        LockLook.setTextSp(v, 14f)
        v.setPadding(LockLook.dp(this, 4f), 0, LockLook.dp(this, 8f), LockLook.dp(this, 4f))
        v.tag = tag
        v.contentDescription = label
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, LockLook.dp(this, 50f))
        if (column.childCount > 0) lp.topMargin = LockLook.dp(this, LockLook.GUTTER.toFloat())
        column.addView(v, lp)
    }

    // ---- rows -------------------------------------------------------------------------------------

    private fun content(): View {
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(LockLook.dp(this, 4f), LockLook.dp(this, 4f), LockLook.dp(this, 4f), LockLook.dp(this, 12f))

        val own = LockSettings.wakeEnabled(this)
        row(
            list, "row_wake", getString(R.string.row_wake), stateText(LockSettings.state(this)),
            getString(if (own) R.string.action_turn_off else R.string.action_turn_on), 0,
        ) {
            LockSettings.setWakeEnabled(this, !own)
            LockListener.resetTrigger()
            rebuild()
        }

        val access = LockSettings.hasNotificationAccess(this)
        row(
            list, "row_access", getString(R.string.row_access),
            getString(if (access) R.string.access_on else R.string.access_off),
            getString(if (access) R.string.action_manage else R.string.action_set_up), 1,
        ) {
            if (access) openAccessSettings() else showAccessDisclosure()
        }

        if (Build.VERSION.SDK_INT >= 29) {
            val overlay = LockSettings.canDrawOverlays(this)
            row(
                list, "row_overlay", getString(R.string.row_overlay),
                getString(if (overlay) R.string.overlay_on else R.string.overlay_off),
                getString(if (overlay) R.string.action_manage else R.string.action_allow), 2,
            ) { openOverlaySettings() }
        }

        val sensitive = LockSettings.showSensitive(this)
        row(
            list, "row_sensitive", getString(R.string.row_sensitive),
            getString(if (sensitive) R.string.sensitive_on else R.string.sensitive_off),
            getString(if (sensitive) R.string.action_hide else R.string.action_show), 3,
        ) {
            LockSettings.setShowSensitive(this, !sensitive)
            LockListener.refreshNow()
            rebuild()
        }

        val faction = TrekPrefs.get(this).faction()
        val names = LockLook.interfaceNames()
        val current = names[LockLook.interfaceIndex(faction)]
        if (LockTheme.followingLauncher()) {
            row(
                list, "row_interface", getString(R.string.row_interface),
                getString(R.string.interface_following, current), null, 4, null,
            )
        } else {
            row(list, "row_interface", getString(R.string.row_interface), current, getString(R.string.action_change), 4) {
                LockLook.options(this, getString(R.string.row_interface), names) { which ->
                    TrekPrefs.get(this).setFaction(LockLook.interfaceAt(which))
                    rebuild()
                }
            }
            if (LockLook.hasSubThemes(faction)) {
                val subs = LockLook.subThemeNames(faction)
                val sub = TrekPrefs.get(this).subTheme(faction)
                row(
                    list, "row_color", getString(R.string.row_color), subs[max(0, min(subs.size - 1, sub))],
                    getString(R.string.action_change), 5,
                ) {
                    LockLook.options(this, getString(R.string.row_color), subs) { which ->
                        TrekPrefs.get(this).setSubTheme(faction, which)
                        rebuild()
                    }
                }
            }
        }

        val unlocked = Licensing.isUnlocked(this)
        row(
            list, "row_licence", getString(R.string.row_licence),
            getString(if (unlocked) R.string.licence_unlocked else R.string.licence_trial),
            if (unlocked) null else getString(R.string.settings_unlock), 6,
        ) { Licensing.openPurchaseScreen(this) }

        val note = LockLook.text(this, getString(R.string.settings_security_note), 14f, LockLook.textLo())
        note.setPadding(0, LockLook.dp(this, 12f), 0, 0)
        list.addView(note)

        val scroll = ScrollView(this)
        scroll.isFillViewport = true
        scroll.addView(list)
        return scroll
    }

    /** One setting: a title, a status line and an optional action key on the right. */
    private fun row(
        list: LinearLayout, tag: String, title: String, status: String, action: String?, accent: Int,
        l: View.OnClickListener?,
    ) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.tag = tag
        val pad = LockLook.dp(this, 10f)
        row.setPadding(pad, pad, pad, pad)
        row.background = LockLook.outline(this, LockLook.surface(), LockLook.grid(), 8f, 1f)

        val texts = LinearLayout(this)
        texts.orientation = LinearLayout.VERTICAL
        texts.addView(LockLook.oneLine(LockLook.bold(this, LockLook.up(this, title), 17f, LockLook.accent(accent))))
        val st = LockLook.text(this, status, 14f, LockLook.textHi())
        st.setLineSpacing(0f, 1.1f)
        texts.addView(st)
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (action != null && l != null) {
            val color = LockLook.accent(accent)
            val b = LockLook.button(this, LockLook.up(this, action), color, LockLook.PILL, l)
            LockLook.setTextSp(b, 14f)
            b.tag = tag + "_action"
            b.contentDescription = "$title: $action"
            b.minWidth = LockLook.dp(this, 96f)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, LockLook.dp(this, LockLook.MIN_TOUCH_DP))
            lp.leftMargin = LockLook.dp(this, 10f)
            row.addView(b, lp)
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = LockLook.dp(this, LockLook.S.toFloat())
        list.addView(row, lp)
    }

    private fun stateText(s: EnableRule.State): String = when (s) {
        EnableRule.State.ACTIVE -> getString(
            if (Build.VERSION.SDK_INT >= 29 && !LockSettings.canDrawOverlays(this)) {
                R.string.state_active_needs_overlay
            } else {
                R.string.state_active
            },
        )
        EnableRule.State.OFF_IN_LAUNCHER -> getString(R.string.state_off_launcher)
        EnableRule.State.NEEDS_ACCESS -> getString(R.string.state_needs_access)
        EnableRule.State.OFF_IN_APP -> getString(R.string.state_off_app)
    }

    // ---- setup flow -------------------------------------------------------------------------------

    /** The prominent disclosure shown before Android's notification-access screen. */
    private fun showAccessDisclosure() {
        LockLook.message(
            this, getString(R.string.disclosure_title), getString(R.string.disclosure_body),
            arrayOf(getString(R.string.disclosure_agree), getString(R.string.disclosure_decline)),
        ) { which ->
            if (which == 0) openAccessSettings()
        }
    }

    private fun openAccessSettings() {
        val cn = ComponentName(this, LockListener::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
            if (tryStart(detail)) return
        }
        tryStart(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun openOverlaySettings() {
        tryStart(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openPreview() {
        LockListener.resetTrigger()
        WakeActivity.preview(this)
    }

    private fun tryStart(i: Intent): Boolean = try {
        startActivity(i)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun version(): String? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            legacyVersion()
        }
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

    @Suppress("DEPRECATION")
    @Throws(PackageManager.NameNotFoundException::class)
    private fun legacyVersion(): String? = packageManager.getPackageInfo(packageName, 0).versionName

    private companion object {
        const val SIDEBAR_DP = 84f
    }
}
