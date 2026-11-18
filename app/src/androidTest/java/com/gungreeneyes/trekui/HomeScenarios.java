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
            assertTrue(scenario.lastStep() + ": still not default home, current " + currentHomePackage(), awaitDefaultHome(5000));
            scenario.and("pressing Home shows the TrekUI home screen");
            device.pressHome();
            boolean shown = device.wait(Until.hasObject(By.pkg(context.getPackageName()).depth(0)), 10000);
            if (!shown) {
                // Android 12L applies a new HOME role holder late: the first press can still reach the old one.
                SystemClock.sleep(1500);
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

    private String currentHomePackage() {
        ResolveInfo ri = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return ri == null || ri.activityInfo == null ? null : ri.activityInfo.packageName;
    }

    /** Picks this launcher in the system role/home-picker dialog; true if something was clicked. */
    private boolean answerHomeDialog() {
        String label = context.getApplicationInfo().loadLabel(context.getPackageManager()).toString();
        boolean clicked = false;
        UiObject2 entry = device.wait(Until.findObject(By.text(label)), 4000);
        if (entry != null) {
            entry.click();
            clicked = true;
            SystemClock.sleep(600);
        }
        BySelector confirm = By.text(Pattern.compile("(?i)(set as default|always|ok|allow|yes)"));
        UiObject2 button = device.wait(Until.findObject(confirm), 3000);
        if (button != null) {
            media.screenshot("role-confirm");
            button.click();
            clicked = true;
        }
        return clicked;
    }

    /** Makes {@code pkg} the home app from the shell (API 29+: role; older: preferred activity). */
    private void setHomeByShell(String pkg, ComponentName component) {
        if (Build.VERSION.SDK_INT >= 30) {
            Log.i(TAG, media.shell("cmd role add-role-holder android.app.role.HOME " + pkg + " 0"));
        } else if (Build.VERSION.SDK_INT == 29) {
            // Android 10's role shell command has no FLAGS argument; passing one makes it fail.
            Log.i(TAG, media.shell("cmd role add-role-holder android.app.role.HOME " + pkg));
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
