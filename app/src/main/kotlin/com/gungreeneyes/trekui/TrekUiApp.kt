package com.gungreeneyes.trekui

import android.app.Application
import com.gungreeneyes.trekui.core.CoreConfig

/** The launcher's Application: hands its BuildConfig values (trial length, flavor, product) to :core. */
class TrekUiApp : Application(), CoreConfig.Provider {
    override fun coreConfig(): CoreConfig =
        CoreConfig.Builder("TREKUI")
            .trialSessionMinutes(BuildConfig.TRIAL_SESSION_MINUTES)
            .unlockProductId(BuildConfig.UNLOCK_PRODUCT_ID)
            .debugBuild(BuildConfig.DEBUG)
            .developFlavor("develop" == BuildConfig.FLAVOR)
            .homeApp(true)
            .build()
}
