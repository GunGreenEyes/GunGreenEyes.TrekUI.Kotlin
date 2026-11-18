package com.gungreeneyes.trekui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Feature: rotation and resizing ({@code features/rotation.feature}).
 *
 * <p>{@link MainActivity} handles orientation/size changes itself ({@code android:configChanges}), so a
 * rotation must keep the same activity instance, the chosen interface and an open drawer.</p>
 */
@RunWith(AndroidJUnit4.class)
public class RotationScenarios extends AppScenario {

    /** Scenario: rotating portrait to landscape and back keeps the launcher's state. */
    @Test
    public void rotationKeepsState() throws Exception {
        scenario.given("the Imperial interface is showing in portrait with the app drawer open");
        device.setOrientationPortrait();
        MainActivity home = launchHome(Interface.IMPERIAL);
        instrumentation.runOnMainSync(() -> AppHooks.openDrawer(home));
        settle();
        media.screenshot("portrait");

        scenario.when("I rotate the device to landscape");
        device.setOrientationLandscape();
        SystemClock.sleep(1200);
        settle();
        media.screenshot("landscape");
        scenario.then("the same launcher instance is showing, still Imperial, drawer still open");
        assertSame(scenario.lastStep() + ": activity was recreated", home, waitForResumed(MainActivity.class, LAUNCH_TIMEOUT_MS));
        assertEquals(scenario.lastStep() + ": interface", Interface.IMPERIAL.faction, (int) onMain(AppHooks::skinFaction));
        assertTrue(scenario.lastStep() + ": drawer closed", onMain(() -> AppHooks.drawerOpen(home)));
        scenario.and("the landscape layout fits the screen");
        assertFitsScreen(home, "landscape drawer");

        scenario.when("I rotate back to portrait");
        device.setOrientationPortrait();
        SystemClock.sleep(1200);
        settle();
        media.screenshot("portrait-again");
        scenario.then("the drawer is still open and the layout fits");
        assertTrue(scenario.lastStep() + ": drawer closed", onMain(() -> AppHooks.drawerOpen(home)));
        assertFitsScreen(home, "portrait drawer");
        instrumentation.runOnMainSync(() -> AppHooks.closeDrawer(home));
    }

    /** Scenario: the home screen in landscape fits for every console family. */
    @Test
    public void landscapeHomeFits() throws Exception {
        scenario.given("the device is in landscape");
        device.setOrientationLandscape();
        SystemClock.sleep(800);
        for (Interface iface : new Interface[]{Interface.COMMAND, Interface.IMPERIAL, Interface.COLLECTIVE_HUB, Interface.NEXT_CONSOLE}) {
            scenario.when("I show the " + iface + " interface");
            MainActivity home = launchHome(iface);
            media.screenshot(iface.slug() + "-landscape");
            scenario.then("it fits the landscape screen");
            assertFitsScreen(home, iface.slug() + " landscape");
        }
    }

    /**
     * Scenario: resizing the display (as split-screen / freeform / a fold does) does not crash.
     *
     * <p>Home activities cannot enter split-screen, so the window size change is driven with
     * {@code wm size} / {@code wm density}, which delivers the same configuration change.</p>
     */
    @Test
    public void resizeDoesNotCrash() {
        scenario.given("the Command interface is showing");
        MainActivity home = launchHome(Interface.COMMAND);
        int w = device.getDisplayWidth(), h = device.getDisplayHeight();
        int natW = Math.min(w, h), natH = Math.max(w, h);
        int[][] sizes = {
                {natW, natH / 2},            // top half of a split screen
                {natW / 2 + 40, natH},       // narrow column
                {natH, natW},                // swapped aspect (unfolded / tablet-like)
        };
        for (int[] s : sizes) {
            scenario.when("the window is resized to " + s[0] + "x" + s[1]);
            media.shell("wm size " + s[0] + "x" + s[1]);
            SystemClock.sleep(1500);
            settle();
            MainActivity now = waitForResumed(MainActivity.class, LAUNCH_TIMEOUT_MS);
            media.screenshot("resize-" + s[0] + "x" + s[1]);
            scenario.then("the launcher is still running and shows content");
            assertShowsContent(now);
            assertTrue(scenario.lastStep(), !now.isFinishing());
        }
        scenario.when("the display density is raised (larger display size setting)");
        media.shell("wm size reset");
        int dpi = context.getResources().getDisplayMetrics().densityDpi;
        media.shell("wm density " + Math.round(dpi * 1.3f));
        SystemClock.sleep(1500);
        settle();
        MainActivity now = waitForResumed(MainActivity.class, LAUNCH_TIMEOUT_MS);
        media.screenshot("density-130pct");
        scenario.then("the launcher is still running, shows content and fits");
        assertShowsContent(now);
        assertFitsScreen(now, "density 130%");
    }
}
