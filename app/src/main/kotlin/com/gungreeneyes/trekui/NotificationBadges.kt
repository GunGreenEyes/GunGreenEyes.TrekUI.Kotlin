package com.gungreeneyes.trekui

import android.app.Notification
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import java.util.Collections
import java.util.Locale

/**
 * Unread-notification counts drawn as badges on home tiles, reveal-hub nodes and dock keys, in
 * every interface (console pill, Imperial blade, Collective block).
 *
 * Counts come from [MediaListener] (the notification listener the owner grants in
 * Android's "notification access"); without that grant there are simply no badges. They are held
 * in memory only, never written to disk, and are recomputed only while the home screen is resumed.
 * A notification counts when it is from another app, clearable (not an ongoing / foreground
 * service notification), allowed to show a badge (Android's per-app / per-channel "notification
 * dot" setting) and not a group summary whose children are also listed; it counts its
 * [Notification.number] when the app sets one, else 1. Counting is pure ([count]).
 *
 * Badges are a [Drawable] on the view's overlay, so they decorate any tile view without
 * changing its layout; [attach] is the one hook each home builder calls, and
 * [refresh] re-reads the counts for every decorated view under a root.
 */
internal object NotificationBadges {
    /** Key for the total over all apps (shown on tiles assigned to the console shade). */
    const val ALL = "*"

    /** Preference: show badges (default on). */
    const val PREF = "notifBadges"

    /** Where the badge sits: the view's top-end corner, bottom-start corner, or the top-end of a centred icon. */
    const val TOP_END = 0
    const val BOTTOM_START = 1
    const val ICON = 2

    /** Largest number drawn; more shows as "99+". */
    const val MAX_SHOWN = 99

    private const val BADGE_H_DP = 18f
    private const val TEXT_SP = 11f
    private const val INSET_DP = 5f
    private const val PAD_DP = 5f

    /** The current counts by package (and [ALL]); replaced wholesale, never mutated. */
    @Volatile
    private var counts: Map<String, Int> = Collections.emptyMap()

    /** One active notification, reduced to what counting needs. */
    class Entry(
        @JvmField val pkg: String?,
        @JvmField val group: String?,
        @JvmField val summary: Boolean,
        @JvmField val ongoing: Boolean,
        @JvmField val canBadge: Boolean,
        @JvmField val number: Int,
    )

    // ---- counting (pure) ----------------------------------------------------------------------------

    /**
     * Unread counts by package, plus the total under [ALL] (see the class comment for the
     * rules). Packages with nothing to count are absent.
     */
    @JvmStatic
    fun count(entries: List<Entry>, ownPackage: String?): Map<String, Int> {
        val groupsWithChildren = HashSet<String>()
        for (e in entries) if (!e.summary && e.group != null) groupsWithChildren.add(e.pkg + "|" + e.group)
        val out = HashMap<String, Int>()
        var total = 0
        for (e in entries) {
            val pkg = e.pkg
            if (pkg == null || pkg == ownPackage || !e.canBadge || e.ongoing) continue
            if (e.summary && e.group != null && groupsWithChildren.contains(pkg + "|" + e.group)) continue
            val n = if (e.number > 0) e.number else 1
            out[pkg] = (out[pkg] ?: 0) + n
            total += n
        }
        if (total > 0) out[ALL] = total
        return out
    }

    /** Text for a count: "" for none, the number up to [MAX_SHOWN], then "99+". */
    @JvmStatic
    fun label(count: Int): String {
        if (count <= 0) return ""
        return if (count > MAX_SHOWN) "$MAX_SHOWN+" else count.toString()
    }

    /** The package of a flattened ComponentName ("pkg/.Activity" → "pkg"), or null. */
    @JvmStatic
    fun packageOf(flatComponent: String?): String? {
        if (flatComponent == null) return null
        val slash = flatComponent.indexOf('/')
        return if (slash <= 0) null else flatComponent.substring(0, slash)
    }

    /** What a tile's badge counts: its app's package, [ALL] for the console shade, else null. */
    @JvmStatic
    fun keyFor(tile: Tile?): String? {
        if (tile == null) return null
        val comp = tile.comp
        if (comp != null) return packageOf(comp)
        return if (Tile.A_SHADE == tile.action) ALL else null
    }

    /** The current count for a key (package or [ALL]). */
    @JvmStatic
    fun countFor(key: String?): Int = if (key == null) 0 else counts[key] ?: 0

    // ---- live counts ------------------------------------------------------------------------------

    /** Forgets every count (notification access lost). */
    @JvmStatic
    fun clear() {
        counts = Collections.emptyMap()
    }

    /**
     * Recomputes the counts from the listener's active notifications (main thread, after a
     * debounced change). Clears them when badges are switched off in CONFIG.
     */
    @JvmStatic
    fun update(listener: NotificationListenerService?, c: Context) {
        if (listener == null || !Prefs.get(c).b(PREF, true)) {
            clear()
            return
        }
        val active: Array<StatusBarNotification>? = try {
            listener.activeNotifications
        } catch (e: RuntimeException) {
            return // listener not yet (or no longer) connected
        }
        var map: NotificationListenerService.RankingMap? = null
        try {
            map = listener.currentRanking
        } catch (ignored: RuntimeException) {
            // no ranking: assume badges allowed
        }
        val ranking = NotificationListenerService.Ranking()
        val entries = ArrayList<Entry>()
        if (active != null) {
            for (s in active) {
                val n = s.notification ?: continue
                val canBadge = map == null || !map.getRanking(s.key, ranking) || ranking.canShowBadge()
                entries.add(
                    Entry(
                        s.packageName, n.group, (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0,
                        !s.isClearable, canBadge, n.number,
                    ),
                )
            }
        }
        counts = Collections.unmodifiableMap(count(entries, c.packageName))
    }

    // ---- decoration ---------------------------------------------------------------------------------

    /**
     * Decorates a tile view with its badge (or removes it when the tile has nothing to count).
     *
     * @param anchor     [TOP_END], [BOTTOM_START] or [ICON]
     * @param iconHalfPx for [ICON]: half the centred icon's size, in px
     */
    @JvmStatic
    fun attach(view: View?, tile: Tile?, anchor: Int, iconHalfPx: Int) {
        attach(view, keyFor(tile), anchor, iconHalfPx)
    }

    /** Decorates a view with the badge for `key` (a package or [ALL]); null removes it. */
    @JvmStatic
    fun attach(view: View?, key: String?, anchor: Int, iconHalfPx: Int) {
        if (view == null) return
        val old = view.getTag(R.id.trekui_notification_badge)
        if (old is Badge) {
            view.overlay.remove(old)
            view.removeOnLayoutChangeListener(old)
            if (old.baseDescription != null) view.contentDescription = old.baseDescription
            view.setTag(R.id.trekui_notification_badge, null)
        }
        if (key == null) return
        val badge = Badge(view.context, key, anchor, iconHalfPx, view.contentDescription)
        view.setTag(R.id.trekui_notification_badge, badge)
        badge.setBounds(0, 0, view.width, view.height)
        view.addOnLayoutChangeListener(badge)
        view.overlay.add(badge)
        badge.sync(view)
    }

    /** Re-reads the counts for every decorated view under `root`. */
    @JvmStatic
    fun refresh(root: View?) {
        if (root == null) return
        val tag = root.getTag(R.id.trekui_notification_badge)
        if (tag is Badge) tag.sync(root)
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) refresh(root.getChildAt(i))
        }
    }

    /** The badge itself: a count in the current interface's shape, drawn on the view's overlay. */
    private class Badge(
        c: Context,
        private val key: String,
        private val anchor: Int,
        private val iconHalfPx: Int,
        val baseDescription: CharSequence?,
    ) : Drawable(), View.OnLayoutChangeListener {
        private val descriptionFormat: String = c.getString(R.string.badge_cd)
        private val density: Float = c.resources.displayMetrics.density * Ui.zoom(c)
        private val fill: Int
        private val ink: Int
        private val edge: Int
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val path = Path()
        private var text = ""

        init {
            val pal = Prefs.get(c).palette()
            if (Skin.collective()) {
                fill = pal.c(0)
                ink = Color.BLACK
            } else {
                fill = pal.alert
                ink = Color.BLACK
            }
            edge = Color.BLACK
            paint.typeface = Ui.font(c, true)
            paint.textSize = TEXT_SP * density * Skin.textScale()
            paint.textAlign = Paint.Align.CENTER
        }

        /** Updates the text and the view's spoken description from the current count. */
        fun sync(view: View) {
            val n = countFor(key)
            val now = label(n)
            if (now != text) {
                text = now
                invalidateSelf()
                view.invalidate()
            }
            if (baseDescription != null) {
                view.contentDescription = if (n > 0) {
                    String.format(Locale.getDefault(), descriptionFormat, baseDescription, n)
                } else {
                    baseDescription
                }
            }
        }

        override fun onLayoutChange(v: View, l: Int, t: Int, right: Int, bottom: Int, ol: Int, ot: Int, or: Int, ob: Int) {
            setBounds(0, 0, right - l, bottom - t)
            v.invalidate()
        }

        override fun draw(canvas: Canvas) {
            if (text.isEmpty()) return
            val b = bounds
            val h = BADGE_H_DP * density
            val w = Math.max(h, paint.measureText(text) + 2 * PAD_DP * density)
            val inset = INSET_DP * density
            var left: Float
            var top: Float
            when (anchor) {
                BOTTOM_START -> {
                    left = b.left + inset
                    top = b.bottom - inset - h
                }
                ICON -> {
                    left = b.exactCenterX() + iconHalfPx - w * 0.45f
                    top = b.exactCenterY() - iconHalfPx - h * 0.35f
                }
                else -> {
                    left = b.right - inset - w
                    top = b.top + inset
                }
            }
            left = Math.max(b.left.toFloat(), Math.min(left, b.right - w))
            top = Math.max(b.top.toFloat(), Math.min(top, b.bottom - h))
            r.set(left, top, left + w, top + h)
            shape()
            paint.style = Paint.Style.FILL
            paint.color = if (Ui.cloak) Ui.alpha(fill, 0.7f) else fill
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.color = edge
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.color = ink
            val fm = paint.fontMetrics
            canvas.drawText(text, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2, paint)
        }

        /** The badge outline for the current interface. */
        private fun shape() {
            path.reset()
            val h = r.height()
            if (Skin.imperial()) {
                val cut = h * 0.35f // blade: clipped corners, pointed ends
                path.moveTo(r.left + cut, r.top)
                path.lineTo(r.right, r.top)
                path.lineTo(r.right - cut, r.bottom)
                path.lineTo(r.left, r.bottom)
                path.close()
            } else if (Skin.collective()) {
                path.addRect(r, Path.Direction.CW)
            } else {
                path.addRoundRect(r, h / 2, h / 2, Path.Direction.CW) // console pill
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(cf: ColorFilter?) {
            paint.colorFilter = cf
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
