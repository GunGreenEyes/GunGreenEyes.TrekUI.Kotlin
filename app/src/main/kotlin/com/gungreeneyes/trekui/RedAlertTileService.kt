package com.gungreeneyes.trekui

import android.service.quicksettings.TileService

/**
 * Quick Settings tile for RED ALERT: tap to go to red alert or stand down ([RedAlert.toggle]).
 * Active while on alert, with MUTED or the configured timeout as subtitle. The home screen frame
 * follows instantly through [RedAlert.Listener].
 */
class RedAlertTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        RedAlert.check(this)
        update()
    }

    override fun onClick() {
        super.onClick()
        RedAlert.toggle(this)
        update()
    }

    private fun update() {
        val on = RedAlert.active(this)
        val subtitle = when {
            !on -> getString(R.string.qs_off)
            RedAlert.muted(this) -> getString(R.string.red_alert_muted)
            else -> getString(R.string.qs_red_alert_on)
        }
        QsTiles.paint(qsTile, on, getString(R.string.qs_red_alert), subtitle)
    }
}
