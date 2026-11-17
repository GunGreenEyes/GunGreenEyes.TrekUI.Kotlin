package com.gungreeneyes.trekui;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CONFIG › GESTURES helpers (gap A9): what each gesture is called, what it is set to, and the dialogs
 * that assign it an app or an console function (including OPEN GROUP and LOCK SCREEN). The section rows
 * themselves are built by {@code SettingsActivity.gesturesSection}.
 */
final class GestureSettings {

    private GestureSettings() {}

    /** Display name of a gesture id from {@link HomeGestures#ALL}. */
    static String label(Context c, String gesture) {
        switch (gesture) {
            case HomeGestures.DOUBLE_TAP: return c.getString(R.string.gesture_double_tap);
            case HomeGestures.TWO_FINGER_UP: return c.getString(R.string.gesture_two_finger_up);
            case HomeGestures.TWO_FINGER_DOWN: return c.getString(R.string.gesture_two_finger_down);
            case HomeGestures.PINCH_IN: return c.getString(R.string.gesture_pinch_in);
            case HomeGestures.PINCH_OUT: return c.getString(R.string.gesture_pinch_out);
            case HomeGestures.SWIPE_LEFT: return c.getString(R.string.gesture_swipe_left);
            case HomeGestures.SWIPE_RIGHT: return c.getString(R.string.gesture_swipe_right);
            default: return gesture.toUpperCase(Locale.US);
        }
    }

    /** What a gesture currently runs: an app's name, a function's name, a group's name, or NOTHING. */
    static String describe(Context c, String gesture) {
        Tile t = HomeGestures.assigned(Prefs.get(c), gesture);
        if (t == null) return c.getString(R.string.cfg_gesture_none);
        if (t.action == null) {
            AppInfo app = AppRepo.get(c).find(t.comp, t.user);
            return app != null ? app.label.toUpperCase(Locale.getDefault()) : t.label;
        }
        if (Tile.A_GROUP.equals(t.action)) {
            AppGroups.Group g = AppGroups.find(AppGroups.load(Prefs.get(c)), t.arg);
            return c.getString(R.string.fn_group_entry, g != null ? g.name : "?");
        }
        if (t.label != null && !t.label.isEmpty()) return t.label;
        return Tile.actionLabel(t.action);
    }

    /** NOTHING / AN APP… / AN console FUNCTION… for one gesture; {@code onChanged} runs after a change. */
    static void pick(final Context c, final String gesture, final Runnable onChanged) {
        String[] items = {c.getString(R.string.cfg_gesture_choose_none), c.getString(R.string.cfg_gesture_choose_app),
                c.getString(R.string.cfg_gesture_choose_function)};
        ConsoleDialog.options(c, c.getString(R.string.cfg_gesture_pick_title, label(c, gesture)), items, which -> {
            if (which == 0) {
                HomeGestures.assign(Prefs.get(c), gesture, null);
                Sfx.play(c, Sfx.CLOSE);
                onChanged.run();
            } else if (which == 1) {
                pickApp(c, gesture, onChanged);
            } else {
                pickFunction(c, gesture, onChanged);
            }
        });
    }

    private static void pickApp(final Context c, final String gesture, final Runnable onChanged) {
        AppRepo repo = AppRepo.get(c);
        if (!repo.isLoaded()) {
            repo.load();
            android.widget.Toast.makeText(c, c.getString(R.string.toast_apps_loading), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final List<AppInfo> apps = new ArrayList<>(repo.apps());
        String[] labels = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) labels[i] = apps.get(i).label.toUpperCase(Locale.getDefault());
        ConsoleDialog.options(c, c.getString(R.string.cfg_gesture_pick_app), labels, which -> {
            AppInfo app = apps.get(which);
            Tile t = new Tile();
            t.comp = app.cn.flattenToShortString();
            t.user = app.mainProfile ? -1L : app.serial;
            t.label = HomeLogic.shortLabel(app.label);
            save(c, gesture, t, onChanged);
        });
    }

    /** The console functions, then one entry per app group, Govee light and Nest thermostat. */
    private static void pickFunction(final Context c, final String gesture, final Runnable onChanged) {
        Prefs prefs = Prefs.get(c);
        final List<String> labels = new ArrayList<>();
        final List<Tile> tiles = new ArrayList<>();
        for (int i = 0; i < Tile.ACTIONS.length; i++) {
            labels.add(Tile.ACTION_LABELS[i]);
            tiles.add(function(Tile.ACTIONS[i], null, Tile.ACTION_LABELS[i]));
        }
        for (AppGroups.Group g : AppGroups.load(prefs)) {
            labels.add(c.getString(R.string.fn_group_entry, g.name));
            tiles.add(function(Tile.A_GROUP, g.id, g.name));
        }
        for (Govee.Device light : Govee.devices(prefs)) {
            labels.add("LIGHTS: " + light.title());
            tiles.add(function(Tile.A_GOVEE, light.id, light.title()));
        }
        for (Nest.Device thermostat : Nest.devices(prefs)) {
            labels.add("CLIMATE: " + thermostat.label);
            tiles.add(function(Tile.A_NEST, thermostat.name, "CLIMATE"));
        }
        ConsoleDialog.options(c, c.getString(R.string.cfg_gesture_pick_function), labels.toArray(new String[0]), which -> {
            Tile t = tiles.get(which);
            if (Tile.A_LOCK.equals(t.action) && LockScreen.supported() && !LockService.running()) {
                LockScreen.showDisclosure(c);
            }
            save(c, gesture, t, onChanged);
        });
    }

    private static Tile function(String action, String arg, String label) {
        Tile t = new Tile();
        t.action = action;
        t.arg = arg;
        t.label = label;
        return t;
    }

    private static void save(Context c, String gesture, Tile t, Runnable onChanged) {
        HomeGestures.assign(Prefs.get(c), gesture, t);
        Sfx.play(c, Sfx.CONFIRM);
        onChanged.run();
    }
}
