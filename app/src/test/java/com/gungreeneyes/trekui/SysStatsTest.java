package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.Map;

/** {@link SysStats}: /proc parsing, load maths and the panel's text formatting (pure JVM). */
public class SysStatsTest {

    @Test
    public void parseStat_aggregateLine() {
        String stat = "cpu  100 0 50 800 50 0 0 0 0 0\ncpu0 1 2 3 4 5 6 7 8\nintr 1 2\n";
        long[] r = SysStats.parseStat(stat);
        assertThat(r).asList().containsExactly(850L, 1000L).inOrder();
    }

    @Test
    public void parseStat_rejectsMissingOrBroken() {
        assertThat(SysStats.parseStat(null)).isNull();
        assertThat(SysStats.parseStat("intr 1 2\n")).isNull();
        assertThat(SysStats.parseStat("cpu  a b c\n")).isNull();
        assertThat(SysStats.parseStat("cpu  0 0 0 0\n")).isNull();
    }

    @Test
    public void load_betweenSamples() {
        assertThat(SysStats.load(new long[]{800, 1000}, new long[]{850, 1100})).isWithin(1e-6f).of(0.5f);
        assertThat(SysStats.load(null, new long[]{1, 2})).isEqualTo(-1f);
        assertThat(SysStats.load(new long[]{1, 5}, new long[]{1, 5})).isEqualTo(-1f);
    }

    @Test
    public void parseMeminfo_andBytes() {
        Map<String, Long> m = SysStats.parseMeminfo("MemTotal:  8000000 kB\nMemAvailable: 2000000 kB\nbogus line\nSwapTotal: 0 kB\n");
        assertThat(m).containsEntry("MemTotal", 8000000L);
        assertThat(SysStats.meminfoBytes(m, "MemAvailable")).isEqualTo(2000000L * 1024);
        assertThat(SysStats.meminfoBytes(m, "Missing")).isEqualTo(-1L);
        assertThat(SysStats.meminfoBytes(null, "MemTotal")).isEqualTo(-1L);
        assertThat(SysStats.parseMeminfo("")).isNull();
    }

    @Test
    public void parseLong() {
        assertThat(SysStats.parseLong(" 1804800\n")).isEqualTo(1804800L);
        assertThat(SysStats.parseLong("x")).isEqualTo(-1L);
        assertThat(SysStats.parseLong(null)).isEqualTo(-1L);
    }

    @Test
    public void formatting() {
        assertThat(SysStats.gb(12_345_678_901L)).isEqualTo("12.3");
        assertThat(SysStats.gb(256_000_000_000L)).isEqualTo("256");
        assertThat(SysStats.ghz(2_841_600)).isEqualTo("2.84");
        assertThat(SysStats.rate(125_000_000)).isEqualTo("1.00 GBPS");
        assertThat(SysStats.rate(1_250_000)).isEqualTo("10.0 MBPS");
        assertThat(SysStats.rate(12_500_000)).isEqualTo("100 MBPS");
        assertThat(SysStats.rate(1_000)).isEqualTo("8 KBPS");
        assertThat(SysStats.rate(10)).isEqualTo("80 BPS");
        assertThat(SysStats.percent(0.426f)).isEqualTo(43);
        assertThat(SysStats.uptime(3 * 1440 + 4 * 60 + 7)).isEqualTo("3D 04H 07M");
        assertThat(SysStats.uptime(-5)).isEqualTo("0D 00H 00M");
    }

    @Test
    public void cpuDetail() {
        SysStats.Freq f = new SysStats.Freq();
        assertThat(SysStats.cpuDetail(8, f, "")).isEqualTo("8 CORES");
        f.readable = 8;
        f.peakKHz = 2_000_000;
        f.maxKHz = 3_000_000;
        assertThat(SysStats.cpuDetail(8, f, "THERMAL NOMINAL")).isEqualTo("8 CORES • PEAK 2.00 / 3.00 GHZ • THERMAL NOMINAL");
    }

    @Test
    public void memDetail() {
        assertThat(SysStats.memDetail(4_000_000_000L, 8_000_000_000L, -1, -1, false, 500_000_000L))
                .isEqualTo("4.0 OF 8.0 GB IN USE (50%) • LOW AT 0.5 GB");
        assertThat(SysStats.memDetail(4_000_000_000L, 8_000_000_000L, 2_000_000_000L, 1_000_000_000L, true, 0))
                .isEqualTo("4.0 OF 8.0 GB IN USE (50%) • SWAP 1.0/2.0 GB • LOW MEMORY");
    }

    @Test
    public void diskDetail() {
        assertThat(SysStats.diskDetail(100_000_000_000L, 25_000_000_000L)).isEqualTo("75.0 OF 100 GB USED (75%)");
        assertThat(SysStats.diskDetail(0, 0)).isEqualTo("0.0 OF 0.0 GB USED (0%)");
    }
}
