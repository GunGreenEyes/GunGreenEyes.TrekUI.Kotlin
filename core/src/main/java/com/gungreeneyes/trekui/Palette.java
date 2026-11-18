package com.gungreeneyes.trekui;

/**
 * A colour theme: six accent colours used in rotation for frames, tiles and data, plus text, alert
 * and panel colours. {@link #ALL} holds the Command themes, {@link #ERA23} / {@link #ERA25} the two
 * extra Command eras (specs in {@link Era}), {@link #IMPERIAL} / {@link #COLLECTIVE} the faction
 * sub-themes (specs in {@link SubThemes}). {@link #themesFor} is the one
 * lookup every screen uses.
 *
 * <p>Colours are parsed with the pure {@link Era#rgb} (not {@code android.graphics.Color}), so the
 * theme tables hold their real values in JVM unit tests too.
 */
final class Palette {
    final String name;
    /** Accent slots: 0 primary (orange), 1 lavender, 2 blue, 3 pink, 4 gold, 5 purple. */
    final int[] c;
    /** Bright readout text (clock, values). */
    final int textHi;
    /** Secondary data text. */
    final int textLo;
    /** Warning / low battery colour. */
    final int alert;
    /** Card/panel fill behind data. */
    final int panel;
    /**
     * Chrome tones of an Imperial / Collective sub-theme (screen background, tray surface, grid lines,
     * detail marks, control lettering); only meaningful when {@link #toned}.
     */
    final int bg, surface, grid, detail, ink;
    /** True when this palette carries its own chrome tones (Imperial and Collective sub-themes). */
    final boolean toned;

    /** Builds a palette from {@code #RRGGBB} strings. */
    Palette(String name, String[] accents, String textHi, String textLo, String alert, String panel) {
        this(name, accents, textHi, textLo, alert, panel, null);
    }

    /** Builds a palette with optional chrome tones {bg, surface, grid, detail, ink} ({@code null}: none). */
    private Palette(String name, String[] accents, String textHi, String textLo, String alert, String panel, String[] tones) {
        this.name = name;
        this.c = new int[accents.length];
        for (int i = 0; i < accents.length; i++) this.c[i] = Era.rgb(accents[i]);
        this.textHi = Era.rgb(textHi);
        this.textLo = Era.rgb(textLo);
        this.alert = Era.rgb(alert);
        this.panel = Era.rgb(panel);
        this.toned = tones != null;
        this.bg = toned ? Era.rgb(tones[0]) : 0xFF000000;
        this.surface = toned ? Era.rgb(tones[1]) : this.panel;
        this.grid = toned ? Era.rgb(tones[2]) : 0;
        this.detail = toned ? Era.rgb(tones[3]) : 0;
        this.ink = toned ? Era.rgb(tones[4]) : 0xFF000000;
    }

    /** Accent {@code i}, wrapping around (negative indexes count back from the end). */
    int c(int i) {
        return c[wrapIndex(i, c.length)];
    }

    /**
     * Builds a palette from a theme spec (name, six accents, text hi/lo, alert, panel, then optionally
     * the five {@link SubThemes} chrome tones).
     */
    static Palette fromSpec(String[] spec) {
        String[] accents = new String[Era.SPEC_ACCENTS];
        System.arraycopy(spec, Era.SPEC_ACCENT0, accents, 0, Era.SPEC_ACCENTS);
        String[] tones = null;
        if (spec.length >= SubThemes.SPEC_TONED_LENGTH) {
            tones = new String[SubThemes.SPEC_TONED_LENGTH - SubThemes.SPEC_BG];
            System.arraycopy(spec, SubThemes.SPEC_BG, tones, 0, tones.length);
        }
        return new Palette(spec[Era.SPEC_NAME], accents, spec[Era.SPEC_TEXT_HI], spec[Era.SPEC_TEXT_LO],
                spec[Era.SPEC_ALERT], spec[Era.SPEC_PANEL], tones);
    }

    private static Palette[] fromSpecs(String[][] specs) {
        Palette[] out = new Palette[specs.length];
        for (int i = 0; i < specs.length; i++) out[i] = fromSpec(specs[i]);
        return out;
    }

    /** The 23rd-century console themes ({@link Era#ERA23_THEMES}). */
    static final Palette[] ERA23 = fromSpecs(Era.ERA23_THEMES);
    /** The 25th-century glass themes ({@link Era#ERA25_THEMES}). */
    static final Palette[] ERA25 = fromSpecs(Era.ERA25_THEMES);
    /** The Imperial sub-themes ({@link SubThemes#IMPERIAL}); 0 is the original tactical red. */
    static final Palette[] IMPERIAL = fromSpecs(SubThemes.IMPERIAL);
    /** The Collective sub-themes ({@link SubThemes#COLLECTIVE}); 0 is the original matrix green. */
    static final Palette[] COLLECTIVE = fromSpecs(SubThemes.COLLECTIVE);

    /**
     * Colour themes offered for a faction, indexed by stored sub-theme: the Command list, the era
     * sets, or the Imperial / Collective sub-themes (Command's list for any other id).
     */
    static Palette[] themesFor(int faction) {
        if (faction == Skin.ERA23) return ERA23;
        if (faction == Skin.ERA25) return ERA25;
        if (faction == Skin.IMPERIAL) return IMPERIAL;
        if (faction == Skin.COLLECTIVE) return COLLECTIVE;
        return ALL;
    }

    /** The palette of sub-theme {@code index} of {@code faction}, clamped into its list. */
    static Palette of(int faction, int index) {
        Palette[] list = themesFor(faction);
        return list[index < 0 || index >= list.length ? 0 : index];
    }

    /** {@code i} modulo {@code n}, always in 0..n-1 (also for negative {@code i}). */
    static int wrapIndex(int i, int n) {
        return ((i % n) + n) % n;
    }

    /**
     * The Command themes offered in CONFIG, by stored theme index ({@link SubThemes#COMMAND}): CLASSIC
     * (default, the owner's reference palette) and the original five keep indexes 0-5; the 6.3 themes
     * follow, so a stored choice never changes meaning.
     */
    static final Palette[] ALL = fromSpecs(SubThemes.COMMAND);
}
