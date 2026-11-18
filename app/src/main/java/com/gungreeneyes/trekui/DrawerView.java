package com.gungreeneyes.trekui;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.AnimationSet;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.GridLayoutAnimationController;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen app drawer: an {@link ConsoleFrame} with a category sidebar, a search row and the icon
 * grid. Also used as an app picker ({@link Pick}) when assigning tiles, dock keys and slots.
 *
 * <p>Responsive: the grid's column count follows the drawer's own size (so rotation, split-screen and
 * folding are handled without rebuilding) via {@link #columnsFor}: the user's CONFIG column count is
 * what a 360dp reference phone shows, and wider windows add columns as {@link ScreenMetrics#columns}
 * allows. Search matching is the pure {@link #matchRank}.
 */
class DrawerView extends FrameLayout {
    /** Receives the app chosen while the drawer is in picker mode. */
    interface Pick {
        String title();
        void picked(AppInfo app);
    }

    /** Range of the user's column setting. */
    static final int MIN_USER_COLS = 3, MAX_USER_COLS = 6;
    /** Fewest and most columns the grid ever shows. */
    static final int MIN_COLS = 3, MAX_COLS = 12;
    /** Width of the category sidebar, design dp. */
    static final float SIDEBAR_DP = 76f;
    /** Most app shortcuts listed in the long-press menu. */
    static final int MAX_SHORTCUTS = 4;
    /** Sidebar colour rotation (palette slots). */
    private static final int[] CATEGORY_COLORS = {0, 1, 3, 2, 4, 5};
    /** Brightness of unselected category buttons. */
    private static final float UNSELECTED_DARKEN = 0.45f;

    private final MainActivity a;
    private final Palette pal;
    private final Prefs prefs;
    private final ConsoleFrame frame;
    private final EditText search;
    private final GridView grid;
    private final TextView titleView, countView, empty;
    private final LinearLayout cats;
    private final Adapter adapter = new Adapter();
    private final List<AppInfo> shown = new ArrayList<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private int cat = AppInfo.CAT_ALL;
    private Pick pick;
    private boolean open;

    DrawerView(MainActivity act) {
        super(act);
        a = act;
        prefs = Prefs.get(act);
        pal = prefs.palette();
        // Opaque: at 96% the bright home labels (Imperial) ghosted through the app list.
        setBackgroundColor(Ui.alpha(Skin.bg(), 1f));
        setClickable(true);

        frame = new ConsoleFrame(act);
        frame.sidebarW = Ui.dp(act, SIDEBAR_DP);
        frame.colorTop = pal.c(2);
        frame.colorBot = pal.c(1);
        frame.topBlocks = new int[]{pal.c(0)};
        frame.botBlocks = new int[]{pal.c(3), pal.c(4)};
        titleView = Ui.oneLine(Ui.bold(act, Skin.drawerTitle(), 30, pal.c(0)));
        titleView.setPadding(Ui.dp(act, Ui.XS), 0, Ui.dp(act, Ui.HAIR), 0);
        frame.setTitle(titleView);
        countView = Ui.text(act, "", 14, pal.c(1));
        countView.setPadding(Ui.dp(act, Ui.XS), 0, Ui.dp(act, Ui.HAIR), 0);
        frame.setFooter(countView);

        // category sidebar
        ScrollView catScroll = new ScrollView(act);
        catScroll.setVerticalScrollBarEnabled(false);
        cats = new LinearLayout(act);
        cats.setOrientation(LinearLayout.VERTICAL);
        catScroll.addView(cats);
        frame.setSidebar(catScroll);

        // content: search row + grid
        LinearLayout content = new LinearLayout(act);
        content.setOrientation(LinearLayout.VERTICAL);
        LinearLayout searchRow = new LinearLayout(act);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        search = new EditText(act);
        search.setSingleLine(true);
        search.setHint("SEARCH APPLICATIONS");
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setTextColor(pal.textHi);
        search.setHintTextColor(Ui.alpha(pal.textLo, 0.65f));
        search.setTypeface(Ui.font(act, false));
        Ui.setTextSp(search, 18);
        search.setBackground(Ui.outline(act, pal.panel, pal.c(2), 24, 2));
        search.setPadding(Ui.dp(act, Ui.L), 0, Ui.dp(act, Ui.M), 0);
        searchRow.addView(search, new LinearLayout.LayoutParams(0, Ui.dp(act, 50), 1));
        TextView close = Ui.button(act, "HOME", pal.c(3), Ui.RIGHT, new OnClickListener() {
            @Override public void onClick(View v) { close(true); }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(act, 76), Ui.dp(act, 50));
        clp.leftMargin = Ui.dp(act, Ui.GUTTER);
        searchRow.addView(close, clp);
        content.addView(searchRow, new LinearLayout.LayoutParams(-1, -2));

        FrameLayout gridBox = new FrameLayout(act);
        grid = new GridView(act);
        grid.setNumColumns(columnsFor(ScreenMetrics.of(act), prefs.drawerCols(), Ui.zoom(act)));
        grid.setVerticalSpacing(Ui.dp(act, Ui.M));
        grid.setHorizontalSpacing(Ui.dp(act, Ui.XS));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setFastScrollEnabled(false); // no fast-scroll strip: it stole taps from the right-hand column
        grid.setFastScrollAlwaysVisible(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setClipToPadding(false);
        grid.setPadding(0, Ui.dp(act, Ui.M), 0, Ui.dp(act, Ui.M));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View v, int pos, long id) { activate(shown.get(pos), v); }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> parent, View v, int pos, long id) {
                Ui.longPress(v);
                appMenu(shown.get(pos));
                return true;
            }
        });
        gridBox.addView(grid, new LayoutParams(-1, -1));
        empty = Ui.bold(act, "", 17, pal.c(0));
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Ui.dp(act, Ui.L), Ui.dp(act, Ui.L), Ui.dp(act, Ui.L), Ui.dp(act, Ui.L));
        empty.setVisibility(GONE);
        empty.setOnClickListener(Ui.feedback(new OnClickListener() {
            @Override public void onClick(View v) { webSearch(); }
        }));
        gridBox.addView(empty, new LayoutParams(-1, -2, Gravity.TOP));
        content.addView(gridBox, new LinearLayout.LayoutParams(-1, 0, 1));
        frame.setContent(content);
        addView(frame, new LayoutParams(-1, -1));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { refilter(false); }
            @Override public void afterTextChanged(Editable e) { }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
                if (!shown.isEmpty()) activate(shown.get(0), null, Sfx.RESULT);
                else if (search.length() > 0) webSearch();
                return true;
            }
        });
        buildCategories();
    }

    // ---- responsive grid ----------------------------------------------------------------------

    /**
     * Grid columns for a drawer filling a window described by {@code m}.
     *
     * <p>The minimum tile width is chosen so that a 360dp reference phone shows exactly the user's
     * column setting ({@code userCols}, clamped to 3..6); the tile then grows with {@code zoom} and
     * {@link ScreenMetrics#columns} decides how many fit, between {@link #MIN_COLS} and {@link #MAX_COLS}.
     * So a 412dp phone keeps the user's count, landscape phones and tablets gain columns, and narrow
     * split-screen windows lose some instead of crushing labels.
     */
    static int columnsFor(ScreenMetrics m, int userCols, float zoom) {
        return ScreenMetrics.columnsFor(m.widthDp, minTileDpFor(userCols) * zoom, MIN_COLS, MAX_COLS);
    }

    /** Minimum tile width (dp) at which the 360dp reference phone fits exactly {@code userCols} columns. */
    static float minTileDpFor(int userCols) {
        int cols = Math.max(MIN_USER_COLS, Math.min(MAX_USER_COLS, userCols));
        float ref = ScreenMetrics.REFERENCE_SHORT_SIDE_DP;
        float usable = ref - 2 * ScreenMetrics.marginFor(ref);
        float gutter = ScreenMetrics.gutterFor(ref);
        // half a dp of slack so float rounding never drops the reference phone to cols - 1
        return (usable + gutter) / cols - gutter - 0.5f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        ScreenMetrics m = ScreenMetrics.compute(w / density, h / density, density);
        int cols = columnsFor(m, prefs.drawerCols(), Ui.zoom(getContext()));
        if (cols != grid.getNumColumns()) grid.setNumColumns(cols);
    }

    /** Pads the drawer clear of the status bar, navigation bar and keyboard (portrait insets). */
    void setInsets(int top, int bottom, int ime) {
        setInsets(0, top, 0, bottom, ime);
    }

    /**
     * Pads the drawer clear of system bars, display cutouts and the keyboard on every side
     * (landscape navigation bars and cutouts sit on the left or right).
     */
    void setInsets(int left, int top, int right, int bottom, int ime) {
        int g = Ui.dp(getContext(), Ui.S);
        setPadding(g + left, top + Ui.dp(getContext(), Ui.XS), g + right,
                Math.max(bottom, ime + bottom) + Ui.dp(getContext(), Ui.GUTTER));
    }

    // ---- open / close -------------------------------------------------------------------------

    boolean isOpen() { return open; }

    /**
     * Shows the drawer, reset to ALL with an empty search.
     *
     * @param p           picker callback, or null to launch apps
     * @param focusSearch focus the search field and show the keyboard
     * @param animate     slide/fade in and reveal the frame
     */
    void open(Pick p, boolean focusSearch, boolean animate) {
        pick = p;
        titleView.setText(p != null ? p.title() : Skin.drawerTitle());
        titleView.setTextColor(p != null ? pal.c(4) : pal.c(0));
        search.setText("");
        cat = AppInfo.CAT_ALL;
        buildCategories();
        refilter(true);
        if (!open) {
            open = true;
            Sfx.play(getContext(), focusSearch ? Sfx.SEARCH : Sfx.OPEN);
            setVisibility(VISIBLE);
            // The backdrop is opaque from the first frame and only the content fades in: fading the
            // whole drawer let the home screen's labels show through the app list while it opened.
            setAlpha(1f);
            if (animate) {
                setTranslationY(getHeight() > 0 ? getHeight() * 0.06f : Ui.dp(getContext(), 40));
                animate().alpha(1f).translationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator(1.5f)).withEndAction(null).start();
                frame.setAlpha(0f);
                frame.animate().alpha(1f).setDuration(240).setInterpolator(new DecelerateInterpolator(1.5f)).start();
                frame.reveal();
            } else {
                frame.animate().cancel();
                frame.setAlpha(1f);
                setTranslationY(0);
            }
        }
        if (focusSearch) {
            search.requestFocus();
            postDelayed(new Runnable() {
                @Override public void run() {
                    InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 180);
        } else {
            grid.requestFocus();
        }
        grid.setSelection(0);
    }

    /** Hides the drawer (and the keyboard), ending picker mode. */
    void close(boolean animate) {
        if (!open) return;
        open = false;
        pick = null;
        hideIme();
        Sfx.play(getContext(), Sfx.CLOSE);
        if (animate && Ui.animate(getContext())) {
            animate().alpha(0f).translationY(getHeight() * 0.05f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).withEndAction(new Runnable() {
                        @Override public void run() { if (!open) setVisibility(GONE); }
                    }).start();
        } else {
            animate().cancel();
            setVisibility(GONE);
        }
    }

    /** Back: clears the search, then returns to ALL, then closes. */
    void back() {
        if (search.length() > 0) { search.setText(""); return; }
        if (cat != AppInfo.CAT_ALL) { setCategory(AppInfo.CAT_ALL); return; }
        close(true);
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
    }

    /** Apps were installed, removed, hidden or restyled: drop cached icons and refresh. */
    void onAppsChanged() {
        iconCache.clear();
        buildCategories();
        refilter(false);
    }

    // ---- categories and filtering ---------------------------------------------------------------

    /** Rebuilds the sidebar: ALL plus every category that has at least one visible app. */
    private void buildCategories() {
        cats.removeAllViews();
        int[] counts = new int[AppInfo.CAT_NAMES.length];
        Set<String> hidden = prefs.hiddenApps();
        for (AppInfo app : AppRepo.get(getContext()).apps()) {
            if (hidden.contains(app.key)) continue;
            counts[app.category]++;
            counts[AppInfo.CAT_ALL]++;
        }
        int shownCount = 0;
        for (int i = 0; i < AppInfo.CAT_NAMES.length; i++) {
            if (i != AppInfo.CAT_ALL && counts[i] == 0) continue;
            final int category = i;
            int color = pal.c(CATEGORY_COLORS[shownCount++ % CATEGORY_COLORS.length]);
            boolean sel = i == cat;
            int fill = sel ? color : Ui.darken(color, UNSELECTED_DARKEN);
            TextView b = Ui.button(getContext(), AppInfo.CAT_NAMES[i], fill, Ui.SQUARE, new OnClickListener() {
                @Override public void onClick(View v) { setCategory(category); }
            });
            b.setBackground(Ui.touch(Ui.shape(getContext(), fill, Ui.SQUARE, 0)));
            Ui.setTextSp(b, 15);
            b.setPadding(Ui.dp(getContext(), Ui.XS), 0, Ui.dp(getContext(), Ui.S), Ui.dp(getContext(), Ui.XS));
            if (!sel) b.setTextColor(Ui.ink());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(getContext(), 54));
            if (cats.getChildCount() > 0) lp.topMargin = Ui.dp(getContext(), Ui.GUTTER);
            cats.addView(b, lp);
        }
    }

    private void setCategory(int c) {
        if (c == cat) return;
        cat = c;
        Sfx.play(getContext(), Sfx.PAGE);
        buildCategories();
        refilter(true);
    }

    /** No match. */
    static final int NO_MATCH = -1;
    /** Label starts with the query: listed first. */
    static final int MATCH_PREFIX = 0;
    /** Query appears inside the label or package name: listed after prefix matches. */
    static final int MATCH_CONTAINS = 1;

    /**
     * How well an app matches a search query.
     *
     * @param lowerLabel the app label, lower-cased
     * @param pkg        the package name
     * @param query      trimmed, lower-cased query (non-empty)
     * @return {@link #MATCH_PREFIX}, {@link #MATCH_CONTAINS} or {@link #NO_MATCH}
     */
    static int matchRank(String lowerLabel, String pkg, String query) {
        if (lowerLabel.startsWith(query)) return MATCH_PREFIX;
        if (lowerLabel.contains(query) || pkg.contains(query)) return MATCH_CONTAINS;
        return NO_MATCH;
    }

    /** Recomputes the visible apps from the category and search, then updates count and empty state. */
    private void refilter(boolean animateGrid) {
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        Set<String> hidden = prefs.hiddenApps();
        List<AppInfo> starts = new ArrayList<>(), contains = new ArrayList<>();
        for (AppInfo app : AppRepo.get(getContext()).apps()) {
            if (hidden.contains(app.key)) continue;
            if (q.isEmpty()) {
                if (cat == AppInfo.CAT_ALL || app.category == cat) starts.add(app);
                continue;
            }
            int rank = matchRank(app.lower, app.pkg, q);
            if (rank == MATCH_PREFIX) starts.add(app);
            else if (rank == MATCH_CONTAINS) contains.add(app);
        }
        shown.clear();
        shown.addAll(starts);
        shown.addAll(contains);
        adapter.notifyDataSetChanged();
        int total = AppRepo.get(getContext()).apps().size() - hidden.size();
        countView.setText(String.format(Locale.US, "%d / %d ENTRIES", shown.size(), Math.max(shown.size(), total)));
        if (shown.isEmpty()) {
            empty.setVisibility(VISIBLE);
            empty.setText(q.isEmpty() ? "NO APPLICATIONS IN THIS CATEGORY"
                    : "NO MATCHING APPLICATIONS\n\nTAP TO SEARCH THE WEB FOR “" + search.getText().toString().trim().toUpperCase(Locale.getDefault()) + "”");
            empty.setBackground(q.isEmpty() ? null : Ui.outline(getContext(), pal.panel, pal.c(0), 20, 2));
        } else {
            empty.setVisibility(GONE);
        }
        if (animateGrid && Ui.animate(getContext())) {
            AnimationSet set = new AnimationSet(true);
            set.addAnimation(new AlphaAnimation(0f, 1f));
            set.addAnimation(new TranslateAnimation(0, 0, Ui.dp(getContext(), 14), 0));
            set.setDuration(200);
            grid.setLayoutAnimation(new GridLayoutAnimationController(set, 0.08f, 0.12f));
            grid.scheduleLayoutAnimation();
        }
    }

    /** Hands the query to the device's web search, falling back to a browser search URL. */
    private void webSearch() {
        String q = search.getText().toString().trim();
        if (q.isEmpty()) return;
        Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
        i.putExtra(SearchManager.QUERY, q);
        hideIme();
        if (!a.startSafe(i, null)) {
            a.startSafe(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))), null);
        }
    }

    private void activate(AppInfo app, View v) { activate(app, v, -1); }

    /** Picks the app (picker mode) or launches it with an optional sound override. */
    private void activate(AppInfo app, View v, int sound) {
        if (pick != null) {
            Pick p = pick;
            close(true);
            p.picked(app);
            return;
        }
        hideIme();
        a.launchApp(app, v, sound);
    }

    /** Long-press menu: up to four app shortcuts, add to home/dock, app info, hide, uninstall. */
    private void appMenu(final AppInfo app) {
        final List<String> items = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        final LauncherApps la = AppRepo.get(getContext()).launcherApps();
        try {
            if (la.hasShortcutHostPermission()) {
                LauncherApps.ShortcutQuery q = new LauncherApps.ShortcutQuery();
                q.setPackage(app.pkg);
                q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC | LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST);
                List<ShortcutInfo> list = la.getShortcuts(q, app.user);
                if (list != null) {
                    int n = 0;
                    for (final ShortcutInfo si : list) {
                        if (n++ >= MAX_SHORTCUTS) break;
                        CharSequence l = si.getShortLabel();
                        if (l == null) continue;
                        items.add("▸ " + l.toString().toUpperCase(Locale.getDefault()));
                        acts.add(new Runnable() {
                            @Override public void run() {
                                try { la.startShortcut(si, null, null); Sfx.play(getContext(), Sfx.LAUNCH); }
                                catch (Exception e) { Sfx.play(getContext(), Sfx.ERROR); }
                            }
                        });
                    }
                }
            }
        } catch (Exception ignored) {
            // shortcut access requires being the default launcher
        }
        items.add("ADD TO HOME TILES");
        acts.add(new Runnable() { @Override public void run() { close(true); a.addToHome(app, false); } });
        if (prefs.dockCount() > 0) {
            items.add("ADD TO DOCK");
            acts.add(new Runnable() { @Override public void run() { close(true); a.addToHome(app, true); } });
        }
        items.add(getContext().getString(R.string.group_add_to_group)); // LCA-T20
        acts.add(new Runnable() { @Override public void run() { GroupEditor.addAppToGroup(a, app); } });
        items.add(getContext().getString(R.string.icon_change)); // LCA-T22
        acts.add(new Runnable() { @Override public void run() { IconPicker.show(a, app); } });
        items.add("APP INFO");
        acts.add(new Runnable() {
            @Override public void run() {
                try { la.startAppDetailsActivity(app.cn, app.user, null, null); }
                catch (Exception e) { Sfx.play(getContext(), Sfx.ERROR); }
            }
        });
        items.add("HIDE FROM DRAWER");
        acts.add(new Runnable() {
            @Override public void run() {
                prefs.setAppHidden(app.key, true);
                Sfx.play(getContext(), Sfx.CONFIRM);
                a.toast("HIDDEN • RESTORE IN CONFIG › HIDDEN APPS");
                onAppsChanged();
            }
        });
        if (app.mainProfile) {
            items.add("UNINSTALL");
            acts.add(new Runnable() {
                @Override public void run() {
                    a.startSafe(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)), null);
                }
            });
        }
        a.showing(ConsoleDialog.options(getContext(), app.label.toUpperCase(Locale.getDefault()), items.toArray(new String[0]),
                new ConsoleDialog.OnPick() {
                    @Override public void pick(int w) { acts.get(w).run(); }
                }));
    }

    /** Grid cells: icon (styled and cached by {@link IconFactory#style}) over a two-line label. */
    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Holder h;
            if (convert == null) {
                Context c = parent.getContext();
                LinearLayout cell = new LinearLayout(c);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(0, Ui.dp(c, Ui.XS), 0, Ui.dp(c, Ui.HAIR));
                cell.setBackground(Ui.touchArea(c, 14));
                h = new Holder();
                h.icon = new ImageView(c);
                int s = Ui.dp(c, 54);
                cell.addView(h.icon, new LinearLayout.LayoutParams(s, s));
                h.label = Ui.text(c, "", 13, pal.textHi);
                h.label.setGravity(Gravity.CENTER);
                h.label.setMaxLines(2);
                h.label.setEllipsize(TextUtils.TruncateAt.END);
                h.label.setPadding(Ui.dp(c, Ui.HAIR), Ui.dp(c, Ui.GUTTER), Ui.dp(c, Ui.HAIR), 0);
                cell.addView(h.label, new LinearLayout.LayoutParams(-1, -2));
                cell.setTag(h);
                convert = cell;
            } else {
                h = (Holder) convert.getTag();
            }
            AppInfo app = shown.get(pos);
            Drawable d = iconCache.get(app.key);
            if (d == null) {
                d = IconFactory.style(getContext(), app, pal, prefs.iconStyle(), prefs.iconTone());
                if (d != null) iconCache.put(app.key, d);
            }
            h.icon.setImageDrawable(d);
            h.label.setText(Ui.up(getContext(), app.label));
            h.label.setTextColor(app.mainProfile ? pal.textHi : pal.c(4));
            convert.setContentDescription(app.label);
            return convert;
        }
    }

    private static final class Holder {
        ImageView icon;
        TextView label;
    }
}
