package com.gungreeneyes.trekui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.provider.Telephony;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import com.gungreeneyes.trekui.core.SuiteContract;

import java.util.List;

/**
 * The system side of CONFIG › SUITE (LCA-T35): which keyboard is active, the keyboard picker, the
 * default-SMS role for TrekUI Messages and Play links to the companion apps.
 *
 * <p>The rows themselves are built by {@code SettingsActivity.suiteSection()}; this class holds the
 * intents and lookups, and the pure rules ({@link #imePackage}, {@link #isSuiteKeyboard},
 * {@link #restoreTarget}) are unit-tested in {@code SuiteSettingsTest}.
 *
 * <p>Default SMS: Android 10+ only lets an app request a role for itself (RoleManager), so the launcher
 * asks TrekUI Messages to do it with {@link SuiteContract#ACTION_REQUEST_SMS_ROLE}; older versions take
 * {@code Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT} for any package. When neither is available the
 * system's default-apps screen opens instead. RESTORE goes back to the SMS app that was the default
 * before (remembered in {@link #KEY_PREV_SMS}).
 */
final class SuiteSettings {
    private SuiteSettings() {}

    /** Pref key: the default SMS app before the user made TrekUI Messages the default. */
    static final String KEY_PREV_SMS = "suitePrevSms";

    // ---- pure rules -----------------------------------------------------------------------------

    /** Package of an input-method id ({@code "pkg/.Service"} or {@code "pkg/pkg.Service"}), or null. */
    static String imePackage(String imeId) {
        if (imeId == null) return null;
        int slash = imeId.indexOf('/');
        String pkg = slash < 0 ? imeId : imeId.substring(0, slash);
        return pkg.isEmpty() ? null : pkg;
    }

    /** True when the input method {@code imeId} belongs to TrekUI Keyboard ({@code keyboardPkg}). */
    static boolean isSuiteKeyboard(String imeId, String keyboardPkg) {
        String pkg = imePackage(imeId);
        return pkg != null && pkg.equals(keyboardPkg);
    }

    /**
     * The SMS app RESTORE should return to: the remembered one, unless it is missing or TrekUI Messages
     * itself (then null: the user picks in the system's default-apps screen).
     */
    static String restoreTarget(String remembered, String messagesPkg) {
        if (remembered == null || remembered.isEmpty() || remembered.equals(messagesPkg)) return null;
        return remembered;
    }

    // ---- keyboard ---------------------------------------------------------------------------------

    /** The id of the active input method, or null when it can't be read. */
    static String activeImeId(Context c) {
        try {
            return Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The display name of the enabled input method {@code imeId}, or null when unknown. */
    static String imeLabel(Context c, String imeId) {
        if (imeId == null) return null;
        try {
            InputMethodManager imm = c.getSystemService(InputMethodManager.class);
            if (imm == null) return null;
            List<InputMethodInfo> enabled = imm.getEnabledInputMethodList();
            for (InputMethodInfo info : enabled) {
                if (imeId.equals(info.getId())) return String.valueOf(info.loadLabel(c.getPackageManager()));
            }
        } catch (RuntimeException ignored) {
            // fall through: unknown
        }
        return null;
    }

    /** Shows the system keyboard picker; false when the device has none. */
    static boolean showImePicker(Context c) {
        InputMethodManager imm = c.getSystemService(InputMethodManager.class);
        if (imm == null) return false;
        imm.showInputMethodPicker();
        return true;
    }

    /** The system screen where keyboards are enabled. */
    static Intent imeSettingsIntent() {
        return new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
    }

    // ---- messages ---------------------------------------------------------------------------------

    /** The current default SMS app's package, or null (no telephony / none chosen). */
    static String defaultSms(Context c) {
        try {
            return Telephony.Sms.getDefaultSmsPackage(c);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The intent that asks for {@code messagesPkg} to become the default SMS app (see class notes). */
    static Intent requestSmsIntent(String messagesPkg) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new Intent(SuiteContract.ACTION_REQUEST_SMS_ROLE).setPackage(messagesPkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return changeDefaultSms(messagesPkg);
    }

    /** The intent that restores {@code previous} as default SMS app, or the default-apps screen. */
    static Intent restoreSmsIntent(String previous) {
        if (previous != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return changeDefaultSms(previous);
        return defaultAppsIntent();
    }

    /** The system's default-apps screen (the user picks the SMS app there). */
    static Intent defaultAppsIntent() {
        return new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
    }

    /** Android 9 and older: the system "change SMS app?" dialog for {@code pkg}. */
    @SuppressWarnings("deprecation")
    private static Intent changeDefaultSms(String pkg) {
        return new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, pkg);
    }

    // ---- install ----------------------------------------------------------------------------------

    /** Opens the Play Store app on suite app {@code pkg}'s listing. */
    static Intent marketIntent(String pkg) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(SuiteContract.marketUri(pkg)));
    }

    /** Opens suite app {@code pkg}'s Play listing in a browser (no Play Store app). */
    static Intent webStoreIntent(String pkg) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(SuiteContract.playUrl(pkg)));
    }
}
