package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * console environmental control for a Nest thermostat: ambient reading, HVAC status, the target
 * temperature(s) with ▲/▼ and a draggable segmented scale, mode buttons (HEAT / COOL / HEAT•COOL /
 * OFF) and ECO. Changes are sent about a second after the last adjustment, then confirmed.
 *
 * Opened from a home thermostat tile or CONFIG › DEVICES. The thermostat behind it is a
 * [Backend]: a Nest device ([NestBackend]) or a Home Assistant climate entity
 * ([HomeAssistant.Climate]). Network work goes through [Nest.async] (one background
 * thread) with the application context; replies are ignored once the dialog is closed.
 * Responsive: the scale's height follows the window height so the dialog fits landscape phones.
 * Setpoint snapping and the HEAT•COOL gap are pure functions ([snap], [keepGap]).
 */
internal class ThermostatDialog private constructor(
    private val c: Context,
    private val device: Backend,
) {
    /**
     * The thermostat a dialog controls. Every method blocks (it is called on a background thread)
     * and works in °C and the console mode names HEAT / COOL / HEATCOOL / OFF.
     */
    interface Backend {
        /** Reads the current state. */
        @Throws(IOException::class)
        fun read(app: Context): Nest.State

        /** Switches the HVAC mode. */
        @Throws(IOException::class)
        fun setMode(app: Context, mode: String)

        /** Engages or leaves ECO. */
        @Throws(IOException::class)
        fun setEco(app: Context, on: Boolean)

        /** Sends the setpoint(s) that match `mode`. */
        @Throws(IOException::class)
        fun setTemps(app: Context, mode: String, heatC: Double, coolC: Double)
    }

    /** A Google Nest thermostat by SDM device name. */
    class NestBackend(private val device: String?) : Backend {
        override fun read(app: Context): Nest.State = Nest.state(app, device)

        override fun setMode(app: Context, mode: String) = Nest.setMode(app, device, mode)

        override fun setEco(app: Context, on: Boolean) = Nest.setEco(app, device, on)

        override fun setTemps(app: Context, mode: String, heatC: Double, coolC: Double) =
            Nest.setTemps(app, device, mode, heatC, coolC)
    }

    /** Application context for background jobs (never the Activity). */
    private val app: Context = c.applicationContext
    private val pal: Palette = Prefs.get(c).palette()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var built: ConsoleDialog.Built

    private var st: Nest.State? = null
    private var heatC = Double.NaN
    private var coolC = Double.NaN
    private var editingCool = false // HEAT•COOL: which end ▲/▼ and the scale move
    private lateinit var ambientView: TextView
    private lateinit var statusView: TextView
    private lateinit var line: TextView
    private var heatNum: TextView? = null
    private var coolNum: TextView? = null
    private var heatLbl: TextView? = null
    private var coolLbl: TextView? = null
    private lateinit var setpoints: LinearLayout
    private lateinit var modes: LinearLayout
    private lateinit var scale: Scale
    private var pending = false // an adjustment is waiting to be sent
    private val send = Runnable { pushTemps() }

    private fun open(label: String?) {
        built = ConsoleDialog.base(c, "ENVIRONMENTAL CONTROL")
        val b = built.body
        val name = Ui.oneLine(Ui.bold(c, label, 16f, pal.c(1)))
        name.gravity = Gravity.END
        b.addView(name)

        ambientView = Ui.bold(c, "AMBIENT —", 30f, pal.textHi)
        b.addView(ambientView)
        statusView = Ui.text(c, "CONTACTING THERMOSTAT…", 14f, pal.c(2))
        b.addView(statusView)

        val body = LinearLayout(c)
        body.setPadding(0, Ui.dp(c, Ui.M.toFloat()), 0, Ui.dp(c, Ui.GUTTER.toFloat()))
        setpoints = LinearLayout(c)
        setpoints.orientation = LinearLayout.VERTICAL
        body.addView(setpoints, LinearLayout.LayoutParams(0, -2, 1f))
        scale = Scale(c)
        val sl = LinearLayout.LayoutParams(Ui.dp(c, SCALE_W_DP), scaleHeightPx())
        sl.leftMargin = Ui.dp(c, Ui.M.toFloat())
        body.addView(scale, sl)
        b.addView(body)

        modes = LinearLayout(c)
        modes.orientation = LinearLayout.VERTICAL
        b.addView(modes)

        line = Ui.text(c, "", 13f, pal.textLo)
        line.setPadding(0, Ui.dp(c, Ui.S.toFloat()), 0, Ui.dp(c, Ui.GUTTER.toFloat()))
        b.addView(line)
        val close = ConsoleDialog.option(c, "CLOSE CHANNEL", pal.c(2)) { finish() }
        ConsoleDialog.addOption(built, close)
        built.dialog.setOnDismissListener {
            if (pending) pushTemps() // don't lose a pending change
            main.removeCallbacksAndMessages(null)
        }
        built.dialog.show()
        Sfx.play(c, Sfx.OPEN)
        refresh(false)
    }

    /** Scale height: up to [SCALE_MAX_H_DP], but no more than a share of the window height. */
    private fun scaleHeightPx(): Int {
        val m = ScreenMetrics.of(c)
        val dp = max(SCALE_MIN_H_DP, min(SCALE_MAX_H_DP, m.heightDp * SCALE_HEIGHT_SHARE))
        return m.px(dp)
    }

    private fun finish() {
        Sfx.play(c, Sfx.CLOSE)
        built.dialog.dismiss()
    }

    // ---- data ---------------------------------------------------------------------------------------

    /** Reads the thermostat; quiet suppresses the error sound (automatic read-backs). */
    private fun refresh(quiet: Boolean) {
        Nest.async({ device.read(app) }) { s, error ->
            if (!built.dialog.isShowing) return@async
            if (error != null) {
                statusView.text = "SENSOR LINK FAILED"
                statusView.setTextColor(pal.alert)
                line.text = error
                if (!quiet) Sfx.play(c, Sfx.ERROR)
                return@async
            }
            st = s
            if (!pending) {
                heatC = s!!.heatC
                coolC = s.coolC
            }
            render()
        }
    }

    private fun f(): Boolean = st?.fahrenheit() ?: true

    private fun fmt(cel: Double): String {
        if (cel.isNaN()) return "—"
        return if (f()) Math.round(Nest.toF(cel)).toString() else String.format(Locale.US, "%.1f", Math.round(cel * 2) / 2.0)
    }

    private fun unit(): String = if (f()) "°F" else "°C"

    /** Setpoints can change only online, out of ECO, in HEAT / COOL / HEAT•COOL. */
    private fun adjustable(): Boolean {
        val s = st ?: return false
        return s.online() && !s.ecoOn() && ("HEAT" == s.mode || "COOL" == s.mode || "HEATCOOL" == s.mode)
    }

    /** Rebuilds readings, setpoint boxes and mode keys from the last state. */
    private fun render() {
        val st = st!!
        ambientView.text = "AMBIENT " + fmt(st.ambientC) + unit()
        val hv = if ("HEATING" == st.hvac) "HEATING" else if ("COOLING" == st.hvac) "COOLING" else "IDLE"
        var s = StringBuilder(hv)
        if (st.humidity >= 0) s.append("  •  HUMIDITY ").append(st.humidity).append('%')
        if (!st.online()) s = StringBuilder("THERMOSTAT OFFLINE")
        statusView.text = s.toString()
        statusView.setTextColor(if ("HEATING" == hv) pal.c(0) else if ("COOLING" == hv) pal.c(2) else pal.textLo)

        setpoints.removeAllViews()
        heatNum = null
        coolNum = null
        if (st.ecoOn()) {
            setpoints.addView(Ui.bold(c, "ECO MODE", 26f, pal.c(4)))
            setpoints.addView(
                Ui.text(
                    c,
                    "HOLDING " + fmt(st.ecoHeatC) + "–" + fmt(st.ecoCoolC) + unit() + "\nTURN ECO OFF TO SET A TEMPERATURE",
                    14f,
                    pal.textLo,
                ),
            )
        } else if ("OFF" == st.mode) {
            setpoints.addView(Ui.bold(c, "SYSTEM OFF", 28f, pal.textLo))
            setpoints.addView(Ui.text(c, "CHOOSE HEAT OR COOL BELOW", 14f, pal.textLo))
        } else {
            val range = "HEATCOOL" == st.mode
            if ("HEAT" == st.mode || range) addSetpoint(false, range)
            if ("COOL" == st.mode || range) addSetpoint(true, range)
            if (!range) editingCool = "COOL" == st.mode
        }
        renderModes()
        scale.invalidate()
        if (line.text.isEmpty() || line.currentTextColor == pal.alert) {
            line.setTextColor(pal.textLo)
            line.text = if (adjustable()) "TAP ▲ ▼ OR DRAG THE SCALE • CHANGES SEND AUTOMATICALLY" else ""
        }
    }

    /** A setpoint box: label, value, ▲/▼; in HEAT•COOL tapping it selects which end the scale moves. */
    private fun addSetpoint(cool: Boolean, range: Boolean) {
        val color = if (cool) pal.c(2) else pal.c(0)
        val selected = !range || editingCool == cool
        val box = LinearLayout(c)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(c, Ui.M.toFloat()), Ui.dp(c, Ui.XS.toFloat()), Ui.dp(c, Ui.M.toFloat()), Ui.dp(c, Ui.S.toFloat()))
        box.background = Ui.outline(c, pal.panel, Ui.alpha(color, if (selected) 1f else 0.35f), 16f, if (selected) 2.5f else 1.2f)
        val lbl = Ui.bold(c, if (cool) "COOL TO" else "HEAT TO", 15f, color)
        val num = Ui.bold(c, fmt(if (cool) coolC else heatC) + unit(), if (range) 40f else 58f, pal.textHi)
        box.addView(lbl)
        box.addView(num)
        val arrows = LinearLayout(c)
        val up = Ui.button(c, "▲", color, Ui.PILL) {
            editingCool = cool
            step(+1)
        }
        val down = Ui.button(c, "▼", color, Ui.PILL) {
            editingCool = cool
            step(-1)
        }
        up.gravity = Gravity.CENTER
        down.gravity = Gravity.CENTER
        up.setTextSize(20 * Skin.textScale())
        down.setTextSize(20 * Skin.textScale())
        up.contentDescription = (if (cool) "Cooling" else "Heating") + " setpoint up"
        down.contentDescription = (if (cool) "Cooling" else "Heating") + " setpoint down"
        arrows.addView(up, LinearLayout.LayoutParams(0, Ui.dp(c, 46f), 1f))
        val dl = LinearLayout.LayoutParams(0, Ui.dp(c, 46f), 1f)
        dl.leftMargin = Ui.dp(c, Ui.GUTTER.toFloat())
        arrows.addView(down, dl)
        box.addView(arrows)
        box.setOnClickListener { v ->
            editingCool = cool
            Ui.tap(v)
            render()
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = Ui.dp(c, Ui.S.toFloat())
        setpoints.addView(box, lp)
        if (cool) {
            coolNum = num
            coolLbl = lbl
        } else {
            heatNum = num
            heatLbl = lbl
        }
    }

    /** Mode keys the thermostat supports, plus ECO when available. */
    private fun renderModes() {
        val st = st!!
        modes.removeAllViews()
        val row = LinearLayout(c)
        val ids = arrayOf("HEAT", "COOL", "HEATCOOL", "OFF")
        val names = arrayOf("HEAT", "COOL", "HEAT•COOL", "OFF")
        val colors = intArrayOf(pal.c(0), pal.c(2), pal.c(5), pal.c(3))
        for (i in ids.indices) {
            val m = ids[i]
            if (st.modes.isNotEmpty() && !st.modes.contains(m)) continue
            val on = m == st.mode && !st.ecoOn()
            val b = Ui.button(c, names[i], if (on) colors[i] else Ui.alpha(colors[i], 0.35f), Ui.PILL) { changeMode(m) }
            b.gravity = Gravity.CENTER
            b.setTextSize(14 * Skin.textScale())
            val lp = LinearLayout.LayoutParams(0, Ui.dp(c, 44f), 1f)
            if (row.childCount > 0) lp.leftMargin = Ui.dp(c, Ui.GUTTER.toFloat())
            row.addView(b, lp)
        }
        modes.addView(row)
        if (st.ecoAvailable) {
            val eco = Ui.button(
                c,
                if (st.ecoOn()) "ECO • ON" else "ECO • OFF",
                if (st.ecoOn()) pal.c(4) else Ui.alpha(pal.c(4), 0.35f),
                Ui.PILL,
            ) { toggleEco() }
            eco.gravity = Gravity.CENTER
            eco.setTextSize(14 * Skin.textScale())
            val lp = LinearLayout.LayoutParams(-1, Ui.dp(c, 44f))
            lp.topMargin = Ui.dp(c, Ui.GUTTER.toFloat())
            modes.addView(eco, lp)
        }
    }

    // ---- adjusting ------------------------------------------------------------------------------------

    /** One ▲/▼ step: 1 °F, or 0.5 °C. */
    private fun stepC(): Double = if (f()) 5.0 / 9.0 else 0.5

    private fun step(dir: Int) {
        if (!adjustable()) {
            Sfx.play(c, Sfx.DENY)
            return
        }
        setTarget((if (editingCool) coolC else heatC) + dir * stepC())
        Sfx.play(c, Sfx.TAP)
    }

    /** Snaps to whole °F / half °C, clamps, keeps the HEAT•COOL gap, and schedules the send. */
    private fun setTarget(target: Double) {
        val st = st!!
        var v = target
        if (v.isNaN()) v = if (st.ambientC.isNaN()) 20.0 else st.ambientC
        v = snap(v, f())
        val range = "HEATCOOL" == st.mode
        if (editingCool) {
            coolC = if (range) keepGap(heatC, v, true) else v
        } else {
            heatC = if (range) keepGap(v, coolC, false) else v
        }
        heatNum?.text = fmt(heatC) + unit()
        coolNum?.text = fmt(coolC) + unit()
        scale.invalidate()
        line.setTextColor(pal.c(4))
        line.text = "ADJUSTING…"
        main.removeCallbacks(send)
        pending = true
        main.postDelayed(send, SEND_DELAY_MS)
    }

    /** Sends the pending setpoint(s) for the current mode. */
    private fun pushTemps() {
        main.removeCallbacks(send)
        pending = false
        val st = st ?: return
        val mode = st.mode
        val h = heatC
        val k = coolC
        line.setTextColor(pal.c(4))
        line.text = "TRANSMITTING…"
        Nest.async({
            device.setTemps(app, mode, h, k)
            true
        }) { _, error -> afterCommand(error, "SETPOINT CONFIRMED") }
    }

    /** Switches mode (leaving ECO first when needed). */
    private fun changeMode(m: String) {
        val st = st ?: return
        if (m == st.mode && !st.ecoOn()) {
            Ui.tap(scale)
            return
        }
        Sfx.play(c, Sfx.TAP)
        line.setTextColor(pal.c(4))
        line.text = "TRANSMITTING $m…"
        val leaveEco = st.ecoOn()
        Nest.async({
            if (leaveEco) device.setEco(app, false)
            device.setMode(app, m)
            true
        }) { _, error -> afterCommand(error, (if ("HEATCOOL" == m) "HEAT•COOL" else m) + " MODE CONFIRMED") }
    }

    private fun toggleEco() {
        val st = st ?: return
        Sfx.play(c, Sfx.TAP)
        val on = !st.ecoOn()
        line.setTextColor(pal.c(4))
        line.text = "TRANSMITTING…"
        Nest.async({
            device.setEco(app, on)
            true
        }) { _, error -> afterCommand(error, if (on) "ECO ENGAGED" else "ECO DISENGAGED") }
    }

    /** Reports a command result and schedules a read-back of what the thermostat now holds. */
    private fun afterCommand(error: String?, okText: String) {
        if (!built.dialog.isShowing) {
            if (error == null) Sfx.play(c, Sfx.CONFIRM)
            return
        }
        if (error != null) {
            Sfx.play(c, Sfx.ERROR)
            line.setTextColor(pal.alert)
            line.text = "UNABLE TO COMPLY • $error"
        } else {
            Sfx.play(c, Sfx.CONFIRM)
            line.setTextColor(pal.c(2))
            line.text = okText
        }
        // read back what the thermostat actually holds
        main.postDelayed({ if (built.dialog.isShowing) refresh(true) }, READBACK_MS)
    }

    // ---- the console scale ------------------------------------------------------------------------------

    /** Vertical segmented scale (warm at the top): ambient marker, setpoint band; drag to adjust. */
    private inner class Scale(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        init {
            p.typeface = Ui.font(ctx, true)
            contentDescription = "Temperature scale. Drag to adjust."
        }

        private fun yFor(cel: Double, top: Float, h: Float): Float {
            val t = (cel - MIN_C) / (MAX_C - MIN_C)
            return (top + h - max(0.0, min(1.0, t)) * h).toFloat()
        }

        private fun celFor(y: Float, top: Float, h: Float): Double {
            val t = (1 - (y - top) / h).toDouble()
            return MIN_C + max(0.0, min(1.0, t)) * (MAX_C - MIN_C)
        }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val top = dp(14f)
            val bot = h - dp(14f)
            val sh = bot - top
            val barW = w * 0.46f
            val gap = dp(Ui.XS.toFloat())
            val segH = (sh - gap * (SEGMENTS - 1)) / SEGMENTS
            val active = adjustable()
            val st = st
            for (i in 0 until SEGMENTS) {
                val y0 = top + i * (segH + gap)
                val cel = celFor(y0 + segH / 2, top, sh)
                var lit = false
                if (st != null && "HEATCOOL" == st.mode) {
                    lit = !heatC.isNaN() && !coolC.isNaN() && cel >= heatC && cel <= coolC
                } else if (st != null && "HEAT" == st.mode) {
                    lit = !heatC.isNaN() && cel <= heatC
                } else if (st != null && "COOL" == st.mode) {
                    lit = !coolC.isNaN() && cel >= coolC
                }
                val col = if (cel > 22) pal.c(0) else if (cel > 18) pal.c(4) else pal.c(2)
                p.color = if (lit && active) col else Ui.alpha(col, 0.22f)
                r.set(0f, y0, barW, y0 + segH)
                canvas(cv, r, if (i == 0) 1 else if (i == SEGMENTS - 1) 2 else 0)
            }
            p.textSize = dp(12f) * Skin.textScale()
            p.color = pal.textLo
            for (cel in doubleArrayOf(MAX_C, (MIN_C + MAX_C) / 2, MIN_C)) {
                cv.drawText(fmt(cel) + "°", barW + dp(6f), yFor(cel, top, sh) + dp(4f), p)
            }
            if (st != null && !st.ambientC.isNaN()) {
                val y = yFor(st.ambientC, top, sh)
                p.color = pal.textHi
                p.strokeWidth = dp(3f)
                cv.drawLine(-dp(2f), y, barW + dp(2f), y, p)
                p.textSize = dp(11f) * Skin.textScale()
                cv.drawText("NOW", barW + dp(6f), y - dp(6f), p)
            }
            if (active) {
                if (!heatC.isNaN() && "COOL" != st!!.mode) marker(cv, yFor(heatC, top, sh), barW, pal.c(0))
                if (!coolC.isNaN() && "HEAT" != st!!.mode) marker(cv, yFor(coolC, top, sh), barW, pal.c(2))
            }
        }

        private fun canvas(cv: Canvas, rect: RectF, cap: Int) {
            val rad = rect.width() / 2
            if (cap == 0) {
                cv.drawRect(rect, p)
                return
            }
            cv.drawRoundRect(rect, min(rad, rect.height()), min(rad, rect.height()), p)
        }

        private fun marker(cv: Canvas, y: Float, barW: Float, color: Int) {
            p.color = color
            r.set(barW + dp(2f), y - dp(9f), width.toFloat(), y + dp(9f))
            cv.drawRoundRect(r, dp(9f), dp(9f), p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!adjustable()) {
                if (e.action == MotionEvent.ACTION_UP) Sfx.play(c, Sfx.DENY)
                return true
            }
            parent.requestDisallowInterceptTouchEvent(true)
            val top = dp(14f)
            val sh = height - dp(28f)
            val cel = celFor(e.y, top, sh)
            if (e.action == MotionEvent.ACTION_DOWN && "HEATCOOL" == st!!.mode) {
                editingCool = abs(cel - coolC) < abs(cel - heatC)
            }
            val before = if (editingCool) coolC else heatC
            setTarget(cel)
            val after = if (editingCool) coolC else heatC
            if (abs(after - before) > 0.01 || e.action == MotionEvent.ACTION_DOWN) Sfx.play(c, Sfx.TAP_SOFT)
            if (e.action == MotionEvent.ACTION_UP) render()
            return true
        }

        private fun dp(v: Float): Float = v * resources.displayMetrics.density
    }

    companion object {
        /** Setpoint limits and the minimum HEAT•COOL gap, in °C (Nest's own limits). */
        const val MIN_C = 9.0
        const val MAX_C = 32.0
        const val GAP_C = 1.7

        /** Changes are sent this long after the last adjustment. */
        private const val SEND_DELAY_MS = 1100L

        /** After a command, read the thermostat back after this long. */
        private const val READBACK_MS = 1500L

        /** Scale width and its tallest height, in dp; the shortest it may shrink to. */
        private const val SCALE_W_DP = 92f
        private const val SCALE_MAX_H_DP = 290f
        private const val SCALE_MIN_H_DP = 150f

        /** Share of the window height the scale may take. */
        private const val SCALE_HEIGHT_SHARE = 0.42f

        private const val SEGMENTS = 24

        /** Opens the control for an SDM device name, titled with its label. */
        @JvmStatic
        fun show(c: Context, device: String?, label: String?) {
            ThermostatDialog(c, NestBackend(device)).open(label)
        }

        /** Opens the control for any thermostat backend (e.g. a Home Assistant climate entity). */
        @JvmStatic
        fun show(c: Context, backend: Backend, label: String?) {
            ThermostatDialog(c, backend).open(label)
        }

        /** Snaps a °C setpoint to whole °F (fahrenheit) or half °C, clamped to [MIN_C]..[MAX_C]. */
        @JvmStatic
        fun snap(celsius: Double, fahrenheit: Boolean): Double {
            val v = if (fahrenheit) Nest.toC(Math.round(Nest.toF(celsius)).toDouble()) else Math.round(celsius * 2) / 2.0
            return max(MIN_C, min(MAX_C, v))
        }

        /**
         * Keeps HEAT•COOL setpoints at least [GAP_C] apart by moving the end being edited.
         *
         * @param movingCool true when the cool end was changed (returns the new cool value), false
         *                   for the heat end (returns the new heat value)
         */
        @JvmStatic
        fun keepGap(heatC: Double, coolC: Double, movingCool: Boolean): Double {
            if (movingCool) return if (!heatC.isNaN() && coolC - heatC < GAP_C) heatC + GAP_C else coolC
            return if (!coolC.isNaN() && coolC - heatC < GAP_C) coolC - GAP_C else heatC
        }
    }
}
