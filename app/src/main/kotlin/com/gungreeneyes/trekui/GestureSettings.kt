package com.gungreeneyes.trekui

import android.content.Context
import android.widget.Toast
import java.util.Locale

/**
 * CONFIG › GESTURES helpers (gap A9): what each gesture is called, what it is set to, and the dialogs
 * that assign it an app or an console function (including OPEN GROUP and LOCK SCREEN). The section rows
 * themselves are built by `SettingsActivity.gesturesSection`.
 */
internal object GestureSettings {

    /** Display name of a gesture id from [HomeGestures.ALL]. */
    @JvmStatic
    fun label(c: Context, gesture: String): String = when (gesture) {
        HomeGestures.DOUBLE_TAP -> c.getString(R.string.gesture_double_tap)
        HomeGestures.TWO_FINGER_UP -> c.getString(R.string.gesture_two_finger_up)
        HomeGestures.TWO_FINGER_DOWN -> c.getString(R.string.gesture_two_finger_down)
        HomeGestures.PINCH_IN -> c.getString(R.string.gesture_pinch_in)
        HomeGestures.PINCH_OUT -> c.getString(R.string.gesture_pinch_out)
        HomeGestures.SWIPE_LEFT -> c.getString(R.string.gesture_swipe_left)
        HomeGestures.SWIPE_RIGHT -> c.getString(R.string.gesture_swipe_right)
        else -> gesture.uppercase(Locale.US)
    }

    /** What a gesture currently runs: an app's name, a function's name, a group's name, or NOTHING. */
    @JvmStatic
    fun describe(c: Context, gesture: String): String? {
        val t = HomeGestures.assigned(Prefs.get(c), gesture) ?: return c.getString(R.string.cfg_gesture_none)
        if (t.action == null) {
            val app = AppRepo.get(c).find(t.comp, t.user)
            return app?.label?.uppercase(Locale.getDefault()) ?: t.label
        }
        if (Tile.A_GROUP == t.action) {
            val g = AppGroups.find(AppGroups.load(Prefs.get(c)), t.arg)
            return c.getString(R.string.fn_group_entry, g?.name ?: "?")
        }
        if (!t.label.isNullOrEmpty()) return t.label
        return Tile.actionLabel(t.action)
    }

    /** NOTHING / AN APP… / AN console FUNCTION… for one gesture; `onChanged` runs after a change. */
    @JvmStatic
    fun pick(c: Context, gesture: String, onChanged: Runnable) {
        val items = arrayOf(
            c.getString(R.string.cfg_gesture_choose_none), c.getString(R.string.cfg_gesture_choose_app),
            c.getString(R.string.cfg_gesture_choose_function),
        )
        ConsoleDialog.options(c, c.getString(R.string.cfg_gesture_pick_title, label(c, gesture)), items) { which ->
            when (which) {
                0 -> {
                    HomeGestures.assign(Prefs.get(c), gesture, null)
                    Sfx.play(c, Sfx.CLOSE)
                    onChanged.run()
                }
                1 -> pickApp(c, gesture, onChanged)
                else -> pickFunction(c, gesture, onChanged)
            }
        }
    }

    private fun pickApp(c: Context, gesture: String, onChanged: Runnable) {
        val repo = AppRepo.get(c)
        if (!repo.isLoaded) {
            repo.load()
            Toast.makeText(c, c.getString(R.string.toast_apps_loading), Toast.LENGTH_SHORT).show()
            return
        }
        val apps = ArrayList(repo.apps())
        val labels = Array(apps.size) { i -> apps[i].label.uppercase(Locale.getDefault()) }
        ConsoleDialog.options(c, c.getString(R.string.cfg_gesture_pick_app), labels) { which ->
            val app = apps[which]
            val t = Tile()
            t.comp = app.cn.flattenToShortString()
            t.user = if (app.mainProfile) -1L else app.serial
            t.label = HomeLogic.shortLabel(app.label)
            save(c, gesture, t, onChanged)
        }
    }

    /** The console functions, then one entry per app group, Govee light and Nest thermostat. */
    private fun pickFunction(c: Context, gesture: String, onChanged: Runnable) {
        val prefs = Prefs.get(c)
        val labels = ArrayList<String>()
        val tiles = ArrayList<Tile>()
        for (i in Tile.ACTIONS.indices) {
            labels.add(Tile.ACTION_LABELS[i])
            tiles.add(function(Tile.ACTIONS[i], null, Tile.ACTION_LABELS[i]))
        }
        for (g in AppGroups.load(prefs)) {
            labels.add(c.getString(R.string.fn_group_entry, g.name))
            tiles.add(function(Tile.A_GROUP, g.id, g.name))
        }
        for (light in Govee.devices(prefs)) {
            labels.add("LIGHTS: " + light.title())
            tiles.add(function(Tile.A_GOVEE, light.id, light.title()))
        }
        for (thermostat in Nest.devices(prefs)) {
            labels.add("CLIMATE: " + thermostat.label)
            tiles.add(function(Tile.A_NEST, thermostat.name, "CLIMATE"))
        }
        ConsoleDialog.options(c, c.getString(R.string.cfg_gesture_pick_function), labels.toTypedArray()) { which ->
            val t = tiles[which]
            if (Tile.A_LOCK == t.action && LockScreen.supported() && !LockService.running()) {
                LockScreen.showDisclosure(c)
            }
            save(c, gesture, t, onChanged)
        }
    }

    private fun function(action: String, arg: String?, label: String?): Tile = Tile().apply {
        this.action = action
        this.arg = arg
        this.label = label
    }

    private fun save(c: Context, gesture: String, t: Tile, onChanged: Runnable) {
        HomeGestures.assign(Prefs.get(c), gesture, t)
        Sfx.play(c, Sfx.CONFIRM)
        onChanged.run()
    }
}
