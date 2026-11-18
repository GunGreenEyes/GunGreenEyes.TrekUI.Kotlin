package com.gungreeneyes.trekui

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs the sound update channel check ([SoundUpdate], docs/SOUNDS.md) for the launcher, the one app with
 * the INTERNET permission: on its own background thread through [Http] (HTTPS only, timeouts, size caps),
 * at most once every 24 hours from the home screen, and on every visit to the settings. When the playable
 * sounds changed, the sound pool is reloaded on the main thread. Failures are silent.
 */
internal object SoundUpdateCheck {
    private val executor: ExecutorService by lazy { Http.newExecutor("trekui-sfx-update") }
    private val running = AtomicBoolean()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /**
     * The channel's network access: plain GETs through the launcher's HTTPS helper, following a redirect
     * only to https on mindattic.com ([SoundRedirect]).
     */
    @JvmField
    val FETCHER = SoundUpdate.Fetcher { url, maxBytes -> SoundRedirect.follow(url, maxBytes, Http::getOnce) }

    /** Unit tests (Robolectric) never reach the network from an activity's lifecycle. */
    private fun enabled(): Boolean = "robolectric" != Build.FINGERPRINT

    /**
     * Starts a check in the background unless one is running.
     *
     * @param force skip the 24-hour throttle (the user opened the settings)
     */
    @JvmStatic
    fun maybeStart(context: Context, force: Boolean) {
        if (!enabled() || !running.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            try {
                val r = SoundUpdate.checkIfDue(app, System.currentTimeMillis(), force, FETCHER)
                if (r != null && r.changed()) main.post { Sfx.reload(app) }
            } catch (ignored: RuntimeException) {
                // never let the update channel break the launcher; the bundled sounds keep playing
            } finally {
                running.set(false)
            }
        }
    }
}
