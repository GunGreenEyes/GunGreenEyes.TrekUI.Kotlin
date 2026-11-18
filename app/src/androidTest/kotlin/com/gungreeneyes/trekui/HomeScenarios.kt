package com.gungreeneyes.trekui

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.Until
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * Feature: the launcher replaces the home screen (`features/home.feature`).
 */
@RunWith(AndroidJUnit4::class)
class HomeScenarios : AppScenario() {

    /** Scenario: the launcher starts and shows its home screen. */
    @Test
    fun launcherStartsAndShowsHome() {
        scenario.given("the launcher is installed with default settings")
            .`when`("I start it from the app list")
        val home = launchHome(Interface.COMMAND)
        scenario.then("the TrekUI home screen is shown")
        assertShowsContent(home)
        // Right after boot the accessibility tree can lag; wait for our window before reading the package.
        device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10000)
        assertEquals(scenario.lastStep + ": foreground package", context.packageName, device.currentPackageName)
        media.screenshot("home")
        scenario.and("nothing on it is clipped or under a system bar")
        assertFitsScreen(home, "home")
    }

    /**
     * Scenario: the launcher can be made the default home app, and the Home button then opens it.
     *
     * Uses the real in-app request ([MainActivity.requestDefaultHome]: RoleManager on API 29+,
     * the system home picker before), answers the system dialog through UiAutomator when it can, and
     * falls back to the shell (`cmd role` / `cmd package set-home-activity`) when the dialog
     * text is not recognised. The previous default home is restored afterwards.
     */
    @Test
    fun setAsDefaultHome() {
        val previous = currentHomePackage()
        scenario.given("another app is the default home ($previous)")
        val home = launchHome(Interface.COMMAND)
        try {
            scenario.`when`("I ask the launcher to become the default home app")
            instrumentation.runOnMainSync { AppHooks.requestDefaultHome(home) }
            SystemClock.sleep(1500)
            media.screenshot("role-request")
            val answered = answerHomeDialog()
            SystemClock.sleep(1000)
            if (!isDefaultHome()) {
                Log.i(TAG, "dialog not answered ($answered), using shell to set the home role")
                device.pressBack()
                setHomeByShell(context.packageName, ComponentName(context, MainActivity::class.java))
            }
            scenario.then("it holds the home role")
            assertTrue(scenario.lastStep + ": still not default home, current " + currentHomePackage(), awaitDefaultHome(20000))
            // The role flag flips before Android re-routes the Home key; on a loaded device that takes many seconds.
            if (!awaitHomeResolvesHere(30000)) {
                // Some Pixel emulator images (API 33/34) keep routing Home to Pixel Launcher after a dialog grant,
                // although the role is held; re-applying the role through the shell makes them re-route.
                Log.i(TAG, "role held but HOME still resolves to " + currentHomePackage() + "; re-applying by shell")
                setHomeByShell(context.packageName, ComponentName(context, MainActivity::class.java))
                awaitHomeResolvesHere(30000)
            }
            scenario.and("pressing Home shows the TrekUI home screen")
            device.pressHome()
            var shown = device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 10000)
            if (!shown) {
                // Android 12L applies a new HOME role holder late: the first press can still reach the old one.
                SystemClock.sleep(1500)
                device.pressHome()
                shown = device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 15000)
            }
            if (!shown) {
                // Pixel emulator images for API 33/34 can keep the Home key on Pixel Launcher after a dialog grant
                // even though the role and the HOME resolution both name TrekUI; a shell grant re-routes the key.
                Log.i(TAG, "Home key still opens " + device.currentPackageName + " after the dialog grant; re-applying by shell")
                setHomeByShell(context.packageName, ComponentName(context, MainActivity::class.java))
                SystemClock.sleep(2000)
                device.pressHome()
                shown = device.wait(Until.hasObject(By.pkg(context.packageName).depth(0)), 15000)
            }
            assertTrue(scenario.lastStep, shown)
            waitForResumed(MainActivity::class.java, LAUNCH_TIMEOUT_MS)
            settle()
            media.screenshot("home-button")
        } finally {
            // "android" is the chooser, i.e. there was no default: nothing to restore.
            if (previous != null && previous != context.packageName && "android" != previous) {
                setHomeByShell(previous, null)
            }
        }
    }

    /**
     * Polls [isDefaultHome]: the role grant reaches RoleManager asynchronously, so a check
     * right after the dialog/shell call can still see the old holder.
     */
    private fun awaitDefaultHome(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (isDefaultHome()) return true
            SystemClock.sleep(250)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    /** RoleManager on API 29+ or the preferred HOME activity: either one naming us counts. */
    private fun isDefaultHome(): Boolean {
        if (context.packageName == currentHomePackage()) return true
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        return false
    }

    /** Waits until the HOME intent itself resolves to this app (not just the role flag); false on timeout. */
    private fun awaitHomeResolvesHere(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (context.packageName == currentHomePackage()) return true
            SystemClock.sleep(500)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun currentHomePackage(): String? {
        val ri = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return ri?.activityInfo?.packageName
    }

    /** Picks this launcher in the system role/home-picker dialog; true if something was clicked. */
    private fun answerHomeDialog(): Boolean {
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        var clicked = false
        // Android 6.0-9 open Settings > Home: the title sits in its own clickable block that does not select
        // the row, so press the row's radio button.
        val row = device.wait(
            Until.findObject(By.res("com.android.settings", "home_app_pref").hasDescendant(By.text(label))),
            4000,
        )
        val radio = row?.findObject(By.res("com.android.settings", "home_radio"))
        if (radio != null) {
            radio.click()
            SystemClock.sleep(1000)
            return true
        }
        if (clickWhenFound(By.text(label), 4000)) {
            clicked = true
            SystemClock.sleep(600)
        }
        val confirm = By.text(Pattern.compile("(?i)(set as default|always|ok|allow|yes)"))
        if (device.wait(Until.hasObject(confirm), 3000)) {
            media.screenshot("role-confirm")
            // Android 10's role dialog can ignore a tap on the label text: press the real positive button
            // when it exists, and tap again if the dialog is still there.
            val positive = By.res("android", "button1")
            var attempt = 0
            while (attempt < 2 && device.hasObject(confirm)) {
                clicked = clicked or if (device.hasObject(positive)) clickWhenFound(positive, 1000) else clickWhenFound(confirm, 3000)
                device.wait(Until.gone(confirm), 3000)
                attempt++
            }
        }
        return clicked
    }

    /** Finds and clicks `sel`, finding it again if the dialog redraws between find and click. */
    private fun clickWhenFound(sel: BySelector, timeoutMs: Long): Boolean {
        for (attempt in 0 until 3) {
            val o = device.wait(Until.findObject(sel), timeoutMs) ?: return false
            try {
                o.click()
                return true
            } catch (e: StaleObjectException) {
                SystemClock.sleep(300)
            }
        }
        return false
    }

    /** Makes `pkg` the home app from the shell (API 29+: role, then on every level the preferred activity). */
    private fun setHomeByShell(pkg: String, component: ComponentName?) {
        // Android 6.0 has no `cmd` binary, and executing a missing binary through UiAutomation kills the
        // whole instrumentation ("Error running shell command"); there the chooser's ALWAYS is the only way.
        if (Build.VERSION.SDK_INT < 24) {
            Log.i(TAG, "no shell fallback for the home app before Android 7.0")
            return
        }
        val c = component?.flattenToString() ?: homeComponentOf(pkg)
        if (Build.VERSION.SDK_INT >= 29) {
            Log.i(TAG, media.shell("cmd role add-role-holder android.app.role.HOME $pkg 0"))
        }
        // Also set the package manager's preferred home directly: on some API 33+ tablet images the role
        // changes but the Home key keeps following the old preferred activity.
        if (c != null) Log.i(TAG, media.shell("cmd package set-home-activity $c"))
        SystemClock.sleep(800)
    }

    private fun homeComponentOf(pkg: String): String? {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg)
        val ri = context.packageManager.queryIntentActivities(i, 0).firstOrNull() ?: return null
        return ComponentName(ri.activityInfo.packageName, ri.activityInfo.name).flattenToString()
    }
}
