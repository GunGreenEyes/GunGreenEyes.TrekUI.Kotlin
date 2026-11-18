package com.gungreeneyes.trekui

import android.content.SharedPreferences
import java.util.Locale

/**
 * Every home-screen interface the launcher offers, as the scenarios switch between them.
 *
 * An interface is a `faction` preference value (one of [Skin.INTERFACES]: the console factions, CLASSIC CONSOLE and NEXT CONSOLE)
 * plus, for the three console factions, the alternative home layout stored under
 * `Prefs.layoutKey()` (1 = reveal layout for Command/Imperial, hub layout for Collective).
 */
enum class Interface(val faction: Int, val layoutKey: String?, val layout: Int) {
    COMMAND(Skin.COMMAND, null, 0),
    COMMAND_REVEAL(Skin.COMMAND, "commandLayout", 1),
    IMPERIAL(Skin.IMPERIAL, null, 0),
    IMPERIAL_REVEAL(Skin.IMPERIAL, "imperialLayout", 1),
    COLLECTIVE(Skin.COLLECTIVE, null, 0),
    COLLECTIVE_HUB(Skin.COLLECTIVE, "collectiveLayout", 1),
    CLASSIC_CONSOLE(Skin.ERA23, null, 0),
    NEXT_CONSOLE(Skin.ERA25, null, 0),
    ;

    /** Writes this interface into the launcher's preferences (the activity rebuilds on resume). */
    fun apply(e: SharedPreferences.Editor) {
        e.putInt("faction", faction)
        e.putInt("commandLayout", 0).putInt("imperialLayout", 0).putInt("collectiveLayout", 0)
        if (layoutKey != null) e.putInt(layoutKey, layout)
    }

    /** Lower-case name used in screenshot/video file names, e.g. "collective_hub". */
    fun slug(): String = name.lowercase(Locale.US)
}
