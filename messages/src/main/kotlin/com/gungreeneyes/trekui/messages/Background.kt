package com.gungreeneyes.trekui.messages

import android.content.BroadcastReceiver
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * One background thread for provider work started from receivers, services and screens, so SMS/MMS writes
 * happen in order and never on the main thread.
 */
internal object Background {
    private val IO: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "messages-io").apply { isDaemon = true }
    }

    /** Runs [work] in the background. */
    fun run(work: () -> Unit) {
        IO.execute(work)
    }

    /** Runs a receiver's [work] in the background and finishes [pending] afterwards. */
    fun run(pending: BroadcastReceiver.PendingResult, work: () -> Unit) {
        IO.execute {
            try {
                work()
            } finally {
                pending.finish()
            }
        }
    }
}
