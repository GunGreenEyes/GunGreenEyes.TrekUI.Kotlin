package com.gungreeneyes.trekui

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Screenshots and screen recordings for the instrumented scenarios.
 *
 * Everything is written by the `shell` user (via [UiAutomation.executeShellCommand])
 * into [DEVICE_DIR] on shared storage, which works on every API level without storage
 * permissions; `scripts/capture/run-matrix.ps1` pulls that folder into
 * `assets/<device>-api<N>/` after the run.
 *
 * File names follow `<scenario>[-<step>]__<device>__api<N>__<orientation>.png|.mp4`. The
 * device name comes from the instrumentation argument `consoleDevice` (the AVD name chosen by the
 * capture runner), falling back to [Build.MODEL].
 */
class MediaCapture(private val instrumentation: Instrumentation, private val scenario: String) {
    private var recording: ParcelFileDescriptor? = null
    private var videoFile: String? = null

    init {
        shell("mkdir -p $DEVICE_DIR")
    }

    private fun baseName(step: String?): String {
        val ctx = instrumentation.targetContext
        val s = if (step.isNullOrEmpty()) scenario else scenario + "-" + sanitize(step)
        return String.format(Locale.US, "%s__%s__api%d__%s", s, deviceName(), Build.VERSION.SDK_INT, orientation(ctx))
    }

    /**
     * Saves a PNG of the whole screen (status and navigation bars included, so overlap is visible).
     *
     * @param step short label for this moment of the scenario, e.g. "home" or "drawer-open"
     * @return the device path written
     */
    fun screenshot(step: String?): String {
        // Let the last frame reach the screen before grabbing it.
        instrumentation.runOnMainSync { }
        SystemClock.sleep(400)
        val path = DEVICE_DIR + "/" + baseName(step) + ".png"
        shell("screencap -p $path")
        Log.i(TAG, "screenshot $path")
        return path
    }

    /** Starts `screenrecord` in the background; [stopVideo] finalises the mp4. */
    fun startVideo() {
        if (recording != null) return
        // Before Android 8.0 a shell command that cannot start (the API 25 image denies screenrecord to the
        // UiAutomation shell) crashes the whole instrumentation instead of throwing here; screenshots only.
        val videoOff = "false" == InstrumentationRegistry.getArguments().getString("trekuiVideo") ||
            android.os.Build.VERSION.SDK_INT < 26
        if (videoOff) return
        val file = DEVICE_DIR + "/" + baseName(null) + ".mp4"
        videoFile = file
        val m = realMetrics(instrumentation.targetContext)
        var w = m.widthPixels
        var h = m.heightPixels
        // Software encoders on emulators reject very large frames; keep the long side <= 1280.
        val scale = min(1f, 1280f / max(w, h))
        w = (w * scale).roundToInt() and 15.inv()
        h = (h * scale).roundToInt() and 15.inv()
        val cmd = String.format(Locale.US, "screenrecord --bit-rate 2000000 --time-limit 170 --size %dx%d %s", w, h, file)
        try {
            recording = instrumentation.uiAutomation.executeShellCommand(cmd)
            Log.i(TAG, "recording $file")
            SystemClock.sleep(600) // encoder start-up, so the first frames are not lost
        } catch (e: RuntimeException) {
            Log.w(TAG, "screenrecord unavailable: $e")
            recording = null
        }
    }

    /** Stops the recording with SIGINT (so the mp4 header is written) and waits for it to finish. */
    fun stopVideo() {
        val pfd = recording ?: return
        SystemClock.sleep(500)
        shell("pkill -2 screenrecord")
        // Reading the pipe to EOF waits until screenrecord has exited and flushed the file; a helper
        // thread keeps a stuck encoder from hanging the whole run.
        val waiter = Thread({ drain(pfd, 8000) }, "screenrecord-wait")
        waiter.start()
        try {
            waiter.join(8000)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (waiter.isAlive) {
            shell("pkill -9 screenrecord")
            try {
                pfd.close()
            } catch (ignored: IOException) {
            }
        }
        recording = null
        Log.i(TAG, "recorded $videoFile")
    }

    /** Runs a shell command as the shell user and returns its stdout (blocking until it exits). */
    fun shell(command: String): String {
        val ua = instrumentation.uiAutomation
        val pfd = ua.executeShellCommand(command)
        return drain(pfd, 30000)
    }

    companion object {
        const val TAG = "TREKUI-MEDIA"

        /** Folder on the device the capture runner pulls. */
        const val DEVICE_DIR = "/sdcard/trekui-test"

        /** Device label used in file names: the `consoleDevice` argument or the model name. */
        @JvmStatic
        fun deviceName(): String {
            val arg = InstrumentationRegistry.getArguments().getString("trekuiDevice")
            return sanitize(if (!arg.isNullOrEmpty()) arg else Build.MODEL)
        }

        /** Replaces everything except letters, digits, '_' and '-' with '-' (shared with the runner). */
        @JvmStatic
        fun sanitize(s: String): String {
            var out = s.replace(Regex("[^A-Za-z0-9_-]+"), "-").replace(Regex("-+"), "-")
            while (out.endsWith("-")) out = out.substring(0, out.length - 1)
            return out.ifEmpty { "x" }
        }

        /** "portrait" or "landscape", from the current real display size. */
        @JvmStatic
        fun orientation(context: Context): String {
            val m = realMetrics(context)
            return if (m.widthPixels > m.heightPixels) "landscape" else "portrait"
        }

        @JvmStatic
        fun realMetrics(context: Context): DisplayMetrics {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val m = DisplayMetrics()
            // getRealMetrics is the only call that works from API 26 to 37
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            return m
        }

        private fun drain(pfd: ParcelFileDescriptor, timeoutMs: Long): String {
            val sb = StringBuilder()
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            try {
                FileInputStream(pfd.fileDescriptor).use { input ->
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        sb.append(String(buf, 0, n))
                        if (SystemClock.uptimeMillis() > deadline) break
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "shell read: $e")
            } finally {
                try {
                    pfd.close()
                } catch (ignored: IOException) {
                }
            }
            return sb.toString()
        }
    }
}
