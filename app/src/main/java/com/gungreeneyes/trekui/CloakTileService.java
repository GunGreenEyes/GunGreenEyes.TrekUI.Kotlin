package com.gungreeneyes.trekui;

import android.service.quicksettings.TileService;

/**
 * Quick Settings tile for CLOAK (night mode): tap to engage or disengage, exactly like the CLOAK
 * launcher function ({@link Cloak#toggle}). Active while cloaked, with the minutes left as subtitle.
 * The home screen picks up the change on its next tick or when it resumes ({@link Cloak#check}).
 */
public final class CloakTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Cloak.check(this);
        update();
    }

    @Override
    public void onClick() {
        super.onClick();
        Cloak.toggle(this);
        update();
    }

    private void update() {
        boolean on = Cloak.active(this);
        String subtitle = on
                ? getString(R.string.qs_cloak_left, SettingsLogic.minutesLeft(Cloak.remainingMs(this)))
                : getString(R.string.qs_off);
        QsTiles.paint(getQsTile(), on, getString(R.string.qs_cloak), subtitle);
    }
}
