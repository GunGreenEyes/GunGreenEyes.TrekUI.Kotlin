package com.gungreeneyes.trekui.licensing;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.gungreeneyes.trekui.core.CoreConfig;
import com.gungreeneyes.trekui.core.SuiteClient;
import java.lang.ref.WeakReference;

/**
 * Public entry point of the licensing package (in {@code :core}, shared by every TrekUI app).
 *
 * <p>Model: every TrekUI app is free and ad-free. An unlicensed copy is fully functional, but each
 * session lasts {@link CoreConfig#trialSessionMinutes} (24 h in production, 5 min in the develop
 * flavor; the app supplies it through {@link CoreConfig}). When a session is over, the next time the
 * app's main screen comes to the front {@link PurchaseActivity} explains that a one-time purchase
 * ({@link CoreConfig#unlockProductId}, {@code trekui_unlock} in every app) is needed. From there the
 * user can buy, restore, start a new session, or (launcher) switch to another home app. A reboot also
 * starts a new session. One purchase unlocks every TrekUI app: a sibling's unlock is picked up through
 * {@link SuiteClient} and cached by {@link Entitlement#grantSuite}.
 *
 * <p>The main activity calls {@link #init(Activity)} from {@code onCreate} and
 * {@link #onHomeResumed(Activity)} (alias {@link #onAppResumed(Activity)}) from {@code onResume}. The
 * session end is only acted upon while that screen is in front: another app is never interrupted.
 *
 * <p>Test hooks (ignored in release builds): see the {@code EXTRA_DEBUG_*} constants and
 * {@link #debugSetUnlocked(Context, Boolean)} / {@link #debugSetTrialSeconds(Context, int)}.
 */
public final class Licensing {
    /** Debug builds: boolean extra on the home intent; true forces unlocked, false forces locked. */
    public static final String EXTRA_DEBUG_UNLOCK = "trekui.debug.unlock";
    /** Debug builds: boolean extra; true removes a forced state set by {@link #EXTRA_DEBUG_UNLOCK}. */
    public static final String EXTRA_DEBUG_CLEAR_OVERRIDE = "trekui.debug.clear_override";
    /** Debug builds: int extra; overrides the session length in seconds (0 restores the configured length). */
    public static final String EXTRA_DEBUG_TRIAL_SECONDS = "trekui.debug.trial_seconds";
    /** Debug builds: boolean extra; true starts a fresh session immediately. */
    public static final String EXTRA_DEBUG_RESET_TRIAL = "trekui.debug.reset_trial";
    /** Debug builds: boolean extra; true ends the current session now (shows the purchase screen). */
    public static final String EXTRA_DEBUG_EXPIRE_NOW = "trekui.debug.expire_now";

    static final String KEY_DEBUG_TRIAL_SECONDS = "debug.trial_seconds";
    /** Grace added to the scheduled check so it fires after, not just before, the deadline. */
    private static final long CHECK_SLACK_MS = 500L;

    private static Context appContext;
    private static KeyValueStore store;
    private static TrialClock clock;
    private static Entitlement entitlement;
    private static BillingManager billing;
    private static CoreConfig config;
    private static boolean processStarted;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> home = new WeakReference<>(null);
    private static final Runnable EXPIRY_CHECK = () -> {
        Activity a = home.get();
        if (a != null && !a.isFinishing()) checkSession(a);
    };

    private Licensing() {
    }

    /** Creates the singletons on first use (application context only, no Activity leaks). */
    private static synchronized void ensure(Context context) {
        if (appContext != null) return;
        appContext = context.getApplicationContext();
        config = CoreConfig.get(appContext);
        store = new PrefsKeyValueStore(appContext);
        clock = new TrialClock(store, new SystemTimeSource(appContext), configuredLimitMillis());
        entitlement = new Entitlement(store, appContext.getPackageName(), config.unlockProductId,
                config.debugBuild, isDevelopFlavor());
        billing = new BillingManager(appContext, entitlement);
    }

    /**
     * Call from the home activity's {@code onCreate}. Continues or starts the trial session,
     * connects to Google Play and restores purchases in the background.
     */
    public static void init(Activity activity) {
        ensure(activity);
        applyDebugExtras(activity.getIntent());
        if (!processStarted) {
            processStarted = true;
            clock.onProcessStart();
            activity.getApplication().registerActivityLifecycleCallbacks(new HomeTracker());
        }
        billing.start();
        checkSiblings();
    }

    /**
     * Call from the home activity's {@code onResume}. Shows the purchase screen if the session is
     * over, otherwise schedules a check for the moment it ends (cancelled when home is paused).
     */
    public static void onHomeResumed(Activity activity) {
        ensure(activity);
        applyDebugExtras(activity.getIntent());
        home = new WeakReference<>(activity);
        billing.refreshIfStale();
        checkSession(activity);
        checkSiblings();
    }

    /** Same as {@link #onHomeResumed(Activity)}; the name companion apps use from their main screen. */
    public static void onAppResumed(Activity activity) {
        onHomeResumed(activity);
    }

    /** True if this copy is unlocked (purchase cached, or a test override in debug builds). */
    public static boolean isUnlocked(Context context) {
        ensure(context);
        return entitlement.isUnlocked();
    }

    /** Milliseconds left in the trial session; {@link Long#MAX_VALUE} when unlocked. */
    public static long trialRemainingMillis(Context context) {
        ensure(context);
        return entitlement.isUnlocked() ? Long.MAX_VALUE : clock.remainingMillis();
    }

    /**
     * True if this copy may tell sibling apps it is unlocked (a real or suite unlock, or a simulated one
     * in the develop flavor; never a debug override). What the suite provider publishes.
     */
    public static boolean isShareableUnlock(Context context) {
        ensure(context);
        return entitlement.shareableUnlock();
    }

    /** Why this copy is unlocked (or NONE): what the suite provider publishes as the source. */
    public static Entitlement.Source unlockSource(Context context) {
        ensure(context);
        return entitlement.source();
    }

    /**
     * Records that the sibling app {@code fromPackage} reported itself unlocked through its
     * signature-protected suite provider: caches the suite grant and tells open purchase screens.
     * Called by {@link SuiteClient}; returns true if this unlocked the copy.
     */
    public static boolean grantFromSibling(Context context, String fromPackage) {
        ensure(context);
        boolean wasUnlocked = entitlement.isUnlocked();
        boolean granted = entitlement.grantSuite(fromPackage, System.currentTimeMillis());
        if (granted) MAIN.post(billing::notifyListeners);
        return granted && !wasUnlocked;
    }

    /** Opens the purchase screen on request (e.g. an "UNLOCK" entry in Settings). */
    public static void openPurchaseScreen(Context context) {
        ensure(context);
        PurchaseActivity.start(context, false);
    }

    /** Debug builds only: force unlocked (true), locked (false) or clear the override (null). */
    public static boolean debugSetUnlocked(Context context, Boolean unlocked) {
        ensure(context);
        boolean ok = entitlement.setDebugOverride(unlocked == null ? Entitlement.Override.NONE
                : unlocked ? Entitlement.Override.UNLOCKED : Entitlement.Override.LOCKED);
        billing.notifyListeners();
        return ok;
    }

    /** Debug builds only: session length in seconds; 0 restores {@code TRIAL_SESSION_MINUTES}. */
    public static boolean debugSetTrialSeconds(Context context, int seconds) {
        ensure(context);
        if (!config.debugBuild) return false;
        store.putLong(KEY_DEBUG_TRIAL_SECONDS, Math.max(0, seconds));
        clock.setLimitMillis(configuredLimitMillis());
        return true;
    }

    // ---- package-internal API used by PurchaseActivity ----

    static TrialClock clock() {
        return clock;
    }

    static Entitlement entitlement() {
        return entitlement;
    }

    static BillingManager billing() {
        return billing;
    }

    static boolean isDevelopFlavor() {
        return config != null && config.developFlavor;
    }

    /** The app's config (valid after any public call). */
    static CoreConfig config() {
        return config;
    }

    /** Asks installed sibling apps (in the background) whether one of them is unlocked. */
    private static void checkSiblings() {
        if (!entitlement.isUnlocked()) SuiteClient.checkSiblingsAsync(appContext, null);
    }

    /** Explicit relaunch from the expiry screen: a fresh session begins now. */
    static void startNewSession() {
        clock.setLimitMillis(configuredLimitMillis()); // drops a one-off EXTRA_DEBUG_EXPIRE_NOW
        clock.startNewSession();
    }

    /** Session length: the app's CoreConfig, or the debug override in debug builds. */
    static long configuredLimitMillis() {
        if (config.debugBuild && store != null) {
            long seconds = store.getLong(KEY_DEBUG_TRIAL_SECONDS, 0L);
            if (seconds > 0) return seconds * 1000L;
        }
        return config.trialSessionMinutes * 60_000L;
    }

    private static void checkSession(Activity activity) {
        MAIN.removeCallbacks(EXPIRY_CHECK);
        if (entitlement.isUnlocked()) return;
        clock.ensureSession();
        if (clock.isExpired()) {
            clock.markExpiryShown();
            PurchaseActivity.start(activity, true);
            return;
        }
        long remaining = clock.remainingMillis();
        if (remaining != Long.MAX_VALUE) MAIN.postDelayed(EXPIRY_CHECK, remaining + CHECK_SLACK_MS);
    }

    /** Handles the debug-only intent extras; a no-op in release builds. */
    private static void applyDebugExtras(Intent intent) {
        if (!config.debugBuild || intent == null || intent.getExtras() == null) return;
        if (intent.hasExtra(EXTRA_DEBUG_UNLOCK)) {
            debugSetUnlocked(appContext, intent.getBooleanExtra(EXTRA_DEBUG_UNLOCK, false));
            intent.removeExtra(EXTRA_DEBUG_UNLOCK);
        }
        if (intent.getBooleanExtra(EXTRA_DEBUG_CLEAR_OVERRIDE, false)) {
            debugSetUnlocked(appContext, null);
            intent.removeExtra(EXTRA_DEBUG_CLEAR_OVERRIDE);
        }
        if (intent.hasExtra(EXTRA_DEBUG_TRIAL_SECONDS)) {
            debugSetTrialSeconds(appContext, intent.getIntExtra(EXTRA_DEBUG_TRIAL_SECONDS, 0));
            intent.removeExtra(EXTRA_DEBUG_TRIAL_SECONDS);
        }
        if (intent.getBooleanExtra(EXTRA_DEBUG_RESET_TRIAL, false)) {
            startNewSession();
            intent.removeExtra(EXTRA_DEBUG_RESET_TRIAL);
        }
        if (intent.getBooleanExtra(EXTRA_DEBUG_EXPIRE_NOW, false)) {
            clock.setLimitMillis(1L);
            store.putLong(KEY_DEBUG_TRIAL_SECONDS, 0L); // next process start uses the configured length again
            intent.removeExtra(EXTRA_DEBUG_EXPIRE_NOW);
        }
    }

    /** Cancels the scheduled expiry check whenever the home activity leaves the foreground. */
    private static final class HomeTracker implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityPaused(Activity activity) {
            if (activity == home.get()) MAIN.removeCallbacks(EXPIRY_CHECK);
        }

        @Override public void onActivityDestroyed(Activity activity) {
            if (activity == home.get()) {
                MAIN.removeCallbacks(EXPIRY_CHECK);
                home = new WeakReference<>(null);
            }
        }

        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }

        @Override public void onActivityStarted(Activity activity) { }

        @Override public void onActivityResumed(Activity activity) { }

        @Override public void onActivityStopped(Activity activity) { }

        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
    }
}
