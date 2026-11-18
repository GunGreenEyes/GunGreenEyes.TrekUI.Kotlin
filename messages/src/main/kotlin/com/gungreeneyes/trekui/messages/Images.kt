package com.gungreeneyes.trekui.messages

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.core.net.toUri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Image work for attachments: down-scaled thumbnails for bubbles (cached, decoded in the background) and
 * JPEG compression to fit the carrier's MMS size limit.
 */
internal object Images {
    /** Longest side of an image sent by MMS. */
    const val SEND_MAX_SIDE = 1280

    /** Total MMS payload most carriers accept (300 KB); images share it. */
    const val MMS_BUDGET_BYTES = 300 * 1024

    /** Room kept for the text, SMIL and headers. */
    const val MMS_OVERHEAD_BYTES = 16 * 1024
    private const val MIN_QUALITY = 30

    private val IO: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "messages-images").apply { isDaemon = true }
    }
    private val MAIN = Handler(Looper.getMainLooper())
    private val CACHE = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** A cached thumbnail, or null (then call [load]). */
    fun cached(uri: String, maxSide: Int): Bitmap? = CACHE.get("$uri@$maxSide")

    /** Decodes a thumbnail of [uri] no larger than [maxSide] px in the background; [loaded] gets it on the main thread (null when unreadable). */
    fun load(c: Context, uri: String, maxSide: Int, loaded: (Bitmap?) -> Unit) {
        val app = c.applicationContext
        val key = "$uri@$maxSide"
        IO.execute {
            val b = decode(app, uri.toUri(), maxSide)
            if (b != null) CACHE.put(key, b)
            MAIN.post { loaded(b) }
        }
    }

    /** Decodes [uri] scaled down to at most [maxSide] px (blocking), or null. */
    fun decode(c: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val opened = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds); true } ?: false
        if (!opened || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide)
            }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?.let { scaleDown(it, maxSide) }
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** Largest power-of-two sample size keeping the longer side at least [maxSide]. */
    fun sampleSize(w: Int, h: Int, maxSide: Int): Int {
        var sample = 1
        val longer = maxOf(w, h)
        while (longer / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val longer = maxOf(b.width, b.height)
        if (longer <= maxSide) return b
        val s = maxSide / longer.toFloat()
        val m = Matrix().apply { setScale(s, s) }
        val out = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
        if (out !== b) b.recycle()
        return out
    }

    /**
     * Copies a picked image into the app's cache as a JPEG sized for MMS, so it stays readable after the
     * picker's temporary grant ends. Blocking; returns the file or null.
     */
    fun importForSending(c: Context, picked: Uri): File? {
        val b = decode(c, picked, SEND_MAX_SIDE) ?: return null
        val dir = File(c.cacheDir, "attach")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, "img-" + System.currentTimeMillis() + ".jpg")
        return try {
            FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            f
        } catch (e: IOException) {
            null
        }
    }

    /**
     * JPEG bytes of [file] compressed until they fit [budget] bytes (quality, then size, is
     * lowered). Blocking; null when unreadable.
     */
    fun jpegWithin(file: File, budget: Int): ByteArray? {
        var b = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        var side = maxOf(b.width, b.height)
        while (true) {
            for (q in 85 downTo MIN_QUALITY step 10) {
                val out = ByteArrayOutputStream()
                b.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= budget) return out.toByteArray()
            }
            side = side * 3 / 4
            if (side < 160) {
                val out = ByteArrayOutputStream()
                b.compress(Bitmap.CompressFormat.JPEG, MIN_QUALITY, out)
                return out.toByteArray()
            }
            b = scaleDown(b, side)
        }
    }
}
