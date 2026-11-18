package com.gungreeneyes.trekui

import android.app.Dialog
import android.content.Context
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import java.util.Locale

/**
 * CHANGE ICON (LCA-T22): the per-app icon picker opened from the drawer's long-press menu.
 *
 * Three ways to restyle one app: a grid of every pictogram in the current interface's style
 * ([IconFactory.glyph]), any drawable from an installed icon
 * pack (searchable, loaded off the main thread in pages of [PACK_PAGE]), the app's own icon,
 * or AUTOMATIC to forget the choice. Choices are stored as `appIcon.<key>` preferences (see
 * [IconPacks]) and applied by reloading [AppRepo], so the drawer, dock and tiles all
 * pick them up together.
 */
internal object IconPicker {
    /** Largest number of pack drawables shown at once (packs can hold several thousand). */
    const val PACK_PAGE = 60

    /** Side of one grid cell, dp. */
    const val CELL_DP = 60f

    private val WHITESPACE = Regex("\\s+")

    /** Pure: grid columns for a dialog `widthDp` wide with `cellDp` cells, at least 3. */
    @JvmStatic
    fun columns(widthDp: Float, cellDp: Float): Int = Math.max(3, (widthDp / cellDp).toInt())

    /**
     * Pure: the pack drawables matching a search (every word must appear in the name, ignoring
     * case and "_"), at most `limit`; the app's own pack icon, when known, comes first.
     */
    @JvmStatic
    fun filter(drawables: List<String>, query: String?, suggested: String?, limit: Int): List<String> {
        val out = ArrayList<String>()
        if (suggested != null && drawables.contains(suggested)) out.add(suggested)
        val words = query?.javaTrim()?.lowercase(Locale.US)?.split(WHITESPACE) ?: emptyList()
        for (d in drawables) {
            if (out.size >= limit) break
            if (d == suggested) continue
            val n = d.lowercase(Locale.US).replace('_', ' ')
            if (words.all { it.isEmpty() || n.contains(it) }) out.add(d)
        }
        return out
    }

    /** Opens the main CHANGE ICON menu for `app`. */
    @JvmStatic
    fun show(a: MainActivity, app: AppInfo) {
        val items = arrayOf(
            a.getString(R.string.icon_pick_pictogram),
            a.getString(R.string.icon_pick_pack),
            a.getString(R.string.icon_pick_own),
            a.getString(R.string.icon_pick_auto),
        )
        val title = a.getString(R.string.icon_pick_title, app.label.uppercase(Locale.getDefault()))
        a.showing(
            ConsoleDialog.options(a, title, items) { which ->
                when (which) {
                    0 -> pictograms(a, app)
                    1 -> packs(a, app)
                    2 -> store(a, app, IconPacks.CHOICE_APP)
                    else -> store(a, app, null)
                }
            },
        )
    }

    /** Saves (or with null, clears) an app's icon choice and reloads the catalogue so every view updates. */
    @JvmStatic
    fun store(a: MainActivity, app: AppInfo, choice: String?) {
        val prefs = Prefs.get(a)
        val key = IconPacks.prefKey(app.key)
        if (choice == null) prefs.sp.edit().remove(key).apply() else prefs.put(key, choice)
        Sfx.play(a, Sfx.CONFIRM)
        AppRepo.get(a).load()
    }

    /** Dialog width in dp, as [ConsoleDialog] sizes it. */
    private fun dialogWidthDp(c: Context): Float {
        val m = ScreenMetrics.of(c)
        return ConsoleDialog.widthDpFor(m.widthDp, Ui.zoom(c)) - 2 * 14f // panel padding
    }

    /** A grid of every pictogram in the current style. */
    private fun pictograms(a: MainActivity, app: AppInfo) {
        val p = Prefs.get(a).palette()
        val b = ConsoleDialog.base(a, a.getString(R.string.icon_pick_pictogram))
        val cols = columns(dialogWidthDp(a), CELL_DP)
        var row: LinearLayout? = null
        for (i in Pictograms.ALL.indices) {
            val name = Pictograms.ALL[i]
            val r = if (i % cols == 0) newRow(b).also { row = it } else row!!
            val cell = cell(a, r)
            val g = IconFactory.glyph(a, name)
            if (g != null) cell.setImageBitmap(g)
            cell.setColorFilter(if (name == app.glyph) p.c(0) else p.c(1 + i % 3), PorterDuff.Mode.SRC_IN)
            cell.contentDescription = name
            cell.tag = "glyph_$name"
            cell.setOnClickListener {
                b.dialog.dismiss()
                store(a, app, IconPacks.CHOICE_GLYPH + name)
            }
        }
        padRow(row, Pictograms.ALL.size, cols)
        b.dialog.show()
        a.showing(b.dialog)
    }

    /** Lists installed packs (the selected one first) and opens the chosen pack's grid. */
    private fun packs(a: MainActivity, app: AppInfo) {
        val c = a.applicationContext
        IconPacks.async<List<IconPacks.Pack>>({ IconPacks.installed(c) }) { list ->
            if (a.isFinishing || a.isDestroyed) return@async
            if (list.isNullOrEmpty()) {
                a.toast(a.getString(R.string.icon_pack_none_installed))
                return@async
            }
            if (list.size == 1) {
                packGrid(a, app, list[0])
                return@async
            }
            val names = Array(list.size) { i -> list[i].label.uppercase(Locale.getDefault()) }
            a.showing(ConsoleDialog.options(a, a.getString(R.string.icon_pick_pack), names) { which -> packGrid(a, app, list[which]) })
        }
    }

    /** A searchable grid of one pack's drawables; loads the pack and each page off the main thread. */
    private fun packGrid(a: MainActivity, app: AppInfo, pack: IconPacks.Pack) {
        val appCtx = a.applicationContext
        val p = Prefs.get(a).palette()
        val b = ConsoleDialog.base(a, pack.label.uppercase(Locale.getDefault()))
        val search = EditText(a)
        search.setSingleLine(true)
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.hint = a.getString(R.string.icon_pack_search_hint)
        search.setTextColor(p.textHi)
        search.setHintTextColor(Ui.alpha(p.textLo, 0.7f))
        search.tag = "icon_pack_search"
        b.body.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val grid = LinearLayout(a)
        grid.orientation = LinearLayout.VERTICAL
        b.body.addView(grid)
        val status = Ui.text(a, a.getString(R.string.icon_pack_loading), 14f, p.textLo)
        grid.addView(status)
        b.dialog.show()
        a.showing(b.dialog)

        IconPacks.async<IconPackParser.Result>({ IconPacks.load(appCtx, pack.pkg) }) { r ->
            if (!b.dialog.isShowing) return@async
            if (r == null || r.drawables.isEmpty()) {
                status.text = a.getString(R.string.icon_pack_empty)
                return@async
            }
            val all = r.drawableList()
            val suggested = r.drawableFor(app.pkg, app.cn.className)
            val refresh = Runnable {
                fillPackGrid(a, app, pack, b, grid, filter(all, search.text.toString(), suggested, PACK_PAGE))
            }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

                override fun afterTextChanged(e: Editable?) {
                    grid.removeCallbacks(refresh)
                    grid.postDelayed(refresh, 250)
                }
            })
            refresh.run()
        }
    }

    /** Rebuilds the pack grid with `names`; the drawables arrive from the worker. */
    private fun fillPackGrid(
        a: MainActivity, app: AppInfo, pack: IconPacks.Pack,
        b: ConsoleDialog.Built, grid: LinearLayout, names: List<String>,
    ) {
        grid.removeAllViews()
        val appCtx = a.applicationContext
        val cols = columns(dialogWidthDp(a), CELL_DP)
        val cells = ArrayList<ImageView>()
        var row: LinearLayout? = null
        for (i in names.indices) {
            val name = names[i]
            val r = if (i % cols == 0) {
                LinearLayout(a).also {
                    grid.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(a, CELL_DP)))
                    row = it
                }
            } else {
                row!!
            }
            val cell = cell(a, r)
            cell.contentDescription = name.replace('_', ' ')
            cell.tag = "pack_$name"
            cell.setOnClickListener {
                b.dialog.dismiss()
                store(a, app, IconPacks.packChoice(pack.pkg, name))
            }
            cells.add(cell)
        }
        padRow(row, names.size, cols)
        IconPacks.async<List<Drawable?>>({ names.map { IconPacks.drawable(appCtx, pack.pkg, it) } }) { ds ->
            if (ds == null || !b.dialog.isShowing) return@async
            var i = 0
            while (i < ds.size && i < cells.size) {
                val d = ds[i]
                if (d != null) cells[i].setImageDrawable(d)
                i++
            }
        }
    }

    private fun newRow(b: ConsoleDialog.Built): LinearLayout {
        val c = b.body.context
        val row = LinearLayout(c)
        b.body.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, CELL_DP)))
        return row
    }

    private fun cell(c: Context, row: LinearLayout): ImageView {
        val v = ImageView(c)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = Ui.dp(c, 8f)
        v.setPadding(pad, pad, pad, pad)
        v.background = Ui.touchArea(c, 12f)
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        return v
    }

    /** Fills a partial last row with spacers so its cells keep the full-row width. */
    private fun padRow(row: LinearLayout?, count: Int, cols: Int) {
        if (row == null) return
        val rem = count % cols
        if (rem == 0) return
        for (i in rem until cols) {
            row.addView(View(row.context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
    }

    /** Dismisses `d` when it is still showing. */
    @JvmStatic
    fun dismiss(d: Dialog?) {
        if (d != null && d.isShowing) d.dismiss()
    }
}
