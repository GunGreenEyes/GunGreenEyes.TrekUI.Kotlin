package com.gungreeneyes.trekui.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.gungreeneyes.trekui.TrekPrefs;
import com.gungreeneyes.trekui.core.SuiteClient;
import com.gungreeneyes.trekui.core.SuiteContract;
import com.gungreeneyes.trekui.core.SuiteTheme;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Which interface and sub-theme the keyboard draws in.
 *
 * <p>When the TrekUI launcher (same flavor, same signing key) is installed, the keyboard follows it:
 * {@link #refresh} reads the launcher's choice through {@link SuiteClient#launcherTheme} in the background
 * (at most every {@link #MIN_REFRESH_MS} from the keyboard, on every resume from settings) and installs it
 * with {@link TrekPrefs#setThemeOverride}, so every :core drawing helper answers from it. The last answer
 * is cached ({@link #applyCached}) so a cold start draws in the right look before the provider answers.
 * Without the launcher the override is cleared and the keyboard's own choice (settings › INTERFACE,
 * stored in its own {@code trekui} prefs) applies. Same scheme as TrekUI Messages.
 */
public final class KeyboardTheme {
    private static final String FILE = "keyboard_theme";
    private static final String KEY_CACHED = "launcher_theme";
    /** The keyboard re-asks the launcher at most this often (it starts input many times a minute). */
    static final long MIN_REFRESH_MS = 15_000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "keyboard-theme");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastRefresh = Long.MIN_VALUE / 2;

    private KeyboardTheme() {}

    /** True while the launcher's choice is applied (the keyboard's own picker is then read-only). */
    public static boolean followingLauncher() {
        return TrekPrefs.themeOverride() != null;
    }

    /** Applies the launcher theme cached by the last refresh, if the launcher is still installed. */
    public static void applyCached(Context c) {
        SharedPreferences sp = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        SuiteTheme t = decode(sp.getString(KEY_CACHED, null));
        if (t != null && launcherInstalled(c)) TrekPrefs.setThemeOverride(t);
    }

    /** Like {@link #refresh} but skipped if the last refresh was less than {@link #MIN_REFRESH_MS} ago. */
    public static void refreshThrottled(Context context, Runnable onChanged) {
        if (SystemClock.elapsedRealtime() - lastRefresh < MIN_REFRESH_MS) return;
        refresh(context, onChanged);
    }

    /**
     * Re-reads the launcher's choice in the background; on the main thread installs it (or clears the
     * override when the launcher is gone) and runs {@code onChanged} if the look changed.
     */
    public static void refresh(Context context, Runnable onChanged) {
        lastRefresh = SystemClock.elapsedRealtime();
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            SuiteTheme t = SuiteClient.launcherTheme(app);
            MAIN.post(() -> {
                SuiteTheme before = TrekPrefs.themeOverride();
                TrekPrefs.setThemeOverride(t);
                app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                        .putString(KEY_CACHED, encode(t)).apply();
                if (!Objects.equals(before, t) && onChanged != null) onChanged.run();
            });
        });
    }

    private static boolean launcherInstalled(Context c) {
        try {
            c.getPackageManager().getPackageInfo(SuiteContract.launcherPackage(c.getPackageName()), 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** "faction,subTheme,lettering,soundSet" (soundSet may be empty), or null. */
    static String encode(SuiteTheme t) {
        if (t == null) return null;
        return t.faction + "," + t.subTheme + "," + t.lettering + "," + (t.soundSet == null ? "" : t.soundSet);
    }

    static SuiteTheme decode(String s) {
        if (s == null) return null;
        String[] p = s.split(",", -1);
        if (p.length < 4) return null;
        try {
            int f = TrekPrefs.clampFaction(Integer.parseInt(p[0]));
            return new SuiteTheme(f, Integer.parseInt(p[1]), Integer.parseInt(p[2]), p[3].isEmpty() ? null : p[3]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
