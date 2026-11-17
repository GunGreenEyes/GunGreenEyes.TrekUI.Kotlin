package com.gungreeneyes.trekui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a third-party icon pack's {@code appfilter.xml} (the ADW / Nova / Go launcher format every
 * icon pack on Play ships) into a lookup table (LCA-T22).
 *
 * <p>The format, element by element:
 * <ul>
 *   <li>{@code <item component="ComponentInfo{pkg/cls}" drawable="name"/>}: the icon for one activity;</li>
 *   <li>{@code <calendar component="…" prefix="cal_"/>}: a date-aware icon, {@code prefix + day} (1..31);</li>
 *   <li>{@code <iconback img1="…" img2="…"/>}, {@code <iconmask img1="…"/>}, {@code <iconupon img1="…"/>}
 *       and {@code <scale factor="0.8"/>}: how to theme apps the pack does not cover (backplate,
 *       mask cut out of the scaled app icon, overlay).</li>
 * </ul>
 *
 * <p>The parser is fed element by element ({@link #element}) so it stays pure Java: the app drives
 * it from Android's {@code XmlPullParser} ({@link IconPacks}), the unit tests from the JDK's StAX.
 * Unit-tested in {@code IconPackParserTest}.
 */
final class IconPackParser {
    /** Parsed pack: component and package lookups, the unthemed-app recipe and every drawable named. */
    static final class Result {
        /** "pkg/cls" → drawable name. */
        final Map<String, String> byComponent = new HashMap<>();
        /** "pkg" → the first drawable listed for any activity of that package (fallback for renamed activities). */
        final Map<String, String> byPackage = new HashMap<>();
        /** "pkg/cls" → calendar drawable prefix. */
        final Map<String, String> calendars = new HashMap<>();
        /** Backplates (img1, img2, …) for apps the pack doesn't cover; one is chosen per package. */
        final List<String> backs = new ArrayList<>();
        /** Mask cut from scaled app icons and overlay drawn on top, or null. */
        String mask, upon;
        /** Scale of the app icon on its backplate (1 = full size). */
        float scale = 1f;
        /** Every drawable the pack names, in first-seen order, for the per-app icon picker. */
        final Set<String> drawables = new LinkedHashSet<>();

        /**
         * The pack's drawable for an activity, or null: an exact component match first, then any
         * activity of the same package.
         */
        String drawableFor(String pkg, String cls) {
            if (pkg == null) return null;
            if (cls != null) {
                String d = byComponent.get(pkg + "/" + cls);
                if (d != null) return d;
            }
            return byPackage.get(pkg);
        }

        /** The calendar drawable for an activity on {@code dayOfMonth} (1..31), or null. */
        String calendarFor(String pkg, String cls, int dayOfMonth) {
            String prefix = calendars.get(pkg + "/" + cls);
            return prefix == null ? null : prefix + dayOfMonth;
        }

        /** True when apps the pack doesn't cover get a backplate / mask / overlay. */
        boolean themesUnknownApps() {
            return !backs.isEmpty() || mask != null || upon != null;
        }

        /** The backplate for a package (stable per package), or null when the pack has none. */
        String backFor(String pkg) {
            if (backs.isEmpty()) return null;
            return backs.get(Math.abs((pkg == null ? 0 : pkg.hashCode()) % backs.size()));
        }

        /** Number of activities the pack themes. */
        int size() { return byComponent.size(); }

        /** The drawable names as a list (picker order). */
        List<String> drawableList() { return Collections.unmodifiableList(new ArrayList<>(drawables)); }
    }

    /** Smallest and largest accepted {@code <scale factor>}; anything else is ignored. */
    static final float MIN_SCALE = 0.1f, MAX_SCALE = 2f;
    /** Highest {@code imgN} index read from {@code <iconback>}. */
    static final int MAX_BACKS = 32;

    private final Result result = new Result();

    /** Feeds one start tag; attributes not listed above are ignored. */
    void element(String name, Map<String, String> attrs) {
        if (name == null) return;
        switch (name) {
            case "item": {
                String comp = component(attrs.get("component"));
                String drawable = clean(attrs.get("drawable"));
                if (drawable == null) return;
                result.drawables.add(drawable);
                if (comp == null) return;
                if (!result.byComponent.containsKey(comp)) result.byComponent.put(comp, drawable);
                String pkg = comp.substring(0, comp.indexOf('/'));
                if (!result.byPackage.containsKey(pkg)) result.byPackage.put(pkg, drawable);
                break;
            }
            case "calendar": {
                String comp = component(attrs.get("component"));
                String prefix = clean(attrs.get("prefix"));
                if (comp != null && prefix != null) result.calendars.put(comp, prefix);
                break;
            }
            case "iconback":
                // img1, img2, … (a few packs start at img0 or leave gaps, so every index is checked)
                for (int i = 0; i <= MAX_BACKS; i++) {
                    String img = clean(attrs.get("img" + i));
                    if (img != null && !result.backs.contains(img)) result.backs.add(img);
                }
                break;
            case "iconmask":
                result.mask = firstImg(attrs);
                break;
            case "iconupon":
                result.upon = firstImg(attrs);
                break;
            case "scale":
                try {
                    String f = attrs.get("factor");
                    if (f != null) {
                        float v = Float.parseFloat(f.trim());
                        if (v >= MIN_SCALE && v <= MAX_SCALE) result.scale = v;
                    }
                } catch (NumberFormatException ignored) {
                    // keep 1
                }
                break;
            default:
                break;
        }
    }

    /** Feeds one {@code <item drawable="…"/>} of a pack's {@code drawable.xml} (the full icon catalogue). */
    void catalogueItem(String drawable) {
        String d = clean(drawable);
        if (d != null) result.drawables.add(d);
    }

    /** The parse so far. */
    Result result() { return result; }

    private static String firstImg(Map<String, String> attrs) {
        String v = clean(attrs.get("img1"));
        if (v == null) v = clean(attrs.get("img"));
        if (v == null) v = clean(attrs.get("img0"));
        return v;
    }

    /** Trims and drops empty values and resource-style "@drawable/" prefixes. */
    static String clean(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.startsWith("@drawable/")) s = s.substring("@drawable/".length());
        return s.isEmpty() ? null : s;
    }

    /**
     * Normalises an appfilter component to "pkg/cls": strips "ComponentInfo{…}", and expands a
     * class that starts with "." against the package. Returns null for anything malformed
     * (no slash, empty package or class, or the ":LAUNCHER_ACTION_*" pseudo-components some packs list).
     */
    static String component(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("ComponentInfo{")) s = s.substring("ComponentInfo{".length());
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);
        s = s.trim();
        int slash = s.indexOf('/');
        if (slash <= 0 || slash == s.length() - 1) return null;
        String pkg = s.substring(0, slash).trim(), cls = s.substring(slash + 1).trim();
        if (pkg.isEmpty() || cls.isEmpty() || pkg.indexOf(':') >= 0) return null;
        if (cls.startsWith(".")) cls = pkg + cls;
        return pkg + "/" + cls;
    }
}
