package com.gungreeneyes.trekui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.view.inputmethod.InputMethodManager
import com.gungreeneyes.trekui.core.SuiteContract

/**
 * The system side of CONFIG › SUITE (LCA-T35): which keyboard is active, the keyboard picker, the
 * default-SMS role for TrekUI Messages and Play links to the companion apps.
 *
 * The rows themselves are built by `SettingsActivity.suiteSection()`; this object holds the
 * intents and lookups, and the pure rules ([imePackage], [isSuiteKeyboard],
 * [restoreTarget]) are unit-tested in `SuiteSettingsTest`.
 *
 * Default SMS: Android 10+ only lets an app request a role for itself (RoleManager), so the launcher
 * asks TrekUI Messages to do it with [SuiteContract.ACTION_REQUEST_SMS_ROLE]; older versions take
 * `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT` for any package. When neither is available the
 * system's default-apps screen opens instead. RESTORE goes back to the SMS app that was the default
 * before (remembered in [KEY_PREV_SMS]).
 */
internal object SuiteSettings {

    /** Pref key: the default SMS app before the user made TrekUI Messages the default. */
    const val KEY_PREV_SMS = "suitePrevSms"

    // ---- pure rules -----------------------------------------------------------------------------

    /** Package of an input-method id (`"pkg/.Service"` or `"pkg/pkg.Service"`), or null. */
    @JvmStatic
    fun imePackage(imeId: String?): String? {
        if (imeId == null) return null
        val pkg = imeId.substringBefore('/')
        return pkg.ifEmpty { null }
    }

    /** True when the input method `imeId` belongs to TrekUI Keyboard (`keyboardPkg`). */
    @JvmStatic
    fun isSuiteKeyboard(imeId: String?, keyboardPkg: String?): Boolean {
        val pkg = imePackage(imeId)
        return pkg != null && pkg == keyboardPkg
    }

    /**
     * The SMS app RESTORE should return to: the remembered one, unless it is missing or TrekUI Messages
     * itself (then null: the user picks in the system's default-apps screen).
     */
    @JvmStatic
    fun restoreTarget(remembered: String?, messagesPkg: String?): String? =
        if (remembered.isNullOrEmpty() || remembered == messagesPkg) null else remembered

    // ---- keyboard ---------------------------------------------------------------------------------

    /** The id of the active input method, or null when it can't be read. */
    @JvmStatic
    fun activeImeId(c: Context): String? = try {
        Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    } catch (e: RuntimeException) {
        null
    }

    /** The display name of the enabled input method `imeId`, or null when unknown. */
    @JvmStatic
    fun imeLabel(c: Context, imeId: String?): String? {
        if (imeId == null) return null
        try {
            val imm = c.getSystemService(InputMethodManager::class.java) ?: return null
            for (info in imm.enabledInputMethodList) {
                if (imeId == info.id) return info.loadLabel(c.packageManager).toString()
            }
        } catch (ignored: RuntimeException) {
            // fall through: unknown
        }
        return null
    }

    /** Shows the system keyboard picker; false when the device has none. */
    @JvmStatic
    fun showImePicker(c: Context): Boolean {
        val imm = c.getSystemService(InputMethodManager::class.java) ?: return false
        imm.showInputMethodPicker()
        return true
    }

    /** The system screen where keyboards are enabled. */
    @JvmStatic
    fun imeSettingsIntent(): Intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)

    // ---- messages ---------------------------------------------------------------------------------

    /** The current default SMS app's package, or null (no telephony / none chosen). */
    @JvmStatic
    fun defaultSms(c: Context): String? = try {
        Telephony.Sms.getDefaultSmsPackage(c)
    } catch (e: RuntimeException) {
        null
    }

    /** The intent that asks for `messagesPkg` to become the default SMS app (see the object notes). */
    @JvmStatic
    fun requestSmsIntent(messagesPkg: String?): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return Intent(SuiteContract.ACTION_REQUEST_SMS_ROLE).setPackage(messagesPkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return changeDefaultSms(messagesPkg)
    }

    /** The intent that restores `previous` as default SMS app, or the default-apps screen. */
    @JvmStatic
    fun restoreSmsIntent(previous: String?): Intent {
        if (previous != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return changeDefaultSms(previous)
        return defaultAppsIntent()
    }

    /**
     * The system's default-apps screen (the user picks the SMS app there). It has its own action from Android 7.0
     * (API 24); on 6.0 it is the Apps settings screen, which lists Default apps.
     */
    @JvmStatic
    fun defaultAppsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        } else {
            Intent(Settings.ACTION_APPLICATION_SETTINGS)
        }

    /** Android 9 and older: the system "change SMS app?" dialog for `pkg`. */
    @Suppress("DEPRECATION")
    private fun changeDefaultSms(pkg: String?): Intent =
        Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, pkg)

    // ---- install ----------------------------------------------------------------------------------

    /** Opens the Play Store app on suite app `pkg`'s listing. */
    @JvmStatic
    fun marketIntent(pkg: String?): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(SuiteContract.marketUri(pkg)))

    /** Opens suite app `pkg`'s Play listing in a browser (no Play Store app). */
    @JvmStatic
    fun webStoreIntent(pkg: String?): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(SuiteContract.playUrl(pkg)))
}
