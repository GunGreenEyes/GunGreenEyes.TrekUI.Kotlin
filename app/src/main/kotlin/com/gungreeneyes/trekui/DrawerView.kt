package com.gungreeneyes.trekui

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.AnimationSet
import android.view.animation.DecelerateInterpolator
import android.view.animation.GridLayoutAnimationController
import android.view.animation.TranslateAnimation
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen app drawer: a [ConsoleFrame] with a category sidebar, a search row and the icon
 * grid. Also used as an app picker ([Pick]) when assigning tiles, dock keys and slots.
 *
 * Responsive: the grid's column count follows the drawer's own size (so rotation, split-screen and
 * folding are handled without rebuilding) via [columnsFor]: the user's CONFIG column count is
 * what a 360dp reference phone shows, and wider windows add columns as [ScreenMetrics.columnsFor]
 * allows. Search matching is the pure [matchRank].
 */
internal class DrawerView(act: MainActivity) : FrameLayout(act) {
    /** Receives the app chosen while the drawer is in picker mode. */
    interface Pick {
        fun title(): String?

        fun picked(app: AppInfo)
    }

    private val a: MainActivity = act
    private val prefs: Prefs = Prefs.get(act)
    private val pal: Palette = prefs.palette()
    private val frame: ConsoleFrame
    private val search: EditText
    private val grid: GridView
    private val titleView: TextView
    private val countView: TextView
    private val empty: TextView
    private val cats: LinearLayout
    private val adapter = Adapter()
    private val shown = ArrayList<AppInfo>()
    private val iconCache = HashMap<String, Drawable>()
    private var cat = AppInfo.CAT_ALL
    private var pick: Pick? = null
    private var open = false

    init {
        // Opaque: at 96% the bright home labels (Imperial) ghosted through the app list.
        setBackgroundColor(Ui.alpha(Skin.bg(), 1f))
        isClickable = true

        frame = ConsoleFrame(act)
        frame.sidebarW = Ui.dp(act, SIDEBAR_DP)
        frame.colorTop = pal.c(2)
        frame.colorBot = pal.c(1)
        frame.topBlocks = intArrayOf(pal.c(0))
        frame.botBlocks = intArrayOf(pal.c(3), pal.c(4))
        titleView = Ui.oneLine(Ui.bold(act, Skin.drawerTitle(), 30f, pal.c(0)))
        titleView.setPadding(Ui.dp(act, Ui.XS.toFloat()), 0, Ui.dp(act, Ui.HAIR.toFloat()), 0)
        frame.setTitle(titleView)
        countView = Ui.text(act, "", 14f, pal.c(1))
        countView.setPadding(Ui.dp(act, Ui.XS.toFloat()), 0, Ui.dp(act, Ui.HAIR.toFloat()), 0)
        frame.setFooter(countView)

        // category sidebar
        val catScroll = ScrollView(act)
        catScroll.isVerticalScrollBarEnabled = false
        cats = LinearLayout(act)
        cats.orientation = LinearLayout.VERTICAL
        catScroll.addView(cats)
        frame.setSidebar(catScroll)

        // content: search row + grid
        val content = LinearLayout(act)
        content.orientation = LinearLayout.VERTICAL
        val searchRow = LinearLayout(act)
        searchRow.gravity = Gravity.CENTER_VERTICAL
        search = EditText(act)
        search.setSingleLine(true)
        search.hint = "SEARCH APPLICATIONS"
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.imeOptions = EditorInfo.IME_ACTION_GO
        search.setTextColor(pal.textHi)
        search.setHintTextColor(Ui.alpha(pal.textLo, 0.65f))
        search.typeface = Ui.font(act, false)
        Ui.setTextSp(search, 18f)
        search.background = Ui.outline(act, pal.panel, pal.c(2), 24f, 2f)
        search.setPadding(Ui.dp(act, Ui.L.toFloat()), 0, Ui.dp(act, Ui.M.toFloat()), 0)
        searchRow.addView(search, LinearLayout.LayoutParams(0, Ui.dp(act, 50f), 1f))
        val home = Ui.button(act, "HOME", pal.c(3), Ui.RIGHT) { close(true) }
        val clp = LinearLayout.LayoutParams(Ui.dp(act, 76f), Ui.dp(act, 50f))
        clp.leftMargin = Ui.dp(act, Ui.GUTTER.toFloat())
        searchRow.addView(home, clp)
        content.addView(searchRow, LinearLayout.LayoutParams(-1, -2))

        val gridBox = FrameLayout(act)
        grid = GridView(act)
        grid.numColumns = columnsFor(ScreenMetrics.of(act), prefs.drawerCols(), Ui.zoom(act))
        grid.verticalSpacing = Ui.dp(act, Ui.M.toFloat())
        grid.horizontalSpacing = Ui.dp(act, Ui.XS.toFloat())
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.selector = ColorDrawable(Color.TRANSPARENT)
        grid.isFastScrollEnabled = false // no fast-scroll strip: it stole taps from the right-hand column
        grid.isFastScrollAlwaysVisible = false
        grid.isVerticalScrollBarEnabled = false
        grid.clipToPadding = false
        grid.setPadding(0, Ui.dp(act, Ui.M.toFloat()), 0, Ui.dp(act, Ui.M.toFloat()))
        grid.adapter = adapter
        grid.setOnItemClickListener { _, v, pos, _ -> activate(shown[pos], v) }
        grid.setOnItemLongClickListener { _, v, pos, _ ->
            Ui.longPress(v)
            appMenu(shown[pos])
            true
        }
        gridBox.addView(grid, LayoutParams(-1, -1))
        empty = Ui.bold(act, "", 17f, pal.c(0))
        empty.gravity = Gravity.CENTER
        val l = Ui.dp(act, Ui.L.toFloat())
        empty.setPadding(l, l, l, l)
        empty.visibility = GONE
        empty.setOnClickListener(Ui.feedback { webSearch() })
        gridBox.addView(empty, LayoutParams(-1, -2, Gravity.TOP))
        content.addView(gridBox, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.setContent(content)
        addView(frame, LayoutParams(-1, -1))

        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) {}

                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                    refilter(false)
                }

                override fun afterTextChanged(e: Editable?) {}
            },
        )
        search.setOnEditorActionListener { _, _, _ ->
            if (shown.isNotEmpty()) {
                activate(shown[0], null, Sfx.RESULT)
            } else if (search.length() > 0) {
                webSearch()
            }
            true
        }
        buildCategories()
    }

    // ---- responsive grid ----------------------------------------------------------------------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val m = ScreenMetrics.compute(w / density, h / density, density)
        val cols = columnsFor(m, prefs.drawerCols(), Ui.zoom(context))
        if (cols != grid.numColumns) grid.numColumns = cols
    }

    /** Pads the drawer clear of the status bar, navigation bar and keyboard (portrait insets). */
    fun setInsets(top: Int, bottom: Int, ime: Int) {
        setInsets(0, top, 0, bottom, ime)
    }

    /**
     * Pads the drawer clear of system bars, display cutouts and the keyboard on every side
     * (landscape navigation bars and cutouts sit on the left or right).
     */
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int, ime: Int) {
        val g = Ui.dp(context, Ui.S.toFloat())
        setPadding(
            g + left,
            top + Ui.dp(context, Ui.XS.toFloat()),
            g + right,
            max(bottom, ime + bottom) + Ui.dp(context, Ui.GUTTER.toFloat()),
        )
    }

    // ---- open / close -------------------------------------------------------------------------

    fun isOpen(): Boolean = open

    /**
     * Shows the drawer, reset to ALL with an empty search.
     *
     * @param p           picker callback, or null to launch apps
     * @param focusSearch focus the search field and show the keyboard
     * @param animate     slide/fade in and reveal the frame
     */
    fun open(p: Pick?, focusSearch: Boolean, animate: Boolean) {
        pick = p
        titleView.text = if (p != null) p.title() else Skin.drawerTitle()
        titleView.setTextColor(if (p != null) pal.c(4) else pal.c(0))
        search.setText("")
        cat = AppInfo.CAT_ALL
        buildCategories()
        refilter(true)
        if (!open) {
            open = true
            Sfx.play(context, if (focusSearch) Sfx.SEARCH else Sfx.OPEN)
            visibility = VISIBLE
            // The backdrop is opaque from the first frame and only the content fades in: fading the
            // whole drawer let the home screen's labels show through the app list while it opened.
            alpha = 1f
            if (animate) {
                translationY = if (height > 0) height * 0.06f else Ui.dp(context, 40f).toFloat()
                animate().alpha(1f).translationY(0f).setDuration(240)
                    .setInterpolator(DecelerateInterpolator(1.5f)).withEndAction(null).start()
                frame.alpha = 0f
                frame.animate().alpha(1f).setDuration(240).setInterpolator(DecelerateInterpolator(1.5f)).start()
                frame.reveal()
            } else {
                frame.animate().cancel()
                frame.alpha = 1f
                translationY = 0f
            }
        }
        if (focusSearch) {
            search.requestFocus()
            postDelayed({
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
                imm?.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT)
            }, 180)
        } else {
            grid.requestFocus()
        }
        grid.setSelection(0)
    }

    /** Hides the drawer (and the keyboard), ending picker mode. */
    fun close(animate: Boolean) {
        if (!open) return
        open = false
        pick = null
        hideIme()
        Sfx.play(context, Sfx.CLOSE)
        if (animate && Ui.animate(context)) {
            animate().alpha(0f).translationY(height * 0.05f).setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { if (!open) visibility = GONE }
                .start()
        } else {
            animate().cancel()
            visibility = GONE
        }
    }

    /** Back: clears the search, then returns to ALL, then closes. */
    fun back() {
        if (search.length() > 0) {
            search.setText("")
            return
        }
        if (cat != AppInfo.CAT_ALL) {
            setCategory(AppInfo.CAT_ALL)
            return
        }
        close(true)
    }

    private fun hideIme() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        imm?.hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    /** Apps were installed, removed, hidden or restyled: drop cached icons and refresh. */
    fun onAppsChanged() {
        iconCache.clear()
        buildCategories()
        refilter(false)
    }

    // ---- categories and filtering ---------------------------------------------------------------

    /** Rebuilds the sidebar: ALL plus every category that has at least one visible app. */
    private fun buildCategories() {
        cats.removeAllViews()
        val counts = IntArray(AppInfo.CAT_NAMES.size)
        val hidden = prefs.hiddenApps()
        for (app in AppRepo.get(context).apps()) {
            if (hidden.contains(app.key)) continue
            counts[app.category]++
            counts[AppInfo.CAT_ALL]++
        }
        var shownCount = 0
        val c = context
        for (i in AppInfo.CAT_NAMES.indices) {
            if (i != AppInfo.CAT_ALL && counts[i] == 0) continue
            val color = pal.c(CATEGORY_COLORS[shownCount++ % CATEGORY_COLORS.size])
            val sel = i == cat
            val fill = if (sel) color else Ui.darken(color, UNSELECTED_DARKEN)
            val b = Ui.button(c, AppInfo.CAT_NAMES[i], fill, Ui.SQUARE) { setCategory(i) }
            b.background = Ui.touch(Ui.shape(c, fill, Ui.SQUARE, 0f))
            Ui.setTextSp(b, 15f)
            b.setPadding(Ui.dp(c, Ui.XS.toFloat()), 0, Ui.dp(c, Ui.S.toFloat()), Ui.dp(c, Ui.XS.toFloat()))
            if (!sel) b.setTextColor(Ui.ink())
            val lp = LinearLayout.LayoutParams(-1, Ui.dp(c, 54f))
            if (cats.childCount > 0) lp.topMargin = Ui.dp(c, Ui.GUTTER.toFloat())
            cats.addView(b, lp)
        }
    }

    private fun setCategory(c: Int) {
        if (c == cat) return
        cat = c
        Sfx.play(context, Sfx.PAGE)
        buildCategories()
        refilter(true)
    }

    /** Recomputes the visible apps from the category and search, then updates count and empty state. */
    private fun refilter(animateGrid: Boolean) {
        val q = search.text.toString().trim().lowercase(Locale.getDefault())
        val hidden = prefs.hiddenApps()
        val starts = ArrayList<AppInfo>()
        val contains = ArrayList<AppInfo>()
        for (app in AppRepo.get(context).apps()) {
            if (hidden.contains(app.key)) continue
            if (q.isEmpty()) {
                if (cat == AppInfo.CAT_ALL || app.category == cat) starts.add(app)
                continue
            }
            when (matchRank(app.lower, app.pkg, q)) {
                MATCH_PREFIX -> starts.add(app)
                MATCH_CONTAINS -> contains.add(app)
            }
        }
        shown.clear()
        shown.addAll(starts)
        shown.addAll(contains)
        adapter.notifyDataSetChanged()
        val total = AppRepo.get(context).apps().size - hidden.size
        countView.text = String.format(Locale.US, "%d / %d ENTRIES", shown.size, max(shown.size, total))
        if (shown.isEmpty()) {
            empty.visibility = VISIBLE
            empty.text =
                if (q.isEmpty()) {
                    "NO APPLICATIONS IN THIS CATEGORY"
                } else {
                    "NO MATCHING APPLICATIONS\n\nTAP TO SEARCH THE WEB FOR “" +
                        search.text.toString().trim().uppercase(Locale.getDefault()) + "”"
                }
            empty.background = if (q.isEmpty()) null else Ui.outline(context, pal.panel, pal.c(0), 20f, 2f)
        } else {
            empty.visibility = GONE
        }
        if (animateGrid && Ui.animate(context)) {
            val set = AnimationSet(true)
            set.addAnimation(AlphaAnimation(0f, 1f))
            set.addAnimation(TranslateAnimation(0f, 0f, Ui.dp(context, 14f).toFloat(), 0f))
            set.duration = 200
            grid.layoutAnimation = GridLayoutAnimationController(set, 0.08f, 0.12f)
            grid.scheduleLayoutAnimation()
        }
    }

    /** Hands the query to the device's web search, falling back to a browser search URL. */
    private fun webSearch() {
        val q = search.text.toString().trim()
        if (q.isEmpty()) return
        val i = Intent(Intent.ACTION_WEB_SEARCH)
        i.putExtra(SearchManager.QUERY, q)
        hideIme()
        if (!a.startSafe(i, null)) {
            a.startSafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))), null)
        }
    }

    /** Picks the app (picker mode) or launches it with an optional sound override. */
    private fun activate(app: AppInfo, v: View?, sound: Int = -1) {
        val p = pick
        if (p != null) {
            close(true)
            p.picked(app)
            return
        }
        hideIme()
        a.launchApp(app, v, sound)
    }

    /** Long-press menu: up to four app shortcuts, add to home/dock, app info, hide, uninstall. */
    private fun appMenu(app: AppInfo) {
        val items = ArrayList<String>()
        val acts = ArrayList<Runnable>()
        val la: LauncherApps = AppRepo.get(context).launcherApps()
        try {
            // App shortcuts exist from Android 7.1 (API 25); below it the menu starts with ADD TO HOME TILES.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1 && la.hasShortcutHostPermission()) {
                val q = LauncherApps.ShortcutQuery()
                q.setPackage(app.pkg)
                q.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
                val list = la.getShortcuts(q, app.user!!)
                if (list != null) {
                    var n = 0
                    for (si in list) {
                        if (n++ >= MAX_SHORTCUTS) break
                        val l = si.shortLabel ?: continue
                        items.add("▸ " + l.toString().uppercase(Locale.getDefault()))
                        acts.add(
                            Runnable {
                                try {
                                    la.startShortcut(si, null, null)
                                    Sfx.play(context, Sfx.LAUNCH)
                                } catch (e: Exception) {
                                    Sfx.play(context, Sfx.ERROR)
                                }
                            },
                        )
                    }
                }
            }
        } catch (ignored: Exception) {
            // shortcut access requires being the default launcher
        }
        items.add("ADD TO HOME TILES")
        acts.add(
            Runnable {
                close(true)
                a.addToHome(app, false)
            },
        )
        if (prefs.dockCount() > 0) {
            items.add("ADD TO DOCK")
            acts.add(
                Runnable {
                    close(true)
                    a.addToHome(app, true)
                },
            )
        }
        items.add(context.getString(R.string.group_add_to_group)) // LCA-T20
        acts.add(Runnable { GroupEditor.addAppToGroup(a, app) })
        items.add(context.getString(R.string.icon_change)) // LCA-T22
        acts.add(Runnable { IconPicker.show(a, app) })
        items.add("APP INFO")
        acts.add(
            Runnable {
                try {
                    la.startAppDetailsActivity(app.cn, app.user!!, null, null)
                } catch (e: Exception) {
                    Sfx.play(context, Sfx.ERROR)
                }
            },
        )
        items.add("HIDE FROM DRAWER")
        acts.add(
            Runnable {
                prefs.setAppHidden(app.key, true)
                Sfx.play(context, Sfx.CONFIRM)
                a.toast("HIDDEN • RESTORE IN CONFIG › HIDDEN APPS")
                onAppsChanged()
            },
        )
        if (app.mainProfile) {
            items.add("UNINSTALL")
            acts.add(Runnable { a.startSafe(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)), null) })
        }
        a.showing(
            ConsoleDialog.options(context, app.label.uppercase(Locale.getDefault()), items.toTypedArray()) { w ->
                acts[w].run()
            },
        )
    }

    /** Grid cells: icon (styled and cached by [IconFactory.style]) over a two-line label. */
    private inner class Adapter : BaseAdapter() {
        override fun getCount(): Int = shown.size

        override fun getItem(i: Int): Any = shown[i]

        override fun getItemId(i: Int): Long = i.toLong()

        override fun getView(pos: Int, convert: View?, parent: ViewGroup): View {
            val h: Holder
            val cell: View
            if (convert == null) {
                val c = parent.context
                val row = LinearLayout(c)
                row.orientation = LinearLayout.VERTICAL
                row.gravity = Gravity.CENTER_HORIZONTAL
                row.setPadding(0, Ui.dp(c, Ui.XS.toFloat()), 0, Ui.dp(c, Ui.HAIR.toFloat()))
                row.background = Ui.touchArea(c, 14f)
                val icon = ImageView(c)
                val s = Ui.dp(c, 54f)
                row.addView(icon, LinearLayout.LayoutParams(s, s))
                val label = Ui.text(c, "", 13f, pal.textHi)
                label.gravity = Gravity.CENTER
                label.maxLines = 2
                label.ellipsize = TextUtils.TruncateAt.END
                label.setPadding(Ui.dp(c, Ui.HAIR.toFloat()), Ui.dp(c, Ui.GUTTER.toFloat()), Ui.dp(c, Ui.HAIR.toFloat()), 0)
                row.addView(label, LinearLayout.LayoutParams(-1, -2))
                h = Holder(icon, label)
                row.tag = h
                cell = row
            } else {
                h = convert.tag as Holder
                cell = convert
            }
            val app = shown[pos]
            var d = iconCache[app.key]
            if (d == null) {
                d = IconFactory.style(context, app, pal, prefs.iconStyle(), prefs.iconTone())
                if (d != null) iconCache[app.key] = d
            }
            h.icon.setImageDrawable(d)
            h.label.text = Ui.up(context, app.label)
            h.label.setTextColor(if (app.mainProfile) pal.textHi else pal.c(4))
            cell.contentDescription = app.label
            return cell
        }
    }

    private class Holder(val icon: ImageView, val label: TextView)

    companion object {
        /** Range of the user's column setting. */
        const val MIN_USER_COLS = 3
        const val MAX_USER_COLS = 6

        /** Fewest and most columns the grid ever shows. */
        const val MIN_COLS = 3
        const val MAX_COLS = 12

        /** Width of the category sidebar, design dp. */
        const val SIDEBAR_DP = 76f

        /** Most app shortcuts listed in the long-press menu. */
        const val MAX_SHORTCUTS = 4

        /** Sidebar colour rotation (palette slots). */
        private val CATEGORY_COLORS = intArrayOf(0, 1, 3, 2, 4, 5)

        /** Brightness of unselected category buttons. */
        private const val UNSELECTED_DARKEN = 0.45f

        /** No match. */
        const val NO_MATCH = -1

        /** Label starts with the query: listed first. */
        const val MATCH_PREFIX = 0

        /** Query appears inside the label or package name: listed after prefix matches. */
        const val MATCH_CONTAINS = 1

        /**
         * Grid columns for a drawer filling a window described by `m`.
         *
         * The minimum tile width is chosen so that a 360dp reference phone shows exactly the user's
         * column setting (`userCols`, clamped to 3..6); the tile then grows with `zoom` and
         * [ScreenMetrics.columnsFor] decides how many fit, between [MIN_COLS] and [MAX_COLS].
         * So a 412dp phone keeps the user's count, landscape phones and tablets gain columns, and narrow
         * split-screen windows lose some instead of crushing labels.
         */
        @JvmStatic
        fun columnsFor(m: ScreenMetrics, userCols: Int, zoom: Float): Int =
            ScreenMetrics.columnsFor(m.widthDp, minTileDpFor(userCols) * zoom, MIN_COLS, MAX_COLS)

        /** Minimum tile width (dp) at which the 360dp reference phone fits exactly `userCols` columns. */
        @JvmStatic
        fun minTileDpFor(userCols: Int): Float {
            val cols = max(MIN_USER_COLS, min(MAX_USER_COLS, userCols))
            val ref = ScreenMetrics.REFERENCE_SHORT_SIDE_DP
            val usable = ref - 2 * ScreenMetrics.marginFor(ref)
            val gutter = ScreenMetrics.gutterFor(ref)
            // half a dp of slack so float rounding never drops the reference phone to cols - 1
            return (usable + gutter) / cols - gutter - 0.5f
        }

        /**
         * How well an app matches a search query.
         *
         * @param lowerLabel the app label, lower-cased
         * @param pkg        the package name
         * @param query      trimmed, lower-cased query (non-empty)
         * @return [MATCH_PREFIX], [MATCH_CONTAINS] or [NO_MATCH]
         */
        @JvmStatic
        fun matchRank(lowerLabel: String, pkg: String, query: String): Int {
            if (lowerLabel.startsWith(query)) return MATCH_PREFIX
            if (lowerLabel.contains(query) || pkg.contains(query)) return MATCH_CONTAINS
            return NO_MATCH
        }
    }
}
