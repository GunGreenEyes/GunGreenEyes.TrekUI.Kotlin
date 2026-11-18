package com.gungreeneyes.trekui.lockscreen;

import android.app.Application;

import com.gungreeneyes.trekui.core.CoreConfig;

/**
 * TrekUI Lock Screen's Application: hands its BuildConfig values (trial length, flavor, product) to
 * :core's licensing and applies the launcher's last known interface before the first frame draws.
 */
public final class LockApp extends Application implements CoreConfig.Provider {
    @Override
    public void onCreate() {
        super.onCreate();
        LockTheme.applyCached(this);
    }

    @Override
    public CoreConfig coreConfig() {
        return new CoreConfig.Builder("TREKUI LOCK SCREEN")
                .trialSessionMinutes(BuildConfig.TRIAL_SESSION_MINUTES)
                .unlockProductId(BuildConfig.UNLOCK_PRODUCT_ID)
                .debugBuild(BuildConfig.DEBUG)
                .developFlavor("develop".equals(BuildConfig.FLAVOR))
                .homeApp(false)
                .build();
    }
}
