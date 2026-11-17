package com.gungreeneyes.trekui;

import android.service.quicksettings.TileService;

/**
 * Quick Settings tile for RED ALERT: tap to go to red alert or stand down ({@link RedAlert#toggle}).
 * Active while on alert, with MUTED or the configured timeout as subtitle. The home screen frame
 * follows instantly through {@link RedAlert.Listener}.
 */
public final class RedAlertTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        RedAlert.check(this);
        update();
    }

    @Override
    public void onClick() {
        super.onClick();
        RedAlert.toggle(this);
        update();
    }

    private void update() {
        boolean on = RedAlert.active(this);
        String subtitle = !on ? getString(R.string.qs_off)
                : RedAlert.muted(this) ? getString(R.string.red_alert_muted)
                : getString(R.string.qs_red_alert_on);
        QsTiles.paint(getQsTile(), on, getString(R.string.qs_red_alert), subtitle);
    }
}
