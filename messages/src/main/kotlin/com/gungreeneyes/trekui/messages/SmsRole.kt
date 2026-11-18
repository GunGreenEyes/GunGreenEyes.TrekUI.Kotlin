package com.gungreeneyes.trekui.messages

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony

/**
 * The default-SMS-app role: whether Messages holds it and the system intent that asks the user for it
 * ([RoleManager.ROLE_SMS] on API 29+, [Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT] on 26-28),
 * plus the runtime permissions still missing. Only the default SMS app may write to the SMS/MMS provider
 * and receives SMS_DELIVER / WAP_PUSH_DELIVER; reading works with READ_SMS alone.
 */
internal object SmsRole {
    /** True if this app is the default SMS app. */
    fun isDefault(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = c.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) return rm.isRoleHeld(RoleManager.ROLE_SMS)
        }
        return c.packageName == Telephony.Sms.getDefaultSmsPackage(c)
    }

    /** True if this device offers the SMS role at all (false on Wi-Fi-only tablets). */
    fun available(c: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = c.getSystemService(RoleManager::class.java)
            return rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)
        }
        return c.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }

    /** The system screen asking the user to make Messages the default SMS app. */
    fun requestIntent(c: Context): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = c.getSystemService(RoleManager::class.java)
            if (rm != null) return rm.createRequestRoleIntent(RoleManager.ROLE_SMS)
        }
        return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, c.packageName)
    }

    /** Runtime permissions the app uses that are not granted yet (empty when all are). */
    fun missingPermissions(c: Context): Array<String> {
        val want = buildList {
            add(Manifest.permission.READ_SMS)
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.RECEIVE_SMS)
            add(Manifest.permission.RECEIVE_MMS)
            add(Manifest.permission.READ_CONTACTS)
            add(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return want.filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
    }

    /** True if the SMS store can be read. */
    fun canRead(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
}
