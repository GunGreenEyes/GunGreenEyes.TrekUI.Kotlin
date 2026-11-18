package com.gungreeneyes.trekui.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.gungreeneyes.trekui.TrekPrefs
import com.gungreeneyes.trekui.licensing.Licensing

/**
 * Read-only provider every TrekUI app registers so its siblings can ask "are you unlocked?" and
 * "which interface / sub-theme / sound set did the user choose?" (rows defined by [SuiteContract]).
 *
 * Registration in each app's manifest (the permission itself is declared by :core's manifest):
 * ```
 * <provider
 *     android:name="com.gungreeneyes.trekui.core.SuiteProvider"
 *     android:authorities="${applicationId}.suite"
 *     android:exported="true"
 *     android:readPermission="${trekuiSuitePermission}"
 *     android:writePermission="${trekuiSuitePermission}" />
 * ```
 * `trekuiSuitePermission` is a manifest placeholder the app sets per flavor
 * ([SuiteContract.permission]). Only apps signed with the same key hold that
 * signature-level permission, so no other app can read these rows.
 *
 * Every app also answers `lockscreen` with its `TrekPrefs.lockscreenEnabled()` switch;
 * only the launcher's (set in CONFIG › SUITE) is read by the wake-screen companion. Subclass to publish
 * more paths: override [queryOther]. Writes are not supported.
 *
 * `open` and registered by this fully qualified name in every app's manifest: keep both.
 */
open class SuiteProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? {
        val c = context ?: return null
        val path = uri.pathSegments.firstOrNull() ?: ""
        return when (path) {
            SuiteContract.PATH_UNLOCK -> row(
                SuiteContract.UNLOCK_COLUMNS,
                SuiteContract.unlockRow(c.packageName, Licensing.isShareableUnlock(c), Licensing.unlockSource(c).name),
            )
            SuiteContract.PATH_THEME -> row(
                SuiteContract.THEME_COLUMNS,
                SuiteContract.themeRow(c.packageName, currentTheme(c)),
            )
            SuiteContract.PATH_LOCKSCREEN -> row(
                SuiteContract.LOCKSCREEN_COLUMNS,
                SuiteContract.lockscreenRow(c.packageName, TrekPrefs.get(c).lockscreenEnabled()),
            )
            else -> queryOther(uri, path)
        }
    }

    /**
     * The choice this app publishes on the `theme` path. The launcher's own settings; a companion
     * publishes what it currently shows (the launcher's while it follows it).
     */
    protected open fun currentTheme(c: Context): SuiteTheme = TrekPrefs.get(c).currentTheme()

    /** Hook for subclasses: answer another path, or null (unknown path). */
    protected open fun queryOther(uri: Uri, path: String): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int =
        throw UnsupportedOperationException("read-only")

    companion object {
        /**
         * Tells observers of this app's `path` row that it changed (e.g. after the user picks another
         * sub-theme or flips SUITE › LOCK SCREEN), so a running companion can re-read it at once. Siblings
         * observe with `ContentResolver.registerContentObserver` on
         * `SuiteContract.uriString(launcherPackage, path)`.
         */
        @JvmStatic
        fun notifyChanged(context: Context, path: String) {
            try {
                context.contentResolver.notifyChange(
                    Uri.parse(SuiteContract.uriString(context.packageName, path)), null,
                )
            } catch (ignored: RuntimeException) {
                // no observers / resolver unavailable: nothing to tell
            }
        }

        /** A one-row cursor of `row` in `columns` order. */
        @JvmStatic
        protected fun row(columns: Array<String>, row: Map<String, String?>): Cursor {
            val m = MatrixCursor(columns, 1)
            m.addRow(SuiteContract.values(row, columns))
            return m
        }
    }
}
