package com.gungreeneyes.trekui.core;

import java.util.Objects;

/**
 * One look-and-sound choice: interface (faction id, see {@code Skin}), its sub-theme (index into
 * {@code Palette.themesFor(faction)}; {@code SubThemes} lists names and counts for every interface), the
 * Imperial lettering and the sound set id ({@code Sfx.SET_*}, may be null for "not chosen").
 *
 * <p>Immutable value passed between apps through the suite provider (see {@link SuiteContract}) and
 * installed into {@code TrekPrefs.setThemeOverride} when a companion follows the launcher.
 */
public final class SuiteTheme {
    public final int faction;
    public final int subTheme;
    public final int lettering;
    public final String soundSet;

    public SuiteTheme(int faction, int subTheme, int lettering, String soundSet) {
        this.faction = faction;
        this.subTheme = subTheme;
        this.lettering = lettering;
        this.soundSet = soundSet;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SuiteTheme)) return false;
        SuiteTheme t = (SuiteTheme) o;
        return faction == t.faction && subTheme == t.subTheme && lettering == t.lettering
                && Objects.equals(soundSet, t.soundSet);
    }

    @Override
    public int hashCode() {
        return Objects.hash(faction, subTheme, lettering, soundSet);
    }

    @Override
    public String toString() {
        return "SuiteTheme{faction=" + faction + ", subTheme=" + subTheme + ", lettering=" + lettering
                + ", soundSet=" + soundSet + '}';
    }
}
