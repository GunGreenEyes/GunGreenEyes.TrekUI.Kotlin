package com.gungreeneyes.trekui;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Low-cost readers for CPU and memory, plus the text formatting used by {@link SystemsPanel}.
 *
 * <p>Every reader tolerates Android's restrictions (SELinux blocks /proc/stat for apps since
 * Android 8; some vendors block cpufreq): an unreadable source returns null / -1 and the panel
 * shows what is available. Readers touch the file system and belong on a background thread; the
 * parsers and formatters are pure and unit-tested in {@code SysStatsTest}.
 */
final class SysStats {
    private static final Pattern CPU_DIR = Pattern.compile("cpu\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** /proc/stat columns after "cpu": user nice system idle iowait irq softirq steal. */
    private static final int STAT_COLUMNS = 8, IDLE_COLUMN = 4, IOWAIT_COLUMN = 5;
    private static final long KB = 1024;

    private SysStats() {}

    /** Reads a small text file, or null when it is missing or blocked. */
    static String read(String path) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(path), 4096)) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }

    // ---- CPU load (/proc/stat) ---------------------------------------------------------------------

    /** @return {idle, total} jiffies from the aggregate "cpu" line, or null. */
    static long[] parseStat(String stat) {
        if (stat == null) return null;
        for (String line : stat.split("\n")) {
            if (!line.startsWith("cpu ")) continue;
            String[] p = WHITESPACE.split(line.trim());
            long total = 0, idle = 0;
            for (int i = 1; i < p.length && i <= STAT_COLUMNS; i++) {
                long v;
                try { v = Long.parseLong(p[i]); } catch (NumberFormatException e) { return null; }
                total += v;
                if (i == IDLE_COLUMN || i == IOWAIT_COLUMN) idle += v;
            }
            return total > 0 ? new long[]{idle, total} : null;
        }
        return null;
    }

    /** Busy fraction (0..1) between two samples, or -1 when it can't be computed. */
    static float load(long[] prev, long[] cur) {
        if (prev == null || cur == null) return -1;
        long dt = cur[1] - prev[1], di = cur[0] - prev[0];
        if (dt <= 0) return -1;
        return Math.max(0f, Math.min(1f, 1f - di / (float) dt));
    }

    // ---- CPU clocks (/sys/devices/system/cpu) ------------------------------------------------------

    /** Clock readings across cores. */
    static final class Freq {
        /** Cores counted, and cores whose clocks could be read. */
        int cores, readable;
        /** Fastest current clock and highest maximum clock, in kHz. */
        long peakKHz, maxKHz;
        /** Average current/maximum across readable cores, -1 when none could be read. */
        float ratio = -1;
    }

    /** Number of cpuN directories under root, or the JVM's processor count if none are listed. */
    static int cores(String root) {
        File[] f = new File(root).listFiles();
        int n = 0;
        if (f != null) for (File x : f) if (CPU_DIR.matcher(x.getName()).matches()) n++;
        return n > 0 ? n : Runtime.getRuntime().availableProcessors();
    }

    private static long readLong(String path) {
        return parseLong(read(path));
    }

    /** A trimmed decimal number, or -1. */
    static long parseLong(String s) {
        if (s == null) return -1;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * Reads every core's current and maximum clock; cheap (two tiny files per core).
     *
     * @param maxCache one slot per core; maximum clocks are read once and cached here
     *                 (0 = not read yet, -1 = unreadable)
     */
    static Freq freq(String root, long[] maxCache) {
        Freq f = new Freq();
        f.cores = maxCache.length;
        float sum = 0;
        for (int i = 0; i < maxCache.length; i++) {
            String base = root + "/cpu" + i + "/cpufreq/";
            if (maxCache[i] == 0) {
                long m = readLong(base + "cpuinfo_max_freq");
                maxCache[i] = m > 0 ? m : -1;
            }
            long cur = readLong(base + "scaling_cur_freq");
            if (cur <= 0 || maxCache[i] <= 0) continue; // offline core or blocked
            f.readable++;
            sum += Math.min(1f, cur / (float) maxCache[i]);
            f.peakKHz = Math.max(f.peakKHz, cur);
            f.maxKHz = Math.max(f.maxKHz, maxCache[i]);
        }
        if (f.readable > 0) f.ratio = sum / f.readable;
        return f;
    }

    // ---- memory (/proc/meminfo) -------------------------------------------------------------------

    /** kB values keyed by name (MemTotal, MemAvailable, SwapTotal, SwapFree, Cached...), or null. */
    static Map<String, Long> parseMeminfo(String s) {
        if (s == null) return null;
        Map<String, Long> m = new HashMap<>();
        for (String line : s.split("\n")) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String[] p = WHITESPACE.split(line.substring(c + 1).trim());
            try { m.put(line.substring(0, c).trim(), Long.parseLong(p[0])); } catch (NumberFormatException ignored) { }
        }
        return m.isEmpty() ? null : m;
    }

    /** A meminfo value in bytes, or -1 when absent. */
    static long meminfoBytes(Map<String, Long> m, String key) {
        if (m == null) return -1;
        Long v = m.get(key);
        return v == null ? -1 : v * KB;
    }

    // ---- formatting -------------------------------------------------------------------------------

    /** Bytes as decimal gigabytes: one decimal below 100 GB, whole numbers above. */
    static String gb(long bytes) {
        double g = bytes / 1e9;
        return g >= 100 ? String.format(Locale.US, "%.0f", g) : String.format(Locale.US, "%.1f", g);
    }

    /** kHz as GHz with two decimals. */
    static String ghz(long kHz) {
        return String.format(Locale.US, "%.2f", kHz / 1e6);
    }

    /** Network rate in bits per second, as speed tests and data plans quote it. */
    static String rate(double bytesPerSec) {
        double bits = bytesPerSec * 8;
        if (bits >= 1e9) return String.format(Locale.US, "%.2f GBPS", bits / 1e9);
        if (bits >= 1e6) return String.format(Locale.US, bits >= 1e8 ? "%.0f MBPS" : "%.1f MBPS", bits / 1e6);
        if (bits >= 1e3) return String.format(Locale.US, "%.0f KBPS", bits / 1e3);
        return String.format(Locale.US, "%.0f BPS", bits);
    }

    /** A 0..1 fraction as a whole percentage. */
    static int percent(float fraction) { return Math.round(fraction * 100); }

    /** Uptime in minutes as "3D 04H 07M". */
    static String uptime(long minutes) {
        long m = Math.max(0, minutes);
        return String.format(Locale.US, "%dD %02dH %02dM", m / 1440, (m / 60) % 24, m % 60);
    }

    /** CPU detail line: core count, peak clock (when readable) and thermal status (when known). */
    static String cpuDetail(int cores, Freq f, String thermal) {
        StringBuilder d = new StringBuilder();
        d.append(cores).append(" CORES");
        if (f != null && f.readable > 0) d.append(" • PEAK ").append(ghz(f.peakKHz)).append(" / ").append(ghz(f.maxKHz)).append(" GHZ");
        if (thermal != null && !thermal.isEmpty()) d.append(" • ").append(thermal);
        return d.toString();
    }

    /**
     * Memory detail line: used of total with percentage, swap use when there is swap, and the
     * low-memory state or threshold.
     */
    static String memDetail(long used, long total, long swapTotal, long swapFree, boolean low, long threshold) {
        StringBuilder d = new StringBuilder();
        float frac = total > 0 ? used / (float) total : 0;
        d.append(gb(used)).append(" OF ").append(gb(total)).append(" GB IN USE (").append(percent(frac)).append("%)");
        if (swapTotal > 0) {
            long free = swapFree >= 0 ? swapFree : swapTotal;
            d.append(" • SWAP ").append(gb(swapTotal - free)).append("/").append(gb(swapTotal)).append(" GB");
        }
        if (low) d.append(" • LOW MEMORY");
        else d.append(" • LOW AT ").append(gb(threshold)).append(" GB");
        return d.toString();
    }

    /** Storage detail line: "12.3 OF 128 GB USED (10%)". */
    static String diskDetail(long total, long free) {
        float frac = total > 0 ? (total - free) / (float) total : 0;
        return gb(total - free) + " OF " + gb(total) + " GB USED (" + percent(frac) + "%)";
    }
}
