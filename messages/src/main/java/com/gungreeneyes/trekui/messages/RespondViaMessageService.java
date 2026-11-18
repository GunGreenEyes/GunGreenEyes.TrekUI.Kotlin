package com.gungreeneyes.trekui.messages;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.telephony.TelephonyManager;

import com.gungreeneyes.trekui.messages.data.PhoneNumbers;

import java.util.List;

/**
 * Required default-SMS component: "respond via message" from the incoming-call screen. The system sends
 * RESPOND_VIA_MESSAGE with the caller in the data URI and the chosen text in EXTRA_TEXT; the reply is
 * sent as SMS without any UI.
 */
public final class RespondViaMessageService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !TelephonyManager.ACTION_RESPOND_VIA_MESSAGE.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        Uri data = intent.getData();
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        List<String> to = PhoneNumbers.fromSchemePart(data == null ? null : data.getSchemeSpecificPart());
        if (to.isEmpty() || text == null || text.trim().isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        final int id = startId;
        Background.run(() -> {
            for (String a : to) SmsSender.send(getApplicationContext(), a, text, -1);
            stopSelf(id);
        });
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
