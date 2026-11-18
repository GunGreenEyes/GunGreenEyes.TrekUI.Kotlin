package com.gungreeneyes.trekui.messages

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Hands MMS PDU files to the platform MMS service: `SmsManager.sendMultimediaMessage` reads an
 * outgoing m-send-req from `content://<app>.mmsfiles/<name>` and
 * `downloadMultimediaMessage` writes the downloaded m-retrieve-conf there. Not exported; the
 * system's MMS broker is granted access per call (`grantUriPermissions`). Files live in
 * `cache/mms/` and are deleted once handled.
 */
class MmsFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val c = context
        val name = uri.lastPathSegment
        if (c == null || name == null || !SAFE_NAME.matches(name)) throw FileNotFoundException(uri.toString())
        val f = file(c, name)
        var m = ParcelFileDescriptor.parseMode(mode)
        if ((m and ParcelFileDescriptor.MODE_WRITE_ONLY) != 0 || (m and ParcelFileDescriptor.MODE_READ_WRITE) != 0) {
            m = m or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        }
        return ParcelFileDescriptor.open(f, m)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = "application/vnd.wap.mms-message"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    internal companion object {
        private const val DIR = "mms"
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

        /** The content URI of PDU file [name]. */
        fun uriFor(c: Context, name: String): Uri =
            Uri.Builder().scheme("content").authority(c.packageName + ".mmsfiles").appendPath(name).build()

        /** The backing file of PDU [name] (directory created on demand). */
        fun file(c: Context, name: String): File {
            val dir = File(c.cacheDir, DIR)
            if (!dir.exists()) dir.mkdirs()
            return File(dir, name)
        }
    }
}
