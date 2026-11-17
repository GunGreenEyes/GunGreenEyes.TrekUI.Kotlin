package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Drawing kit for the three pipe-lounge interfaces. Each one has its own materials and geometry:
 *
 *  TOBACCONIST  Victorian tobacconist's cabinet: walnut, engraved brass plates, oxblood leather,
 *               parchment label cards, ring pulls. Cinzel and IM Fell English.
 *  EMBER LOUNGE Art Deco smoking room: black lacquer, gold double rules, stepped corners,
 *               sunburst fans, an ember glow. Limelight and Josefin Sans.
 *  BLEND JOURNAL Tobacco-barn ledger: pebbled leather, saddle stitching, kraft tags with brass
 *               grommets, red rubber-stamp ink, wax seals. Rye and Special Elite.
 */
final class PipeDraw {
    // fixed materials (colours that are part of the object, not the palette)
    static final int OXBLOOD = 0xFF6E1F1B, OXBLOOD_DK = 0xFF34100D, BRASS = 0xFFC9A04E, BRASS_HI = 0xFFF4DE9C,
            BRASS_LO = 0xFF6B4A1C, WALNUT_DK = 0xFF1C0F08, INK_DARK = 0xFF2A170C;
    static final int GOLD = 0xFFD4AF37, GOLD_PALE = 0xFFF0D78C, LACQ = 0xFF0D0807, LACQ_HI = 0xFF221511, EMBER = 0xFFE2572B;
    static final int RED_INK = 0xFFA8322A, LEATHER_DK = 0xFF1E110A, STITCH = 0xFFD8B47A, KRAFT_INK = 0xFF2E1A0E;

    /** Light parchment that dim controls' lettering is lifted towards (Tobacconist / Journal). */
    static final int PARCHMENT_LIGHT = 0xFFF1E3C2;
    /** Luminance below which a colour is too dark for dark lettering ({@link #dim}). */
    static final float DIM_LUMINANCE = 0.33f;
    /** How far {@link #lifted} moves a dim colour towards the light lettering colour. */
    static final float LIFT_AMOUNT = 0.62f;
    /** Alpha at or below which a control colour is drawn as a "ghost" (outline/recess) rather than solid. */
    static final int GHOST_ALPHA = 128;
    /** Alpha below which a control is not drawn at all. */
    static final int INVISIBLE_ALPHA = 8;
    /** Fallback fill when a texture asset is missing and no tint was given. */
    private static final int MISSING_TEXTURE = 0xFF5A341C;

    /** Decoded textures by asset name (null cached for missing assets). Textures are small, RGB_565. */
    private static final Map<String, Bitmap> TEX = new HashMap<String, Bitmap>();
    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Path TMP = new Path();
    private static final RectF RF = new RectF();

    private PipeDraw() {}

    // ---- materials ---------------------------------------------------------------------------

    /** Loads (once) a texture from {@code assets/pipe/<name>}; null if it is missing. */
    static synchronized Bitmap tex(Context c, String name) {
        if (TEX.containsKey(name)) return TEX.get(name);
        Bitmap b = null;
        InputStream in = null;
        try {
            in = c.getAssets().open("pipe/" + name);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            b = BitmapFactory.decodeStream(in, null, o);
        } catch (Exception e) {
            b = null;
        } finally {
            try { if (in != null) in.close(); } catch (Exception e) { }
        }
        TEX.put(name, b);
        return b;
    }

    /** A paint that fills with a tiled texture (mirrored, so no seams), optionally multiplied by a tint. */
    static Paint material(Context c, String name, int tint, float scale) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Bitmap b = tex(c, name);
        if (b == null) {
            p.setColor(tint != 0 ? tint : MISSING_TEXTURE);
            return p;
        }
        BitmapShader s = new BitmapShader(b, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR);
        if (scale != 1f) {
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            s.setLocalMatrix(m);
        }
        p.setShader(s);
        if (tint != 0) p.setColorFilter(new PorterDuffColorFilter(tint | 0xFF000000, PorterDuff.Mode.MULTIPLY));
        return p;
    }

    // ---- colour maths (pure: plain bit arithmetic, no android.graphics.Color, so JVM-testable) ------

    /** Alpha channel 0..255. */
    static int alphaOf(int c) { return (c >>> 24) & 0xFF; }
    /** Red channel 0..255. */
    static int redOf(int c) { return (c >> 16) & 0xFF; }
    /** Green channel 0..255. */
    static int greenOf(int c) { return (c >> 8) & 0xFF; }
    /** Blue channel 0..255. */
    static int blueOf(int c) { return c & 0xFF; }

    /** Packs ARGB channels (each clamped to 0..255) into a colour int. */
    static int argb(int alpha, int red, int green, int blue) {
        return (clampChannel(alpha) << 24) | (clampChannel(red) << 16) | (clampChannel(green) << 8) | clampChannel(blue);
    }

    private static int clampChannel(int v) { return v < 0 ? 0 : v > 255 ? 255 : v; }

    /** Linear blend of two ARGB colours, channel by channel: t=0 gives {@code a}, t=1 gives {@code b}. */
    static int mix(int a, int b, float t) {
        return argb(Math.round(alphaOf(a) + (alphaOf(b) - alphaOf(a)) * t),
                Math.round(redOf(a) + (redOf(b) - redOf(a)) * t),
                Math.round(greenOf(a) + (greenOf(b) - greenOf(a)) * t),
                Math.round(blueOf(a) + (blueOf(b) - blueOf(a)) * t));
    }

    /**
     * The colour with its existing alpha multiplied by {@code alpha} (clamped to 0..1). Unlike
     * {@link Ui#alpha}, which replaces alpha, this keeps a translucent palette colour translucent.
     */
    static int a(int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(255 * Math.max(0, Math.min(1, alpha)) * (alphaOf(color) / 255f)) << 24);
    }

    /**
     * Multiply-blend a texture over what's already drawn, keeping the destination opaque.
     * (PorterDuff MULTIPLY also multiplies alpha, which made the plates see-through.)
     */
    static boolean multiply(Paint p, int alpha) {
        if (android.os.Build.VERSION.SDK_INT < 29) return false;
        p.setAlpha(alpha);
        p.setBlendMode(android.graphics.BlendMode.MULTIPLY);
        return true;
    }

    /** A colour too dark to carry dark engraved/inked lettering. */
    static boolean dim(int c) {
        return lum(c) < DIM_LUMINANCE;
    }

    /** Light lettering for a dim control in the same family of colour. */
    static int lifted(int c) {
        return liftedFor(c, Skin.mLounge());
    }

    /** {@link #lifted} for an explicit family: towards pale gold on Ember Lounge, towards parchment elsewhere. */
    static int liftedFor(int c, boolean lounge) {
        return mix(c | 0xFF000000, lounge ? GOLD_PALE : PARCHMENT_LIGHT, LIFT_AMOUNT);
    }

    /** Relative luminance 0..1 (Rec. 709 weights on the gamma-encoded channels; alpha ignored). */
    static float lum(int c) {
        return (0.2126f * redOf(c) + 0.7152f * greenOf(c) + 0.0722f * blueOf(c)) / 255f;
    }

    // ---- shared shapes -----------------------------------------------------------------------

    /** Rectangle whose corners step inwards (Art Deco). steps=0 gives a plain rectangle. */
    static Path stepped(Path p, float l, float t, float r, float b, float s, int steps, boolean top, boolean bottom) {
        p.reset();
        if (steps <= 0 || s <= 0) {
            p.addRect(l, t, r, b, Path.Direction.CW);
            return p;
        }
        s = Math.min(s, Math.min((r - l) / 2.2f, (b - t) / 2.2f));
        float st = s / steps;
        int n = steps * 2 + 1;
        float[] dx = new float[n], dy = new float[n];
        dx[0] = 0; dy[0] = s;
        for (int i = 0; i < steps; i++) {
            dx[1 + 2 * i] = i * st;       dy[1 + 2 * i] = s - (i + 1) * st;
            dx[2 + 2 * i] = (i + 1) * st; dy[2 + 2 * i] = s - (i + 1) * st;
        }
        boolean first = true;
        // top-left, forward
        if (top) { for (int i = 0; i < n; i++) { float x = l + dx[i], y = t + dy[i]; if (first) { p.moveTo(x, y); first = false; } else p.lineTo(x, y); } }
        else { p.moveTo(l, t); first = false; }
        // top-right, reversed
        if (top) { for (int i = n - 1; i >= 0; i--) p.lineTo(r - dx[i], t + dy[i]); } else p.lineTo(r, t);
        // bottom-right, forward
        if (bottom) { for (int i = 0; i < n; i++) p.lineTo(r - dx[i], b - dy[i]); } else p.lineTo(r, b);
        // bottom-left, reversed
        if (bottom) { for (int i = n - 1; i >= 0; i--) p.lineTo(l + dx[i], b - dy[i]); } else p.lineTo(l, b);
        p.close();
        return p;
    }

    /** Luggage tag: chamfered left end, softly rounded right end. */
    static Path tag(Path p, float l, float t, float r, float b, float chamfer, float radius) {
        p.reset();
        p.moveTo(l + chamfer, t);
        p.lineTo(r - radius, t);
        RF.set(r - 2 * radius, t, r, t + 2 * radius);
        p.arcTo(RF, 270, 90, false);
        p.lineTo(r, b - radius);
        RF.set(r - 2 * radius, b - 2 * radius, r, b);
        p.arcTo(RF, 0, 90, false);
        p.lineTo(l + chamfer, b);
        p.lineTo(l, b - chamfer);
        p.lineTo(l, t + chamfer);
        p.close();
        return p;
    }

    /** Brass: vertical metallic gradient around a base colour, with an engraved inner line. */
    static Shader brass(float top, float bottom, int base) {
        int hi = mix(base, BRASS_HI, 0.55f), lo = mix(base, BRASS_LO, 0.55f);
        return new LinearGradient(0, top, 0, bottom, new int[]{hi, base, mix(base, lo, 0.4f), lo},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP);
    }

    /** A slotted brass screw head of radius {@code r}. */
    static void screw(Canvas cv, float x, float y, float r, float d) {
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(BRASS_LO);
        cv.drawCircle(x, y, r, P);
        P.setColor(mix(BRASS, BRASS_HI, 0.4f));
        cv.drawCircle(x - r * 0.2f, y - r * 0.2f, r * 0.62f, P);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(Math.max(1, r * 0.28f));
        P.setColor(BRASS_LO);
        cv.drawLine(x - r * 0.55f, y + r * 0.3f, x + r * 0.4f, y - r * 0.55f, P);
    }

    /** A domed rivet in {@code color} with a dark rim. */
    static void rivet(Canvas cv, float x, float y, float r, int color) {
        P.setShader(new RadialGradient(x - r * 0.35f, y - r * 0.35f, r * 1.4f,
                new int[]{mix(color, 0xFFFFFFFF, 0.6f), color, mix(color, 0xFF000000, 0.55f)}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        P.setStyle(Paint.Style.FILL);
        cv.drawCircle(x, y, r, P);
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(Math.max(1, r * 0.18f));
        P.setColor(a(0xFF000000, 0.45f));
        cv.drawCircle(x, y, r, P);
    }

    /** A wax seal: lumpy disc with a pressed ring. */
    static void seal(Canvas cv, float x, float y, float r, int color, int seed) {
        TMP.reset();
        int n = 14;
        for (int i = 0; i <= n; i++) {
            double ang = Math.PI * 2 * i / n;
            float rr = r * (0.9f + 0.1f * (float) Math.sin(i * 2.3 + seed));
            float px = x + (float) Math.cos(ang) * rr, py = y + (float) Math.sin(ang) * rr;
            if (i == 0) TMP.moveTo(px, py); else TMP.lineTo(px, py);
        }
        TMP.close();
        P.setStyle(Paint.Style.FILL);
        P.setShader(new RadialGradient(x - r * 0.3f, y - r * 0.3f, r * 1.3f,
                new int[]{mix(color, 0xFFFFFFFF, 0.25f), color, mix(color, 0xFF000000, 0.5f)}, null, Shader.TileMode.CLAMP));
        cv.drawPath(TMP, P);
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(Math.max(1, r * 0.12f));
        P.setColor(mix(color, 0xFF000000, 0.45f));
        cv.drawCircle(x, y, r * 0.58f, P);
    }

    /** Saddle stitching along a path (dashed round-capped stroke). {@code d} is pixels per dp. */
    static void stitches(Canvas cv, Path path, int color, float d, float alpha) {
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setStrokeWidth(1.4f * d);
        P.setPathEffect(new DashPathEffect(new float[]{4.5f * d, 3.2f * d}, 0));
        P.setColor(a(color, alpha));
        cv.drawPath(path, P);
        P.setPathEffect(null);
        P.setStrokeCap(Paint.Cap.BUTT);
    }

    // ---- generic control (Ui.shape / Skin.cell) ---------------------------------------------

    /** Buttons, segments, rules and small plates everywhere in the app. kind: 0 square, 1 pill, 2 left end, 3 right end. */
    static Drawable cell(Context c, int color, int kind) {
        return new Cell(c, color, kind);
    }

    /**
     * A control background in the current material family: an engraved brass plate (Tobacconist),
     * a stepped lacquer panel (Ember Lounge) or a stitched kraft label (Blend Journal). Translucent or
     * dim colours draw as "ghost" controls; anything thinner than 7dp draws as a rule; CLOAK draws an
     * outline on black.
     */
    static final class Cell extends Base {
        final int color, kind;
        Cell(Context c, int color, int kind) {
            super(c);
            this.color = color;
            this.kind = kind;
        }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            if (w < 2 || h < 2) return;
            if (alphaOf(color) < INVISIBLE_ALPHA) return;
            boolean ghost = alphaOf(color) <= GHOST_ALPHA || dim(color);
            int solid = alphaOf(color) > GHOST_ALPHA && dim(color) ? mix(color | 0xFF000000, Skin.mat() == Skin.LOUNGE ? GOLD : BRASS, 0.55f) : color | 0xFF000000;
            float l = b.left, t = b.top, r = b.right, bt = b.bottom;
            if (h <= 7 * d || w <= 7 * d) { rule(cv, l, t, r, bt, solid, ghost ? 0.6f : 1f); return; }
            int theme = Skin.mat();
            if (Ui.cloak) {
                outline(cv, l, t, r, bt, solid);
                return;
            }
            if (theme == Skin.TOBACCONIST) plate(cv, l, t, r, bt, solid, ghost);
            else if (theme == Skin.LOUNGE) lacquer(cv, l, t, r, bt, solid, ghost);
            else label(cv, l, t, r, bt, solid, ghost);
        }

        private Path shape(float l, float t, float r, float b) {
            float h = b - t;
            int theme = Skin.mat();
            if (theme == Skin.LOUNGE) {
                float s = Math.min(h * 0.22f, 7 * d);
                return stepped(path, l, t, r, b, kind == 0 ? s : s * 1.4f, 2, true, true);
            }
            float rad = theme == Skin.TOBACCONIST ? Math.min(h * 0.18f, 6 * d) : Math.min(h * 0.22f, 9 * d);
            if (kind == 1) rad = h / 2f;
            float rl = kind == 3 ? 2 * d : rad, rr = kind == 2 ? 2 * d : rad;
            if (kind == 2) rl = Math.min(h / 2f, 22 * d);
            if (kind == 3) rr = Math.min(h / 2f, 22 * d);
            path.reset();
            RF.set(l, t, r, b);
            path.addRoundRect(RF, new float[]{rl, rl, rr, rr, rr, rr, rl, rl}, Path.Direction.CW);
            return path;
        }

        private void outline(Canvas cv, float l, float t, float r, float b, int color) {
            Path p = shape(l + d, t + d, r - d, b - d);
            P.setShader(null);
            P.setStyle(Paint.Style.FILL);
            P.setColor(0xFF000000);
            cv.drawPath(p, P);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(1.5f * d);
            P.setColor(a(color, 0.65f));
            cv.drawPath(p, P);
        }

        /** Tobacconist: an engraved brass plate with screws. Ghost: a recessed walnut panel. */
        private void plate(Canvas cv, float l, float t, float r, float b, int color, boolean ghost) {
            float in = 1.2f * d;
            Path p = shape(l + in, t + in, r - in, b - in);
            if (ghost) {
                Paint m = material(ctx, "walnut.jpg", 0xFF8A7060, 0.6f);
                cv.drawPath(p, m);
                P.setShader(null);
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(1.3f * d);
                P.setColor(a(color, 0.7f));
                cv.drawPath(p, P);
                return;
            }
            // shadow, plate, engraved border, screws
            P.setShader(null);
            P.setStyle(Paint.Style.FILL);
            P.setColor(a(0xFF000000, 0.45f));
            cv.save();
            cv.translate(0, 1.4f * d);
            cv.drawPath(p, P);
            cv.restore();
            P.setShader(brass(t, b, color));
            cv.drawPath(p, P);
            Paint g = material(ctx, "brushed.jpg", 0, 1f);
            if (multiply(g, 70)) cv.drawPath(p, g);
            P.setShader(null);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(Math.max(1, 0.9f * d));
            P.setColor(a(BRASS_LO, 0.9f));
            float k = 3.4f * d;
            if (b - t > 22 * d && r - l > 30 * d) {
                Path inner = shape(l + k, t + k, r - k, b - k);
                cv.drawPath(inner, P);
                P.setColor(a(BRASS_HI, 0.5f));
                cv.save();
                cv.translate(0.8f * d, 0.8f * d);
                cv.drawPath(inner, P);
                cv.restore();
            }
            P.setColor(a(BRASS_LO, 1f));
            P.setStrokeWidth(Math.max(1, 1f * d));
            cv.drawPath(p, P);
            if (b - t > 34 * d && r - l > 60 * d) {
                float sr = 2.2f * d, sx = 7.5f * d, sy = 7.5f * d;
                screw(cv, l + sx, t + sy, sr, d);
                screw(cv, r - sx, t + sy, sr, d);
                screw(cv, l + sx, b - sy, sr, d);
                screw(cv, r - sx, b - sy, sr, d);
            }
        }

        /** Ember Lounge: black lacquer with a gold double rule and stepped corners. */
        private void lacquer(Canvas cv, float l, float t, float r, float b, int color, boolean ghost) {
            float in = 1.2f * d;
            Path p = shape(l + in, t + in, r - in, b - in);
            P.setStyle(Paint.Style.FILL);
            P.setShader(new LinearGradient(0, t, 0, b, ghost ? new int[]{0x99140C0A, 0x990B0706} : new int[]{LACQ_HI, LACQ, 0xFF080504},
                    null, Shader.TileMode.CLAMP));
            cv.drawPath(p, P);
            P.setShader(null);
            if (!ghost && b - t > 26 * d) {
                // a quiet fan at the foot, like the lounge's lighting
                cv.save();
                cv.clipPath(p);
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(Math.max(1, 0.8f * d));
                P.setColor(a(color, 0.16f));
                float cx = (l + r) / 2f, cy = b + (b - t) * 0.1f, len = (b - t) * 0.9f;
                for (int i = 1; i < 12; i++) {
                    double ang = Math.PI + Math.PI * i / 12;
                    cv.drawLine(cx, cy, cx + (float) Math.cos(ang) * len * 1.6f, cy + (float) Math.sin(ang) * len, P);
                }
                cv.restore();
            }
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(1.6f * d);
            P.setColor(a(color, ghost ? 0.55f : 1f));
            cv.drawPath(p, P);
            if (b - t > 24 * d && r - l > 30 * d) {
                float k = 4 * d;
                Path inner = shape(l + k, t + k, r - k, b - k);
                P.setStrokeWidth(Math.max(1, 0.7f * d));
                P.setColor(a(color, ghost ? 0.3f : 0.6f));
                cv.drawPath(inner, P);
            }
        }

        /** Blend Journal: a kraft paper label tinted by the colour; ghost: an empty stitched outline. */
        private void label(Canvas cv, float l, float t, float r, float b, int color, boolean ghost) {
            float in = 1.2f * d;
            Path p = shape(l + in, t + in, r - in, b - in);
            if (ghost) {
                P.setShader(null);
                P.setStyle(Paint.Style.FILL);
                P.setColor(a(color, 0.12f));
                cv.drawPath(p, P);
                stitches(cv, p, color, d, 0.7f);
                return;
            }
            P.setShader(null);
            P.setStyle(Paint.Style.FILL);
            P.setColor(a(0xFF000000, 0.5f));
            cv.save();
            cv.translate(0.8f * d, 1.6f * d);
            cv.drawPath(p, P);
            cv.restore();
            cv.drawPath(p, material(ctx, "paper_grain.jpg", color, 1f));
            // a soft darker edge, like handled paper
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(2.2f * d);
            P.setColor(a(0xFF5A3518, 0.28f));
            cv.drawPath(p, P);
            P.setStrokeWidth(Math.max(1, 0.8f * d));
            P.setColor(a(0xFF3A2010, 0.7f));
            cv.drawPath(p, P);
            if (b - t > 30 * d && r - l > 50 * d) {
                // red ruled line along the top, as on a ledger label
                P.setColor(a(RED_INK, 0.75f));
                P.setStrokeWidth(Math.max(1, 1f * d));
                float y = t + 7 * d;
                cv.drawLine(l + 8 * d, y, r - 8 * d, y, P);
            }
        }
    }

    /** Thin dividers: engraved brass rule, gold rule with diamond, or saddle stitching. */
    static void rule(Canvas cv, float l, float t, float r, float b, int color, float alpha) {
        int theme = Skin.mat();
        float cy = (t + b) / 2f, h = b - t;
        boolean vertical = h > (r - l);
        P.setShader(null);
        P.setPathEffect(null);
        if (theme == Skin.JOURNAL) {
            TMP.reset();
            if (vertical) { TMP.moveTo((l + r) / 2f, t); TMP.lineTo((l + r) / 2f, b); }
            else { TMP.moveTo(l, cy); TMP.lineTo(r, cy); }
            float d = Math.max(1f, Math.min(h, r - l) / 3f);
            stitches(cv, TMP, color, Math.max(1.2f, vertical ? (r - l) / 3f : h / 3f), alpha);
            return;
        }
        P.setStyle(Paint.Style.FILL);
        if (theme == Skin.LOUNGE && !vertical) {
            float th = Math.max(1, h * 0.28f);
            P.setColor(a(color, alpha));
            cv.drawRect(l, cy - th * 1.6f, r, cy - th * 0.6f, P);
            cv.drawRect(l, cy + th * 0.6f, r, cy + th * 1.6f, P);
            float cx = (l + r) / 2f, s = h * 0.9f;
            TMP.reset();
            TMP.moveTo(cx - s, cy); TMP.lineTo(cx, cy - s); TMP.lineTo(cx + s, cy); TMP.lineTo(cx, cy + s); TMP.close();
            cv.drawPath(TMP, P);
            return;
        }
        // Tobacconist: a bright line over a dark one, like an engraved groove
        P.setColor(a(mix(color, 0xFF000000, 0.45f), alpha));
        if (vertical) cv.drawRect((l + r) / 2f, t, r, b, P); else cv.drawRect(l, cy, r, b, P);
        P.setColor(a(mix(color, BRASS_HI, 0.3f), alpha));
        if (vertical) cv.drawRect(l, t, (l + r) / 2f, b, P); else cv.drawRect(l, t, r, cy, P);
    }

    /** A divider drawable: {@link #rule(Canvas, float, float, float, float, int, float)} centred vertically, at most 4dp tall. */
    static Drawable rule(Context c, final int color) {
        return new Base(c) {
            @Override public void draw(Canvas cv) {
                Rect b = getBounds();
                float h = Math.min(b.height(), 4 * d);
                float cy = b.exactCenterY();
                rule(cv, b.left, cy - h / 2f, b.right, cy + h / 2f, color | 0xFF000000, 1f);
            }
        };
    }

    // ---- home tiles ------------------------------------------------------------------------------

    /** Geometry shared with the tile layout in MainActivity (dp). */
    static final float TOB_CARD_TOP = 10, TOB_CARD_H = 34, TOB_CARD_SIDE = 12;

    /**
     * A home-screen tile background in the current material family.
     *
     * @param empty an unassigned tile (drawn recessed / blank)
     * @param index tile position, used to vary the Blend Journal tags
     */
    static Drawable tile(Context c, int color, boolean empty, int index) {
        int theme = Skin.mat();
        if (theme == Skin.TOBACCONIST) return new Drawer(c, color, empty);
        if (theme == Skin.LOUNGE) return new DecoPanel(c, color, empty);
        return new Tag(c, color, empty, index);
    }

    /** Tobacconist: a walnut drawer front with a brass card holder and a ring pull. */
    static final class Drawer extends Base {
        final int color;
        final boolean empty;
        Drawer(Context c, int color, boolean empty) { super(c); this.color = color | 0xFF000000; this.empty = empty; }

        @Override public void draw(Canvas cv) {
            Rect bb = getBounds();
            float l = bb.left + d, t = bb.top + d, r = bb.right - d, b = bb.bottom - d;
            if (r - l < 20 * d || b - t < 20 * d) return;
            if (Ui.cloak) {
                P.setShader(null); P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(1.4f * d); P.setColor(a(color, 0.6f));
                cv.drawRect(l, t, r, b, P);
                return;
            }
            // face
            Paint wood = material(ctx, "walnut.jpg", empty ? 0xFF8C7A70 : 0, 0.75f);
            cv.drawRect(l, t, r, b, wood);
            // bevel: light top/left, dark bottom/right, then a recessed field
            P.setShader(null);
            P.setStyle(Paint.Style.FILL);
            float bv = 3 * d;
            P.setColor(a(0xFFFFE0B0, 0.16f));
            cv.drawRect(l, t, r, t + bv, P);
            cv.drawRect(l, t, l + bv, b, P);
            P.setColor(a(0xFF000000, 0.45f));
            cv.drawRect(l, b - bv, r, b, P);
            cv.drawRect(r - bv, t, r, b, P);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(Math.max(1, 0.8f * d));
            P.setColor(a(0xFF000000, 0.5f));
            cv.drawRect(l + 6 * d, t + 6 * d, r - 6 * d, b - 6 * d, P);
            P.setColor(a(0xFFFFE0B0, 0.12f));
            cv.drawRect(l + 6.8f * d, t + 6.8f * d, r - 5.2f * d, b - 5.2f * d, P);
            P.setColor(a(0xFF000000, 0.75f));
            P.setStrokeWidth(Math.max(1, d));
            cv.drawRect(l, t, r, b, P);
            // brass card holder with a parchment card
            float cl = bb.left + TOB_CARD_SIDE * d, cr = bb.right - TOB_CARD_SIDE * d;
            float ct = bb.top + TOB_CARD_TOP * d, cb = ct + TOB_CARD_H * d;
            float fr = 2.6f * d;
            P.setStyle(Paint.Style.FILL);
            P.setColor(a(0xFF000000, 0.5f));
            cv.drawRect(cl - fr, ct - fr + 1.5f * d, cr + fr, cb + fr + 1.5f * d, P);
            P.setShader(brass(ct - fr, cb + fr, empty ? mix(color, 0xFF6B5A40, 0.5f) : color));
            cv.drawRect(cl - fr, ct - fr, cr + fr, cb + fr, P);
            P.setShader(null);
            if (empty) {
                P.setColor(0xFF1A0E08);
                cv.drawRect(cl, ct, cr, cb, P);
            } else {
                Paint parch = material(ctx, "parchment.jpg", 0, 0.5f);
                cv.drawRect(cl, ct, cr, cb, parch);
                P.setColor(a(0xFF000000, 0.22f));
                cv.drawRect(cl, ct, cr, ct + 2 * d, P);
            }
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(Math.max(1, 0.8f * d));
            P.setColor(BRASS_LO);
            cv.drawRect(cl - fr, ct - fr, cr + fr, cb + fr, P);
            screw(cv, cl - fr * 0.1f + 0.5f * d, (ct + cb) / 2f, 1.5f * d, d);
            screw(cv, cr + fr * 0.1f - 0.5f * d, (ct + cb) / 2f, 1.5f * d, d);
            // ring pull below the card
            float cx = (l + r) / 2f, py = cb + (b - cb) * 0.42f;
            if (b - cb > 18 * d) {
                float pr = Math.min(7.5f * d, (b - cb) * 0.36f);
                P.setStyle(Paint.Style.FILL);
                P.setShader(brass(py - 3 * d, py + 3 * d, color));
                RF.set(cx - 6 * d, py - 3.2f * d, cx + 6 * d, py + 2.2f * d);
                cv.drawRoundRect(RF, 2 * d, 2 * d, P);
                P.setShader(null);
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(2.6f * d);
                P.setColor(a(0xFF000000, 0.45f));
                cv.drawCircle(cx, py + pr + 1.2f * d, pr, P);
                P.setColor(empty ? mix(color, 0xFF5A4A30, 0.5f) : color);
                cv.drawCircle(cx, py + pr, pr, P);
                P.setStrokeWidth(0.9f * d);
                P.setColor(a(BRASS_HI, 0.7f));
                RF.set(cx - pr, py, cx + pr, py + 2 * pr);
                cv.drawArc(RF, 200, 110, false, P);
            }
        }
    }

    /** Ember Lounge: a lacquer panel with stepped shoulders, a gold double rule and a fan behind the icon. */
    static final class DecoPanel extends Base {
        final int color;
        final boolean empty;
        DecoPanel(Context c, int color, boolean empty) { super(c); this.color = color | 0xFF000000; this.empty = empty; }

        @Override public void draw(Canvas cv) {
            Rect bb = getBounds();
            float l = bb.left + d, t = bb.top + d, r = bb.right - d, b = bb.bottom - d;
            float h = b - t, w = r - l;
            if (w < 20 * d || h < 20 * d) return;
            float s = Math.min(14 * d, h * 0.16f);
            Path outer = stepped(path, l, t, r, b, s, 3, true, false);
            if (Ui.cloak) {
                P.setShader(null); P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(1.4f * d); P.setColor(a(color, 0.6f));
                cv.drawPath(outer, P);
                return;
            }
            P.setStyle(Paint.Style.FILL);
            P.setShader(new LinearGradient(0, t, 0, b, empty ? new int[]{0x8818100C, 0x880B0706} : new int[]{0xFF1E130F, LACQ, 0xFF070403},
                    empty ? null : new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            cv.drawPath(outer, P);
            P.setShader(null);
            float cx = (l + r) / 2f, fy = t + h * 0.56f;
            if (!empty) {
                // sunburst fan behind the icon
                cv.save();
                cv.clipPath(outer);
                P.setStyle(Paint.Style.FILL);
                P.setShader(new RadialGradient(cx, fy, h * 0.55f, new int[]{a(EMBER, 0.28f), a(EMBER, 0.06f), 0}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
                cv.drawCircle(cx, fy, h * 0.55f, P);
                P.setShader(null);
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(Math.max(1, 0.9f * d));
                for (int i = 0; i <= 14; i++) {
                    double ang = Math.PI + Math.PI * i / 14;
                    float r0 = h * 0.14f, r1 = h * 0.5f;
                    P.setColor(a(color, i % 2 == 0 ? 0.32f : 0.16f));
                    cv.drawLine(cx + (float) Math.cos(ang) * r0, fy + (float) Math.sin(ang) * r0,
                            cx + (float) Math.cos(ang) * r1, fy + (float) Math.sin(ang) * r1, P);
                }
                RF.set(cx - h * 0.14f, fy - h * 0.14f, cx + h * 0.14f, fy + h * 0.14f);
                P.setColor(a(color, 0.4f));
                cv.drawArc(RF, 180, 180, false, P);
                RF.set(cx - h * 0.5f, fy - h * 0.5f, cx + h * 0.5f, fy + h * 0.5f);
                P.setColor(a(color, 0.22f));
                cv.drawArc(RF, 180, 180, false, P);
                cv.restore();
            }
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(1.8f * d);
            P.setColor(a(color, empty ? 0.5f : 1f));
            cv.drawPath(outer, P);
            float k = 4.5f * d;
            Path inner = stepped(new Path(), l + k, t + k, r - k, b - k, Math.max(2 * d, s - k * 0.6f), 3, true, false);
            P.setStrokeWidth(Math.max(1, 0.8f * d));
            P.setColor(a(color, empty ? 0.25f : 0.55f));
            cv.drawPath(inner, P);
            if (!empty) {
                // three short bars at the foot
                P.setStyle(Paint.Style.FILL);
                P.setColor(a(color, 0.8f));
                float y = b - 7.5f * d;
                for (int i = -1; i <= 1; i++) {
                    float bw = i == 0 ? 10 * d : 5 * d;
                    float x = cx + i * 9 * d;
                    cv.drawRect(x - bw / 2f, y, x + bw / 2f, y + 1.4f * d, P);
                }
            }
        }
    }

    /** Blend Journal: a kraft luggage tag with a brass grommet and its string. */
    static final class Tag extends Base {
        final int color;
        final boolean empty;
        final int index;
        Tag(Context c, int color, boolean empty, int index) { super(c); this.color = color | 0xFF000000; this.empty = empty; this.index = index; }

        @Override public void draw(Canvas cv) {
            Rect bb = getBounds();
            float l = bb.left + 2 * d, t = bb.top + 2 * d, r = bb.right - 2 * d, b = bb.bottom - 3 * d;
            float h = b - t;
            if (r - l < 30 * d || h < 20 * d) return;
            float ch = Math.min(h * 0.32f, 12 * d);
            Path p = tag(path, l, t, r, b, ch, 6 * d);
            if (Ui.cloak) {
                P.setShader(null); P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(1.4f * d); P.setColor(a(color, 0.6f));
                cv.drawPath(p, P);
                return;
            }
            float hx = l + ch + 5 * d, hy = (t + b) / 2f;
            if (empty) {
                P.setShader(null);
                P.setStyle(Paint.Style.FILL);
                P.setColor(a(color, 0.10f));
                cv.drawPath(p, P);
                stitches(cv, p, color, d, 0.6f);
                return;
            }
            // tilt every other tag a little, as if pinned by hand
            cv.save();
            cv.rotate(index % 3 == 0 ? -0.8f : index % 3 == 1 ? 0.6f : 0f, (l + r) / 2f, hy);
            P.setShader(null);
            P.setStyle(Paint.Style.FILL);
            P.setColor(a(0xFF000000, 0.5f));
            cv.save();
            cv.translate(1.2f * d, 2.2f * d);
            cv.drawPath(p, P);
            cv.restore();
            cv.drawPath(p, material(ctx, "paper_grain.jpg", color, 1f));
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(3 * d);
            P.setColor(a(0xFF5A3518, 0.22f));
            cv.drawPath(p, P);
            P.setStrokeWidth(Math.max(1, 0.8f * d));
            P.setColor(a(0xFF3A2010, 0.65f));
            cv.drawPath(p, P);
            // red double rule near the right end
            P.setColor(a(RED_INK, 0.7f));
            P.setStrokeWidth(Math.max(1, 1.1f * d));
            float rx = r - 10 * d;
            cv.drawLine(rx, t + 5 * d, rx, b - 5 * d, P);
            cv.drawLine(rx - 3 * d, t + 5 * d, rx - 3 * d, b - 5 * d, P);
            // grommet and hole
            float gr = Math.min(5.5f * d, h * 0.16f);
            P.setStyle(Paint.Style.FILL);
            P.setShader(new RadialGradient(hx - gr * 0.4f, hy - gr * 0.4f, gr * 1.8f, new int[]{BRASS_HI, BRASS, BRASS_LO}, null, Shader.TileMode.CLAMP));
            cv.drawCircle(hx, hy, gr, P);
            P.setShader(null);
            P.setColor(LEATHER_DK);
            cv.drawCircle(hx, hy, gr * 0.5f, P);
            cv.restore();
            // the string, looping out past the left end
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(1.3f * d);
            P.setColor(a(0xFFE8D6B0, 0.85f));
            TMP.reset();
            TMP.moveTo(hx, hy);
            TMP.cubicTo(hx - 10 * d, hy - h * 0.4f, bb.left + d, hy - h * 0.1f, bb.left, hy + h * 0.25f);
            cv.drawPath(TMP, P);
        }
    }

    // ---- dock tray --------------------------------------------------------------------------------

    /** The dock background: a pipe rack (Tobacconist), a gilt bar (Ember Lounge) or a leather strap (Blend Journal). */
    static Drawable tray(Context c, final int color, final int slots) {
        return new Base(c) {
            @Override public void draw(Canvas cv) {
                Rect bb = getBounds();
                float l = bb.left, t = bb.top, r = bb.right, b = bb.bottom, h = b - t;
                if (r - l < 20 * d) return;
                int theme = Skin.mat();
                int col = color | 0xFF000000;
                int n = Math.max(1, slots);
                float slot = (r - l) / n;
                if (Ui.cloak) {
                    P.setShader(null); P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(1.4f * d); P.setColor(a(col, 0.6f));
                    RF.set(l + d, t + d, r - d, b - d);
                    cv.drawRect(RF, P);
                    return;
                }
                if (theme == Skin.TOBACCONIST) {
                    // pipe rack: a walnut rail with a cradle for each pipe and brass pegs between
                    RF.set(l + d, t + h * 0.18f, r - d, b - d);
                    cv.drawRoundRect(RF, 5 * d, 5 * d, material(ctx, "walnut.jpg", 0, 0.75f));
                    P.setShader(null);
                    P.setStyle(Paint.Style.FILL);
                    P.setColor(a(0xFF000000, 0.35f));
                    cv.drawRect(l + d, b - 7 * d, r - d, b - d, P);
                    P.setShader(brass(t + h * 0.18f, t + h * 0.18f + 3.2f * d, col));
                    cv.drawRect(l + 4 * d, t + h * 0.18f, r - 4 * d, t + h * 0.18f + 3.2f * d, P);
                    P.setShader(null);
                    for (int i = 0; i < n; i++) {
                        float cx = l + slot * (i + 0.5f);
                        P.setColor(a(0xFF000000, 0.55f));
                        RF.set(cx - Math.min(slot * 0.34f, 26 * d), t + h * 0.18f - 2 * d, cx + Math.min(slot * 0.34f, 26 * d), t + h * 0.18f + 15 * d);
                        cv.drawArc(RF, 0, 180, true, P);
                        if (i > 0) screw(cv, l + slot * i, t + h * 0.62f, 2.4f * d, d);
                    }
                    P.setStyle(Paint.Style.STROKE);
                    P.setStrokeWidth(Math.max(1, d));
                    P.setColor(a(0xFF000000, 0.7f));
                    RF.set(l + d, t + h * 0.18f, r - d, b - d);
                    cv.drawRoundRect(RF, 5 * d, 5 * d, P);
                } else if (theme == Skin.LOUNGE) {
                    Path p = stepped(path, l + d, t + d, r - d, b - d, Math.min(h * 0.22f, 8 * d), 2, true, true);
                    P.setStyle(Paint.Style.FILL);
                    P.setShader(new LinearGradient(0, t, 0, b, new int[]{LACQ_HI, LACQ, 0xFF070403}, null, Shader.TileMode.CLAMP));
                    cv.drawPath(p, P);
                    P.setShader(null);
                    P.setStyle(Paint.Style.STROKE);
                    P.setStrokeWidth(1.6f * d);
                    P.setColor(col);
                    cv.drawPath(p, P);
                    P.setStrokeWidth(Math.max(1, 0.7f * d));
                    P.setColor(a(col, 0.55f));
                    cv.drawPath(stepped(new Path(), l + 5 * d, t + 5 * d, r - 5 * d, b - 5 * d, Math.min(h * 0.18f, 6 * d), 2, true, true), P);
                    for (int i = 1; i < n; i++) {
                        float x = l + slot * i;
                        P.setStrokeWidth(Math.max(1, 0.9f * d));
                        P.setColor(a(col, 0.6f));
                        cv.drawLine(x - 1.5f * d, t + 12 * d, x - 1.5f * d, b - 12 * d, P);
                        cv.drawLine(x + 1.5f * d, t + 12 * d, x + 1.5f * d, b - 12 * d, P);
                    }
                } else {
                    // leather strap with saddle stitching and rivets between the slots
                    RF.set(l + d, t + d, r - d, b - d);
                    float rr = Math.min(h * 0.3f, 12 * d);
                    P.setShader(null);
                    P.setStyle(Paint.Style.FILL);
                    P.setColor(a(0xFF000000, 0.45f));
                    RF.offset(0, 1.6f * d);
                    cv.drawRoundRect(RF, rr, rr, P);
                    RF.offset(0, -1.6f * d);
                    cv.drawRoundRect(RF, rr, rr, material(ctx, "leather.jpg", 0xFFB08A70, 1f));
                    path.reset();
                    RF.set(l + 5 * d, t + 5 * d, r - 5 * d, b - 5 * d);
                    path.addRoundRect(RF, rr * 0.7f, rr * 0.7f, Path.Direction.CW);
                    stitches(cv, path, col, d, 0.85f);
                    for (int i = 1; i < n; i++) rivet(cv, l + slot * i, (t + b) / 2f, 2.6f * d, BRASS);
                }
            }
        };
    }

    // ---- frame (ConsoleFrame) pieces in elbow mode ------------------------------------------------

    /** Draw an console-geometry corner+bar (already built as a path by the frame) in the pipe material. */
    static void frameCorner(Canvas cv, Context c, Path elbow, int color, float d, float sidebarW, float barH, float barEnd) {
        int theme = Skin.mat();
        if (Ui.cloak) {
            P.setShader(null); P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(1.5f * d); P.setColor(a(color, 0.7f));
            cv.drawPath(elbow, P);
            return;
        }
        if (theme == Skin.TOBACCONIST) {
            cv.drawPath(elbow, material(c, "walnut.jpg", 0, 0.7f));
            P.setShader(null);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(Math.max(1, 1.2f * d));
            P.setColor(a(0xFF000000, 0.7f));
            cv.drawPath(elbow, P);
            // brass inlay line along the bar and down the sidebar
            P.setStrokeWidth(1.6f * d);
            P.setColor(color);
            float y = barH * 0.5f, x = sidebarW * 0.5f;
            if (barEnd > sidebarW + 8 * d) cv.drawLine(sidebarW + barH * 0.9f, y, barEnd - 5 * d, y, P);
            cv.drawLine(x, barH + sidebarW * 0.4f, x, barH * 2.4f + sidebarW * 0.4f, P);
            RF.set(x, y, x + sidebarW + barH * 0.8f - x, y + (sidebarW + barH * 0.8f - x));
            TMP.reset();
            TMP.moveTo(x, barH + sidebarW * 0.4f);
            TMP.quadTo(x, y, sidebarW + barH * 0.9f, y);
            cv.drawPath(TMP, P);
            rivet(cv, x, y + 1 * d, Math.min(4.5f * d, barH * 0.32f), color);
        } else if (theme == Skin.LOUNGE) {
            P.setShader(new LinearGradient(0, 0, 0, barH * 3, new int[]{LACQ_HI, LACQ}, null, Shader.TileMode.CLAMP));
            P.setStyle(Paint.Style.FILL);
            cv.drawPath(elbow, P);
            P.setShader(null);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(1.6f * d);
            P.setColor(color);
            cv.drawPath(elbow, P);
            // stepped echo inside the corner
            P.setStrokeWidth(Math.max(1, 0.8f * d));
            P.setColor(a(color, 0.6f));
            float s = Math.min(sidebarW, barH * 2.2f) * 0.32f;
            float x0 = 4 * d, y0 = 4 * d;
            TMP.reset();
            TMP.moveTo(x0, y0 + 3 * s);
            TMP.lineTo(x0, y0 + 2 * s); TMP.lineTo(x0 + s, y0 + 2 * s); TMP.lineTo(x0 + s, y0 + s);
            TMP.lineTo(x0 + 2 * s, y0 + s); TMP.lineTo(x0 + 2 * s, y0); TMP.lineTo(x0 + 3 * s, y0);
            cv.drawPath(TMP, P);
        } else {
            cv.drawPath(elbow, material(c, "leather.jpg", 0xFFC09A80, 1f));
            P.setShader(null);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(Math.max(1, d));
            P.setColor(a(0xFF000000, 0.6f));
            cv.drawPath(elbow, P);
            // saddle stitching along the bar and the sidebar
            TMP.reset();
            float y = barH * 0.5f, x = sidebarW * 0.5f;
            TMP.moveTo(x, barH * 2.6f + sidebarW * 0.3f);
            TMP.quadTo(x, y, x + sidebarW * 0.55f, y);
            if (barEnd > sidebarW + 10 * d) TMP.lineTo(barEnd - 6 * d, y);
            stitches(cv, TMP, color, d, 0.9f);
        }
    }

    /** One segment block next to a frame bar: brass rivet, gold chevron or wax seal. */
    static void frameBlock(Canvas cv, float right, float top, float bottom, float w, int color, float d, boolean pressed) {
        float cx = right - w / 2f, cy = (top + bottom) / 2f, h = bottom - top;
        int theme = Skin.mat();
        int col = color | 0xFF000000;
        if (pressed) col = mix(col, 0xFFFFFFFF, 0.3f);
        if (Ui.cloak) {
            P.setShader(null); P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(1.2f * d); P.setColor(a(col, 0.7f));
            cv.drawCircle(cx, cy, Math.min(h, w) * 0.4f, P);
            return;
        }
        if (theme == Skin.TOBACCONIST) {
            rivet(cv, cx, cy, Math.max(3 * d, Math.min(h * 0.46f, w * 0.3f)), col);
        } else if (theme == Skin.LOUNGE) {
            P.setShader(null);
            P.setStyle(Paint.Style.FILL);
            P.setColor(col);
            float s = Math.min(h * 0.55f, w * 0.32f);
            TMP.reset();
            TMP.moveTo(cx - s * 1.2f, cy); TMP.lineTo(cx, cy - s); TMP.lineTo(cx + s * 1.2f, cy); TMP.lineTo(cx, cy + s); TMP.close();
            cv.drawPath(TMP, P);
            P.setColor(LACQ);
            float k = s * 0.45f;
            TMP.reset();
            TMP.moveTo(cx - k * 1.2f, cy); TMP.lineTo(cx, cy - k); TMP.lineTo(cx + k * 1.2f, cy); TMP.lineTo(cx, cy + k); TMP.close();
            cv.drawPath(TMP, P);
        } else {
            seal(cv, cx, cy, Math.max(3.5f * d, Math.min(h * 0.55f, w * 0.34f)), mix(col, RED_INK, 0.55f), (int) right);
        }
    }

    // ---- plaque frames (home screen) ------------------------------------------------------------

    /**
     * A whole framed panel for the home screen: Tobacconist cabinet / leather inset, Ember Lounge
     * gilt rules with stepped corners, Blend Journal stitched leather. The title and footer sit in
     * plates centred on the top and bottom edges.
     */
    static void plaque(Canvas cv, Context c, float w, float h, float border, RectF title, RectF footer,
                       int colorTop, int colorBot, boolean header, float progress) {
        int theme = Skin.mat();
        float d = Ui.density(c);
        float am = Math.max(0f, Math.min(1f, progress));
        Path path = new Path();
        int ct = colorTop | 0xFF000000, cb = colorBot | 0xFF000000;
        float half = border / 2f;
        float top = title != null ? title.centerY() : half, bot = footer != null ? footer.centerY() : h - half;
        if (Ui.cloak) {
            P.setShader(null); P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(1.5f * d); P.setColor(a(ct, 0.6f * am));
            cv.drawRect(half, top, w - half, bot, P);
            plates(cv, c, title, footer, ct, cb, d, am);
            return;
        }
        int layer = cv.saveLayerAlpha(0, 0, w, h, Math.round(255 * am));
        if (theme == Skin.TOBACCONIST) {
            // field: oxblood leather (header) or a dark glaze over the walnut (cabinet)
            RF.set(border, top, w - border, bot);
            P.setStyle(Paint.Style.FILL);
            if (header) {
                P.setShader(new RadialGradient(w / 2f, (top + bot) / 2f, Math.max(w, bot - top) * 0.7f,
                        new int[]{OXBLOOD, mix(OXBLOOD, OXBLOOD_DK, 0.6f), OXBLOOD_DK}, new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
                cv.drawRect(RF, P);
                Paint grain = material(c, "leather.jpg", 0, 1f);
                if (multiply(grain, 60)) cv.drawRect(RF, grain);
                // gold tooling
                P.setShader(null);
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(Math.max(1, 0.9f * d));
                P.setColor(a(GOLD, 0.75f));
                RF.inset(6 * d, 6 * d);
                cv.drawRect(RF, P);
                RF.inset(3 * d, 3 * d);
                P.setColor(a(GOLD, 0.4f));
                cv.drawRect(RF, P);
                fleurons(cv, RF, a(GOLD, 0.75f), d);
            } else {
                P.setShader(null);
                P.setColor(a(0xFF000000, 0.38f));
                cv.drawRect(RF, P);
            }
            // walnut moulding
            Paint wood = material(c, "walnut.jpg", 0, 0.75f);
            P.setShader(null);
            path.reset();
            path.setFillType(Path.FillType.EVEN_ODD);
            path.addRect(0, top - half, w, bot + half, Path.Direction.CW);
            path.addRect(border, top + half, w - border, bot - half, Path.Direction.CW);
            cv.drawPath(path, wood);
            path.setFillType(Path.FillType.WINDING);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(Math.max(1, d));
            P.setColor(a(0xFFFFE0B0, 0.18f));
            cv.drawRect(d, top - half + d, w - d, bot + half - d, P);
            P.setColor(a(0xFF000000, 0.8f));
            cv.drawRect(0.5f, top - half, w - 0.5f, bot + half, P);
            // brass bead on the inner edge
            P.setStrokeWidth(1.8f * d);
            P.setColor(ct);
            cv.drawRect(border - d, top + half - d, w - border + d, bot - half + d, P);
            // corner brackets
            bracket(cv, 0, top - half, border * 2.6f, 1, 1, ct, d);
            bracket(cv, w, top - half, border * 2.6f, -1, 1, ct, d);
            bracket(cv, 0, bot + half, border * 2.6f, 1, -1, cb, d);
            bracket(cv, w, bot + half, border * 2.6f, -1, -1, cb, d);
        } else if (theme == Skin.LOUNGE) {
            float s = border * 1.6f;
            Path outer = stepped(path, half, top, w - half, bot, s, 3, true, true);
            P.setStyle(Paint.Style.FILL);
            P.setShader(new LinearGradient(0, top, 0, bot, header ? new int[]{0xEE150D0B, 0xEE0A0605} : new int[]{0x99100A08, 0x99080504},
                    null, Shader.TileMode.CLAMP));
            cv.drawPath(outer, P);
            P.setShader(null);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(2f * d);
            P.setColor(ct);
            cv.drawPath(outer, P);
            float k = 5 * d;
            P.setStrokeWidth(Math.max(1, 0.8f * d));
            P.setColor(a(ct, 0.6f));
            cv.drawPath(stepped(new Path(), half + k, top + k, w - half - k, bot - k, Math.max(2 * d, s - k * 0.7f), 3, true, true), P);
            // gilt pinstripes down both sides
            P.setColor(a(cb, 0.35f));
            for (int i = 1; i <= 2; i++) {
                float x = half + k + i * 3.2f * d;
                cv.drawLine(x, top + s + k, x, bot - s - k, P);
                cv.drawLine(w - x, top + s + k, w - x, bot - s - k, P);
            }
        } else {
            float rr = 16 * d;
            RF.set(half, top, w - half, bot);
            P.setShader(null);
            P.setStyle(Paint.Style.FILL);
            P.setColor(a(0xFF000000, 0.5f));
            RF.offset(0, 2 * d);
            cv.drawRoundRect(RF, rr, rr, P);
            RF.offset(0, -2 * d);
            Paint lea = material(c, "leather.jpg", header ? 0xFFD8B49A : 0xFF9C8070, 1f);
            if (!header) lea.setAlpha(170);
            cv.drawRoundRect(RF, rr, rr, lea);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(Math.max(1, 1.2f * d));
            P.setColor(a(0xFF000000, 0.7f));
            cv.drawRoundRect(RF, rr, rr, P);
            path.reset();
            RF.inset(6 * d, 6 * d);
            path.addRoundRect(RF, rr - 5 * d, rr - 5 * d, Path.Direction.CW);
            stitches(cv, path, ct, d, 0.9f);
            // corner rivets
            float ri = 3 * d;
            rivet(cv, half + 13 * d, top + 13 * d, ri, BRASS);
            rivet(cv, w - half - 13 * d, top + 13 * d, ri, BRASS);
            rivet(cv, half + 13 * d, bot - 13 * d, ri, BRASS);
            rivet(cv, w - half - 13 * d, bot - 13 * d, ri, BRASS);
        }
        plates(cv, c, title, footer, ct, cb, d, 1f);
        cv.restoreToCount(layer);
    }

    /** The plates behind the title and footer. */
    private static void plates(Canvas cv, Context c, RectF title, RectF footer, int ct, int cb, float d, float am) {
        int theme = Skin.mat();
        for (int i = 0; i < 2; i++) {
            RectF r = i == 0 ? title : footer;
            if (r == null || r.width() < 4) continue;
            int col = i == 0 ? ct : cb;
            RectF q = new RectF(r.left - 10 * d, r.top - 3 * d, r.right + 10 * d, r.bottom + 3 * d);
            if (Ui.cloak) {
                P.setShader(null); P.setStyle(Paint.Style.FILL); P.setColor(0xFF000000); cv.drawRect(q, P);
                P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(1.3f * d); P.setColor(a(col, 0.6f * am)); cv.drawRect(q, P);
                continue;
            }
            if (theme == Skin.TOBACCONIST) {
                P.setStyle(Paint.Style.FILL);
                P.setShader(null);
                P.setColor(a(0xFF000000, 0.5f));
                RF.set(q); RF.offset(0, 1.5f * d);
                cv.drawRoundRect(RF, q.height() / 2f, q.height() / 2f, P);
                P.setShader(brass(q.top, q.bottom, col));
                cv.drawRoundRect(q, q.height() / 2f, q.height() / 2f, P);
                P.setShader(null);
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(Math.max(1, 0.9f * d));
                P.setColor(BRASS_LO);
                cv.drawRoundRect(q, q.height() / 2f, q.height() / 2f, P);
                RF.set(q); RF.inset(3 * d, 3 * d);
                P.setColor(a(BRASS_LO, 0.7f));
                cv.drawRoundRect(RF, RF.height() / 2f, RF.height() / 2f, P);
                float sr = Math.min(2.2f * d, q.height() * 0.12f);
                screw(cv, q.left + q.height() * 0.42f, q.centerY(), sr, d);
                screw(cv, q.right - q.height() * 0.42f, q.centerY(), sr, d);
            } else if (theme == Skin.LOUNGE) {
                Path p = stepped(new Path(), q.left, q.top, q.right, q.bottom, Math.min(q.height() * 0.3f, 8 * d), 2, true, true);
                P.setStyle(Paint.Style.FILL);
                P.setShader(new LinearGradient(0, q.top, 0, q.bottom, new int[]{LACQ_HI, LACQ}, null, Shader.TileMode.CLAMP));
                cv.drawPath(p, P);
                P.setShader(null);
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(1.6f * d);
                P.setColor(col);
                cv.drawPath(p, P);
                // chevrons either side
                P.setStyle(Paint.Style.FILL);
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    float x = sgn < 0 ? q.left - 6 * d : q.right + 6 * d, y = q.centerY(), s = Math.min(5 * d, q.height() * 0.22f);
                    for (int k = 0; k < 2; k++) {
                        float xx = x + sgn * k * 6 * d;
                        TMP.reset();
                        TMP.moveTo(xx, y - s); TMP.lineTo(xx + sgn * s, y); TMP.lineTo(xx, y + s);
                        TMP.lineTo(xx + sgn * s * 0.45f, y); TMP.close();
                        P.setColor(a(col, k == 0 ? 0.9f : 0.5f));
                        cv.drawPath(TMP, P);
                    }
                }
            } else {
                Path p = tag(new Path(), q.left, q.top, q.right, q.bottom, Math.min(q.height() * 0.3f, 8 * d), 4 * d);
                P.setStyle(Paint.Style.FILL);
                P.setShader(null);
                P.setColor(a(0xFF000000, 0.5f));
                cv.save(); cv.translate(d, 1.6f * d); cv.drawPath(p, P); cv.restore();
                cv.drawPath(p, material(c, "paper_grain.jpg", col, 1f));
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(Math.max(1, 0.8f * d));
                P.setColor(a(0xFF3A2010, 0.7f));
                cv.drawPath(p, P);
                P.setColor(a(RED_INK, 0.75f));
                cv.drawLine(q.left + 10 * d, q.bottom - 4 * d, q.right - 8 * d, q.bottom - 4 * d, P);
            }
        }
    }

    private static void bracket(Canvas cv, float x, float y, float len, int sx, int sy, int color, float d) {
        // an L of brass with a small scroll at its inner end
        P.setStyle(Paint.Style.FILL);
        P.setShader(brass(y - (sy < 0 ? len : 0), y + (sy > 0 ? len : 0), color));
        float th = 5 * d;
        RF.set(Math.min(x, x + sx * len), Math.min(y, y + sy * th), Math.max(x, x + sx * len), Math.max(y, y + sy * th));
        cv.drawRect(RF, P);
        RF.set(Math.min(x, x + sx * th), Math.min(y, y + sy * len), Math.max(x, x + sx * th), Math.max(y, y + sy * len));
        cv.drawRect(RF, P);
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1.8f * d);
        P.setColor(color);
        float cx = x + sx * (th + 4 * d), cy = y + sy * (th + 4 * d);
        RF.set(cx - 3.5f * d, cy - 3.5f * d, cx + 3.5f * d, cy + 3.5f * d);
        cv.drawArc(RF, 0, 300, false, P);
        screw(cv, x + sx * th * 0.5f, y + sy * th * 0.5f, 1.6f * d, d);
    }

    /** Gold-tooled corner leaves inside a rectangle. */
    private static void fleurons(Canvas cv, RectF r, int color, float d) {
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        float s = 7 * d;
        float[][] cs = {{r.left, r.top, 1, 1}, {r.right, r.top, -1, 1}, {r.left, r.bottom, 1, -1}, {r.right, r.bottom, -1, -1}};
        for (float[] k : cs) {
            float x = k[0] + k[2] * 3 * d, y = k[1] + k[3] * 3 * d;
            TMP.reset();
            TMP.moveTo(x, y);
            TMP.quadTo(x + k[2] * s, y, x + k[2] * s, y + k[3] * s * 0.35f);
            TMP.quadTo(x + k[2] * s * 0.4f, y + k[3] * s * 0.4f, x, y);
            TMP.moveTo(x, y);
            TMP.quadTo(x, y + k[3] * s, x + k[2] * s * 0.35f, y + k[3] * s);
            TMP.quadTo(x + k[2] * s * 0.4f, y + k[3] * s * 0.4f, x, y);
            cv.drawPath(TMP, P);
            cv.drawCircle(x + k[2] * s * 0.6f, y + k[3] * s * 0.6f, 1.2f * d, P);
        }
    }

    // ---- header ornament behind the clock -------------------------------------------------------

    /** The ornament drawn behind the home clock; honours {@code setAlpha} so the frame reveal can fade it in. */
    static Drawable ornament(Context c, final Palette pal) {
        return new Base(c) {
            float am = 1f;
            @Override public void setAlpha(int alpha) { am = alpha / 255f; }
            @Override public void draw(Canvas cv) {
                Rect b = getBounds();
                float w = b.width(), h = b.height();
                if (w < 10 || h < 10) return;
                float cx = b.exactCenterX(), cy = b.exactCenterY();
                float dim = (Ui.cloak ? 0.45f : 1f) * am;
                int theme = Skin.mat();
                P.setShader(null);
                P.setPathEffect(null);
                if (theme == Skin.TOBACCONIST) {
                    // engraved scrollwork either side of the clock and a ruled band above and below
                    P.setStyle(Paint.Style.STROKE);
                    P.setStrokeCap(Paint.Cap.ROUND);
                    int col = pal.c(0);
                    for (int s = -1; s <= 1; s += 2) {
                        float x0 = cx + s * w * 0.30f, x1 = cx + s * w * 0.47f;
                        P.setStrokeWidth(1.4f * d);
                        P.setColor(a(col, 0.75f * dim));
                        TMP.reset();
                        TMP.moveTo(x0, cy);
                        TMP.cubicTo(x0 + s * w * 0.06f, cy - h * 0.34f, x1, cy - h * 0.1f, x1 - s * w * 0.03f, cy + h * 0.06f);
                        TMP.cubicTo(x1 - s * w * 0.05f, cy + h * 0.16f, x1 - s * w * 0.08f, cy - h * 0.02f, x1 - s * w * 0.06f, cy - h * 0.02f);
                        cv.drawPath(TMP, P);
                        TMP.reset();
                        TMP.moveTo(x0, cy);
                        TMP.cubicTo(x0 + s * w * 0.06f, cy + h * 0.34f, x1, cy + h * 0.1f, x1 - s * w * 0.03f, cy - h * 0.06f);
                        P.setColor(a(col, 0.5f * dim));
                        cv.drawPath(TMP, P);
                        P.setStyle(Paint.Style.FILL);
                        P.setColor(a(col, 0.8f * dim));
                        cv.drawCircle(x0, cy, 2.2f * d, P);
                        P.setStyle(Paint.Style.STROKE);
                    }
                    P.setStrokeWidth(Math.max(1, 0.9f * d));
                    P.setColor(a(col, 0.55f * dim));
                    float y0 = b.top + 3 * d, y1 = b.bottom - 3 * d;
                    cv.drawLine(cx - w * 0.28f, y0, cx + w * 0.28f, y0, P);
                    cv.drawLine(cx - w * 0.28f, y1, cx + w * 0.28f, y1, P);
                    P.setStyle(Paint.Style.FILL);
                    for (float y : new float[]{y0, y1}) {
                        TMP.reset();
                        TMP.moveTo(cx - 4 * d, y); TMP.lineTo(cx, y - 3 * d); TMP.lineTo(cx + 4 * d, y); TMP.lineTo(cx, y + 3 * d); TMP.close();
                        cv.drawPath(TMP, P);
                    }
                    P.setStrokeCap(Paint.Cap.BUTT);
                } else if (theme == Skin.LOUNGE) {
                    // sunburst rising behind the clock
                    float oy = b.bottom - h * 0.06f, rad = Math.min(w * 0.5f, h * 1.05f);
                    P.setStyle(Paint.Style.FILL);
                    P.setShader(new RadialGradient(cx, oy, rad, new int[]{a(EMBER, 0.42f * dim), a(0xFFB3262A, 0.16f * dim), 0}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                    RF.set(cx - rad, oy - rad, cx + rad, oy + rad);
                    cv.drawArc(RF, 180, 180, true, P);
                    P.setShader(null);
                    P.setStyle(Paint.Style.STROKE);
                    int n = 24;
                    for (int i = 0; i <= n; i++) {
                        double ang = Math.PI + Math.PI * i / n;
                        float r0 = rad * 0.22f, r1 = rad * (i % 2 == 0 ? 1f : 0.82f);
                        P.setStrokeWidth(Math.max(1, (i % 2 == 0 ? 1.1f : 0.7f) * d));
                        P.setColor(a(pal.c(0), (i % 2 == 0 ? 0.42f : 0.24f) * dim));
                        cv.drawLine(cx + (float) Math.cos(ang) * r0, oy + (float) Math.sin(ang) * r0, cx + (float) Math.cos(ang) * r1, oy + (float) Math.sin(ang) * r1, P);
                    }
                    for (float k : new float[]{0.22f, 0.25f, 1f}) {
                        P.setStrokeWidth(Math.max(1, 1.1f * d));
                        P.setColor(a(pal.c(0), 0.5f * dim));
                        RF.set(cx - rad * k, oy - rad * k, cx + rad * k, oy + rad * k);
                        cv.drawArc(RF, 180, 180, false, P);
                    }
                } else {
                    // a round tin lid: oxblood enamel, cream rings, beaded rim
                    float rx = Math.min(w * 0.46f, h * 1.45f), ry = h * 0.47f;
                    RF.set(cx - rx, cy - ry, cx + rx, cy + ry);
                    P.setStyle(Paint.Style.FILL);
                    P.setColor(a(0xFF000000, 0.5f * dim));
                    RF.offset(0, 2 * d); cv.drawOval(RF, P); RF.offset(0, -2 * d);
                    P.setShader(new RadialGradient(cx - rx * 0.25f, cy - ry * 0.35f, rx * 1.3f, new int[]{a(0xFF9A2A20, dim), a(0xFF6A1712, dim), a(0xFF3A0C09, dim)}, null, Shader.TileMode.CLAMP));
                    cv.drawOval(RF, P);
                    P.setShader(null);
                    P.setStyle(Paint.Style.STROKE);
                    P.setStrokeWidth(2.4f * d);
                    P.setColor(a(BRASS, dim));
                    cv.drawOval(RF, P);
                    RF.inset(6 * d, 5 * d);
                    P.setStrokeWidth(Math.max(1, 1.1f * d));
                    P.setColor(a(0xFFEFDDB4, 0.85f * dim));
                    cv.drawOval(RF, P);
                    RF.inset(3.5f * d, 3 * d);
                    P.setStrokeWidth(Math.max(1, 0.7f * d));
                    P.setColor(a(0xFFEFDDB4, 0.5f * dim));
                    cv.drawOval(RF, P);
                    // beaded rim
                    P.setStyle(Paint.Style.FILL);
                    P.setColor(a(BRASS_HI, 0.8f * dim));
                    int beads = 36;
                    for (int i = 0; i < beads; i++) {
                        double ang = Math.PI * 2 * i / beads;
                        cv.drawCircle(cx + (float) Math.cos(ang) * (rx - 3 * d), cy + (float) Math.sin(ang) * (ry - 2.6f * d), 0.9f * d, P);
                    }
                }
            }
        };
    }

    // ---- backdrop, dialogs, card brackets -------------------------------------------------------

    /**
     * The full-screen backdrop: the painted scene's inner view for the three scene interfaces, otherwise
     * the family's backdrop texture, centre-cropped to the bounds at any aspect ratio.
     */
    static Drawable backdrop(Context c) {
        final int theme = Skin.mat();
        final String name = Skin.faction == Skin.BRIAR ? "scenes/ember_inner.jpg" : Skin.faction == Skin.LEDGER ? "scenes/ledger_inner.jpg" : Skin.faction == Skin.MIDNIGHT ? "scenes/midnight_inner.jpg"
                : theme == Skin.TOBACCONIST ? "tob_backdrop.jpg" : theme == Skin.LOUNGE ? "lounge_backdrop.jpg" : "journal_backdrop.jpg";
        return new Base(c) {
            final Rect src = new Rect();
            @Override public void draw(Canvas cv) {
                Rect b = getBounds();
                cv.drawColor(Skin.bg());
                Bitmap bm = tex(ctx, name);
                if (bm == null) return;
                float[] crop = centerCropSource(bm.getWidth(), bm.getHeight(), b.width(), b.height());
                src.set(Math.round(crop[0]), Math.round(crop[1]), Math.round(crop[2]), Math.round(crop[3]));
                P.setShader(null);
                P.setAlpha(Ui.cloak ? 90 : 255);
                cv.drawBitmap(bm, src, b, P);
                P.setAlpha(255);
            }
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }

    /** Dialog window background in the current material family (used by ConsoleDialog). */
    static Drawable dialog(Context c, final Palette pal) {
        return new Base(c) {
            @Override public void draw(Canvas cv) {
                Rect bb = getBounds();
                float l = bb.left + d, t = bb.top + d, r = bb.right - d, b = bb.bottom - d;
                int theme = Skin.mat();
                int col = pal.c(0);
                if (theme == Skin.TOBACCONIST) {
                    RF.set(l, t, r, b);
                    cv.drawRoundRect(RF, 6 * d, 6 * d, material(ctx, "walnut.jpg", 0xFF8A7A70, 0.7f));
                    P.setShader(null);
                    P.setStyle(Paint.Style.FILL);
                    P.setColor(a(0xFF000000, 0.35f));
                    RF.inset(9 * d, 9 * d);
                    cv.drawRect(RF, P);
                    P.setStyle(Paint.Style.STROKE);
                    P.setStrokeWidth(1.8f * d);
                    P.setColor(col);
                    cv.drawRect(RF, P);
                    P.setStrokeWidth(Math.max(1, d));
                    P.setColor(a(0xFF000000, 0.8f));
                    RF.set(l, t, r, b);
                    cv.drawRoundRect(RF, 6 * d, 6 * d, P);
                    screw(cv, l + 5 * d, t + 5 * d, 2 * d, d);
                    screw(cv, r - 5 * d, t + 5 * d, 2 * d, d);
                    screw(cv, l + 5 * d, b - 5 * d, 2 * d, d);
                    screw(cv, r - 5 * d, b - 5 * d, 2 * d, d);
                } else if (theme == Skin.LOUNGE) {
                    Path p = stepped(path, l, t, r, b, 14 * d, 3, true, true);
                    P.setStyle(Paint.Style.FILL);
                    P.setShader(new LinearGradient(0, t, 0, b, new int[]{0xFF1A110E, LACQ, 0xFF060403}, null, Shader.TileMode.CLAMP));
                    cv.drawPath(p, P);
                    P.setShader(null);
                    P.setStyle(Paint.Style.STROKE);
                    P.setStrokeWidth(2 * d);
                    P.setColor(col);
                    cv.drawPath(p, P);
                    P.setStrokeWidth(Math.max(1, 0.8f * d));
                    P.setColor(a(col, 0.55f));
                    cv.drawPath(stepped(new Path(), l + 5 * d, t + 5 * d, r - 5 * d, b - 5 * d, 10 * d, 3, true, true), P);
                } else {
                    RF.set(l, t, r, b);
                    cv.drawRoundRect(RF, 16 * d, 16 * d, material(ctx, "leather.jpg", 0xFFC8A890, 1f));
                    P.setShader(null);
                    P.setStyle(Paint.Style.STROKE);
                    P.setStrokeWidth(Math.max(1, 1.2f * d));
                    P.setColor(a(0xFF000000, 0.75f));
                    cv.drawRoundRect(RF, 16 * d, 16 * d, P);
                    path.reset();
                    RF.inset(6 * d, 6 * d);
                    path.addRoundRect(RF, 11 * d, 11 * d, Path.Direction.CW);
                    stitches(cv, path, STITCH, d, 0.9f);
                }
            }
        };
    }

    /** The bracket at the left of a dashboard panel. */
    static void cardBracket(Canvas cv, Context c, float w, float h, int color) {
        float d = Ui.density(c);
        int theme = Skin.mat();
        int col = color | 0xFF000000;
        float x = w * 0.45f;
        if (Ui.cloak) {
            P.setShader(null); P.setStyle(Paint.Style.STROKE); P.setStrokeWidth(1.4f * d); P.setColor(a(col, 0.6f));
            cv.drawLine(x, 4 * d, x, h - 4 * d, P);
            return;
        }
        if (theme == Skin.TOBACCONIST) {
            // a brass rod with turned finials
            P.setStyle(Paint.Style.FILL);
            P.setShader(new LinearGradient(x - 2.5f * d, 0, x + 2.5f * d, 0, new int[]{BRASS_LO, mix(col, BRASS_HI, 0.5f), BRASS_LO}, null, Shader.TileMode.CLAMP));
            cv.drawRect(x - 2.2f * d, 8 * d, x + 2.2f * d, h - 8 * d, P);
            P.setShader(null);
            rivet(cv, x, 6 * d, 4.5f * d, col);
            rivet(cv, x, h - 6 * d, 4.5f * d, col);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(1.4f * d);
            P.setColor(col);
            cv.drawLine(x, 6 * d, w + 14 * d, 6 * d, P);
            cv.drawLine(x, h - 6 * d, w + 14 * d, h - 6 * d, P);
        } else if (theme == Skin.LOUNGE) {
            P.setShader(null);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(1.4f * d);
            P.setColor(col);
            float s = 5 * d;
            TMP.reset();
            TMP.moveTo(w + 14 * d, d);
            TMP.lineTo(x + 2 * s, d); TMP.lineTo(x + 2 * s, d + s); TMP.lineTo(x + s, d + s); TMP.lineTo(x + s, d + 2 * s); TMP.lineTo(x, d + 2 * s);
            TMP.lineTo(x, h - d - 2 * s); TMP.lineTo(x + s, h - d - 2 * s); TMP.lineTo(x + s, h - d - s); TMP.lineTo(x + 2 * s, h - d - s);
            TMP.lineTo(x + 2 * s, h - d); TMP.lineTo(w + 14 * d, h - d);
            cv.drawPath(TMP, P);
            P.setStrokeWidth(Math.max(1, 0.7f * d));
            P.setColor(a(col, 0.5f));
            cv.drawLine(x + 3.5f * d, d + 2 * s + 3 * d, x + 3.5f * d, h - d - 2 * s - 3 * d, P);
        } else {
            RF.set(x - 4 * d, 2 * d, x + 4 * d, h - 2 * d);
            cv.drawRoundRect(RF, 4 * d, 4 * d, material(c, "leather.jpg", 0xFFC09A80, 1f));
            TMP.reset();
            TMP.moveTo(x, 8 * d);
            TMP.lineTo(x, h - 8 * d);
            stitches(cv, TMP, STITCH, d, 0.8f);
            seal(cv, x, 9 * d, 6 * d, mix(col, RED_INK, 0.6f), 3);
        }
    }

    /** Framed app icon backgrounds (IconFactory): brass roundel, gilt octagon or kraft square with a red ring. */
    static void iconPlate(Canvas cv, RectF r, int color, int fill, float min) {
        int theme = Skin.mat();
        float st = Math.max(2, 0.035f * min);
        P.setShader(null);
        P.setPathEffect(null);
        if (theme == Skin.TOBACCONIST) {
            float cx = r.centerX(), cy = r.centerY(), rad = r.width() / 2f;
            P.setStyle(Paint.Style.FILL);
            P.setShader(new RadialGradient(cx - rad * 0.3f, cy - rad * 0.35f, rad * 1.5f, new int[]{mix(color, BRASS_HI, 0.5f), color, BRASS_LO}, null, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, rad, P);
            P.setShader(null);
            P.setColor(mix(fill, 0xFF1A0E08, 0.6f));
            cv.drawCircle(cx, cy, rad * 0.8f, P);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(st);
            P.setColor(BRASS_LO);
            cv.drawCircle(cx, cy, rad, P);
            cv.drawCircle(cx, cy, rad * 0.8f, P);
        } else if (theme == Skin.LOUNGE) {
            float k = r.width() * 0.29f;
            TMP.reset();
            TMP.moveTo(r.left + k, r.top); TMP.lineTo(r.right - k, r.top); TMP.lineTo(r.right, r.top + k); TMP.lineTo(r.right, r.bottom - k);
            TMP.lineTo(r.right - k, r.bottom); TMP.lineTo(r.left + k, r.bottom); TMP.lineTo(r.left, r.bottom - k); TMP.lineTo(r.left, r.top + k); TMP.close();
            P.setStyle(Paint.Style.FILL);
            P.setColor(LACQ);
            cv.drawPath(TMP, P);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(st);
            P.setColor(color);
            cv.drawPath(TMP, P);
        } else {
            P.setStyle(Paint.Style.FILL);
            P.setColor(mix(0xFFD6B47E, color, 0.25f));
            cv.drawRoundRect(r, min * 0.14f, min * 0.14f, P);
            P.setStyle(Paint.Style.STROKE);
            P.setStrokeWidth(st * 0.8f);
            P.setColor(0xFF6E4524);
            cv.drawRoundRect(r, min * 0.14f, min * 0.14f, P);
            P.setColor(a(RED_INK, 0.85f));
            P.setStrokeWidth(st);
            cv.drawCircle(r.centerX(), r.centerY(), r.width() * 0.4f, P);
        }
    }

    /**
     * The source rectangle {left, top, right, bottom} of a {@code srcW x srcH} image that, scaled up
     * uniformly, exactly covers a {@code dstW x dstH} area (centre-crop: the excess is trimmed equally
     * from both sides of the longer dimension).
     */
    static float[] centerCropSource(float srcW, float srcH, float dstW, float dstH) {
        float scale = Math.max(dstW / srcW, dstH / srcH);
        float visibleW = dstW / scale, visibleH = dstH / scale;
        return new float[]{(srcW - visibleW) / 2f, (srcH - visibleH) / 2f, (srcW + visibleW) / 2f, (srcH + visibleH) / 2f};
    }

    // ---- base drawable ------------------------------------------------------------------------

    /**
     * Common base for the pipe drawables: holds the application context (for textures), the pixels per
     * zoomed dp ({@link Ui#density}) and a scratch path. Alpha and colour filters are ignored; the
     * drawables are always translucent.
     */
    abstract static class Base extends Drawable {
        /** Application context, safe to hold in long-lived drawables. */
        final Context ctx;
        /** Pixels per (zoomed) dp. */
        final float d;
        final Path path = new Path();
        Base(Context c) {
            this.ctx = c.getApplicationContext() != null ? c.getApplicationContext() : c;
            this.d = Ui.density(c);
        }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
