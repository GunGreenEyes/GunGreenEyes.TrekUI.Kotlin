package com.gungreeneyes.trekui;

import android.content.Intent;
import android.view.View;

/**
 * A live home-screen widget panel (weather, calendar, media, power, systems, ...). MainActivity creates
 * it with {@link #create()}, which wraps {@link #build()}'s content in an {@link ConsoleCard}, and drives
 * its lifecycle: work (receivers, polling, animation) happens only between {@link #resume()} and
 * {@link #pause()}.
 */
abstract class Panel {
    final MainActivity a;
    final Palette pal;
    final int accent;
    ConsoleCard card;

    Panel(MainActivity a, int accent) {
        this.a = a;
        this.pal = Prefs.get(a).palette();
        this.accent = accent;
    }

    /** Stable id (one of Prefs.ALL_WIDGETS or a quick-widget id). */
    abstract String id();
    /** Card title. */
    abstract String title();
    /** Fill card.body. */
    abstract void build();

    /** The home became visible: start listening/polling. */
    void resume() {}
    /** The home was hidden: stop all work. */
    void pause() {}
    /** Called about once a minute while visible. */
    void tick() {}
    /** Sticky battery broadcast, forwarded while resumed. */
    void onBattery(Intent battery) {}
    /** Result of a runtime permission request this panel made. */
    void onPermissionResult(int requestCode, boolean granted) {}
    /** Extra long-press menu entries. */
    String[] extraMenu() { return new String[0]; }
    /** One of {@link #extraMenu()}'s entries was chosen. */
    void onExtraMenu(int which) {}

    /** Creates the card and fills it. */
    View create() {
        card = new ConsoleCard(a, title(), accent, pal);
        build();
        return card;
    }
}
