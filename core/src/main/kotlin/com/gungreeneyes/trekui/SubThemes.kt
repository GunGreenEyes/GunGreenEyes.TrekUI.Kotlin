package com.gungreeneyes.trekui

import kotlin.math.max

/**
 * The sub-themes (colour themes) every interface family offers, as pure `#RRGGBB` specs so JVM
 * tests can check them and companion apps can list them without a launcher.
 *
 * A sub-theme is chosen per interface and stored in [TrekPrefs] under
 * [TrekPrefs.themeKeyFor]; the launcher publishes the active one on the suite provider
 * (`SuiteTheme.subTheme`), and `SuiteContract.parseTheme` clamps it with [clamp].
 * Index 0 of every list is the interface's original look, so a user who never picks one sees no change.
 *
 * ```
 *  COMMAND        11 themes: CLASSIC (default) and the five 6.x themes keep their indexes 0-5, the
 *                 owner's DEEP BLUE .. MODERN GRAY follow at 6-10, so stored choices never move.
 *  CLASSIC / NEXT CONSOLE (eras)   their own three themes each ([Era]).
 *  IMPERIAL       5 themes, COLLECTIVE 5 themes: original palettes that also carry the interface's
 *                 chrome tones (background, surface, grid, detail, control lettering).
 * ```
 *
 * Spec layout: [Era]'s eleven entries (name, six accents, text high, text low, alert, panel),
 * then for Imperial and Collective five chrome tones ([SPEC_BG] .. [SPEC_INK]).
 * Accent slots keep their per-interface meaning: Imperial 0 line red, 1 orange, 2 highlight yellow,
 * 3 deep; Collective 0 lit node, 1 highlight, 2 matrix line, 3 grid, 5 conduit.
 */
object SubThemes {
    /** Chrome tone entries after the eleven [Era] spec entries (Imperial and Collective only). */
    const val SPEC_BG = 11
    const val SPEC_SURFACE = 12
    const val SPEC_GRID = 13
    const val SPEC_DETAIL = 14
    const val SPEC_INK = 15
    const val SPEC_TONED_LENGTH = 16

    /**
     * Command themes. 0-5 are the original six (CLASSIC is the owner's reference palette; every pair
     * clears WCAG AA on black and on the panel); 6-10 are the owner's 2026-11 additions, from the same
     * reference site's theme colours under neutral names (see the handoff PALETTE notes).
     */
    @JvmField
    val COMMAND: Array<Array<String>> = arrayOf(
        arrayOf("CLASSIC", "#EA9C72", "#8899FF", "#D29B7F", "#CF4F4F", "#EDB378", "#BAA4E5",
            "#FFFFFF", "#BAA4E5", "#FF2200", "#0E0C16"),
        arrayOf("MIDNIGHT BLUE", "#5588FF", "#99CCFF", "#3D6BCC", "#CCDDFF", "#FFAA33", "#7788CC",
            "#E2EEFF", "#99CCFF", "#FF5544", "#0A0E18"),
        arrayOf("RED ALERT", "#E03030", "#FF7766", "#B04040", "#FFAA99", "#FF5A1F", "#CC5566",
            "#FFE0D8", "#FF9988", "#FFDD00", "#160909"),
        arrayOf("ENGINEERING AMBER", "#FF9900", "#FFC266", "#CC7A00", "#FFE0A0", "#E8A33D", "#B36B00",
            "#FFEBCC", "#FFC266", "#FF4433", "#140F06"),
        arrayOf("NEBULA VIOLET", "#AA77EE", "#CCB0FF", "#6688EE", "#EE99CC", "#FFBB66", "#8866CC",
            "#EEE4FF", "#CCB0FF", "#FF5566", "#100B18"),
        arrayOf("ASTROMETRICS TEAL", "#33BBCC", "#88DDEE", "#3388BB", "#BBEEFF", "#FFAA44", "#5599AA",
            "#E0FAFF", "#88DDEE", "#FF5544", "#071214"),
        // DEEP BLUE: evening bars, cool buttons, ghost frame, tangerine sidebar, pale accent headings.
        arrayOf("DEEP BLUE", "#2266FF", "#6699FF", "#88BBFF", "#FF8833", "#ADCAFF", "#E63D00",
            "#EBF0FF", "#6699FF", "#DA2537", "#070B1A"),
        // SUNSET DECK: orange bars, harvest-gold buttons, daybreak frame, sunset sidebar, honey headings.
        arrayOf("SUNSET DECK", "#FF7700", "#FFAA44", "#FF9911", "#FF4400", "#FFCC99", "#CC5500",
            "#FFEECC", "#FF9911", "#FF3355", "#160B04"),
        // NIGHT PADD: alpha-blue keys (night-rain moved to the frame so key lettering stays readable).
        arrayOf("NIGHT PADD", "#5588EE", "#7799DD", "#455580", "#88EEFF", "#99CCFF", "#5588EE",
            "#F3F3FC", "#7799DD", "#FF3300", "#0B0F1E"),
        // STELLAR SILVER: silver frame and bars, luna buttons, pewter frame, command-gold sidebar.
        arrayOf("STELLAR SILVER", "#828CAD", "#BEC2CF", "#74788B", "#FFBB33", "#FFE1CA", "#70B5FF",
            "#FFE1CA", "#70B5FF", "#FF3300", "#0E1018"),
        // MODERN GRAY: gray frame, light-gray buttons, the blue family for data, ghost-gray headings.
        arrayOf("MODERN GRAY", "#6D748C", "#9EA5BA", "#37A6D1", "#41C4F7", "#D2D5DF", "#2A7193",
            "#F3F4F7", "#9EA5BA", "#FF6753", "#161A24"),
    )

    /** Index of the first theme added in 6.3 (themes before it have exported app-theme art). */
    const val COMMAND_ART_THEMES = 6

    /** For each Command theme, the original theme whose exported app/panel art matches it best. */
    private val COMMAND_ART = intArrayOf(0, 1, 2, 3, 4, 5, 1, 3, 1, 5, 0)

    /**
     * Imperial themes (original): the tactical red default, then bronze, crimson-rose, cold steel blue
     * and iron grey with flame. Each keeps the line / orange / highlight / deep slot roles and dark
     * chrome so the chamfered plates and triangle clusters read the same way.
     */
    @JvmField
    val IMPERIAL: Array<Array<String>> = arrayOf(
        arrayOf("IMPERIAL TACTICAL", "#D8231E", "#F0821E", "#F5C830", "#A3161A", "#E8562A", "#F2A43A",
            "#F5C830", "#F0821E", "#FF3B2E", "#140606",
            "#050303", "#140606", "#4A0E0E", "#2A0808", "#F5B83A"),
        arrayOf("WAR BRONZE", "#C0702A", "#E09A4A", "#F2D27A", "#7A3E14", "#D4823A", "#E8B860",
            "#F2D27A", "#E09A4A", "#FF4A2E", "#140C06",
            "#060403", "#140C06", "#4A2A0E", "#2A1808", "#F2C86A"),
        arrayOf("DUSK RAIDER", "#C8305E", "#E8687E", "#F6B8CC", "#7E1A3C", "#DC4A6C", "#EE98B0",
            "#F6C8D8", "#E8687E", "#FFB020", "#140610",
            "#060305", "#140610", "#4A0E2C", "#2A0818", "#F4B8CC"),
        arrayOf("ICE FORGE", "#3A80D8", "#6FB2F2", "#C2E2FF", "#1E4A8A", "#4E98E4", "#9ACCF2",
            "#D2E8FF", "#6FB2F2", "#FF4A3A", "#060C16",
            "#030508", "#08101C", "#10284A", "#0A182A", "#C2E2FF"),
        arrayOf("IRON & FLAME", "#A09A92", "#E8762A", "#FFB45A", "#5A544C", "#C8602A", "#D8C8B0",
            "#F4E6D0", "#E8762A", "#FF3B2E", "#0E0C0B",
            "#050505", "#110F0E", "#3A3632", "#22201E", "#FFC27A"),
    )

    /**
     * Collective themes (original): the matrix-green default, then cyan, amber, violet and frost
     * networks. Each keeps a bright lit node, a lighter highlight and dark matrix / conduit lines.
     */
    @JvmField
    val COLLECTIVE: Array<Array<String>> = arrayOf(
        arrayOf("THE COLLECTIVE", "#79C98B", "#B1DC86", "#2B704A", "#325846", "#73887F", "#123B2B",
            "#C4D7C0", "#73887F", "#E0A45C", "#0C1714",
            "#030706", "#06100C", "#123B2B", "#325846", "#C4D7C0"),
        arrayOf("CYAN LATTICE", "#5FC8D4", "#A8E8F0", "#1F6A78", "#2A5258", "#74888C", "#0F3640",
            "#C0DCE0", "#7A9296", "#E0A45C", "#0A1416",
            "#030607", "#060E10", "#0F3640", "#2A5258", "#C0DCE0"),
        arrayOf("AMBER SIGNAL", "#D8B060", "#F0D890", "#7A5A20", "#584A30", "#8A8270", "#3A2C10",
            "#E0D6C0", "#8E8672", "#E06A5C", "#16120A",
            "#070604", "#100C06", "#3A2C10", "#584A30", "#E0D6C0"),
        arrayOf("VIOLET NODE", "#A88AE0", "#CDB8F4", "#4A3A7A", "#3E3858", "#857E96", "#241A44",
            "#D6CCE8", "#8A82A0", "#E0A45C", "#100C18",
            "#050408", "#0C0912", "#241A44", "#3E3858", "#D6CCE8"),
        arrayOf("FROST NODE", "#C8D4D8", "#EEF4F6", "#5A6870", "#3C484E", "#8A969A", "#1E282C",
            "#E6ECEE", "#8E9A9E", "#E0A45C", "#0C1012",
            "#040506", "#0A0D0F", "#1E282C", "#3C484E", "#E6ECEE"),
    )

    private val NONE: Array<Array<String>> = emptyArray()

    /**
     * Theme specs offered for `faction`; empty for an id that names no interface (see
     * [count]).
     */
    @JvmStatic
    fun specs(faction: Int): Array<Array<String>> = when (faction) {
        Skin.COMMAND -> COMMAND
        Skin.IMPERIAL -> IMPERIAL
        Skin.COLLECTIVE -> COLLECTIVE
        Skin.ERA23, Skin.ERA25 -> Era.themes(faction)
        else -> NONE
    }

    /** Number of sub-themes `faction` offers (at least 1: the default look). */
    @JvmStatic
    fun count(faction: Int): Int = max(1, specs(faction).size)

    /** True when CONFIG should offer a theme choice for `faction`. */
    @JvmStatic
    fun selectable(faction: Int): Boolean = count(faction) > 1

    /** Clamps a stored sub-theme index into `faction`'s list (0, the default look, when out of range). */
    @JvmStatic
    fun clamp(faction: Int, index: Int): Int = if (index < 0 || index >= count(faction)) 0 else index

    /** Display name of sub-theme `index` of `faction` (clamped), or "" for a fixed-colour interface. */
    @JvmStatic
    fun name(faction: Int, index: Int): String {
        val s = specs(faction)
        return if (s.isEmpty()) "" else s[clamp(faction, index)][Era.SPEC_NAME]
    }

    /** The six accents of sub-theme `index` of `faction` (clamped) as ARGB, for preview strips. */
    @JvmStatic
    fun accents(faction: Int, index: Int): IntArray {
        val s = specs(faction)
        if (s.isEmpty()) return IntArray(0)
        val spec = s[clamp(faction, index)]
        return IntArray(Era.SPEC_ACCENTS) { Era.rgb(spec[Era.SPEC_ACCENT0 + it]) }
    }

    /** The Command theme whose bundled app-theme / Quick Panel art (assets 0-5) best fits `theme`. */
    @JvmStatic
    fun commandArtIndex(theme: Int): Int = COMMAND_ART[clamp(Skin.COMMAND, theme)]
}
