package com.gungreeneyes.trekui.keyboard;

import android.app.Application;

import com.gungreeneyes.trekui.KeyboardLook;
import com.gungreeneyes.trekui.core.CoreConfig;

/**
 * TrekUI Keyboard's Application: hands its BuildConfig values (trial length, flavor, product) to :core's
 * licensing, applies the launcher's last known interface before the keyboard first draws, and sets the
 * keyboard's own sound defaults (synthesized clicks).
 */
public final class KeyboardApp extends Application implements CoreConfig.Provider {
    @Override
    public void onCreate() {
        super.onCreate();
        KeyboardTheme.applyCached(this);
        KeyboardLook.ensureSoundDefaults(this);
    }

    @Override
    public CoreConfig coreConfig() {
        return new CoreConfig.Builder("TREKUI KEYBOARD")
                .trialSessionMinutes(BuildConfig.TRIAL_SESSION_MINUTES)
                .unlockProductId(BuildConfig.UNLOCK_PRODUCT_ID)
                .debugBuild(BuildConfig.DEBUG)
                .developFlavor("develop".equals(BuildConfig.FLAVOR))
                .homeApp(false)
                .build();
    }
}
