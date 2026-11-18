package com.gungreeneyes.trekui

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feature: launcher settings (`features/settings.feature`).
 */
@RunWith(AndroidJUnit4::class)
class SettingsScenarios : AppScenario() {

    /** Scenario: settings open from home, fit the screen and scroll to the end without crashing. */
    @Test
    fun settingsOpens() {
        scenario.given("the launcher home screen is showing")
        val home = launchHome(Interface.COMMAND)
        scenario.`when`("I open the launcher settings")
        instrumentation.runOnMainSync { AppHooks.openSettings(home) }
        val settings = waitForResumed(SettingsActivity::class.java, LAUNCH_TIMEOUT_MS)
        settle()
        scenario.then("the settings screen is shown")
        assertShowsContent(settings)
        media.screenshot("settings-top")
        scenario.and("nothing on it is clipped or under a system bar")
        assertFitsScreen(settings, "settings")
        scenario.`when`("I scroll to the bottom of the settings")
        for (i in 0 until 6) {
            device.swipe(
                device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 4, 20,
            )
            SystemClock.sleep(300)
        }
        settle()
        media.screenshot("settings-bottom")
        scenario.then("the settings screen is still running")
        assertFalse(scenario.lastStep, settings.isFinishing || settings.isDestroyed)
        instrumentation.runOnMainSync(settings::finish)
    }
}
