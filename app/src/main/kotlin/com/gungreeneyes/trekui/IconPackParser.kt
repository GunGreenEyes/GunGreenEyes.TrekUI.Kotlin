package com.gungreeneyes.trekui

import java.util.Collections

/**
 * Reads a third-party icon pack's `appfilter.xml` (the ADW / Nova / Go launcher format every
 * icon pack on Play ships) into a lookup table (LCA-T22).
 *
 * The format, element by element:
 * - `<item component="ComponentInfo{pkg/cls}" drawable="name"/>`: the icon for one activity;
 * - `<calendar component="…" prefix="cal_"/>`: a date-aware icon, `prefix + day` (1..31);
 * - `<iconback img1="…" img2="…"/>`, `<iconmask img1="…"/>`, `<iconupon img1="…"/>`
 *   and `<scale factor="0.8"/>`: how to theme apps the pack does not cover (backplate,
 *   mask cut out of the scaled app icon, overlay).
 *
 * The parser is fed element by element ([element]) so it stays pure JVM code: the app drives
 * it from Android's `XmlPullParser` ([IconPacks]), the unit tests from the JDK's SAX parser.
 * Unit-tested in `IconPackParserTest`.
 */
internal class IconPackParser {
    /** Parsed pack: component and package lookups, the unthemed-app recipe and every drawable named. */
    class Result {
        /** "pkg/cls" → drawable name. */
        @JvmField val byComponent: MutableMap<String, String> = HashMap()

        /** "pkg" → the first drawable listed for any activity of that package (fallback for renamed activities). */
        @JvmField val byPackage: MutableMap<String, String> = HashMap()

        /** "pkg/cls" → calendar drawable prefix. */
        @JvmField val calendars: MutableMap<String, String> = HashMap()

        /** Backplates (img1, img2, …) for apps the pack doesn't cover; one is chosen per package. */
        @JvmField val backs: MutableList<String> = ArrayList()

        /** Mask cut from scaled app icons, or null. */
        @JvmField var mask: String? = null

        /** Overlay drawn on top, or null. */
        @JvmField var upon: String? = null

        /** Scale of the app icon on its backplate (1 = full size). */
        @JvmField var scale: Float = 1f

        /** Every drawable the pack names, in first-seen order, for the per-app icon picker. */
        @JvmField val drawables: MutableSet<String> = LinkedHashSet()

        /**
         * The pack's drawable for an activity, or null: an exact component match first, then any
         * activity of the same package.
         */
        fun drawableFor(pkg: String?, cls: String?): String? {
            if (pkg == null) return null
            if (cls != null) byComponent["$pkg/$cls"]?.let { return it }
            return byPackage[pkg]
        }

        /** The calendar drawable for an activity on `dayOfMonth` (1..31), or null. */
        fun calendarFor(pkg: String?, cls: String?, dayOfMonth: Int): String? {
            val prefix = calendars["$pkg/$cls"] ?: return null
            return prefix + dayOfMonth
        }

        /** True when apps the pack doesn't cover get a backplate / mask / overlay. */
        fun themesUnknownApps(): Boolean = backs.isNotEmpty() || mask != null || upon != null

        /** The backplate for a package (stable per package), or null when the pack has none. */
        fun backFor(pkg: String?): String? {
            if (backs.isEmpty()) return null
            return backs[Math.abs((pkg?.hashCode() ?: 0) % backs.size)]
        }

        /** Number of activities the pack themes. */
        fun size(): Int = byComponent.size

        /** The drawable names as a list (picker order). */
        fun drawableList(): List<String> = Collections.unmodifiableList(ArrayList(drawables))
    }

    private val result = Result()

    /** Feeds one start tag; attributes not listed above are ignored. */
    fun element(name: String?, attrs: Map<String, String?>) {
        when (name) {
            null -> return
            "item" -> {
                val comp = component(attrs["component"])
                val drawable = clean(attrs["drawable"]) ?: return
                result.drawables.add(drawable)
                if (comp == null) return
                result.byComponent.getOrPut(comp) { drawable }
                val pkg = comp.substring(0, comp.indexOf('/'))
                result.byPackage.getOrPut(pkg) { drawable }
            }
            "calendar" -> {
                val comp = component(attrs["component"])
                val prefix = clean(attrs["prefix"])
                if (comp != null && prefix != null) result.calendars[comp] = prefix
            }
            "iconback" -> {
                // img1, img2, … (a few packs start at img0 or leave gaps, so every index is checked)
                for (i in 0..MAX_BACKS) {
                    val img = clean(attrs["img$i"])
                    if (img != null && !result.backs.contains(img)) result.backs.add(img)
                }
            }
            "iconmask" -> result.mask = firstImg(attrs)
            "iconupon" -> result.upon = firstImg(attrs)
            "scale" -> try {
                val f = attrs["factor"]
                if (f != null) {
                    val v = f.javaTrim().toFloat()
                    if (v >= MIN_SCALE && v <= MAX_SCALE) result.scale = v
                }
            } catch (ignored: NumberFormatException) {
                // keep 1
            }
            else -> {}
        }
    }

    /** Feeds one `<item drawable="…"/>` of a pack's `drawable.xml` (the full icon catalogue). */
    fun catalogueItem(drawable: String?) {
        val d = clean(drawable)
        if (d != null) result.drawables.add(d)
    }

    /** The parse so far. */
    fun result(): Result = result

    companion object {
        /** Smallest accepted `<scale factor>`; anything else is ignored. */
        const val MIN_SCALE = 0.1f

        /** Largest accepted `<scale factor>`. */
        const val MAX_SCALE = 2f

        /** Highest `imgN` index read from `<iconback>`. */
        const val MAX_BACKS = 32

        private const val DRAWABLE_PREFIX = "@drawable/"
        private const val COMPONENT_INFO = "ComponentInfo{"

        private fun firstImg(attrs: Map<String, String?>): String? =
            clean(attrs["img1"]) ?: clean(attrs["img"]) ?: clean(attrs["img0"])

        /** Trims and drops empty values and resource-style "@drawable/" prefixes. */
        @JvmStatic
        fun clean(v: String?): String? {
            if (v == null) return null
            var s = v.javaTrim()
            if (s.startsWith(DRAWABLE_PREFIX)) s = s.substring(DRAWABLE_PREFIX.length)
            return s.ifEmpty { null }
        }

        /**
         * Normalises an appfilter component to "pkg/cls": strips "ComponentInfo{…}", and expands a
         * class that starts with "." against the package. Returns null for anything malformed
         * (no slash, empty package or class, or the ":LAUNCHER_ACTION_*" pseudo-components some packs list).
         */
        @JvmStatic
        fun component(raw: String?): String? {
            if (raw == null) return null
            var s = raw.javaTrim()
            if (s.startsWith(COMPONENT_INFO)) s = s.substring(COMPONENT_INFO.length)
            if (s.endsWith("}")) s = s.substring(0, s.length - 1)
            s = s.javaTrim()
            val slash = s.indexOf('/')
            if (slash <= 0 || slash == s.length - 1) return null
            val pkg = s.substring(0, slash).javaTrim()
            var cls = s.substring(slash + 1).javaTrim()
            if (pkg.isEmpty() || cls.isEmpty() || pkg.indexOf(':') >= 0) return null
            if (cls.startsWith(".")) cls = pkg + cls
            return "$pkg/$cls"
        }
    }
}
