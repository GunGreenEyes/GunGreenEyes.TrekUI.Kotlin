package com.gungreeneyes.trekui.messages;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Image work for attachments: down-scaled thumbnails for bubbles (cached, decoded in the background) and
 * JPEG compression to fit the carrier's MMS size limit.
 */
public final class Images {
    /** Longest side of an image sent by MMS. */
    static final int SEND_MAX_SIDE = 1280;
    /** Total MMS payload most carriers accept (300 KB); images share it. */
    static final int MMS_BUDGET_BYTES = 300 * 1024;
    /** Room kept for the text, SMIL and headers. */
    static final int MMS_OVERHEAD_BYTES = 16 * 1024;
    private static final int MIN_QUALITY = 30;

    /** Receives a decoded thumbnail on the main thread (null when unreadable). */
    public interface Callback { void loaded(Bitmap bitmap); }

    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "messages-images");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private Images() {}

    /** A cached thumbnail, or null (then call {@link #load}). */
    public static Bitmap cached(String uri, int maxSide) {
        return CACHE.get(uri + "@" + maxSide);
    }

    /** Decodes a thumbnail of {@code uri} no larger than {@code maxSide} px in the background. */
    public static void load(Context c, String uri, int maxSide, Callback cb) {
        Context app = c.getApplicationContext();
        String key = uri + "@" + maxSide;
        IO.execute(() -> {
            Bitmap b = decode(app, Uri.parse(uri), maxSide);
            if (b != null) CACHE.put(key, b);
            MAIN.post(() -> cb.loaded(b));
        });
    }

    /** Decodes {@code uri} scaled down to at most {@code maxSide} px (blocking), or null. */
    public static Bitmap decode(Context c, Uri uri, int maxSide) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide);
            Bitmap b;
            try (InputStream in = c.getContentResolver().openInputStream(uri)) {
                if (in == null) return null;
                b = BitmapFactory.decodeStream(in, null, opts);
            }
            return b == null ? null : scaleDown(b, maxSide);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Largest power-of-two sample size keeping the longer side at least {@code maxSide}. */
    static int sampleSize(int w, int h, int maxSide) {
        int sample = 1;
        int longer = Math.max(w, h);
        while (longer / (sample * 2) >= maxSide) sample *= 2;
        return sample;
    }

    private static Bitmap scaleDown(Bitmap b, int maxSide) {
        int longer = Math.max(b.getWidth(), b.getHeight());
        if (longer <= maxSide) return b;
        float s = maxSide / (float) longer;
        Matrix m = new Matrix();
        m.setScale(s, s);
        Bitmap out = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        if (out != b) b.recycle();
        return out;
    }

    /**
     * Copies a picked image into the app's cache as a JPEG sized for MMS, so it stays readable after the
     * picker's temporary grant ends. Blocking; returns the file or null.
     */
    public static File importForSending(Context c, Uri picked) {
        Bitmap b = decode(c, picked, SEND_MAX_SIDE);
        if (b == null) return null;
        File dir = new File(c.getCacheDir(), "attach");
        if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        File f = new File(dir, "img-" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 90, out);
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * JPEG bytes of {@code file} compressed until they fit {@code budget} bytes (quality, then size, is
     * lowered). Blocking; null when unreadable.
     */
    public static byte[] jpegWithin(File file, int budget) {
        Bitmap b = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (b == null) return null;
        int side = Math.max(b.getWidth(), b.getHeight());
        while (true) {
            for (int q = 85; q >= MIN_QUALITY; q -= 10) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, q, out);
                if (out.size() <= budget) return out.toByteArray();
            }
            side = side * 3 / 4;
            if (side < 160) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, MIN_QUALITY, out);
                return out.toByteArray();
            }
            b = scaleDown(b, side);
        }
    }
}
