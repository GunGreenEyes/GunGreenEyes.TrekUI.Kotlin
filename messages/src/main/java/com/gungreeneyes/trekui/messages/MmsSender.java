package com.gungreeneyes.trekui.messages;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Telephony;

import com.gungreeneyes.trekui.messages.mms.MmsBuilder;
import com.gungreeneyes.trekui.messages.mms.MmsPdu;
import com.gungreeneyes.trekui.messages.mms.PduCodec;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Sends and downloads MMS through the platform MMS service ({@code SmsManager.sendMultimediaMessage} /
 * {@code downloadMultimediaMessage}), with PDUs built and parsed by this app's own codec
 * ({@link MmsBuilder}, {@link PduCodec}) and exchanged as files through {@link MmsFileProvider}.
 * Results arrive at {@link MmsResultReceiver}. Blocking; call off the main thread.
 */
public final class MmsSender {
    static final String ACTION_SENT = "com.gungreeneyes.trekui.messages.MMS_SENT";
    static final String ACTION_DOWNLOADED = "com.gungreeneyes.trekui.messages.MMS_DOWNLOADED";
    static final String EXTRA_FILE = "file";
    static final String EXTRA_SUB = "sub";
    static final String EXTRA_FROM = "from";

    private MmsSender() {}

    /**
     * Sends {@code text} and the JPEG {@code images} (cache files, deleted afterwards) to every recipient
     * in one MMS (a group message when there are several).
     *
     * @return true if the message was handed to the MMS service
     */
    public static boolean send(Context c, List<String> recipients, String text, List<File> images, int subId) {
        String body = text == null ? "" : text;
        int textBytes = body.getBytes(StandardCharsets.UTF_8).length;
        int count = images == null ? 0 : images.size();
        List<byte[]> jpegs = new ArrayList<>();
        if (count > 0) {
            int budget = Math.max(24 * 1024, (Images.MMS_BUDGET_BYTES - Images.MMS_OVERHEAD_BYTES - textBytes) / count);
            for (File f : images) {
                byte[] b = Images.jpegWithin(f, budget);
                if (b != null) jpegs.add(b);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        long now = System.currentTimeMillis();
        MmsPdu pdu = MmsBuilder.sendRequest(recipients, body, jpegs, "T" + Long.toHexString(now), now / 1000L);
        byte[] bytes = PduCodec.encode(pdu);
        String name = "send-" + now + ".pdu";
        if (!write(MmsFileProvider.file(c, name), bytes)) return false;

        long thread = MessageStore.threadFor(c, recipients);
        Uri row = MmsStore.insertOutgoing(c, pdu, thread, subId);
        Intent result = new Intent(ACTION_SENT, row, c, MmsResultReceiver.class)
                .putExtra(EXTRA_FILE, name)
                .putExtra(EXTRA_SUB, subId);
        PendingIntent pi = PendingIntent.getBroadcast(c, (int) now, result, mutableFlags());
        try {
            Sims.manager(c, subId).sendMultimediaMessage(c, MmsFileProvider.uriFor(c, name), null, null, pi);
            return true;
        } catch (RuntimeException e) {
            MmsStore.setBox(c, row, Telephony.Mms.MESSAGE_BOX_FAILED, null);
            //noinspection ResultOfMethodCallIgnored
            MmsFileProvider.file(c, name).delete();
            return false;
        }
    }

    /** Asks the MMS service to download the message announced at {@code contentLocation}. */
    public static void download(Context c, String contentLocation, int subId, String from) {
        long now = System.currentTimeMillis();
        String name = "recv-" + now + ".pdu";
        MmsFileProvider.file(c, name); // ensures the directory exists
        Intent result = new Intent(ACTION_DOWNLOADED, null, c, MmsResultReceiver.class)
                .putExtra(EXTRA_FILE, name)
                .putExtra(EXTRA_SUB, subId)
                .putExtra(EXTRA_FROM, from);
        PendingIntent pi = PendingIntent.getBroadcast(c, (int) now, result, mutableFlags());
        Sims.manager(c, subId).downloadMultimediaMessage(c, contentLocation, MmsFileProvider.uriFor(c, name), null, pi);
    }

    /** Result PendingIntents are filled in by the MMS service (status bytes), so mutable on API 31+. */
    private static int mutableFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
        return flags;
    }

    private static boolean write(File f, byte[] bytes) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(bytes);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
