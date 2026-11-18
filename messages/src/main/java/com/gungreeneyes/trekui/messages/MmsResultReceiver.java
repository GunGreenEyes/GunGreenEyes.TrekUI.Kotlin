package com.gungreeneyes.trekui.messages;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony;
import android.telephony.SmsManager;

import com.gungreeneyes.trekui.messages.mms.MmsPdu;
import com.gungreeneyes.trekui.messages.mms.PduCodec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Results from the MMS service. Sent: the outbox row moves to SENT (with the carrier's message id from
 * the m-send-conf) or FAILED. Downloaded: the m-retrieve-conf file is parsed and stored in the inbox,
 * then the conversation notifies. The PDU file is deleted either way.
 */
public final class MmsResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        final int code = getResultCode();
        final Uri row = intent.getData();
        final String file = intent.getStringExtra(MmsSender.EXTRA_FILE);
        final int subId = intent.getIntExtra(MmsSender.EXTRA_SUB, -1);
        final String from = intent.getStringExtra(MmsSender.EXTRA_FROM);
        final byte[] response = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA);
        final Context app = context.getApplicationContext();
        Background.run(goAsync(), () -> {
            File f = file == null ? null : MmsFileProvider.file(app, file);
            try {
                if (MmsSender.ACTION_SENT.equals(action)) onSent(app, row, code, response);
                else if (MmsSender.ACTION_DOWNLOADED.equals(action)) onDownloaded(app, f, code, subId, from);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                if (f != null) f.delete();
            }
        });
    }

    private static void onSent(Context app, Uri row, int code, byte[] response) {
        MmsPdu conf = PduCodec.decode(response);
        boolean ok = code == Activity.RESULT_OK
                && (conf == null || conf.responseStatus == 0 || conf.responseStatus == MmsPdu.RESPONSE_OK);
        if (ok) {
            MmsStore.setBox(app, row, Telephony.Mms.MESSAGE_BOX_SENT, conf == null ? null : conf.messageId);
        } else {
            MmsStore.setBox(app, row, Telephony.Mms.MESSAGE_BOX_FAILED, null);
            Notifier.showFailure(app, MessageStore.threadOf(app, row, true), "", R.string.error_send);
        }
    }

    private static void onDownloaded(Context app, File f, int code, int subId, String from) {
        MmsPdu pdu = null;
        if (code == Activity.RESULT_OK && f != null && f.exists()) {
            try {
                pdu = PduCodec.decode(Files.readAllBytes(f.toPath()));
            } catch (IOException ignored) {
                // treated as a failed download below
            }
        }
        if (pdu == null || pdu.messageType != MmsPdu.TYPE_RETRIEVE_CONF) {
            Notifier.showFailure(app, -1, from == null ? "" : from, R.string.error_mms_download);
            return;
        }
        if (pdu.from == null) pdu.from = from;
        Uri stored = MmsStore.insertIncoming(app, pdu, subId);
        long thread = MessageStore.threadOf(app, stored, true);
        if (thread > 0 && !ThreadActivity.isShowing(thread)) Notifier.showThread(app, thread);
    }
}
