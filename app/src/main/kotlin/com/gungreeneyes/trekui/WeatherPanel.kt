package com.gungreeneyes.trekui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Home panel with live weather: current conditions and a three-day forecast from Open-Meteo
 * ([Weather]). The location is the device's (coarse location, asked only when chosen) or a
 * searched city. The forecast JSON is cached in preferences
 * and refreshed at most every [MAX_AGE] ms while the home screen is visible.
 *
 * All network and geocoding work runs on [Weather.IO]; results are applied on the main
 * thread only while the activity is still alive.
 */
internal class WeatherPanel(a: MainActivity, accent: Int) : Panel(a, accent) {
    private val p: Prefs = Prefs.get(a)
    private val metrics: ScreenMetrics = ScreenMetrics.of(a)
    private var busy = false
    private var lastAttempt = 0L

    /** False once the activity is finishing or destroyed: late background results are dropped. */
    private fun alive(): Boolean = !a.isFinishing && !a.isDestroyed

    /** Runs on the main thread if the activity is still alive. */
    private fun onUi(r: Runnable) {
        a.runOnUiThread { if (alive()) r.run() }
    }

    /** A stored coordinate, or NaN when missing or unreadable. */
    private fun coord(key: String): Double {
        val s = p.s(key, null) ?: return Double.NaN
        return try {
            s.toDouble()
        } catch (e: NumberFormatException) {
            Double.NaN
        }
    }

    override fun id(): String = Prefs.W_WEATHER

    override fun title(): String = "WEATHER"

    override fun build() {
        render()
        card!!.body.setOnClickListener(
            Ui.feedback {
                if (p.i("wxMode", Prefs.WX_NONE) == Prefs.WX_NONE) chooseLocation() else refresh(true)
            },
        )
    }

    override fun extraMenu(): Array<String> =
        arrayOf("REFRESH NOW", "CHANGE LOCATION", if (p.fahrenheit()) "USE °C" else "USE °F")

    override fun onExtraMenu(which: Int) {
        when (which) {
            0 -> refresh(true)
            1 -> chooseLocation()
            else -> {
                p.put("wxF", !p.fahrenheit())
                p.put("wxTime", 0L)
                refresh(true)
            }
        }
    }

    override fun resume() = refresh(false)

    override fun tick() = refresh(false)

    // ---- location ------------------------------------------------------------------------------

    /** Asks whether to use the device location or search for a city. */
    fun chooseLocation() {
        a.showing(
            ConsoleDialog.options(a, "WEATHER LOCATION", arrayOf("USE DEVICE LOCATION", "SEARCH FOR A CITY")) { w ->
                if (w == 0) useDevice() else searchCity()
            },
        )
    }

    private fun hasLocationPerm(): Boolean =
        a.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun useDevice() {
        p.put("wxMode", Prefs.WX_DEVICE)
        if (!hasLocationPerm()) {
            a.requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), MainActivity.REQ_LOCATION)
            return
        }
        locate()
    }

    override fun onPermissionResult(requestCode: Int, granted: Boolean) {
        if (requestCode != MainActivity.REQ_LOCATION) return
        if (granted) {
            locate()
        } else {
            p.put("wxMode", Prefs.WX_NONE)
            a.toast("LOCATION DENIED • YOU CAN SEARCH FOR A CITY INSTEAD")
            render()
        }
    }

    /**
     * Uses the freshest last-known position at once (any age, so something shows), and asks for
     * a current fix when it is older than [FRESH_FIX_MS].
     */
    @SuppressLint("MissingPermission") // permission checked
    @Suppress("DEPRECATION") // requestSingleUpdate only below API 30
    private fun locate() {
        val lm = a.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
        if (lm == null || !hasLocationPerm()) return
        var best: Location? = null
        val providers =
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            } else {
                arrayOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            }
        for (pr in providers) {
            try {
                val l = lm.getLastKnownLocation(pr)
                if (l != null && (best == null || l.time > best.time)) best = l
            } catch (ignored: Exception) {
            }
        }
        if (best != null && System.currentTimeMillis() - best.time < FRESH_FIX_MS) {
            gotLocation(best)
            return
        }
        if (best != null) gotLocation(best)
        card!!.status.text = "ACQUIRING POSITION…"
        val provider =
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                LocationManager.NETWORK_PROVIDER
            } else if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                LocationManager.FUSED_PROVIDER
            } else {
                LocationManager.GPS_PROVIDER
            }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, a.mainExecutor) { l: Location? ->
                    if (!alive()) return@getCurrentLocation
                    if (l != null) {
                        gotLocation(l)
                    } else if (p.s("wxLat", null) == null) {
                        locationFailed()
                    }
                }
            } else {
                lm.requestSingleUpdate(
                    provider,
                    object : LocationListener {
                        override fun onLocationChanged(l: Location) {
                            if (alive()) gotLocation(l)
                        }

                        // Abstract before API 30: kept so older platforms find them.
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(s: String?, i: Int, b: Bundle?) {}

                        override fun onProviderEnabled(s: String) {}

                        override fun onProviderDisabled(s: String) {}
                    },
                    Looper.getMainLooper(),
                )
            }
        } catch (e: Exception) {
            if (best == null) locationFailed()
        }
    }

    private fun locationFailed() {
        Sfx.play(a, Sfx.ERROR)
        card!!.status.text = "POSITION UNAVAILABLE"
        a.toast("COULD NOT GET LOCATION • CHECK LOCATION IS ON, OR SEARCH FOR A CITY")
    }

    /** Stores a new position, then names it (reverse geocoding) and refreshes the forecast. */
    private fun gotLocation(l: Location) {
        if (!Weather.validCoordinates(l.latitude, l.longitude)) return
        p.put("wxLat", l.latitude.toString())
        p.put("wxLon", l.longitude.toString())
        p.put("wxTime", 0L)
        val fallback = String.format(Locale.US, "%.2f, %.2f", l.latitude, l.longitude)
        val app = a.applicationContext
        if (!Geocoder.isPresent()) {
            named(fallback)
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Geocoder(app, Locale.getDefault()).getFromLocation(
                    l.latitude,
                    l.longitude,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(r: MutableList<Address>) = namedFromUi(placeName(r, fallback))

                        override fun onError(message: String?) = namedFromUi(fallback)
                    },
                )
            } catch (e: RuntimeException) {
                named(fallback)
            }
            return
        }
        Weather.IO.execute {
            var r: List<Address>? = null
            try {
                r = legacyGeocode(app, l)
            } catch (ignored: IOException) {
                // no geocoder backend or no network: use the coordinates as the name
            } catch (ignored: RuntimeException) {
            }
            namedFromUi(placeName(r, fallback))
        }
    }

    private fun namedFromUi(name: String) = onUi { named(name) }

    private fun named(name: String) {
        p.put("wxName", name)
        refresh(true)
    }

    /** Prompts for a city name and offers the geocoder's matches. */
    private fun searchCity() {
        a.showing(
            ConsoleDialog.prompt(a, "SEARCH CITY", "", "e.g. NAMPA") { typed ->
                val q = typed.trim()
                if (q.isEmpty()) return@prompt
                card!!.status.text = "SEARCHING…"
                Weather.IO.execute {
                    var found: List<Weather.Place>? = null
                    try {
                        found = Weather.search(q)
                    } catch (ignored: Exception) {
                    }
                    val f = found
                    onUi { showResults(q, f) }
                }
            },
        )
    }

    private fun showResults(q: String, found: List<Weather.Place>?) {
        card!!.status.text = ""
        if (found == null) {
            Sfx.play(a, Sfx.ERROR)
            a.toast("SEARCH FAILED • CHECK YOUR CONNECTION")
            return
        }
        if (found.isEmpty()) {
            Sfx.play(a, Sfx.DENY)
            a.toast("NO LOCATION MATCHES “" + q.uppercase(Locale.getDefault()) + "”")
            return
        }
        val names = Array(found.size) { i -> found[i].name!!.uppercase(Locale.getDefault()) }
        a.showing(
            ConsoleDialog.options(a, "SELECT LOCATION", names) { w ->
                val pl = found[w]
                p.put("wxMode", Prefs.WX_CITY)
                p.put("wxLat", pl.lat.toString())
                p.put("wxLon", pl.lon.toString())
                p.put("wxName", pl.name)
                p.put("wxTime", 0L)
                Sfx.play(a, Sfx.CONFIRM)
                refresh(true)
            },
        )
    }

    // ---- fetch & render ------------------------------------------------------------------------

    /**
     * Fetches a new forecast when the cache is stale, the units changed or (force) the person
     * asked; otherwise just redraws. Never runs two fetches at once.
     */
    fun refresh(force: Boolean) {
        val mode = p.i("wxMode", Prefs.WX_NONE)
        val lat = p.s("wxLat", null)
        val lon = p.s("wxLon", null)
        val la = coord("wxLat")
        val lo = coord("wxLon")
        if (mode == Prefs.WX_NONE || lat == null || lon == null || !Weather.validCoordinates(la, lo)) {
            render()
            return
        }
        val age = System.currentTimeMillis() - p.l("wxTime", 0)
        val unitsChanged = p.b("wxCacheF", true) != p.fahrenheit()
        if (!force && age < MAX_AGE && !unitsChanged) {
            render()
            return
        }
        if (busy) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAttempt < RETRY_MS && p.s("wxCache", null) != null) {
            render()
            return
        }
        if (force && now - lastAttempt < MANUAL_DEBOUNCE_MS && age < MAX_AGE && !unitsChanged) return
        lastAttempt = now
        if (mode == Prefs.WX_DEVICE && hasLocationPerm() && age > MAX_AGE * 4) {
            // Re-acquire position occasionally (cheap: last-known first).
            locate()
        }
        busy = true
        val manual = force
        if (manual) Sfx.play(a, Sfx.SCAN_START)
        card!!.status.text = "UPDATING…"
        val f = p.fahrenheit()
        Weather.IO.execute {
            var json: String?
            try {
                json = Weather.get(Weather.forecastUrl(la, lo, f))
                Weather.parse(json, f)
            } catch (e: Exception) {
                json = null
            }
            val j = json
            a.runOnUiThread {
                if (!alive()) {
                    busy = false
                    return@runOnUiThread
                }
                busy = false
                if (lat != p.s("wxLat", null) || lon != p.s("wxLon", null) || f != p.fahrenheit()) {
                    refresh(true) // location or units changed mid-request
                    return@runOnUiThread
                }
                if (j != null) {
                    p.put("wxCache", j)
                    p.put("wxCacheF", f)
                    p.put("wxTime", System.currentTimeMillis())
                }
                render()
                if (j == null) card!!.status.text = "SUBSPACE LINK OFFLINE"
                if (manual) Sfx.play(a, if (j != null) Sfx.SCAN_DONE else Sfx.ERROR)
            }
        }
    }

    private fun deg(v: Double): String = Weather.degrees(v)

    /** Rebuilds the panel body from the cached forecast (or the set-location prompt). */
    private fun render() {
        val card = card!!
        val b = card.body
        b.removeAllViews()
        val mode = p.i("wxMode", Prefs.WX_NONE)
        if (mode == Prefs.WX_NONE) {
            val t = Ui.text(
                a,
                "NO LOCATION SET. Weather uses Open-Meteo (free, no account). " +
                    "Choose your device location or search for a city.",
                15f,
                pal.textHi,
            )
            t.setLineSpacing(0f, 1.1f)
            b.addView(t)
            val btn = Ui.button(a, "SET LOCATION", accent, Ui.PILL) { chooseLocation() }
            // At least 52dp but free to grow, and the label shrinks rather than clipping, so the
            // control isn't squeezed in short or narrow landscape cards (NEXT CONSOLE).
            btn.minHeight = Ui.dp(a, 52f)
            Ui.fitText(btn, false)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.topMargin = Ui.dp(a, Ui.M.toFloat())
            b.addView(btn, lp)
            card.status.text = "OFFLINE"
            return
        }
        val cache = p.s("wxCache", null)
        var d: Weather.Data? = null
        if (cache != null) {
            try {
                d = Weather.parse(cache, p.b("wxCacheF", true))
            } catch (ignored: Exception) {
            }
        }
        val place = (p.s("wxName", "") ?: "").uppercase(Locale.getDefault())
        if (d == null) {
            b.addView(Ui.text(a, if (busy) "CONTACTING WEATHER SERVICE…" else "NO DATA YET • TAP TO RETRY", 15f, pal.textHi))
            card.status.text = place
            return
        }
        val unit = if (d.fahrenheit) "F" else "C"
        val row = LinearLayout(a)
        row.gravity = Gravity.CENTER_VERTICAL
        val icon = WxIcon(a, Weather.kind(d.code), d.day, pal)
        val iconPx = max(Ui.dp(a, ICON_DP), metrics.scaledPx(ICON_DP))
        row.addView(icon, LinearLayout.LayoutParams(iconPx, iconPx))
        val temp = Ui.bold(a, deg(d.temp) + unit, 46f, pal.textHi)
        temp.setPadding(Ui.dp(a, Ui.M.toFloat()), 0, Ui.dp(a, Ui.M.toFloat()), 0)
        row.addView(temp)
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.oneLine(Ui.bold(a, Weather.describe(d.code), 17f, accent)))
        val det = Ui.text(
            a,
            "FEELS " + deg(d.feels) + "  •  WIND " + Math.round(d.wind) + Weather.windUnit(d.fahrenheit) +
                (if (d.humidity >= 0) "\nHUMIDITY " + d.humidity + "%" else ""),
            13f,
            pal.textLo,
        )
        det.setPadding(0, Ui.dp(a, Ui.XS.toFloat()), 0, 0)
        col.addView(det)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        b.addView(row)

        val dayIcon = max(Ui.dp(a, DAY_ICON_DP), metrics.scaledPx(DAY_ICON_DP))
        var i = 0
        while (i < d.days.size && i < Weather.FORECAST_DAYS) {
            val day = d.days[i]
            val name = Weather.dayName(day.date, i, Locale.getDefault())
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, Ui.dp(a, Ui.XS.toFloat()), 0, 0)
            r.addView(
                Ui.bold(a, name, 15f, pal.c(i + 1)),
                LinearLayout.LayoutParams(max(Ui.dp(a, DAY_COLUMN_DP), metrics.scaledPx(DAY_COLUMN_DP)), -2),
            )
            r.addView(WxIcon(a, Weather.kind(day.code), true, pal), LinearLayout.LayoutParams(dayIcon, dayIcon))
            val desc = Ui.oneLine(Ui.text(a, "  " + Weather.describe(day.code), 14f, pal.textHi))
            r.addView(desc, LinearLayout.LayoutParams(0, -2, 1f))
            val pop = if (day.pop >= 0) day.pop.toString() + "%  " else ""
            r.addView(Ui.text(a, pop, 13f, pal.c(2)))
            r.addView(Ui.bold(a, deg(day.hi) + " / " + deg(day.lo), 15f, pal.textHi))
            b.addView(r)
            i++
        }
        val t = p.l("wxTime", 0)
        val time =
            if (t > 0) {
                SimpleDateFormat(if (a.use24h()) "HH:mm" else "h:mm a", Locale.getDefault()).format(Date(t)).uppercase(Locale.getDefault())
            } else {
                ""
            }
        card.status.text = if (busy) "UPDATING…" else place + (if (time.isEmpty()) "" else " • $time")
    }

    /** Simple drawn console-palette weather pictograms (sun/moon, cloud, rain, snow, storm, fog), sized to the view. */
    class WxIcon(
        c: Context,
        private val kind: Int,
        private val day: Boolean,
        private val pal: Palette,
    ) : View(c) {
        private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val r = RectF()

        override fun onDraw(cv: Canvas) {
            val s = min(width, height).toFloat()
            val ox = (width - s) / 2f
            val oy = (height - s) / 2f
            cv.save()
            cv.translate(ox, oy)
            val cloud = kind != Weather.K_CLEAR
            if (kind == Weather.K_CLEAR || kind == Weather.K_PARTLY) {
                val cx = if (kind == Weather.K_CLEAR) s * 0.5f else s * 0.64f
                val cy = if (kind == Weather.K_CLEAR) s * 0.5f else s * 0.36f
                val rad = if (kind == Weather.K_CLEAR) s * 0.22f else s * 0.17f
                pt.color = pal.c(4)
                if (day) {
                    cv.drawCircle(cx, cy, rad, pt)
                    pt.strokeWidth = s * 0.06f
                    pt.strokeCap = Paint.Cap.ROUND
                    for (i in 0 until 8) {
                        val ang = i * Math.PI / 4
                        cv.drawLine(
                            cx + cos(ang).toFloat() * rad * 1.45f,
                            cy + sin(ang).toFloat() * rad * 1.45f,
                            cx + cos(ang).toFloat() * rad * 1.9f,
                            cy + sin(ang).toFloat() * rad * 1.9f,
                            pt,
                        )
                    }
                } else {
                    pt.color = pal.c(1)
                    cv.drawCircle(cx, cy, rad * 1.2f, pt)
                    pt.color = Skin.bg()
                    cv.drawCircle(cx + rad * 0.55f, cy - rad * 0.35f, rad * 1.0f, pt)
                }
            }
            if (cloud) {
                val top = if (kind == Weather.K_PARTLY) s * 0.42f else s * 0.22f
                pt.color = if (kind == Weather.K_STORM) Ui.darken(pal.c(1), 0.75f) else pal.c(1)
                val h = s * 0.32f
                cv.drawCircle(s * 0.36f, top + h * 0.55f, h * 0.45f, pt)
                cv.drawCircle(s * 0.58f, top + h * 0.38f, h * 0.6f, pt)
                r.set(s * 0.14f, top + h * 0.5f, s * 0.86f, top + h * 1.05f)
                cv.drawRoundRect(r, h * 0.28f, h * 0.28f, pt)
                val base = top + h * 1.2f
                pt.strokeCap = Paint.Cap.ROUND
                pt.strokeWidth = s * 0.06f
                when (kind) {
                    Weather.K_RAIN -> {
                        pt.color = pal.c(2)
                        for (i in 0 until 3) cv.drawLine(s * (0.3f + i * 0.2f), base + s * 0.04f, s * (0.25f + i * 0.2f), base + s * 0.2f, pt)
                    }
                    Weather.K_SNOW -> {
                        pt.color = -0x1 // 0xFFFFFFFF
                        for (i in 0 until 3) cv.drawCircle(s * (0.3f + i * 0.2f), base + s * 0.12f, s * 0.045f, pt)
                    }
                    Weather.K_STORM -> {
                        pt.color = pal.c(4)
                        path.reset()
                        path.moveTo(s * 0.52f, base - s * 0.02f)
                        path.lineTo(s * 0.38f, base + s * 0.16f)
                        path.lineTo(s * 0.5f, base + s * 0.16f)
                        path.lineTo(s * 0.42f, base + s * 0.32f)
                        path.lineTo(s * 0.64f, base + s * 0.1f)
                        path.lineTo(s * 0.52f, base + s * 0.1f)
                        path.close()
                        cv.drawPath(path, pt)
                    }
                    Weather.K_FOG -> {
                        pt.color = pal.c(2)
                        for (i in 0 until 2) cv.drawLine(s * 0.18f, base + s * (0.06f + i * 0.12f), s * 0.82f, base + s * (0.06f + i * 0.12f), pt)
                    }
                }
            }
            cv.restore()
        }
    }

    private companion object {
        /** A cached forecast younger than this is shown without refetching. */
        const val MAX_AGE = 30 * 60 * 1000L

        /** Automatic retries after a failure wait at least this long. */
        const val RETRY_MS = 5 * 60 * 1000L

        /** A manual refresh within this time of the last one is ignored (unless data is stale). */
        const val MANUAL_DEBOUNCE_MS = 15_000L

        /** A last-known position younger than this is used without asking for a new fix. */
        const val FRESH_FIX_MS = 3 * 3600 * 1000L

        /** Icon sizes and the day-name column width, in dp at the design scale. */
        const val ICON_DP = 64f
        const val DAY_ICON_DP = 22f
        const val DAY_COLUMN_DP = 58f

        @Suppress("DEPRECATION") // the blocking form is the only one before API 33
        @Throws(IOException::class)
        fun legacyGeocode(c: Context, l: Location): List<Address>? =
            Geocoder(c, Locale.getDefault()).getFromLocation(l.latitude, l.longitude, 1)

        /** "Locality, Region" of the first address, or the fallback. */
        fun placeName(r: List<Address>?, fallback: String): String {
            if (r.isNullOrEmpty()) return fallback
            val ad = r[0]
            var name: String? = ad.locality ?: ad.subAdminArea
            if (name != null && ad.adminArea != null) name += ", " + ad.adminArea
            return name ?: fallback
        }
    }
}
