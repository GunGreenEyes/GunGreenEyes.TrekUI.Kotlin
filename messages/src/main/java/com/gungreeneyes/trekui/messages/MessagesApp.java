package com.gungreeneyes.trekui.messages;

import android.app.Application;

import com.gungreeneyes.trekui.core.CoreConfig;

/**
 * TrekUI Messages' Application: hands its BuildConfig values (trial length, flavor, product) to :core's
 * licensing, applies the launcher's last known interface before the first screen draws, and creates the
 * notification channels.
 */
public final class MessagesApp extends Application implements CoreConfig.Provider {
    @Override
    public void onCreate() {
        super.onCreate();
        MessagesTheme.applyCached(this);
        Notifier.ensureChannels(this);
    }

    @Override
    public CoreConfig coreConfig() {
        return new CoreConfig.Builder("TREKUI MESSAGES")
                .trialSessionMinutes(BuildConfig.TRIAL_SESSION_MINUTES)
                .unlockProductId(BuildConfig.UNLOCK_PRODUCT_ID)
                .debugBuild(BuildConfig.DEBUG)
                .developFlavor("develop".equals(BuildConfig.FLAVOR))
                .homeApp(false)
                .build();
    }
}
