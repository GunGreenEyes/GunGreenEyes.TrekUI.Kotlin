package com.gungreeneyes.trekui

import android.service.quicksettings.TileService

/**
 * Quick Settings tile for CLOAK (night mode): tap to engage or disengage, exactly like the CLOAK
 * launcher function ([Cloak.toggle]). Active while cloaked, with the minutes left as subtitle.
 * The home screen picks up the change on its next tick or when it resumes ([Cloak.check]).
 */
class CloakTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        Cloak.check(this)
        update()
    }

    override fun onClick() {
        super.onClick()
        Cloak.toggle(this)
        update()
    }

    private fun update() {
        val on = Cloak.active(this)
        val subtitle = if (on) {
            getString(R.string.qs_cloak_left, SettingsLogic.minutesLeft(Cloak.remainingMs(this)))
        } else {
            getString(R.string.qs_off)
        }
        QsTiles.paint(qsTile, on, getString(R.string.qs_cloak), subtitle)
    }
}
