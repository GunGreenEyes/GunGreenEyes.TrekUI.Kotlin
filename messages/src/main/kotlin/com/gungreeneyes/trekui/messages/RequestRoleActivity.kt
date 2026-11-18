package com.gungreeneyes.trekui.messages

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import com.gungreeneyes.trekui.core.SuiteContract

/**
 * Invisible trampoline for [SuiteContract.ACTION_REQUEST_SMS_ROLE]: the launcher's CONFIG > SUITE
 * > MESSAGES button cannot ask for the SMS role on another app's behalf (RoleManager only lets an app
 * request a role for itself), so it sends this explicit intent and Messages shows the system "set as
 * default SMS app" dialog itself, then finishes. Guarded by the signature-level suite permission, so only
 * TrekUI apps signed with our key can start it.
 */
class RequestRoleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return // already waiting for the system dialog
        if (SmsRole.isDefault(this) || !SmsRole.available(this)) {
            finish()
            return
        }
        try {
            startActivityForResult(SmsRole.requestIntent(this), REQUEST_ROLE)
        } catch (e: ActivityNotFoundException) {
            finish()
        } catch (e: SecurityException) {
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        finish()
    }

    private companion object {
        const val REQUEST_ROLE = 1
    }
}
