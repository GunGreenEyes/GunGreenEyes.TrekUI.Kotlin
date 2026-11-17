package com.gungreeneyes.trekui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.InputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Third-party icon pack support (LCA-T22): finds installed ADW / Nova / Go / Apex / Atom-format
 * icon packs, parses the chosen one's {@code appfilter.xml} with {@link IconPackParser}, and
 * swaps each app's raw icon for the pack's drawing.
 *
 * <p>The swap happens inside {@link AppRepo}'s background load ({@link #apply}), so every interface
 * that shows {@link AppInfo#rawIcon} (FRAMED and NATIVE icon styles, dock, tiles, shade, pipe
 * interfaces) gets pack icons with no further changes, while the PICTOGRAM / console style keeps
 * drawing our pictograms. Apps the pack doesn't cover get the pack's backplate / mask / overlay
 * when it defines them ({@link #compose}). Parsed packs and their {@link Resources} are cached for
 * the life of the process; nothing here runs on the main thread except the {@link #async} hand-off.
 *
 * <p>Per-app choices (CHANGE ICON in the drawer, see {@link IconPicker}) are stored as
 * {@code appIcon.<AppInfo.key>} preferences: {@code glyph:<name>}, {@code pack:<pkg>/<drawable>}
 * or {@code app} (always the app's own icon). Format helpers are pure and unit-tested in
 * {@code IconPacksTest}.
 */
final class IconPacks {
    /** Intent actions icon packs register (each launcher family's own); queried in the manifest's {@code <queries>}. */
    static final String[] THEME_ACTIONS = {
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.dlto.atom.launcher.THEME",
    };
    /** Categories some packs use on a MAIN activity instead of an action. */
    static final String[] THEME_CATEGORIES = {
        "com.fede.launcher.THEME_ICONPACK",
        "com.anddoes.launcher.THEME",
        "com.teslacoilsw.launcher.THEME",
    };

    /** Selected pack's package, or "" for none. */
    static final String PREF_PACK = "iconPack";
    /** Per-app icon choice key prefix (followed by {@link AppInfo#key}). */
    static final String PREF_APP_PREFIX = "appIcon.";
    /** Give apps without a core pictogram a keyword-matched one ({@link Pictograms#defaultGlyph}). */
    static final String PREF_AUTO_GLYPHS = "autoGlyphs";
    /** Per-app choice values. */
    static final String CHOICE_GLYPH = "glyph:", CHOICE_PACK = "pack:", CHOICE_APP = "app";

    /** Side of composed (backplate/mask) icons in px. */
    static final int COMPOSED_PX = 192;

    /** An installed icon pack. */
    static final class Pack {
        final String pkg, label;
        Pack(String pkg, String label) { this.pkg = pkg; this.label = label; }
    }

    /** Result callback on the main thread. */
    interface Callback<T> { void done(T value); }

    private static final Map<String, IconPackParser.Result> parsed = new HashMap<>();
    private static final Map<String, Resources> resources = new HashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ExecutorService worker;

    private IconPacks() {}

    // ---- pure helpers ---------------------------------------------------------------------------

    /** Preference key of an app's icon choice. */
    static String prefKey(String appKey) { return PREF_APP_PREFIX + appKey; }

    /** The pictogram name in a choice, or null when it isn't a pictogram choice. */
    static String choiceGlyph(String choice) {
        return choice != null && choice.startsWith(CHOICE_GLYPH) && choice.length() > CHOICE_GLYPH.length()
                ? choice.substring(CHOICE_GLYPH.length()) : null;
    }

    /** {pack package, drawable} of a pack choice, or null when it isn't a pack choice or is malformed. */
    static String[] choicePack(String choice) {
        if (choice == null || !choice.startsWith(CHOICE_PACK)) return null;
        String rest = choice.substring(CHOICE_PACK.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) return null;
        return new String[]{rest.substring(0, slash), rest.substring(slash + 1)};
    }

    /** A pack choice value. */
    static String packChoice(String packPkg, String drawable) { return CHOICE_PACK + packPkg + "/" + drawable; }

    // ---- discovery --------------------------------------------------------------------------------

    /** Installed icon packs sorted by label (background thread: queries the package manager). */
    static List<Pack> installed(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, Pack> found = new LinkedHashMap<>();
        List<Intent> queries = new ArrayList<>();
        for (String a : THEME_ACTIONS) queries.add(new Intent(a));
        for (String cat : THEME_CATEGORIES) queries.add(new Intent(Intent.ACTION_MAIN).addCategory(cat));
        for (Intent q : queries) {
            List<ResolveInfo> list;
            try { list = pm.queryIntentActivities(q, PackageManager.GET_META_DATA); }
            catch (RuntimeException e) { continue; }
            for (ResolveInfo ri : list) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (found.containsKey(pkg) || pkg.equals(c.getPackageName())) continue;
                CharSequence l = ri.activityInfo.applicationInfo != null ? ri.activityInfo.applicationInfo.loadLabel(pm) : null;
                found.put(pkg, new Pack(pkg, l == null ? pkg : l.toString()));
            }
        }
        List<Pack> out = new ArrayList<>(found.values());
        final Collator col = Collator.getInstance();
        col.setStrength(Collator.PRIMARY);
        Collections.sort(out, new Comparator<Pack>() {
            @Override public int compare(Pack x, Pack y) { return col.compare(x.label, y.label); }
        });
        return out;
    }

    /** Last {@link #installed} result, or null before the first scan (main thread reads it for CONFIG). */
    private static volatile List<Pack> lastInstalled;

    /** The packs found by the last scan, or null when none has run yet. */
    static List<Pack> cachedInstalled() { return lastInstalled; }

    /**
     * Rescans installed packs off the main thread; {@code cb} gets the list on the main thread
     * and {@code changed} = true when it differs from {@link #cachedInstalled()}.
     */
    static void refreshInstalled(final Context c, final Callback<Boolean> cb) {
        final Context app = c.getApplicationContext();
        final List<Pack> before = lastInstalled;
        async(new Callable<List<Pack>>() {
            @Override public List<Pack> call() { return installed(app); }
        }, new Callback<List<Pack>>() {
            @Override public void done(List<Pack> now) {
                if (now == null) now = Collections.emptyList();
                lastInstalled = now;
                cb.done(before == null || !samePacks(before, now));
            }
        });
    }

    /** Pure: same packages in the same order. */
    static boolean samePacks(List<Pack> x, List<Pack> y) {
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) if (!x.get(i).pkg.equals(y.get(i).pkg)) return false;
        return true;
    }

    /** Runs {@code work} on the icon-pack worker and hands the result to {@code cb} on the main thread. */
    static <T> void async(final Callable<T> work, final Callback<T> cb) {
        synchronized (IconPacks.class) {
            if (worker == null) worker = Http.newExecutor("trekui-iconpack");
        }
        worker.execute(new Runnable() {
            @Override public void run() {
                T v = null;
                try { v = work.call(); } catch (RuntimeException ignored) { /* reported as null */ }
                final T value = v;
                MAIN.post(new Runnable() { @Override public void run() { cb.done(value); } });
            }
        });
    }

    /** Background work for {@link #async} (no checked exceptions). */
    interface Callable<T> { T call(); }

    // ---- parsing ----------------------------------------------------------------------------------

    /** The pack's resources, or null when it is not installed. Cached. */
    static synchronized Resources resources(Context c, String pkg) {
        if (resources.containsKey(pkg)) return resources.get(pkg);
        Resources r = null;
        try { r = c.getPackageManager().getResourcesForApplication(pkg); }
        catch (PackageManager.NameNotFoundException | RuntimeException ignored) { /* not installed */ }
        resources.put(pkg, r);
        return r;
    }

    /** Forgets cached packs (a pack was updated, or the choice changed). */
    static synchronized void clearCache() {
        parsed.clear();
        resources.clear();
    }

    /**
     * The parsed pack (background thread). Reads {@code appfilter.xml} from the pack's assets, or
     * {@code res/xml/appfilter}; then {@code drawable.xml} (assets or res/xml) for the full icon
     * catalogue. Returns an empty result for packs without an appfilter. Cached.
     */
    static IconPackParser.Result load(Context c, String pkg) {
        synchronized (IconPacks.class) {
            IconPackParser.Result hit = parsed.get(pkg);
            if (hit != null) return hit;
        }
        IconPackParser p = new IconPackParser();
        Resources res = resources(c, pkg);
        if (res != null) {
            readXml(res, pkg, "appfilter", p, false);
            readXml(res, pkg, "drawable", p, true);
        }
        IconPackParser.Result r = p.result();
        synchronized (IconPacks.class) { parsed.put(pkg, r); }
        return r;
    }

    /** Feeds one XML file (assets/{name}.xml, else res/xml/{name}) into the parser. */
    private static void readXml(Resources res, String pkg, String name, IconPackParser p, boolean catalogue) {
        XmlPullParser xp = null;
        InputStream in = null;
        try {
            try {
                in = res.getAssets().open(name + ".xml");
                xp = XmlPullParserFactory.newInstance().newPullParser();
                xp.setInput(in, null);
            } catch (Exception noAsset) {
                in = null;
                int id = res.getIdentifier(name, "xml", pkg);
                if (id != 0) xp = res.getXml(id);
            }
            if (xp == null) return;
            Map<String, String> attrs = new HashMap<>();
            for (int ev = xp.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = xp.next()) {
                if (ev != XmlPullParser.START_TAG) continue;
                if (catalogue) {
                    if ("item".equals(xp.getName())) p.catalogueItem(xp.getAttributeValue(null, "drawable"));
                    continue;
                }
                attrs.clear();
                for (int i = 0; i < xp.getAttributeCount(); i++) attrs.put(xp.getAttributeName(i), xp.getAttributeValue(i));
                p.element(xp.getName(), attrs);
            }
        } catch (Exception ignored) {
            // a malformed file: keep whatever parsed before the error
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { /* nothing to do */ }
        }
    }

    // ---- drawables --------------------------------------------------------------------------------

    /** A drawable from a pack by name (drawable, then mipmap), or null (background thread preferred). */
    static Drawable drawable(Context c, String packPkg, String name) {
        if (packPkg == null || name == null) return null;
        Resources res = resources(c, packPkg);
        if (res == null) return null;
        try {
            int id = res.getIdentifier(name, "drawable", packPkg);
            if (id == 0) id = res.getIdentifier(name, "mipmap", packPkg);
            return id == 0 ? null : res.getDrawable(id, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The pack's icon for an app: its own drawing (calendar-aware), else the app's icon on the
     * pack's backplate when the pack themes unknown apps, else null (keep the app's icon).
     */
    static Drawable iconFor(Context c, String packPkg, IconPackParser.Result r, AppInfo a, Drawable appIcon) {
        String cls = a.cn.getClassName();
        String cal = r.calendarFor(a.pkg, cls, Calendar.getInstance().get(Calendar.DAY_OF_MONTH));
        Drawable d = cal != null ? drawable(c, packPkg, cal) : null;
        if (d == null) d = drawable(c, packPkg, r.drawableFor(a.pkg, cls));
        if (d != null) return d;
        if (appIcon != null && r.themesUnknownApps()) return compose(c, packPkg, r, a.pkg, appIcon);
        return null;
    }

    /**
     * Themes an icon the pack doesn't cover, ADW style: backplate, then the app icon scaled by the
     * pack's factor with the mask's opaque pixels cut away, then the overlay.
     */
    static Drawable compose(Context c, String packPkg, IconPackParser.Result r, String appPkg, Drawable appIcon) {
        int s = COMPOSED_PX;
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Drawable back = drawable(c, packPkg, r.backFor(appPkg));
        if (back != null) { back.setBounds(0, 0, s, s); back.draw(cv); }
        Bitmap icon = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas ic = new Canvas(icon);
        int inset = Math.round(s * (1f - r.scale) / 2f);
        Drawable copy = IconFactory.copy(appIcon);
        copy.setBounds(inset, inset, s - inset, s - inset);
        copy.draw(ic);
        Drawable mask = drawable(c, packPkg, r.mask);
        if (mask != null) {
            Bitmap mb = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
            Canvas mc = new Canvas(mb);
            mask.setBounds(0, 0, s, s);
            mask.draw(mc);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
            ic.drawBitmap(mb, 0, 0, p);
            mb.recycle();
        }
        cv.drawBitmap(icon, 0, 0, null);
        icon.recycle();
        Drawable upon = drawable(c, packPkg, r.upon);
        if (upon != null) { upon.setBounds(0, 0, s, s); upon.draw(cv); }
        return new BitmapDrawable(c.getResources(), out);
    }

    /**
     * Background thread (called from {@link AppRepo}'s load): keeps each app's system icon in
     * {@link AppInfo#sysIcon} and replaces {@link AppInfo#rawIcon} with the selected pack's icon,
     * or with the per-app pack drawable the user picked; {@code app} choices keep the system icon.
     */
    static void apply(Context c, List<AppInfo> apps) {
        Prefs prefs = Prefs.get(c);
        String pack = prefs.s(PREF_PACK, "");
        IconPackParser.Result r = pack.isEmpty() ? null : load(c, pack);
        for (AppInfo a : apps) {
            a.sysIcon = a.rawIcon;
            String choice = prefs.s(prefKey(a.key), null);
            String[] pick = choicePack(choice);
            Drawable d = null;
            if (pick != null) d = drawable(c, pick[0], pick[1]);
            else if (r != null && !CHOICE_APP.equals(choice) && a.mainProfile) d = iconFor(c, pack, r, a, a.rawIcon);
            if (d != null) a.rawIcon = d;
        }
    }
}
