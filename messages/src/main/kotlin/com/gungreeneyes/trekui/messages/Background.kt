package com.gungreeneyes.trekui.messages

import android.content.BroadcastReceiver
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Background threads for provider work, so SMS/MMS writes never run on the main thread: one for services
 * and screens (including slow work such as MMS image compression), and a separate one for broadcast
 * receivers, so a receiver's `goAsync` work never waits behind the UI's and times out. Each runs in order.
 */
internal object Background {
    private val IO: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "messages-io").apply { isDaemon = true }
    }
    private val RECEIVERS: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "messages-rx").apply { isDaemon = true }
    }

    /** Runs [work] in the background. */
    fun run(work: () -> Unit) {
        IO.execute(work)
    }

    /** Runs a receiver's [work] on the receivers' thread and finishes [pending] afterwards. */
    fun run(pending: BroadcastReceiver.PendingResult, work: () -> Unit) {
        RECEIVERS.execute {
            try {
                work()
            } finally {
                pending.finish()
            }
        }
    }
}
