package com.gungreeneyes.trekui;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Screenshots and screen recordings for the instrumented scenarios.
 *
 * <p>Everything is written by the {@code shell} user (via {@link UiAutomation#executeShellCommand})
 * into {@link #DEVICE_DIR} on shared storage, which works on every API level without storage
 * permissions; {@code scripts/capture/run-matrix.ps1} pulls that folder into
 * {@code assets/<device>-api<N>/} after the run.</p>
 *
 * <p>File names follow {@code <scenario>[-<step>]__<device>__api<N>__<orientation>.png|.mp4}. The
 * device name comes from the instrumentation argument {@code consoleDevice} (the AVD name chosen by the
 * capture runner), falling back to {@link Build#MODEL}.</p>
 */
final class MediaCapture {
    static final String TAG = "TREKUI-MEDIA";
    /** Folder on the device the capture runner pulls. */
    static final String DEVICE_DIR = "/sdcard/trekui-test";

    private final Instrumentation instrumentation;
    private final String scenario;
    private ParcelFileDescriptor recording;
    private String videoFile;

    MediaCapture(Instrumentation instrumentation, String scenario) {
        this.instrumentation = instrumentation;
        this.scenario = scenario;
        shell("mkdir -p " + DEVICE_DIR);
    }

    /** Device label used in file names: the {@code consoleDevice} argument or the model name. */
    static String deviceName() {
        String arg = InstrumentationRegistry.getArguments().getString("trekuiDevice");
        return sanitize(arg != null && !arg.isEmpty() ? arg : Build.MODEL);
    }

    /** Replaces everything except letters, digits, '_' and '-' with '-' (shared with the runner). */
    static String sanitize(String s) {
        String out = s.replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("-+", "-");
        while (out.endsWith("-")) out = out.substring(0, out.length() - 1);
        return out.isEmpty() ? "x" : out;
    }

    /** "portrait" or "landscape", from the current real display size. */
    static String orientation(Context context) {
        DisplayMetrics m = realMetrics(context);
        return m.widthPixels > m.heightPixels ? "landscape" : "portrait";
    }

    static DisplayMetrics realMetrics(Context context) {
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics m = new DisplayMetrics();
        //noinspection deprecation - getRealMetrics is the only call that works from API 26 to 37
        wm.getDefaultDisplay().getRealMetrics(m);
        return m;
    }

    private String baseName(String step) {
        Context ctx = instrumentation.getTargetContext();
        String s = step == null || step.isEmpty() ? scenario : scenario + "-" + sanitize(step);
        return String.format(Locale.US, "%s__%s__api%d__%s", s, deviceName(), Build.VERSION.SDK_INT, orientation(ctx));
    }

    /**
     * Saves a PNG of the whole screen (status and navigation bars included, so overlap is visible).
     *
     * @param step short label for this moment of the scenario, e.g. "home" or "drawer-open"
     * @return the device path written
     */
    String screenshot(String step) {
        // Let the last frame reach the screen before grabbing it.
        instrumentation.runOnMainSync(() -> { });
        SystemClock.sleep(400);
        String path = DEVICE_DIR + "/" + baseName(step) + ".png";
        shell("screencap -p " + path);
        Log.i(TAG, "screenshot " + path);
        return path;
    }

    /** Starts {@code screenrecord} in the background; {@link #stopVideo()} finalises the mp4. */
    void startVideo() {
        if (recording != null) return;
        if ("false".equals(InstrumentationRegistry.getArguments().getString("trekuiVideo"))) return;
        videoFile = DEVICE_DIR + "/" + baseName(null) + ".mp4";
        DisplayMetrics m = realMetrics(instrumentation.getTargetContext());
        int w = m.widthPixels, h = m.heightPixels;
        // Software encoders on emulators reject very large frames; keep the long side <= 1280.
        float scale = Math.min(1f, 1280f / Math.max(w, h));
        w = Math.round(w * scale) & ~15;
        h = Math.round(h * scale) & ~15;
        String cmd = String.format(Locale.US, "screenrecord --bit-rate 2000000 --time-limit 170 --size %dx%d %s", w, h, videoFile);
        try {
            recording = instrumentation.getUiAutomation().executeShellCommand(cmd);
            Log.i(TAG, "recording " + videoFile);
            SystemClock.sleep(600); // encoder start-up, so the first frames are not lost
        } catch (RuntimeException e) {
            Log.w(TAG, "screenrecord unavailable: " + e);
            recording = null;
        }
    }

    /** Stops the recording with SIGINT (so the mp4 header is written) and waits for it to finish. */
    void stopVideo() {
        if (recording == null) return;
        SystemClock.sleep(500);
        shell("pkill -2 screenrecord");
        // Reading the pipe to EOF waits until screenrecord has exited and flushed the file; a helper
        // thread keeps a stuck encoder from hanging the whole run.
        final ParcelFileDescriptor pfd = recording;
        Thread waiter = new Thread(() -> drain(pfd, 8000), "screenrecord-wait");
        waiter.start();
        try {
            waiter.join(8000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (waiter.isAlive()) {
            shell("pkill -9 screenrecord");
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
        recording = null;
        Log.i(TAG, "recorded " + videoFile);
    }

    /** Runs a shell command as the shell user and returns its stdout (blocking until it exits). */
    String shell(String command) {
        UiAutomation ua = instrumentation.getUiAutomation();
        ParcelFileDescriptor pfd = ua.executeShellCommand(command);
        return drain(pfd, 30000);
    }

    private static String drain(ParcelFileDescriptor pfd, long timeoutMs) {
        StringBuilder sb = new StringBuilder();
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        try (InputStream in = new FileInputStream(pfd.getFileDescriptor())) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n));
                if (SystemClock.uptimeMillis() > deadline) break;
            }
        } catch (IOException e) {
            Log.w(TAG, "shell read: " + e);
        } finally {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
        }
        return sb.toString();
    }
}
