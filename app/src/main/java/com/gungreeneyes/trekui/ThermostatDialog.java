package com.gungreeneyes.trekui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * console environmental control for a Nest thermostat: ambient reading, HVAC status, the target
 * temperature(s) with ▲/▼ and a draggable segmented scale, mode buttons (HEAT / COOL / HEAT•COOL /
 * OFF) and ECO. Changes are sent about a second after the last adjustment, then confirmed.
 *
 * <p>Opened from a home thermostat tile or CONFIG › DEVICES. The thermostat behind it is a
 * {@link Backend}: a Nest device ({@link NestBackend}) or a Home Assistant climate entity
 * ({@link HomeAssistant.Climate}). Network work goes through {@link Nest#async} (one background
 * thread) with the application context; replies are ignored once the dialog is closed.
 * Responsive: the scale's height follows the window height so the dialog fits landscape phones.
 * Setpoint snapping and the HEAT•COOL gap are pure functions ({@link #snap}, {@link #keepGap}).
 */
final class ThermostatDialog {
    /**
     * The thermostat a dialog controls. Every method blocks (it is called on a background thread)
     * and works in °C and the console mode names HEAT / COOL / HEATCOOL / OFF.
     */
    interface Backend {
        /** Reads the current state. */
        Nest.State read(Context app) throws java.io.IOException;
        /** Switches the HVAC mode. */
        void setMode(Context app, String mode) throws java.io.IOException;
        /** Engages or leaves ECO. */
        void setEco(Context app, boolean on) throws java.io.IOException;
        /** Sends the setpoint(s) that match {@code mode}. */
        void setTemps(Context app, String mode, double heatC, double coolC) throws java.io.IOException;
    }

    /** A Google Nest thermostat by SDM device name. */
    static final class NestBackend implements Backend {
        private final String device;

        NestBackend(String device) { this.device = device; }

        @Override public Nest.State read(Context app) throws java.io.IOException { return Nest.state(app, device); }
        @Override public void setMode(Context app, String mode) throws java.io.IOException { Nest.setMode(app, device, mode); }
        @Override public void setEco(Context app, boolean on) throws java.io.IOException { Nest.setEco(app, device, on); }
        @Override public void setTemps(Context app, String mode, double heatC, double coolC) throws java.io.IOException {
            Nest.setTemps(app, device, mode, heatC, coolC);
        }
    }

    /** Setpoint limits and the minimum HEAT•COOL gap, in °C (Nest's own limits). */
    static final double MIN_C = 9, MAX_C = 32, GAP_C = 1.7;
    /** Changes are sent this long after the last adjustment. */
    private static final long SEND_DELAY_MS = 1100;
    /** After a command, read the thermostat back after this long. */
    private static final long READBACK_MS = 1500;
    /** Scale width and its tallest height, in dp; the shortest it may shrink to. */
    private static final float SCALE_W_DP = 92, SCALE_MAX_H_DP = 290, SCALE_MIN_H_DP = 150;
    /** Share of the window height the scale may take. */
    private static final float SCALE_HEIGHT_SHARE = 0.42f;

    private final Context c;
    /** Application context for background jobs (never the Activity). */
    private final Context app;
    private final Palette pal;
    private final Backend device;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConsoleDialog.Built built;

    private Nest.State st;
    private double heatC = Double.NaN, coolC = Double.NaN;
    private boolean editingCool; // HEAT•COOL: which end ▲/▼ and the scale move
    private TextView ambientView, statusView, line, heatNum, coolNum, heatLbl, coolLbl;
    private LinearLayout setpoints, modes;
    private Scale scale;
    private boolean pending; // an adjustment is waiting to be sent
    private final Runnable send = new Runnable() { @Override public void run() { pushTemps(); } };

    private ThermostatDialog(Context c, Backend device) {
        this.c = c;
        this.app = c.getApplicationContext();
        this.pal = Prefs.get(c).palette();
        this.device = device;
    }

    /** Opens the control for an SDM device name, titled with its label. */
    static void show(Context c, String device, String label) {
        new ThermostatDialog(c, new NestBackend(device)).open(label);
    }

    /** Opens the control for any thermostat backend (e.g. a Home Assistant climate entity). */
    static void show(Context c, Backend backend, String label) {
        new ThermostatDialog(c, backend).open(label);
    }

    private void open(String label) {
        built = ConsoleDialog.base(c, "ENVIRONMENTAL CONTROL");
        LinearLayout b = built.body;
        TextView name = Ui.oneLine(Ui.bold(c, label, 16, pal.c(1)));
        name.setGravity(Gravity.END);
        b.addView(name);

        ambientView = Ui.bold(c, "AMBIENT —", 30, pal.textHi);
        b.addView(ambientView);
        statusView = Ui.text(c, "CONTACTING THERMOSTAT…", 14, pal.c(2));
        b.addView(statusView);

        LinearLayout body = new LinearLayout(c);
        body.setPadding(0, Ui.dp(c, Ui.M), 0, Ui.dp(c, Ui.GUTTER));
        setpoints = new LinearLayout(c);
        setpoints.setOrientation(LinearLayout.VERTICAL);
        body.addView(setpoints, new LinearLayout.LayoutParams(0, -2, 1));
        scale = new Scale(c);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(Ui.dp(c, SCALE_W_DP), scaleHeightPx());
        sl.leftMargin = Ui.dp(c, Ui.M);
        body.addView(scale, sl);
        b.addView(body);

        modes = new LinearLayout(c);
        modes.setOrientation(LinearLayout.VERTICAL);
        b.addView(modes);

        line = Ui.text(c, "", 13, pal.textLo);
        line.setPadding(0, Ui.dp(c, Ui.S), 0, Ui.dp(c, Ui.GUTTER));
        b.addView(line);
        TextView close = ConsoleDialog.option(c, "CLOSE CHANNEL", pal.c(2), new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        ConsoleDialog.addOption(built, close);
        built.dialog.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                if (pending) pushTemps(); // don't lose a pending change
                main.removeCallbacksAndMessages(null);
            }
        });
        built.dialog.show();
        Sfx.play(c, Sfx.OPEN);
        refresh(false);
    }

    /** Scale height: up to {@link #SCALE_MAX_H_DP}, but no more than a share of the window height. */
    private int scaleHeightPx() {
        ScreenMetrics m = ScreenMetrics.of(c);
        float dp = Math.max(SCALE_MIN_H_DP, Math.min(SCALE_MAX_H_DP, m.heightDp * SCALE_HEIGHT_SHARE));
        return m.px(dp);
    }

    private void finish() {
        Sfx.play(c, Sfx.CLOSE);
        built.dialog.dismiss();
    }

    // ---- data ---------------------------------------------------------------------------------------

    /** Reads the thermostat; quiet suppresses the error sound (automatic read-backs). */
    private void refresh(final boolean quiet) {
        Nest.async(new Nest.Job<Nest.State>() {
            @Override public Nest.State run() throws java.io.IOException { return device.read(app); }
        }, new Nest.Result<Nest.State>() {
            @Override public void done(Nest.State s, String error) {
                if (!built.dialog.isShowing()) return;
                if (error != null) {
                    statusView.setText("SENSOR LINK FAILED");
                    statusView.setTextColor(pal.alert);
                    line.setText(error);
                    if (!quiet) Sfx.play(c, Sfx.ERROR);
                    return;
                }
                st = s;
                if (!pending) { heatC = s.heatC; coolC = s.coolC; }
                render();
            }
        });
    }

    private boolean f() { return st == null || st.fahrenheit(); }

    private String fmt(double cel) {
        if (Double.isNaN(cel)) return "—";
        return f() ? String.valueOf(Math.round(Nest.toF(cel))) : String.format(Locale.US, "%.1f", Math.round(cel * 2) / 2.0);
    }

    private String unit() { return f() ? "°F" : "°C"; }

    /** Setpoints can change only online, out of ECO, in HEAT / COOL / HEAT•COOL. */
    private boolean adjustable() {
        return st != null && st.online() && !st.ecoOn() && ("HEAT".equals(st.mode) || "COOL".equals(st.mode) || "HEATCOOL".equals(st.mode));
    }

    /** Rebuilds readings, setpoint boxes and mode keys from the last state. */
    private void render() {
        ambientView.setText("AMBIENT " + fmt(st.ambientC) + unit());
        String hv = "HEATING".equals(st.hvac) ? "HEATING" : "COOLING".equals(st.hvac) ? "COOLING" : "IDLE";
        StringBuilder s = new StringBuilder(hv);
        if (st.humidity >= 0) s.append("  •  HUMIDITY ").append(st.humidity).append('%');
        if (!st.online()) s = new StringBuilder("THERMOSTAT OFFLINE");
        statusView.setText(s.toString());
        statusView.setTextColor("HEATING".equals(hv) ? pal.c(0) : "COOLING".equals(hv) ? pal.c(2) : pal.textLo);

        setpoints.removeAllViews();
        heatNum = coolNum = null;
        if (st.ecoOn()) {
            setpoints.addView(Ui.bold(c, "ECO MODE", 26, pal.c(4)));
            setpoints.addView(Ui.text(c, "HOLDING " + fmt(st.ecoHeatC) + "–" + fmt(st.ecoCoolC) + unit()
                    + "\nTURN ECO OFF TO SET A TEMPERATURE", 14, pal.textLo));
        } else if ("OFF".equals(st.mode)) {
            setpoints.addView(Ui.bold(c, "SYSTEM OFF", 28, pal.textLo));
            setpoints.addView(Ui.text(c, "CHOOSE HEAT OR COOL BELOW", 14, pal.textLo));
        } else {
            boolean range = "HEATCOOL".equals(st.mode);
            if ("HEAT".equals(st.mode) || range) addSetpoint(false, range);
            if ("COOL".equals(st.mode) || range) addSetpoint(true, range);
            if (!range) editingCool = "COOL".equals(st.mode);
        }
        renderModes();
        scale.invalidate();
        if (line.getText().length() == 0 || line.getCurrentTextColor() == pal.alert) {
            line.setTextColor(pal.textLo);
            line.setText(adjustable() ? "TAP ▲ ▼ OR DRAG THE SCALE • CHANGES SEND AUTOMATICALLY" : "");
        }
    }

    /** A setpoint box: label, value, ▲/▼; in HEAT•COOL tapping it selects which end the scale moves. */
    private void addSetpoint(final boolean cool, boolean range) {
        int color = cool ? pal.c(2) : pal.c(0);
        boolean selected = !range || editingCool == cool;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(c, Ui.M), Ui.dp(c, Ui.XS), Ui.dp(c, Ui.M), Ui.dp(c, Ui.S));
        box.setBackground(Ui.outline(c, pal.panel, Ui.alpha(color, selected ? 1f : 0.35f), 16, selected ? 2.5f : 1.2f));
        TextView lbl = Ui.bold(c, cool ? "COOL TO" : "HEAT TO", 15, color);
        TextView num = Ui.bold(c, fmt(cool ? coolC : heatC) + unit(), range ? 40 : 58, pal.textHi);
        box.addView(lbl);
        box.addView(num);
        LinearLayout arrows = new LinearLayout(c);
        TextView up = Ui.button(c, "▲", color, Ui.PILL, new View.OnClickListener() {
            @Override public void onClick(View v) { editingCool = cool; step(+1); }
        });
        TextView down = Ui.button(c, "▼", color, Ui.PILL, new View.OnClickListener() {
            @Override public void onClick(View v) { editingCool = cool; step(-1); }
        });
        up.setGravity(Gravity.CENTER);
        down.setGravity(Gravity.CENTER);
        up.setTextSize((20) * Skin.textScale());
        down.setTextSize((20) * Skin.textScale());
        up.setContentDescription((cool ? "Cooling" : "Heating") + " setpoint up");
        down.setContentDescription((cool ? "Cooling" : "Heating") + " setpoint down");
        arrows.addView(up, new LinearLayout.LayoutParams(0, Ui.dp(c, 46), 1));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, Ui.dp(c, 46), 1);
        dl.leftMargin = Ui.dp(c, Ui.GUTTER);
        arrows.addView(down, dl);
        box.addView(arrows);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { editingCool = cool; Ui.tap(v); render(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(c, Ui.S);
        setpoints.addView(box, lp);
        if (cool) { coolNum = num; coolLbl = lbl; } else { heatNum = num; heatLbl = lbl; }
    }

    /** Mode keys the thermostat supports, plus ECO when available. */
    private void renderModes() {
        modes.removeAllViews();
        LinearLayout row = new LinearLayout(c);
        String[] ids = {"HEAT", "COOL", "HEATCOOL", "OFF"};
        String[] names = {"HEAT", "COOL", "HEAT•COOL", "OFF"};
        int[] colors = {pal.c(0), pal.c(2), pal.c(5), pal.c(3)};
        for (int i = 0; i < ids.length; i++) {
            final String m = ids[i];
            if (!st.modes.isEmpty() && !st.modes.contains(m)) continue;
            boolean on = m.equals(st.mode) && !st.ecoOn();
            TextView b = Ui.button(c, names[i], on ? colors[i] : Ui.alpha(colors[i], 0.35f), Ui.PILL, new View.OnClickListener() {
                @Override public void onClick(View v) { changeMode(m); }
            });
            b.setGravity(Gravity.CENTER);
            b.setTextSize((14) * Skin.textScale());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1);
            if (row.getChildCount() > 0) lp.leftMargin = Ui.dp(c, Ui.GUTTER);
            row.addView(b, lp);
        }
        modes.addView(row);
        if (st.ecoAvailable) {
            TextView eco = Ui.button(c, st.ecoOn() ? "ECO • ON" : "ECO • OFF", st.ecoOn() ? pal.c(4) : Ui.alpha(pal.c(4), 0.35f),
                    Ui.PILL, new View.OnClickListener() {
                        @Override public void onClick(View v) { toggleEco(); }
                    });
            eco.setGravity(Gravity.CENTER);
            eco.setTextSize((14) * Skin.textScale());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 44));
            lp.topMargin = Ui.dp(c, Ui.GUTTER);
            modes.addView(eco, lp);
        }
    }

    // ---- adjusting ------------------------------------------------------------------------------------

    /** One ▲/▼ step: 1 °F, or 0.5 °C. */
    private double stepC() { return f() ? 5.0 / 9.0 : 0.5; }

    private void step(int dir) {
        if (!adjustable()) { Sfx.play(c, Sfx.DENY); return; }
        setTarget((editingCool ? coolC : heatC) + dir * stepC());
        Sfx.play(c, Sfx.TAP);
    }

    /** Snaps to whole °F / half °C, clamps, keeps the HEAT•COOL gap, and schedules the send. */
    private void setTarget(double v) {
        if (Double.isNaN(v)) v = Double.isNaN(st.ambientC) ? 20 : st.ambientC;
        v = snap(v, f());
        boolean range = "HEATCOOL".equals(st.mode);
        if (editingCool) {
            coolC = range ? keepGap(heatC, v, true) : v;
        } else {
            heatC = range ? keepGap(v, coolC, false) : v;
        }
        if (heatNum != null) heatNum.setText(fmt(heatC) + unit());
        if (coolNum != null) coolNum.setText(fmt(coolC) + unit());
        scale.invalidate();
        line.setTextColor(pal.c(4));
        line.setText("ADJUSTING…");
        main.removeCallbacks(send);
        pending = true;
        main.postDelayed(send, SEND_DELAY_MS);
    }

    /** Snaps a °C setpoint to whole °F (fahrenheit) or half °C, clamped to {@link #MIN_C}..{@link #MAX_C}. */
    static double snap(double celsius, boolean fahrenheit) {
        double v = fahrenheit ? Nest.toC(Math.round(Nest.toF(celsius))) : Math.round(celsius * 2) / 2.0;
        return Math.max(MIN_C, Math.min(MAX_C, v));
    }

    /**
     * Keeps HEAT•COOL setpoints at least {@link #GAP_C} apart by moving the end being edited.
     *
     * @param movingCool true when the cool end was changed (returns the new cool value), false
     *                   for the heat end (returns the new heat value)
     */
    static double keepGap(double heatC, double coolC, boolean movingCool) {
        if (movingCool) return !Double.isNaN(heatC) && coolC - heatC < GAP_C ? heatC + GAP_C : coolC;
        return !Double.isNaN(coolC) && coolC - heatC < GAP_C ? coolC - GAP_C : heatC;
    }

    /** Sends the pending setpoint(s) for the current mode. */
    private void pushTemps() {
        main.removeCallbacks(send);
        pending = false;
        if (st == null) return;
        final String mode = st.mode;
        final double h = heatC, k = coolC;
        line.setTextColor(pal.c(4));
        line.setText("TRANSMITTING…");
        Nest.async(new Nest.Job<Boolean>() {
            @Override public Boolean run() throws java.io.IOException { device.setTemps(app, mode, h, k); return true; }
        }, new Nest.Result<Boolean>() {
            @Override public void done(Boolean ok, String error) { afterCommand(error, "SETPOINT CONFIRMED"); }
        });
    }

    /** Switches mode (leaving ECO first when needed). */
    private void changeMode(final String m) {
        if (st == null) return;
        if (m.equals(st.mode) && !st.ecoOn()) { Ui.tap(scale); return; }
        Sfx.play(c, Sfx.TAP);
        line.setTextColor(pal.c(4));
        line.setText("TRANSMITTING " + m + "…");
        final boolean leaveEco = st.ecoOn();
        Nest.async(new Nest.Job<Boolean>() {
            @Override public Boolean run() throws java.io.IOException {
                if (leaveEco) device.setEco(app, false);
                device.setMode(app, m);
                return true;
            }
        }, new Nest.Result<Boolean>() {
            @Override public void done(Boolean ok, String error) { afterCommand(error, ("HEATCOOL".equals(m) ? "HEAT•COOL" : m) + " MODE CONFIRMED"); }
        });
    }

    private void toggleEco() {
        if (st == null) return;
        Sfx.play(c, Sfx.TAP);
        final boolean on = !st.ecoOn();
        line.setTextColor(pal.c(4));
        line.setText("TRANSMITTING…");
        Nest.async(new Nest.Job<Boolean>() {
            @Override public Boolean run() throws java.io.IOException { device.setEco(app, on); return true; }
        }, new Nest.Result<Boolean>() {
            @Override public void done(Boolean ok, String error) { afterCommand(error, on ? "ECO ENGAGED" : "ECO DISENGAGED"); }
        });
    }

    /** Reports a command result and schedules a read-back of what the thermostat now holds. */
    private void afterCommand(String error, String okText) {
        if (!built.dialog.isShowing()) {
            if (error == null) Sfx.play(c, Sfx.CONFIRM);
            return;
        }
        if (error != null) {
            Sfx.play(c, Sfx.ERROR);
            line.setTextColor(pal.alert);
            line.setText("UNABLE TO COMPLY • " + error);
        } else {
            Sfx.play(c, Sfx.CONFIRM);
            line.setTextColor(pal.c(2));
            line.setText(okText);
        }
        // read back what the thermostat actually holds
        main.postDelayed(new Runnable() { @Override public void run() { if (built.dialog.isShowing()) refresh(true); } }, READBACK_MS);
    }

    // ---- the console scale ------------------------------------------------------------------------------

    /** Vertical segmented scale (warm at the top): ambient marker, setpoint band; drag to adjust. */
    private final class Scale extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private static final int SEGMENTS = 24;

        Scale(Context ctx) {
            super(ctx);
            p.setTypeface(Ui.font(ctx, true));
            setContentDescription("Temperature scale. Drag to adjust.");
        }

        private float yFor(double cel, float top, float h) {
            double t = (cel - MIN_C) / (MAX_C - MIN_C);
            return (float) (top + h - Math.max(0, Math.min(1, t)) * h);
        }

        private double celFor(float y, float top, float h) {
            double t = 1 - (y - top) / h;
            return MIN_C + Math.max(0, Math.min(1, t)) * (MAX_C - MIN_C);
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float top = dp(14), bot = h - dp(14), sh = bot - top, barW = w * 0.46f;
            float gap = dp(Ui.XS), segH = (sh - gap * (SEGMENTS - 1)) / SEGMENTS;
            boolean active = adjustable();
            for (int i = 0; i < SEGMENTS; i++) {
                float y0 = top + i * (segH + gap);
                double cel = celFor(y0 + segH / 2, top, sh);
                boolean lit = false;
                int col;
                if (st != null && "HEATCOOL".equals(st.mode)) lit = !Double.isNaN(heatC) && !Double.isNaN(coolC) && cel >= heatC && cel <= coolC;
                else if (st != null && "HEAT".equals(st.mode)) lit = !Double.isNaN(heatC) && cel <= heatC;
                else if (st != null && "COOL".equals(st.mode)) lit = !Double.isNaN(coolC) && cel >= coolC;
                col = cel > 22 ? pal.c(0) : cel > 18 ? pal.c(4) : pal.c(2);
                p.setColor(lit && active ? col : Ui.alpha(col, 0.22f));
                r.set(0, y0, barW, y0 + segH);
                canvas(cv, r, i == 0 ? 1 : i == SEGMENTS - 1 ? 2 : 0);
            }
            p.setTextSize(dp(12) * Skin.textScale());
            p.setColor(pal.textLo);
            for (double cel : new double[]{MAX_C, (MIN_C + MAX_C) / 2, MIN_C}) {
                cv.drawText(fmt(cel) + "°", barW + dp(6), yFor(cel, top, sh) + dp(4), p);
            }
            if (st != null && !Double.isNaN(st.ambientC)) {
                float y = yFor(st.ambientC, top, sh);
                p.setColor(pal.textHi);
                p.setStrokeWidth(dp(3));
                cv.drawLine(-dp(2), y, barW + dp(2), y, p);
                p.setTextSize(dp(11) * Skin.textScale());
                cv.drawText("NOW", barW + dp(6), y - dp(6), p);
            }
            if (active) {
                if (!Double.isNaN(heatC) && !"COOL".equals(st.mode)) marker(cv, yFor(heatC, top, sh), barW, pal.c(0));
                if (!Double.isNaN(coolC) && !"HEAT".equals(st.mode)) marker(cv, yFor(coolC, top, sh), barW, pal.c(2));
            }
        }

        private void canvas(Canvas cv, RectF rect, int cap) {
            float rad = rect.width() / 2;
            if (cap == 0) { cv.drawRect(rect, p); return; }
            cv.drawRoundRect(rect, Math.min(rad, rect.height()), Math.min(rad, rect.height()), p);
        }

        private void marker(Canvas cv, float y, float barW, int color) {
            p.setColor(color);
            r.set(barW + dp(2), y - dp(9), getWidth(), y + dp(9));
            cv.drawRoundRect(r, dp(9), dp(9), p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (!adjustable()) {
                if (e.getAction() == MotionEvent.ACTION_UP) Sfx.play(c, Sfx.DENY);
                return true;
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            float top = dp(14), sh = getHeight() - dp(28);
            double cel = celFor(e.getY(), top, sh);
            if (e.getAction() == MotionEvent.ACTION_DOWN && "HEATCOOL".equals(st.mode)) {
                editingCool = Math.abs(cel - coolC) < Math.abs(cel - heatC);
            }
            double before = editingCool ? coolC : heatC;
            setTarget(cel);
            double after = editingCool ? coolC : heatC;
            if (Math.abs(after - before) > 0.01 || e.getAction() == MotionEvent.ACTION_DOWN) Sfx.play(c, Sfx.TAP_SOFT);
            if (e.getAction() == MotionEvent.ACTION_UP) render();
            return true;
        }

        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    }
}
