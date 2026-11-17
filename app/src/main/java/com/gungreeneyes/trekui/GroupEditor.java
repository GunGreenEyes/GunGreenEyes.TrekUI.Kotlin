package com.gungreeneyes.trekui;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dialogs that create and edit app groups ({@link AppGroups}): create, add / remove apps, reorder
 * apps, rename, move the group up or down the list, and delete. Used from the home screen (assign
 * dialog, drawer ADD TO GROUP, long-press on an open {@link GroupPanel}) and from CONFIG › APP GROUPS,
 * so every method takes a plain {@link Context}; when it is the home activity the dialog is registered
 * with {@link MainActivity#showing} so Home dismisses it.
 *
 * <p>Every edit loads the stored list, changes it and saves it straight back, so two screens never
 * overwrite each other's changes. {@code onChanged} (may be null) runs after each save, for CONFIG to
 * redraw its list.
 */
final class GroupEditor {

    /** Called with a group the user just created (and filled). */
    interface Created {
        void created(AppGroups.Group group);
    }

    private GroupEditor() {}

    private static void register(Context c, Dialog d) {
        if (c instanceof MainActivity) ((MainActivity) c).showing(d);
    }

    private static void changed(Runnable onChanged) {
        if (onChanged != null) onChanged.run();
    }

    // ---- create --------------------------------------------------------------------------------

    /** Asks for a name, creates the group, then opens the app picker for it. */
    static void create(final Context c, final Runnable onChanged, final Created onCreated) {
        final Prefs prefs = Prefs.get(c);
        if (AppGroups.load(prefs).size() >= AppGroups.MAX_GROUPS) {
            Sfx.play(c, Sfx.DENY);
            toast(c, c.getString(R.string.toast_groups_full, AppGroups.MAX_GROUPS));
            return;
        }
        register(c, ConsoleDialog.prompt(c, c.getString(R.string.group_new_title), "", c.getString(R.string.group_name_hint), text -> {
            List<AppGroups.Group> groups = AppGroups.load(prefs);
            AppGroups.Group g = AppGroups.create(groups, text);
            if (g == null) return;
            AppGroups.save(prefs, groups);
            changed(onChanged);
            pickApps(c, g.id, () -> {
                changed(onChanged);
                if (onCreated != null) {
                    AppGroups.Group filled = AppGroups.find(AppGroups.load(prefs), g.id);
                    if (filled != null) onCreated.created(filled);
                }
            });
        }));
    }

    // ---- manage --------------------------------------------------------------------------------

    /** The edit menu for one group. */
    static void manage(final Context c, final String groupId, final Runnable onChanged) {
        final Prefs prefs = Prefs.get(c);
        final List<AppGroups.Group> groups = AppGroups.load(prefs);
        final AppGroups.Group g = AppGroups.find(groups, groupId);
        if (g == null) {
            toast(c, c.getString(R.string.toast_group_missing));
            return;
        }
        final int index = groups.indexOf(g);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add(c.getString(R.string.group_add_apps));
        actions.add(() -> pickApps(c, groupId, onChanged));
        if (g.apps.size() > 1) {
            labels.add(c.getString(R.string.group_reorder_apps));
            actions.add(() -> reorder(c, groupId, onChanged));
        }
        labels.add(c.getString(R.string.group_rename));
        actions.add(() -> rename(c, groupId, onChanged));
        if (index > 0) {
            labels.add(c.getString(R.string.group_move_up));
            actions.add(() -> moveGroup(c, groupId, -1, onChanged));
        }
        if (index < groups.size() - 1) {
            labels.add(c.getString(R.string.group_move_down));
            actions.add(() -> moveGroup(c, groupId, 1, onChanged));
        }
        labels.add(c.getString(R.string.group_delete));
        actions.add(() -> confirmDelete(c, groupId, onChanged));
        register(c, ConsoleDialog.options(c, g.name + " • " + c.getString(R.string.group_count, g.apps.size()),
                labels.toArray(new String[0]), which -> {
                    if (which >= 0 && which < actions.size()) actions.get(which).run();
                }));
    }

    static void rename(final Context c, final String groupId, final Runnable onChanged) {
        final Prefs prefs = Prefs.get(c);
        AppGroups.Group g = AppGroups.find(AppGroups.load(prefs), groupId);
        if (g == null) return;
        register(c, ConsoleDialog.prompt(c, c.getString(R.string.group_rename_title, g.name), g.name,
                c.getString(R.string.group_name_hint), text -> {
                    List<AppGroups.Group> groups = AppGroups.load(prefs);
                    AppGroups.Group current = AppGroups.find(groups, groupId);
                    if (current == null) return;
                    current.name = AppGroups.cleanName(text);
                    AppGroups.save(prefs, groups);
                    changed(onChanged);
                }));
    }

    static void moveGroup(Context c, String groupId, int delta, Runnable onChanged) {
        Prefs prefs = Prefs.get(c);
        List<AppGroups.Group> groups = AppGroups.load(prefs);
        AppGroups.Group g = AppGroups.find(groups, groupId);
        if (g != null && AppGroups.move(groups, groups.indexOf(g), delta)) {
            AppGroups.save(prefs, groups);
            Sfx.play(c, Sfx.CONFIRM);
            changed(onChanged);
        }
    }

    static void confirmDelete(final Context c, final String groupId, final Runnable onChanged) {
        final Prefs prefs = Prefs.get(c);
        AppGroups.Group g = AppGroups.find(AppGroups.load(prefs), groupId);
        if (g == null) return;
        register(c, ConsoleDialog.message(c, c.getString(R.string.group_delete), c.getString(R.string.group_delete_confirm, g.name),
                new String[]{c.getString(R.string.group_delete_button), c.getString(R.string.group_cancel)}, which -> {
                    if (which != 0) return;
                    List<AppGroups.Group> groups = AppGroups.load(prefs);
                    if (AppGroups.delete(groups, groupId)) {
                        AppGroups.save(prefs, groups);
                        Sfx.play(c, Sfx.CLOSE);
                        changed(onChanged);
                    }
                }));
    }

    // ---- apps ----------------------------------------------------------------------------------

    /**
     * Multi-select app picker: the group's apps first (marked ●), then every other app. Tapping toggles
     * an app (up to {@link AppGroups#MAX_APPS}); DONE or dismissing the dialog saves.
     */
    static void pickApps(final Context c, final String groupId, final Runnable onChanged) {
        final Prefs prefs = Prefs.get(c);
        final List<AppGroups.Group> groups = AppGroups.load(prefs);
        final AppGroups.Group g = AppGroups.find(groups, groupId);
        if (g == null) return;
        AppRepo repo = AppRepo.get(c);
        if (!repo.isLoaded()) {
            repo.load();
            toast(c, c.getString(R.string.toast_apps_loading));
            return;
        }
        List<AppInfo> ordered = new ArrayList<>();
        for (AppGroups.Member m : g.apps) {
            AppInfo a = repo.find(m.comp, m.user);
            if (a != null && !ordered.contains(a)) ordered.add(a);
        }
        for (AppInfo a : repo.apps()) if (!ordered.contains(a)) ordered.add(a);

        final Palette p = prefs.palette();
        final ConsoleDialog.Built b = ConsoleDialog.base(c, c.getString(R.string.group_pick_title, g.name, g.apps.size(), AppGroups.MAX_APPS));
        final TextView done = ConsoleDialog.option(c, "", p.c(0), null);
        final Runnable updateDone = () -> done.setText(c.getString(R.string.group_done) + " • "
                + g.apps.size() + "/" + AppGroups.MAX_APPS);
        updateDone.run();
        done.setOnClickListener(v -> {
            Sfx.play(c, Sfx.CONFIRM);
            b.dialog.dismiss();
        });
        ConsoleDialog.addOption(b, done);
        for (final AppInfo app : ordered) {
            final AppGroups.Member member = member(app);
            final TextView row = ConsoleDialog.option(c, "", p.c(1), null);
            final Runnable paint = () -> {
                boolean in = AppGroups.indexOf(g, member) >= 0;
                row.setText((in ? "● " : "○ ") + app.label.toUpperCase(Locale.getDefault()));
                row.setAlpha(in ? 1f : 0.55f);
            };
            paint.run();
            row.setOnClickListener(v -> {
                if (AppGroups.removeApp(g, member)) {
                    Sfx.play(c, Sfx.CLOSE);
                } else if (AppGroups.addApp(g, member)) {
                    Sfx.play(c, Sfx.CONFIRM);
                } else {
                    Sfx.play(c, Sfx.DENY);
                    toast(c, c.getString(R.string.toast_group_full, AppGroups.MAX_APPS));
                }
                paint.run();
                updateDone.run();
            });
            row.setContentDescription(app.label);
            ConsoleDialog.addOption(b, row);
        }
        b.dialog.setOnDismissListener(d -> {
            // re-read so edits made elsewhere meanwhile survive; only this group's apps are replaced
            List<AppGroups.Group> latest = AppGroups.load(prefs);
            AppGroups.Group target = AppGroups.find(latest, groupId);
            if (target == null) return;
            target.apps.clear();
            target.apps.addAll(g.apps);
            AppGroups.save(prefs, latest);
            changed(onChanged);
        });
        b.dialog.show();
        register(c, b.dialog);
    }

    /** One row per app with ▲ / ▼ keys; saved when the dialog closes. */
    static void reorder(final Context c, final String groupId, final Runnable onChanged) {
        final Prefs prefs = Prefs.get(c);
        final AppGroups.Group g = AppGroups.find(AppGroups.load(prefs), groupId);
        if (g == null) return;
        final Palette p = prefs.palette();
        final ConsoleDialog.Built b = ConsoleDialog.base(c, c.getString(R.string.group_reorder_title, g.name));
        final LinearLayout rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        b.body.addView(rows);
        final AppRepo repo = AppRepo.get(c);
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            rows.removeAllViews();
            for (int i = 0; i < g.apps.size(); i++) {
                final int index = i;
                AppGroups.Member m = g.apps.get(i);
                AppInfo app = repo.find(m.comp, m.user);
                String name = app != null ? app.label.toUpperCase(Locale.getDefault()) : m.comp;
                LinearLayout row = new LinearLayout(c);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView label = Ui.oneLine(Ui.bold(c, HomeLogic.tileNumber(i) + "  " + name, 15, p.textHi));
                row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(arrow(c, "▲", p.c(2), index > 0, v -> {
                    AppGroups.move(g.apps, index, -1);
                    fill[0].run();
                }));
                row.addView(arrow(c, "▼", p.c(3), index < g.apps.size() - 1, v -> {
                    AppGroups.move(g.apps, index, 1);
                    fill[0].run();
                }));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = Ui.dp(c, Ui.XS);
                rows.addView(row, lp);
            }
        };
        fill[0].run();
        ConsoleDialog.addOption(b, ConsoleDialog.option(c, c.getString(R.string.group_done), p.c(0), v -> {
            Sfx.play(c, Sfx.CONFIRM);
            b.dialog.dismiss();
        }));
        b.dialog.setOnDismissListener(d -> {
            List<AppGroups.Group> latest = AppGroups.load(prefs);
            AppGroups.Group target = AppGroups.find(latest, groupId);
            if (target == null) return;
            target.apps.clear();
            target.apps.addAll(g.apps);
            AppGroups.save(prefs, latest);
            changed(onChanged);
        });
        b.dialog.show();
        register(c, b.dialog);
    }

    private static TextView arrow(Context c, String glyph, int color, boolean enabled, View.OnClickListener l) {
        TextView t = Ui.button(c, glyph, color, Ui.PILL, l);
        t.setGravity(android.view.Gravity.CENTER);
        t.setEnabled(enabled);
        t.setAlpha(enabled ? 1f : 0.3f);
        int size = Ui.dp(c, Ui.MIN_TOUCH_DP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = Ui.dp(c, Ui.XS);
        t.setLayoutParams(lp);
        return t;
    }

    /** Drawer long-press › ADD TO GROUP: pick an existing group or create one with this app in it. */
    static void addAppToGroup(final MainActivity a, final AppInfo app) {
        final Prefs prefs = Prefs.get(a);
        final List<AppGroups.Group> groups = AppGroups.load(prefs);
        String[] labels = new String[groups.size() + 1];
        for (int i = 0; i < groups.size(); i++) {
            AppGroups.Group g = groups.get(i);
            labels[i] = g.name + " (" + g.apps.size() + "/" + AppGroups.MAX_APPS + ")";
        }
        labels[groups.size()] = a.getString(R.string.fn_new_group);
        a.showing(ConsoleDialog.options(a, a.getString(R.string.group_add_to_title, app.label.toUpperCase(Locale.getDefault())), labels, which -> {
            if (which == groups.size()) {
                if (groups.size() >= AppGroups.MAX_GROUPS) {
                    Sfx.play(a, Sfx.DENY);
                    a.toast(a.getString(R.string.toast_groups_full, AppGroups.MAX_GROUPS));
                    return;
                }
                a.showing(ConsoleDialog.prompt(a, a.getString(R.string.group_new_title), "", a.getString(R.string.group_name_hint), text -> {
                    List<AppGroups.Group> latest = AppGroups.load(prefs);
                    AppGroups.Group g = AppGroups.create(latest, text);
                    if (g == null) return;
                    AppGroups.addApp(g, member(app));
                    AppGroups.save(prefs, latest);
                    Sfx.play(a, Sfx.CONFIRM);
                    a.toast(a.getString(R.string.toast_added_to_group, g.name));
                }));
                return;
            }
            List<AppGroups.Group> latest = AppGroups.load(prefs);
            AppGroups.Group g = AppGroups.find(latest, groups.get(which).id);
            if (g == null) return;
            AppGroups.Member m = member(app);
            if (AppGroups.indexOf(g, m) >= 0) {
                Sfx.play(a, Sfx.DENY);
                a.toast(a.getString(R.string.toast_already_in_group, g.name));
            } else if (AppGroups.addApp(g, m)) {
                AppGroups.save(prefs, latest);
                Sfx.play(a, Sfx.CONFIRM);
                a.toast(a.getString(R.string.toast_added_to_group, g.name));
            } else {
                Sfx.play(a, Sfx.DENY);
                a.toast(a.getString(R.string.toast_group_full, AppGroups.MAX_APPS));
            }
        }));
    }

    /** The group member for an app (main-profile apps are stored without a user serial, like tiles). */
    static AppGroups.Member member(AppInfo app) {
        return new AppGroups.Member(app.cn.flattenToShortString(), app.mainProfile ? -1L : app.serial);
    }

    private static void toast(Context c, String message) {
        if (c instanceof MainActivity) ((MainActivity) c).toast(message);
        else android.widget.Toast.makeText(c, message, android.widget.Toast.LENGTH_SHORT).show();
    }
}
