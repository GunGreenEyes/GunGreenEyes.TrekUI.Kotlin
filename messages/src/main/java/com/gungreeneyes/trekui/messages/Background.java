package com.gungreeneyes.trekui.messages;

import android.content.BroadcastReceiver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One background thread for provider work started from receivers, services and screens, so SMS/MMS writes
 * happen in order and never on the main thread.
 */
public final class Background {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "messages-io");
        t.setDaemon(true);
        return t;
    });

    private Background() {}

    /** Runs {@code work} in the background. */
    public static void run(Runnable work) {
        IO.execute(work);
    }

    /** Runs a receiver's {@code work} in the background and finishes {@code pending} afterwards. */
    public static void run(BroadcastReceiver.PendingResult pending, Runnable work) {
        IO.execute(() -> {
            try {
                work.run();
            } finally {
                pending.finish();
            }
        });
    }
}
