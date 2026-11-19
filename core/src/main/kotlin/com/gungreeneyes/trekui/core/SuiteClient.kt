package com.gungreeneyes.trekui.core

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.gungreeneyes.trekui.licensing.Licensing
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Reads sibling TrekUI apps' [SuiteProvider]s: "is any installed sibling unlocked?" (one purchase
 * unlocks every TrekUI app). A sibling only counts when it is signed with this app's key and actually owns
 * the provider authority, so a look-alike package installed from elsewhere can never grant an unlock.
 *
 * Queries run on one background daemon thread ("trekui-suite"); results are posted to the main thread.
 */
object SuiteClient {
    /** Result of [checkSiblingsAsync], delivered on the main thread. */
    fun interface Callback {
        fun onDone(unlockedBySibling: Boolean)
    }

    private val IO: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "trekui-suite").apply { isDaemon = true }
    }
    private val MAIN = Handler(Looper.getMainLooper())

    /**
     * Asks every installed sibling (in the background) whether it owns its own purchase; the first that
     * does gets cached through [Licensing.grantFromSibling]. A cached grant whose sibling now answers that
     * it owns no purchase (refunded) is revoked first. `callback` may be null.
     */
    @JvmStatic
    fun checkSiblingsAsync(context: Context, callback: Callback?) {
        val app = context.applicationContext
        IO.execute {
            val grantor = Licensing.suiteGrantSource(app)
            if (grantor != null && ownsPurchase(app, grantor) == false) Licensing.revokeSiblingGrant(app, grantor)
            val from = firstUnlockedSibling(app)
            val granted = from != null && Licensing.grantFromSibling(app, from)
            if (callback != null) MAIN.post { callback.onDone(granted) }
        }
    }

    /** The package of the first trusted sibling reporting its own purchase, or null. Blocking. */
    @JvmStatic
    fun firstUnlockedSibling(context: Context): String? =
        SuiteContract.siblingPackages(context.packageName).firstOrNull { pkg -> ownsPurchase(context, pkg) == true }

    /** Does trusted sibling `pkg` report its own purchase? Null when it can't be trusted or read. Blocking. */
    private fun ownsPurchase(context: Context, pkg: String): Boolean? =
        SuiteContract.parseOwnPurchase(query(context, pkg, SuiteContract.PATH_UNLOCK))

    /** The launcher's published theme choice, or null (not installed, untrusted, or no answer). Blocking. */
    @JvmStatic
    fun launcherTheme(context: Context): SuiteTheme? {
        val launcher = SuiteContract.launcherPackage(context.packageName)
        if (launcher == context.packageName) return null
        return SuiteContract.parseTheme(query(context, launcher, SuiteContract.PATH_THEME))
    }

    /**
     * The launcher's SUITE › LOCK SCREEN switch: TRUE / FALSE, or null when the launcher is absent,
     * untrusted or too old to publish it (the wake screen then follows its own setting). Blocking.
     */
    @JvmStatic
    fun launcherLockscreenEnabled(context: Context): Boolean? {
        val launcher = SuiteContract.launcherPackage(context.packageName)
        if (launcher == context.packageName) return null
        return SuiteContract.parseLockscreen(query(context, launcher, SuiteContract.PATH_LOCKSCREEN))
    }

    /** One row of `pkg`'s suite provider at `path`, or null when it can't be trusted or read. */
    @JvmStatic
    internal fun query(context: Context, pkg: String, path: String): Map<String, String?>? {
        if (!trusted(context, pkg)) return null
        return try {
            context.contentResolver.query(
                Uri.parse(SuiteContract.uriString(pkg, path)), null, null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return null
                HashMap<String, String?>().apply {
                    for (i in 0 until c.columnCount) put(c.getColumnName(i), c.getString(i))
                }
            }
        } catch (e: RuntimeException) {
            null // permission denied, provider crashed or went away: no answer
        }
    }

    /** True if `pkg` is installed, signed with our key, and is the one serving its suite authority. */
    private fun trusted(context: Context, pkg: String): Boolean {
        val pm = context.packageManager
        return try {
            if (pm.checkSignatures(context.packageName, pkg) != PackageManager.SIGNATURE_MATCH) return false
            val info = pm.resolveContentProvider(SuiteContract.authority(pkg), 0)
            info != null && pkg == info.packageName
        } catch (e: RuntimeException) {
            false
        }
    }
}
