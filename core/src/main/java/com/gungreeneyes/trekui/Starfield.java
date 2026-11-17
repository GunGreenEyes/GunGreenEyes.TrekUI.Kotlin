package com.gungreeneyes.trekui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.Random;

/**
 * Static procedural starfield background: a near-black sky, two faint nebula glows in palette
 * colours and stars at a density proportional to the area. Rendered once per size to an RGB_565
 * bitmap (no ongoing redraw cost); the fixed seed keeps the sky identical across rebuilds.
 */
final class Starfield extends Drawable {
    /** Seed for the star positions (the same sky every time). */
    private static final long SEED = 1701;
    /** One star per this many square pixels. */
    private static final int PIXELS_PER_STAR = 5200;

    private final Palette pal;
    private Bitmap cache;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    Starfield(Palette pal) { this.pal = pal; }

    @Override
    protected void onBoundsChange(Rect b) {
        if (b.width() <= 0 || b.height() <= 0) return;
        if (cache != null && cache.getWidth() == b.width() && cache.getHeight() == b.height()) return;
        if (cache != null) cache.recycle(); // size changed (rotation, resize): free the old sky
        cache = Bitmap.createBitmap(b.width(), b.height(), Bitmap.Config.RGB_565);
        Canvas c = new Canvas(cache);
        c.drawColor(Color.rgb(3, 3, 8));
        int w = b.width(), h = b.height();
        p.setShader(new RadialGradient(w * 0.8f, h * 0.3f, w * 0.9f, Ui.alpha(pal.c(5), 0.16f), 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.15f, h * 0.78f, w * 0.8f, Ui.alpha(pal.c(2), 0.12f), 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        Random r = new Random(SEED);
        int n = (w * h) / PIXELS_PER_STAR;
        for (int i = 0; i < n; i++) {
            float x = r.nextFloat() * w, y = r.nextFloat() * h;
            float s = r.nextFloat();
            float rad = s < 0.92f ? 0.6f + s * 1.1f : 1.8f + r.nextFloat() * 1.4f;
            int a = 90 + r.nextInt(165);
            int tint = r.nextInt(10) == 0 ? pal.c(r.nextInt(4)) : Color.WHITE;
            p.setColor(Ui.alpha(tint, a / 255f));
            c.drawCircle(x, y, rad, p);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        if (cache == null) onBoundsChange(getBounds());
        if (cache != null) canvas.drawBitmap(cache, getBounds().left, getBounds().top, null);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(ColorFilter cf) { }
    @SuppressWarnings("deprecation") // still abstract in Drawable; required by every subclass
    @Override public int getOpacity() { return PixelFormat.OPAQUE; }
}
