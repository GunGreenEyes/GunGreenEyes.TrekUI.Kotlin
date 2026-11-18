package com.gungreeneyes.trekui

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import java.util.concurrent.atomic.AtomicReference

/**
 * Base class of every instrumented scenario.
 *
 * Per scenario it: puts the device in the run's orientation (instrumentation argument
 * `consoleOrientation` = natural | portrait | landscape), starts a screen recording named after the
 * scenario, resets the launcher's preferences to a quiet known state (no boot animation, no welcome
 * dialog, trial forced unlocked in debug builds so the 5-minute develop trial cannot interrupt a long
 * run), and afterwards stops the recording and restores display size/orientation.
 *
 * Scenario ids are `<TestClass>_<method>` sanitised with [MediaCapture.sanitize]; the
 * capture runner uses the same rule to match instrumentation results to media files.
 */
abstract class AppScenario {

    @get:Rule
    val testName = TestName()

    protected lateinit var instrumentation: Instrumentation
    protected lateinit var device: UiDevice
    protected lateinit var context: Context
    protected lateinit var media: MediaCapture
    protected lateinit var scenario: Gherkin

    @Before
    fun setUpScenario() {
        instrumentation = InstrumentationRegistry.getInstrumentation()
        device = UiDevice.getInstance(instrumentation)
        context = instrumentation.targetContext
        val id = MediaCapture.sanitize(javaClass.simpleName + "_" + testName.methodName)
        applyRunOrientation()
        media = MediaCapture(instrumentation, id)
        scenario = Gherkin(id)
        resetPreferences()
        forceLicence(true, 0)
        media.startVideo()
    }

    @After
    fun tearDownScenario() {
        try {
            media.stopVideo()
        } finally {
            media.shell("wm size reset")
            media.shell("wm density reset")
            try {
                applyRunOrientation()
            } catch (e: RemoteException) {
                Log.w(TAG, "orientation restore failed", e)
            }
        }
    }

    // ---- device ----------------------------------------------------------------------------------

    @Throws(RemoteException::class)
    protected fun applyRunOrientation() {
        when (runOrientation()) {
            "landscape" -> device.setOrientationLandscape()
            "portrait" -> device.setOrientationPortrait()
            else -> device.setOrientationNatural()
        }
        SystemClock.sleep(500)
    }

    // ---- launcher state --------------------------------------------------------------------------

    /** The launcher's own preference file (see [Prefs]). */
    protected fun launcherPrefs(): SharedPreferences = context.getSharedPreferences("trekui", Context.MODE_PRIVATE)

    /** Quiet defaults: no boot sequence, no first-run welcome, Command interface. */
    protected fun resetPreferences() {
        val sp = launcherPrefs()
        val e = sp.edit()
            .putBoolean("welcomed", true)
            .putBoolean("bootSeq", false)
        Interface.COMMAND.apply(e)
        e.putInt("ver", sp.getInt("ver", 0) + 1).commit()
    }

    /**
     * Debug-build licence hooks from `com.gungreeneyes.trekui.licensing.Licensing`, called by
     * reflection so these tests keep compiling while that package evolves.
     *
     * @param unlocked     true forces unlocked, false locked, null clears the override
     * @param trialSeconds session length override in seconds, 0 restores the BuildConfig value
     * @return false when the licensing package or its hooks are not present
     */
    protected fun forceLicence(unlocked: Boolean?, trialSeconds: Int): Boolean {
        return try {
            val lic = Class.forName("com.gungreeneyes.trekui.licensing.Licensing")
            val setUnlocked = lic.getMethod("debugSetUnlocked", Context::class.java, Boolean::class.javaObjectType)
            val setSeconds = lic.getMethod("debugSetTrialSeconds", Context::class.java, Int::class.java)
            instrumentation.runOnMainSync {
                try {
                    setUnlocked.invoke(null, context, unlocked)
                    setSeconds.invoke(null, context, trialSeconds)
                } catch (ex: ReflectiveOperationException) {
                    throw RuntimeException(ex)
                }
            }
            true
        } catch (e: ReflectiveOperationException) {
            Log.i(TAG, "licensing hooks unavailable: $e")
            false
        } catch (e: RuntimeException) {
            Log.i(TAG, "licensing hooks unavailable: $e")
            false
        }
    }

    /**
     * Switches to `iface` and brings the launcher's home screen to the front.
     *
     * Bumping the `ver` preference makes [MainActivity.onResume] rebuild its UI, so the
     * new interface also applies when the (singleTask) activity is already running.
     */
    protected fun launchHome(iface: Interface): Activity {
        val sp = launcherPrefs()
        val e = sp.edit()
        iface.apply(e)
        e.putInt("ver", sp.getInt("ver", 0) + 1).commit()
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(context, MainActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        val a = waitForResumed(MainActivity::class.java, LAUNCH_TIMEOUT_MS)
        settle()
        dismissSystemDialogs()
        return a
    }

    /**
     * Clears system windows that a freshly booted emulator likes to show over the launcher (the
     * "System UI / Pixel Launcher isn't responding" ANR dialog, notification shade, crash dialogs of
     * other apps). These are environment noise, not launcher bugs; an ANR of the launcher itself is
     * still reported because its process name is logged.
     */
    protected fun dismissSystemDialogs() {
        for (attempt in 0 until 3) {
            val pkg = device.currentPackageName
            if (context.packageName == pkg) return
            val wait = device.findObject(By.res("android", "aerr_wait"))
                ?: device.findObject(By.res("android", "aerr_close"))
            if (wait != null) {
                Log.w(
                    TAG,
                    "dismissing system dialog over the launcher: " +
                        device.findObject(By.res("android", "alertTitle")),
                )
                wait.click()
            } else {
                media.shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
            }
            SystemClock.sleep(800)
        }
    }

    /** Waits for the main thread and lets animations finish (no idle-sync: the starfield never idles). */
    protected fun settle() {
        instrumentation.runOnMainSync { }
        SystemClock.sleep(SETTLE_MS)
        instrumentation.runOnMainSync { }
    }

    /** Polls the lifecycle monitor until an activity of `type` is RESUMED, or fails. */
    protected fun <T : Activity> waitForResumed(type: Class<T>, timeoutMs: Long): T =
        findResumed(type, timeoutMs) ?: throw AssertionError(
            scenario.lastStep + ": no resumed " + type.simpleName + " within " + timeoutMs +
                "ms (resumed: " + describeResumed() + ")",
        )

    /** Like [waitForResumed] but returns null instead of failing. */
    protected fun <T : Activity> findResumed(type: Class<T>, timeoutMs: Long): T? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val ref = AtomicReference<Activity?>()
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                for (a in resumed) {
                    if (type.isInstance(a)) ref.set(a)
                }
            }
            ref.get()?.let { return type.cast(it) }
            SystemClock.sleep(200)
        }
        return null
    }

    /** Resumed activity whose class name contains `fragment` (for classes we cannot reference). */
    protected fun findResumedByName(fragment: String, timeoutMs: Long): Activity? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val ref = AtomicReference<Activity?>()
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                for (a in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (a.javaClass.name.contains(fragment)) ref.set(a)
                }
            }
            ref.get()?.let { return it }
            SystemClock.sleep(200)
        }
        return null
    }

    protected fun describeResumed(): String {
        val sb = StringBuilder()
        instrumentation.runOnMainSync {
            for (a in ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                sb.append(a.javaClass.simpleName).append(' ')
            }
        }
        sb.append("| foreground pkg ").append(device.currentPackageName)
        return sb.toString()
    }

    /** Runs `r` on the main thread and returns its result. */
    protected fun <T> onMain(r: () -> T): T {
        val out = AtomicReference<T>()
        val err = AtomicReference<Throwable?>()
        instrumentation.runOnMainSync {
            try {
                out.set(r())
            } catch (t: Throwable) {
                err.set(t)
            }
        }
        err.get()?.let { throw AssertionError(scenario.lastStep + ": " + it, it) }
        return out.get()
    }

    // ---- assertions ------------------------------------------------------------------------------

    /** Asserts the activity shows something: a content view with at least one key view. */
    protected fun assertShowsContent(activity: Activity?) {
        assertNotNull(scenario.lastStep + ": activity", activity)
        val a = activity!!
        val keys = onMain { LayoutAudit.countKeyViews(a) }
        // Canvas-drawn screens (e.g. the purchase screen) may have no labelled child views at all:
        // then a visible, laid-out content view is enough.
        val drawn = onMain { visibleLeaves(a.findViewById(android.R.id.content)) }
        assertTrue(
            scenario.lastStep + ": expected visible content, found " + keys + " key views and " +
                drawn + " drawn views",
            keys > 0 || drawn > 0,
        )
    }

    /** Asserts nothing important is clipped off-screen or hidden under system bars; lists all problems. */
    protected fun assertFitsScreen(activity: Activity, where: String) {
        val v = onMain { LayoutAudit.audit(activity) }
        if (v.isNotEmpty()) {
            val sb = StringBuilder(scenario.lastStep).append(" [").append(where).append("] ")
                .append(v.size).append(" layout problem(s) on ").append(MediaCapture.deviceName())
                .append(" api").append(Build.VERSION.SDK_INT).append(' ')
                .append(MediaCapture.orientation(context)).append(':')
            var n = 0
            for (x in v) {
                sb.append("\n  - ").append(x)
                if (++n == 25) {
                    sb.append("\n  ... ").append(v.size - n).append(" more")
                    break
                }
            }
            Log.e(TAG, sb.toString())
            fail(sb.toString())
        }
    }

    companion object {
        const val TAG = "TREKUI-TEST"
        const val LAUNCH_TIMEOUT_MS = 20000L

        /** Time allowed for reveal animations and first panel updates before a screenshot. */
        const val SETTLE_MS = 1500L

        /** Instrumentation argument `consoleOrientation`: natural (default), portrait or landscape. */
        @JvmStatic
        fun runOrientation(): String {
            val args = InstrumentationRegistry.getArguments()
            return args.getString("trekuiOrientation") ?: "natural"
        }

        /** Visible, non-empty leaf views (custom-drawn views included) below `v`. */
        @JvmStatic
        fun visibleLeaves(v: View?): Int {
            if (v == null || v.visibility != View.VISIBLE || v.width == 0 || v.height == 0) return 0
            if (v is ViewGroup && v.childCount > 0) {
                var n = 0
                for (i in 0 until v.childCount) n += visibleLeaves(v.getChildAt(i))
                return n
            }
            return 1
        }
    }
}
