package com.gungreeneyes.trekui

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * The optional accessibility service behind the LOCK SCREEN function (gap A9).
 *
 * It does exactly one thing: when the user runs LOCK SCREEN (from a tile, element or gesture they
 * assigned), [LockScreen] asks it to perform [GLOBAL_ACTION_LOCK_SCREEN] (Android 9+).
 * Its configuration (`res/xml/lock_service.xml`) subscribes to no accessibility events and
 * cannot read window content, so it never sees what is on screen. The user turns it on themselves in
 * Android's Accessibility settings, only after accepting the prominent disclosure in
 * [LockScreen.showDisclosure]. No Device Admin is used.
 */
class LockService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        connected = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        connected = null
        super.onDestroy()
    }

    /** Never called: the service subscribes to no events. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // intentionally empty: this service only performs the lock action on request
    }

    override fun onInterrupt() {
        // nothing to interrupt
    }

    companion object {
        /** The running, connected service, or null. Cleared when Android unbinds it. */
        @Volatile
        private var connected: LockService? = null

        /** True while the service is enabled and connected. */
        @JvmStatic
        fun running(): Boolean = connected != null

        /**
         * Locks the screen through the connected service.
         *
         * @return false when the service is off or the device is older than Android 9
         */
        @JvmStatic
        fun lockNow(): Boolean {
            val service = connected
            if (service == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        }
    }
}
