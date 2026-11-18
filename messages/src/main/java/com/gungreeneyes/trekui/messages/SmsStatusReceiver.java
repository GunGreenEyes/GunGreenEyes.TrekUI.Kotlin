package com.gungreeneyes.trekui.messages;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/**
 * Sent and delivery results of outgoing SMS parts ({@link SmsSender}'s PendingIntents): the row becomes
 * SENT when the last part is accepted, FAILED as soon as any part fails (with a notification), and gets
 * the delivery status (complete / pending / failed) from the status report PDU.
 */
public final class SmsStatusReceiver extends BroadcastReceiver {
    /** TP-Status ranges (3GPP TS 23.040): 0x00-0x1F done, 0x20-0x3F still trying, 0x40+ failed. */
    static final int TP_STATUS_PENDING_MIN = 0x20, TP_STATUS_FAILED_MIN = 0x40;

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        final Uri row = intent.getData();
        final int code = getResultCode();
        final int part = intent.getIntExtra(SmsSender.EXTRA_PART, 0);
        final int parts = intent.getIntExtra(SmsSender.EXTRA_PARTS, 1);
        final String address = intent.getStringExtra(SmsSender.EXTRA_ADDRESS);
        final int error = intent.getIntExtra("errorCode", code);
        final byte[] pdu = intent.getByteArrayExtra("pdu");
        final String format = intent.getStringExtra("format");
        final Context app = context.getApplicationContext();
        Background.run(goAsync(), () -> {
            if (SmsSender.ACTION_SENT.equals(action)) {
                if (code == Activity.RESULT_OK) {
                    if (part == parts - 1) MessageStore.setSmsType(app, row, Telephony.Sms.MESSAGE_TYPE_SENT, 0);
                } else {
                    MessageStore.setSmsType(app, row, Telephony.Sms.MESSAGE_TYPE_FAILED, error);
                    Notifier.showFailure(app, MessageStore.threadOf(app, row, false), address, R.string.error_send);
                }
            } else if (SmsSender.ACTION_DELIVERED.equals(action) && pdu != null) {
                SmsMessage report = SmsMessage.createFromPdu(pdu, format);
                if (report == null) return;
                int tp = report.getStatus();
                int status = tp >= TP_STATUS_FAILED_MIN ? Telephony.Sms.STATUS_FAILED
                        : tp >= TP_STATUS_PENDING_MIN ? Telephony.Sms.STATUS_PENDING : Telephony.Sms.STATUS_COMPLETE;
                // a multipart message counts as delivered when its last part is
                if (status != Telephony.Sms.STATUS_COMPLETE || part == parts - 1) {
                    MessageStore.setSmsStatus(app, row, status);
                }
            }
        });
    }
}
