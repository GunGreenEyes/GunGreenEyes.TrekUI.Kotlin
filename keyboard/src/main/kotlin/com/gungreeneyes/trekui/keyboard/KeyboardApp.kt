package com.gungreeneyes.trekui.keyboard

import android.app.Application
import com.gungreeneyes.trekui.KeyboardLook
import com.gungreeneyes.trekui.core.CoreConfig

/**
 * TrekUI Keyboard's Application: hands its BuildConfig values (trial length, flavor, product) to :core's
 * licensing, applies the launcher's last known interface before the keyboard first draws, and sets the
 * keyboard's own sound defaults (synthesized clicks).
 */
class KeyboardApp : Application(), CoreConfig.Provider {
    override fun onCreate() {
        super.onCreate()
        KeyboardTheme.applyCached(this)
        KeyboardLook.ensureSoundDefaults(this)
    }

    override fun coreConfig(): CoreConfig =
        CoreConfig.Builder("TREKUI KEYBOARD")
            .trialSessionMinutes(BuildConfig.TRIAL_SESSION_MINUTES)
            .unlockProductId(BuildConfig.UNLOCK_PRODUCT_ID)
            .debugBuild(BuildConfig.DEBUG)
            .developFlavor(BuildConfig.FLAVOR == "develop")
            .homeApp(false)
            .build()
}
