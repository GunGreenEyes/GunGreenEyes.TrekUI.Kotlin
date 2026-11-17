package com.gungreeneyes.trekui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CHANGE ICON (LCA-T22): the per-app icon picker opened from the drawer's long-press menu.
 *
 * <p>Three ways to restyle one app: a grid of every pictogram in the current interface's style
 * ({@link IconFactory#glyph} picks the plain console or pipe set), any drawable from an installed icon
 * pack (searchable, loaded off the main thread in pages of {@link #PACK_PAGE}), the app's own icon,
 * or AUTOMATIC to forget the choice. Choices are stored as {@code appIcon.<key>} preferences (see
 * {@link IconPacks}) and applied by reloading {@link AppRepo}, so the drawer, dock and tiles all
 * pick them up together.
 */
final class IconPicker {
    /** Largest number of pack drawables shown at once (packs can hold several thousand). */
    static final int PACK_PAGE = 60;
    /** Side of one grid cell, dp. */
    static final float CELL_DP = 60f;

    private IconPicker() {}

    /**
     * Pure: grid columns for a dialog {@code widthDp} wide with {@code cellDp} cells, at least 3.
     */
    static int columns(float widthDp, float cellDp) {
        return Math.max(3, (int) (widthDp / cellDp));
    }

    /**
     * Pure: the pack drawables matching a search (every word must appear in the name, ignoring
     * case and "_"), at most {@code limit}; the app's own pack icon, when known, comes first.
     */
    static List<String> filter(List<String> drawables, String query, String suggested, int limit) {
        List<String> out = new ArrayList<>();
        if (suggested != null && drawables.contains(suggested)) out.add(suggested);
        String[] words = query == null ? new String[0] : query.trim().toLowerCase(Locale.US).split("\\s+");
        for (String d : drawables) {
            if (out.size() >= limit) break;
            if (d.equals(suggested)) continue;
            String n = d.toLowerCase(Locale.US).replace('_', ' ');
            boolean ok = true;
            for (String w : words) if (!w.isEmpty() && !n.contains(w)) { ok = false; break; }
            if (ok) out.add(d);
        }
        return out;
    }

    /** Opens the main CHANGE ICON menu for {@code app}. */
    static void show(final MainActivity a, final AppInfo app) {
        final Context c = a;
        final String[] items = {
            c.getString(R.string.icon_pick_pictogram),
            c.getString(R.string.icon_pick_pack),
            c.getString(R.string.icon_pick_own),
            c.getString(R.string.icon_pick_auto),
        };
        String title = c.getString(R.string.icon_pick_title, app.label.toUpperCase(Locale.getDefault()));
        a.showing(ConsoleDialog.options(c, title, items, new ConsoleDialog.OnPick() {
            @Override public void pick(int which) {
                switch (which) {
                    case 0: pictograms(a, app); break;
                    case 1: packs(a, app); break;
                    case 2: store(a, app, IconPacks.CHOICE_APP); break;
                    default: store(a, app, null); break;
                }
            }
        }));
    }

    /** Saves (or with null, clears) an app's icon choice and reloads the catalogue so every view updates. */
    static void store(MainActivity a, AppInfo app, String choice) {
        Prefs prefs = Prefs.get(a);
        String key = IconPacks.prefKey(app.key);
        if (choice == null) prefs.sp.edit().remove(key).apply();
        else prefs.put(key, choice);
        Sfx.play(a, Sfx.CONFIRM);
        AppRepo.get(a).load();
    }

    /** Dialog width in dp, as {@link ConsoleDialog} sizes it. */
    private static float dialogWidthDp(Context c) {
        ScreenMetrics m = ScreenMetrics.of(c);
        return ConsoleDialog.widthDpFor(m.widthDp, Ui.zoom(c)) - 2 * 14f; // panel padding
    }

    /** A grid of every pictogram in the current style. */
    private static void pictograms(final MainActivity a, final AppInfo app) {
        final Context c = a;
        Palette p = Prefs.get(c).palette();
        final ConsoleDialog.Built b = ConsoleDialog.base(c, c.getString(R.string.icon_pick_pictogram));
        int cols = columns(dialogWidthDp(c), CELL_DP);
        LinearLayout row = null;
        for (int i = 0; i < Pictograms.ALL.length; i++) {
            final String name = Pictograms.ALL[i];
            if (i % cols == 0) row = newRow(b);
            ImageView cell = cell(c, row, cols);
            Bitmap g = IconFactory.glyph(c, name);
            if (g != null) cell.setImageBitmap(g);
            cell.setColorFilter(name.equals(app.glyph) ? p.c(0) : p.c(1 + i % 3), PorterDuff.Mode.SRC_IN);
            cell.setContentDescription(name);
            cell.setTag("glyph_" + name);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    b.dialog.dismiss();
                    store(a, app, IconPacks.CHOICE_GLYPH + name);
                }
            });
        }
        padRow(row, Pictograms.ALL.length, cols);
        b.dialog.show();
        a.showing(b.dialog);
    }

    /** Lists installed packs (the selected one first) and opens the chosen pack's grid. */
    private static void packs(final MainActivity a, final AppInfo app) {
        final Context c = a.getApplicationContext();
        IconPacks.async(new IconPacks.Callable<List<IconPacks.Pack>>() {
            @Override public List<IconPacks.Pack> call() { return IconPacks.installed(c); }
        }, new IconPacks.Callback<List<IconPacks.Pack>>() {
            @Override public void done(final List<IconPacks.Pack> list) {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (list == null || list.isEmpty()) {
                    a.toast(a.getString(R.string.icon_pack_none_installed));
                    return;
                }
                if (list.size() == 1) { packGrid(a, app, list.get(0)); return; }
                String[] names = new String[list.size()];
                for (int i = 0; i < names.length; i++) names[i] = list.get(i).label.toUpperCase(Locale.getDefault());
                a.showing(ConsoleDialog.options(a, a.getString(R.string.icon_pick_pack), names, new ConsoleDialog.OnPick() {
                    @Override public void pick(int which) { packGrid(a, app, list.get(which)); }
                }));
            }
        });
    }

    /** A searchable grid of one pack's drawables; loads the pack and each page off the main thread. */
    private static void packGrid(final MainActivity a, final AppInfo app, final IconPacks.Pack pack) {
        final Context appCtx = a.getApplicationContext();
        Palette p = Prefs.get(a).palette();
        final ConsoleDialog.Built b = ConsoleDialog.base(a, pack.label.toUpperCase(Locale.getDefault()));
        final EditText search = new EditText(a);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setHint(a.getString(R.string.icon_pack_search_hint));
        search.setTextColor(p.textHi);
        search.setHintTextColor(Ui.alpha(p.textLo, 0.7f));
        search.setTag("icon_pack_search");
        b.body.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout grid = new LinearLayout(a);
        grid.setOrientation(LinearLayout.VERTICAL);
        b.body.addView(grid);
        final TextView status = Ui.text(a, a.getString(R.string.icon_pack_loading), 14, p.textLo);
        grid.addView(status);
        b.dialog.show();
        a.showing(b.dialog);

        IconPacks.async(new IconPacks.Callable<IconPackParser.Result>() {
            @Override public IconPackParser.Result call() { return IconPacks.load(appCtx, pack.pkg); }
        }, new IconPacks.Callback<IconPackParser.Result>() {
            @Override public void done(final IconPackParser.Result r) {
                if (!b.dialog.isShowing()) return;
                if (r == null || r.drawables.isEmpty()) {
                    status.setText(a.getString(R.string.icon_pack_empty));
                    return;
                }
                final List<String> all = r.drawableList();
                final String suggested = r.drawableFor(app.pkg, app.cn.getClassName());
                final Runnable refresh = new Runnable() {
                    @Override public void run() {
                        fillPackGrid(a, app, pack, b, grid, filter(all, search.getText().toString(), suggested, PACK_PAGE));
                    }
                };
                search.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int st, int n, int after) { }
                    @Override public void onTextChanged(CharSequence s, int st, int before, int n) { }
                    @Override public void afterTextChanged(Editable e) {
                        grid.removeCallbacks(refresh);
                        grid.postDelayed(refresh, 250);
                    }
                });
                refresh.run();
            }
        });
    }

    /** Rebuilds the pack grid with {@code names}; the drawables arrive from the worker. */
    private static void fillPackGrid(final MainActivity a, final AppInfo app, final IconPacks.Pack pack,
                                     final ConsoleDialog.Built b, final LinearLayout grid, final List<String> names) {
        grid.removeAllViews();
        final Context appCtx = a.getApplicationContext();
        int cols = columns(dialogWidthDp(a), CELL_DP);
        final List<ImageView> cells = new ArrayList<>();
        LinearLayout row = null;
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            if (i % cols == 0) {
                row = new LinearLayout(a);
                grid.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, CELL_DP)));
            }
            ImageView cell = cell(a, row, cols);
            cell.setContentDescription(name.replace('_', ' '));
            cell.setTag("pack_" + name);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    b.dialog.dismiss();
                    store(a, app, IconPacks.packChoice(pack.pkg, name));
                }
            });
            cells.add(cell);
        }
        padRow(row, names.size(), cols);
        IconPacks.async(new IconPacks.Callable<List<Drawable>>() {
            @Override public List<Drawable> call() {
                List<Drawable> out = new ArrayList<>();
                for (String n : names) out.add(IconPacks.drawable(appCtx, pack.pkg, n));
                return out;
            }
        }, new IconPacks.Callback<List<Drawable>>() {
            @Override public void done(List<Drawable> ds) {
                if (ds == null || !b.dialog.isShowing()) return;
                for (int i = 0; i < ds.size() && i < cells.size(); i++) {
                    if (ds.get(i) != null) cells.get(i).setImageDrawable(ds.get(i));
                }
            }
        });
    }

    private static LinearLayout newRow(ConsoleDialog.Built b) {
        Context c = b.body.getContext();
        LinearLayout row = new LinearLayout(c);
        b.body.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, CELL_DP)));
        return row;
    }

    private static ImageView cell(Context c, LinearLayout row, int cols) {
        ImageView v = new ImageView(c);
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Ui.dp(c, 8);
        v.setPadding(pad, pad, pad, pad);
        v.setBackground(Ui.touchArea(c, 12));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return v;
    }

    /** Fills a partial last row with spacers so its cells keep the full-row width. */
    private static void padRow(LinearLayout row, int count, int cols) {
        if (row == null) return;
        int rem = count % cols;
        if (rem == 0) return;
        for (int i = rem; i < cols; i++) {
            row.addView(new View(row.getContext()), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }
    }

    /** Dismisses {@code d} when it is still showing. */
    static void dismiss(Dialog d) {
        if (d != null && d.isShowing()) d.dismiss();
    }
}
