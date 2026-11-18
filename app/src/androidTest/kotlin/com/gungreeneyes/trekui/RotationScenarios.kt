package com.gungreeneyes.trekui

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Feature: rotation and resizing (`features/rotation.feature`).
 *
 * [MainActivity] handles orientation/size changes itself (`android:configChanges`), so a
 * rotation must keep the same activity instance, the chosen interface and an open drawer.
 */
@RunWith(AndroidJUnit4::class)
class RotationScenarios : AppScenario() {

    /** Scenario: rotating portrait to landscape and back keeps the launcher's state. */
    @Test
    fun rotationKeepsState() {
        scenario.given("the Imperial interface is showing in portrait with the app drawer open")
        device.setOrientationPortrait()
        val home = launchHome(Interface.IMPERIAL)
        instrumentation.runOnMainSync { AppHooks.openDrawer(home) }
        settle()
        media.screenshot("portrait")

        scenario.`when`("I rotate the device to landscape")
        device.setOrientationLandscape()
        SystemClock.sleep(1200)
        settle()
        media.screenshot("landscape")
        scenario.then("the same launcher instance is showing, still Imperial, drawer still open")
        assertSame(scenario.lastStep + ": activity was recreated", home, waitForResumed(MainActivity::class.java, LAUNCH_TIMEOUT_MS))
        assertEquals(scenario.lastStep + ": interface", Interface.IMPERIAL.faction.toLong(), onMain { AppHooks.skinFaction() }.toLong())
        assertTrue(scenario.lastStep + ": drawer closed", onMain { AppHooks.drawerOpen(home) })
        scenario.and("the landscape layout fits the screen")
        assertFitsScreen(home, "landscape drawer")

        scenario.`when`("I rotate back to portrait")
        device.setOrientationPortrait()
        SystemClock.sleep(1200)
        settle()
        media.screenshot("portrait-again")
        scenario.then("the drawer is still open and the layout fits")
        assertTrue(scenario.lastStep + ": drawer closed", onMain { AppHooks.drawerOpen(home) })
        assertFitsScreen(home, "portrait drawer")
        instrumentation.runOnMainSync { AppHooks.closeDrawer(home) }
    }

    /** Scenario: the home screen in landscape fits for every console family. */
    @Test
    fun landscapeHomeFits() {
        scenario.given("the device is in landscape")
        device.setOrientationLandscape()
        SystemClock.sleep(800)
        for (iface in arrayOf(Interface.COMMAND, Interface.IMPERIAL, Interface.COLLECTIVE_HUB, Interface.NEXT_CONSOLE)) {
            scenario.`when`("I show the $iface interface")
            val home = launchHome(iface)
            media.screenshot(iface.slug() + "-landscape")
            scenario.then("it fits the landscape screen")
            assertFitsScreen(home, iface.slug() + " landscape")
        }
    }

    /**
     * Scenario: resizing the display (as split-screen / freeform / a fold does) does not crash.
     *
     * Home activities cannot enter split-screen, so the window size change is driven with
     * `wm size` / `wm density`, which delivers the same configuration change.
     */
    @Test
    fun resizeDoesNotCrash() {
        scenario.given("the Command interface is showing")
        launchHome(Interface.COMMAND)
        val w = device.displayWidth
        val h = device.displayHeight
        val natW = min(w, h)
        val natH = max(w, h)
        val sizes = arrayOf(
            intArrayOf(natW, natH / 2), // top half of a split screen
            intArrayOf(natW / 2 + 40, natH), // narrow column
            intArrayOf(natH, natW), // swapped aspect (unfolded / tablet-like)
        )
        for (s in sizes) {
            scenario.`when`("the window is resized to " + s[0] + "x" + s[1])
            media.shell("wm size " + s[0] + "x" + s[1])
            SystemClock.sleep(1500)
            settle()
            val now = waitForResumed(MainActivity::class.java, LAUNCH_TIMEOUT_MS)
            media.screenshot("resize-" + s[0] + "x" + s[1])
            scenario.then("the launcher is still running and shows content")
            assertShowsContent(now)
            assertTrue(scenario.lastStep, !now.isFinishing)
        }
        scenario.`when`("the display density is raised (larger display size setting)")
        media.shell("wm size reset")
        val dpi = context.resources.displayMetrics.densityDpi
        media.shell("wm density " + (dpi * 1.3f).roundToInt())
        SystemClock.sleep(1500)
        settle()
        val now = waitForResumed(MainActivity::class.java, LAUNCH_TIMEOUT_MS)
        media.screenshot("density-130pct")
        scenario.then("the launcher is still running, shows content and fits")
        assertShowsContent(now)
        assertFitsScreen(now, "density 130%")
    }
}
