package com.gungreeneyes.trekui

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast

/**
 * The LOCK SCREEN function (gap A9): locks the phone through the optional [LockService].
 *
 * Google Play requires a prominent, in-app disclosure with an explicit "agree" before an app leads
 * the user to enable an accessibility service it uses for something other than helping people with
 * disabilities. [showDisclosure] is that screen: it says what the service does and does not
 * do, and only "agree" opens Android's Accessibility settings. The text lives in
 * `strings_groups_gestures.xml` and is mirrored in `docs/PLAY_STORE.md`.
 *
 * No Device Admin / DevicePolicyManager fallback exists on purpose: below Android 9, or with the
 * service off, LOCK SCREEN explains what is needed and does nothing else.
 */
internal object LockScreen {

    /** True on Android 9+ where `GLOBAL_ACTION_LOCK_SCREEN` exists. */
    @JvmStatic
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * Runs LOCK SCREEN: locks at once when the service is on, otherwise shows the disclosure that
     * leads to enabling it (or explains that Android 9 is needed).
     */
    @JvmStatic
    fun lock(c: Context) {
        if (!supported()) {
            Sfx.play(c, Sfx.DENY)
            toast(c, c.getString(R.string.toast_lock_needs_p))
            return
        }
        if (LockService.running()) {
            if (!LockService.lockNow()) {
                Sfx.play(c, Sfx.ERROR)
                toast(c, c.getString(R.string.toast_lock_failed))
            }
            return
        }
        val d = showDisclosure(c)
        if (c is MainActivity) c.showing(d)
    }

    /**
     * The prominent disclosure, shown every time LOCK SCREEN is used while the service is off. AGREE
     * opens Accessibility settings (the user still has to switch the service on there); NO THANKS closes
     * it without changing anything. Nothing is stored either way.
     */
    @JvmStatic
    fun showDisclosure(c: Context): Dialog =
        ConsoleDialog.message(
            c,
            c.getString(R.string.lock_disclosure_title),
            c.getString(R.string.lock_disclosure_body),
            arrayOf(c.getString(R.string.lock_disclosure_accept), c.getString(R.string.lock_disclosure_decline)),
        ) { which ->
            if (which != 0) {
                Sfx.play(c, Sfx.CANCEL)
            } else {
                Sfx.play(c, Sfx.CONFIRM)
                openAccessibilitySettings(c)
            }
        }

    /** Opens Android's Accessibility settings (where the user turns the service on or off). */
    @JvmStatic
    fun openAccessibilitySettings(c: Context) {
        try {
            val i = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            if (c !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
            toast(c, c.getString(R.string.toast_lock_enable))
        } catch (e: ActivityNotFoundException) {
            notAvailable(c)
        } catch (e: SecurityException) {
            notAvailable(c)
        }
    }

    private fun notAvailable(c: Context) {
        Sfx.play(c, Sfx.ERROR)
        toast(c, c.getString(R.string.toast_not_available))
    }

    private fun toast(c: Context, message: String) {
        Toast.makeText(c, message, Toast.LENGTH_SHORT).show()
    }
}
