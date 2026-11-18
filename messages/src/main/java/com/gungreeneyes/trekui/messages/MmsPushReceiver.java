package com.gungreeneyes.trekui.messages;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;

import com.gungreeneyes.trekui.messages.data.PhoneNumbers;
import com.gungreeneyes.trekui.messages.mms.MmsPdu;
import com.gungreeneyes.trekui.messages.mms.PduCodec;

/**
 * Required default-SMS component: receives WAP_PUSH_DELIVER for MMS. The push carries an
 * m-notification-ind (parsed by {@link PduCodec}); unless the sender is blocked, the message itself is
 * downloaded at once through the MMS service ({@link MmsSender#download}), which also sends the
 * acknowledgement to the carrier.
 */
public final class MmsPushReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION.equals(intent.getAction())) return;
        byte[] data = intent.getByteArrayExtra("data");
        MmsPdu pdu = PduCodec.decode(data);
        if (pdu == null || pdu.messageType != MmsPdu.TYPE_NOTIFICATION_IND || pdu.contentLocation == null) return;
        final String from = PhoneNumbers.normalize(pdu.from);
        final String location = pdu.contentLocation;
        final int subId = Sims.subIdFrom(intent);
        final Context app = context.getApplicationContext();
        Background.run(goAsync(), () -> {
            if (Blocking.isBlocked(app, from)) return;
            try {
                MmsSender.download(app, location, subId, from);
            } catch (RuntimeException e) {
                Notifier.showFailure(app, -1, from, R.string.error_mms_download);
            }
        });
    }
}
