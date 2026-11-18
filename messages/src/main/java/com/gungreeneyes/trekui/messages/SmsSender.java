package com.gungreeneyes.trekui.messages;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;

import java.util.ArrayList;

/**
 * Sends text messages: stores the message in the outbox (so it shows at once and survives a crash), splits
 * it with {@link SmsManager#divideMessage}, and sends every part with a sent and (optionally) a delivery
 * {@link PendingIntent} to {@link SmsStatusReceiver}, which moves the row to SENT / FAILED and records
 * the delivery report. Blocking (provider write); call off the main thread.
 */
public final class SmsSender {
    static final String ACTION_SENT = "com.gungreeneyes.trekui.messages.SMS_SENT";
    static final String ACTION_DELIVERED = "com.gungreeneyes.trekui.messages.SMS_DELIVERED";
    static final String EXTRA_PART = "part";
    static final String EXTRA_PARTS = "parts";
    static final String EXTRA_ADDRESS = "address";

    private SmsSender() {}

    /**
     * Sends {@code body} to {@code address} from SIM {@code subId} (-1: default SIM).
     *
     * @return the outbox row (null when not the default SMS app: the message is still sent, untracked)
     */
    public static Uri send(Context c, String address, String body, int subId) {
        Uri row = MessageStore.insertOutgoing(c, address, body, subId);
        SmsManager sms = Sims.manager(c, subId);
        ArrayList<String> parts = sms.divideMessage(body);
        if (parts == null || parts.isEmpty()) {
            parts = new ArrayList<>();
            parts.add(body);
        }
        boolean reports = MessagesSettings.deliveryReports(c);
        ArrayList<PendingIntent> sent = new ArrayList<>(parts.size());
        ArrayList<PendingIntent> delivered = reports ? new ArrayList<>(parts.size()) : null;
        long base = row != null ? android.content.ContentUris.parseId(row) : System.currentTimeMillis();
        for (int i = 0; i < parts.size(); i++) {
            sent.add(status(c, ACTION_SENT, row, address, i, parts.size(), base));
            if (delivered != null) delivered.add(status(c, ACTION_DELIVERED, row, address, i, parts.size(), base));
        }
        try {
            if (parts.size() == 1) {
                sms.sendTextMessage(address, null, parts.get(0), sent.get(0),
                        delivered == null ? null : delivered.get(0));
            } else {
                sms.sendMultipartTextMessage(address, null, parts, sent, delivered);
            }
        } catch (RuntimeException e) {
            MessageStore.setSmsType(c, row, android.provider.Telephony.Sms.MESSAGE_TYPE_FAILED, -1);
        }
        return row;
    }

    /**
     * A status PendingIntent for part {@code part}. Mutable on API 31+: SmsManager fills in the error
     * code and, for delivery reports, the status PDU. The intent is explicit to our own receiver.
     */
    private static PendingIntent status(Context c, String action, Uri row, String address, int part, int parts,
                                        long base) {
        Intent i = new Intent(action, row, c, SmsStatusReceiver.class)
                .putExtra(EXTRA_PART, part)
                .putExtra(EXTRA_PARTS, parts)
                .putExtra(EXTRA_ADDRESS, address);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        int request = (int) ((base * 31 + part) * 2 + (ACTION_SENT.equals(action) ? 0 : 1));
        return PendingIntent.getBroadcast(c, request, i, flags);
    }
}
