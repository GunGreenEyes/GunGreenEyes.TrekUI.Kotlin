package com.gungreeneyes.trekui;

import android.app.Activity;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * The launcher's Quick Settings tiles ({@link CloakTileService}, {@link RedAlertTileService}): shared
 * helpers to refresh them when their state changes elsewhere, to paint a tile, and to offer adding a
 * tile to the panel from CONFIG (API 33+ {@link StatusBarManager#requestAddTileService}).
 */
final class QsTiles {

    /** Result callback of {@link #requestAdd}: one of the {@code StatusBarManager.TILE_ADD_REQUEST_*} codes, or -1 when unsupported. */
    interface Added {
        void result(int code);
    }

    private QsTiles() {}

    /** Asks the system to re-read both tiles (no-op when no tile is in the panel). */
    static void refresh(Context c) {
        request(c, CloakTileService.class);
        request(c, RedAlertTileService.class);
    }

    private static void request(Context c, Class<? extends TileService> service) {
        try {
            TileService.requestListeningState(c, new ComponentName(c, service));
        } catch (RuntimeException ignored) {
            // tile service unavailable (some OEM builds, tests): the tile catches up when next shown
        }
    }

    /** Paints a tile: on/off state, label and (API 29+) a subtitle. */
    static void paint(Tile tile, boolean on, CharSequence label, CharSequence subtitle) {
        if (tile == null) return;
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(label);
        tile.setContentDescription(label);
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(subtitle);
        tile.updateTile();
    }

    /** True when the system can prompt to add a tile (Android 13+). */
    static boolean canRequestAdd() {
        return Build.VERSION.SDK_INT >= 33;
    }

    /**
     * Shows the system "Add tile to Quick Settings?" prompt (Android 13+; must be called from a
     * foreground activity). Below 33 {@code done} gets -1 and the owner adds the tile by editing the panel.
     */
    static void requestAdd(Activity a, Class<? extends TileService> service, int labelRes, int iconRes, Added done) {
        if (Build.VERSION.SDK_INT < 33) {
            done.result(-1);
            return;
        }
        StatusBarManager sbm = a.getSystemService(StatusBarManager.class);
        if (sbm == null) {
            done.result(-1);
            return;
        }
        try {
            sbm.requestAddTileService(new ComponentName(a, service), a.getString(labelRes),
                    Icon.createWithResource(a, iconRes), a.getMainExecutor(), code -> done.result(code));
        } catch (RuntimeException e) {
            done.result(-1);
        }
    }
}
