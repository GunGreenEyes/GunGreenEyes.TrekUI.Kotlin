package com.gungreeneyes.trekui

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

/**
 * Home panel "POWER SYSTEMS": battery percentage with a segmented bar (pulsing while charging),
 * charging state and source, temperature, voltage, health and time-to-full. Fed by MainActivity's
 * battery broadcast ([onBattery]); tapping opens the system battery usage screen.
 *
 * The wording is built by pure functions ([stateText], [detailText])
 * unit-tested in `PowerPanelTest`.
 */
internal class PowerPanel(a: MainActivity, accent: Int) : Panel(a, accent) {
    private lateinit var pct: TextView
    private lateinit var state: TextView
    private lateinit var detail: TextView
    private lateinit var bar: SegBar
    private var charging = false
    private var resumed = false

    override fun id(): String = Prefs.W_POWER

    override fun title(): String = "POWER SYSTEMS"

    override fun build() {
        val card = card!!
        val row = LinearLayout(a)
        row.gravity = Gravity.CENTER_VERTICAL
        pct = Ui.bold(a, "--%", 50f, pal.textHi)
        row.addView(pct)
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(a, Ui.L.toFloat()), 0, 0, 0)
        state = Ui.oneLine(Ui.bold(a, "READING…", 18f, accent))
        detail = Ui.text(a, "", 13f, pal.textLo)
        detail.setPadding(0, Ui.dp(a, Ui.XS.toFloat()), 0, 0)
        col.addView(state)
        col.addView(detail)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        card.body.addView(row)
        bar = SegBar(a, 20, accent, Ui.alpha(accent, 0.16f))
        val bl = LinearLayout.LayoutParams(-1, Ui.dp(a, 14f))
        bl.topMargin = Ui.dp(a, Ui.S.toFloat())
        card.body.addView(bar, bl)
        card.body.setOnClickListener(Ui.feedback { v -> a.startSafe(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), v) })
        card.status.text = "TAP FOR USAGE"
    }

    override fun resume() {
        resumed = true
        bar.setPulse(charging)
    }

    override fun pause() {
        resumed = false
        bar.setPulse(false)
    }

    override fun onBattery(battery: Intent?) {
        val b = battery ?: return
        val level = MainActivity.batteryPct(b)
        val status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val volt = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val health = b.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING

        pct.text = if (level < 0) "--%" else "$level%"
        val low = isLow(level, plugged)
        pct.setTextColor(if (low) pal.alert else pal.textHi)
        state.text = stateText(level, status, plugged)
        state.setTextColor(if (low) pal.alert else accent)

        val d = StringBuilder(detailText(temp, volt, health))
        if (charging && Build.VERSION.SDK_INT >= 28) {
            val bm = a.getSystemService(Context.BATTERY_SERVICE) as BatteryManager?
            val ms = bm?.computeChargeTimeRemaining() ?: -1L
            if (ms > 0) {
                val m = ms / 60000
                d.append(String.format(Locale.US, "\nFULL IN %d:%02d", m / 60, m % 60))
            }
        }
        detail.text = d.toString()
        bar.set(if (level < 0) 0f else level / 100f, if (low) pal.alert else accent)
        if (resumed) bar.setPulse(charging)
    }

    companion object {
        /** At or below this level, unplugged, the panel shows a low-power warning. */
        const val LOW_LEVEL = 15

        /** BatteryManager.BATTERY_PLUGGED_DOCK (API 33); defined here so older SDK levels compile the same. */
        const val PLUGGED_DOCK = 8

        /** Low-power warning: known level at or below [LOW_LEVEL] and not plugged in. */
        fun isLow(level: Int, plugged: Int): Boolean = level in 0..LOW_LEVEL && plugged == 0

        /** Power source for an EXTRA_PLUGGED value ("" when unplugged or unknown). */
        fun sourceName(plugged: Int): String =
            when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC MAINS"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "INDUCTION (WIRELESS)"
                PLUGGED_DOCK -> "DOCK"
                else -> ""
            }

        /** The state line: FULLY CHARGED, CHARGING • source, CONNECTED • source, LOW POWER WARNING or ON INTERNAL POWER. */
        fun stateText(level: Int, status: Int, plugged: Int): String {
            val src = sourceName(plugged)
            if (status == BatteryManager.BATTERY_STATUS_FULL || (plugged != 0 && level >= 100)) return "FULLY CHARGED"
            if (status == BatteryManager.BATTERY_STATUS_CHARGING) return "CHARGING • $src"
            if (plugged != 0) return "CONNECTED • $src"
            return if (isLow(level, plugged)) "LOW POWER WARNING" else "ON INTERNAL POWER"
        }

        /**
         * The detail line: temperature (tenths of °C), voltage (mV, or V on devices that report volts)
         * and health. Unknown temperature is Int.MIN_VALUE; unknown voltage is 0 or less.
         */
        fun detailText(tempTenths: Int, volt: Int, health: Int): String {
            val d = StringBuilder()
            if (tempTenths != Int.MIN_VALUE) d.append(String.format(Locale.US, "TEMP %.1f°C", tempTenths / 10f))
            if (volt > 0) {
                if (d.isNotEmpty()) d.append("  •  ")
                d.append(String.format(Locale.US, "%.2f V", if (volt > 1000) volt / 1000f else volt.toFloat()))
            }
            if (d.isNotEmpty()) d.append("  •  ")
            return d.append(healthName(health)).toString()
        }

        /** console wording for a BatteryManager health constant. */
        fun healthName(h: Int): String =
            when (h) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "HEALTH NOMINAL"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
                BatteryManager.BATTERY_HEALTH_DEAD -> "CELL FAILURE"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER VOLTAGE"
                BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
                else -> "HEALTH UNKNOWN"
            }
    }
}
