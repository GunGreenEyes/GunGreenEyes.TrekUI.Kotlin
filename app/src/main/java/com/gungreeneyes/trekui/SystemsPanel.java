package com.gungreeneyes.trekui;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Home panel "SHIP SYSTEMS": CPU (load where Android permits, else live core clocks, else thermal
 * headroom), thermal state, memory (free / in use / swap), storage, live network throughput with
 * a traffic graph, device identity and uptime, and quick controls (torch, Wi-Fi, Bluetooth,
 * volume).
 *
 * <p>Fast readings refresh every {@link #FAST_MS} ms only while the home screen is visible. The
 * /proc and /sys files and the storage query are read on a background thread ({@link #SAMPLER});
 * the views are updated on the main thread. A generation counter drops results that arrive after
 * the panel was paused, so a pause/resume never starts two sampling loops.
 */
class SystemsPanel extends Panel {
    private static final String CPU_ROOT = "/sys/devices/system/cpu";
    /** Sampling period for CPU and network; memory and thermal refresh every second sample. */
    private static final long FAST_MS = 1500;
    /** Load above this fraction is drawn in the alert colour. */
    private static final float HOT_LOAD = 0.85f;
    /** Free storage below these fractions is drawn in alert colour / announced once. */
    private static final float LOW_DISK = 0.08f, CRITICAL_DISK = 0.05f;
    /** Label and value column widths, bar and graph heights, in dp. */
    private static final float LABEL_DP = 72, VALUE_DP = 96, BAR_DP = 12, GRAPH_DP = 40, CONTROL_ROW_DP = 52;
    /** Background thread for file-system reads (shared by every SystemsPanel). */
    private static final ExecutorService SAMPLER = Http.newExecutor("trekui-sysstats");

    private static final class Meter {
        SegBar bar;
        TextView value, detail;
    }

    private Meter cpu, mem, disk;
    private TextView net, rates, ident;
    private RateGraph graph;
    private TextView torchBtn;
    private CameraManager cams;
    private String torchId;
    private boolean torchOn, running;
    private static boolean storageWarned;

    // sampling state
    private long[] statPrev;
    private boolean statBlocked;
    private long[] maxFreq;
    private long rxPrev = -1, txPrev = -1, netAt;
    private int fastCount;
    /** Bumped on every resume and pause; background results from an older generation are dropped. */
    private int generation;
    private String thermalText = "";

    private final CameraManager.TorchCallback torchCb = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean enabled) {
            if (id.equals(torchId)) { torchOn = enabled; paintTorch(); }
        }
        @Override public void onTorchModeUnavailable(String id) { }
    };

    /** One sampling cycle: read files in the background, then update the views and schedule the next. */
    private final Runnable fast = new Runnable() {
        @Override public void run() {
            if (!running) return;
            final int gen = generation;
            final boolean slow = fastCount % 2 == 0;
            final boolean readStat = !statBlocked;
            fastCount++;
            SAMPLER.execute(new Runnable() {
                @Override public void run() {
                    final long[] stat = readStat ? SysStats.parseStat(SysStats.read("/proc/stat")) : null;
                    final SysStats.Freq freq = SysStats.freq(CPU_ROOT, maxFreq);
                    final Map<String, Long> meminfo = slow ? SysStats.parseMeminfo(SysStats.read("/proc/meminfo")) : null;
                    a.handler().post(new Runnable() {
                        @Override public void run() {
                            if (!running || gen != generation) return;
                            showCpu(readStat, stat, freq);
                            sampleNet();
                            if (slow) { sampleThermal(); showMem(meminfo); }
                            a.handler().postDelayed(fast, FAST_MS);
                        }
                    });
                }
            });
        }
    };

    SystemsPanel(MainActivity a, int accent) { super(a, accent); }

    @Override String id() { return Prefs.W_SYSTEMS; }
    @Override String title() { return "SHIP SYSTEMS"; }

    /** A labelled segmented bar with a value on the right and a detail line underneath. */
    private Meter meter(String label, int color) {
        Meter m = new Meter();
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = Ui.bold(a, label, 15, color);
        row.addView(l, new LinearLayout.LayoutParams(Ui.dp(a, LABEL_DP), -2));
        m.bar = new SegBar(a, 14, color, Ui.alpha(color, 0.16f));
        row.addView(m.bar, new LinearLayout.LayoutParams(0, Ui.dp(a, BAR_DP), 1));
        m.value = Ui.oneLine(Ui.bold(a, "", 14, pal.textHi));
        m.value.setGravity(Gravity.END);
        row.addView(m.value, new LinearLayout.LayoutParams(Ui.dp(a, VALUE_DP), -2));
        card.body.addView(row);
        m.detail = Ui.text(a, "", 12, pal.textLo);
        m.detail.setPadding(Ui.dp(a, LABEL_DP), Ui.dp(a, Ui.HAIR), 0, Ui.dp(a, Ui.S));
        card.body.addView(m.detail);
        return m;
    }

    @Override
    void build() {
        cpu = meter("CPU", pal.c(0));
        mem = meter("MEMORY", pal.c(1));
        disk = meter("STORAGE", pal.c(2));

        net = Ui.oneLine(Ui.text(a, "", 14, pal.textHi));
        card.body.addView(net);
        rates = Ui.oneLine(Ui.bold(a, "", 17, pal.c(2)));
        rates.setPadding(0, Ui.dp(a, Ui.HAIR), 0, Ui.dp(a, Ui.XS));
        card.body.addView(rates);
        graph = new RateGraph(a, pal.c(2), pal.c(0));
        card.body.addView(graph, new LinearLayout.LayoutParams(-1, Ui.dp(a, GRAPH_DP)));
        ident = Ui.oneLine(Ui.text(a, "", 13, pal.textLo));
        ident.setPadding(0, Ui.dp(a, Ui.S), 0, Ui.dp(a, Ui.M));
        card.body.addView(ident);

        LinearLayout row = new LinearLayout(a);
        torchBtn = control(row, "TORCH", pal.c(4), new View.OnClickListener() {
            @Override public void onClick(View v) { toggleTorch(); }
        });
        control(row, "WI-FI", pal.c(1), new View.OnClickListener() {
            @Override public void onClick(View v) {
                a.startSafe(new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIFI_SETTINGS), v);
            }
        });
        control(row, "BLUETOOTH", pal.c(2), new View.OnClickListener() {
            @Override public void onClick(View v) { a.startSafe(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), v); }
        });
        control(row, "VOLUME", pal.c(3), new View.OnClickListener() {
            @Override public void onClick(View v) {
                AudioManager am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
            }
        });
        card.body.addView(row, new LinearLayout.LayoutParams(-1, Ui.dp(a, CONTROL_ROW_DP)));
        card.status.setText("DEVICE STATUS");
        card.status.setOnClickListener(Ui.feedback(new View.OnClickListener() {
            @Override public void onClick(View v) { a.startSafe(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS), v); }
        }));

        cams = (CameraManager) a.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (cams != null) for (String id : cams.getCameraIdList()) {
                Boolean flash = cams.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = cams.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { torchId = id; break; }
            }
        } catch (Exception ignored) {
        }
        if (torchId == null) torchBtn.setAlpha(0.35f);
        maxFreq = new long[SysStats.cores(CPU_ROOT)];
    }

    /** A quick-control key; the first gets a rounded left end, the fourth a rounded right end. */
    private TextView control(LinearLayout row, String label, int color, View.OnClickListener l) {
        TextView b = Ui.button(a, label, color, row.getChildCount() == 0 ? Ui.LEFT : Ui.SQUARE, l);
        b.setTextSize((14) * Skin.textScale());
        b.setPadding(Ui.dp(a, Ui.XS), 0, Ui.dp(a, Ui.S), Ui.dp(a, Ui.GUTTER));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) lp.leftMargin = Ui.dp(a, Ui.XS);
        row.addView(b, lp);
        if (row.getChildCount() == 4) b.setBackground(Ui.touch(Ui.shape(a, color, Ui.RIGHT, 20)));
        return b;
    }

    private void toggleTorch() {
        if (torchId == null || cams == null) { Sfx.play(a, Sfx.DENY); return; }
        try {
            cams.setTorchMode(torchId, !torchOn);
        } catch (Exception e) {
            Sfx.play(a, Sfx.ERROR);
            a.toast("TORCH UNAVAILABLE (CAMERA IN USE)");
        }
    }

    private void paintTorch() {
        torchBtn.setText(torchOn ? "TORCH ON" : "TORCH");
        int c = torchOn ? pal.textHi : pal.c(4);
        torchBtn.setBackground(Ui.touch(Ui.shape(a, c, Ui.LEFT, 20)));
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    @Override
    void resume() {
        if (cams != null && torchId != null) {
            try { cams.registerTorchCallback(torchCb, a.handler()); } catch (Exception ignored) { }
        }
        running = true;
        generation++;
        rxPrev = txPrev = -1;
        statPrev = null;
        fastCount = 0;
        sampleThermal();
        sampleDisk();
        sampleIdent();
        a.handler().removeCallbacks(fast);
        fast.run();
    }

    @Override
    void pause() {
        running = false;
        generation++;
        a.handler().removeCallbacks(fast);
        if (cams != null && torchId != null) {
            try { cams.unregisterTorchCallback(torchCb); } catch (Exception ignored) { }
        }
    }

    @Override void tick() { sampleDisk(); sampleIdent(); }

    // ---- CPU & thermal ---------------------------------------------------------------------------

    /**
     * Shows CPU use from the background readings: real load when /proc/stat is readable (two
     * samples needed), else how hard the cores are clocked, else thermal headroom.
     *
     * @param attempted whether /proc/stat was read this cycle
     * @param cur       the parsed /proc/stat sample, or null when blocked
     */
    private void showCpu(boolean attempted, long[] cur, SysStats.Freq f) {
        float load = -1;
        if (attempted) {
            if (cur == null) statBlocked = true;
            else { load = SysStats.load(statPrev, cur); statPrev = cur; }
        }
        StringBuilder d = new StringBuilder(SysStats.cpuDetail(maxFreq.length, f, thermalText));
        if (load >= 0) {
            cpu.bar.set(load, load > HOT_LOAD ? pal.alert : pal.c(0));
            cpu.value.setText(Math.round(load * 100) + "% LOAD");
        } else if (f.ratio >= 0) {
            // Android blocks CPU-usage counters for apps: show how hard the cores are clocked instead.
            cpu.bar.set(f.ratio, pal.c(0));
            cpu.value.setText(Math.round(f.ratio * 100) + "% CLOCK");
        } else if (thermalHeadroom >= 0) {
            cpu.bar.set(Math.min(1f, thermalHeadroom), thermalHeadroom > HOT_LOAD ? pal.alert : pal.c(0));
            cpu.value.setText(Math.round(thermalHeadroom * 100) + "% THERMAL");
        } else {
            cpu.bar.set(0, pal.c(0));
            cpu.value.setText("RESTRICTED");
            d.append(" • CPU COUNTERS BLOCKED BY ANDROID");
        }
        cpu.detail.setText(d.toString());
    }

    private float thermalHeadroom = -1;

    /** Thermal status (API 29+) and headroom (API 30+), shown in the CPU detail line. */
    private void sampleThermal() {
        if (Build.VERSION.SDK_INT < 29) return;
        PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        String status;
        switch (pm.getCurrentThermalStatus()) {
            case PowerManager.THERMAL_STATUS_NONE: status = "THERMAL NOMINAL"; break;
            case PowerManager.THERMAL_STATUS_LIGHT: status = "THERMAL LIGHT"; break;
            case PowerManager.THERMAL_STATUS_MODERATE: status = "THERMAL MODERATE"; break;
            case PowerManager.THERMAL_STATUS_SEVERE: status = "THERMAL SEVERE"; break;
            case PowerManager.THERMAL_STATUS_CRITICAL: status = "THERMAL CRITICAL"; break;
            default: status = "THERMAL EMERGENCY"; break;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            float h = pm.getThermalHeadroom(0); // 0 = now; 1.0 = throttling threshold
            if (!Float.isNaN(h) && h >= 0) {
                thermalHeadroom = h;
                status += " (" + Math.round(h * 100) + "%)";
            }
        }
        thermalText = status;
    }

    // ---- memory & storage --------------------------------------------------------------------------

    /**
     * Shows memory from ActivityManager, preferring the kernel's MemAvailable (read in the
     * background) because it counts reclaimable cache as free.
     */
    private void showMem(Map<String, Long> m) {
        ActivityManager am = (ActivityManager) a.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long total = mi.totalMem, avail = mi.availMem;
        if (total <= 0) return;
        long kernelAvail = SysStats.meminfoBytes(m, "MemAvailable");
        if (kernelAvail >= 0) avail = kernelAvail;
        long used = Math.max(0, total - avail);
        mem.bar.set(used / (float) total, mi.lowMemory ? pal.alert : pal.c(1));
        mem.value.setText(SysStats.gb(avail) + " GB FREE");
        mem.detail.setText(SysStats.memDetail(used, total, SysStats.meminfoBytes(m, "SwapTotal"),
                SysStats.meminfoBytes(m, "SwapFree"), mi.lowMemory, mi.threshold));
        mem.detail.setTextColor(mi.lowMemory ? pal.alert : pal.textLo);
    }

    /** Reads free/total storage of the data partition in the background, then shows it. */
    private void sampleDisk() {
        final int gen = generation;
        SAMPLER.execute(new Runnable() {
            @Override public void run() {
                long t = -1, f = -1;
                try {
                    StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
                    t = fs.getTotalBytes();
                    f = fs.getAvailableBytes();
                } catch (RuntimeException ignored) {
                    // storage stats unavailable
                }
                final long total = t, free = f;
                a.handler().post(new Runnable() {
                    @Override public void run() { if (gen == generation && total > 0) showDisk(total, free); }
                });
            }
        });
    }

    private void showDisk(long total, long free) {
        float freeFrac = free / (float) total;
        disk.bar.set(1 - freeFrac, freeFrac < LOW_DISK ? pal.alert : pal.c(2));
        disk.value.setText(SysStats.gb(free) + " GB FREE");
        disk.detail.setText(SysStats.diskDetail(total, free));
        if (freeFrac < CRITICAL_DISK && !storageWarned && a.isResumedNow()) {
            storageWarned = true;
            Sfx.play(a, Sfx.SYS_WARNING);
            a.toast("STORAGE BELOW 5% • FREE UP SPACE");
        }
    }

    // ---- network -----------------------------------------------------------------------------------

    /** Connection type and link speed, plus throughput since the previous sample. */
    private void sampleNet() {
        long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes();
        long now = SystemClock.elapsedRealtime();
        String type = "OFFLINE", link = "";
        try {
            ConnectivityManager cm = (ConnectivityManager) a.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities c = n == null ? null : cm.getNetworkCapabilities(n);
            if (c != null) {
                type = c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WI-FI"
                        : c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "CELLULAR"
                        : c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "ETHERNET" : "CONNECTED";
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) type += " + VPN";
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) type += " (NO INTERNET)";
                int down = c.getLinkDownstreamBandwidthKbps();
                if (down > 0) link = " • LINK ~" + SysStats.rate(down * 1000 / 8.0);
            }
        } catch (Exception ignored) {
        }
        net.setText("SUBSPACE LINK • " + type + link);
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
            rates.setText("THROUGHPUT UNAVAILABLE ON THIS DEVICE");
            return;
        }
        if (rxPrev >= 0 && now > netAt) {
            double sec = (now - netAt) / 1000.0;
            double down = Math.max(0, rx - rxPrev) / sec, up = Math.max(0, tx - txPrev) / sec;
            rates.setText("▼ " + SysStats.rate(down) + "     ▲ " + SysStats.rate(up));
            graph.add((float) down, (float) up);
        } else {
            rates.setText("▼ …     ▲ …");
        }
        rxPrev = rx;
        txPrev = tx;
        netAt = now;
    }

    /** Device name, Android version and uptime. */
    private void sampleIdent() {
        long up = SystemClock.elapsedRealtime() / 60000;
        String name = null;
        try { name = Settings.Global.getString(a.getContentResolver(), Settings.Global.DEVICE_NAME); } catch (RuntimeException ignored) { }
        if (name == null || name.isEmpty()) name = Build.MODEL;
        ident.setText(name.toUpperCase(Locale.getDefault()) + "  •  ANDROID " + Build.VERSION.RELEASE + "  •  UPTIME " + SysStats.uptime(up));
    }

    /** Scrolling console traffic graph: download as bars, upload as a thin line, auto-scaled. */
    static class RateGraph extends View {
        private static final int N = 40;
        private final float[] down = new float[N], up = new float[N];
        private int count;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), base = new Paint();

        RateGraph(Context c, int downColor, int upColor) {
            super(c);
            bar.setColor(downColor);
            line.setColor(upColor);
            line.setStrokeWidth(Ui.dp(c, 2));
            line.setStrokeCap(Paint.Cap.ROUND);
            base.setColor(Ui.alpha(downColor, 0.25f));
        }

        /** Appends one sample (bytes per second) and redraws. */
        void add(float d, float u) {
            System.arraycopy(down, 1, down, 0, N - 1);
            System.arraycopy(up, 1, up, 0, N - 1);
            down[N - 1] = d;
            up[N - 1] = u;
            count = Math.min(N, count + 1);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            cv.drawRect(0, h - Ui.dp(getContext(), 2), w, h, base);
            float max = 64 * 1024; // floor the scale at 512 kbps so idle traffic stays small
            for (int i = 0; i < N; i++) max = Math.max(max, Math.max(down[i], up[i]));
            float slot = w / N, gap = Math.max(1, slot * 0.25f);
            for (int i = N - count; i < N; i++) {
                float bh = (h - 2) * (float) Math.sqrt(down[i] / max);
                float x = i * slot;
                if (bh > 0.5f) cv.drawRect(x, h - bh, x + slot - gap, h, bar);
            }
            for (int i = Math.max(N - count, 1); i < N; i++) {
                if (i - 1 < N - count) continue;
                float y0 = h - (h - 2) * (float) Math.sqrt(up[i - 1] / max), y1 = h - (h - 2) * (float) Math.sqrt(up[i] / max);
                cv.drawLine((i - 0.5f) * slot, y0, (i + 0.5f) * slot, y1, line);
            }
        }
    }
}
