package com.gungreeneyes.trekui;

import android.content.Intent;
import android.os.BatteryManager;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Home panel "POWER SYSTEMS": battery percentage with a segmented bar (pulsing while charging),
 * charging state and source, temperature, voltage, health and time-to-full. Fed by MainActivity's
 * battery broadcast ({@link #onBattery}); tapping opens the system battery usage screen.
 *
 * <p>The wording is built by pure static functions ({@link #stateText}, {@link #detailText})
 * unit-tested in {@code PowerPanelTest}.
 */
class PowerPanel extends Panel {
    /** At or below this level, unplugged, the panel shows a low-power warning. */
    static final int LOW_LEVEL = 15;
    /** BatteryManager.BATTERY_PLUGGED_DOCK (API 33); defined here so older SDK levels compile the same. */
    static final int PLUGGED_DOCK = 8;
    private TextView pct, state, detail;
    private SegBar bar;
    private boolean charging;
    private boolean resumed;

    PowerPanel(MainActivity a, int accent) { super(a, accent); }

    @Override public String id() { return Prefs.W_POWER; }
    @Override public String title() { return "POWER SYSTEMS"; }

    @Override
    public void build() {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        pct = Ui.bold(a, "--%", 50, pal.textHi);
        row.addView(pct);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(a, Ui.L), 0, 0, 0);
        state = Ui.oneLine(Ui.bold(a, "READING…", 18, accent));
        detail = Ui.text(a, "", 13, pal.textLo);
        detail.setPadding(0, Ui.dp(a, Ui.XS), 0, 0);
        col.addView(state);
        col.addView(detail);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        card.body.addView(row);
        bar = new SegBar(a, 20, accent, Ui.alpha(accent, 0.16f));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, Ui.dp(a, 14));
        bl.topMargin = Ui.dp(a, Ui.S);
        card.body.addView(bar, bl);
        card.body.setOnClickListener(Ui.feedback(new View.OnClickListener() {
            @Override public void onClick(View v) { a.startSafe(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY), v); }
        }));
        card.status.setText("TAP FOR USAGE");
    }

    @Override public void resume() { resumed = true; bar.setPulse(charging); }
    @Override public void pause() { resumed = false; bar.setPulse(false); }

    @Override
    public void onBattery(Intent b) {
        int level = MainActivity.batteryPct(b);
        int status = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int volt = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        int health = b.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING;

        pct.setText(level < 0 ? "--%" : level + "%");
        boolean low = isLow(level, plugged);
        pct.setTextColor(low ? pal.alert : pal.textHi);
        state.setText(stateText(level, status, plugged));
        state.setTextColor(low ? pal.alert : accent);

        StringBuilder d = new StringBuilder(detailText(temp, volt, health));
        if (charging && Build.VERSION.SDK_INT >= 28) {
            BatteryManager bm = (BatteryManager) a.getSystemService(android.content.Context.BATTERY_SERVICE);
            long ms = bm != null ? bm.computeChargeTimeRemaining() : -1;
            if (ms > 0) {
                long m = ms / 60000;
                d.append(String.format(Locale.US, "\nFULL IN %d:%02d", m / 60, m % 60));
            }
        }
        detail.setText(d.toString());
        bar.set(level < 0 ? 0 : level / 100f, low ? pal.alert : accent);
        if (resumed) bar.setPulse(charging);
    }

    /** Low-power warning: known level at or below {@link #LOW_LEVEL} and not plugged in. */
    static boolean isLow(int level, int plugged) {
        return level >= 0 && level <= LOW_LEVEL && plugged == 0;
    }

    /** Power source for an EXTRA_PLUGGED value ("" when unplugged or unknown). */
    static String sourceName(int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "AC MAINS";
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "INDUCTION (WIRELESS)";
            case PLUGGED_DOCK: return "DOCK";
            default: return "";
        }
    }

    /** The state line: FULLY CHARGED, CHARGING • source, CONNECTED • source, LOW POWER WARNING or ON INTERNAL POWER. */
    static String stateText(int level, int status, int plugged) {
        String src = sourceName(plugged);
        if (status == BatteryManager.BATTERY_STATUS_FULL || (plugged != 0 && level >= 100)) return "FULLY CHARGED";
        if (status == BatteryManager.BATTERY_STATUS_CHARGING) return "CHARGING • " + src;
        if (plugged != 0) return "CONNECTED • " + src;
        return isLow(level, plugged) ? "LOW POWER WARNING" : "ON INTERNAL POWER";
    }

    /**
     * The detail line: temperature (tenths of °C), voltage (mV, or V on devices that report volts)
     * and health. Unknown temperature is Integer.MIN_VALUE; unknown voltage is 0 or less.
     */
    static String detailText(int tempTenths, int volt, int health) {
        StringBuilder d = new StringBuilder();
        if (tempTenths != Integer.MIN_VALUE) d.append(String.format(Locale.US, "TEMP %.1f°C", tempTenths / 10f));
        if (volt > 0) {
            if (d.length() > 0) d.append("  •  ");
            d.append(String.format(Locale.US, "%.2f V", volt > 1000 ? volt / 1000f : volt));
        }
        if (d.length() > 0) d.append("  •  ");
        return d.append(healthName(health)).toString();
    }

    /** console wording for a BatteryManager health constant. */
    static String healthName(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "HEALTH NOMINAL";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "OVERHEAT";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "CELL FAILURE";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "OVER VOLTAGE";
            case BatteryManager.BATTERY_HEALTH_COLD: return "COLD";
            default: return "HEALTH UNKNOWN";
        }
    }
}
