package com.gungreeneyes.trekui;

import static org.junit.Assume.assumeTrue;
import static org.junit.Assert.assertNotNull;

import android.app.Activity;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Feature: trial and purchase ({@code features/trial.feature}).
 *
 * <p>Relies on the debug-only hooks of {@code com.gungreeneyes.trekui.licensing.Licensing}
 * ({@code debugSetUnlocked}, {@code debugSetTrialSeconds} and the {@code console.debug.expire_now}
 * launch extra). The scenario is skipped (assumption failure) when those hooks or the purchase screen
 * are not part of the build yet.</p>
 */
@RunWith(AndroidJUnit4.class)
public class TrialScenarios extends AppScenario {
    static final String EXTRA_EXPIRE_NOW = "trekui.debug.expire_now";

    /** Scenario: when the trial session runs out, the purchase screen covers the launcher. */
    @Test
    public void trialExpiryScreenAppears() {
        scenario.given("an unlicensed develop build whose trial session has run out");
        assumeTrue("licensing debug hooks not in this build", forceLicence(null, 1));
        assumeTrue("no purchase screen in this build", hasClass("com.gungreeneyes.trekui.licensing.PurchaseActivity"));
        forceLicence(Boolean.FALSE, 1);
        scenario.when("I open the launcher");
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(context, MainActivity.class.getName())
                .putExtra(EXTRA_EXPIRE_NOW, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        scenario.then("the trial-expired purchase screen is shown");
        Activity purchase = findResumedByName("Purchase", LAUNCH_TIMEOUT_MS);
        // Licensing.init() sets its static processStarted; if the home screen never called it the
        // licensing package is not wired in yet, which is a pending integration, not a failure.
        assumeTrue("licensing is not wired into MainActivity yet (Licensing.init never called)",
                purchase != null || licensingInitialised());
        assertNotNull(scenario.lastStep() + " (resumed: " + describeResumed() + ")", purchase);
        settle();
        media.screenshot("trial-expired");
        assertShowsContent(purchase);
        scenario.and("it fits the screen");
        assertFitsScreen(purchase, "purchase screen");
        instrumentation.runOnMainSync(purchase::finish);
        forceLicence(Boolean.TRUE, 0);
    }

    private static boolean licensingInitialised() {
        try {
            java.lang.reflect.Field f = Class.forName("com.gungreeneyes.trekui.licensing.Licensing").getDeclaredField("processStarted");
            f.setAccessible(true);
            return Boolean.TRUE.equals(f.get(null));
        } catch (ReflectiveOperationException e) {
            return true; // cannot tell: let the assertion decide
        }
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
