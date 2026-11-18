package com.gungreeneyes.trekui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * The OPEN GROUP overlay (gap A6): an app group unfolding over the home screen in the style of the
 * active interface.
 *
 * - **Command**: an console cascade like [CommandCascade]: the group's name in a pill key,
 *   a segmented spine growing down from it, and app keys sliding out of the spine left and right.
 * - **Imperial**: a tactical deploy: chamfered plates fly out of the name plate into a grid,
 *   under a band of warning chevrons.
 * - **Collective**: an orbit: the name in a hub node, apps on an ellipse joined to it by traces that
 *   stretch outward as it opens.
 *
 * All sizes come from [ScreenMetrics] (margins, columns, orientation) and [Ui.dp]
 * (zoom-aware dp), inside the window insets, so the panel fits phones, tablets, landscape and split
 * screen. Tapping an app launches it; tapping outside, Back or Home folds the panel; long-pressing
 * the title edits the group ([GroupEditor]). Test hooks: view id `group_panel`, tag
 * `"group_panel"`; app keys are tagged `"group_key_<n>"`.
 *
 * Java interop: [open], [close] and [isOpen] are [JvmStatic], so MainActivity keeps calling
 * `GroupPanel.close(this)`.
 */
internal class GroupPanel private constructor(
    private val activity: MainActivity,
    private val group: AppGroups.Group,
) : FrameLayout(activity) {
    private val pal: Palette = activity.pal ?: Prefs.get(activity).palette()
    private val metrics: ScreenMetrics = ScreenMetrics.of(activity)
    private val style: Int = if (Skin.imperial()) STYLE_IMPERIAL else if (Skin.collective()) STYLE_COLLECTIVE else STYLE_COMMAND
    private val title: TextView
    private val keys = ArrayList<View>()
    private val keyColors = ArrayList<Int>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Laid-out key positions (left, top, right, bottom) and the title's rectangle. */
    private var keyRects: Array<FloatArray> = emptyArray()
    private val titleRect = RectF()

    /** Command spine / grid extent; Collective hub centre and orbit radii; Imperial band width. */
    private var spineTop = 0f
    private var spineBottom = 0f
    private var rowH = 0f
    private var gap = 0f
    private var hubX = 0f
    private var hubY = 0f
    private var orbitX = 0f
    private var orbitY = 0f
    private var bandW = 0f
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var folding = false

    init {
        val a = activity
        id = R.id.group_panel
        tag = "group_panel"
        setWillNotDraw(false)
        isClickable = true
        setOnClickListener { fold() }
        contentDescription = a.getString(R.string.cd_group_panel, group.name)

        title = Ui.oneLine(
            Ui.bold(
                a,
                group.name,
                if (style == STYLE_COLLECTIVE) 15f else 20f,
                if (style == STYLE_COMMAND) Ui.ink() else ink(pal.c(0)),
            ),
        )
        title.gravity = if (style == STYLE_COMMAND) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER
        if (style == STYLE_COLLECTIVE) {
            title.isSingleLine = false
            title.maxLines = 2
        }
        val pad = Ui.dp(a, Ui.L.toFloat())
        title.setPadding(pad, 0, pad, 0)
        title.background = Ui.touch(
            if (style == STYLE_COMMAND) {
                Ui.shape(a, pal.c(0), Ui.PILL, COMMAND_RADIUS)
            } else {
                Skin.cell(a, pal.c(0), Ui.PILL, COMMAND_RADIUS)
            },
        )
        title.setOnClickListener { fold() }
        title.setOnLongClickListener { v ->
            Ui.longPress(v)
            editGroup()
            true
        }
        title.tag = "group_title"
        addView(title)

        val repo = AppRepo.get(a)
        var position = 0
        for (m in group.apps) {
            val app = repo.find(m.comp, m.user) ?: continue
            addKey(app, position++)
        }
        if (keys.isEmpty()) addEmptyKey()
    }

    // ---- opening and closing -------------------------------------------------------------------

    private fun unfold() {
        animateTo(1f, UNFOLD_MS, null)
    }

    /** Folds the panel away and removes it. */
    fun fold() {
        if (folding) return
        folding = true
        Sfx.play(context, Sfx.CLOSE)
        animateTo(0f, FOLD_MS) { detach() }
    }

    private fun detach() {
        animator?.cancel()
        val parent = parent as ViewGroup?
        parent?.removeView(this)
    }

    private fun animateTo(target: Float, ms: Long, after: Runnable?) {
        animator?.cancel()
        if (!Ui.animate(context)) {
            setProgress(target)
            after?.run()
            return
        }
        val anim = ValueAnimator.ofFloat(progress, target)
        animator = anim
        anim.duration = ms
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener { va -> setProgress(va.animatedValue as Float) }
        if (after != null) {
            anim.addListener(
                object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        after.run()
                    }
                },
            )
        }
        anim.start()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    // ---- keys ----------------------------------------------------------------------------------

    /** Key ink: Command keys use the dark console ink; Collective keys the matrix green; others a glow. */
    private fun ink(color: Int): Int {
        if (style == STYLE_COMMAND) return Ui.ink()
        if (style == STYLE_COLLECTIVE) return if (Ui.cloak) Skin.glow(color) else Skin.tileInk()
        return Skin.glow(color)
    }

    private fun leftKey(i: Int): Boolean = i % 2 == 0

    private fun keyBackground(color: Int, i: Int): Drawable {
        if (style == STYLE_COMMAND) {
            return Ui.touch(Ui.shape(context, color, if (leftKey(i)) Ui.LEFT else Ui.RIGHT, COMMAND_RADIUS))
        }
        return Ui.touch(Skin.cell(context, color, if (style == STYLE_COLLECTIVE) Ui.PILL else Ui.SQUARE, 18f))
    }

    private fun addKey(app: AppInfo, i: Int) {
        val c = context
        val color = pal.c(HomeLogic.accentIndex(i + 1))
        val key = LinearLayout(c)
        val commandStyle = style == STYLE_COMMAND
        key.orientation = if (commandStyle) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        key.gravity = Gravity.CENTER
        key.background = keyBackground(color, i)
        val inset = Ui.dp(c, if (commandStyle) 16f else 6f)
        key.setPadding(inset, Ui.dp(c, 4f), inset, Ui.dp(c, 4f))

        val icon = ImageView(c)
        val prefs = Prefs.get(c)
        icon.setImageDrawable(IconFactory.style(c, app, pal, prefs.iconStyle(), prefs.iconTone()))
        icon.scaleType = ImageView.ScaleType.FIT_CENTER
        val iconSize = Ui.dp(c, ICON_DP)
        val label = Ui.oneLine(Ui.bold(c, Ui.up(c, HomeLogic.shortLabel(app.label)), if (commandStyle) 15f else 12f, ink(color)))
        if (commandStyle) {
            label.gravity = if (leftKey(i)) Gravity.END or Gravity.BOTTOM else Gravity.START or Gravity.BOTTOM
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            lp.rightMargin = Ui.dp(c, Ui.S.toFloat())
            lp.leftMargin = lp.rightMargin
            label.setPadding(0, 0, 0, Ui.dp(c, 4f))
            if (leftKey(i)) {
                key.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
                key.addView(label, lp)
            } else {
                key.addView(label, lp)
                key.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
            }
        } else {
            label.gravity = Gravity.CENTER
            key.addView(icon, LinearLayout.LayoutParams(iconSize, iconSize))
            key.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        key.contentDescription = app.label
        key.tag = "group_key_$i"
        key.setOnClickListener { v ->
            activity.launchApp(app, v)
            post { detach() }
        }
        key.setOnLongClickListener { v ->
            Ui.longPress(v)
            editGroup()
            true
        }
        keys.add(key)
        keyColors.add(color)
        addView(key)
    }

    /** An empty group shows one key that opens the app picker. */
    private fun addEmptyKey() {
        val c = context
        val color = pal.c(2)
        val key = Ui.oneLine(Ui.bold(c, c.getString(R.string.group_add_apps), 14f, ink(color)))
        key.gravity = Gravity.CENTER
        key.background = keyBackground(color, 0)
        key.tag = "group_key_add"
        key.setOnClickListener {
            detach()
            GroupEditor.pickApps(activity, group.id, null)
        }
        keys.add(key)
        keyColors.add(color)
        addView(key)
    }

    private fun editGroup() {
        detach()
        GroupEditor.manage(activity, group.id, null)
    }

    // ---- layout --------------------------------------------------------------------------------

    private fun dp(v: Float): Float = Ui.dp(context, v).toFloat()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = View.MeasureSpec.getSize(widthSpec)
        val h = View.MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w, h)
        computeLayout(w, h)
        for (i in keys.indices) {
            val k = keyRects[i]
            keys[i].measure(
                View.MeasureSpec.makeMeasureSpec(Math.round(k[2] - k[0]), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.round(k[3] - k[1]), View.MeasureSpec.EXACTLY),
            )
        }
        title.measure(
            View.MeasureSpec.makeMeasureSpec(Math.round(titleRect.width()), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(Math.round(titleRect.height()), View.MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in keys.indices) {
            val k = keyRects[i]
            keys[i].layout(Math.round(k[0]), Math.round(k[1]), Math.round(k[2]), Math.round(k[3]))
        }
        title.layout(Math.round(titleRect.left), Math.round(titleRect.top), Math.round(titleRect.right), Math.round(titleRect.bottom))
        apply()
    }

    /** Works out every rectangle for the current style inside the insets and ScreenMetrics margins. */
    private fun computeLayout(w: Int, h: Int) {
        val margin = metrics.marginPx().toFloat()
        val left = activity.insetLeft + margin
        val right = w - activity.insetRight - margin
        val top = activity.insetTop + margin
        val bottom = h - activity.insetBottom - margin
        val cw = min(right - left, dp(MAX_CONTENT_DP))
        val cx = (left + right) / 2f
        val area = RectF(cx - cw / 2f, top, cx + cw / 2f, max(top + dp(120f), bottom))
        gap = dp(Ui.GUTTER.toFloat())
        val n = keys.size
        keyRects = Array(n) { FloatArray(0) }
        when (style) {
            STYLE_COMMAND -> layoutCascade(area, n)
            STYLE_COLLECTIVE -> layoutOrbit(area, n)
            else -> layoutGrid(area, n)
        }
    }

    /** Command: title pill on top, two key columns either side of a centred spine. */
    private fun layoutCascade(a: RectF, n: Int) {
        val titleH = dp(COMMAND_TITLE_H)
        val spine = dp(COMMAND_SPINE)
        val rows = max(1, (n + 1) / 2)
        val avail = a.height() - titleH - gap * 2
        rowH = clamp(avail / rows - gap, dp(COMMAND_ROW_MIN), dp(COMMAND_ROW_MAX))
        if (rows * (rowH + gap) > avail) rowH = max(dp(24f), avail / rows - gap)
        val half = min((a.width() - spine) / 2f - gap, dp(COMMAND_KEY_MAX_W))
        val total = titleH + gap * 2 + rows * (rowH + gap) - gap
        val y0 = a.top + max(0f, (a.height() - total) / 2f)
        val cx = a.centerX()
        val titleW = min(a.width() * 0.7f, dp(COMMAND_TITLE_MAX_W))
        titleRect.set(cx - titleW / 2f, y0, cx + titleW / 2f, y0 + titleH)
        val keysTop = y0 + titleH + gap * 2
        for (i in 0 until n) {
            val y = keysTop + (i / 2) * (rowH + gap)
            val x = if (leftKey(i)) cx - spine / 2f - gap - half else cx + spine / 2f + gap
            keyRects[i] = floatArrayOf(x, y, x + half, y + rowH)
        }
        spineTop = y0 + titleH + gap * 0.5f
        spineBottom = keysTop + rows * (rowH + gap) - gap
    }

    /** Imperial: title plate on top, keys in a grid whose column count follows the window width. */
    private fun layoutGrid(a: RectF, n: Int) {
        val cols = max(1, min(n, metrics.columns(120f, 2, if (metrics.isLandscape) 5 else 3)))
        val titleH = dp(46f)
        val rows = max(1, (n + cols - 1) / cols)
        val pad = 0f
        val gridW = min(a.width() - pad * 2, cols * dp(190f))
        val keyW = (gridW - gap * (cols - 1)) / cols
        val avail = a.height() - titleH - gap * 3 - pad * 2
        rowH = clamp(avail / rows - gap, dp(GRID_ROW_MIN), dp(GRID_ROW_MAX))
        if (rows * (rowH + gap) > avail) rowH = max(dp(28f), avail / rows - gap)
        val total = titleH + gap * 3 + rows * (rowH + gap) - gap + pad * 2
        val y0 = a.top + max(0f, (a.height() - total) / 2f) + pad
        val cx = a.centerX()
        val titleW = min(gridW, max(gridW * 0.8f, dp(200f)))
        titleRect.set(cx - titleW / 2f, y0, cx + titleW / 2f, y0 + titleH)
        val keysTop = y0 + titleH + gap * 3
        val x0 = cx - gridW / 2f
        for (i in 0 until n) {
            val row = i / cols
            val col = i % cols
            // centre a partial last row
            val inRow = if (row == rows - 1) n - row * cols else cols
            val rowX = x0 + (gridW - (inRow * keyW + (inRow - 1) * gap)) / 2f
            val x = rowX + col * (keyW + gap)
            val y = keysTop + row * (rowH + gap)
            keyRects[i] = floatArrayOf(x, y, x + keyW, y + rowH)
        }
        spineTop = y0 + titleH + gap
        spineBottom = keysTop + rows * (rowH + gap) - gap
        bandW = gridW
    }

    /** Collective: the title is the hub; keys sit on an ellipse around it. */
    private fun layoutOrbit(a: RectF, n: Int) {
        val hub = min(dp(COLLECTIVE_HUB), min(a.width(), a.height()) * 0.34f)
        hubX = a.centerX()
        hubY = a.centerY()
        titleRect.set(hubX - hub / 2f, hubY - hub / 2f, hubX + hub / 2f, hubY + hub / 2f)
        val ry0 = a.height() / 2f
        val rx0 = min(a.width() / 2f, ry0 * 1.6f)
        val circumference = (Math.PI * (rx0 + ry0)).toFloat()
        val kw = clamp(circumference / max(1, n) * 0.8f, dp(COLLECTIVE_KEY_MIN), dp(COLLECTIVE_KEY_MAX))
        val kh = kw * 0.86f
        orbitX = max(hub / 2f + kw / 2f, rx0 - kw / 2f)
        orbitY = max(hub / 2f + kh / 2f, ry0 - kh / 2f)
        for (i in 0 until n) {
            val angle = -Math.PI / 2 + i * 2 * Math.PI / max(1, n)
            val x = hubX + Math.cos(angle).toFloat() * orbitX
            val y = hubY + Math.sin(angle).toFloat() * orbitY
            keyRects[i] = floatArrayOf(x - kw / 2f, y - kh / 2f, x + kw / 2f, y + kh / 2f)
        }
    }

    // ---- animation and drawing -----------------------------------------------------------------

    private fun setProgress(p: Float) {
        progress = p
        apply()
    }

    /** How far key `i` has deployed (keys follow one another). */
    private fun keyProgress(i: Int): Float {
        val stagger = if (keys.size > 1) 0.3f / keys.size else 0f
        return ease((progress - 0.25f - i * stagger) / 0.45f)
    }

    /** Applies the current progress to the title and keys (per-style deploy motion). */
    private fun apply() {
        val head = ease(progress / 0.35f)
        title.alpha = head
        title.scaleX = 0.6f + 0.4f * head
        title.scaleY = 0.6f + 0.4f * head
        var i = 0
        while (i < keys.size && i < keyRects.size) {
            val v = keys[i]
            val e = keyProgress(i)
            val k = keyRects[i]
            val kx = (k[0] + k[2]) / 2f
            val ky = (k[1] + k[3]) / 2f
            when (style) {
                STYLE_COMMAND -> {
                    v.pivotX = if (leftKey(i)) v.width.toFloat() else 0f
                    v.pivotY = v.height / 2f
                    v.scaleX = max(0.01f, e)
                }
                STYLE_IMPERIAL -> {
                    v.translationX = (1 - e) * (titleRect.centerX() - kx)
                    v.translationY = (1 - e) * (titleRect.centerY() - ky)
                }
                STYLE_COLLECTIVE -> {
                    v.translationX = (1 - e) * (hubX - kx)
                    v.translationY = (1 - e) * (hubY - ky)
                    v.scaleX = 0.4f + 0.6f * e
                    v.scaleY = 0.4f + 0.6f * e
                }
            }
            v.alpha = 0.15f + 0.85f * e
            v.visibility = if (e <= 0.001f) View.INVISIBLE else View.VISIBLE
            i++
        }
        invalidate()
    }

    /** Scrim, then the style's decorations (spine, chevron band, orbit traces), then the keys. */
    override fun dispatchDraw(cv: Canvas) {
        val fade = clamp(progress / 0.3f, 0f, 1f)
        paint.style = Paint.Style.FILL
        paint.color = Ui.alpha(Skin.bg(), SCRIM_ALPHA * fade)
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val d = Ui.density(context)
        when (style) {
            STYLE_COMMAND -> drawSpine(cv)
            STYLE_IMPERIAL -> drawChevrons(cv, fade)
            STYLE_COLLECTIVE -> drawTraces(cv, d)
        }
        super.dispatchDraw(cv)
    }

    /** Command: one console segment per row, growing down from the title key. */
    private fun drawSpine(cv: Canvas) {
        val grow = ease((progress - 0.12f) / 0.45f)
        if (grow <= 0) return
        val cx = titleRect.centerX()
        val sw = dp(COMMAND_SPINE) / 2f
        val reach = spineTop + (spineBottom - spineTop) * grow
        val seg = intArrayOf(pal.c(1), pal.c(3), pal.c(5), pal.c(2))
        paint.style = if (Ui.cloak) Paint.Style.STROKE else Paint.Style.FILL
        paint.strokeWidth = dp(1.5f)
        var y = spineTop
        var k = 0
        while (y < reach) {
            val end = min(y + rowH, reach)
            paint.color = seg[k++ % seg.size]
            cv.drawRect(cx - sw, y, cx + sw, end, paint)
            y = end + gap
        }
        paint.style = Paint.Style.FILL
    }

    /** Imperial: a band of chevrons and warning triangles under the title plate. */
    private fun drawChevrons(cv: Canvas, alpha: Float) {
        val w = bandW * ease(progress / 0.5f)
        if (w <= 0) return
        val cx = titleRect.centerX()
        val y = spineTop
        val h = gap * 1.2f
        ImperialDraw.row(cv, cx - w / 2f, y, w, h, intArrayOf(ImperialDraw.RED, ImperialDraw.ORANGE, ImperialDraw.YELLOW), alpha)
        val s = dp(10f)
        ImperialDraw.tri(cv, titleRect.left - s * 1.4f, titleRect.centerY(), s, s, false, pal.c(1), alpha)
        ImperialDraw.tri(cv, titleRect.right + s * 1.4f, titleRect.centerY(), s, s, false, pal.c(1), alpha)
    }

    /** Collective: curved traces from the hub to every node (following the nodes as they fly out). */
    private fun drawTraces(cv: Canvas, d: Float) {
        var i = 0
        while (i < keys.size && i < keyRects.size) {
            val v = keys[i]
            if (v.visibility != View.VISIBLE) {
                i++
                continue
            }
            val k = keyRects[i]
            val x = (k[0] + k[2]) / 2f + v.translationX
            val y = (k[1] + k[3]) / 2f + v.translationY
            CollectiveDraw.curveTrace(cv, hubX, hubY, x, y, if (i % 2 == 0) 0.18f else -0.18f, keyColors[i], d, keyProgress(i))
            i++
        }
        CollectiveDraw.ringNode(cv, hubX, hubY, titleRect.width() / 2f + dp(6f), pal.c(1), d, ease(progress / 0.35f))
    }

    companion object {
        /** Unfold / fold durations, in ms. */
        private const val UNFOLD_MS = 460L
        private const val FOLD_MS = 220L

        /** Scrim opacity over the home screen. */
        private const val SCRIM_ALPHA = 0.82f

        /** Widest the panel content gets, in dp. */
        private const val MAX_CONTENT_DP = 620f

        /** Command: title key height and max width, spine width, row height range, widest key (dp). */
        private const val COMMAND_TITLE_H = 50f
        private const val COMMAND_TITLE_MAX_W = 300f
        private const val COMMAND_SPINE = 20f
        private const val COMMAND_ROW_MIN = 36f
        private const val COMMAND_ROW_MAX = 62f
        private const val COMMAND_KEY_MAX_W = 260f

        /** Command key corner radius and icon size (dp). */
        private const val COMMAND_RADIUS = 30f
        private const val ICON_DP = 26f

        /** Grid key height range for the Imperial layout (dp). */
        private const val GRID_ROW_MIN = 44f
        private const val GRID_ROW_MAX = 64f

        /** Collective hub diameter and key size range (dp). */
        private const val COLLECTIVE_HUB = 104f
        private const val COLLECTIVE_KEY_MIN = 58f
        private const val COLLECTIVE_KEY_MAX = 88f

        private const val STYLE_COMMAND = 0
        private const val STYLE_IMPERIAL = 1
        private const val STYLE_COLLECTIVE = 2

        /**
         * Unfolds the group with `groupId` over the home screen (replacing any open group).
         *
         * @return false (after an error chirp and toast) when the group no longer exists
         */
        @JvmStatic
        fun open(a: MainActivity, groupId: String?): Boolean {
            val g = AppGroups.find(AppGroups.load(Prefs.get(a)), groupId)
            if (g == null) {
                Sfx.play(a, Sfx.ERROR)
                a.toast(a.getString(R.string.toast_group_missing))
                return false
            }
            val rootView = a.findViewById<View>(R.id.home_root) as? ViewGroup ?: return false
            removeNow(a)
            val panel = GroupPanel(a, g)
            rootView.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            Sfx.play(a, Sfx.OPEN)
            panel.unfold()
            return true
        }

        /** Folds the open group panel, if any. Used by Back and Home; true when one was open. */
        @JvmStatic
        fun close(a: MainActivity): Boolean {
            val v = a.findViewById<View>(R.id.group_panel) as? GroupPanel ?: return false
            v.fold()
            return true
        }

        /** True while a group panel is on screen. */
        @JvmStatic
        fun isOpen(a: MainActivity): Boolean = a.findViewById<View>(R.id.group_panel) is GroupPanel

        /** Removes an open panel without animation. */
        private fun removeNow(a: MainActivity) {
            (a.findViewById<View>(R.id.group_panel) as? GroupPanel)?.detach()
        }

        private fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))

        private fun ease(x: Float): Float {
            val c = clamp(x, 0f, 1f)
            val inv = 1f - c
            return 1f - inv * inv * inv
        }
    }
}
