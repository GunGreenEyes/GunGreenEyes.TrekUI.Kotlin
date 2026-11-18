package com.gungreeneyes.trekui

import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.Locale
import java.util.regex.Pattern

/**
 * Low-cost readers for CPU and memory, plus the text formatting used by [SystemsPanel].
 *
 * Every reader tolerates Android's restrictions (SELinux blocks /proc/stat for apps since
 * Android 8; some vendors block cpufreq): an unreadable source returns null / -1 and the panel
 * shows what is available. Readers touch the file system and belong on a background thread; the
 * parsers and formatters are pure and unit-tested in `SysStatsTest`.
 */
internal object SysStats {
    private val CPU_DIR: Pattern = Pattern.compile("cpu\\d+")
    private val WHITESPACE: Pattern = Pattern.compile("\\s+")

    /** /proc/stat columns after "cpu": user nice system idle iowait irq softirq steal. */
    private const val STAT_COLUMNS = 8
    private const val IDLE_COLUMN = 4
    private const val IOWAIT_COLUMN = 5
    private const val KB = 1024L

    /** Reads a small text file, or null when it is missing or blocked. */
    @JvmStatic
    fun read(path: String): String? =
        try {
            BufferedReader(FileReader(path), 4096).use { r ->
                val sb = StringBuilder()
                while (true) {
                    val line = r.readLine() ?: break
                    sb.append(line).append('\n')
                }
                sb.toString()
            }
        } catch (e: IOException) {
            null
        } catch (e: SecurityException) {
            null
        }

    // ---- CPU load (/proc/stat) ---------------------------------------------------------------------

    /** @return {idle, total} jiffies from the aggregate "cpu" line, or null. */
    @JvmStatic
    fun parseStat(stat: String?): LongArray? {
        if (stat == null) return null
        for (line in stat.split("\n")) {
            if (!line.startsWith("cpu ")) continue
            val p = WHITESPACE.split(line.javaTrim())
            var total = 0L
            var idle = 0L
            var i = 1
            while (i < p.size && i <= STAT_COLUMNS) {
                val v = try {
                    p[i].toLong()
                } catch (e: NumberFormatException) {
                    return null
                }
                total += v
                if (i == IDLE_COLUMN || i == IOWAIT_COLUMN) idle += v
                i++
            }
            return if (total > 0) longArrayOf(idle, total) else null
        }
        return null
    }

    /** Busy fraction (0..1) between two samples, or -1 when it can't be computed. */
    @JvmStatic
    fun load(prev: LongArray?, cur: LongArray?): Float {
        if (prev == null || cur == null) return -1f
        val dt = cur[1] - prev[1]
        val di = cur[0] - prev[0]
        if (dt <= 0) return -1f
        return Math.max(0f, Math.min(1f, 1f - di / dt.toFloat()))
    }

    // ---- CPU clocks (/sys/devices/system/cpu) ------------------------------------------------------

    /** Clock readings across cores. */
    class Freq {
        /** Cores counted, and cores whose clocks could be read. */
        @JvmField var cores = 0

        @JvmField var readable = 0

        /** Fastest current clock and highest maximum clock, in kHz. */
        @JvmField var peakKHz = 0L

        @JvmField var maxKHz = 0L

        /** Average current/maximum across readable cores, -1 when none could be read. */
        @JvmField var ratio = -1f
    }

    /** Number of cpuN directories under root, or the JVM's processor count if none are listed. */
    @JvmStatic
    fun cores(root: String): Int {
        val f = File(root).listFiles()
        var n = 0
        if (f != null) for (x in f) if (CPU_DIR.matcher(x.name).matches()) n++
        return if (n > 0) n else Runtime.getRuntime().availableProcessors()
    }

    private fun readLong(path: String): Long = parseLong(read(path))

    /** A trimmed decimal number, or -1. */
    @JvmStatic
    fun parseLong(s: String?): Long {
        if (s == null) return -1
        return try {
            s.javaTrim().toLong()
        } catch (e: NumberFormatException) {
            -1
        }
    }

    /**
     * Reads every core's current and maximum clock; cheap (two tiny files per core).
     *
     * @param maxCache one slot per core; maximum clocks are read once and cached here
     *                 (0 = not read yet, -1 = unreadable)
     */
    @JvmStatic
    fun freq(root: String, maxCache: LongArray): Freq {
        val f = Freq()
        f.cores = maxCache.size
        var sum = 0f
        for (i in maxCache.indices) {
            val base = "$root/cpu$i/cpufreq/"
            if (maxCache[i] == 0L) {
                val m = readLong(base + "cpuinfo_max_freq")
                maxCache[i] = if (m > 0) m else -1
            }
            val cur = readLong(base + "scaling_cur_freq")
            if (cur <= 0 || maxCache[i] <= 0) continue // offline core or blocked
            f.readable++
            sum += Math.min(1f, cur / maxCache[i].toFloat())
            f.peakKHz = Math.max(f.peakKHz, cur)
            f.maxKHz = Math.max(f.maxKHz, maxCache[i])
        }
        if (f.readable > 0) f.ratio = sum / f.readable
        return f
    }

    // ---- memory (/proc/meminfo) -------------------------------------------------------------------

    /** kB values keyed by name (MemTotal, MemAvailable, SwapTotal, SwapFree, Cached...), or null. */
    @JvmStatic
    fun parseMeminfo(s: String?): Map<String, Long>? {
        if (s == null) return null
        val m = HashMap<String, Long>()
        for (line in s.split("\n")) {
            val c = line.indexOf(':')
            if (c <= 0) continue
            val p = WHITESPACE.split(line.substring(c + 1).javaTrim())
            try {
                m[line.substring(0, c).javaTrim()] = p[0].toLong()
            } catch (ignored: NumberFormatException) {
            }
        }
        return if (m.isEmpty()) null else m
    }

    /** A meminfo value in bytes, or -1 when absent. */
    @JvmStatic
    fun meminfoBytes(m: Map<String, Long>?, key: String): Long {
        if (m == null) return -1
        val v = m[key] ?: return -1
        return v * KB
    }

    // ---- formatting -------------------------------------------------------------------------------

    /** Bytes as decimal gigabytes: one decimal below 100 GB, whole numbers above. */
    @JvmStatic
    fun gb(bytes: Long): String {
        val g = bytes / 1e9
        return if (g >= 100) String.format(Locale.US, "%.0f", g) else String.format(Locale.US, "%.1f", g)
    }

    /** kHz as GHz with two decimals. */
    @JvmStatic
    fun ghz(kHz: Long): String = String.format(Locale.US, "%.2f", kHz / 1e6)

    /** Network rate in bits per second, as speed tests and data plans quote it. */
    @JvmStatic
    fun rate(bytesPerSec: Double): String {
        val bits = bytesPerSec * 8
        if (bits >= 1e9) return String.format(Locale.US, "%.2f GBPS", bits / 1e9)
        if (bits >= 1e6) return String.format(Locale.US, if (bits >= 1e8) "%.0f MBPS" else "%.1f MBPS", bits / 1e6)
        if (bits >= 1e3) return String.format(Locale.US, "%.0f KBPS", bits / 1e3)
        return String.format(Locale.US, "%.0f BPS", bits)
    }

    /** A 0..1 fraction as a whole percentage. */
    @JvmStatic
    fun percent(fraction: Float): Int = Math.round(fraction * 100)

    /** Uptime in minutes as "3D 04H 07M". */
    @JvmStatic
    fun uptime(minutes: Long): String {
        val m = Math.max(0L, minutes)
        return String.format(Locale.US, "%dD %02dH %02dM", m / 1440, (m / 60) % 24, m % 60)
    }

    /** CPU detail line: core count, peak clock (when readable) and thermal status (when known). */
    @JvmStatic
    fun cpuDetail(cores: Int, f: Freq?, thermal: String?): String {
        val d = StringBuilder()
        d.append(cores).append(" CORES")
        if (f != null && f.readable > 0) d.append(" • PEAK ").append(ghz(f.peakKHz)).append(" / ").append(ghz(f.maxKHz)).append(" GHZ")
        if (!thermal.isNullOrEmpty()) d.append(" • ").append(thermal)
        return d.toString()
    }

    /**
     * Memory detail line: used of total with percentage, swap use when there is swap, and the
     * low-memory state or threshold.
     */
    @JvmStatic
    fun memDetail(used: Long, total: Long, swapTotal: Long, swapFree: Long, low: Boolean, threshold: Long): String {
        val d = StringBuilder()
        val frac = if (total > 0) used / total.toFloat() else 0f
        d.append(gb(used)).append(" OF ").append(gb(total)).append(" GB IN USE (").append(percent(frac)).append("%)")
        if (swapTotal > 0) {
            val free = if (swapFree >= 0) swapFree else swapTotal
            d.append(" • SWAP ").append(gb(swapTotal - free)).append("/").append(gb(swapTotal)).append(" GB")
        }
        if (low) d.append(" • LOW MEMORY") else d.append(" • LOW AT ").append(gb(threshold)).append(" GB")
        return d.toString()
    }

    /** Storage detail line: "12.3 OF 128 GB USED (10%)". */
    @JvmStatic
    fun diskDetail(total: Long, free: Long): String {
        val frac = if (total > 0) (total - free) / total.toFloat() else 0f
        return gb(total - free) + " OF " + gb(total) + " GB USED (" + percent(frac) + "%)"
    }
}
