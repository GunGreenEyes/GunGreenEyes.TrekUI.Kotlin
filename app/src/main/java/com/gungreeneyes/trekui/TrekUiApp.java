package com.gungreeneyes.trekui;

import android.app.Application;

import com.gungreeneyes.trekui.core.CoreConfig;

/** The launcher's Application: hands its BuildConfig values (trial length, flavor, product) to :core. */
public final class TrekUiApp extends Application implements CoreConfig.Provider {
    @Override
    public CoreConfig coreConfig() {
        return new CoreConfig.Builder("TREKUI")
                .trialSessionMinutes(BuildConfig.TRIAL_SESSION_MINUTES)
                .unlockProductId(BuildConfig.UNLOCK_PRODUCT_ID)
                .debugBuild(BuildConfig.DEBUG)
                .developFlavor("develop".equals(BuildConfig.FLAVOR))
                .homeApp(true)
                .build();
    }
}
