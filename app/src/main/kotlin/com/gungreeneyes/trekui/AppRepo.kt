package com.gungreeneyes.trekui

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import java.text.Collator
import java.util.Collections
import java.util.Locale

/**
 * Installed-app catalogue via [LauncherApps] (the official launcher API: includes work
 * profile / Secure Folder apps, and reports installs/removals so the drawer stays current).
 *
 * A process-wide singleton holding only the application context. Loading runs on a background
 * thread; the finished list is published on the main thread and listeners are told. Bursts of
 * package events are coalesced ([RELOAD_DEBOUNCE_MS]). Sorting and search matching are
 * pure functions ([compareEntries], [matches]) unit-tested in `AppRepoTest`.
 */
internal class AppRepo private constructor(c: Context) {
    /** Told on the main thread whenever [apps] has a new list (implemented by the Java `MainActivity`). */
    interface Listener {
        fun onAppsChanged()
    }

    private val ctx: Context = c.applicationContext
    private val la: LauncherApps = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val um: UserManager = ctx.getSystemService(Context.USER_SERVICE) as UserManager
    private val main = Handler(Looper.getMainLooper())
    private val worker = Http.newExecutor("trekui-apps")
    private val listeners = ArrayList<Listener>()

    @Volatile
    private var current: List<AppInfo> = Collections.emptyList()

    @Volatile
    private var byKey: Map<String, AppInfo> = Collections.emptyMap()

    /** Main thread only: a list is published; a load is running; another load was asked for meanwhile. */
    private var loaded = false
    private var pending = false
    private var again = false

    private val reload = Runnable { load() }

    init {
        la.registerCallback(object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = scheduleReload()

            override fun onPackageAdded(packageName: String?, user: UserHandle?) = scheduleReload()

            override fun onPackageChanged(packageName: String?, user: UserHandle?) = scheduleReload()

            override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) =
                scheduleReload()

            override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) =
                scheduleReload()
        }, main)
    }

    fun launcherApps(): LauncherApps = la

    fun users(): UserManager = um

    /** Main thread only. Listeners must be removed when their screen goes away. */
    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** True once a list has been published (main thread). */
    val isLoaded: Boolean
        get() = loaded

    /** The current list, sorted by label (immutable; replaced, never modified). */
    fun apps(): List<AppInfo> = current

    /** Reloads shortly, coalescing bursts of package events. */
    fun scheduleReload() {
        main.removeCallbacks(reload)
        main.postDelayed(reload, RELOAD_DEBOUNCE_MS)
    }

    fun serial(u: UserHandle): Long = um.getSerialNumberForUser(u)

    fun mySerial(): Long = serial(Process.myUserHandle())

    /** The profile for a stored serial number; the main profile when unknown or negative. */
    fun userFor(serial: Long): UserHandle {
        if (serial < 0) return Process.myUserHandle()
        return um.getUserForSerialNumber(serial) ?: Process.myUserHandle()
    }

    /**
     * Finds an app by flattened component and profile serial (negative = main profile); falls
     * back to the main-profile app with the same package when the activity was renamed.
     */
    fun find(flatComponent: String?, serial: Long): AppInfo? {
        if (flatComponent == null) return null
        byKey[AppInfo.key(flatComponent, if (serial < 0) mySerial() else serial)]?.let { return it }
        val cn = ComponentName.unflattenFromString(flatComponent) ?: return null
        return findPackage(cn.packageName)
    }

    /** The first main-profile app in a package, or null. */
    fun findPackage(pkg: String?): AppInfo? {
        if (pkg == null) return null
        return current.firstOrNull { it.mainProfile && it.pkg == pkg }
    }

    /** Reloads the list in the background (main thread only); a call during a load queues one more. */
    fun load() {
        if (pending) {
            again = true
            return
        }
        pending = true
        worker.execute {
            val list = query()
            val map = HashMap<String, AppInfo>()
            for (a in list) map[a.key] = a
            main.post {
                current = Collections.unmodifiableList(list)
                byKey = map
                loaded = true
                pending = false
                for (l in ArrayList(listeners)) l.onAppsChanged()
                if (again) {
                    again = false
                    load()
                }
            }
        }
    }

    /** Background thread: every launchable activity in every profile except this launcher, sorted. */
    private fun query(): MutableList<AppInfo> {
        val list = ArrayList<AppInfo>()
        val me = Process.myUserHandle()
        val self = ctx.packageName
        val profiles: List<UserHandle> = try {
            um.userProfiles
        } catch (e: RuntimeException) {
            Collections.singletonList(me)
        }
        for (u in profiles) {
            val infos: List<LauncherActivityInfo> = try {
                la.getActivityList(null, u)
            } catch (e: RuntimeException) {
                continue // profile locked or gone
            }
            val serial = serial(u)
            val mainProfile = u == me
            for (li in infos) {
                val cn = li.componentName
                if (mainProfile && self == cn.packageName) continue
                val label: CharSequence? = li.label
                val a = AppInfo(cn, u, serial, mainProfile, label?.toString()?.javaTrim(), li.applicationInfo)
                try {
                    a.rawIcon = li.getBadgedIcon(0)
                } catch (ignored: RuntimeException) {
                    // icon unavailable: IconFactory draws a glyph instead
                }
                list.add(a)
            }
        }
        sort(list)
        // LCA-T22: icon pack swap, per-app icon choices and keyword-matched pictograms
        IconPacks.apply(ctx, list)
        val prefs = Prefs.get(ctx)
        IconFactory.assignGlyphs(list, appIconChoices(prefs), prefs.b(IconPacks.PREF_AUTO_GLYPHS, true))
        return list
    }

    companion object {
        /** Package events usually arrive in bursts (install = removed + added + changed). */
        const val RELOAD_DEBOUNCE_MS = 400L

        private val WHITESPACE = Regex("\\s+")

        private var instance: AppRepo? = null

        /** The shared catalogue (created on first use; keeps only the application context). */
        @JvmStatic
        fun get(c: Context): AppRepo = synchronized(AppRepo::class.java) {
            instance ?: AppRepo(c).also { instance = it }
        }

        /** Every stored per-app icon choice: AppInfo.key → choice (see [IconPacks]). */
        @JvmStatic
        fun appIconChoices(prefs: Prefs): Map<String, String> {
            val out = HashMap<String, String>()
            for ((k, v) in prefs.sp.all) {
                if (k.startsWith(IconPacks.PREF_APP_PREFIX) && v is String) {
                    out[k.substring(IconPacks.PREF_APP_PREFIX.length)] = v
                }
            }
            return out
        }

        /** Sorts apps by label (locale collation, case/accent-insensitive), main profile first on ties. */
        @JvmStatic
        fun sort(list: MutableList<AppInfo>) {
            val col = Collator.getInstance()
            col.strength = Collator.PRIMARY
            list.sortWith { x, y -> compareEntries(col, x.label, x.mainProfile, y.label, y.mainProfile) }
        }

        /** Drawer order: by label with the given collator; for equal labels the main profile comes first. */
        @JvmStatic
        fun compareEntries(col: Collator, labelA: String?, mainA: Boolean, labelB: String?, mainB: Boolean): Int {
            val r = col.compare(labelA, labelB)
            return if (r != 0) r else (!mainA).compareTo(!mainB)
        }

        /**
         * Search match used by app pickers: every word of the query must start a word of the label
         * or appear in the package name; an empty query matches everything.
         *
         * @param lowerLabel the app's lower-case label
         */
        @JvmStatic
        fun matches(lowerLabel: String?, pkg: String?, query: String?): Boolean {
            if (query == null) return true
            val q = query.javaTrim().lowercase(Locale.getDefault())
            if (q.isEmpty()) return true
            val label = lowerLabel ?: ""
            val p = pkg?.lowercase(Locale.US) ?: ""
            for (word in q.split(WHITESPACE)) {
                val hit = p.contains(word) || label.startsWith(word) || label.contains(" $word") ||
                    label.contains("-$word") || label.contains(".$word")
                if (!hit) return false
            }
            return true
        }

        /** Apps in a category ([AppInfo.CAT_ALL] for every one) that match a search. */
        @JvmStatic
        fun filter(all: List<AppInfo>, query: String?, category: Int): List<AppInfo> =
            all.filterTo(ArrayList()) {
                (category == AppInfo.CAT_ALL || it.category == category) && matches(it.lower, it.pkg, query)
            }
    }
}
