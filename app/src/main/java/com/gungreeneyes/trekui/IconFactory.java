package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds launcher icons in the selected style ({@link Prefs#iconStyle()}): console pictograms, app
 * icons inside a faction frame, the faction's own artwork badges, or the native icon.
 *
 * <p>Pictograms ("glyphs") exist for a fixed set of core apps ({@link #GLYPH_RULES}). Decoded
 * bitmaps are cached for the life of the process.
 */
final class IconFactory {
    /** Core apps that get an console pictogram. First installed package in each row wins, so glyphs stay unique. */
    static final String[][] GLYPH_RULES = {
        {"phone", "com.samsung.android.dialer", "com.google.android.dialer", "com.android.dialer"},
        {"messages", "com.samsung.android.messaging", "com.google.android.apps.messaging", "com.android.mms", "com.android.messaging"},
        {"browser", "com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.android.browser"},
        {"camera", "com.sec.android.app.camera", "com.google.android.GoogleCamera", "com.android.camera2", "com.android.camera"},
        {"settings", "com.android.settings"},
        {"calculator", "com.sec.android.app.popupcalculator", "com.google.android.calculator", "com.android.calculator2"},
        {"calendar", "com.samsung.android.calendar", "com.google.android.calendar", "com.android.calendar"},
        {"clock", "com.sec.android.app.clockpackage", "com.google.android.deskclock", "com.android.deskclock"},
        {"contacts", "com.samsung.android.app.contacts", "com.google.android.contacts", "com.android.contacts"},
        {"files", "com.sec.android.app.myfiles", "com.google.android.apps.nbu.files", "com.android.documentsui"},
        {"fitness", "com.sec.android.app.shealth", "com.google.android.apps.fitness"},
        {"mail", "com.google.android.gm", "com.samsung.android.email.provider", "com.android.email"},
        {"maps", "com.google.android.apps.maps"},
        {"music", "com.sec.android.app.music", "com.google.android.apps.youtube.music"},
        {"notes", "com.samsung.android.app.notes", "com.google.android.keep"},
        {"photos", "com.sec.android.gallery3d", "com.google.android.apps.photos", "com.android.gallery3d"},
        {"store", "com.android.vending", "com.sec.android.app.samsungapps"},
        {"video", "com.google.android.youtube", "com.samsung.android.video"},
        {"weather", "com.sec.android.daemonapp", "com.google.android.apps.weather"},
        {"radio", "com.sec.android.app.fm", "com.google.android.apps.podcasts"},
        {"search", "com.google.android.googlequicksearchbox"},
        {"system", "com.samsung.android.lool"},
        {"home", "com.samsung.android.oneconnect"},
        {"secure", "org.thoughtcrime.securesms"},
        {"officemail", "com.microsoft.office.outlook"},
        {"computer", "com.anthropic.claude"},
    };

    /** Frame and glyph geometry as fractions of the icon's side. */
    private static final float GLYPH_INSET = 0.22f, ICON_INSET = 0.17f;
    /** Size reported to views that ask for an intrinsic size (they normally set bounds). */
    private static final int INTRINSIC_PX = 192;
    /** Alpha of faction artwork while cloaked. */
    private static final int CLOAKED_ART_ALPHA = 150;
    /** Pictogram ink on a SOLID (filled) frame. */
    private static final int SOLID_MARK = 0xB3000000;

    private static final Map<String, Bitmap> glyphs = new HashMap<>();
    private static final Map<String, Bitmap> art = new HashMap<>();

    private IconFactory() {}

    /**
     * Pure rule matching: for each {@link #GLYPH_RULES} row, the first listed package that is
     * installed gets that row's glyph name.
     *
     * @param installed installed main-profile package names
     * @return package name → glyph name
     */
    static Map<String, String> matchGlyphs(Collection<String> installed) {
        Set<String> pkgs = installed instanceof Set ? (Set<String>) installed : new LinkedHashSet<>(installed);
        Map<String, String> out = new HashMap<>();
        for (String[] rule : GLYPH_RULES) {
            for (int i = 1; i < rule.length; i++) {
                if (pkgs.contains(rule[i])) {
                    out.put(rule[i], rule[0]);
                    break;
                }
            }
        }
        return out;
    }

    /** Assigns pictogram names to the main-profile apps that match a rule (work-profile apps keep their icons). */
    static void assignGlyphs(List<AppInfo> apps) {
        Map<String, AppInfo> byPkg = new HashMap<>();
        for (AppInfo a : apps) {
            a.glyph = null;
            a.glyphPinned = false;
            if (a.mainProfile && !byPkg.containsKey(a.pkg)) byPkg.put(a.pkg, a);
        }
        for (Map.Entry<String, String> e : matchGlyphs(byPkg.keySet()).entrySet()) {
            byPkg.get(e.getKey()).glyph = e.getValue();
        }
    }

    /**
     * {@link #assignGlyphs(List)} plus the user's per-app choices and, when {@code auto} is on,
     * keyword-matched defaults for every other main-profile app (LCA-T22).
     *
     * @param choices AppInfo.key → stored icon choice ({@link IconPacks#CHOICE_GLYPH} etc.); missing = automatic
     */
    static void assignGlyphs(List<AppInfo> apps, Map<String, String> choices, boolean auto) {
        assignGlyphs(apps);
        for (AppInfo a : apps) {
            String choice = choices.get(a.key);
            a.glyph = resolveGlyph(a.glyph, choice, auto && a.mainProfile, a.pkg, a.label, a.category);
            a.glyphPinned = IconPacks.choiceGlyph(choice) != null && a.glyph != null;
        }
    }

    /**
     * Pure: the pictogram an app ends up with. A pictogram choice wins (if the name exists); an
     * "own icon" or icon-pack choice means no pictogram; otherwise the core rule's glyph, else
     * (when {@code auto}) the keyword default from {@link Pictograms#defaultGlyph}.
     *
     * @param core the glyph from {@link #GLYPH_RULES}, or null
     */
    static String resolveGlyph(String core, String choice, boolean auto, String pkg, String label, int category) {
        String picked = IconPacks.choiceGlyph(choice);
        if (picked != null) return Pictograms.exists(picked) ? picked : core;
        if (IconPacks.CHOICE_APP.equals(choice) || IconPacks.choicePack(choice) != null) return null;
        if (core != null) return core;
        return auto ? Pictograms.defaultGlyph(pkg, label, category) : null;
    }

    /** Asset path of the pictogram {@code name}. */
    static String glyphPath(String name) {
        return "glyphs/" + name + ".png";
    }

    /** The pictogram bitmap for a glyph name, or null. Cached. */
    static synchronized Bitmap glyph(Context c, String name) {
        if (glyphs.containsKey(name)) return glyphs.get(name);
        Bitmap b = decodeAsset(c, glyphPath(name));
        glyphs.put(name, b);
        return b;
    }

    /** The faction artwork badge for a pictogram name (Imperial octagon, Collective module), or null. */
    static synchronized Bitmap factionArt(Context c, String name) {
        if (Skin.command() || name == null) return null;
        String path = Skin.artFolder() + name + ".png";
        if (art.containsKey(path)) return art.get(path);
        Bitmap b = decodeAsset(c, path);
        art.put(path, b);
        return b;
    }

    private static Bitmap decodeAsset(Context c, String path) {
        try (InputStream in = c.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Palette slot 0..3 an app's frame uses, stable per package. */
    static int accentIndex(String pkg) {
        return Math.abs(pkg.hashCode() % 4);
    }

    /** The accent colour of an app's frame. */
    static int accentFor(AppInfo a, Palette p) {
        return p.c(accentIndex(a.pkg));
    }

    /**
     * The drawer/tile icon for an app.
     *
     * @param style one of {@link Prefs#ICON_CONSOLE}, {@link Prefs#ICON_FRAMED}, {@link Prefs#ICON_NATIVE}, {@link Prefs#ICON_FACTION}
     * @param tone  one of {@link Prefs#TONE_CLASSIC}, {@link Prefs#TONE_VIVID}, {@link Prefs#TONE_SOLID}
     */
    static Drawable style(Context c, AppInfo a, Palette p, int style, int tone) {
        if (a.rawIcon == null) return null;
        // a pictogram the user picked for this app (CHANGE ICON) shows in every style but faction art
        if (a.glyphPinned && style != Prefs.ICON_FACTION) style = Prefs.ICON_CONSOLE;
        if (style == Prefs.ICON_NATIVE) return copy(a.rawIcon);
        int color = accentFor(a, p);
        if (tone != Prefs.TONE_CLASSIC) color = Ui.vivid(color);
        color = Skin.glow(color);
        boolean solid = tone == Prefs.TONE_SOLID && !Ui.cloak; // cloaked icons stay outlines
        if (style == Prefs.ICON_FACTION) {
            Bitmap fa = factionArt(c, a.glyph);
            if (fa != null) {
                BitmapDrawable bd = new BitmapDrawable(c.getResources(), fa);
                if (Ui.cloak) bd.setAlpha(CLOAKED_ART_ALPHA);
                return bd;
            }
            style = Prefs.ICON_CONSOLE;
        }
        if (style == Prefs.ICON_CONSOLE && a.glyph != null) {
            Bitmap g = glyph(c, a.glyph);
            if (g != null) return new Framed(null, g, color, p.panel, solid);
        }
        return new Framed(copy(a.rawIcon), null, color, p.panel, solid);
    }

    /** Each view needs its own Drawable instance (bounds are per-instance). */
    static Drawable copy(Drawable d) {
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable().mutate() : d;
    }

    /**
     * Tile frame around an icon or glyph, in the geometry of the faction active when it was created:
     * console rounded key with two accent bars, Imperial octagon with a diamond marker, Collective disk with an
     * inner ring and node, the 23rd-century square key with a lamp or 25th-century cut-corner glass
     * ({@link EraDraw#iconFrame}). Scales with its bounds.
     */
    static final class Framed extends Drawable {
        private final Drawable inner;
        private final Bitmap glyph;
        private final int color, fill, mark;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF();
        private final Rect src = new Rect(), dst = new Rect();
        private final Path path = new Path();
        /** Frame geometry follows the interface it was created for. */
        private final int faction = Skin.faction;

        Framed(Drawable inner, Bitmap glyph, int color, int fill) { this(inner, glyph, color, fill, false); }

        /** @param solid filled console button (colour fill, black pictogram) instead of a coloured outline */
        Framed(Drawable inner, Bitmap glyph, int color, int fill, boolean solid) {
            this.inner = inner;
            this.glyph = glyph;
            this.color = color;
            this.fill = solid ? color : fill;
            this.mark = solid ? SOLID_MARK : color;
            int glyphInk = solid ? 0xFF000000 : color;
            gp.setColorFilter(new PorterDuffColorFilter(glyphInk, PorterDuff.Mode.SRC_IN));
        }

        @Override
        public void draw(Canvas cv) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float x0 = b.centerX() - s / 2f, y0 = b.centerY() - s / 2f;
            float sw = Math.max(2f, s * 0.035f);
            r.set(x0 + sw, y0 + sw, x0 + s - sw, y0 + s - sw);
            if (Skin.eraFaction(faction)) {
                EraDraw.iconFrame(cv, r, s, sw, color, fill, mark, faction);
            } else if (faction == Skin.COMMAND) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(fill);
                cv.drawRoundRect(r, s * 0.22f, s * 0.22f, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(sw);
                paint.setColor(color);
                cv.drawRoundRect(r, s * 0.22f, s * 0.22f, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(mark);
                float bh = s * 0.055f;
                r.set(x0 + s * 0.10f, y0 + s * 0.11f, x0 + s * 0.32f, y0 + s * 0.11f + bh);
                cv.drawRoundRect(r, bh, bh, paint);
                r.set(x0 + s * 0.68f, y0 + s * 0.89f - bh, x0 + s * 0.90f, y0 + s * 0.89f);
                cv.drawRoundRect(r, bh, bh, paint);
            } else {
                path.reset();
                if (faction == Skin.IMPERIAL) {
                    float k = (r.right - r.left) * 0.29f;
                    path.moveTo(r.left + k, r.top);
                    path.lineTo(r.right - k, r.top);
                    path.lineTo(r.right, r.top + k);
                    path.lineTo(r.right, r.bottom - k);
                    path.lineTo(r.right - k, r.bottom);
                    path.lineTo(r.left + k, r.bottom);
                    path.lineTo(r.left, r.bottom - k);
                    path.lineTo(r.left, r.top + k);
                    path.close();
                } else {
                    path.addCircle(r.centerX(), r.centerY(), (r.right - r.left) / 2f, Path.Direction.CW);
                }
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(fill);
                cv.drawPath(path, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(sw);
                paint.setColor(color);
                cv.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(mark);
                if (faction == Skin.IMPERIAL) {
                    // diamond marker on the upper-left bevel
                    float cx = x0 + s * 0.2f, cy = y0 + s * 0.2f, d = s * 0.06f;
                    path.reset();
                    path.moveTo(cx, cy - d);
                    path.lineTo(cx + d, cy);
                    path.lineTo(cx, cy + d);
                    path.lineTo(cx - d, cy);
                    path.close();
                    cv.drawPath(path, paint);
                } else {
                    // inner ring and a square node at three o'clock
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(sw * 0.6f);
                    cv.drawCircle(r.centerX(), r.centerY(), (r.right - r.left) / 2f - sw * 2.2f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    float q = s * 0.05f;
                    cv.drawRect(r.right - q * 1.2f, r.centerY() - q, r.right + q * 0.8f, r.centerY() + q, paint);
                }
            }
            if (glyph != null) {
                int in = Math.round(s * GLYPH_INSET);
                src.set(0, 0, glyph.getWidth(), glyph.getHeight());
                dst.set(Math.round(x0) + in, Math.round(y0) + in, Math.round(x0 + s) - in, Math.round(y0 + s) - in);
                cv.drawBitmap(glyph, src, dst, gp);
            } else if (inner != null) {
                int in = Math.round(s * ICON_INSET);
                inner.setBounds(Math.round(x0) + in, Math.round(y0) + in, Math.round(x0 + s) - in, Math.round(y0 + s) - in);
                inner.draw(cv);
            }
        }

        @Override public int getIntrinsicWidth() { return INTRINSIC_PX; }
        @Override public int getIntrinsicHeight() { return INTRINSIC_PX; }
        @Override public void setAlpha(int a) { paint.setAlpha(a); gp.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
