package com.gungreeneyes.trekui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Home panel "SHIP SYSTEMS": CPU (load where Android permits, else live core clocks, else thermal
 * headroom), thermal state, memory (free / in use / swap), storage, live network throughput with
 * a traffic graph, device identity and uptime, and quick controls (torch, Wi-Fi, Bluetooth,
 * volume).
 *
 * Fast readings refresh every [FAST_MS] ms only while the home screen is visible. The
 * /proc and /sys files and the storage query are read on a background thread ([SAMPLER]);
 * the views are updated on the main thread. A generation counter drops results that arrive after
 * the panel was paused, so a pause/resume never starts two sampling loops.
 */
internal class SystemsPanel(a: MainActivity, accent: Int) : Panel(a, accent) {
    private class Meter {
        lateinit var bar: SegBar
        lateinit var value: TextView
        lateinit var detail: TextView
    }

    private lateinit var cpu: Meter
    private lateinit var mem: Meter
    private lateinit var disk: Meter
    private lateinit var net: TextView
    private lateinit var rates: TextView
    private lateinit var ident: TextView
    private lateinit var graph: RateGraph
    private lateinit var torchBtn: TextView
    private var cams: CameraManager? = null
    private var torchId: String? = null
    private var torchOn = false
    private var running = false

    // sampling state
    private var statPrev: LongArray? = null
    private var statBlocked = false
    private lateinit var maxFreq: LongArray
    private var rxPrev = -1L
    private var txPrev = -1L
    private var netAt = 0L
    private var fastCount = 0

    /** Bumped on every resume and pause; background results from an older generation are dropped. */
    private var generation = 0
    private var thermalText = ""
    private var thermalHeadroom = -1f

    private val torchCb: CameraManager.TorchCallback =
        object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == torchId) {
                    torchOn = enabled
                    paintTorch()
                }
            }

            override fun onTorchModeUnavailable(id: String) {}
        }

    /** One sampling cycle: read files in the background, then update the views and schedule the next. */
    private val fast: Runnable =
        object : Runnable {
            override fun run() {
                if (!running) return
                val gen = generation
                val slow = fastCount % 2 == 0
                val readStat = !statBlocked
                fastCount++
                val self = this
                SAMPLER.execute {
                    val stat = if (readStat) SysStats.parseStat(SysStats.read("/proc/stat")) else null
                    val freq = SysStats.freq(CPU_ROOT, maxFreq)
                    val meminfo = if (slow) SysStats.parseMeminfo(SysStats.read("/proc/meminfo")) else null
                    a.handler().post {
                        if (!running || gen != generation) return@post
                        showCpu(readStat, stat, freq)
                        sampleNet()
                        if (slow) {
                            sampleThermal()
                            showMem(meminfo)
                        }
                        a.handler().postDelayed(self, FAST_MS)
                    }
                }
            }
        }

    override fun id(): String = Prefs.W_SYSTEMS

    override fun title(): String = "SHIP SYSTEMS"

    /** A labelled segmented bar with a value on the right and a detail line underneath. */
    private fun meter(label: String, color: Int): Meter {
        val card = card!!
        val m = Meter()
        val row = LinearLayout(a)
        row.gravity = Gravity.CENTER_VERTICAL
        val l = Ui.bold(a, label, 15f, color)
        row.addView(l, LinearLayout.LayoutParams(Ui.dp(a, LABEL_DP), -2))
        m.bar = SegBar(a, 14, color, Ui.alpha(color, 0.16f))
        row.addView(m.bar, LinearLayout.LayoutParams(0, Ui.dp(a, BAR_DP), 1f))
        m.value = Ui.oneLine(Ui.bold(a, "", 14f, pal.textHi))
        m.value.gravity = Gravity.END
        row.addView(m.value, LinearLayout.LayoutParams(Ui.dp(a, VALUE_DP), -2))
        card.body.addView(row)
        m.detail = Ui.text(a, "", 12f, pal.textLo)
        m.detail.setPadding(Ui.dp(a, LABEL_DP), Ui.dp(a, Ui.HAIR.toFloat()), 0, Ui.dp(a, Ui.S.toFloat()))
        card.body.addView(m.detail)
        return m
    }

    override fun build() {
        val card = card!!
        cpu = meter("CPU", pal.c(0))
        mem = meter("MEMORY", pal.c(1))
        disk = meter("STORAGE", pal.c(2))

        net = Ui.oneLine(Ui.text(a, "", 14f, pal.textHi))
        card.body.addView(net)
        rates = Ui.oneLine(Ui.bold(a, "", 17f, pal.c(2)))
        rates.setPadding(0, Ui.dp(a, Ui.HAIR.toFloat()), 0, Ui.dp(a, Ui.XS.toFloat()))
        card.body.addView(rates)
        graph = RateGraph(a, pal.c(2), pal.c(0))
        card.body.addView(graph, LinearLayout.LayoutParams(-1, Ui.dp(a, GRAPH_DP)))
        ident = Ui.oneLine(Ui.text(a, "", 13f, pal.textLo))
        ident.setPadding(0, Ui.dp(a, Ui.S.toFloat()), 0, Ui.dp(a, Ui.M.toFloat()))
        card.body.addView(ident)

        val row = LinearLayout(a)
        torchBtn = control(row, "TORCH", pal.c(4)) { toggleTorch() }
        control(row, "WI-FI", pal.c(1)) { v ->
            a.startSafe(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS), v)
        }
        control(row, "BLUETOOTH", pal.c(2)) { v -> a.startSafe(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), v) }
        control(row, "VOLUME", pal.c(3)) {
            val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
            am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        }
        card.body.addView(row, LinearLayout.LayoutParams(-1, Ui.dp(a, CONTROL_ROW_DP)))
        card.status.text = "DEVICE STATUS"
        card.status.setOnClickListener(Ui.feedback { v -> a.startSafe(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), v) })

        val cams = a.getSystemService(Context.CAMERA_SERVICE) as CameraManager?
        this.cams = cams
        try {
            if (cams != null) {
                for (id in cams.cameraIdList) {
                    val flash = cams.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)
                    val facing = cams.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                    if (flash == true && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        torchId = id
                        break
                    }
                }
            }
        } catch (ignored: Exception) {
        }
        if (torchId == null) torchBtn.alpha = 0.35f
        maxFreq = LongArray(SysStats.cores(CPU_ROOT))
    }

    /** A quick-control key; the first gets a rounded left end, the fourth a rounded right end. */
    private fun control(row: LinearLayout, label: String, color: Int, l: View.OnClickListener): TextView {
        val b = Ui.button(a, label, color, if (row.childCount == 0) Ui.LEFT else Ui.SQUARE, l)
        b.setTextSize(14 * Skin.textScale())
        b.setPadding(Ui.dp(a, Ui.XS.toFloat()), 0, Ui.dp(a, Ui.S.toFloat()), Ui.dp(a, Ui.GUTTER.toFloat()))
        val lp = LinearLayout.LayoutParams(0, -1, 1f)
        if (row.childCount > 0) lp.leftMargin = Ui.dp(a, Ui.XS.toFloat())
        row.addView(b, lp)
        if (row.childCount == 4) b.background = Ui.touch(Ui.shape(a, color, Ui.RIGHT, 20f))
        return b
    }

    private fun toggleTorch() {
        val id = torchId
        val cams = cams
        if (id == null || cams == null) {
            Sfx.play(a, Sfx.DENY)
            return
        }
        try {
            cams.setTorchMode(id, !torchOn)
        } catch (e: Exception) {
            Sfx.play(a, Sfx.ERROR)
            a.toast("TORCH UNAVAILABLE (CAMERA IN USE)")
        }
    }

    private fun paintTorch() {
        torchBtn.text = if (torchOn) "TORCH ON" else "TORCH"
        val c = if (torchOn) pal.textHi else pal.c(4)
        torchBtn.background = Ui.touch(Ui.shape(a, c, Ui.LEFT, 20f))
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    override fun resume() {
        val cams = cams
        if (cams != null && torchId != null) {
            try {
                cams.registerTorchCallback(torchCb, a.handler())
            } catch (ignored: Exception) {
            }
        }
        running = true
        generation++
        rxPrev = -1
        txPrev = -1
        statPrev = null
        fastCount = 0
        sampleThermal()
        sampleDisk()
        sampleIdent()
        a.handler().removeCallbacks(fast)
        fast.run()
    }

    override fun pause() {
        running = false
        generation++
        a.handler().removeCallbacks(fast)
        val cams = cams
        if (cams != null && torchId != null) {
            try {
                cams.unregisterTorchCallback(torchCb)
            } catch (ignored: Exception) {
            }
        }
    }

    override fun tick() {
        sampleDisk()
        sampleIdent()
    }

    // ---- CPU & thermal ---------------------------------------------------------------------------

    /**
     * Shows CPU use from the background readings: real load when /proc/stat is readable (two
     * samples needed), else how hard the cores are clocked, else thermal headroom.
     *
     * @param attempted whether /proc/stat was read this cycle
     * @param cur       the parsed /proc/stat sample, or null when blocked
     */
    private fun showCpu(attempted: Boolean, cur: LongArray?, f: SysStats.Freq) {
        var load = -1f
        if (attempted) {
            if (cur == null) {
                statBlocked = true
            } else {
                load = SysStats.load(statPrev, cur)
                statPrev = cur
            }
        }
        val d = StringBuilder(SysStats.cpuDetail(maxFreq.size, f, thermalText))
        if (load >= 0) {
            cpu.bar.set(load, if (load > HOT_LOAD) pal.alert else pal.c(0))
            cpu.value.text = Math.round(load * 100).toString() + "% LOAD"
        } else if (f.ratio >= 0) {
            // Android blocks CPU-usage counters for apps: show how hard the cores are clocked instead.
            cpu.bar.set(f.ratio, pal.c(0))
            cpu.value.text = Math.round(f.ratio * 100).toString() + "% CLOCK"
        } else if (thermalHeadroom >= 0) {
            cpu.bar.set(min(1f, thermalHeadroom), if (thermalHeadroom > HOT_LOAD) pal.alert else pal.c(0))
            cpu.value.text = Math.round(thermalHeadroom * 100).toString() + "% THERMAL"
        } else {
            cpu.bar.set(0f, pal.c(0))
            cpu.value.text = "RESTRICTED"
            d.append(" • CPU COUNTERS BLOCKED BY ANDROID")
        }
        cpu.detail.text = d.toString()
    }

    /** Thermal status (API 29+) and headroom (API 30+), shown in the CPU detail line. */
    private fun sampleThermal() {
        if (Build.VERSION.SDK_INT < 29) return
        val pm = a.getSystemService(Context.POWER_SERVICE) as PowerManager? ?: return
        var status =
            when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "THERMAL NOMINAL"
                PowerManager.THERMAL_STATUS_LIGHT -> "THERMAL LIGHT"
                PowerManager.THERMAL_STATUS_MODERATE -> "THERMAL MODERATE"
                PowerManager.THERMAL_STATUS_SEVERE -> "THERMAL SEVERE"
                PowerManager.THERMAL_STATUS_CRITICAL -> "THERMAL CRITICAL"
                else -> "THERMAL EMERGENCY"
            }
        if (Build.VERSION.SDK_INT >= 30) {
            val h = pm.getThermalHeadroom(0) // 0 = now; 1.0 = throttling threshold
            if (!h.isNaN() && h >= 0) {
                thermalHeadroom = h
                status += " (" + Math.round(h * 100) + "%)"
            }
        }
        thermalText = status
    }

    // ---- memory & storage --------------------------------------------------------------------------

    /**
     * Shows memory from ActivityManager, preferring the kernel's MemAvailable (read in the
     * background) because it counts reclaimable cache as free.
     */
    private fun showMem(m: Map<String, Long>?) {
        val am = a.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager? ?: return
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val total = mi.totalMem
        var avail = mi.availMem
        if (total <= 0) return
        val kernelAvail = SysStats.meminfoBytes(m, "MemAvailable")
        if (kernelAvail >= 0) avail = kernelAvail
        val used = max(0L, total - avail)
        mem.bar.set(used / total.toFloat(), if (mi.lowMemory) pal.alert else pal.c(1))
        mem.value.text = SysStats.gb(avail) + " GB FREE"
        mem.detail.text = SysStats.memDetail(
            used,
            total,
            SysStats.meminfoBytes(m, "SwapTotal"),
            SysStats.meminfoBytes(m, "SwapFree"),
            mi.lowMemory,
            mi.threshold,
        )
        mem.detail.setTextColor(if (mi.lowMemory) pal.alert else pal.textLo)
    }

    /** Reads free/total storage of the data partition in the background, then shows it. */
    private fun sampleDisk() {
        val gen = generation
        SAMPLER.execute {
            var t = -1L
            var f = -1L
            try {
                val fs = StatFs(Environment.getDataDirectory().path)
                t = fs.totalBytes
                f = fs.availableBytes
            } catch (ignored: RuntimeException) {
                // storage stats unavailable
            }
            val total = t
            val free = f
            a.handler().post { if (gen == generation && total > 0) showDisk(total, free) }
        }
    }

    private fun showDisk(total: Long, free: Long) {
        val freeFrac = free / total.toFloat()
        disk.bar.set(1 - freeFrac, if (freeFrac < LOW_DISK) pal.alert else pal.c(2))
        disk.value.text = SysStats.gb(free) + " GB FREE"
        disk.detail.text = SysStats.diskDetail(total, free)
        if (freeFrac < CRITICAL_DISK && !storageWarned && a.isResumedNow()) {
            storageWarned = true
            Sfx.play(a, Sfx.SYS_WARNING)
            a.toast("STORAGE BELOW 5% • FREE UP SPACE")
        }
    }

    // ---- network -----------------------------------------------------------------------------------

    /** Connection type and link speed, plus throughput since the previous sample. */
    private fun sampleNet() {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()
        var type = "OFFLINE"
        var link = ""
        try {
            val cm = a.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork
            val c = if (n == null) null else cm.getNetworkCapabilities(n)
            if (c != null) {
                type =
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        "WI-FI"
                    } else if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        "CELLULAR"
                    } else if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        "ETHERNET"
                    } else {
                        "CONNECTED"
                    }
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) type += " + VPN"
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) type += " (NO INTERNET)"
                val down = c.linkDownstreamBandwidthKbps
                if (down > 0) link = " • LINK ~" + SysStats.rate(down * 1000 / 8.0)
            }
        } catch (ignored: Exception) {
        }
        net.text = "SUBSPACE LINK • $type$link"
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            rates.text = "THROUGHPUT UNAVAILABLE ON THIS DEVICE"
            return
        }
        if (rxPrev >= 0 && now > netAt) {
            val sec = (now - netAt) / 1000.0
            val down = max(0L, rx - rxPrev) / sec
            val up = max(0L, tx - txPrev) / sec
            rates.text = "▼ " + SysStats.rate(down) + "     ▲ " + SysStats.rate(up)
            graph.add(down.toFloat(), up.toFloat())
        } else {
            rates.text = "▼ …     ▲ …"
        }
        rxPrev = rx
        txPrev = tx
        netAt = now
    }

    /** Device name, Android version and uptime. */
    private fun sampleIdent() {
        val up = SystemClock.elapsedRealtime() / 60000
        var name: String? = null
        try {
            // DEVICE_NAME is API 25; below it the model name is shown
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                name = Settings.Global.getString(a.contentResolver, Settings.Global.DEVICE_NAME)
            }
        } catch (ignored: RuntimeException) {
        }
        if (name.isNullOrEmpty()) name = Build.MODEL
        ident.text = name!!.uppercase(Locale.getDefault()) + "  •  ANDROID " + Build.VERSION.RELEASE + "  •  UPTIME " + SysStats.uptime(up)
    }

    /** Scrolling console traffic graph: download as bars, upload as a thin line, auto-scaled. */
    class RateGraph(c: Context, downColor: Int, upColor: Int) : View(c) {
        private val down = FloatArray(N)
        private val up = FloatArray(N)
        private var count = 0
        private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint(Paint.ANTI_ALIAS_FLAG)
        private val base = Paint()

        init {
            bar.color = downColor
            line.color = upColor
            line.strokeWidth = Ui.dp(c, 2f).toFloat()
            line.strokeCap = Paint.Cap.ROUND
            base.color = Ui.alpha(downColor, 0.25f)
        }

        /** Appends one sample (bytes per second) and redraws. */
        fun add(d: Float, u: Float) {
            System.arraycopy(down, 1, down, 0, N - 1)
            System.arraycopy(up, 1, up, 0, N - 1)
            down[N - 1] = d
            up[N - 1] = u
            count = min(N, count + 1)
            invalidate()
        }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            cv.drawRect(0f, h - Ui.dp(context, 2f), w, h, base)
            var peak = 64f * 1024 // floor the scale at 512 kbps so idle traffic stays small
            for (i in 0 until N) peak = max(peak, max(down[i], up[i]))
            val slot = w / N
            val gap = max(1f, slot * 0.25f)
            for (i in N - count until N) {
                val bh = (h - 2) * sqrt((down[i] / peak).toDouble()).toFloat()
                val x = i * slot
                if (bh > 0.5f) cv.drawRect(x, h - bh, x + slot - gap, h, bar)
            }
            for (i in max(N - count, 1) until N) {
                if (i - 1 < N - count) continue
                val y0 = h - (h - 2) * sqrt((up[i - 1] / peak).toDouble()).toFloat()
                val y1 = h - (h - 2) * sqrt((up[i] / peak).toDouble()).toFloat()
                cv.drawLine((i - 0.5f) * slot, y0, (i + 0.5f) * slot, y1, line)
            }
        }

        private companion object {
            const val N = 40
        }
    }

    private companion object {
        const val CPU_ROOT = "/sys/devices/system/cpu"

        /** Sampling period for CPU and network; memory and thermal refresh every second sample. */
        const val FAST_MS = 1500L

        /** Load above this fraction is drawn in the alert colour. */
        const val HOT_LOAD = 0.85f

        /** Free storage below these fractions is drawn in alert colour / announced once. */
        const val LOW_DISK = 0.08f
        const val CRITICAL_DISK = 0.05f

        /** Label and value column widths, bar and graph heights, in dp. */
        const val LABEL_DP = 72f
        const val VALUE_DP = 96f
        const val BAR_DP = 12f
        const val GRAPH_DP = 40f
        const val CONTROL_ROW_DP = 52f

        /** Background thread for file-system reads (shared by every SystemsPanel). */
        val SAMPLER: ExecutorService = Http.newExecutor("trekui-sysstats")

        var storageWarned = false
    }
}
