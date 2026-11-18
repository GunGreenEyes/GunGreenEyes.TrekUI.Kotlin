package com.gungreeneyes.trekui.core

import android.content.Context

/**
 * The per-app build values :core needs, which a library cannot read from its own BuildConfig (it has no
 * product flavors): trial session length, Play product id of the unlock, debug/develop switches and the
 * app's display name for the purchase screen.
 *
 * How an app supplies it: its [android.app.Application] implements [Provider] and returns
 * a config built from the app's `BuildConfig`. :core asks for it lazily through [get],
 * which works from any entry point (activity, service, content provider) because the Application object
 * exists before any of them. [install] is an explicit alternative (tests). Without
 * either, [DEFAULT] applies (24 h sessions, [SuiteContract.UNLOCK_PRODUCT_ID], release).
 *
 * ```
 * public final class MessagesApp extends Application implements CoreConfig.Provider {
 *     @Override public CoreConfig coreConfig() {
 *         return new CoreConfig.Builder("TREKUI MESSAGES")
 *                 .trialSessionMinutes(BuildConfig.TRIAL_SESSION_MINUTES)
 *                 .debugBuild(BuildConfig.DEBUG)
 *                 .developFlavor("develop".equals(BuildConfig.FLAVOR))
 *                 .build();
 *     }
 * }
 * ```
 *
 * The values are [JvmField]s and [Builder] stays a class, so the Java apps above compile unchanged.
 */
class CoreConfig private constructor(
    /** Minutes an unlicensed copy runs per session before the purchase screen appears. */
    @JvmField val trialSessionMinutes: Long,
    /** Play product id of the one-time unlock (the same in every TrekUI app). */
    @JvmField val unlockProductId: String,
    /** Debug build: honour test overrides (forced unlock, session length extras). */
    @JvmField val debugBuild: Boolean,
    /** develop flavor: allow a simulated purchase (long-press on the purchase screen header). */
    @JvmField val developFlavor: Boolean,
    /** Display name in purchase-screen copy, e.g. "TREKUI" or "TREKUI MESSAGES". */
    @JvmField val appName: String,
    /** True for the launcher: the purchase screen offers "use another home app" and home-screen wording. */
    @JvmField val homeApp: Boolean,
) {
    /** Implemented by an app's Application class to hand its config to :core. */
    fun interface Provider {
        fun coreConfig(): CoreConfig?
    }

    /** Builds a [CoreConfig]; every value has the production default. */
    class Builder(private val appName: String) {
        private var trialSessionMinutes = 1440L
        private var unlockProductId = SuiteContract.UNLOCK_PRODUCT_ID
        private var debugBuild = false
        private var developFlavor = false
        private var homeApp = false

        fun trialSessionMinutes(minutes: Long): Builder = apply { trialSessionMinutes = maxOf(1L, minutes) }

        fun unlockProductId(id: String): Builder = apply { unlockProductId = id }

        fun debugBuild(debug: Boolean): Builder = apply { debugBuild = debug }

        fun developFlavor(develop: Boolean): Builder = apply { developFlavor = develop }

        fun homeApp(home: Boolean): Builder = apply { homeApp = home }

        fun build(): CoreConfig =
            CoreConfig(trialSessionMinutes, unlockProductId, debugBuild, developFlavor, appName, homeApp)
    }

    companion object {
        /**
         * The sound update channel (docs/SOUNDS.md): `manifest.json` and the project's own sound files live
         * here. The one place this address is written; [com.gungreeneyes.trekui.SoundCatalog] and
         * [com.gungreeneyes.trekui.SoundUpdate] read it from here, and tools/sounds/publish-sfx.py parses it
         * from this line. The bare host: the site redirects www to it, and the HTTPS helper follows no
         * redirects.
         */
        const val SFX_BASE_URL = "https://mindattic.com/gungreeneyes/trekui/sfx/"

        /** Production defaults: 24-hour sessions, the shared unlock product, release build. */
        @JvmField
        val DEFAULT: CoreConfig = Builder("TREKUI").build()

        @Volatile
        private var installed: CoreConfig? = null

        /** Explicitly installs the config (overrides the Application's [Provider]); null clears it. */
        @JvmStatic
        fun install(config: CoreConfig?) {
            installed = config
        }

        /** The app's config: installed one, else the Application's [Provider], else [DEFAULT]. */
        @JvmStatic
        fun get(context: Context?): CoreConfig {
            installed?.let { return it }
            val app = context?.applicationContext
            if (app is Provider) {
                val c = app.coreConfig()
                if (c != null) {
                    installed = c
                    return c
                }
            }
            return DEFAULT
        }
    }
}
