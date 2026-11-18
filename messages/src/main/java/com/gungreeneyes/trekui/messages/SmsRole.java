package com.gungreeneyes.trekui.messages;

import android.Manifest;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Telephony;

import java.util.ArrayList;
import java.util.List;

/**
 * The default-SMS-app role: whether Messages holds it and the system intent that asks the user for it
 * ({@link RoleManager#ROLE_SMS} on API 29+, {@link Telephony.Sms.Intents#ACTION_CHANGE_DEFAULT} on 26-28),
 * plus the runtime permissions still missing. Only the default SMS app may write to the SMS/MMS provider
 * and receives SMS_DELIVER / WAP_PUSH_DELIVER; reading works with READ_SMS alone.
 */
public final class SmsRole {
    private SmsRole() {}

    /** True if this app is the default SMS app. */
    public static boolean isDefault(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = c.getSystemService(RoleManager.class);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) return rm.isRoleHeld(RoleManager.ROLE_SMS);
        }
        return c.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(c));
    }

    /** True if this device offers the SMS role at all (false on Wi-Fi-only tablets). */
    public static boolean available(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = c.getSystemService(RoleManager.class);
            return rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS);
        }
        return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY);
    }

    /** The system screen asking the user to make Messages the default SMS app. */
    public static Intent requestIntent(Context c) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager rm = c.getSystemService(RoleManager.class);
            if (rm != null) return rm.createRequestRoleIntent(RoleManager.ROLE_SMS);
        }
        return new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, c.getPackageName());
    }

    /** Runtime permissions the app uses that are not granted yet (empty when all are). */
    public static String[] missingPermissions(Context c) {
        List<String> want = new ArrayList<>();
        want.add(Manifest.permission.READ_SMS);
        want.add(Manifest.permission.SEND_SMS);
        want.add(Manifest.permission.RECEIVE_SMS);
        want.add(Manifest.permission.RECEIVE_MMS);
        want.add(Manifest.permission.READ_CONTACTS);
        want.add(Manifest.permission.READ_PHONE_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) want.add(Manifest.permission.POST_NOTIFICATIONS);
        List<String> missing = new ArrayList<>();
        for (String p : want) {
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        }
        return missing.toArray(new String[0]);
    }

    /** True if the SMS store can be read. */
    public static boolean canRead(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }
}
