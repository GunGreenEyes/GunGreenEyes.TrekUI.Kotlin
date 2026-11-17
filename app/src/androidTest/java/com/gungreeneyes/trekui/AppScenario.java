package com.gungreeneyes.trekui;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import androidx.test.uiautomator.UiDevice;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TestName;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Base class of every instrumented scenario.
 *
 * <p>Per scenario it: puts the device in the run's orientation (instrumentation argument
 * {@code consoleOrientation} = natural | portrait | landscape), starts a screen recording named after the
 * scenario, resets the launcher's preferences to a quiet known state (no boot animation, no welcome
 * dialog, trial forced unlocked in debug builds so the 5-minute develop trial cannot interrupt a long
 * run), and afterwards stops the recording and restores display size/orientation.</p>
 *
 * <p>Scenario ids are {@code <TestClass>_<method>} sanitised with {@link MediaCapture#sanitize}; the
 * capture runner uses the same rule to match instrumentation results to media files.</p>
 */
public abstract class AppScenario {
    static final String TAG = "TREKUI-TEST";
    static final long LAUNCH_TIMEOUT_MS = 20000;
    /** Time allowed for reveal animations and first panel updates before a screenshot. */
    static final long SETTLE_MS = 1500;

    @Rule
    public final TestName testName = new TestName();

    protected Instrumentation instrumentation;
    protected UiDevice device;
    protected Context context;
    protected MediaCapture media;
    protected Gherkin scenario;

    @Before
    public final void setUpScenario() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        device = UiDevice.getInstance(instrumentation);
        context = instrumentation.getTargetContext();
        String id = MediaCapture.sanitize(getClass().getSimpleName() + "_" + testName.getMethodName());
        applyRunOrientation();
        media = new MediaCapture(instrumentation, id);
        scenario = new Gherkin(id);
        resetPreferences();
        forceLicence(Boolean.TRUE, 0);
        media.startVideo();
    }

    @After
    public final void tearDownScenario() {
        try {
            media.stopVideo();
        } finally {
            media.shell("wm size reset");
            media.shell("wm density reset");
            try {
                applyRunOrientation();
            } catch (RemoteException e) {
                Log.w(TAG, "orientation restore failed", e);
            }
        }
    }

    // ---- device ----------------------------------------------------------------------------------

    /** Instrumentation argument {@code consoleOrientation}: natural (default), portrait or landscape. */
    static String runOrientation() {
        Bundle args = InstrumentationRegistry.getArguments();
        String o = args.getString("trekuiOrientation");
        return o == null ? "natural" : o;
    }

    protected void applyRunOrientation() throws RemoteException {
        String o = runOrientation();
        if ("landscape".equals(o)) {
            device.setOrientationLandscape();
        } else if ("portrait".equals(o)) {
            device.setOrientationPortrait();
        } else {
            device.setOrientationNatural();
        }
        SystemClock.sleep(500);
    }

    // ---- launcher state --------------------------------------------------------------------------

    /** The launcher's own preference file (see {@link Prefs}). */
    protected SharedPreferences launcherPrefs() {
        return context.getSharedPreferences("trekui", Context.MODE_PRIVATE);
    }

    /** Quiet defaults: no boot sequence, no first-run welcome, Command interface. */
    protected void resetPreferences() {
        SharedPreferences sp = launcherPrefs();
        SharedPreferences.Editor e = sp.edit()
                .putBoolean("welcomed", true)
                .putBoolean("bootSeq", false);
        Interface.COMMAND.apply(e);
        e.putInt("ver", sp.getInt("ver", 0) + 1).commit();
    }

    /**
     * Debug-build licence hooks from {@code com.gungreeneyes.trekui.licensing.Licensing}, called by
     * reflection so these tests keep compiling while that package evolves.
     *
     * @param unlocked     TRUE forces unlocked, FALSE locked, null clears the override
     * @param trialSeconds session length override in seconds, 0 restores the BuildConfig value
     * @return false when the licensing package or its hooks are not present
     */
    protected boolean forceLicence(Boolean unlocked, int trialSeconds) {
        try {
            Class<?> lic = Class.forName("com.gungreeneyes.trekui.licensing.Licensing");
            Method setUnlocked = lic.getMethod("debugSetUnlocked", Context.class, Boolean.class);
            Method setSeconds = lic.getMethod("debugSetTrialSeconds", Context.class, int.class);
            instrumentation.runOnMainSync(() -> {
                try {
                    setUnlocked.invoke(null, context, unlocked);
                    setSeconds.invoke(null, context, trialSeconds);
                } catch (ReflectiveOperationException ex) {
                    throw new RuntimeException(ex);
                }
            });
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.i(TAG, "licensing hooks unavailable: " + e);
            return false;
        }
    }

    /**
     * Switches to {@code iface} and brings the launcher's home screen to the front.
     *
     * <p>Bumping the {@code ver} preference makes {@link MainActivity#onResume} rebuild its UI, so the
     * new interface also applies when the (singleTask) activity is already running.</p>
     */
    protected MainActivity launchHome(Interface iface) {
        SharedPreferences sp = launcherPrefs();
        SharedPreferences.Editor e = sp.edit();
        iface.apply(e);
        e.putInt("ver", sp.getInt("ver", 0) + 1).commit();
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setClassName(context, MainActivity.class.getName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        MainActivity a = waitForResumed(MainActivity.class, LAUNCH_TIMEOUT_MS);
        settle();
        dismissSystemDialogs();
        return a;
    }

    /**
     * Clears system windows that a freshly booted emulator likes to show over the launcher (the
     * "System UI / Pixel Launcher isn't responding" ANR dialog, notification shade, crash dialogs of
     * other apps). These are environment noise, not launcher bugs; an ANR of the launcher itself is
     * still reported because its process name is logged.
     */
    protected void dismissSystemDialogs() {
        for (int attempt = 0; attempt < 3; attempt++) {
            String pkg = device.getCurrentPackageName();
            if (context.getPackageName().equals(pkg)) return;
            androidx.test.uiautomator.UiObject2 wait = device.findObject(
                    androidx.test.uiautomator.By.res("android", "aerr_wait"));
            if (wait == null) {
                wait = device.findObject(androidx.test.uiautomator.By.res("android", "aerr_close"));
            }
            if (wait != null) {
                Log.w(TAG, "dismissing system dialog over the launcher: "
                        + device.findObject(androidx.test.uiautomator.By.res("android", "alertTitle")));
                wait.click();
            } else {
                media.shell("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS");
            }
            SystemClock.sleep(800);
        }
    }

    /** Waits for the main thread and lets animations finish (no idle-sync: the starfield never idles). */
    protected void settle() {
        instrumentation.runOnMainSync(() -> { });
        SystemClock.sleep(SETTLE_MS);
        instrumentation.runOnMainSync(() -> { });
    }

    /** Polls the lifecycle monitor until an activity of {@code type} is RESUMED, or fails. */
    protected <T extends Activity> T waitForResumed(Class<T> type, long timeoutMs) {
        T found = findResumed(type, timeoutMs);
        if (found == null) {
            fail(scenario.lastStep() + ": no resumed " + type.getSimpleName() + " within " + timeoutMs
                    + "ms (resumed: " + describeResumed() + ")");
        }
        return found;
    }

    /** Like {@link #waitForResumed} but returns null instead of failing. */
    @SuppressWarnings("unchecked")
    protected <T extends Activity> T findResumed(Class<T> type, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        AtomicReference<Activity> ref = new AtomicReference<>();
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> {
                Collection<Activity> resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED);
                for (Activity a : resumed) {
                    if (type.isInstance(a)) ref.set(a);
                }
            });
            if (ref.get() != null) return (T) ref.get();
            SystemClock.sleep(200);
        }
        return null;
    }

    /** Resumed activity whose class name contains {@code fragment} (for classes we cannot reference). */
    protected Activity findResumedByName(String fragment, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        AtomicReference<Activity> ref = new AtomicReference<>();
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync(() -> {
                for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                    if (a.getClass().getName().contains(fragment)) ref.set(a);
                }
            });
            if (ref.get() != null) return ref.get();
            SystemClock.sleep(200);
        }
        return null;
    }

    protected String describeResumed() {
        StringBuilder sb = new StringBuilder();
        instrumentation.runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                sb.append(a.getClass().getSimpleName()).append(' ');
            }
        });
        sb.append("| foreground pkg ").append(device.getCurrentPackageName());
        return sb.toString();
    }

    /** Runs {@code r} on the main thread and returns its result. */
    protected <T> T onMain(java.util.concurrent.Callable<T> r) {
        AtomicReference<T> out = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                out.set(r.call());
            } catch (Throwable t) {
                err.set(t);
            }
        });
        if (err.get() != null) throw new AssertionError(scenario.lastStep() + ": " + err.get(), err.get());
        return out.get();
    }

    // ---- assertions ------------------------------------------------------------------------------

    /** Asserts the activity shows something: a content view with at least one key view. */
    protected void assertShowsContent(Activity activity) {
        assertNotNull(scenario.lastStep() + ": activity", activity);
        int keys = onMain(() -> LayoutAudit.countKeyViews(activity));
        // Canvas-drawn screens (e.g. the purchase screen) may have no labelled child views at all:
        // then a visible, laid-out content view is enough.
        int drawn = onMain(() -> visibleLeaves(activity.findViewById(android.R.id.content)));
        assertTrue(scenario.lastStep() + ": expected visible content, found " + keys + " key views and "
                + drawn + " drawn views", keys > 0 || drawn > 0);
    }

    /** Visible, non-empty leaf views (custom-drawn views included) below {@code v}. */
    static int visibleLeaves(android.view.View v) {
        if (v == null || v.getVisibility() != android.view.View.VISIBLE || v.getWidth() == 0 || v.getHeight() == 0) return 0;
        if (v instanceof android.view.ViewGroup && ((android.view.ViewGroup) v).getChildCount() > 0) {
            int n = 0;
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) n += visibleLeaves(g.getChildAt(i));
            return n;
        }
        return 1;
    }

    /** Asserts nothing important is clipped off-screen or hidden under system bars; lists all problems. */
    protected void assertFitsScreen(Activity activity, String where) {
        List<LayoutAudit.Violation> v = onMain(() -> LayoutAudit.audit(activity));
        if (!v.isEmpty()) {
            StringBuilder sb = new StringBuilder(scenario.lastStep()).append(" [").append(where).append("] ")
                    .append(v.size()).append(" layout problem(s) on ").append(MediaCapture.deviceName())
                    .append(" api").append(android.os.Build.VERSION.SDK_INT).append(' ')
                    .append(MediaCapture.orientation(context)).append(':');
            int n = 0;
            for (LayoutAudit.Violation x : v) {
                sb.append("\n  - ").append(x);
                if (++n == 25) {
                    sb.append("\n  ... ").append(v.size() - n).append(" more");
                    break;
                }
            }
            Log.e(TAG, sb.toString());
            fail(sb.toString());
        }
    }
}
