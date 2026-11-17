package com.gungreeneyes.trekui;

import android.content.SharedPreferences;

/**
 * Every home-screen interface the launcher offers, as the scenarios switch between them.
 *
 * <p>An interface is a {@code faction} preference value ({@link Skin#COMMAND} .. {@link Skin#ERA25}: the console factions, the six pipe interfaces, CLASSIC CONSOLE and NEXT CONSOLE)
 * plus, for the three console factions, the alternative home layout stored under
 * {@link Prefs#layoutKey()} (1 = reveal layout for Command/Imperial, hub layout for Collective).</p>
 */
enum Interface {
    COMMAND(Skin.COMMAND, null, 0),
    COMMAND_REVEAL(Skin.COMMAND, "commandLayout", 1),
    IMPERIAL(Skin.IMPERIAL, null, 0),
    IMPERIAL_REVEAL(Skin.IMPERIAL, "imperialLayout", 1),
    COLLECTIVE(Skin.COLLECTIVE, null, 0),
    COLLECTIVE_HUB(Skin.COLLECTIVE, "collectiveLayout", 1),
    PIPE_TOBACCONIST(Skin.TOBACCONIST, null, 0),
    PIPE_LOUNGE(Skin.LOUNGE, null, 0),
    PIPE_JOURNAL(Skin.JOURNAL, null, 0),
    PIPE_BRIAR(Skin.BRIAR, null, 0),
    PIPE_LEDGER(Skin.LEDGER, null, 0),
    PIPE_MIDNIGHT(Skin.MIDNIGHT, null, 0),
    CLASSIC_CONSOLE(Skin.ERA23, null, 0),
    NEXT_CONSOLE(Skin.ERA25, null, 0);

    final int faction;
    final String layoutKey;
    final int layout;

    Interface(int faction, String layoutKey, int layout) {
        this.faction = faction;
        this.layoutKey = layoutKey;
        this.layout = layout;
    }

    /** Writes this interface into the launcher's preferences (the activity rebuilds on resume). */
    void apply(SharedPreferences.Editor e) {
        e.putInt("faction", faction);
        e.putInt("commandLayout", 0).putInt("imperialLayout", 0).putInt("collectiveLayout", 0);
        if (layoutKey != null) e.putInt(layoutKey, layout);
    }

    /** Lower-case name used in screenshot/video file names, e.g. "pipe_lounge". */
    String slug() {
        return name().toLowerCase(java.util.Locale.US);
    }
}
