package com.gungreeneyes.trekui;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/**
 * The optional accessibility service behind the LOCK SCREEN function (gap A9).
 *
 * <p>It does exactly one thing: when the user runs LOCK SCREEN (from a tile, element or gesture they
 * assigned), {@link LockScreen} asks it to perform {@link #GLOBAL_ACTION_LOCK_SCREEN} (Android 9+).
 * Its configuration ({@code res/xml/lock_service.xml}) subscribes to no accessibility events and
 * cannot read window content, so it never sees what is on screen. The user turns it on themselves in
 * Android's Accessibility settings, only after accepting the prominent disclosure in
 * {@link LockScreen#showDisclosure}. No Device Admin is used.
 */
public final class LockService extends AccessibilityService {

    /** The running, connected service, or null. Cleared when Android unbinds it. */
    private static volatile LockService connected;

    /** True while the service is enabled and connected. */
    static boolean running() {
        return connected != null;
    }

    /**
     * Locks the screen through the connected service.
     *
     * @return false when the service is off or the device is older than Android 9
     */
    static boolean lockNow() {
        LockService service = connected;
        if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        return service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = null;
        super.onDestroy();
    }

    /** Never called: the service subscribes to no events. */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // intentionally empty: this service only performs the lock action on request
    }

    @Override
    public void onInterrupt() {
        // nothing to interrupt
    }
}
