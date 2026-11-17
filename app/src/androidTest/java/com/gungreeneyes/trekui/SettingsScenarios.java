package com.gungreeneyes.trekui;

import static org.junit.Assert.assertFalse;

import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Feature: launcher settings ({@code features/settings.feature}).
 */
@RunWith(AndroidJUnit4.class)
public class SettingsScenarios extends AppScenario {

    /** Scenario: settings open from home, fit the screen and scroll to the end without crashing. */
    @Test
    public void settingsOpens() {
        scenario.given("the launcher home screen is showing");
        MainActivity home = launchHome(Interface.COMMAND);
        scenario.when("I open the launcher settings");
        instrumentation.runOnMainSync(() -> AppHooks.openSettings(home));
        SettingsActivity settings = waitForResumed(SettingsActivity.class, LAUNCH_TIMEOUT_MS);
        settle();
        scenario.then("the settings screen is shown");
        assertShowsContent(settings);
        media.screenshot("settings-top");
        scenario.and("nothing on it is clipped or under a system bar");
        assertFitsScreen(settings, "settings");
        scenario.when("I scroll to the bottom of the settings");
        for (int i = 0; i < 6; i++) {
            device.swipe(device.getDisplayWidth() / 2, device.getDisplayHeight() * 3 / 4,
                    device.getDisplayWidth() / 2, device.getDisplayHeight() / 4, 20);
            SystemClock.sleep(300);
        }
        settle();
        media.screenshot("settings-bottom");
        scenario.then("the settings screen is still running");
        assertFalse(scenario.lastStep(), settings.isFinishing() || settings.isDestroyed());
        instrumentation.runOnMainSync(settings::finish);
    }
}
