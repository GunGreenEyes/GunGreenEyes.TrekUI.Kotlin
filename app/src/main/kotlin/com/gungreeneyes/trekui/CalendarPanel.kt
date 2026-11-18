package com.gungreeneyes.trekui

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.provider.CalendarContract
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService

/**
 * Home panel "SCHEDULE": the next appointments (up to [MAX_EVENTS] within
 * [DAYS_AHEAD] days) from the device calendar, read-only. Needs READ_CALENDAR, asked only
 * when the person taps GRANT. Watches the calendar provider while visible and plays a comm alert
 * once shortly before an appointment starts (and a hail as it starts) while the home screen is on.
 */
internal class CalendarPanel(a: MainActivity, accent: Int) : Panel(a, accent) {
    /** Bumped on every refresh and pause; query results from an older one are dropped. */
    private var generation = 0
    private var observing = false
    private val observer: ContentObserver =
        object : ContentObserver(a.handler()) {
            override fun onChange(self: Boolean) = refresh()
        }

    private class Ev {
        var id = 0L
        var begin = 0L
        var end = 0L
        var title: String = ""
        var where: String? = null
        var allDay = false
        var color = 0
    }

    override fun id(): String = Prefs.W_CALENDAR

    override fun title(): String = "SCHEDULE"

    override fun build() {
        card!!.status.setOnClickListener(Ui.feedback { v -> openCalendar(System.currentTimeMillis(), v) })
        refresh()
    }

    /** True when READ_CALENDAR is granted. */
    private fun granted(): Boolean =
        a.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun resume() {
        if (granted() && !observing) {
            try {
                a.contentResolver.registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer)
                observing = true
            } catch (ignored: Exception) {
            }
        }
        refresh()
    }

    override fun pause() {
        generation++
        if (observing) {
            a.contentResolver.unregisterContentObserver(observer)
            observing = false
        }
    }

    override fun tick() = refresh()

    override fun onPermissionResult(requestCode: Int, granted: Boolean) {
        if (requestCode != MainActivity.REQ_CALENDAR) return
        if (granted) resume() else refresh()
    }

    /** Opens the calendar app at a time. */
    private fun openCalendar(time: Long, v: View) {
        val u = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(time.toString()).build()
        a.startSafe(Intent(Intent.ACTION_VIEW, u), v)
    }

    /**
     * Shows the permission prompt, or queries the calendar in the background and then rebuilds
     * the list and plays any due alerts (results are dropped if a newer refresh or a pause came
     * first, or the activity is gone).
     */
    fun refresh() {
        val gen = ++generation
        if (!granted()) {
            val card = card!!
            val b = card.body
            b.removeAllViews()
            card.status.text = "ACCESS REQUIRED"
            val t = Ui.text(
                a,
                "Allow read-only calendar access to show your upcoming appointments here. " +
                    "Nothing leaves your phone.",
                15f,
                pal.textHi,
            )
            t.setLineSpacing(0f, 1.1f)
            b.addView(t)
            val btn = Ui.button(a, "GRANT CALENDAR ACCESS", accent, Ui.PILL) { v ->
                if (Prefs.get(a).b("calAsked", false) && !a.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)) {
                    // Permanently denied: send to app settings.
                    a.startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)), v)
                } else {
                    Prefs.get(a).put("calAsked", true)
                    a.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), MainActivity.REQ_CALENDAR)
                }
            }
            val lp = LinearLayout.LayoutParams(-1, Ui.dp(a, 52f))
            lp.topMargin = Ui.dp(a, Ui.M.toFloat())
            b.addView(btn, lp)
            return
        }
        val resolver = a.applicationContext.contentResolver
        IO.execute {
            val evs = query(resolver)
            a.runOnUiThread {
                if (gen != generation || a.isFinishing || a.isDestroyed) return@runOnUiThread
                show(evs)
            }
        }
    }

    /** Rebuilds the list from query results (null = provider unavailable). */
    private fun show(evs: List<Ev>?) {
        val card = card!!
        val b = card.body
        b.removeAllViews()
        if (evs == null) {
            card.status.text = "UNAVAILABLE"
            b.addView(Ui.text(a, "CALENDAR PROVIDER UNAVAILABLE", 15f, pal.textLo))
            return
        }
        card.status.text = if (evs.isEmpty()) "CLEAR" else evs.size.toString() + (if (evs.size == 1) " ENTRY" else " ENTRIES")
        if (evs.isEmpty()) {
            b.addView(Ui.text(a, "NO APPOINTMENTS IN THE NEXT 7 DAYS", 15f, pal.textLo))
            return
        }
        val now = System.currentTimeMillis()
        if (alerted.size > MAX_ALERT_KEYS) alerted.clear()
        for (e in evs) {
            val row = LinearLayout(a)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, Ui.dp(a, Ui.GUTTER.toFloat()), 0, Ui.dp(a, Ui.GUTTER.toFloat()))
            row.background = Ui.touchArea(a, 12f)
            val chip = View(a)
            chip.background = Ui.shape(a, if (e.color != 0) (e.color or OPAQUE) else accent, Ui.PILL, 0f)
            row.addView(chip, LinearLayout.LayoutParams(Ui.dp(a, 7f), Ui.dp(a, 38f)))
            val col = LinearLayout(a)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(Ui.dp(a, Ui.M.toFloat()), 0, 0, 0)
            val live = !e.allDay && e.begin <= now && e.end > now
            col.addView(Ui.oneLine(Ui.bold(a, e.title, 17f, if (live) pal.c(0) else pal.textHi)))
            val where = e.where
            val time = whenText(e) + (if (!where.isNullOrEmpty()) "  •  $where" else "")
            col.addView(Ui.oneLine(Ui.text(a, time.uppercase(Locale.getDefault()), 13f, if (live) pal.c(0) else pal.textLo)))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            row.setOnClickListener(
                Ui.feedback { v ->
                    val i = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
                    i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin)
                    i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)
                    a.startSafe(i, v)
                },
            )
            b.addView(row, LinearLayout.LayoutParams(-1, -2))
            // Comm alert once when an appointment is about to start (only while home is visible).
            val k = e.id.toString() + ":" + e.begin
            if (!e.allDay && e.begin > now && e.begin - now <= ALERT_BEFORE_MS && !alerted.contains(k) && a.isResumedNow()) {
                alerted.add(k)
                Sfx.play(a, Sfx.ALERT)
            } else if (!e.allDay && e.begin <= now && now - e.begin < STARTING_WINDOW_MS && !alerted.contains("$k!") && a.isResumedNow()) {
                alerted.add("$k!")
                alerted.add(k)
                Sfx.play(a, Sfx.HAIL) // appointment starting now
            }
        }
    }

    /** "TODAY 9:30 AM – 10:00 AM", "TOMORROW  •  ALL DAY", "FRI JUN 6 …". All-day events are UTC dates. */
    private fun whenText(e: Ev): String {
        val today = Calendar.getInstance()
        val c = Calendar.getInstance()
        if (e.allDay) c.timeZone = TimeZone.getTimeZone("UTC")
        c.timeInMillis = e.begin
        var day = relativeDay(dayDiff(today, c, e.allDay))
        if (day == null) {
            val f = SimpleDateFormat("EEE MMM d", Locale.getDefault())
            if (e.allDay) f.timeZone = TimeZone.getTimeZone("UTC")
            day = f.format(Date(e.begin))
        }
        if (e.allDay) return "$day  •  ALL DAY"
        val t = SimpleDateFormat(if (a.use24h()) "HH:mm" else "h:mm a", Locale.getDefault())
        return day + "  " + t.format(Date(e.begin)) + " – " + t.format(Date(e.end))
    }

    companion object {
        /** How far ahead to look. */
        const val DAYS_AHEAD = 7

        /** How many entries to show. */
        const val MAX_EVENTS = 4

        /** Alert this long before an appointment. */
        const val ALERT_BEFORE_MS = 10 * 60 * 1000L

        /** Treat an appointment as "starting now" for this long after it begins. */
        const val STARTING_WINDOW_MS = 2 * 60 * 1000L
        private const val DAY_MS = 24L * 3600 * 1000

        /** Alert keys remembered; older ones are dropped so the set can't grow without bound. */
        private const val MAX_ALERT_KEYS = 200
        private const val OPAQUE = -0x1000000 // 0xFF000000

        /** Events already announced ("id:begin", plus "!" for the starting-now hail). */
        private val alerted = HashSet<String>()

        /** Calendar provider queries run here, never on the main thread. */
        private val IO: ExecutorService = Http.newExecutor("trekui-calendar")

        /** "TODAY" / "TOMORROW" for a day difference of 0 / 1, else null (show the date). */
        fun relativeDay(dayDiff: Int): String? =
            when (dayDiff) {
                0 -> "TODAY"
                1 -> "TOMORROW"
                else -> null
            }

        /** Whole calendar days from today's date to c's date (UTC dates for all-day events). */
        fun dayDiff(today: Calendar, c: Calendar, allDay: Boolean): Int {
            val t = Calendar.getInstance(if (allDay) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
            t.clear()
            t.set(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH))
            val d = Calendar.getInstance(if (allDay) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
            d.clear()
            d.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH))
            return Math.round((d.timeInMillis - t.timeInMillis) / DAY_MS.toDouble()).toInt()
        }

        /**
         * Visible event instances from yesterday (to catch ones still running) to [DAYS_AHEAD]
         * days ahead, skipping finished ones. Null when the provider fails; empty without permission.
         */
        private fun query(resolver: ContentResolver): List<Ev>? {
            val now = System.currentTimeMillis()
            val ub = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(ub, now - DAY_MS)
            ContentUris.appendId(ub, now + DAYS_AHEAD * DAY_MS)
            val proj = arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DISPLAY_COLOR,
            )
            val out = ArrayList<Ev>()
            try {
                val cursor = resolver.query(
                    ub.build(),
                    proj,
                    CalendarContract.Instances.VISIBLE + "=1",
                    null,
                    CalendarContract.Instances.BEGIN + " ASC",
                ) ?: return null
                cursor.use { c ->
                    // Midnight today in UTC terms, for all-day events (stored as UTC dates).
                    val local = Calendar.getInstance()
                    val utcToday = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                    utcToday.clear()
                    utcToday.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
                    while (c.moveToNext() && out.size < MAX_EVENTS) {
                        val e = Ev()
                        e.id = c.getLong(0)
                        e.title = if (c.isNull(1) || c.getString(1).trim().isEmpty()) "(NO TITLE)" else c.getString(1).trim()
                        e.begin = c.getLong(2)
                        e.end = c.getLong(3)
                        e.allDay = c.getInt(4) == 1
                        e.where = if (c.isNull(5)) null else c.getString(5).trim()
                        e.color = if (c.isNull(6)) 0 else c.getInt(6)
                        if (if (e.allDay) e.end <= utcToday.timeInMillis else e.end <= now) continue
                        out.add(e)
                    }
                }
            } catch (se: SecurityException) {
                return ArrayList()
            } catch (ex: RuntimeException) {
                return null // provider missing or crashed
            }
            return out
        }
    }
}
