package com.gungreeneyes.trekui.messages

import android.app.Application
import com.gungreeneyes.trekui.core.CoreConfig

/**
 * TrekUI Messages' Application: hands its BuildConfig values (trial length, flavor, product) to :core's
 * licensing, applies the launcher's last known interface before the first screen draws, and creates the
 * notification channels.
 */
class MessagesApp : Application(), CoreConfig.Provider {
    override fun onCreate() {
        super.onCreate()
        MessagesTheme.applyCached(this)
        Notifier.ensureChannels(this)
    }

    override fun coreConfig(): CoreConfig =
        CoreConfig.Builder("TREKUI MESSAGES")
            .trialSessionMinutes(BuildConfig.TRIAL_SESSION_MINUTES)
            .unlockProductId(BuildConfig.UNLOCK_PRODUCT_ID)
            .debugBuild(BuildConfig.DEBUG)
            .developFlavor(BuildConfig.FLAVOR == "develop")
            .homeApp(false)
            .build()
}
