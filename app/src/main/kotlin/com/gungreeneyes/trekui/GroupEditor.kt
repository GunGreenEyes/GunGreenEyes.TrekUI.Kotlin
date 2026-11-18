package com.gungreeneyes.trekui

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Dialogs that create and edit app groups ([AppGroups]): create, add / remove apps, reorder
 * apps, rename, move the group up or down the list, and delete. Used from the home screen (assign
 * dialog, drawer ADD TO GROUP, long-press on an open [GroupPanel]) and from CONFIG › APP GROUPS,
 * so every method takes a plain [Context]; when it is the home activity the dialog is registered
 * with [MainActivity.showing] so Home dismisses it.
 *
 * Every edit loads the stored list, changes it and saves it straight back, so two screens never
 * overwrite each other's changes. `onChanged` (may be null) runs after each save, for CONFIG to
 * redraw its list.
 *
 * Java interop: every function is [JvmStatic] and [Created] is a `fun interface`, so
 * MainActivity and SettingsActivity keep calling `GroupEditor.create(this, null, g -> ...)`.
 */
internal object GroupEditor {
    /** Called with a group the user just created (and filled). */
    fun interface Created {
        fun created(group: AppGroups.Group)
    }

    private fun register(c: Context, d: Dialog) {
        if (c is MainActivity) c.showing(d)
    }

    private fun changed(onChanged: Runnable?) {
        onChanged?.run()
    }

    // ---- create --------------------------------------------------------------------------------

    /** Asks for a name, creates the group, then opens the app picker for it. */
    @JvmStatic
    fun create(c: Context, onChanged: Runnable?, onCreated: Created?) {
        val prefs = Prefs.get(c)
        if (AppGroups.load(prefs).size >= AppGroups.MAX_GROUPS) {
            Sfx.play(c, Sfx.DENY)
            toast(c, c.getString(R.string.toast_groups_full, AppGroups.MAX_GROUPS))
            return
        }
        register(
            c,
            ConsoleDialog.prompt(c, c.getString(R.string.group_new_title), "", c.getString(R.string.group_name_hint)) { text ->
                val groups = AppGroups.load(prefs)
                val g = AppGroups.create(groups, text) ?: return@prompt
                AppGroups.save(prefs, groups)
                changed(onChanged)
                pickApps(c, g.id) {
                    changed(onChanged)
                    if (onCreated != null) {
                        val filled = AppGroups.find(AppGroups.load(prefs), g.id)
                        if (filled != null) onCreated.created(filled)
                    }
                }
            },
        )
    }

    // ---- manage --------------------------------------------------------------------------------

    /** The edit menu for one group. */
    @JvmStatic
    fun manage(c: Context, groupId: String, onChanged: Runnable?) {
        val prefs = Prefs.get(c)
        val groups = AppGroups.load(prefs)
        val g = AppGroups.find(groups, groupId)
        if (g == null) {
            toast(c, c.getString(R.string.toast_group_missing))
            return
        }
        val index = groups.indexOf(g)
        val labels = ArrayList<String>()
        val actions = ArrayList<Runnable>()
        labels.add(c.getString(R.string.group_add_apps))
        actions.add(Runnable { pickApps(c, groupId, onChanged) })
        if (g.apps.size > 1) {
            labels.add(c.getString(R.string.group_reorder_apps))
            actions.add(Runnable { reorder(c, groupId, onChanged) })
        }
        labels.add(c.getString(R.string.group_rename))
        actions.add(Runnable { rename(c, groupId, onChanged) })
        if (index > 0) {
            labels.add(c.getString(R.string.group_move_up))
            actions.add(Runnable { moveGroup(c, groupId, -1, onChanged) })
        }
        if (index < groups.size - 1) {
            labels.add(c.getString(R.string.group_move_down))
            actions.add(Runnable { moveGroup(c, groupId, 1, onChanged) })
        }
        labels.add(c.getString(R.string.group_delete))
        actions.add(Runnable { confirmDelete(c, groupId, onChanged) })
        register(
            c,
            ConsoleDialog.options(
                c,
                g.name + " • " + c.getString(R.string.group_count, g.apps.size),
                labels.toTypedArray(),
            ) { which ->
                if (which >= 0 && which < actions.size) actions[which].run()
            },
        )
    }

    @JvmStatic
    fun rename(c: Context, groupId: String, onChanged: Runnable?) {
        val prefs = Prefs.get(c)
        val g = AppGroups.find(AppGroups.load(prefs), groupId) ?: return
        register(
            c,
            ConsoleDialog.prompt(
                c,
                c.getString(R.string.group_rename_title, g.name),
                g.name,
                c.getString(R.string.group_name_hint),
            ) { text ->
                val groups = AppGroups.load(prefs)
                val current = AppGroups.find(groups, groupId) ?: return@prompt
                current.name = AppGroups.cleanName(text)
                AppGroups.save(prefs, groups)
                changed(onChanged)
            },
        )
    }

    @JvmStatic
    fun moveGroup(c: Context, groupId: String, delta: Int, onChanged: Runnable?) {
        val prefs = Prefs.get(c)
        val groups = AppGroups.load(prefs)
        val g = AppGroups.find(groups, groupId)
        if (g != null && AppGroups.move(groups, groups.indexOf(g), delta)) {
            AppGroups.save(prefs, groups)
            Sfx.play(c, Sfx.CONFIRM)
            changed(onChanged)
        }
    }

    @JvmStatic
    fun confirmDelete(c: Context, groupId: String, onChanged: Runnable?) {
        val prefs = Prefs.get(c)
        val g = AppGroups.find(AppGroups.load(prefs), groupId) ?: return
        register(
            c,
            ConsoleDialog.message(
                c,
                c.getString(R.string.group_delete),
                c.getString(R.string.group_delete_confirm, g.name),
                arrayOf(c.getString(R.string.group_delete_button), c.getString(R.string.group_cancel)),
            ) { which ->
                if (which != 0) return@message
                val groups = AppGroups.load(prefs)
                if (AppGroups.delete(groups, groupId)) {
                    AppGroups.save(prefs, groups)
                    Sfx.play(c, Sfx.CLOSE)
                    changed(onChanged)
                }
            },
        )
    }

    // ---- apps ----------------------------------------------------------------------------------

    /**
     * Multi-select app picker: the group's apps first (marked ●), then every other app. Tapping toggles
     * an app (up to [AppGroups.MAX_APPS]); DONE or dismissing the dialog saves.
     */
    @JvmStatic
    fun pickApps(c: Context, groupId: String, onChanged: Runnable?) {
        val prefs = Prefs.get(c)
        val groups = AppGroups.load(prefs)
        val g = AppGroups.find(groups, groupId) ?: return
        val repo = AppRepo.get(c)
        if (!repo.isLoaded) {
            repo.load()
            toast(c, c.getString(R.string.toast_apps_loading))
            return
        }
        val ordered = ArrayList<AppInfo>()
        for (m in g.apps) {
            val a = repo.find(m.comp, m.user)
            if (a != null && !ordered.contains(a)) ordered.add(a)
        }
        for (a in repo.apps()) if (!ordered.contains(a)) ordered.add(a)

        val p = prefs.palette()
        val b = ConsoleDialog.base(c, c.getString(R.string.group_pick_title, g.name, g.apps.size, AppGroups.MAX_APPS))
        val done = ConsoleDialog.option(c, "", p.c(0), null)
        val updateDone = Runnable {
            done.text = c.getString(R.string.group_done) + " • " + g.apps.size + "/" + AppGroups.MAX_APPS
        }
        updateDone.run()
        done.setOnClickListener {
            Sfx.play(c, Sfx.CONFIRM)
            b.dialog.dismiss()
        }
        ConsoleDialog.addOption(b, done)
        for (app in ordered) {
            val member = member(app)
            val row = ConsoleDialog.option(c, "", p.c(1), null)
            val paint = Runnable {
                val inGroup = AppGroups.indexOf(g, member) >= 0
                row.text = (if (inGroup) "● " else "○ ") + app.label.uppercase(Locale.getDefault())
                row.alpha = if (inGroup) 1f else 0.55f
            }
            paint.run()
            row.setOnClickListener {
                if (AppGroups.removeApp(g, member)) {
                    Sfx.play(c, Sfx.CLOSE)
                } else if (AppGroups.addApp(g, member)) {
                    Sfx.play(c, Sfx.CONFIRM)
                } else {
                    Sfx.play(c, Sfx.DENY)
                    toast(c, c.getString(R.string.toast_group_full, AppGroups.MAX_APPS))
                }
                paint.run()
                updateDone.run()
            }
            row.contentDescription = app.label
            ConsoleDialog.addOption(b, row)
        }
        b.dialog.setOnDismissListener {
            // re-read so edits made elsewhere meanwhile survive; only this group's apps are replaced
            val latest = AppGroups.load(prefs)
            val target = AppGroups.find(latest, groupId) ?: return@setOnDismissListener
            target.apps.clear()
            target.apps.addAll(g.apps)
            AppGroups.save(prefs, latest)
            changed(onChanged)
        }
        b.dialog.show()
        register(c, b.dialog)
    }

    /** One row per app with ▲ / ▼ keys; saved when the dialog closes. */
    @JvmStatic
    fun reorder(c: Context, groupId: String, onChanged: Runnable?) {
        val prefs = Prefs.get(c)
        val g = AppGroups.find(AppGroups.load(prefs), groupId) ?: return
        val p = prefs.palette()
        val b = ConsoleDialog.base(c, c.getString(R.string.group_reorder_title, g.name))
        val rows = LinearLayout(c)
        rows.orientation = LinearLayout.VERTICAL
        b.body.addView(rows)
        val repo = AppRepo.get(c)
        lateinit var fill: Runnable
        fill = Runnable {
            rows.removeAllViews()
            for (i in g.apps.indices) {
                val index = i
                val m = g.apps[i]
                val app = repo.find(m.comp, m.user)
                val name = app?.label?.uppercase(Locale.getDefault()) ?: m.comp
                val row = LinearLayout(c)
                row.gravity = Gravity.CENTER_VERTICAL
                val label = Ui.oneLine(Ui.bold(c, HomeLogic.tileNumber(i) + "  " + name, 15f, p.textHi))
                row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(
                    arrow(c, "▲", p.c(2), index > 0) {
                        AppGroups.move(g.apps, index, -1)
                        fill.run()
                    },
                )
                row.addView(
                    arrow(c, "▼", p.c(3), index < g.apps.size - 1) {
                        AppGroups.move(g.apps, index, 1)
                        fill.run()
                    },
                )
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = Ui.dp(c, Ui.XS.toFloat())
                rows.addView(row, lp)
            }
        }
        fill.run()
        ConsoleDialog.addOption(
            b,
            ConsoleDialog.option(c, c.getString(R.string.group_done), p.c(0)) {
                Sfx.play(c, Sfx.CONFIRM)
                b.dialog.dismiss()
            },
        )
        b.dialog.setOnDismissListener {
            val latest = AppGroups.load(prefs)
            val target = AppGroups.find(latest, groupId) ?: return@setOnDismissListener
            target.apps.clear()
            target.apps.addAll(g.apps)
            AppGroups.save(prefs, latest)
            changed(onChanged)
        }
        b.dialog.show()
        register(c, b.dialog)
    }

    private fun arrow(c: Context, glyph: String, color: Int, enabled: Boolean, l: View.OnClickListener): TextView {
        val t = Ui.button(c, glyph, color, Ui.PILL, l)
        t.gravity = Gravity.CENTER
        t.isEnabled = enabled
        t.alpha = if (enabled) 1f else 0.3f
        val size = Ui.dp(c, Ui.MIN_TOUCH_DP)
        val lp = LinearLayout.LayoutParams(size, size)
        lp.leftMargin = Ui.dp(c, Ui.XS.toFloat())
        t.layoutParams = lp
        return t
    }

    /** Drawer long-press › ADD TO GROUP: pick an existing group or create one with this app in it. */
    @JvmStatic
    fun addAppToGroup(a: MainActivity, app: AppInfo) {
        val prefs = Prefs.get(a)
        val groups = AppGroups.load(prefs)
        val labels = Array(groups.size + 1) { i ->
            if (i < groups.size) {
                val g = groups[i]
                g.name + " (" + g.apps.size + "/" + AppGroups.MAX_APPS + ")"
            } else {
                a.getString(R.string.fn_new_group)
            }
        }
        a.showing(
            ConsoleDialog.options(
                a,
                a.getString(R.string.group_add_to_title, app.label.uppercase(Locale.getDefault())),
                labels,
            ) { which ->
                if (which == groups.size) {
                    if (groups.size >= AppGroups.MAX_GROUPS) {
                        Sfx.play(a, Sfx.DENY)
                        a.toast(a.getString(R.string.toast_groups_full, AppGroups.MAX_GROUPS))
                        return@options
                    }
                    a.showing(
                        ConsoleDialog.prompt(a, a.getString(R.string.group_new_title), "", a.getString(R.string.group_name_hint)) { text ->
                            val latest = AppGroups.load(prefs)
                            val g = AppGroups.create(latest, text) ?: return@prompt
                            AppGroups.addApp(g, member(app))
                            AppGroups.save(prefs, latest)
                            Sfx.play(a, Sfx.CONFIRM)
                            a.toast(a.getString(R.string.toast_added_to_group, g.name))
                        },
                    )
                    return@options
                }
                val latest = AppGroups.load(prefs)
                val g = AppGroups.find(latest, groups[which].id) ?: return@options
                val m = member(app)
                if (AppGroups.indexOf(g, m) >= 0) {
                    Sfx.play(a, Sfx.DENY)
                    a.toast(a.getString(R.string.toast_already_in_group, g.name))
                } else if (AppGroups.addApp(g, m)) {
                    AppGroups.save(prefs, latest)
                    Sfx.play(a, Sfx.CONFIRM)
                    a.toast(a.getString(R.string.toast_added_to_group, g.name))
                } else {
                    Sfx.play(a, Sfx.DENY)
                    a.toast(a.getString(R.string.toast_group_full, AppGroups.MAX_APPS))
                }
            },
        )
    }

    /** The group member for an app (main-profile apps are stored without a user serial, like tiles). */
    @JvmStatic
    fun member(app: AppInfo): AppGroups.Member = AppGroups.Member(app.cn.flattenToShortString(), if (app.mainProfile) -1L else app.serial)

    private fun toast(c: Context, message: String) {
        if (c is MainActivity) {
            c.toast(message)
        } else {
            Toast.makeText(c, message, Toast.LENGTH_SHORT).show()
        }
    }
}
