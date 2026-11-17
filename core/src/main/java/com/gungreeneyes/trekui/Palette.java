package com.gungreeneyes.trekui;

import android.graphics.Color;

/**
 * A colour theme: six accent colours used in rotation for frames, tiles and data, plus text, alert
 * and panel colours. {@link #ALL} holds the Command themes, {@link #ERA23} / {@link #ERA25} the two
 * extra Command eras (specs in {@link Era}); faction and pipe palettes live in Skin.
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

    /** Builds a palette from {@code #RRGGBB} strings. */
    Palette(String name, String[] accents, String textHi, String textLo, String alert, String panel) {
        this.name = name;
        this.c = new int[accents.length];
        for (int i = 0; i < accents.length; i++) this.c[i] = Color.parseColor(accents[i]);
        this.textHi = Color.parseColor(textHi);
        this.textLo = Color.parseColor(textLo);
        this.alert = Color.parseColor(alert);
        this.panel = Color.parseColor(panel);
    }

    /** Accent {@code i}, wrapping around (negative indexes count back from the end). */
    int c(int i) {
        return c[wrapIndex(i, c.length)];
    }

    /** Builds a palette from an {@link Era} theme spec (name, six accents, text hi/lo, alert, panel). */
    static Palette fromSpec(String[] spec) {
        String[] accents = new String[Era.SPEC_ACCENTS];
        System.arraycopy(spec, Era.SPEC_ACCENT0, accents, 0, Era.SPEC_ACCENTS);
        return new Palette(spec[Era.SPEC_NAME], accents, spec[Era.SPEC_TEXT_HI], spec[Era.SPEC_TEXT_LO],
                spec[Era.SPEC_ALERT], spec[Era.SPEC_PANEL]);
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

    /** Colour themes offered in CONFIG for a faction: the era sets, or {@link #ALL} for every other Command look. */
    static Palette[] themesFor(int faction) {
        if (faction == Skin.ERA23) return ERA23;
        if (faction == Skin.ERA25) return ERA25;
        return ALL;
    }

    /** {@code i} modulo {@code n}, always in 0..n-1 (also for negative {@code i}). */
    static int wrapIndex(int i, int n) {
        return ((i % n) + n) % n;
    }

    /** The Command themes offered in CONFIG, by stored theme index. */
    static final Palette[] ALL = {
        // CLASSIC (default): the owner's reference console palette. Slots: 0 butterscotch (bars, top
        // elbow, APPS), 1 bluey (header elbows, buttons), 2 almond (left frame, footer), 3 red (bottom
        // elbow, sidebar CONFIG key), 4 barley (headings), 5 african violet; text white / african violet,
        // alert mars. Every pair clears WCAG AA on black and on the panel (lowest: red on panel 4.5:1).
        new Palette("CLASSIC",
            new String[]{"#EA9C72", "#8899FF", "#D29B7F", "#CF4F4F", "#EDB378", "#BAA4E5"},
            "#FFFFFF", "#BAA4E5", "#FF2200", "#0E0C16"),
        new Palette("MIDNIGHT BLUE",
            new String[]{"#5588FF", "#99CCFF", "#3D6BCC", "#CCDDFF", "#FFAA33", "#7788CC"},
            "#E2EEFF", "#99CCFF", "#FF5544", "#0A0E18"),
        new Palette("RED ALERT",
            new String[]{"#E03030", "#FF7766", "#B04040", "#FFAA99", "#FF5A1F", "#CC5566"},
            "#FFE0D8", "#FF9988", "#FFDD00", "#160909"),
        new Palette("ENGINEERING AMBER",
            new String[]{"#FF9900", "#FFC266", "#CC7A00", "#FFE0A0", "#E8A33D", "#B36B00"},
            "#FFEBCC", "#FFC266", "#FF4433", "#140F06"),
        new Palette("NEBULA VIOLET",
            new String[]{"#AA77EE", "#CCB0FF", "#6688EE", "#EE99CC", "#FFBB66", "#8866CC"},
            "#EEE4FF", "#CCB0FF", "#FF5566", "#100B18"),
        new Palette("ASTROMETRICS TEAL",
            new String[]{"#33BBCC", "#88DDEE", "#3388BB", "#BBEEFF", "#FFAA44", "#5599AA"},
            "#E0FAFF", "#88DDEE", "#FF5544", "#071214"),
    };
}
