package com.gungreeneyes.trekui.core;

import android.content.Context;

/**
 * The per-app build values :core needs, which a library cannot read from its own BuildConfig (it has no
 * product flavors): trial session length, Play product id of the unlock, debug/develop switches and the
 * app's display name for the purchase screen.
 *
 * <p>How an app supplies it: its {@link android.app.Application} implements {@link Provider} and returns
 * a config built from the app's {@code BuildConfig}. :core asks for it lazily through {@link #get(Context)},
 * which works from any entry point (activity, service, content provider) because the Application object
 * exists before any of them. {@link #install(CoreConfig)} is an explicit alternative (tests). Without
 * either, {@link #DEFAULT} applies (24 h sessions, {@link SuiteContract#UNLOCK_PRODUCT_ID}, release).
 *
 * <pre>
 * public final class MessagesApp extends Application implements CoreConfig.Provider {
 *     &#64;Override public CoreConfig coreConfig() {
 *         return new CoreConfig.Builder("TREKUI MESSAGES")
 *                 .trialSessionMinutes(BuildConfig.TRIAL_SESSION_MINUTES)
 *                 .debugBuild(BuildConfig.DEBUG)
 *                 .developFlavor("develop".equals(BuildConfig.FLAVOR))
 *                 .build();
 *     }
 * }
 * </pre>
 */
public final class CoreConfig {
    /** Implemented by an app's Application class to hand its config to :core. */
    public interface Provider {
        CoreConfig coreConfig();
    }

    /** Production defaults: 24-hour sessions, the shared unlock product, release build. */
    public static final CoreConfig DEFAULT = new Builder("TREKUI").build();

    private static volatile CoreConfig installed;

    /** Minutes an unlicensed copy runs per session before the purchase screen appears. */
    public final long trialSessionMinutes;
    /** Play product id of the one-time unlock (the same in every TrekUI app). */
    public final String unlockProductId;
    /** Debug build: honour test overrides (forced unlock, session length extras). */
    public final boolean debugBuild;
    /** develop flavor: allow a simulated purchase (long-press on the purchase screen header). */
    public final boolean developFlavor;
    /** Display name in purchase-screen copy, e.g. "TREKUI" or "TREKUI MESSAGES". */
    public final String appName;
    /** True for the launcher: the purchase screen offers "use another home app" and home-screen wording. */
    public final boolean homeApp;

    private CoreConfig(Builder b) {
        trialSessionMinutes = b.trialSessionMinutes;
        unlockProductId = b.unlockProductId;
        debugBuild = b.debugBuild;
        developFlavor = b.developFlavor;
        appName = b.appName;
        homeApp = b.homeApp;
    }

    /** Explicitly installs the config (overrides the Application's {@link Provider}); null clears it. */
    public static void install(CoreConfig config) {
        installed = config;
    }

    /** The app's config: installed one, else the Application's {@link Provider}, else {@link #DEFAULT}. */
    public static CoreConfig get(Context context) {
        CoreConfig c = installed;
        if (c != null) return c;
        Context app = context == null ? null : context.getApplicationContext();
        if (app instanceof Provider) {
            c = ((Provider) app).coreConfig();
            if (c != null) {
                installed = c;
                return c;
            }
        }
        return DEFAULT;
    }

    /** Builds a {@link CoreConfig}; every value has the production default. */
    public static final class Builder {
        private long trialSessionMinutes = 1440;
        private String unlockProductId = SuiteContract.UNLOCK_PRODUCT_ID;
        private boolean debugBuild;
        private boolean developFlavor;
        private final String appName;
        private boolean homeApp;

        public Builder(String appName) {
            this.appName = appName;
        }

        public Builder trialSessionMinutes(long minutes) {
            trialSessionMinutes = Math.max(1, minutes);
            return this;
        }

        public Builder unlockProductId(String id) {
            unlockProductId = id;
            return this;
        }

        public Builder debugBuild(boolean debug) {
            debugBuild = debug;
            return this;
        }

        public Builder developFlavor(boolean develop) {
            developFlavor = develop;
            return this;
        }

        public Builder homeApp(boolean home) {
            homeApp = home;
            return this;
        }

        public CoreConfig build() {
            return new CoreConfig(this);
        }
    }
}
