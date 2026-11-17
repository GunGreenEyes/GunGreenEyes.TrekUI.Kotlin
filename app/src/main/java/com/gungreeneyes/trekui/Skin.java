package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;

/**
 * The interface factions. They share every launcher function (apps, tiles, dock, panels, shade,
 * dialer, sounds, devices…) but draw with their own geometry, type and colour:
 *
 * <pre>
 *  COMMAND (0)  console: curved elbows, pill keys, solid colour segments, Antonio.
 *  IMPERIAL    (1)  Imperial tactical: chamfered/oblique plates, hex targeting optics, diamond
 *                  markers, copper hairlines, Oxanium / Warrior Blade. Never an console curve.
 *  COLLECTIVE       (2)  Collective: square smoked-acrylic modules with a backlit edge bar, conduit lines,
 *                  circular node glyphs, telemetry dots, Share Tech. Never an elbow or a hex.
 *  Pipe Lounge (6.0, drawn by {@link PipeDraw}):
 *  TOBACCONIST (3) Victorian cabinet: walnut, brass and oxblood.
 *  LOUNGE      (4) Art Deco smoking room: lacquer, gold and ember.
 *  JOURNAL     (5) Tobacco-barn ledger: leather, kraft and red ink.
 *  Painted scenes (6.1, home drawn by {@link PipeScene}, every other screen borrows a material
 *  family through {@link #mat()}):
 *  BRIAR    (6)  Ember &amp; Briar fireside study      → walnut/brass (3)
 *  LEDGER   (7)  The Tobacconist's Ledger folio     → leather/kraft (5)
 *  MIDNIGHT (8)  Midnight Ember Art Deco lounge     → lacquer/gilt (4)
 *  More Command eras (6.3, drawn by {@link EraDraw}; {@link #command()} is true for them, so every
 *  Command layout, cascade and sound profile applies, only geometry, type and colour differ):
 *  ERA23    (9)  23rd-century console: chunky square keys, primary colours, blinking lamps, Josefin Sans.
 *  ERA25   (10)  25th-century glass: thin translucent blue/grey layers, cut corners, fine ticks, light Antonio.
 * </pre>
 *
 * The current faction is the static {@link #faction}, set from {@link Prefs#faction()} whenever a
 * screen is built ({@link Prefs#palette()} and {@link #load}). Palettes for Imperial/Collective are the ones
 * measured from the faction artwork bundle (theme.json + SVGs).
 */
final class Skin {
    static final int COMMAND = 0, IMPERIAL = 1, COLLECTIVE = 2;
    static final int TOBACCONIST = 3, LOUNGE = 4, JOURNAL = 5;
    static final int BRIAR = 6, LEDGER = 7, MIDNIGHT = 8;
    /** The extra Command eras (see {@link Era}). */
    static final int ERA23 = 9, ERA25 = 10;
    /** First and last pipe-lounge faction ids. */
    static final int FIRST_PIPE = TOBACCONIST, LAST_PIPE = MIDNIGHT;

    static final String[] NAMES = {"COMMAND", "IMPERIAL", "COLLECTIVE", "THE TOBACCONIST", "EMBER LOUNGE", "BLEND JOURNAL",
            "EMBER & BRIAR", "TOBACCONIST'S LEDGER", "MIDNIGHT EMBER", "CLASSIC CONSOLE", "NEXT CONSOLE"};
    static final String[] SUBTITLES = {"COMMAND • CURVED CONTROL RAILS", "IMPERIAL TACTICAL • CHAMFERED PLATES, HEX OPTICS",
            "COLLECTIVE • NETWORKED CELLS, NODE GLYPHS",
            "VICTORIAN CABINET • WALNUT, BRASS AND OXBLOOD", "ART DECO SMOKING ROOM • LACQUER, GOLD AND EMBER",
            "TOBACCO BARN LEDGER • LEATHER, KRAFT AND RED INK",
            "PAINTED SCENE • THE FIRESIDE SMOKING STUDY", "PAINTED SCENE • THE MERCHANT'S BOUND FOLIO",
            "PAINTED SCENE • THE ART DECO NIGHT LOUNGE",
            "23RD-CENTURY STYLE • SQUARE KEYS, PRIMARY LAMPS", "25TH-CENTURY STYLE • FINE LINES, LAYERED GLASS"};

    /** Current faction (set from prefs whenever a screen is built). */
    static int faction = COMMAND;

    private Skin() {}

    /** Reads the current faction from preferences. */
    static void load(Context c) { faction = Prefs.get(c).faction(); }
    /** The Command family: console proper and the two extra eras (they share every Command layout). */
    static boolean command() { return commandFamily(faction); }
    /** True for Command console and the 23rd/25th-century eras. */
    static boolean commandFamily(int f) { return f == COMMAND || eraFaction(f); }
    /** One of the extra Command eras (9, 10). */
    static boolean era() { return eraFaction(faction); }
    static boolean eraFaction(int f) { return f == ERA23 || f == ERA25; }
    /** 23rd-century console. */
    static boolean era23() { return faction == ERA23; }
    /** 25th-century glass. */
    static boolean era25() { return faction == ERA25; }
    static boolean imperial() { return faction == IMPERIAL; }
    static boolean collective() { return faction == COLLECTIVE; }

    /** One of the pipe-lounge interfaces (3-8). */
    static boolean pipe() { return pipeFaction(faction); }
    static boolean tob() { return faction == TOBACCONIST; }
    static boolean lounge() { return faction == LOUNGE; }
    static boolean journal() { return faction == JOURNAL; }

    /** The three painted-scene interfaces (6-8): artwork homes with live overlays. */
    static boolean scene() { return faction >= BRIAR && faction <= MIDNIGHT; }

    /** Material family used for every screen apart from the home: 3 walnut/brass, 4 lacquer/gilt, 5 leather/kraft. */
    static int mat() { return mat(faction); }

    /**
     * Maps a faction to the material family its non-home screens use: scenes borrow a 6.0 family
     * (Briar → Tobacconist, Ledger → Journal, Midnight → Lounge); every other faction is itself.
     */
    static int mat(int f) {
        return f == BRIAR ? TOBACCONIST : f == LEDGER ? JOURNAL : f == MIDNIGHT ? LOUNGE : f;
    }

    static boolean mTob() { return mat() == TOBACCONIST; }
    static boolean mLounge() { return mat() == LOUNGE; }
    static boolean mJournal() { return mat() == JOURNAL; }

    /** True for the pipe-lounge factions (3-8), false for the starship interfaces (0-2, 9-10). */
    static boolean pipeFaction(int f) { return f >= FIRST_PIPE && f <= LAST_PIPE; }

    /** Palette of a pipe faction (anything above LEDGER falls through to Midnight). */
    static Palette pipePalette(int f) {
        return f == TOBACCONIST ? TOB_PALETTE : f == LOUNGE ? LOUNGE_PALETTE : f == JOURNAL ? JOURNAL_PALETTE
                : f == BRIAR ? BRIAR_PALETTE : f == LEDGER ? LEDGER_PALETTE : MIDNIGHT_PALETTE;
    }

    /** Glyph style folder for the pipe interfaces (engraved, deco, rubber stamp); plain glyphs otherwise. */
    static String glyphFolder() {
        return mTob() ? "pipe/glyphs/engraved/" : mLounge() ? "pipe/glyphs/deco/" : mJournal() ? "pipe/glyphs/stamp/" : "glyphs/";
    }

    /** Asset folder holding the faction's tile/badge art. */
    static String artFolder() {
        if (faction == BRIAR) return "pipe/art/briar/";
        if (faction == LEDGER) return "pipe/art/ledger/";
        if (faction == MIDNIGHT) return "pipe/art/midnight/";
        if (tob()) return "pipe/art/tobacconist/";
        if (lounge()) return "pipe/art/lounge/";
        if (journal()) return "pipe/art/journal/";
        return imperial() ? "faction/imperial/" : "faction/collective/";
    }

    /** Ink for pictograms sitting on a tile: dark engraving on parchment, gold on lacquer, red stamp on kraft. */
    static int tileInk() {
        if (Ui.cloak) return mTob() ? 0xB3C9A04E : mLounge() ? 0xB3D4AF37 : 0xB3C9A063;
        return mTob() ? 0xFF2A170C : mLounge() ? 0xFFE9CF7F : 0xFFA8322A;
    }

    /** Navigation names on the home screen: 0 apps, 1 search, 2 config. */
    static String nav(int i) {
        if (faction == BRIAR) return new String[]{"CABINET", "SEEK", "STUDY"}[i];
        if (faction == LEDGER) return new String[]{"INDEX", "SEARCH", "SETTINGS"}[i];
        if (faction == MIDNIGHT) return new String[]{"DIRECTORY", "SEEK", "CONCIERGE"}[i];
        if (tob()) return new String[]{"CABINET", "INQUIRE", "WORKSHOP"}[i];
        if (lounge()) return new String[]{"THE MENU", "SEEK", "CONCIERGE"}[i];
        if (journal()) return new String[]{"CELLAR", "FIND", "TOOLS"}[i];
        return new String[]{"APPS", "SEARCH", "CONFIG"}[i];
    }

    /** Title of the app drawer. */
    static String drawerTitle() {
        if (scene()) return faction == BRIAR ? "THE STUDY CABINET" : faction == LEDGER ? "THE INDEX" : "THE DIRECTORY";
        return tob() ? "THE CABINET" : lounge() ? "THE MENU" : journal() ? "THE CELLAR" : "APPLICATIONS";
    }

    // ---- colours ---------------------------------------------------------------------------------

    static final Palette BRIAR_PALETTE = new Palette("EMBER & BRIAR",
            new String[]{"#D8A658", "#D9967A", "#F0DDB4", "#C08A52", "#E8B86A", "#CDAA80"}, "#F9DEAE", "#D9BC93", "#E8613A", "#33150F");
    static final Palette LEDGER_PALETTE = new Palette("TOBACCONIST'S LEDGER",
            new String[]{"#E9D3A6", "#D49A78", "#D9AA55", "#C9A063", "#C29A66", "#E2CFA6"}, "#F7E6C4", "#D2B07E", "#E04A2A", "#3A1D12");
    static final Palette MIDNIGHT_PALETTE = new Palette("MIDNIGHT EMBER",
            new String[]{"#D8A658", "#C8352E", "#B07D45", "#F0D08A", "#E2572B", "#E6D3AE"}, "#F9DEAE", "#C9A35A", "#FF5A36", "#25191A");
    /** Tobacconist: brass, copper-rose, parchment, bronze, pale gold, tan. Light metals and papers carry dark engraved ink. */
    static final Palette TOB_PALETTE = new Palette("THE TOBACCONIST",
            new String[]{"#C9A04E", "#C98466", "#DCC6A0", "#B07A45", "#E3C67C", "#C2A27A"}, "#F1E3C2", "#C9A87A", "#E0573A", "#20130C");
    /** Ember Lounge: gilt, lacquer red, bronze, pale gold, ember, cream; gold lettering on black lacquer. */
    static final Palette LOUNGE_PALETTE = new Palette("EMBER LOUNGE",
            new String[]{"#D4AF37", "#C8352E", "#B07D45", "#EBD28B", "#E2572B", "#E6D3AE"}, "#F3E2B4", "#C9A35A", "#FF5A36", "#120A08");
    /** Blend Journal: kraft, cream, Virginia gold, rosy tan, bronze tan, light tan; dark ink on paper. */
    static final Palette JOURNAL_PALETTE = new Palette("BLEND JOURNAL",
            new String[]{"#C9A063", "#E2CFA6", "#D9AA55", "#C88A5E", "#B8905C", "#D7B98A"}, "#F0DDB4", "#C7A372", "#E04A2A", "#24160D");
    /**
     * Imperial Tactical, after the Imperial screens: blood red, orange, warning yellow, deep red,
     * red-orange, amber (brighter and redder than the first artwork bundle).
     */
    static final Palette IMPERIAL_PALETTE = new Palette("IMPERIAL TACTICAL",
            new String[]{"#D8231E", "#F0821E", "#F5C830", "#A3161A", "#E8562A", "#F2A43A"},
            "#F5C830", "#F0821E", "#FF3B2E", "#140606");
    /** The Collective: active green, highlight, matrix, grid, muted, conduit. */
    static final Palette COLLECTIVE_PALETTE = new Palette("THE COLLECTIVE",
            new String[]{"#79C98B", "#B1DC86", "#2B704A", "#325846", "#73887F", "#123B2B"},
            "#C4D7C0", "#73887F", "#E0A45C", "#0C1714");

    /** Imperial warning yellow and Collective highlight green: the colours dark tones are lifted toward. */
    static final int IMPERIAL_GLOW = 0xFFF5C830, COLLECTIVE_GLOW = 0xFFB1DC86;
    /** Relative luminance below which {@link #glow} lifts a colour. */
    static final float GLOW_LUMINANCE = 0.42f;
    private static final int COLLECTIVE_LINE = 0xFF2B704A, COLLECTIVE_LIT = 0xFF79C98B;

    /** Screen background behind everything. */
    static int bg() {
        if (pipe()) {
            if (scene()) return faction == BRIAR ? 0xFF140C0B : faction == LEDGER ? 0xFF1C0E08 : 0xFF100A0B;
            return mTob() ? 0xFF120A06 : mLounge() ? 0xFF0A0605 : 0xFF170E08;
        }
        if (era()) return era23() ? Era.ERA23_BG : Era.ERA25_BG;
        return imperial() ? 0xFF050303 : collective() ? 0xFF030706 : Color.BLACK;
    }

    /** Tray / dialog / card surface. */
    static int surface() {
        if (pipe()) return mTob() ? 0xFF1E120B : mLounge() ? 0xFF140C0A : 0xFF22150C;
        if (era()) return era23() ? 0xFF17130E : 0xFF0B121A;
        return imperial() ? 0xFF140606 : collective() ? 0xFF06100C : 0xFF0E0C16;
    }

    /** Grid and hairline colour. */
    static int grid() {
        if (pipe()) return mTob() ? 0xFF342317 : mLounge() ? 0xFF2A1A12 : 0xFF33261A;
        if (era()) return era23() ? 0xFF2A2620 : 0xFF1E2C3A;
        return imperial() ? 0xFF4A0E0E : collective() ? 0xFF123B2B : 0xFF1A1826;
    }

    /** Secondary detail colour (ticks, inactive marks). */
    static int detail() {
        if (pipe()) return mTob() ? 0xFF4F3420 : mLounge() ? 0xFF5E3A17 : 0xFF57381D;
        if (era()) return era23() ? 0xFF3A342A : 0xFF2A3C4E;
        return imperial() ? 0xFF2A0808 : collective() ? 0xFF325846 : 0xFF2A2840;
    }

    /** Lettering on a control: black on Command's solid colour; light on the factions' dark plates. */
    static int ink() {
        if (pipe()) {
            if (Ui.cloak) return mTob() ? 0xE6C9A04E : mLounge() ? 0xE6D4AF37 : 0xE6C9A063;
            return mTob() ? 0xFF2A170C : mLounge() ? 0xFFEED9A0 : 0xFF2E1A0E;
        }
        if (Ui.cloak) return command() ? Ui.CLOAK_INK : imperial() ? 0xE6F0821E : 0xE679C98B;
        if (era25()) return Era.ERA25_INK;
        return imperial() ? 0xFFF5B83A : collective() ? 0xFFC4D7C0 : Color.BLACK;
    }

    // ---- type ------------------------------------------------------------------------------------

    /**
     * Imperial lettering: BLADE_MIXED (default) sets titles, labels and keys in Warrior Blade (the
     * launcher's own Imperial-style English font) and longer text in Oxanium; BLADE_ALL uses Warrior
     * Blade everywhere; PLAIN is Oxanium only.
     */
    static final int BLADE_MIXED = 0, BLADE_ALL = 1, PLAIN = 2;
    static int lettering = BLADE_MIXED;

    /** Asset path of the heavy typeface, or null for Command's Antonio. */
    static String fontHeavy() {
        if (pipe()) {
            if (scene()) return faction == BRIAR ? "fonts/IMFellEnglishSC.ttf" : faction == LEDGER ? "fonts/CormorantGaramond-Bold.ttf" : "fonts/Federo-Regular.ttf";
            return tob() ? "fonts/Cinzel-SemiBold.ttf" : lounge() ? "fonts/Limelight-Regular.ttf" : "fonts/Rye-Regular.ttf";
        }
        if (imperial()) return lettering == PLAIN ? "fonts/Oxanium-Bold.ttf" : "fonts/WarriorBlade-Bold.ttf";
        if (era23()) return "fonts/JosefinSans-SemiBold.ttf";
        return collective() ? "fonts/ShareTech-Regular.ttf" : null;
    }

    /** Asset path of the regular typeface, or null for Command's Antonio. */
    static String fontRegular() {
        if (pipe()) {
            if (scene()) return faction == BRIAR ? "fonts/IMFellEnglish-Regular.ttf" : faction == LEDGER ? "fonts/CormorantGaramond-SemiBold.ttf" : "fonts/JosefinSans-Regular.ttf";
            return tob() ? "fonts/IMFellEnglish-Regular.ttf" : lounge() ? "fonts/JosefinSans-SemiBold.ttf" : "fonts/SpecialElite-Regular.ttf";
        }
        if (imperial()) return lettering == BLADE_ALL ? "fonts/WarriorBlade-Regular.ttf" : "fonts/Oxanium-SemiBold.ttf";
        if (era23()) return "fonts/JosefinSans-Regular.ttf";
        return collective() ? "fonts/ShareTech-Regular.ttf" : null;
    }

    /**
     * Antonio weight for interfaces that set Antonio at their own weights (fontRegular() is null and
     * the faction is not Command): the 25th century's light 300 / regular 500 lettering.
     */
    static int antonioWeight(boolean heavy) {
        return heavy ? 500 : 300;
    }

    /** The faction typefaces are wider than condensed Antonio: scale text so layouts still fit. */
    static float textScale() {
        if (pipe()) {
            if (scene()) return faction == BRIAR ? 0.86f : faction == LEDGER ? 0.94f : 0.84f;
            return tob() ? 0.80f : lounge() ? 0.78f : 0.76f;
        }
        // Warrior Blade runs ~12% narrower than Oxanium at the same height, so it can be set larger
        if (imperial()) return lettering == PLAIN ? 0.80f : lettering == BLADE_ALL ? 0.90f : 0.84f;
        if (era23()) return 0.80f;
        return collective() ? 0.90f : 1f;
    }

    /** Letter spacing (em) for the faction's lettering. */
    static float letterSpacing() {
        if (pipe()) {
            if (scene()) return faction == BRIAR ? 0.03f : faction == LEDGER ? 0.02f : 0.08f;
            return tob() ? 0.04f : lounge() ? 0.10f : 0.01f;
        }
        if (era()) return era23() ? 0.05f : 0.09f;
        return imperial() ? 0.02f : collective() ? 0.06f : 0.03f;
    }

    // ---- default wording (English labels; no claimed Imperial or Collective language) ---------------------

    /** Home title block. */
    static String title() {
        if (pipe()) {
            if (scene()) return faction == BRIAR ? "EMBER & BRIAR" : faction == LEDGER ? "THE TOBACCONIST'S LEDGER" : "MIDNIGHT EMBER";
            return tob() ? "THE TOBACCONIST" : lounge() ? "EMBER LOUNGE" : "BLEND JOURNAL";
        }
        if (era()) return "CONSOLE";
        return imperial() ? "TACTICAL" : collective() ? "NODE CLUSTER 01" : "TREKUI";
    }

    /** Heading above the home tiles. */
    static String ops() {
        if (pipe()) return tob() ? "THE CABINET" : lounge() ? "HOUSE SELECTIONS" : "TODAY'S ROTATION";
        if (era23()) return "BRIDGE FUNCTIONS";
        return imperial() ? "COMMAND FUNCTIONS" : collective() ? "LOCAL INTERFACE NODES" : "OPERATIONS";
    }

    /** Footer line of the home frame. */
    static String foot() {
        if (pipe()) return tob() ? "PURVEYOR OF FINE BRIAR" : lounge() ? "MEMBERS ONLY • EST. 1928" : "CURED • AGED • SAVORED";
        if (era()) return era23() ? "PRIMARY CONTROL STATION" : "FLEET ACCESS TERMINAL";
        return imperial() ? "IMPERIAL TERMINAL" : collective() ? "COLLECTIVE LINK" : "PERSONAL ACCESS TERMINAL";
    }

    /** Default header (ship registry) line; Command keeps the caller's text. */
    static String ship(String command) {
        if (faction == COMMAND) return command;
        if (era()) return era23() ? "BRIDGE STATION 01 • DECK 1" : "FLEET OPERATIONS • SECTOR 7";
        if (pipe()) {
            if (scene()) return faction == BRIAR ? "THE GENTLEMAN'S SMOKING STUDY" : faction == LEDGER ? "RECORDS • CATALOGS • INVENTORY • BLENDS" : "THE SMOKING LOUNGE";
            return tob() ? "FINE TOBACCOS & BRIAR PIPES" : lounge() ? "SMOKING ROOM • NUMBER FOURTEEN" : "A LOG OF BLENDS & BOWLS";
        }
        return imperial() ? "WARRIOR CONTROL / MOBILE TERMINAL" : "COLLECTIVE / NODE ACCESS";
    }

    /** Sub-heading used on library/reveal screens. */
    static String library() {
        if (pipe()) {
            if (scene()) return faction == BRIAR ? "BY THE FIRE, WITH BRIAR AND LEAF" : faction == LEDGER ? "RECORDS, CATALOGS AND SPECIMENS" : "AFTER HOURS • PRIVATE LOUNGE";
            return tob() ? "CABINET OF BRIAR, LEAF AND BRASS" : lounge() ? "LACQUER, GOLD AND THE EVENING EMBER" : "FILL • TAMP • CHAR • LIGHT • SAVOR";
        }
        if (era()) return era23() ? "COMPUTER ACCESS / BRIDGE STATION" : "LIBRARY ACCESS / FLEET NETWORK";
        return imperial() ? "IMPERIAL TACTICAL / WARRIOR CONTROL" : collective() ? "COLLECTIVE / NODE ACCESS / NETWORK LINK"
                : "CENTRAL COMPUTER ACCESS / CONTROL SYSTEM";
    }

    /** Asset folder of the faction's bundled wallpapers (none for Command and the pipe interfaces). */
    static String wallpaperFolder() {
        if (pipe()) return null;
        return imperial() ? "faction/imperial/" : collective() ? "faction/collective/" : null;
    }

    // ---- colour maths (pure, no android.graphics.Color so it runs in JVM tests) ------------------

    /** Linear blend of two colours' RGB channels; {@code t} = 0 gives {@code a}, 1 gives {@code b}. Result is opaque. */
    static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bgc = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000
                | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bgc - ag) * t) << 8)
                | Math.round(ab + (bb - ab) * t);
    }

    /** Rec. 709 relative luminance of a colour's RGB channels, 0..1 (gamma-encoded values, as the original). */
    static float luminance(int color) {
        return (0.2126f * ((color >> 16) & 0xFF) + 0.7152f * ((color >> 8) & 0xFF) + 0.0722f * (color & 0xFF)) / 255f;
    }

    /**
     * Pure form of {@link #glow}: a colour darker than {@link #GLOW_LUMINANCE} is mixed toward
     * {@code highlight} (by up to 75%, more for darker colours); alpha is preserved.
     */
    static int glowToward(int color, int highlight) {
        float lum = luminance(color);
        if (lum >= GLOW_LUMINANCE) return color;
        return (color & 0xFF000000) | (mix(color, highlight, Math.min(0.75f, (GLOW_LUMINANCE - lum) * 2.2f)) & 0x00FFFFFF);
    }

    /**
     * Edge/glyph version of a palette colour: the darker faction tones (Collective matrix and conduit
     * greens, Imperial bronze) are lifted toward the faction's light text colour so outlines and
     * pictograms stay readable. Fills keep the original tone. Command and pipe colours pass through.
     */
    static int glow(int color) {
        if (command() || pipe()) return color;
        return glowToward(color, imperial() ? IMPERIAL_GLOW : COLLECTIVE_GLOW);
    }

    // ---- controls ---------------------------------------------------------------------------------

    /** Background for a control in the current faction (Command keeps the plain GradientDrawable). */
    static Drawable cell(Context c, int color, int kind, float radiusDp) {
        if (pipe()) return PipeDraw.cell(c, color, kind);
        if (imperial()) return new Chamfer(c, color, kind);
        return new NodeCell(c, color, kind);
    }

    /** Shared paints and state for the Imperial/Collective control drawables. */
    private abstract static class Base extends Drawable {
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), mark = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF r = new RectF();
        /** Pixels per (zoomed) dp. */
        final float d;
        final int color, kind;

        Base(Context c, int color, int kind) {
            this.d = Ui.density(c);
            this.color = color;
            this.kind = kind;
            boolean solid = Color.alpha(color) > 0x80;
            fill.setStyle(Paint.Style.FILL);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, 1.4f * d));
            if (Ui.cloak) {
                fill.setColor(Color.BLACK);
                stroke.setColor(Ui.alpha(color, 0.65f));
                mark.setColor(Ui.alpha(color, 0.65f));
            } else {
                // dark plate tinted by the control's colour; the colour itself becomes edge and marker
                fill.setColor(solid ? mix(surface(), color, imperial() ? 0.10f : 0.13f) : color);
                stroke.setColor(solid ? Ui.alpha(glow(color), 0.85f) : Color.TRANSPARENT);
                mark.setColor(solid ? glow(color) : Color.TRANSPARENT);
            }
        }

        @Override public void setAlpha(int a) { fill.setAlpha(a); stroke.setAlpha(a); mark.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * Imperial control: a hollow long octagon (45° cuts on every corner; only the outer side for
     * LEFT/RIGHT end caps, pointed ends for thin PILL bars) in a heavy blood-red line on black.
     * Large ones are double-lined, carry a faint triangle lattice inside, a solid triangle marker at
     * the leading edge and a short ▲▼▲ row in the corner.
     */
    static final class Chamfer extends Base {
        private final Path inner = new Path();
        private final RectF rr = new RectF();

        Chamfer(Context c, int color, int kind) { super(c, color, kind); }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            if (w < 2 || h < 2) return;
            boolean faint = Color.alpha(color) <= 0x80;
            int col = glow(faint ? (color | 0xFF000000) : color);
            float sw = 2.4f * d, hs = sw / 2;
            float x = b.left + hs, y = b.top + hs, x2 = b.right - hs, y2 = b.bottom - hs;
            float k = Math.min((y2 - y) * 0.36f, Math.min((x2 - x) * 0.22f, 16 * d));
            boolean big = kind != Ui.PILL && h > 40 * d && w > 110 * d;
            if (kind == Ui.PILL) {
                float p = Math.min((y2 - y) * 0.5f, (x2 - x) * 0.2f);
                path.reset();
                path.moveTo(x + p, y);
                path.lineTo(x2 - p, y);
                path.lineTo(x2, (y + y2) / 2);
                path.lineTo(x2 - p, y2);
                path.lineTo(x + p, y2);
                path.lineTo(x, (y + y2) / 2);
                path.close();
            } else {
                ImperialDraw.octagon(path, x, y, x2, y2, k, kind != Ui.RIGHT, kind != Ui.LEFT);
            }
            ImperialDraw.fill(cv, path, Ui.cloak ? Color.BLACK : mix(bg(), col, faint ? 0.03f : 0.07f));
            if (big && !Ui.cloak) {
                cv.save();
                cv.clipPath(path);
                rr.set(b.left, b.top, b.right, b.bottom);
                ImperialDraw.grid(cv, rr, 16 * d, ImperialDraw.RED, faint ? 0.08f : 0.13f, d);
                cv.restore();
            }
            Path in = null;
            if (big || (kind == Ui.SQUARE && h > 30 * d && w > 56 * d)) {
                float g = 3.6f * d;
                ImperialDraw.octagon(inner, x + g, y + g, x2 - g, y2 - g, Math.max(2, k - g * 0.4f), kind != Ui.RIGHT, kind != Ui.LEFT);
                in = inner;
            }
            ImperialDraw.frame(cv, path, in, col, d, faint ? 0.45f : 1f);
            if (faint) return;
            if (big) {
                // solid triangle marker at the leading edge (square plates), ▲▼▲ row in the lower corner
                // away from the cut end
                if (kind == Ui.SQUARE) ImperialDraw.tri(cv, x + 13 * d, (y + y2) / 2f, 9 * d, 10 * d, false, ImperialDraw.YELLOW, 0.95f);
                float rx = kind == Ui.RIGHT ? x + 8 * d : x2 - k - 36 * d;
                if (kind == Ui.LEFT) rx = x2 - 42 * d;
                ImperialDraw.row(cv, rx, y2 - 10 * d, 34 * d, 7 * d,
                        new int[]{ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED}, 0.95f);
            } else if (kind == Ui.SQUARE && h > 30 * d && w > 40 * d) {
                // side key: small triangle in the upper corner
                ImperialDraw.tri(cv, x + k + 7 * d, y + 9 * d, 8 * d, 7 * d, false, ImperialDraw.YELLOW, 0.9f);
            }
        }
    }

    /**
     * Collective control. Large controls (home tiles, sidebar keys) have no box at all: a glowing node
     * on a trace, floating on black, like the Collective displays. Small controls (buttons, chips,
     * code blocks) get a soft rounded plate with a node at the leading end. No corners anywhere.
     */
    static final class NodeCell extends Base {
        NodeCell(Context c, int color, int kind) { super(c, color, kind); }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            float w = b.width(), h = b.height(), x = b.left, y = b.top;
            if (w < 2 || h < 2) return;
            int col = Color.alpha(color) > 0x80 ? glow(color) : color;
            boolean faint = Color.alpha(color) <= 0x80; // empty slot / disabled
            float fa = faint ? 0.45f : 1f;
            int seed = Math.abs((int) (w * 31 + h * 17 + color));
            if (h >= 40 * d && w >= 120 * d && kind == Ui.SQUARE) {
                // home tile: node at the icon position (32 dp in), trace to a via at the far end
                float cx = x + 32 * d, cy = y + h / 2f, nr = Math.min(h * 0.38f, 18 * d);
                float ty = y + h - 8 * d, ex = x + w - 12 * d;
                CollectiveDraw.curveTrace(cv, cx + nr * 0.9f, cy + nr * 0.5f, x + w * 0.55f, ty, 0.12f, col, d, 0.8f * fa);
                Path t = CollectiveDraw.tmp();
                t.moveTo(x + w * 0.55f, ty);
                t.lineTo(ex - 4 * d, ty);
                CollectiveDraw.trace(cv, t, col, d, 0.8f * fa);
                CollectiveDraw.via(cv, ex, ty, 3.5f * d, col, d, fa);
                CollectiveDraw.dots(cv, ex - 34 * d, ty - 7 * d, 6, col, d, seed, fa);
                if (faint) CollectiveDraw.ringNode(cv, cx, cy, nr, col, d, 0.7f);
                else CollectiveDraw.node(cv, cx, cy, nr, col, d, 1f);
                return;
            }
            if (h >= 40 * d && w < 120 * d && kind == Ui.SQUARE) {
                // sidebar key: vertical trace through a node; label sits bottom-right
                float cx = x + w * 0.5f, cy = y + h * 0.36f, nr = Math.min(w * 0.2f, h * 0.2f);
                Path v = CollectiveDraw.tmp();
                v.moveTo(cx, y);
                v.lineTo(cx, cy - nr);
                CollectiveDraw.trace(cv, v, col, d, 0.6f * fa);
                CollectiveDraw.curveTrace(cv, cx, cy + nr, x + 6 * d, y + h, -0.18f, col, d, 0.6f * fa);
                CollectiveDraw.node(cv, cx, cy, nr, col, d, fa);
                return;
            }
            if (kind == Ui.PILL && h < 16 * d) {
                // thin bar: a trace with a via at each end
                Path t = CollectiveDraw.tmp();
                t.moveTo(x + 5 * d, y + h / 2f);
                t.lineTo(x + w - 5 * d, y + h / 2f);
                CollectiveDraw.trace(cv, t, col, d, fa);
                CollectiveDraw.via(cv, x + 3.5f * d, y + h / 2f, 2.5f * d, col, d, fa);
                CollectiveDraw.via(cv, x + w - 3.5f * d, y + h / 2f, 2.5f * d, col, d, fa);
                return;
            }
            // small control: rounded plate, node at the leading end if there's room
            CollectiveDraw.plate(cv, x, y, x + w, y + h, color, d);
            if (w >= 80 * d && h >= 22 * d && kind != Ui.PILL) {
                float nr = Math.min(h * 0.2f, 7 * d);
                float cx = kind == Ui.RIGHT ? x + w - h / 2f : x + h / 2f;
                CollectiveDraw.node(cv, cx, y + h / 2f, nr, col, d, fa);
            }
        }
    }

    /** A divider/header rule: solid bar for Command and Imperial, a trace for Collective, a material rule for pipe. */
    static Drawable rule(Context c, int color) {
        if (pipe()) return PipeDraw.rule(c, color);
        if (era()) return EraDraw.rule(c, color);
        if (!collective()) return new ColorDrawable(color);
        return new NodeCell(c, color, Ui.PILL);
    }

    /** Largest corner radius (dp) of a 23rd-century key or tray: square, barely softened. */
    static final float ERA23_RADIUS = 3f;

    /** Corner radius (dp) for trays and dialog outlines in the current faction. */
    static float trayRadius(float command) { return trayRadiusFor(faction, command); }

    /**
     * Pure form of {@link #trayRadius}: pipe families 4 (walnut) / 1 (lacquer) / 14 (leather),
     * Imperial 1, Collective 22, 23rd century at most {@link #ERA23_RADIUS}, 25th century 0 (its corners are
     * cut by {@link EraDraw} instead), Command the caller's radius.
     */
    static float trayRadiusFor(int f, float command) {
        if (pipeFaction(f)) {
            int m = mat(f);
            return m == TOBACCONIST ? 4 : m == LOUNGE ? 1 : 14;
        }
        if (f == ERA23) return Math.min(command, ERA23_RADIUS);
        if (f == ERA25) return 0;
        return f == IMPERIAL ? 1 : f == COLLECTIVE ? 22 : command;
    }

    /** Frame behind a tile icon: Imperial hexagon optic; none for Command, Collective (the node is the frame) and pipe. */
    static Drawable iconFrame(Context c, final int color) {
        if (command() || collective() || pipe()) return null;
        final float dd = Ui.density(c);
        final boolean k = imperial();
        return new Drawable() {
            final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
            final Path path = new Path();
            @Override public void draw(Canvas cv) {
                Rect b = getBounds();
                float cx = b.exactCenterX(), cy = b.exactCenterY(), rr = Math.min(b.width(), b.height()) / 2f - dd;
                pt.setStyle(Paint.Style.FILL);
                pt.setColor(Ui.cloak ? Color.BLACK : mix(bg(), color, 0.18f));
                path.reset();
                if (k) {
                    for (int i = 0; i < 6; i++) {
                        double a = Math.PI / 3 * i;
                        float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                        if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                    path.close();
                } else {
                    path.addCircle(cx, cy, rr, Path.Direction.CW);
                }
                cv.drawPath(path, pt);
                pt.setStyle(Paint.Style.STROKE);
                pt.setStrokeWidth(1.4f * dd);
                pt.setColor(Ui.alpha(glow(color), Ui.cloak ? 0.6f : 0.9f));
                cv.drawPath(path, pt);
                if (!k) {
                    // second ring: the Collective disk glyph
                    pt.setStrokeWidth(dd);
                    pt.setColor(Ui.alpha(glow(color), 0.45f));
                    cv.drawCircle(cx, cy, rr - 3.5f * dd, pt);
                }
            }
            @Override public void setAlpha(int a) { pt.setAlpha(a); }
            @Override public void setColorFilter(ColorFilter cf) { }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    /**
     * Backdrop for the "pattern" background option: Imperial tactical grid, Collective conduit matrix, the
     * pipe material backdrop. The Collective network is generated deterministically from cell indices so it
     * never shifts between frames; it simply tiles further on larger screens.
     */
    static Drawable backdrop(Context c) {
        if (pipe()) return PipeDraw.backdrop(c);
        final float dd = Ui.density(c);
        final boolean k = imperial();
        return new Drawable() {
            @Override public void draw(Canvas cv) {
                Rect b = getBounds();
                cv.drawColor(bg());
                float dim = Ui.cloak ? 0.4f : 1f;
                if (k) {
                    // dark red triangle lattice, as in the Imperial panel fills
                    ImperialDraw.grid(cv, new RectF(b), 40 * dd, 0xFF4A0E0E, 0.6f * dim, dd);
                    return;
                }
                // a faint, sparse trace network: nodes scattered on a loose grid, linked by curves
                float cell = 92 * dd;
                int cols = (int) (b.width() / cell) + 2, rows = (int) (b.height() / cell) + 2;
                float[] xs = new float[cols * rows], ys = new float[cols * rows];
                for (int j = 0; j < rows; j++) for (int i = 0; i < cols; i++) {
                    int n = j * cols + i, h = (i * 7349 + j * 1931) & 0xFFFF;
                    xs[n] = b.left + (i + 0.15f + (h % 70) / 100f) * cell;
                    ys[n] = b.top + (j + 0.15f + ((h / 70) % 70) / 100f) * cell;
                }
                for (int j = 0; j < rows; j++) for (int i = 0; i < cols; i++) {
                    int n = j * cols + i, h = (i * 7349 + j * 1931) & 0xFFFF;
                    if (i + 1 < cols && h % 3 != 0) CollectiveDraw.curveTrace(cv, xs[n], ys[n], xs[n + 1], ys[n + 1], (h % 2 == 0 ? 0.12f : -0.1f), COLLECTIVE_LINE, dd, 0.16f * dim);
                    if (j + 1 < rows && h % 4 == 1) CollectiveDraw.curveTrace(cv, xs[n], ys[n], xs[n + cols], ys[n + cols], 0.1f, COLLECTIVE_LINE, dd, 0.14f * dim);
                }
                for (int n = 0; n < xs.length; n++) {
                    int h = (n * 2654435) >>> 8;
                    if (h % 5 == 0) CollectiveDraw.ringNode(cv, xs[n], ys[n], (6 + h % 7) * dd, COLLECTIVE_LINE, dd, 0.22f * dim);
                    else if (h % 5 == 1) CollectiveDraw.node(cv, xs[n], ys[n], 3.5f * dd, COLLECTIVE_LIT, dd, 0.15f * dim);
                    else CollectiveDraw.via(cv, xs[n], ys[n], 2.2f * dd, COLLECTIVE_LINE, dd, 0.22f * dim);
                }
            }
            @Override public void setAlpha(int a) { }
            @Override public void setColorFilter(ColorFilter cf) { }
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
    }

    /** Name of the "pattern" background option in CONFIG. */
    static String patternName() {
        if (pipe()) {
            if (scene()) return "PAINTED SCENE";
            return tob() ? "WALNUT PANELS" : lounge() ? "LACQUER & GILT" : "LEATHER & LEAF";
        }
        return imperial() ? "TRIANGLE GRID" : collective() ? "TRACE NETWORK" : "STARFIELD";
    }

    // ---- header ornaments (drawn behind the chronometer) ---------------------------------------------

    /** Ornament behind the home clock (23rd-century lamp bank, 25th-century reticle), or null for Command. */
    static Drawable headerOrnament(Context c, Palette p) {
        if (pipe()) return PipeDraw.ornament(c, p);
        if (era()) return EraDraw.ornament(c, p);
        if (imperial()) return new TacticalDisplay(c, p);
        if (collective()) return new NodeNet(c, p);
        return null;
    }

    /**
     * Imperial header display, behind the clock: a long double-lined octagon frame with a faint
     * triangle lattice inside, stacked triangle clusters at each end and a ▲▼▲ row along the
     * bottom edge.
     */
    static final class TacticalDisplay extends Drawable {
        private final float d;
        private float am = 1f;
        private final Path outer = new Path(), inner = new Path();

        TacticalDisplay(Context c, Palette p) { d = Ui.density(c); }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            float a = (Ui.cloak ? 0.5f : 1f) * am;
            float l = b.left + 2 * d, t = b.top + 2 * d, r = b.right - 2 * d, bt = b.bottom - 2 * d;
            float k = Math.min((bt - t) * 0.32f, 26 * d);
            ImperialDraw.octagon(outer, l, t, r, bt, k, true, true);
            ImperialDraw.octagon(inner, l + 4 * d, t + 4 * d, r - 4 * d, bt - 4 * d, k - 2 * d, true, true);
            cv.save();
            cv.clipPath(inner);
            ImperialDraw.grid(cv, new RectF(b), 22 * d, ImperialDraw.RED, 0.13f * a, d);
            cv.restore();
            ImperialDraw.frame(cv, outer, inner, ImperialDraw.RED, d, 0.55f * a);
            float s = Math.min((bt - t) * 0.16f, 13 * d);
            ImperialDraw.cluster(cv, l + 8 * d + s, (t + bt) / 2f, s, 0.75f * a);
            ImperialDraw.cluster(cv, r - 8 * d - s, (t + bt) / 2f, s, 0.75f * a);
            float rw = Math.min((r - l) * 0.3f, 90 * d);
            ImperialDraw.row(cv, (l + r) / 2f - rw / 2f, bt - 13 * d, rw, 7 * d,
                    new int[]{ImperialDraw.YELLOW, ImperialDraw.ORANGE, ImperialDraw.RED}, 0.7f * a);
        }

        @Override public void setAlpha(int al) { am = al / 255f; }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * Collective header display: a ringed hub with long crosshair lines, satellites on curved traces and
     * strings of data dots, after the Collective wall displays. Kept dim: the clock sits on top of it.
     */
    static final class NodeNet extends Drawable {
        private final Palette p;
        private final float d;
        private float am = 1f;
        /** Satellites in unit space (asymmetric, hand-placed): x, y, radius in dp. */
        private static final float[][] N = {{0.10f, 0.22f, 9}, {0.18f, 0.80f, 6}, {0.34f, 0.12f, 5}, {0.70f, 0.10f, 7},
                {0.86f, 0.30f, 10}, {0.80f, 0.86f, 6}, {0.95f, 0.66f, 4}, {0.04f, 0.55f, 4}};

        NodeNet(Context c, Palette p) {
            this.p = p;
            d = Ui.density(c);
        }

        @Override public void draw(Canvas cv) {
            Rect b = getBounds();
            float w = b.width(), h = b.height();
            float a = (Ui.cloak ? 0.25f : 0.42f) * am;
            float cx = b.left + w * 0.5f, cy = b.top + h * 0.5f, hr = h * 0.42f;
            int line = p.c(0), hi = p.c(1);
            // crosshair
            Path pth = CollectiveDraw.tmp();
            pth.moveTo(b.left + w * 0.02f, cy);
            pth.lineTo(b.right - w * 0.02f, cy);
            pth.moveTo(cx, b.top);
            pth.lineTo(cx, b.bottom);
            CollectiveDraw.trace(cv, pth, hi, d, 0.25f * a);
            // satellites on traces from the hub rim
            for (int i = 0; i < N.length; i++) {
                float x = b.left + N[i][0] * w, y = b.top + N[i][1] * h, r = N[i][2] * d;
                double ang = Math.atan2(y - cy, x - cx);
                float sx = cx + (float) Math.cos(ang) * hr, sy = cy + (float) Math.sin(ang) * hr;
                CollectiveDraw.curveTrace(cv, sx, sy, x, y, (i % 2 == 0 ? 0.18f : -0.15f), line, d, 0.7f * a);
                if (i % 3 == 2) CollectiveDraw.via(cv, x, y, r * 0.6f, line, d, a);
                else CollectiveDraw.ringNode(cv, x, y, r, hi, d, a);
            }
            CollectiveDraw.dots(cv, b.left + w * 0.04f, b.top + h * 0.08f, 7, hi, d, 0x5B, 0.8f * a);
            CollectiveDraw.dots(cv, b.right - w * 0.28f, b.bottom - h * 0.06f, 8, hi, d, 0x3D, 0.8f * a);
            // the hub: concentric rings, hollow so the time stays readable
            CollectiveDraw.ringNode(cv, cx, cy, hr, line, d, 0.8f * a);
            CollectiveDraw.ringNode(cv, cx, cy, hr * 0.55f, hi, d, 0.6f * a);
        }

        @Override public void setAlpha(int al) { am = al / 255f; }
        @Override public void setColorFilter(ColorFilter cf) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
