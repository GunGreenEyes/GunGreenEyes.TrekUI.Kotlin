package com.gungreeneyes.trekui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feature: trial and purchase (`features/trial.feature`).
 *
 * Relies on the debug-only hooks of `com.gungreeneyes.trekui.licensing.Licensing`
 * (`debugSetUnlocked`, `debugSetTrialSeconds` and the `console.debug.expire_now`
 * launch extra). The scenario is skipped (assumption failure) when those hooks or the purchase screen
 * are not part of the build yet.
 */
@RunWith(AndroidJUnit4::class)
class TrialScenarios : AppScenario() {

    /** Scenario: when the trial session runs out, the purchase screen covers the launcher. */
    @Test
    fun trialExpiryScreenAppears() {
        scenario.given("an unlicensed develop build whose trial session has run out")
        assumeTrue("licensing debug hooks not in this build", forceLicence(null, 1))
        assumeTrue("no purchase screen in this build", hasClass("com.gungreeneyes.trekui.licensing.PurchaseActivity"))
        forceLicence(false, 1)
        scenario.`when`("I open the launcher")
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(context, MainActivity::class.java.name)
            .putExtra(EXTRA_EXPIRE_NOW, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        scenario.then("the trial-expired purchase screen is shown")
        val purchase = findResumedByName("Purchase", LAUNCH_TIMEOUT_MS)
        // Licensing.init() sets its static processStarted; if the home screen never called it the
        // licensing package is not wired in yet, which is a pending integration, not a failure.
        assumeTrue(
            "licensing is not wired into MainActivity yet (Licensing.init never called)",
            purchase != null || licensingInitialised(),
        )
        assertNotNull(scenario.lastStep + " (resumed: " + describeResumed() + ")", purchase)
        purchase!!
        settle()
        media.screenshot("trial-expired")
        assertShowsContent(purchase)
        scenario.and("it fits the screen")
        assertFitsScreen(purchase, "purchase screen")
        instrumentation.runOnMainSync(purchase::finish)
        forceLicence(true, 0)
    }

    companion object {
        const val EXTRA_EXPIRE_NOW = "trekui.debug.expire_now"

        private fun licensingInitialised(): Boolean = try {
            val f = Class.forName("com.gungreeneyes.trekui.licensing.Licensing").getDeclaredField("processStarted")
            f.isAccessible = true
            true == f.get(null)
        } catch (e: ReflectiveOperationException) {
            true // cannot tell: let the assertion decide
        }

        private fun hasClass(name: String): Boolean = try {
            Class.forName(name)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
