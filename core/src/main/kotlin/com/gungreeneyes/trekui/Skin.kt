package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The interface factions. They share every launcher function (apps, tiles, dock, panels, shade,
 * dialer, sounds, devices…) but draw with their own geometry, type and colour:
 *
 * ```
 *  COMMAND (0)  console: curved elbows, pill keys, solid colour segments, Antonio.
 *  IMPERIAL    (1)  Imperial tactical: chamfered/oblique plates, hex targeting optics, diamond
 *                  markers, copper hairlines, Oxanium / Warrior Blade. Never an console curve.
 *  COLLECTIVE       (2)  Collective: square tinted-acrylic modules with a backlit edge bar, conduit lines,
 *                  circular node glyphs, telemetry dots, Share Tech. Never an elbow or a hex.
 *  (ids 3-8 are retired: they belonged to interfaces since removed; TrekPrefs.clampFaction
 *  reads a saved one as COMMAND.)
 *  More Command eras (6.3, drawn by EraDraw; command() is true for them, so every
 *  Command layout, cascade and sound profile applies, only geometry, type and colour differ):
 *  ERA23    (9)  23rd-century console: chunky square keys, primary colours, blinking lamps, Josefin Sans.
 *  ERA25   (10)  25th-century glass: thin translucent blue/grey layers, cut corners, fine ticks, light Antonio.
 * ```
 *
 * The current faction is the static [faction], set from [TrekPrefs.faction] whenever a
 * screen is built ([TrekPrefs.palette] and [load]). Palettes for Imperial/Collective are the ones
 * measured from the faction artwork bundle (theme.json + SVGs).
 *
 * Java interop: ids are `const`, the mutable current state ([faction], [lettering], the active
 * sub-themes) and the tables are [JvmField]s and every function is [JvmStatic], so Java keeps
 * calling `Skin.imperial()` and assigning `Skin.faction`.
 */
object Skin {
    const val COMMAND = 0
    const val IMPERIAL = 1
    const val COLLECTIVE = 2
    /** The extra Command eras (see [Era]). */
    const val ERA23 = 9
    const val ERA25 = 10
    /** First and last retired faction id (saved values in this range read as COMMAND). */
    const val FIRST_RETIRED = 3
    const val LAST_RETIRED = 8

    /**
     * The interfaces that exist, in picker order. Ids are stored in preferences, so they never change;
     * [NAMES] and [SUBTITLES] are indexed by id and hold null for the retired ids.
     */
    @JvmField
    val INTERFACES: IntArray = intArrayOf(COMMAND, IMPERIAL, COLLECTIVE, ERA23, ERA25)

    @JvmField
    val NAMES: Array<String?> = arrayOf("COMMAND", "IMPERIAL", "COLLECTIVE", null, null, null,
        null, null, null, "CLASSIC CONSOLE", "NEXT CONSOLE")

    @JvmField
    val SUBTITLES: Array<String?> = arrayOf("COMMAND • CURVED CONTROL RAILS", "IMPERIAL TACTICAL • CHAMFERED PLATES, HEX OPTICS",
        "COLLECTIVE • NETWORKED CELLS, NODE GLYPHS", null, null, null, null, null, null,
        "23RD-CENTURY STYLE • SQUARE KEYS, PRIMARY LAMPS", "25TH-CENTURY STYLE • FINE LINES, LAYERED GLASS")

    /** True for an interface that exists (one of [INTERFACES]); false for retired or unknown ids. */
    @JvmStatic
    fun exists(f: Int): Boolean = f in INTERFACES

    /** The names of [INTERFACES], in picker order (picker position n is interface INTERFACES[n]). */
    @JvmStatic
    fun interfaceNames(): Array<String?> = Array(INTERFACES.size) { NAMES[INTERFACES[it]] }

    /** Picker position of interface `f` in [INTERFACES] (0, Command, when it is not offered). */
    @JvmStatic
    fun interfaceIndex(f: Int): Int = INTERFACES.indexOf(f).coerceAtLeast(0)

    /** Current faction (set from prefs whenever a screen is built). */
    @JvmField
    var faction = COMMAND

    /** Reads the current faction from preferences. */
    @JvmStatic
    fun load(c: Context) {
        faction = TrekPrefs.get(c).faction()
    }

    /** The Command family: console proper and the two extra eras (they share every Command layout). */
    @JvmStatic
    fun command(): Boolean = commandFamily(faction)

    /** True for Command console and the 23rd/25th-century eras. */
    @JvmStatic
    fun commandFamily(f: Int): Boolean = f == COMMAND || eraFaction(f)

    /** One of the extra Command eras (9, 10). */
    @JvmStatic
    fun era(): Boolean = eraFaction(faction)

    @JvmStatic
    fun eraFaction(f: Int): Boolean = f == ERA23 || f == ERA25

    /** 23rd-century console. */
    @JvmStatic
    fun era23(): Boolean = faction == ERA23

    /** 25th-century glass. */
    @JvmStatic
    fun era25(): Boolean = faction == ERA25

    @JvmStatic
    fun imperial(): Boolean = faction == IMPERIAL

    @JvmStatic
    fun collective(): Boolean = faction == COLLECTIVE

    /** Asset folder holding the faction's tile/badge art (Imperial or Collective). */
    @JvmStatic
    fun artFolder(): String = if (imperial()) "faction/imperial/" else "faction/collective/"

    /** Ink for the lettering on a Collective group key. */
    @JvmStatic
    fun tileInk(): Int = if (Ui.cloak) 0xB3C9A063.toInt() else 0xFFA8322A.toInt()

    /** Navigation names on the home screen: 0 apps, 1 search, 2 config. */
    @JvmStatic
    fun nav(i: Int): String = arrayOf("APPS", "SEARCH", "CONFIG")[i]

    /** Title of the app drawer. */
    @JvmStatic
    fun drawerTitle(): String = "APPLICATIONS"

    // ---- colours ---------------------------------------------------------------------------------

    /**
     * Imperial Tactical, after the Imperial screens: blood red, orange, warning yellow, deep red,
     * red-orange, amber (brighter and redder than the first artwork bundle).
     */
    @JvmField
    val IMPERIAL_PALETTE: Palette = Palette.IMPERIAL[0]
    /** The Collective: active green, highlight, matrix, grid, muted, conduit. */
    @JvmField
    val COLLECTIVE_PALETTE: Palette = Palette.COLLECTIVE[0]

    /** Imperial warning yellow and Collective highlight green: the colours dark tones are lifted toward. */
    const val IMPERIAL_GLOW = 0xFFF5C830.toInt()
    const val COLLECTIVE_GLOW = 0xFFB1DC86.toInt()
    /** Relative luminance below which [glow] lifts a colour. */
    const val GLOW_LUMINANCE = 0.42f

    /**
     * The active Imperial and Collective sub-themes (LCA-T35). Their chrome tones (background, surface,
     * grid, detail, lettering, glow, line colours) drive every Imperial / Collective drawing helper.
     * Set by [TrekPrefs.palette] through [useSubTheme]; the defaults are the original looks.
     */
    @JvmField
    var imperialTheme: Palette = IMPERIAL_PALETTE
    @JvmField
    var collectiveTheme: Palette = COLLECTIVE_PALETTE

    /**
     * Makes `p` the active sub-theme of `faction` (Imperial or Collective; any other
     * faction or an untoned palette is ignored). Imperial also recolours `ImperialDraw`'s line,
     * orange, highlight and deep colours.
     */
    @JvmStatic
    fun useSubTheme(faction: Int, p: Palette?) {
        if (p == null || !p.toned) return
        if (faction == IMPERIAL) {
            imperialTheme = p
            ImperialDraw.use(p)
        } else if (faction == COLLECTIVE) {
            collectiveTheme = p
        }
    }

    /** Collective matrix line colour (dark traces of the backdrop network). */
    @JvmStatic
    fun collectiveLine(): Int = collectiveTheme.c[2]

    /** Collective lit node colour. */
    @JvmStatic
    fun collectiveLit(): Int = collectiveTheme.c[0]

    /** The highlight dark tones are lifted toward: Imperial yellow / Collective highlight of the active sub-theme. */
    @JvmStatic
    fun glowColor(): Int = if (imperial()) imperialTheme.c[2] else collectiveTheme.c[1]

    /** Screen background behind everything. */
    @JvmStatic
    fun bg(): Int {
        if (era()) return if (era23()) Era.ERA23_BG else Era.ERA25_BG
        return if (imperial()) imperialTheme.bg else if (collective()) collectiveTheme.bg else Color.BLACK
    }

    /** Tray / dialog / card surface. */
    @JvmStatic
    fun surface(): Int {
        if (era()) return if (era23()) 0xFF17130E.toInt() else 0xFF0B121A.toInt()
        return if (imperial()) imperialTheme.surface else if (collective()) collectiveTheme.surface else 0xFF0E0C16.toInt()
    }

    /** Grid and hairline colour. */
    @JvmStatic
    fun grid(): Int {
        if (era()) return if (era23()) 0xFF2A2620.toInt() else 0xFF1E2C3A.toInt()
        return if (imperial()) imperialTheme.grid else if (collective()) collectiveTheme.grid else 0xFF1A1826.toInt()
    }

    /** Secondary detail colour (ticks, inactive marks). */
    @JvmStatic
    fun detail(): Int {
        if (era()) return if (era23()) 0xFF3A342A.toInt() else 0xFF2A3C4E.toInt()
        return if (imperial()) imperialTheme.detail else if (collective()) collectiveTheme.detail else 0xFF2A2840.toInt()
    }

    /** Lettering on a control: black on Command's solid colour; light on the factions' dark plates. */
    @JvmStatic
    fun ink(): Int {
        if (Ui.cloak) {
            if (command()) return Ui.CLOAK_INK
            return 0xE6000000.toInt() or ((if (imperial()) imperialTheme.c[1] else collectiveTheme.c[0]) and 0x00FFFFFF)
        }
        if (era25()) return Era.ERA25_INK
        return if (imperial()) imperialTheme.ink else if (collective()) collectiveTheme.ink else Color.BLACK
    }

    // ---- type ------------------------------------------------------------------------------------

    /**
     * Imperial lettering: BLADE_MIXED (default) sets titles, labels and keys in Warrior Blade (the
     * launcher's own Imperial-style English font) and longer text in Oxanium; BLADE_ALL uses Warrior
     * Blade everywhere; PLAIN is Oxanium only.
     */
    const val BLADE_MIXED = 0
    const val BLADE_ALL = 1
    const val PLAIN = 2

    @JvmField
    var lettering = BLADE_MIXED

    /** Asset path of the heavy typeface, or null for Command's Antonio. */
    @JvmStatic
    fun fontHeavy(): String? {
        if (imperial()) return if (lettering == PLAIN) "fonts/Oxanium-Bold.ttf" else "fonts/WarriorBlade-Bold.ttf"
        if (era23()) return "fonts/JosefinSans-SemiBold.ttf"
        return if (collective()) "fonts/ShareTech-Regular.ttf" else null
    }

    /** Asset path of the regular typeface, or null for Command's Antonio. */
    @JvmStatic
    fun fontRegular(): String? {
        if (imperial()) return if (lettering == BLADE_ALL) "fonts/WarriorBlade-Regular.ttf" else "fonts/Oxanium-SemiBold.ttf"
        if (era23()) return "fonts/JosefinSans-Regular.ttf"
        return if (collective()) "fonts/ShareTech-Regular.ttf" else null
    }

    /**
     * Antonio weight for interfaces that set Antonio at their own weights (fontRegular() is null and
     * the faction is not Command): the 25th century's light 300 / regular 500 lettering.
     */
    @JvmStatic
    fun antonioWeight(heavy: Boolean): Int = if (heavy) 500 else 300

    /** The faction typefaces are wider than condensed Antonio: scale text so layouts still fit. */
    @JvmStatic
    fun textScale(): Float {
        // Warrior Blade runs ~12% narrower than Oxanium at the same height, so it can be set larger
        if (imperial()) return if (lettering == PLAIN) 0.80f else if (lettering == BLADE_ALL) 0.90f else 0.84f
        if (era23()) return 0.80f
        return if (collective()) 0.90f else 1f
    }

    /** Letter spacing (em) for the faction's lettering. */
    @JvmStatic
    fun letterSpacing(): Float {
        if (era()) return if (era23()) 0.05f else 0.09f
        return if (imperial()) 0.02f else if (collective()) 0.06f else 0.03f
    }

    // ---- default wording (English labels; no claimed Imperial or Collective language) ---------------------

    /** Home title block. */
    @JvmStatic
    fun title(): String {
        if (era()) return "CONSOLE"
        return if (imperial()) "TACTICAL" else if (collective()) "NODE CLUSTER 01" else "TREKUI"
    }

    /** Heading above the home tiles. */
    @JvmStatic
    fun ops(): String {
        if (era23()) return "BRIDGE FUNCTIONS"
        return if (imperial()) "COMMAND FUNCTIONS" else if (collective()) "LOCAL INTERFACE NODES" else "OPERATIONS"
    }

    /** Footer line of the home frame. */
    @JvmStatic
    fun foot(): String {
        if (era()) return if (era23()) "PRIMARY CONTROL STATION" else "FLEET ACCESS TERMINAL"
        return if (imperial()) "IMPERIAL TERMINAL" else if (collective()) "COLLECTIVE LINK" else "PERSONAL ACCESS TERMINAL"
    }

    /** Default header (ship registry) line; Command keeps the caller's text. */
    @JvmStatic
    fun ship(command: String?): String? {
        if (faction == COMMAND) return command
        if (era()) return if (era23()) "BRIDGE STATION 01 • DECK 1" else "FLEET OPERATIONS • SECTOR 7"
        return if (imperial()) "WARRIOR CONTROL / MOBILE TERMINAL" else "COLLECTIVE / NODE ACCESS"
    }

    /** Sub-heading used on library/reveal screens. */
    @JvmStatic
    fun library(): String {
        if (era()) return if (era23()) "COMPUTER ACCESS / BRIDGE STATION" else "LIBRARY ACCESS / FLEET NETWORK"
        return if (imperial()) "IMPERIAL TACTICAL / WARRIOR CONTROL"
        else if (collective()) "COLLECTIVE / NODE ACCESS / NETWORK LINK"
        else "CENTRAL COMPUTER ACCESS / CONTROL SYSTEM"
    }

    /** Asset folder of the faction's bundled wallpapers (none for Command and the eras). */
    @JvmStatic
    fun wallpaperFolder(): String? =
        if (imperial()) "faction/imperial/" else if (collective()) "faction/collective/" else null

    // ---- colour maths (pure, no android.graphics.Color so it runs in JVM tests) ------------------

    /** Linear blend of two colours' RGB channels; `t` = 0 gives `a`, 1 gives `b`. Result is opaque. */
    @JvmStatic
    fun mix(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bgc = (b shr 8) and 0xFF
        val bb = b and 0xFF
        return 0xFF000000.toInt() or
            (Math.round(ar + (br - ar) * t) shl 16) or
            (Math.round(ag + (bgc - ag) * t) shl 8) or
            Math.round(ab + (bb - ab) * t)
    }

    /** Rec. 709 relative luminance of a colour's RGB channels, 0..1 (gamma-encoded values, as the original). */
    @JvmStatic
    fun luminance(color: Int): Float =
        (0.2126f * ((color shr 16) and 0xFF) + 0.7152f * ((color shr 8) and 0xFF) + 0.0722f * (color and 0xFF)) / 255f

    /**
     * Pure form of [glow]: a colour darker than [GLOW_LUMINANCE] is mixed toward
     * `highlight` (by up to 75%, more for darker colours); alpha is preserved.
     */
    @JvmStatic
    fun glowToward(color: Int, highlight: Int): Int {
        val lum = luminance(color)
        if (lum >= GLOW_LUMINANCE) return color
        return (color and 0xFF000000.toInt()) or
            (mix(color, highlight, min(0.75f, (GLOW_LUMINANCE - lum) * 2.2f)) and 0x00FFFFFF)
    }

    /**
     * Edge/glyph version of a palette colour: the darker faction tones (Collective matrix and conduit
     * greens, Imperial bronze) are lifted toward the faction's light text colour so outlines and
     * pictograms stay readable. Fills keep the original tone. Command colours pass through.
     */
    @JvmStatic
    fun glow(color: Int): Int = if (command()) color else glowToward(color, glowColor())

    // ---- controls ---------------------------------------------------------------------------------

    /** Background for a control in the current faction (Command keeps the plain GradientDrawable). */
    @JvmStatic
    fun cell(c: Context, color: Int, kind: Int, radiusDp: Float): Drawable =
        if (imperial()) Chamfer(c, color, kind) else NodeCell(c, color, kind)

    /** Shared paints and state for the Imperial/Collective control drawables. */
    abstract class Base(c: Context, protected val color: Int, protected val kind: Int) : Drawable() {
        protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        protected val mark = Paint(Paint.ANTI_ALIAS_FLAG)
        protected val path = Path()
        protected val r = RectF()
        /** Pixels per (zoomed) dp. */
        protected val d: Float = Ui.density(c)

        init {
            val solid = Color.alpha(color) > 0x80
            fill.style = Paint.Style.FILL
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = max(1f, 1.4f * d)
            if (Ui.cloak) {
                fill.color = Color.BLACK
                stroke.color = Ui.alpha(color, 0.65f)
                mark.color = Ui.alpha(color, 0.65f)
            } else {
                // dark plate tinted by the control's colour; the colour itself becomes edge and marker
                fill.color = if (solid) mix(surface(), color, if (imperial()) 0.10f else 0.13f) else color
                stroke.color = if (solid) Ui.alpha(glow(color), 0.85f) else Color.TRANSPARENT
                mark.color = if (solid) glow(color) else Color.TRANSPARENT
            }
        }

        override fun setAlpha(a: Int) {
            fill.alpha = a
            stroke.alpha = a
            mark.alpha = a
        }

        override fun setColorFilter(cf: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * Imperial control: a hollow long octagon (45° cuts on every corner; only the outer side for
     * LEFT/RIGHT end caps, pointed ends for thin PILL bars) in a heavy blood-red line on black.
     * Large ones are double-lined, carry a faint triangle lattice inside, a solid triangle marker at
     * the leading edge and a short ▲▼▲ row in the corner.
     */
    class Chamfer(c: Context, color: Int, kind: Int) : Base(c, color, kind) {
        private val inner = Path()
        private val rr = RectF()

        override fun draw(cv: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w < 2 || h < 2) return
            val faint = Color.alpha(color) <= 0x80
            val col = glow(if (faint) color or 0xFF000000.toInt() else color)
            val sw = 2.4f * d
            val hs = sw / 2
            val x = b.left + hs
            val y = b.top + hs
            val x2 = b.right - hs
            val y2 = b.bottom - hs
            val k = min((y2 - y) * 0.36f, min((x2 - x) * 0.22f, 16 * d))
            val big = kind != Ui.PILL && h > 40 * d && w > 110 * d
            if (kind == Ui.PILL) {
                val p = min((y2 - y) * 0.5f, (x2 - x) * 0.2f)
                path.reset()
                path.moveTo(x + p, y)
                path.lineTo(x2 - p, y)
                path.lineTo(x2, (y + y2) / 2)
                path.lineTo(x2 - p, y2)
                path.lineTo(x + p, y2)
                path.lineTo(x, (y + y2) / 2)
                path.close()
            } else {
                ImperialDraw.octagon(path, x, y, x2, y2, k, kind != Ui.RIGHT, kind != Ui.LEFT)
            }
            ImperialDraw.fill(cv, path, if (Ui.cloak) Color.BLACK else mix(bg(), col, if (faint) 0.03f else 0.07f))
            if (big && !Ui.cloak) {
                cv.save()
                cv.clipPath(path)
                rr.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                ImperialDraw.grid(cv, rr, 16 * d, ImperialDraw.RED, if (faint) 0.08f else 0.13f, d)
                cv.restore()
            }
            var inPath: Path? = null
            if (big || (kind == Ui.SQUARE && h > 30 * d && w > 56 * d)) {
                val g = 3.6f * d
                ImperialDraw.octagon(inner, x + g, y + g, x2 - g, y2 - g, max(2f, k - g * 0.4f), kind != Ui.RIGHT, kind != Ui.LEFT)
                inPath = inner
            }
            ImperialDraw.frame(cv, path, inPath, col, d, if (faint) 0.45f else 1f)
            if (faint) return
            if (big) {
                // solid triangle marker at the leading edge (square plates), ▲▼▲ row in the lower corner
                // away from the cut end
                if (kind == Ui.SQUARE) ImperialDraw.tri(cv, x + 13 * d, (y + y2) / 2f, 9 * d, 10 * d, false, ImperialDraw.YELLOW, 0.95f)
                var rx = if (kind == Ui.RIGHT) x + 8 * d else x2 - k - 36 * d
                if (kind == Ui.LEFT) rx = x2 - 42 * d
                ImperialDraw.row(cv, rx, y2 - 10 * d, 34 * d, 7 * d,
                    intArrayOf(ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED), 0.95f)
            } else if (kind == Ui.SQUARE && h > 30 * d && w > 40 * d) {
                // side key: small triangle in the upper corner
                ImperialDraw.tri(cv, x + k + 7 * d, y + 9 * d, 8 * d, 7 * d, false, ImperialDraw.YELLOW, 0.9f)
            }
        }
    }

    /**
     * Collective control. Large controls (home tiles, sidebar keys) have no box at all: a glowing node
     * on a trace, floating on black, like the Collective displays. Small controls (buttons, chips,
     * code blocks) get a soft rounded plate with a node at the leading end. No corners anywhere.
     */
    class NodeCell(c: Context, color: Int, kind: Int) : Base(c, color, kind) {
        override fun draw(cv: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val x = b.left.toFloat()
            val y = b.top.toFloat()
            if (w < 2 || h < 2) return
            val col = if (Color.alpha(color) > 0x80) glow(color) else color
            val faint = Color.alpha(color) <= 0x80 // empty slot / disabled
            val fa = if (faint) 0.45f else 1f
            val seed = abs((w * 31 + h * 17 + color).toInt())
            if (h >= 40 * d && w >= 120 * d && kind == Ui.SQUARE) {
                // home tile: node at the icon position (32 dp in), trace to a via at the far end
                val cx = x + 32 * d
                val cy = y + h / 2f
                val nr = min(h * 0.38f, 18 * d)
                val ty = y + h - 8 * d
                val ex = x + w - 12 * d
                CollectiveDraw.curveTrace(cv, cx + nr * 0.9f, cy + nr * 0.5f, x + w * 0.55f, ty, 0.12f, col, d, 0.8f * fa)
                val t = CollectiveDraw.tmp()
                t.moveTo(x + w * 0.55f, ty)
                t.lineTo(ex - 4 * d, ty)
                CollectiveDraw.trace(cv, t, col, d, 0.8f * fa)
                CollectiveDraw.via(cv, ex, ty, 3.5f * d, col, d, fa)
                CollectiveDraw.dots(cv, ex - 34 * d, ty - 7 * d, 6, col, d, seed, fa)
                if (faint) CollectiveDraw.ringNode(cv, cx, cy, nr, col, d, 0.7f)
                else CollectiveDraw.node(cv, cx, cy, nr, col, d, 1f)
                return
            }
            if (h >= 40 * d && w < 120 * d && kind == Ui.SQUARE) {
                // sidebar key: vertical trace through a node; label sits bottom-right
                val cx = x + w * 0.5f
                val cy = y + h * 0.36f
                val nr = min(w * 0.2f, h * 0.2f)
                val v = CollectiveDraw.tmp()
                v.moveTo(cx, y)
                v.lineTo(cx, cy - nr)
                CollectiveDraw.trace(cv, v, col, d, 0.6f * fa)
                CollectiveDraw.curveTrace(cv, cx, cy + nr, x + 6 * d, y + h, -0.18f, col, d, 0.6f * fa)
                CollectiveDraw.node(cv, cx, cy, nr, col, d, fa)
                return
            }
            if (kind == Ui.PILL && h < 16 * d) {
                // thin bar: a trace with a via at each end
                val t = CollectiveDraw.tmp()
                t.moveTo(x + 5 * d, y + h / 2f)
                t.lineTo(x + w - 5 * d, y + h / 2f)
                CollectiveDraw.trace(cv, t, col, d, fa)
                CollectiveDraw.via(cv, x + 3.5f * d, y + h / 2f, 2.5f * d, col, d, fa)
                CollectiveDraw.via(cv, x + w - 3.5f * d, y + h / 2f, 2.5f * d, col, d, fa)
                return
            }
            // small control: rounded plate, node at the leading end if there's room
            CollectiveDraw.plate(cv, x, y, x + w, y + h, color, d)
            if (w >= 80 * d && h >= 22 * d && kind != Ui.PILL) {
                val nr = min(h * 0.2f, 7 * d)
                val cx = if (kind == Ui.RIGHT) x + w - h / 2f else x + h / 2f
                CollectiveDraw.node(cv, cx, y + h / 2f, nr, col, d, fa)
            }
        }
    }

    /** A divider/header rule: solid bar for Command and Imperial, a trace for Collective, a thin rule for the eras. */
    @JvmStatic
    fun rule(c: Context, color: Int): Drawable {
        if (era()) return EraDraw.rule(c, color)
        if (!collective()) return ColorDrawable(color)
        return NodeCell(c, color, Ui.PILL)
    }

    /** Largest corner radius (dp) of a 23rd-century key or tray: square, barely softened. */
    const val ERA23_RADIUS = 3f

    /** Corner radius (dp) for trays and dialog outlines in the current faction. */
    @JvmStatic
    fun trayRadius(command: Float): Float = trayRadiusFor(faction, command)

    /**
     * Pure form of [trayRadius]: Imperial 1, Collective 22, 23rd century at most
     * [ERA23_RADIUS], 25th century 0 (its corners are cut by [EraDraw] instead), Command
     * the caller's radius.
     */
    @JvmStatic
    fun trayRadiusFor(f: Int, command: Float): Float = when (f) {
        ERA23 -> min(command, ERA23_RADIUS)
        ERA25 -> 0f
        IMPERIAL -> 1f
        COLLECTIVE -> 22f
        else -> command
    }

    /** Frame behind a tile icon: Imperial hexagon optic; none for Command, Collective (the node is the frame) and the eras. */
    @JvmStatic
    fun iconFrame(c: Context, color: Int): Drawable? {
        if (command() || collective()) return null
        val dd = Ui.density(c)
        val k = imperial()
        return object : Drawable() {
            val pt = Paint(Paint.ANTI_ALIAS_FLAG)
            val path = Path()

            override fun draw(cv: Canvas) {
                val b = bounds
                val cx = b.exactCenterX()
                val cy = b.exactCenterY()
                val rr = min(b.width(), b.height()) / 2f - dd
                pt.style = Paint.Style.FILL
                pt.color = if (Ui.cloak) Color.BLACK else mix(bg(), color, 0.18f)
                path.reset()
                if (k) {
                    for (i in 0 until 6) {
                        val a = Math.PI / 3 * i
                        val x = cx + cos(a).toFloat() * rr
                        val y = cy + sin(a).toFloat() * rr
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                } else {
                    path.addCircle(cx, cy, rr, Path.Direction.CW)
                }
                cv.drawPath(path, pt)
                pt.style = Paint.Style.STROKE
                pt.strokeWidth = 1.4f * dd
                pt.color = Ui.alpha(glow(color), if (Ui.cloak) 0.6f else 0.9f)
                cv.drawPath(path, pt)
                if (!k) {
                    // second ring: the Collective disk glyph
                    pt.strokeWidth = dd
                    pt.color = Ui.alpha(glow(color), 0.45f)
                    cv.drawCircle(cx, cy, rr - 3.5f * dd, pt)
                }
            }

            override fun setAlpha(a: Int) {
                pt.alpha = a
            }

            override fun setColorFilter(cf: ColorFilter?) {}

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
        }
    }

    /**
     * Backdrop for the "pattern" background option: Imperial tactical grid, Collective conduit matrix. The
     * Collective network is generated deterministically from cell indices so it never shifts between frames; it simply tiles further on larger screens.
     */
    @JvmStatic
    fun backdrop(c: Context): Drawable {
        val dd = Ui.density(c)
        val k = imperial()
        return object : Drawable() {
            override fun draw(cv: Canvas) {
                val b = bounds
                cv.drawColor(bg())
                val dim = if (Ui.cloak) 0.4f else 1f
                if (k) {
                    // dark red triangle lattice, as in the Imperial panel fills
                    ImperialDraw.grid(cv, RectF(b), 40 * dd, imperialTheme.grid, 0.6f * dim, dd)
                    return
                }
                val line = collectiveLine()
                val lit = collectiveLit()
                // a faint, sparse trace network: nodes scattered on a loose grid, linked by curves
                val cell = 92 * dd
                val cols = (b.width() / cell).toInt() + 2
                val rows = (b.height() / cell).toInt() + 2
                val xs = FloatArray(cols * rows)
                val ys = FloatArray(cols * rows)
                for (j in 0 until rows) for (i in 0 until cols) {
                    val n = j * cols + i
                    val h = (i * 7349 + j * 1931) and 0xFFFF
                    xs[n] = b.left + (i + 0.15f + (h % 70) / 100f) * cell
                    ys[n] = b.top + (j + 0.15f + ((h / 70) % 70) / 100f) * cell
                }
                for (j in 0 until rows) for (i in 0 until cols) {
                    val n = j * cols + i
                    val h = (i * 7349 + j * 1931) and 0xFFFF
                    if (i + 1 < cols && h % 3 != 0) {
                        CollectiveDraw.curveTrace(cv, xs[n], ys[n], xs[n + 1], ys[n + 1], if (h % 2 == 0) 0.12f else -0.1f, line, dd, 0.16f * dim)
                    }
                    if (j + 1 < rows && h % 4 == 1) {
                        CollectiveDraw.curveTrace(cv, xs[n], ys[n], xs[n + cols], ys[n + cols], 0.1f, line, dd, 0.14f * dim)
                    }
                }
                for (n in xs.indices) {
                    val h = (n * 2654435) ushr 8
                    when (h % 5) {
                        0 -> CollectiveDraw.ringNode(cv, xs[n], ys[n], (6 + h % 7) * dd, line, dd, 0.22f * dim)
                        1 -> CollectiveDraw.node(cv, xs[n], ys[n], 3.5f * dd, lit, dd, 0.15f * dim)
                        else -> CollectiveDraw.via(cv, xs[n], ys[n], 2.2f * dd, line, dd, 0.22f * dim)
                    }
                }
            }

            override fun setAlpha(a: Int) {}

            override fun setColorFilter(cf: ColorFilter?) {}

            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = PixelFormat.OPAQUE
        }
    }

    /** Name of the "pattern" background option in CONFIG. */
    @JvmStatic
    fun patternName(): String = if (imperial()) "TRIANGLE GRID" else if (collective()) "TRACE NETWORK" else "STARFIELD"

    // ---- header ornaments (drawn behind the chronometer) ---------------------------------------------

    /** Ornament behind the home clock (23rd-century lamp bank, 25th-century reticle), or null for Command. */
    @JvmStatic
    fun headerOrnament(c: Context, p: Palette): Drawable? = when {
        era() -> EraDraw.ornament(c, p)
        imperial() -> TacticalDisplay(c, p)
        collective() -> NodeNet(c, p)
        else -> null
    }

    /**
     * Imperial header display, behind the clock: a long double-lined octagon frame with a faint
     * triangle lattice inside, stacked triangle clusters at each end and a ▲▼▲ row along the
     * bottom edge.
     */
    class TacticalDisplay(c: Context, @Suppress("UNUSED_PARAMETER") p: Palette) : Drawable() {
        private val d: Float = Ui.density(c)
        private var am = 1f
        private val outer = Path()
        private val inner = Path()

        override fun draw(cv: Canvas) {
            val b = bounds
            val a = (if (Ui.cloak) 0.5f else 1f) * am
            val l = b.left + 2 * d
            val t = b.top + 2 * d
            val r = b.right - 2 * d
            val bt = b.bottom - 2 * d
            val k = min((bt - t) * 0.32f, 26 * d)
            ImperialDraw.octagon(outer, l, t, r, bt, k, true, true)
            ImperialDraw.octagon(inner, l + 4 * d, t + 4 * d, r - 4 * d, bt - 4 * d, k - 2 * d, true, true)
            cv.save()
            cv.clipPath(inner)
            ImperialDraw.grid(cv, RectF(b), 22 * d, ImperialDraw.RED, 0.13f * a, d)
            cv.restore()
            ImperialDraw.frame(cv, outer, inner, ImperialDraw.RED, d, 0.55f * a)
            val s = min((bt - t) * 0.16f, 13 * d)
            ImperialDraw.cluster(cv, l + 8 * d + s, (t + bt) / 2f, s, 0.75f * a)
            ImperialDraw.cluster(cv, r - 8 * d - s, (t + bt) / 2f, s, 0.75f * a)
            val rw = min((r - l) * 0.3f, 90 * d)
            ImperialDraw.row(cv, (l + r) / 2f - rw / 2f, bt - 13 * d, rw, 7 * d,
                intArrayOf(ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED), 0.7f * a)
        }

        override fun setAlpha(al: Int) {
            am = al / 255f
        }

        override fun setColorFilter(cf: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /**
     * Collective header display: a ringed hub with long crosshair lines, satellites on curved traces and
     * strings of data dots, after the Collective wall displays. Kept dim: the clock sits on top of it.
     */
    class NodeNet(c: Context, private val p: Palette) : Drawable() {
        private val d: Float = Ui.density(c)
        private var am = 1f

        override fun draw(cv: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val a = (if (Ui.cloak) 0.25f else 0.42f) * am
            val cx = b.left + w * 0.5f
            val cy = b.top + h * 0.5f
            val hr = h * 0.42f
            val line = p.c(0)
            val hi = p.c(1)
            // crosshair
            val pth = CollectiveDraw.tmp()
            pth.moveTo(b.left + w * 0.02f, cy)
            pth.lineTo(b.right - w * 0.02f, cy)
            pth.moveTo(cx, b.top.toFloat())
            pth.lineTo(cx, b.bottom.toFloat())
            CollectiveDraw.trace(cv, pth, hi, d, 0.25f * a)
            // satellites on traces from the hub rim
            for (i in N.indices) {
                val x = b.left + N[i][0] * w
                val y = b.top + N[i][1] * h
                val r = N[i][2] * d
                val ang = atan2((y - cy).toDouble(), (x - cx).toDouble())
                val sx = cx + cos(ang).toFloat() * hr
                val sy = cy + sin(ang).toFloat() * hr
                CollectiveDraw.curveTrace(cv, sx, sy, x, y, if (i % 2 == 0) 0.18f else -0.15f, line, d, 0.7f * a)
                if (i % 3 == 2) CollectiveDraw.via(cv, x, y, r * 0.6f, line, d, a)
                else CollectiveDraw.ringNode(cv, x, y, r, hi, d, a)
            }
            CollectiveDraw.dots(cv, b.left + w * 0.04f, b.top + h * 0.08f, 7, hi, d, 0x5B, 0.8f * a)
            CollectiveDraw.dots(cv, b.right - w * 0.28f, b.bottom - h * 0.06f, 8, hi, d, 0x3D, 0.8f * a)
            // the hub: concentric rings, hollow so the time stays readable
            CollectiveDraw.ringNode(cv, cx, cy, hr, line, d, 0.8f * a)
            CollectiveDraw.ringNode(cv, cx, cy, hr * 0.55f, hi, d, 0.6f * a)
        }

        override fun setAlpha(al: Int) {
            am = al / 255f
        }

        override fun setColorFilter(cf: ColorFilter?) {}

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        private companion object {
            /** Satellites in unit space (asymmetric, hand-placed): x, y, radius in dp. */
            val N = arrayOf(
                floatArrayOf(0.10f, 0.22f, 9f), floatArrayOf(0.18f, 0.80f, 6f), floatArrayOf(0.34f, 0.12f, 5f),
                floatArrayOf(0.70f, 0.10f, 7f), floatArrayOf(0.86f, 0.30f, 10f), floatArrayOf(0.80f, 0.86f, 6f),
                floatArrayOf(0.95f, 0.66f, 4f), floatArrayOf(0.04f, 0.55f, 4f),
            )
        }
    }
}
