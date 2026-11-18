package com.gungreeneyes.trekui.messages

import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.gungreeneyes.trekui.MessagesLook
import com.gungreeneyes.trekui.ScreenMetrics
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Base of every Messages screen: edge-to-edge window, a root that pads itself for the system bars,
 * cutouts and the keyboard, the interface's console frame (title, footer, sidebar of buttons, content),
 * background loading with main-thread delivery, and the theme follow: every resume re-reads the launcher's
 * interface ([MessagesTheme.refresh]) and rebuilds the screen when it changed. Screens handle
 * rotation and resizing themselves (configChanges in the manifest) by rebuilding.
 */
abstract class BaseScreen : ComponentActivity() {
    protected val main = Handler(Looper.getMainLooper())
    protected lateinit var root: FrameLayout
    protected var metrics: ScreenMetrics? = null

    /** A sidebar button: label, colour slot, test tag and action. */
    protected class SideButton(
        val label: String,
        val accent: Int,
        val tag: String,
        val action: View.OnClickListener,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        rebuild()
    }

    override fun onResume() {
        super.onResume()
        MessagesTheme.refresh(this, ::rebuild)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuild()
    }

    /** Re-reads the look and builds the whole screen again (keeps the screen's own state fields). */
    protected fun rebuild() {
        MessagesLook.load(this)
        val m = ScreenMetrics.of(this)
        metrics = m
        val r = FrameLayout(this)
        root = r
        r.setBackgroundColor(MessagesLook.bg())
        r.setOnApplyWindowInsetsListener { view, raw ->
            val w = WindowInsetsCompat.toWindowInsetsCompat(raw, view)
            val bars = w.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = w.getInsets(WindowInsetsCompat.Type.ime())
            val side = if (m.isTwoPane) m.marginPx() else MessagesLook.dp(this, 8f)
            r.setPadding(
                bars.left + side, bars.top + MessagesLook.dp(this, 4f), bars.right + side,
                maxOf(bars.bottom, ime.bottom) + MessagesLook.dp(this, 6f),
            )
            raw
        }
        build()
        setContentView(r)
        r.requestApplyInsets()
    }

    /** Builds the screen into [root] (called on create, theme change and configuration change). */
    protected abstract fun build()

    /** Puts [content] into the console frame with a title, footer and sidebar buttons. */
    protected fun frame(title: String, footer: String, buttons: List<SideButton>, content: View) {
        val t = MessagesLook.oneLine(MessagesLook.bold(this, title, 28f, MessagesLook.accent(0)))
        t.setPadding(MessagesLook.dp(this, 4f), 0, MessagesLook.dp(this, 2f), 0)
        val f = MessagesLook.oneLine(MessagesLook.text(this, footer, 14f, MessagesLook.accent(1)))
        f.setPadding(MessagesLook.dp(this, 4f), 0, MessagesLook.dp(this, 2f), 0)
        val frame = MessagesLook.frame(this, t, f, sidebar(buttons), content, SIDEBAR_DP)
        root.addView(
            frame,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    /** The sidebar: a scrolling column of square console buttons. */
    private fun sidebar(buttons: List<SideButton>): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        buttons.forEachIndexed { i, b ->
            val color = MessagesLook.accent(b.accent)
            val v = MessagesLook.button(this, b.label, color, MessagesLook.SQUARE, b.action)
            v.background = MessagesLook.touchCell(this, color, MessagesLook.SQUARE, 0f)
            MessagesLook.setTextSp(v, 14f)
            v.setPadding(MessagesLook.dp(this, 4f), 0, MessagesLook.dp(this, 8f), MessagesLook.dp(this, 4f))
            v.tag = b.tag
            v.contentDescription = b.label
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, MessagesLook.dp(this, 50f))
            if (i > 0) lp.topMargin = MessagesLook.dp(this, MessagesLook.GUTTER)
            column.addView(v, lp)
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(column)
        }
    }

    /** Runs [work] in the background and hands its result (null if it threw) to [done] on the main thread. */
    protected fun <T> load(work: () -> T?, done: (T?) -> Unit) {
        LOADER.execute {
            val result = try {
                work()
            } catch (e: RuntimeException) {
                null
            }
            main.post { if (!isDestroyed) done(result) }
        }
    }

    /** True if [permission] is granted. */
    protected fun has(permission: String): Boolean = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Version name for the footer. */
    protected fun version(): String? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
        } else {
            legacyVersion()
        }
    } catch (e: PackageManager.NameNotFoundException) {
        ""
    }

    @Suppress("DEPRECATION")
    private fun legacyVersion(): String? = packageManager.getPackageInfo(packageName, 0).versionName

    protected companion object {
        /** Sidebar width in design dp (wider on tablets via the frame's zoom). */
        const val SIDEBAR_DP = 84f

        private val LOADER: ExecutorService = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "messages-load").apply { isDaemon = true }
        }

        /** A new, empty sidebar button list. */
        fun buttons(): MutableList<SideButton> = ArrayList()
    }
}
