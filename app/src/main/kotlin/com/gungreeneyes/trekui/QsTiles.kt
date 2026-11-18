package com.gungreeneyes.trekui

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi

/**
 * The launcher's Quick Settings tiles ([CloakTileService], [RedAlertTileService]): shared
 * helpers to refresh them when their state changes elsewhere, to paint a tile, and to offer adding a
 * tile to the panel from CONFIG (API 33+ [StatusBarManager.requestAddTileService]). Tiles exist from Android 7.0
 * (API 24); on 6.0 there is no Quick Settings tile API, so [refresh] does nothing and the tiles never appear.
 */
internal object QsTiles {

    /** Result callback of [requestAdd]: one of the `StatusBarManager.TILE_ADD_REQUEST_*` codes, or -1 when unsupported. */
    fun interface Added {
        fun result(code: Int)
    }

    /** Asks the system to re-read both tiles (no-op when no tile is in the panel). */
    @JvmStatic
    fun refresh(c: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        request(c, CloakTileService::class.java)
        request(c, RedAlertTileService::class.java)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun request(c: Context, service: Class<out TileService>) {
        try {
            TileService.requestListeningState(c, ComponentName(c, service))
        } catch (ignored: RuntimeException) {
            // tile service unavailable (some OEM builds, tests): the tile catches up when next shown
        }
    }

    /** Paints a tile: on/off state, label and (API 29+) a subtitle. */
    @RequiresApi(Build.VERSION_CODES.N)
    @JvmStatic
    fun paint(tile: Tile?, on: Boolean, label: CharSequence?, subtitle: CharSequence?) {
        if (tile == null) return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = label
        tile.contentDescription = label
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = subtitle
        tile.updateTile()
    }

    /** True when the system can prompt to add a tile (Android 13+). */
    @ChecksSdkIntAtLeast(api = 33)
    @JvmStatic
    fun canRequestAdd(): Boolean = Build.VERSION.SDK_INT >= 33

    /**
     * Shows the system "Add tile to Quick Settings?" prompt (Android 13+; must be called from a
     * foreground activity). Below 33 `done` gets -1 and the owner adds the tile by editing the panel.
     */
    @JvmStatic
    fun requestAdd(a: Activity, service: Class<out TileService>, labelRes: Int, iconRes: Int, done: Added) {
        if (Build.VERSION.SDK_INT < 33) {
            done.result(-1)
            return
        }
        val sbm = a.getSystemService(StatusBarManager::class.java)
        if (sbm == null) {
            done.result(-1)
            return
        }
        try {
            sbm.requestAddTileService(
                ComponentName(a, service), a.getString(labelRes),
                Icon.createWithResource(a, iconRes), a.mainExecutor,
            ) { code -> done.result(code) }
        } catch (e: RuntimeException) {
            done.result(-1)
        }
    }
}
