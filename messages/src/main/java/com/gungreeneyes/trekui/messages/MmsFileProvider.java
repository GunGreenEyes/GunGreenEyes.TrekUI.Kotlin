package com.gungreeneyes.trekui.messages;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Hands MMS PDU files to the platform MMS service: {@code SmsManager.sendMultimediaMessage} reads an
 * outgoing m-send-req from {@code content://<app>.mmsfiles/<name>} and
 * {@code downloadMultimediaMessage} writes the downloaded m-retrieve-conf there. Not exported; the
 * system's MMS broker is granted access per call ({@code grantUriPermissions}). Files live in
 * {@code cache/mms/} and are deleted once handled.
 */
public final class MmsFileProvider extends ContentProvider {
    private static final String DIR = "mms";

    /** The content URI of PDU file {@code name}. */
    public static Uri uriFor(Context c, String name) {
        return new Uri.Builder().scheme("content").authority(c.getPackageName() + ".mmsfiles").appendPath(name).build();
    }

    /** The backing file of PDU {@code name} (directory created on demand). */
    public static File file(Context c, String name) {
        File dir = new File(c.getCacheDir(), DIR);
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        return new File(dir, name);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context c = getContext();
        String name = uri.getLastPathSegment();
        if (c == null || name == null || !name.matches("[A-Za-z0-9._-]+")) throw new FileNotFoundException(String.valueOf(uri));
        File f = file(c, name);
        int m = ParcelFileDescriptor.parseMode(mode);
        if ((m & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0 || (m & ParcelFileDescriptor.MODE_READ_WRITE) != 0) {
            m |= ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        return ParcelFileDescriptor.open(f, m);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.wap.mms-message";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
