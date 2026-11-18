package com.gungreeneyes.trekui.messages

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import com.gungreeneyes.trekui.TrekPrefs
import com.gungreeneyes.trekui.core.SuiteClient
import com.gungreeneyes.trekui.core.SuiteContract
import com.gungreeneyes.trekui.core.SuiteTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Which interface and sub-theme Messages draws in.
 *
 * When the TrekUI launcher (same flavor, same signing key) is installed, Messages follows it: on every
 * screen resume [refresh] reads the launcher's choice through [SuiteClient.launcherTheme] in
 * the background and installs it with [TrekPrefs.setThemeOverride], so every :core drawing helper
 * answers from it. The last answer is cached ([applyCached]) so a cold start draws in the right
 * look before the provider answers. Without the launcher, the override is cleared and the app's own
 * choice (CONFIG > INTERFACE in this app, stored in its own `trekui` prefs) applies.
 */
internal object MessagesTheme {
    private const val FILE = "messages_theme"
    private const val KEY_CACHED = "launcher_theme"

    private val IO: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "messages-theme").apply { isDaemon = true }
    }
    private val MAIN = Handler(Looper.getMainLooper())

    /** True while the launcher's choice is applied (the app's own picker is then read-only). */
    fun followingLauncher(): Boolean = TrekPrefs.themeOverride() != null

    /** Applies the launcher theme cached by the last refresh, if the launcher is still installed. */
    fun applyCached(c: Context) {
        val sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val t = decode(sp.getString(KEY_CACHED, null))
        if (t != null && launcherInstalled(c)) TrekPrefs.setThemeOverride(t)
    }

    /**
     * Re-reads the launcher's choice in the background; on the main thread installs it (or clears the
     * override when the launcher is gone) and runs [onChanged] if the look changed.
     */
    fun refresh(context: Context, onChanged: (() -> Unit)?) {
        val app = context.applicationContext
        IO.execute {
            val t = SuiteClient.launcherTheme(app)
            MAIN.post {
                val before = TrekPrefs.themeOverride()
                TrekPrefs.setThemeOverride(t)
                app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putString(KEY_CACHED, encode(t)) }
                if (before != t) onChanged?.invoke()
            }
        }
    }

    private fun launcherInstalled(c: Context): Boolean = try {
        c.packageManager.getPackageInfo(SuiteContract.launcherPackage(c.packageName), 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** "faction,subTheme,lettering,soundSet" (soundSet may be empty), or null. */
    fun encode(t: SuiteTheme?): String? =
        t?.let { "${it.faction},${it.subTheme},${it.lettering},${it.soundSet ?: ""}" }

    fun decode(s: String?): SuiteTheme? {
        if (s == null) return null
        val p = s.split(",")
        if (p.size < 4) return null
        return try {
            val f = TrekPrefs.clampFaction(p[0].toInt())
            SuiteTheme(f, p[1].toInt(), p[2].toInt(), p[3].ifEmpty { null })
        } catch (e: NumberFormatException) {
            null
        }
    }
}
