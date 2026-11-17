package com.gungreeneyes.trekui.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.gungreeneyes.trekui.TrekPrefs;
import com.gungreeneyes.trekui.licensing.Licensing;

import java.util.List;
import java.util.Map;

/**
 * Read-only provider every TrekUI app registers so its siblings can ask "are you unlocked?" and
 * "which interface / sub-theme / sound set did the user choose?" (rows defined by {@link SuiteContract}).
 *
 * <p>Registration in each app's manifest (the permission itself is declared by :core's manifest):
 * <pre>
 * &lt;provider
 *     android:name="com.gungreeneyes.trekui.core.SuiteProvider"
 *     android:authorities="${applicationId}.suite"
 *     android:exported="true"
 *     android:readPermission="${trekuiSuitePermission}"
 *     android:writePermission="${trekuiSuitePermission}" /&gt;
 * </pre>
 * {@code trekuiSuitePermission} is a manifest placeholder the app sets per flavor
 * ({@link SuiteContract#permission(String)}). Only apps signed with the same key hold that
 * signature-level permission, so no other app can read these rows.
 *
 * <p>Subclass to publish more paths (for example the Lock Screen's on/off flag): override
 * {@link #queryOther}. Writes are not supported.
 */
public class SuiteProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context c = getContext();
        if (c == null) return null;
        List<String> segments = uri.getPathSegments();
        String path = segments.isEmpty() ? "" : segments.get(0);
        switch (path) {
            case SuiteContract.PATH_UNLOCK:
                return row(SuiteContract.UNLOCK_COLUMNS, SuiteContract.unlockRow(c.getPackageName(),
                        Licensing.isShareableUnlock(c), Licensing.unlockSource(c).name()));
            case SuiteContract.PATH_THEME:
                return row(SuiteContract.THEME_COLUMNS,
                        SuiteContract.themeRow(c.getPackageName(), currentTheme(c)));
            default:
                return queryOther(uri, path);
        }
    }

    /**
     * The choice this app publishes on the {@code theme} path. The launcher's own settings; a companion
     * publishes what it currently shows (the launcher's while it follows it).
     */
    protected SuiteTheme currentTheme(Context c) {
        return TrekPrefs.get(c).currentTheme();
    }

    /** Hook for subclasses: answer another path, or null (unknown path). */
    protected Cursor queryOther(Uri uri, String path) {
        return null;
    }

    /** A one-row cursor of {@code row} in {@code columns} order. */
    protected static Cursor row(String[] columns, Map<String, String> row) {
        MatrixCursor m = new MatrixCursor(columns, 1);
        m.addRow(SuiteContract.values(row, columns));
        return m;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
