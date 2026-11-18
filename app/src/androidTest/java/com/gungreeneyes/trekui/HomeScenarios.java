package com.gungreeneyes.trekui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.regex.Pattern;

/**
 * Feature: the launcher replaces the home screen ({@code features/home.feature}).
 */
@RunWith(AndroidJUnit4.class)
public class HomeScenarios extends AppScenario {

    /** Scenario: the launcher starts and shows its home screen. */
    @Test
    public void launcherStartsAndShowsHome() {
        scenario.given("the launcher is installed with default settings")
                .when("I start it from the app list");
        MainActivity home = launchHome(Interface.COMMAND);
        scenario.then("the TrekUI home screen is shown");
        assertShowsContent(home);
        // Right after boot the accessibility tree can lag; wait for our window before reading the package.
        device.wait(Until.hasObject(By.pkg(context.getPackageName()).depth(0)), 10000);
        assertEquals(scenario.lastStep() + ": foreground package", context.getPackageName(), device.getCurrentPackageName());
        media.screenshot("home");
        scenario.and("nothing on it is clipped or under a system bar");
        assertFitsScreen(home, "home");
    }

    /**
     * Scenario: the launcher can be made the default home app, and the Home button then opens it.
     *
     * <p>Uses the real in-app request ({@link MainActivity#requestDefaultHome}: RoleManager on API 29+,
     * the system home picker before), answers the system dialog through UiAutomator when it can, and
     * falls back to the shell ({@code cmd role} / {@code cmd package set-home-activity}) when the dialog
     * text is not recognised. The previous default home is restored afterwards.</p>
     */
    @Test
    public void setAsDefaultHome() {
        String previous = currentHomePackage();
        scenario.given("another app is the default home (" + previous + ")");
        MainActivity home = launchHome(Interface.COMMAND);
        try {
            scenario.when("I ask the launcher to become the default home app");
            instrumentation.runOnMainSync(() -> AppHooks.requestDefaultHome(home));
            SystemClock.sleep(1500);
            media.screenshot("role-request");
            boolean answered = answerHomeDialog();
            SystemClock.sleep(1000);
            if (!isDefaultHome()) {
                Log.i(TAG, "dialog not answered (" + answered + "), using shell to set the home role");
                device.pressBack();
                setHomeByShell(context.getPackageName(), new ComponentName(context, MainActivity.class));
            }
            scenario.then("it holds the home role");
            assertTrue(scenario.lastStep() + ": still not default home, current " + currentHomePackage(), awaitDefaultHome(20000));
            // The role flag flips before Android re-routes the Home key; on a loaded device that takes many seconds.
            if (!awaitHomeResolvesHere(30000)) {
                // Some Pixel emulator images (API 33/34) keep routing Home to Pixel Launcher after a dialog grant,
                // although the role is held; re-applying the role through the shell makes them re-route.
                Log.i(TAG, "role held but HOME still resolves to " + currentHomePackage() + "; re-applying by shell");
                setHomeByShell(context.getPackageName(), new ComponentName(context, MainActivity.class));
                awaitHomeResolvesHere(30000);
            }
            scenario.and("pressing Home shows the TrekUI home screen");
            device.pressHome();
            boolean shown = device.wait(Until.hasObject(By.pkg(context.getPackageName()).depth(0)), 10000);
            if (!shown) {
                // Android 12L applies a new HOME role holder late: the first press can still reach the old one.
                SystemClock.sleep(1500);
                device.pressHome();
                shown = device.wait(Until.hasObject(By.pkg(context.getPackageName()).depth(0)), 15000);
            }
            if (!shown) {
                // Pixel emulator images for API 33/34 can keep the Home key on Pixel Launcher after a dialog grant
                // even though the role and the HOME resolution both name TrekUI; a shell grant re-routes the key.
                Log.i(TAG, "Home key still opens " + device.getCurrentPackageName() + " after the dialog grant; re-applying by shell");
                setHomeByShell(context.getPackageName(), new ComponentName(context, MainActivity.class));
                SystemClock.sleep(2000);
                device.pressHome();
                shown = device.wait(Until.hasObject(By.pkg(context.getPackageName()).depth(0)), 15000);
            }
            assertTrue(scenario.lastStep(), shown);
            waitForResumed(MainActivity.class, LAUNCH_TIMEOUT_MS);
            settle();
            media.screenshot("home-button");
        } finally {
            // "android" is the chooser, i.e. there was no default: nothing to restore.
            if (previous != null && !previous.equals(context.getPackageName()) && !"android".equals(previous)) {
                setHomeByShell(previous, null);
            }
        }
    }

    /**
     * Polls {@link #isDefaultHome()}: the role grant reaches RoleManager asynchronously, so a check
     * right after the dialog/shell call can still see the old holder.
     */
    private boolean awaitDefaultHome(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do {
            if (isDefaultHome()) return true;
            SystemClock.sleep(250);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    /** RoleManager on API 29+ or the preferred HOME activity: either one naming us counts. */
    private boolean isDefaultHome() {
        if (context.getPackageName().equals(currentHomePackage())) return true;
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager rm = context.getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME);
        }
        return false;
    }

    /** Waits until the HOME intent itself resolves to this app (not just the role flag); false on timeout. */
    private boolean awaitHomeResolvesHere(long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do {
            if (context.getPackageName().equals(currentHomePackage())) return true;
            SystemClock.sleep(500);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private String currentHomePackage() {
        ResolveInfo ri = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
    }

    /** Picks this launcher in the system role/home-picker dialog; true if something was clicked. */
    private boolean answerHomeDialog() {
        String label = context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
        boolean clicked = false;
        if (clickWhenFound(By.text(label), 4000)) {
            clicked = true;
            SystemClock.sleep(600);
        }
        BySelector confirm = By.text(Pattern.compile("(?i)(set as default|always|ok|allow|yes)"));
        if (device.wait(Until.hasObject(confirm), 3000)) {
            media.screenshot("role-confirm");
            // Android 10's role dialog can ignore a tap on the label text: press the real positive button
            // when it exists, and tap again if the dialog is still there.
            BySelector positive = By.res("android", "button1");
            for (int attempt = 0; attempt < 2 && device.hasObject(confirm); attempt++) {
                clicked |= device.hasObject(positive) ? clickWhenFound(positive, 1000) : clickWhenFound(confirm, 3000);
                device.wait(Until.gone(confirm), 3000);
            }
        }
        return clicked;
    }

    /** Finds and clicks {@code sel}, finding it again if the dialog redraws between find and click. */
    private boolean clickWhenFound(BySelector sel, long timeoutMs) {
        for (int attempt = 0; attempt < 3; attempt++) {
            UiObject2 o = device.wait(Until.findObject(sel), timeoutMs);
            if (o == null) return false;
            try {
                o.click();
                return true;
            } catch (androidx.test.uiautomator.StaleObjectException e) {
                SystemClock.sleep(300);
            }
        }
        return false;
    }

    /** Makes {@code pkg} the home app from the shell (API 29+: role; older: preferred activity). */
    private void setHomeByShell(String pkg, ComponentName component) {
        if (Build.VERSION.SDK_INT >= 29) {
            Log.i(TAG, media.shell("cmd role add-role-holder android.app.role.HOME " + pkg + " 0"));
        } else {
            String c = component != null ? component.flattenToString() : homeComponentOf(pkg);
            if (c != null) Log.i(TAG, media.shell("cmd package set-home-activity " + c));
        }
        SystemClock.sleep(800);
    }

    private String homeComponentOf(String pkg) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(pkg);
        for (ResolveInfo ri : context.getPackageManager().queryIntentActivities(i, 0)) {
            return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name).flattenToString();
        }
        return null;
    }
}
