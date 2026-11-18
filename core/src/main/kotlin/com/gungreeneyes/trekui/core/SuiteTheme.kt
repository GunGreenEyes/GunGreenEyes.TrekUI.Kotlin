package com.gungreeneyes.trekui.core

/**
 * One look-and-sound choice: interface (faction id, see `Skin`), its sub-theme (index into
 * `Palette.themesFor(faction)`; `SubThemes` lists names and counts for every interface), the
 * Imperial lettering and the sound set id (`Sfx.SET_*`, may be null for "not chosen").
 *
 * Immutable value passed between apps through the suite provider (see [SuiteContract]) and
 * installed into `TrekPrefs.setThemeOverride` when a companion follows the launcher.
 *
 * The fields are [JvmField]s so Java callers keep reading `theme.faction`, `theme.subTheme`, ...
 */
data class SuiteTheme(
    @JvmField val faction: Int,
    @JvmField val subTheme: Int,
    @JvmField val lettering: Int,
    @JvmField val soundSet: String?,
) {
    /** Same text as the Java edition (it can show up in logs). */
    override fun toString(): String =
        "SuiteTheme{faction=$faction, subTheme=$subTheme, lettering=$lettering, soundSet=$soundSet}"
}
