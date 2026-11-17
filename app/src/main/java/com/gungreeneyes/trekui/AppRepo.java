package com.gungreeneyes.trekui;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Installed-app catalogue via {@link LauncherApps} (the official launcher API: includes work
 * profile / Secure Folder apps, and reports installs/removals so the drawer stays current).
 *
 * <p>A process-wide singleton holding only the application context. Loading runs on a background
 * thread; the finished list is published on the main thread and listeners are told. Bursts of
 * package events are coalesced ({@link #RELOAD_DEBOUNCE_MS}). Sorting and search matching are
 * pure functions ({@link #compareEntries}, {@link #matches}) unit-tested in {@code AppRepoTest}.
 */
final class AppRepo {
    /** Told on the main thread whenever {@link #apps()} has a new list. */
    interface Listener { void onAppsChanged(); }

    /** Package events usually arrive in bursts (install = removed + added + changed). */
    static final long RELOAD_DEBOUNCE_MS = 400;

    private static AppRepo instance;
    private final Context ctx;
    private final LauncherApps la;
    private final UserManager um;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Http.newExecutor("trekui-apps");
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<AppInfo> apps = Collections.emptyList();
    private volatile Map<String, AppInfo> byKey = Collections.emptyMap();
    /** Main thread only: a list is published; a load is running; another load was asked for meanwhile. */
    private boolean loaded, pending, again;

    private final Runnable reload = new Runnable() {
        @Override public void run() { load(); }
    };

    private AppRepo(Context c) {
        ctx = c.getApplicationContext();
        la = (LauncherApps) ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        um = (UserManager) ctx.getSystemService(Context.USER_SERVICE);
        la.registerCallback(new LauncherApps.Callback() {
            @Override public void onPackageRemoved(String p, UserHandle u) { scheduleReload(); }
            @Override public void onPackageAdded(String p, UserHandle u) { scheduleReload(); }
            @Override public void onPackageChanged(String p, UserHandle u) { scheduleReload(); }
            @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean r) { scheduleReload(); }
            @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean r) { scheduleReload(); }
        }, main);
    }

    /** The shared catalogue (created on first use; keeps only the application context). */
    static synchronized AppRepo get(Context c) {
        if (instance == null) instance = new AppRepo(c);
        return instance;
    }

    LauncherApps launcherApps() { return la; }
    UserManager users() { return um; }

    /** Main thread only. Listeners must be removed when their screen goes away. */
    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }
    boolean isLoaded() { return loaded; }

    /** The current list, sorted by label (immutable; replaced, never modified). */
    List<AppInfo> apps() { return apps; }

    /** Reloads shortly, coalescing bursts of package events. */
    void scheduleReload() {
        main.removeCallbacks(reload);
        main.postDelayed(reload, RELOAD_DEBOUNCE_MS);
    }

    long serial(UserHandle u) { return um.getSerialNumberForUser(u); }
    long mySerial() { return serial(Process.myUserHandle()); }

    /** The profile for a stored serial number; the main profile when unknown or negative. */
    UserHandle userFor(long serial) {
        if (serial < 0) return Process.myUserHandle();
        UserHandle u = um.getUserForSerialNumber(serial);
        return u != null ? u : Process.myUserHandle();
    }

    /**
     * Finds an app by flattened component and profile serial (negative = main profile); falls
     * back to the main-profile app with the same package when the activity was renamed.
     */
    AppInfo find(String flatComponent, long serial) {
        if (flatComponent == null) return null;
        AppInfo a = byKey.get(AppInfo.key(flatComponent, serial < 0 ? mySerial() : serial));
        if (a != null) return a;
        ComponentName cn = ComponentName.unflattenFromString(flatComponent);
        return cn == null ? null : findPackage(cn.getPackageName());
    }

    /** The first main-profile app in a package, or null. */
    AppInfo findPackage(String pkg) {
        if (pkg == null) return null;
        for (AppInfo a : apps) if (a.mainProfile && a.pkg.equals(pkg)) return a;
        return null;
    }

    /** Reloads the list in the background (main thread only); a call during a load queues one more. */
    void load() {
        if (pending) { again = true; return; }
        pending = true;
        worker.execute(new Runnable() {
            @Override public void run() {
                final List<AppInfo> list = query();
                final Map<String, AppInfo> map = new HashMap<>();
                for (AppInfo a : list) map.put(a.key, a);
                main.post(new Runnable() {
                    @Override public void run() {
                        apps = Collections.unmodifiableList(list);
                        byKey = map;
                        loaded = true;
                        pending = false;
                        for (Listener l : new ArrayList<>(listeners)) l.onAppsChanged();
                        if (again) { again = false; load(); }
                    }
                });
            }
        });
    }

    /** Background thread: every launchable activity in every profile except this launcher, sorted. */
    private List<AppInfo> query() {
        List<AppInfo> list = new ArrayList<>();
        UserHandle me = Process.myUserHandle();
        String self = ctx.getPackageName();
        List<UserHandle> profiles;
        try { profiles = um.getUserProfiles(); } catch (RuntimeException e) { profiles = Collections.singletonList(me); }
        for (UserHandle u : profiles) {
            List<LauncherActivityInfo> infos;
            try { infos = la.getActivityList(null, u); } catch (RuntimeException e) { continue; } // profile locked or gone
            long serial = serial(u);
            boolean mainProfile = u.equals(me);
            for (LauncherActivityInfo li : infos) {
                ComponentName cn = li.getComponentName();
                if (mainProfile && self.equals(cn.getPackageName())) continue;
                CharSequence label = li.getLabel();
                AppInfo a = new AppInfo(cn, u, serial, mainProfile,
                        label == null ? null : label.toString().trim(), li.getApplicationInfo());
                try {
                    Drawable d = li.getBadgedIcon(0);
                    a.rawIcon = d;
                } catch (RuntimeException ignored) {
                    // icon unavailable: IconFactory draws a glyph instead
                }
                list.add(a);
            }
        }
        sort(list);
        // LCA-T22: icon pack swap, per-app icon choices and keyword-matched pictograms
        IconPacks.apply(ctx, list);
        Prefs prefs = Prefs.get(ctx);
        IconFactory.assignGlyphs(list, appIconChoices(prefs), prefs.b(IconPacks.PREF_AUTO_GLYPHS, true));
        return list;
    }

    /** Every stored per-app icon choice: AppInfo.key → choice (see {@link IconPacks}). */
    static Map<String, String> appIconChoices(Prefs prefs) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, ?> e : prefs.sp.getAll().entrySet()) {
            if (e.getKey().startsWith(IconPacks.PREF_APP_PREFIX) && e.getValue() instanceof String) {
                out.put(e.getKey().substring(IconPacks.PREF_APP_PREFIX.length()), (String) e.getValue());
            }
        }
        return out;
    }

    /** Sorts apps by label (locale collation, case/accent-insensitive), main profile first on ties. */
    static void sort(List<AppInfo> list) {
        final Collator col = Collator.getInstance();
        col.setStrength(Collator.PRIMARY);
        Collections.sort(list, new Comparator<AppInfo>() {
            @Override public int compare(AppInfo x, AppInfo y) {
                return compareEntries(col, x.label, x.mainProfile, y.label, y.mainProfile);
            }
        });
    }

    /** Drawer order: by label with the given collator; for equal labels the main profile comes first. */
    static int compareEntries(Collator col, String labelA, boolean mainA, String labelB, boolean mainB) {
        int r = col.compare(labelA, labelB);
        return r != 0 ? r : Boolean.compare(!mainA, !mainB);
    }

    /**
     * Search match used by app pickers: every word of the query must start a word of the label
     * or appear in the package name; an empty query matches everything.
     *
     * @param lowerLabel the app's lower-case label
     */
    static boolean matches(String lowerLabel, String pkg, String query) {
        if (query == null) return true;
        String q = query.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty()) return true;
        String label = lowerLabel == null ? "" : lowerLabel;
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.US);
        for (String word : q.split("\\s+")) {
            boolean hit = p.contains(word) || label.startsWith(word) || label.contains(" " + word)
                    || label.contains("-" + word) || label.contains("." + word);
            if (!hit) return false;
        }
        return true;
    }

    /** Apps in a category ({@link AppInfo#CAT_ALL} for every one) that match a search. */
    static List<AppInfo> filter(List<AppInfo> all, String query, int category) {
        List<AppInfo> out = new ArrayList<>();
        for (AppInfo a : all) {
            if (category != AppInfo.CAT_ALL && a.category != category) continue;
            if (matches(a.lower, a.pkg, query)) out.add(a);
        }
        return out;
    }
}
