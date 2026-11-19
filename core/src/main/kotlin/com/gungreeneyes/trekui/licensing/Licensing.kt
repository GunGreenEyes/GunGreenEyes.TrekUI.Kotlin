package com.gungreeneyes.trekui.licensing

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.gungreeneyes.trekui.core.CoreConfig
import com.gungreeneyes.trekui.core.SuiteClient
import java.lang.ref.WeakReference

/**
 * Public entry point of the licensing package (in `:core`, shared by every TrekUI app).
 *
 * Model: every TrekUI app is free and ad-free. An unlicensed copy is fully functional, but each
 * session lasts [CoreConfig.trialSessionMinutes] (24 h in production, 5 min in the develop flavor; the
 * app supplies it through [CoreConfig]). When a session is over, the next time the app's main screen
 * comes to the front [PurchaseActivity] explains that a one-time purchase ([CoreConfig.unlockProductId],
 * `trekui_unlock` in every app) is needed. From there the user can buy, restore, start a new session,
 * or (launcher) switch to another home app. A reboot also starts a new session. One purchase unlocks
 * every TrekUI app: a sibling's unlock is picked up through [SuiteClient] and cached by
 * [Entitlement.grantSuite].
 *
 * The main activity calls [init] from `onCreate` and [onHomeResumed] (alias [onAppResumed]) from
 * `onResume`. The session end is only acted upon while that screen is in front: another app is never
 * interrupted.
 *
 * Test hooks (ignored in release builds): see the `EXTRA_DEBUG_*` constants and [debugSetUnlocked] /
 * [debugSetTrialSeconds].
 *
 * The public functions are `@JvmStatic` so Java callers keep `Licensing.init(this)`; the androidTest
 * scenarios also reach `debugSetUnlocked`, `debugSetTrialSeconds` and the static `processStarted` field
 * by reflection, so those names must not change.
 */
object Licensing {
    /** Debug builds: boolean extra on the home intent; true forces unlocked, false forces locked. */
    const val EXTRA_DEBUG_UNLOCK: String = "trekui.debug.unlock"

    /** Debug builds: boolean extra; true removes a forced state set by [EXTRA_DEBUG_UNLOCK]. */
    const val EXTRA_DEBUG_CLEAR_OVERRIDE: String = "trekui.debug.clear_override"

    /** Debug builds: int extra; overrides the session length in seconds (0 restores the configured length). */
    const val EXTRA_DEBUG_TRIAL_SECONDS: String = "trekui.debug.trial_seconds"

    /** Debug builds: boolean extra; true starts a fresh session immediately. */
    const val EXTRA_DEBUG_RESET_TRIAL: String = "trekui.debug.reset_trial"

    /** Debug builds: boolean extra; true ends the current session now (shows the purchase screen). */
    const val EXTRA_DEBUG_EXPIRE_NOW: String = "trekui.debug.expire_now"

    internal const val KEY_DEBUG_TRIAL_SECONDS: String = "debug.trial_seconds"

    /** Grace added to the scheduled check so it fires after, not just before, the deadline. */
    private const val CHECK_SLACK_MS = 500L

    private lateinit var appContext: Context
    private lateinit var store: KeyValueStore

    /** The trial clock (valid after any public call). */
    internal lateinit var clock: TrialClock
        private set

    /** The cached unlock state (valid after any public call). */
    internal lateinit var entitlement: Entitlement
        private set

    /** The Play Billing wrapper (valid after any public call). */
    internal lateinit var billing: BillingManager
        private set

    /** The app's config (valid after any public call). */
    internal lateinit var config: CoreConfig
        private set

    /** Read by reflection in TrialScenarios (androidTest): keep the name. */
    private var processStarted = false
    private val main = Handler(Looper.getMainLooper())
    private var home = WeakReference<Activity>(null)
    private val expiryCheck = Runnable {
        val a = home.get()
        if (a != null && !a.isFinishing) checkSession(a)
    }

    /**
     * Creates the singletons on first use (application context only, no Activity leaks). Also called by
     * [PurchaseActivity], which Android may restore first after process death.
     */
    @Synchronized
    internal fun ensure(context: Context) {
        if (::appContext.isInitialized) return
        val app = context.applicationContext
        config = CoreConfig.get(app)
        store = PrefsKeyValueStore(app)
        clock = TrialClock(store, SystemTimeSource(app), configuredLimitMillis())
        entitlement = Entitlement(store, app.packageName, config.unlockProductId, config.debugBuild, isDevelopFlavor())
        billing = BillingManager(app, entitlement)
        appContext = app // last: marks the singletons as created
    }

    /**
     * Call from the home activity's `onCreate`. Continues or starts the trial session, connects to
     * Google Play and restores purchases in the background.
     */
    @JvmStatic
    fun init(activity: Activity) {
        ensure(activity)
        applyDebugExtras(activity.intent)
        if (!processStarted) {
            processStarted = true
            clock.onProcessStart()
            activity.application.registerActivityLifecycleCallbacks(HomeTracker())
        }
        billing.start()
        checkSiblings()
    }

    /**
     * Call from the home activity's `onResume`. Shows the purchase screen if the session is over,
     * otherwise schedules a check for the moment it ends (cancelled when home is paused).
     */
    @JvmStatic
    fun onHomeResumed(activity: Activity) {
        ensure(activity)
        applyDebugExtras(activity.intent)
        home = WeakReference(activity)
        billing.refreshIfStale()
        checkSession(activity)
        checkSiblings()
    }

    /** Same as [onHomeResumed]; the name companion apps use from their main screen. */
    @JvmStatic
    fun onAppResumed(activity: Activity) {
        onHomeResumed(activity)
    }

    /** True if this copy is unlocked (purchase cached, or a test override in debug builds). */
    @JvmStatic
    fun isUnlocked(context: Context): Boolean {
        ensure(context)
        return entitlement.isUnlocked()
    }

    /** Milliseconds left in the trial session; [Long.MAX_VALUE] when unlocked. */
    @JvmStatic
    fun trialRemainingMillis(context: Context): Long {
        ensure(context)
        return if (entitlement.isUnlocked()) Long.MAX_VALUE else clock.remainingMillis()
    }

    /**
     * True if this copy may tell sibling apps it is unlocked (its own verified purchase, or a simulated one
     * in the develop flavor; never a suite grant or a debug override). What the suite provider publishes.
     */
    @JvmStatic
    fun isShareableUnlock(context: Context): Boolean {
        ensure(context)
        return entitlement.shareableUnlock()
    }

    /** Why this copy is unlocked (or NONE): what the suite provider publishes as the source. */
    @JvmStatic
    fun unlockSource(context: Context): Entitlement.Source {
        ensure(context)
        return entitlement.source()
    }

    /**
     * Records that the sibling app [fromPackage] reported itself unlocked through its
     * signature-protected suite provider: caches the suite grant and tells open purchase screens.
     * Called by [SuiteClient]; returns true if this unlocked the copy.
     */
    @JvmStatic
    fun grantFromSibling(context: Context, fromPackage: String?): Boolean {
        ensure(context)
        val wasUnlocked = entitlement.isUnlocked()
        val granted = entitlement.grantSuite(fromPackage, System.currentTimeMillis())
        if (granted) main.post { billing.notifyListeners() }
        return granted && !wasUnlocked
    }

    /** Package of the sibling whose suite grant is cached, or null. Called by [SuiteClient]. */
    @JvmStatic
    fun suiteGrantSource(context: Context): String? {
        ensure(context)
        return entitlement.suiteGrantSource()
    }

    /**
     * Records that [fromPackage], whose grant is cached, answered that it no longer owns a purchase
     * (e.g. refunded): drops the grant and tells open purchase screens. Called by [SuiteClient].
     */
    @JvmStatic
    fun revokeSiblingGrant(context: Context, fromPackage: String?): Boolean {
        ensure(context)
        val revoked = entitlement.revokeSuite(fromPackage)
        if (revoked) main.post { billing.notifyListeners() }
        return revoked
    }

    /** Opens the purchase screen on request (e.g. an "UNLOCK" entry in Settings). */
    @JvmStatic
    fun openPurchaseScreen(context: Context) {
        ensure(context)
        PurchaseActivity.start(context, false)
    }

    /** Debug builds only: force unlocked (true), locked (false) or clear the override (null). */
    @JvmStatic
    fun debugSetUnlocked(context: Context, unlocked: Boolean?): Boolean {
        ensure(context)
        val override = when (unlocked) {
            null -> Entitlement.Override.NONE
            true -> Entitlement.Override.UNLOCKED
            false -> Entitlement.Override.LOCKED
        }
        val ok = entitlement.setDebugOverride(override)
        billing.notifyListeners()
        return ok
    }

    /** Debug builds only: session length in seconds; 0 restores the configured session length. */
    @JvmStatic
    fun debugSetTrialSeconds(context: Context, seconds: Int): Boolean {
        ensure(context)
        if (!config.debugBuild) return false
        store.putLong(KEY_DEBUG_TRIAL_SECONDS, seconds.coerceAtLeast(0).toLong())
        clock.setLimitMillis(configuredLimitMillis())
        return true
    }

    // ---- module-internal API used by PurchaseActivity ----

    internal fun isDevelopFlavor(): Boolean = ::config.isInitialized && config.developFlavor

    /**
     * Asks installed sibling apps (in the background) whether one of them owns a purchase; also runs while
     * only a suite grant unlocks this copy, so the grant is revoked once its sibling is refunded.
     */
    private fun checkSiblings() {
        if (!entitlement.isUnlocked() || entitlement.hasSuiteGrant()) SuiteClient.checkSiblingsAsync(appContext, null)
    }

    /** Explicit relaunch from the expiry screen: a fresh session begins now. */
    internal fun startNewSession() {
        clock.setLimitMillis(configuredLimitMillis()) // drops a one-off EXTRA_DEBUG_EXPIRE_NOW
        clock.startNewSession()
    }

    /** Session length: the app's CoreConfig, or the debug override in debug builds. */
    internal fun configuredLimitMillis(): Long {
        if (config.debugBuild && ::store.isInitialized) {
            val seconds = store.getLong(KEY_DEBUG_TRIAL_SECONDS, 0L)
            if (seconds > 0) return seconds * 1000L
        }
        return config.trialSessionMinutes * 60_000L
    }

    private fun checkSession(activity: Activity) {
        main.removeCallbacks(expiryCheck)
        if (entitlement.isUnlocked()) return
        clock.ensureSession()
        if (clock.isExpired()) {
            clock.markExpiryShown()
            PurchaseActivity.start(activity, true)
            return
        }
        val remaining = clock.remainingMillis()
        if (remaining != Long.MAX_VALUE) main.postDelayed(expiryCheck, remaining + CHECK_SLACK_MS)
    }

    /** Handles the debug-only intent extras; a no-op in release builds. */
    private fun applyDebugExtras(intent: Intent?) {
        if (!config.debugBuild || intent == null || intent.extras == null) return
        if (intent.hasExtra(EXTRA_DEBUG_UNLOCK)) {
            debugSetUnlocked(appContext, intent.getBooleanExtra(EXTRA_DEBUG_UNLOCK, false))
            intent.removeExtra(EXTRA_DEBUG_UNLOCK)
        }
        if (intent.getBooleanExtra(EXTRA_DEBUG_CLEAR_OVERRIDE, false)) {
            debugSetUnlocked(appContext, null)
            intent.removeExtra(EXTRA_DEBUG_CLEAR_OVERRIDE)
        }
        if (intent.hasExtra(EXTRA_DEBUG_TRIAL_SECONDS)) {
            debugSetTrialSeconds(appContext, intent.getIntExtra(EXTRA_DEBUG_TRIAL_SECONDS, 0))
            intent.removeExtra(EXTRA_DEBUG_TRIAL_SECONDS)
        }
        if (intent.getBooleanExtra(EXTRA_DEBUG_RESET_TRIAL, false)) {
            startNewSession()
            intent.removeExtra(EXTRA_DEBUG_RESET_TRIAL)
        }
        if (intent.getBooleanExtra(EXTRA_DEBUG_EXPIRE_NOW, false)) {
            clock.setLimitMillis(1L)
            store.putLong(KEY_DEBUG_TRIAL_SECONDS, 0L) // next process start uses the configured length again
            intent.removeExtra(EXTRA_DEBUG_EXPIRE_NOW)
        }
    }

    /** Cancels the scheduled expiry check whenever the home activity leaves the foreground. */
    private class HomeTracker : Application.ActivityLifecycleCallbacks {
        override fun onActivityPaused(activity: Activity) {
            if (activity === home.get()) main.removeCallbacks(expiryCheck)
        }

        override fun onActivityDestroyed(activity: Activity) {
            if (activity === home.get()) {
                main.removeCallbacks(expiryCheck)
                home = WeakReference(null)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }
}
