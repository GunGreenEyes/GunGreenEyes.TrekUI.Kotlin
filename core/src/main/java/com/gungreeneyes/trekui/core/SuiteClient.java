package com.gungreeneyes.trekui.core;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.gungreeneyes.trekui.licensing.Licensing;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads sibling TrekUI apps' {@link SuiteProvider}s: "is any installed sibling unlocked?" (one purchase
 * unlocks every TrekUI app). A sibling only counts when it is signed with this app's key and actually owns
 * the provider authority, so a look-alike package installed from elsewhere can never grant an unlock.
 */
public final class SuiteClient {
    /** Result of {@link #checkSiblingsAsync}, delivered on the main thread. */
    public interface Callback {
        void onDone(boolean unlockedBySibling);
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "trekui-suite");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SuiteClient() {}

    /**
     * Asks every installed sibling (in the background) whether it is unlocked; the first that is gets
     * cached through {@link Licensing#grantFromSibling}. {@code callback} may be null.
     */
    public static void checkSiblingsAsync(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            String from = firstUnlockedSibling(app);
            boolean granted = from != null && Licensing.grantFromSibling(app, from);
            if (callback != null) MAIN.post(() -> callback.onDone(granted));
        });
    }

    /** The package of the first trusted sibling reporting itself unlocked, or null. Blocking. */
    public static String firstUnlockedSibling(Context context) {
        String own = context.getPackageName();
        for (String pkg : SuiteContract.siblingPackages(own)) {
            if (Boolean.TRUE.equals(SuiteContract.parseUnlock(query(context, pkg, SuiteContract.PATH_UNLOCK)))) {
                return pkg;
            }
        }
        return null;
    }

    /** The launcher's published theme choice, or null (not installed, untrusted, or no answer). Blocking. */
    public static SuiteTheme launcherTheme(Context context) {
        String launcher = SuiteContract.launcherPackage(context.getPackageName());
        if (launcher.equals(context.getPackageName())) return null;
        return SuiteContract.parseTheme(query(context, launcher, SuiteContract.PATH_THEME));
    }

    /**
     * The launcher's SUITE › LOCK SCREEN switch: TRUE / FALSE, or null when the launcher is absent,
     * untrusted or too old to publish it (the wake screen then follows its own setting). Blocking.
     */
    public static Boolean launcherLockscreenEnabled(Context context) {
        String launcher = SuiteContract.launcherPackage(context.getPackageName());
        if (launcher.equals(context.getPackageName())) return null;
        return SuiteContract.parseLockscreen(query(context, launcher, SuiteContract.PATH_LOCKSCREEN));
    }

    /** One row of {@code pkg}'s suite provider at {@code path}, or null when it can't be trusted or read. */
    static Map<String, String> query(Context context, String pkg, String path) {
        if (!trusted(context, pkg)) return null;
        try (Cursor c = context.getContentResolver().query(
                Uri.parse(SuiteContract.uriString(pkg, path)), null, null, null, null)) {
            if (c == null || !c.moveToFirst()) return null;
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < c.getColumnCount(); i++) row.put(c.getColumnName(i), c.getString(i));
            return row;
        } catch (RuntimeException e) {
            return null; // permission denied, provider crashed or went away: no answer
        }
    }

    /** True if {@code pkg} is installed, signed with our key, and is the one serving its suite authority. */
    private static boolean trusted(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        try {
            if (pm.checkSignatures(context.getPackageName(), pkg) != PackageManager.SIGNATURE_MATCH) return false;
            ProviderInfo info = pm.resolveContentProvider(SuiteContract.authority(pkg), 0);
            return info != null && pkg.equals(info.packageName);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
