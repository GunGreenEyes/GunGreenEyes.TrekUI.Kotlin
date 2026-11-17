package com.gungreeneyes.trekui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * BRIDGE STATIONS: six playful consoles reached from INFO › STATIONS on the deploying home
 * layouts. Each one is an animated display with controls that act on it, drawn in the current
 * interface's style, with sounds chosen by role.
 *
 *  OPS          power distribution across ship systems, transporter, hailing
 *  CONN         heading and speed with a starfield that follows the warp factor
 *  TACTICAL     target reticle, shields, pulse beams / torpedoes, red alert
 *  ENGINEERING  warp core output, authorisation, core ejection (and its return)
 *  SCIENCE      sensor sweep with contacts, range, contact analysis, probe
 *  MEDICAL      scan bed: heart trace and vitals, scan, injector
 *
 * Imperial and Collective get their own names for the stations and some controls. Nothing here is real
 * ship data; it is a toy, and says so on screen ("SIMULATION").
 *
 * Sounds: every control plays a ROLE ("beam", "warp_engage"...). A role resolves to the owner's
 * choice for it (long-press any control), else the first imported clip whose name matches the
 * role's keywords (so downloaded clips imported in CONFIG › AUDIO are picked up automatically),
 * else a bundled clip that suits it.
 *
 * <p>Responsive: the display's height follows the window (shorter on landscape phones) and wide
 * windows get three controls per row instead of two. The OPS power maths is pure
 * ({@link Ops#redistribute}) and unit-tested.
 */
final class Stations {
    /** Display height at the design scale, and the least it may shrink to, in dp. */
    private static final float SIM_HEIGHT_DP = 230, SIM_MIN_HEIGHT_DP = 150;
    /** Share of the window height the display may take. */
    private static final float SIM_HEIGHT_SHARE = 0.45f;
    /** Control key height, in dp. */
    private static final float CONTROL_DP = 46;

    /** MainActivity: lets the owner pick sounds for controls. */
    interface Host {
        /** Let the owner choose the sound for a role (long-press on a control). */
        void assignSound(String role, String title);
    }

    /** A station page: name, sidebar glyph, colour and its console view. */
    static final class Station {
        final String name, glyph;
        final int color;
        final View view;

        Station(String name, String glyph, int color, View view) {
            this.name = name;
            this.glyph = glyph;
            this.color = color;
            this.view = view;
        }
    }

    private Stations() {}

    /** Title of the stations page in the current interface. */
    static String pageTitle() { return Skin.imperial() ? "BATTLE STATIONS" : Skin.collective() ? "SUBUNIT FUNCTIONS" : "BRIDGE STATIONS"; }

    static String entryName() { return Skin.collective() ? "SUBUNITS" : "STATIONS"; }

    private static String n(String command, String imperial, String collective) { return Skin.imperial() ? imperial : Skin.collective() ? collective : command; }

    /** The six consoles, named for the current interface. */
    static List<Station> build(Context c, Palette p, Host host) {
        List<Station> out = new ArrayList<>();
        out.add(new Station(n("OPS", "SYSTEMS", "CORE NEXUS"), "computer", p.c(0), new Console(c, p, host, new Ops(c, p))));
        out.add(new Station(n("CONN", "HELM", "CONDUIT DRIVE"), "maps", p.c(1), new Console(c, p, host, new Conn(c, p))));
        out.add(new Station(n("TACTICAL", "WEAPONS", "DEFENSE GRID"), "alert", p.c(3), new Console(c, p, host, new Tactical(c, p))));
        out.add(new Station(n("ENGINEERING", "ENGINEERING", "POWER NODES"), "settings", p.c(2), new Console(c, p, host, new Engineering(c, p))));
        out.add(new Station(n("SCIENCE", "SENSORS", "SENSOR ARRAY"), "search", p.c(4), new Console(c, p, host, new Science(c, p))));
        out.add(new Station(n("MEDICAL", "MEDICAL", "REGENERATION"), "fitness", p.c(5), new Console(c, p, host, new Medical(c, p))));
        return out;
    }

    // ---- sounds by role ---------------------------------------------------------------------------------

    /** keywords (matched against imported clip names), then bundled fallbacks per faction. */
    private static String[] keywords(String role) {
        switch (role) {
            case "beam": return new String[]{"PULSE", "BEAM", "LASER", "DISRUPTOR"};
            case "torpedo": return new String[]{"TORPEDO"};
            case "shields": return new String[]{"SHIELDS UP", "SHIELD", "FORCEFIELD", "FORCE FIELD"};
            case "red_alert": return new String[]{"RED ALERT"};
            case "warp": return new String[]{"WARP ENGAGE", "JUMP TO WARP", "GO TO WARP", "WARP"};
            case "impulse": return new String[]{"IMPULSE"};
            case "all_stop": return new String[]{"ALL STOP", "WARP DOWN", "POWER DOWN"};
            case "course": return new String[]{"HELM", "CONSOLE", "COMPUTER BEEP"};
            case "scan": return new String[]{"SENSOR", "SCAN", "SCIENCE"};
            case "probe": return new String[]{"PROBE", "LAUNCH"};
            case "scanner": return new String[]{"MEDICAL SCAN", "SCANNER", "BIO SCAN", "SCAN BED"};
            case "injector": return new String[]{"INJECTOR", "INJECT"};
            case "eject": return new String[]{"EJECT", "CORE EJECT"};
            case "core": return new String[]{"WARP CORE", "ENGINE ROOM", "ENGINEERING"};
            case "authorize": return new String[]{"AUTHORIZ", "COMMAND CODE"};
            case "reroute": return new String[]{"POWER TRANSFER", "COMPUTER", "PANEL"};
            case "transporter": return new String[]{"TRANSPORTER", "ENERGIZE", "BEAM"};
            case "hail": return new String[]{"HAILING", "HAIL"};
            case "alert": return new String[]{"ALERT"};
            case "tractor": return new String[]{"TRACTOR"};
            case "warp9": return new String[]{"WARP FLASH", "WARP"};
            case "cloak": return new String[]{"CLOAK", "POWER LOSS"};
            case "regen": return new String[]{"REGENERATION"};
            case "comm": return new String[]{"CHIRP", "COMMUNICATOR"};
            case "boot_start": return Skin.imperial() ? new String[]{"IMPERIAL BRIDGE", "IMPERIAL"} : Skin.collective() ? new String[]{"COLLECTIVE VESSEL", "COLLECTIVE"} : new String[0];
            case "boot_ready": return Skin.imperial() ? new String[]{"IMPERIAL ALERT", "BATTLE STATIONS"} : Skin.collective() ? new String[]{"COLLECTIVE COMPUTER", "COLLECTIVE"} : new String[0];
            default: return new String[0];
        }
    }

    /** Bundled clip used when nothing better is available for a role. */
    private static String fallback(String role) {
        boolean k = Skin.imperial(), b = Skin.collective();
        switch (role) {
            case "shields": return "alt_forcefield_touch";
            case "red_alert": return "alert_red_tng";
            case "alert": return "alert_warning";
            case "warp": return b ? "alt_borg_computer" : "ui_screen_open";
            case "impulse": return "ui_panel_open";
            case "all_stop": return "ui_screen_close";
            case "scan": case "scanner": return b ? "alt_borg_computer" : "system_transfer_complete";
            case "eject": return "voice_decoupling_sequence";
            case "authorize": return "voice_command_codes_verified";
            case "transporter": return k ? "alt_klingon_transporter" : "tng_transporter";
            case "hail": return "comms_hailing_open";
            case "beam": case "torpedo": case "probe": return "alert_general_short";
            case "injector": case "reroute": case "course": case "core": return "ui_button_tap";
            case "boot_start": return b ? "alt_borg_computer" : "ui_screen_open";
            case "boot_mid": return "none";
            case "tractor": case "warp9": return b ? "alt_borg_computer" : "ui_screen_open";
            case "cloak": return "ui_screen_close";
            case "regen": return "alt_borg_computer";
            case "comm": return "ui_button_tap";
            case "boot_ready": return b ? "system_transfer_complete" : "alert_general_short";
            default: return "ui_button_tap";
        }
    }

    /**
     * Hand-picked clips from the downloadable source library for each role and interface, best first
     * (used when present in the build; otherwise the keyword search and bundled fallbacks apply).
     */
    private static String[] picks(String role) {
        boolean k = Skin.imperial(), b = Skin.collective();
        switch (role) {
            case "beam": return new String[]{k ? "tk_wpn_disruptor" : b ? "tk_wpn_weapons_2" : "tk_wpn_phaser_1"};
            case "tractor": return new String[]{"tk_misc_tractor_beam_1"};
            case "torpedo": return new String[]{"tk_wpn_torpedo_1"};
            case "shields": return new String[]{b ? "alt_forcefield_touch" : "tk_misc_powering_up"};
            case "red_alert": return new String[]{b ? "tk_cvox_tactical_alert_vessel_approaching" : "tk_alrt_red_alert_3"};
            case "warp": return new String[]{b ? "tk_warp_warp_flash" : "tk_warp_warp_2"};
            case "warp9": return new String[]{"tk_warp_warp_flash"};
            case "impulse": return new String[]{"tk_amb_engine_2"};
            case "all_stop": return new String[]{"tk_warp_warp_out_1"};
            case "course": return new String[]{"tk_cvox_helm_2"};
            case "cloak": return new String[]{"tk_misc_power_loss"};
            case "scan": return new String[]{b ? "tk_calrt_alert_26_sensors" : "tk_cvox_sensors"};
            case "probe": return new String[]{"tk_wpn_torpedo_3"};
            case "scanner": return new String[]{b ? "tk_cvox_diagnostic_complete" : "tk_tric_tricorder_5"};
            case "injector": return new String[]{"tk_door_swoosh"};
            case "regen": return new String[]{"tk_cvox_regeneration_cycle_complete"};
            case "eject": return new String[]{"tk_cvox_initiating_decoupling_sequence"};
            case "core": return new String[]{"tk_misc_engineering_device_3"};
            case "authorize": return new String[]{b ? "tk_cvox_command_codes_verified" : "tk_cvox_security_authorization_accepted"};
            case "reroute": return new String[]{"tk_cui_input_ok_1"};
            case "transporter": return new String[]{k ? "alt_klingon_transporter" : b ? "tk_trn_transporter_2" : "tk_trn_transporter_5"};
            case "hail": return new String[]{k ? "tk_ccomm_hail_alert_1" : b ? "tk_ccomm_communications_star_trransmission" : "tk_ccomm_hailing_frequencies_open_1"};
            case "comm": return new String[]{b ? "tk_ccomm_incoming_transmission" : "tk_comm_chirp_1"};
            case "alert": return new String[]{b ? "alt_borg_computer" : "tk_calrt_damage_alarm"};
            case "boot_start": return new String[]{k ? "tk_wpn_disruptor" : "tk_misc_powering_up"};
            case "boot_mid": return new String[]{k ? "tk_misc_powering_up" : "tk_cui_computer_beep_sequence_1"};
            case "boot_ready": return new String[]{k ? "tk_wpn_weapons_2" : "tk_cvox_regeneration_cycle_complete"};
            default: return new String[0];
        }
    }

    /** Clip for a role: the owner's choice, a hand-picked clip, an imported match, then the fallback. */
    static String clipFor(Context c, String role) {
        String own = Prefs.get(c).s("stnSnd_" + role, null);
        if (own != null) return own;
        for (String pk : picks(role)) if (Sfx.available(c, pk)) return pk; // downloaded, bundled or synthesized
        List<SoundLibrary.Entry> imported = SoundLibrary.imported(c);
        for (String kw : keywords(role)) {
            for (SoundLibrary.Entry e : imported) {
                if (e.label != null && e.label.toUpperCase(Locale.US).contains(kw)) return e.id;
            }
        }
        return fallback(role);
    }

    /** Plays the clip for a role ("none" plays nothing). */
    static void play(Context c, String role) {
        String clip = clipFor(c, role);
        // library not downloaded: start-up still sounds, with the interface's synthesized power-up
        if ("boot_start".equals(role) && !"none".equals(clip) && !Sfx.available(c, clip)) clip = Sfx.synthBootFor(Skin.faction);
        if (!"none".equals(clip)) Sfx.playClip(c, clip, false);
    }

    // ---- console shell: title, display, controls ------------------------------------------------------

    /** A control on a console: label, sound role, and what it does to the display. */
    static final class Control {
        final String label, role;
        final Runnable action;

        Control(String label, String role, Runnable action) {
            this.label = label;
            this.role = role;
            this.action = action;
        }
    }

    /** Base for a station display: a canvas view that animates while it is on screen. */
    abstract static class Sim extends View implements HomeGestureDetector.OwnsTouches {
        final Palette p;
        final float d;
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF r = new RectF();
        final Path path = new Path();
        final Random rnd = new Random();
        long last;
        String status = "";

        Sim(Context c, Palette p) {
            super(c);
            this.p = p;
            d = c.getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            text.setTypeface(Ui.font(c, false));
        }

        abstract String title();

        abstract List<Control> controls();

        /** Advance the simulation by dt seconds. */
        void step(float dt) {}

        abstract void paint(Canvas cv, float w, float h);

        float dp(float v) { return v * d; }

        int col(int i) { return Ui.cloak ? Ui.alpha(p.c(i), 0.6f) : Skin.glow(p.c(i)); }

        void say(String s) { status = s; invalidate(); }

        void text(Canvas cv, String s, float x, float y, float sp, int color, Paint.Align a, boolean heavy) {
            text.setTypeface(Ui.font(getContext(), heavy));
            text.setTextSize(Ui.px(getContext(), sp));
            text.setTextAlign(a);
            text.setColor(color);
            cv.drawText(s, x, y, text);
        }

        /** The display's frame, in the interface's style. */
        void frame(Canvas cv, float w, float h) {
            float m = dp(2);
            if (Skin.imperial()) {
                Path o = ImperialDraw.octagon(new Path(), m, m, w - m, h - m, dp(18), true, true);
                Path in = ImperialDraw.octagon(new Path(), m + dp(4), m + dp(4), w - m - dp(4), h - m - dp(4), dp(16), true, true);
                cv.save();
                cv.clipPath(in);
                r.set(0, 0, w, h);
                ImperialDraw.grid(cv, r, dp(22), ImperialDraw.RED, 0.1f, d);
                cv.restore();
                ImperialDraw.frame(cv, o, in, ImperialDraw.RED, d, 0.8f);
            } else if (Skin.collective()) {
                line.setStrokeWidth(dp(1.2f));
                line.setColor(Ui.alpha(p.c(0), 0.45f));
                r.set(m, m, w - m, h - m);
                cv.drawRoundRect(r, dp(18), dp(18), line);
                CollectiveDraw.node(cv, dp(14), dp(14), dp(5), p.c(0), d, 0.9f);
                CollectiveDraw.via(cv, w - dp(14), h - dp(14), dp(3), p.c(0), d, 0.9f);
            } else {
                line.setStrokeWidth(dp(2));
                line.setColor(p.c(1));
                r.set(m, m, w - m, h - m);
                cv.drawRoundRect(r, dp(16), dp(16), line);
                fill.setColor(p.c(0));
                r.set(dp(18), 0, dp(58), dp(6));
                cv.drawRoundRect(r, dp(3), dp(3), fill);
                fill.setColor(p.c(3));
                r.set(dp(64), 0, dp(84), dp(6));
                cv.drawRoundRect(r, dp(3), dp(3), fill);
            }
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            last = SystemClock.uptimeMillis();
        }

        @Override protected void onDraw(Canvas cv) {
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.1f, (now - last) / 1000f);
            last = now;
            boolean animate = Ui.animate(getContext());
            step(animate ? dt : 0.05f);
            float w = getWidth(), h = getHeight();
            frame(cv, w, h);
            paint(cv, w, h);
            text(cv, "SIMULATION", w - dp(14), h - dp(10), 9, Ui.alpha(p.textLo, 0.6f), Paint.Align.RIGHT, false);
            if (status.length() > 0) text(cv, status, dp(16), h - dp(10), 11, p.textHi, Paint.Align.LEFT, true);
            if (animate && isShown()) postInvalidateOnAnimation();
        }
    }

    /** Title, display and a grid of controls; long-press a control to choose its sound. */
    static final class Console extends LinearLayout {
        Console(Context c, Palette p, final Host host, final Sim sim) {
            super(c);
            setOrientation(VERTICAL);
            ScreenMetrics m = ScreenMetrics.of(c);
            TextView t = Ui.bold(c, sim.title(), 18, p.c(0));
            t.setPadding(Ui.dp(c, Ui.S), 0, 0, Ui.dp(c, Ui.XS));
            addView(t, new LayoutParams(-1, -2));
            float simDp = Math.max(SIM_MIN_HEIGHT_DP, Math.min(SIM_HEIGHT_DP * m.uiScale, m.heightDp * SIM_HEIGHT_SHARE));
            LayoutParams sl = new LayoutParams(-1, m.px(simDp));
            addView(sim, sl);
            List<Control> cs = sim.controls();
            final int perRow = m.isTwoPane() ? 3 : 2;
            LinearLayout row = null;
            int k = 0;
            for (final Control ctl : cs) {
                int col = k % perRow;
                if (col == 0) {
                    row = new LinearLayout(c);
                    LayoutParams rl = new LayoutParams(-1, -2);
                    rl.topMargin = Ui.dp(c, Ui.GUTTER);
                    addView(row, rl);
                }
                int color = p.c(new int[]{0, 1, 3, 2, 4, 5}[k % 6]);
                final Context ctx = c;
                int shape = col == 0 ? Ui.LEFT : col == perRow - 1 ? Ui.RIGHT : Ui.SQUARE;
                TextView b = Ui.button(c, ctl.label, color, shape, null);
                b.setOnClickListener(new OnClickListener() {
                    @Override public void onClick(View v) {
                        // the control's own sound replaces the usual key tap
                        if (Prefs.get(ctx).haptics()) v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                        play(ctx, ctl.role);
                        ctl.action.run();
                        sim.invalidate();
                    }
                });
                b.setTextSize(13 * Skin.textScale());
                b.setOnLongClickListener(new OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        Ui.longPress(v);
                        host.assignSound(ctl.role, ctl.label);
                        return true;
                    }
                });
                LayoutParams bl = new LayoutParams(0, Ui.dp(c, CONTROL_DP), 1);
                if (col > 0) bl.leftMargin = Ui.dp(c, Ui.GUTTER);
                row.addView(b, bl);
                k++;
            }
            TextView hint = Ui.text(c, "LONG-PRESS A CONTROL TO CHOOSE ITS SOUND", 10, Ui.alpha(p.textLo, 0.7f));
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, Ui.dp(c, Ui.S), 0, 0);
            addView(hint, new LayoutParams(-1, -2));
        }
    }

    // ---- OPS: power distribution --------------------------------------------------------------------------

    static final class Ops extends Sim {
        final String[] sys = {"WARP DRIVE", "IMPULSE", "SHIELDS", "SENSORS", "LIFE SUPPORT", "WEAPONS"};
        final float[] target = {22, 12, 18, 14, 20, 14}, shown = {0, 0, 0, 0, 0, 0};
        float shimmer;

        Ops(Context c, Palette p) {
            super(c, p);
            if (Skin.collective()) { sys[0] = "CONDUIT DRIVE"; sys[4] = "CHAMBERS"; sys[5] = "CUTTING BEAMS"; }
            if (Skin.imperial()) { sys[5] = "DISRUPTORS"; sys[2] = "DEFLECTORS"; }
            setOnTouchListener(new OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    if (e.getAction() != MotionEvent.ACTION_UP) return true;
                    int i = (int) ((e.getY() - dp(30)) / rowH());
                    if (i >= 0 && i < sys.length) boost(i);
                    return true;
                }
            });
        }

        float rowH() { return (getHeight() - dp(56)) / sys.length; }

        void boost(int i) {
            redistribute(target, i);
            play(getContext(), "reroute");
            say("POWER REROUTED TO " + sys[i]);
        }

        /**
         * Moves up to 10 points of power to system i, taken evenly from the others (none below
         * 4); the shares always total 100.
         */
        static void redistribute(float[] target, int i) {
            int n = target.length;
            float add = Math.min(10, 100 - target[i]);
            float each = add / (n - 1);
            for (int k = 0; k < n; k++) if (k != i) target[k] = Math.max(4, target[k] - each);
            float sum = 0;
            for (int k = 0; k < n; k++) if (k != i) sum += target[k];
            target[i] = 100 - sum;
        }

        @Override String title() { return Skin.collective() ? "CORE NEXUS • POWER DISTRIBUTION" : Skin.imperial() ? "SYSTEMS • POWER DISTRIBUTION" : "OPS • POWER DISTRIBUTION"; }

        @Override List<Control> controls() {
            List<Control> c = new ArrayList<>();
            c.add(new Control("BALANCE", "reroute", new Runnable() { @Override public void run() {
                for (int k = 0; k < sys.length; k++) target[k] = 100f / sys.length;
                say("POWER EQUALISED"); } }));
            c.add(new Control(Skin.collective() ? "ADAPT" : "EMERGENCY POWER", "alert", new Runnable() { @Override public void run() {
                float[] t = {6, 6, 34, 8, 40, 6};
                System.arraycopy(t, 0, target, 0, t.length);
                say(Skin.collective() ? "ADAPTING" : "EMERGENCY POWER TO SHIELDS AND LIFE SUPPORT"); } }));
            c.add(new Control(Skin.collective() ? "INTEGRATE" : "ENERGIZE", "transporter", new Runnable() { @Override public void run() {
                shimmer = 2.2f;
                say(Skin.collective() ? "TRANSPORTING UNITS" : "TRANSPORTER: ENERGIZING"); } }));
            c.add(new Control(Skin.collective() ? "LINK" : "HAIL", "hail", new Runnable() { @Override public void run() {
                say(Skin.collective() ? "SUBSPACE LINK ESTABLISHED" : "HAILING FREQUENCIES OPEN"); } }));
            return c;
        }

        @Override void step(float dt) {
            for (int k = 0; k < sys.length; k++) shown[k] += (target[k] - shown[k]) * Math.min(1, dt * 5);
            shimmer = Math.max(0, shimmer - dt);
        }

        @Override void paint(Canvas cv, float w, float h) {
            text(cv, "TAP A SYSTEM TO REROUTE POWER", dp(16), dp(22), 10, Ui.alpha(p.textLo, 0.8f), Paint.Align.LEFT, false);
            float rh = rowH(), x0 = dp(110), x1 = w - dp(54);
            for (int k = 0; k < sys.length; k++) {
                float y = dp(30) + k * rh, bh = Math.min(rh * 0.55f, dp(14));
                float cy = y + rh / 2f;
                text(cv, sys[k], dp(16), cy + dp(4), 10, p.textHi, Paint.Align.LEFT, false);
                float len = (x1 - x0) * shown[k] / 50f;
                len = Math.min(len, x1 - x0);
                int c = col(k % 6);
                fill.setColor(Ui.alpha(c, 0.18f));
                r.set(x0, cy - bh / 2, x1, cy + bh / 2);
                cv.drawRect(r, fill);
                fill.setColor(c);
                if (Skin.imperial()) {
                    // segmented in triangles
                    float s = bh * 1.1f;
                    for (float x = x0; x + s / 2 < x0 + len; x += s / 2) {
                        ImperialDraw.tri(cv, x + s / 2, cy, s * 0.92f, bh, ((int) ((x - x0) / (s / 2))) % 2 == 0, c, 1f);
                    }
                } else if (Skin.collective()) {
                    for (float x = x0 + dp(4); x < x0 + len; x += dp(9)) cv.drawCircle(x, cy, bh * 0.35f, fill);
                } else {
                    r.set(x0, cy - bh / 2, x0 + len, cy + bh / 2);
                    cv.drawRoundRect(r, bh / 2, bh / 2, fill);
                }
                text(cv, Math.round(shown[k]) + "%", w - dp(16), cy + dp(4), 11, c, Paint.Align.RIGHT, true);
            }
            if (shimmer > 0) {
                // transporter: sparkling column in the middle of the display
                float a = Math.min(1, shimmer);
                for (int i = 0; i < 40; i++) {
                    fill.setColor(Ui.alpha(Skin.collective() ? 0xFF79C98B : Skin.imperial() ? 0xFFF0821E : 0xFFCFE3FF, a * rnd.nextFloat()));
                    cv.drawCircle(w / 2f + (rnd.nextFloat() - 0.5f) * dp(40), dp(30) + rnd.nextFloat() * (h - dp(60)), dp(1.5f), fill);
                }
            }
        }
    }

    // ---- CONN: course and speed ---------------------------------------------------------------------------------

    static final class Conn extends Sim {
        final float[][] stars = new float[90][3];
        float speed = 0.2f, targetSpeed = 0.2f;
        int heading = 47, mark = 12;
        String speedName = "IMPULSE";

        Conn(Context c, Palette p) {
            super(c, p);
            for (float[] s : stars) reset(s, true);
        }

        void reset(float[] s, boolean anyZ) {
            s[0] = (rnd.nextFloat() - 0.5f) * 2;
            s[1] = (rnd.nextFloat() - 0.5f) * 2;
            s[2] = anyZ ? rnd.nextFloat() : 1;
        }

        @Override String title() { return Skin.collective() ? "CONDUIT DRIVE • COURSE AND VELOCITY" : Skin.imperial() ? "HELM • COURSE AND SPEED" : "CONN • COURSE AND SPEED"; }

        void setSpeed(float s, String name) { targetSpeed = s; speedName = name; say(name.equals("ALL STOP") ? "ALL STOP" : "SPEED: " + name); }

        @Override List<Control> controls() {
            List<Control> c = new ArrayList<>();
            c.add(new Control("SET COURSE", "course", new Runnable() { @Override public void run() {
                heading = rnd.nextInt(360); mark = rnd.nextInt(90);
                say(String.format(Locale.US, "COURSE LAID IN: %03d MARK %03d", heading, mark)); } }));
            c.add(new Control("IMPULSE", "impulse", new Runnable() { @Override public void run() { setSpeed(0.25f, Skin.collective() ? "SUBLIGHT" : "FULL IMPULSE"); } }));
            c.add(new Control(Skin.collective() ? "CONDUIT DRIVE" : "WARP 6", "warp", new Runnable() { @Override public void run() {
                setSpeed(Skin.collective() ? 4f : 1.6f, Skin.collective() ? "CONDUIT OPEN" : "WARP 6"); } }));
            c.add(new Control(Skin.collective() ? "FULL STOP" : "ALL STOP", "all_stop", new Runnable() { @Override public void run() { setSpeed(0f, "ALL STOP"); } }));
            if (!Skin.collective()) c.add(new Control("WARP 9", "warp9", new Runnable() { @Override public void run() { setSpeed(3f, "WARP 9"); } }));
            if (!Skin.collective()) c.add(new Control(Skin.imperial() ? "CLOAK" : "EVASIVE", Skin.imperial() ? "cloak" : "course", new Runnable() { @Override public void run() {
                heading = (heading + 90 + rnd.nextInt(180)) % 360;
                say(Skin.imperial() ? "CLOAKING DEVICE ENGAGED" : "EVASIVE PATTERN DELTA"); } }));
            return c;
        }

        @Override void step(float dt) {
            speed += (targetSpeed - speed) * Math.min(1, dt * 1.6f);
            for (float[] s : stars) {
                s[2] -= dt * (0.05f + speed * 0.6f);
                if (s[2] <= 0.02f) reset(s, false);
            }
        }

        @Override void paint(Canvas cv, float w, float h) {
            float cx = w / 2f, cy = h / 2f - dp(10), sc = Math.min(w, h) * 0.45f;
            cv.save();
            r.set(dp(8), dp(8), w - dp(8), h - dp(28));
            cv.clipRect(r);
            line.setStrokeCap(Paint.Cap.ROUND);
            for (float[] s : stars) {
                float x = cx + s[0] / s[2] * sc * 0.25f, y = cy + s[1] / s[2] * sc * 0.25f;
                float z2 = s[2] + 0.02f + speed * 0.05f;
                float x2 = cx + s[0] / z2 * sc * 0.25f, y2 = cy + s[1] / z2 * sc * 0.25f;
                float b = Math.min(1, (1 - s[2]) * 1.4f);
                line.setStrokeWidth(dp(1 + (1 - s[2]) * 1.5f));
                line.setColor(Ui.alpha(Skin.collective() ? 0xFFB1DC86 : Skin.imperial() ? 0xFFF5C830 : 0xFFE6DEFF, b));
                cv.drawLine(x2, y2, x, y, line);
            }
            cv.restore();
            line.setStrokeCap(Paint.Cap.BUTT);
            text(cv, String.format(Locale.US, "HEADING %03d MARK %03d", heading, mark), dp(16), dp(24), 13, col(0), Paint.Align.LEFT, true);
            text(cv, speedName, w - dp(16), dp(24), 13, col(1), Paint.Align.RIGHT, true);
        }
    }

    // ---- TACTICAL: shields, weapons, alert -------------------------------------------------------------------

    static final class Tactical extends Sim {
        float t, beam, torp = -1, hit, alertT;
        boolean shields, red;

        Tactical(Context c, Palette p) { super(c, p); }

        @Override String title() { return Skin.collective() ? "DEFENSE GRID • TARGETING" : Skin.imperial() ? "WEAPONS • TARGETING" : "TACTICAL • TARGETING"; }

        float tx() { return getWidth() * (0.5f + 0.32f * (float) Math.sin(t * 0.7f)); }

        float ty() { return getHeight() * (0.38f + 0.12f * (float) Math.sin(t * 1.3f)); }

        @Override List<Control> controls() {
            List<Control> c = new ArrayList<>();
            c.add(new Control(Skin.collective() ? "ADAPT SHIELDS" : "SHIELDS", "shields", new Runnable() { @Override public void run() {
                shields = !shields; say(shields ? (Skin.collective() ? "SHIELDS ADAPTED" : "SHIELDS UP") : "SHIELDS DOWN"); } }));
            c.add(new Control(Skin.collective() ? "CUTTING BEAM" : Skin.imperial() ? "DISRUPTORS" : "PULSE BEAMS", "beam", new Runnable() { @Override public void run() {
                beam = 0.6f; hit = 0.5f; say(Skin.collective() ? "CUTTING BEAM ENGAGED" : Skin.imperial() ? "DISRUPTORS FIRING" : "FIRING PULSE BEAMS"); } }));
            c.add(new Control(Skin.collective() ? "TRACTOR BEAM" : "TORPEDO", Skin.collective() ? "tractor" : "torpedo", new Runnable() { @Override public void run() {
                if (Skin.collective()) { beam = 1.4f; say("TRACTOR BEAM LOCKED"); } else { torp = 0; say("TORPEDO AWAY"); } } }));
            c.add(new Control(Skin.collective() ? "ALERT" : "RED ALERT", "red_alert", new Runnable() { @Override public void run() {
                red = !red; say(red ? (Skin.collective() ? "THREAT DETECTED" : "RED ALERT") : "CONDITION GREEN"); } }));
            return c;
        }

        @Override void step(float dt) {
            t += dt;
            beam = Math.max(0, beam - dt);
            hit = Math.max(0, hit - dt);
            alertT += dt;
            if (torp >= 0) {
                torp += dt * 1.4f;
                if (torp >= 1) { torp = -1; hit = 0.6f; }
            }
        }

        @Override void paint(Canvas cv, float w, float h) {
            float sx = w / 2f, sy = h - dp(40), x = tx(), y = ty();
            // own ship and shield bubble
            if (shields) {
                line.setStrokeWidth(dp(2));
                line.setColor(Ui.alpha(col(1), 0.5f + 0.3f * (float) Math.sin(t * 4)));
                if (Skin.imperial()) cv.drawPath(ImperialDraw.octagon(new Path(), sx - dp(36), sy - dp(22), sx + dp(36), sy + dp(14), dp(10), true, true), line);
                else { r.set(sx - dp(36), sy - dp(22), sx + dp(36), sy + dp(14)); cv.drawOval(r, line); }
            }
            fill.setColor(col(0));
            if (Skin.collective()) { r.set(sx - dp(9), sy - dp(9), sx + dp(9), sy + dp(9)); cv.drawRect(r, fill); }
            else if (Skin.imperial()) ImperialDraw.tri(cv, sx, sy, dp(26), dp(14), false, col(0), 1f);
            else { r.set(sx - dp(16), sy - dp(5), sx + dp(16), sy + dp(5)); cv.drawOval(r, fill); }
            // target and reticle
            float rr = dp(26);
            line.setStrokeWidth(dp(1.5f));
            line.setColor(col(3));
            if (Skin.imperial()) {
                cv.drawPath(ImperialDraw.octagon(new Path(), x - rr, y - rr, x + rr, y + rr, rr * 0.4f, true, true), line);
            } else if (Skin.collective()) {
                CollectiveDraw.ringNode(cv, x, y, rr, col(3), d, 1f);
            } else {
                cv.drawCircle(x, y, rr, line);
                cv.drawLine(x - rr * 1.5f, y, x - rr * 0.5f, y, line);
                cv.drawLine(x + rr * 0.5f, y, x + rr * 1.5f, y, line);
                cv.drawLine(x, y - rr * 1.5f, x, y - rr * 0.5f, line);
                cv.drawLine(x, y + rr * 0.5f, x, y + rr * 1.5f, line);
            }
            fill.setColor(Ui.alpha(col(4), 0.9f));
            cv.drawCircle(x, y, dp(5), fill);
            if (beam > 0) {
                line.setStrokeWidth(dp(3));
                line.setColor(Skin.collective() ? 0xFF79C98B : Skin.imperial() ? 0xFF4CE05A : 0xFFFF9C00);
                cv.drawLine(sx, sy - dp(6), x, y, line);
            }
            if (torp >= 0) {
                float px = sx + (x - sx) * torp, py = sy + (y - sy) * torp;
                fill.setColor(Skin.imperial() ? 0xFF4CE05A : 0xFFFF5040);
                cv.drawCircle(px, py, dp(5), fill);
                fill.setColor(Ui.alpha(Color.WHITE, 0.6f));
                cv.drawCircle(px, py, dp(2.5f), fill);
            }
            if (hit > 0) {
                fill.setColor(Ui.alpha(0xFFFFE08A, hit));
                cv.drawCircle(x, y, dp(10) + (0.6f - hit) * dp(40), fill);
            }
            if (red && ((int) (alertT * 2)) % 2 == 0) {
                line.setStrokeWidth(dp(5));
                line.setColor(Ui.alpha(0xFFFF2020, 0.7f));
                r.set(dp(6), dp(6), w - dp(6), h - dp(6));
                cv.drawRoundRect(r, dp(14), dp(14), line);
                text(cv, Skin.collective() ? "THREAT" : "RED ALERT", w / 2f, dp(28), 16, 0xFFFF4040, Paint.Align.CENTER, true);
            }
            text(cv, String.format(Locale.US, "TARGET %03d.%d", (int) (x / w * 360), (int) (y / h * 9)), dp(16), dp(24), 11, col(2), Paint.Align.LEFT, true);
        }
    }

    // ---- ENGINEERING: warp core ------------------------------------------------------------------------------------

    static final class Engineering extends Sim {
        float output = 0.6f, phase, eject = -1, armed;

        Engineering(Context c, Palette p) { super(c, p); }

        @Override String title() { return Skin.collective() ? "POWER NODES • CENTRAL PLEXUS" : Skin.imperial() ? "ENGINEERING • WARP CORE" : "ENGINEERING • WARP CORE"; }

        @Override List<Control> controls() {
            List<Control> c = new ArrayList<>();
            c.add(new Control("INCREASE OUTPUT", "core", new Runnable() { @Override public void run() {
                output = Math.min(1, output + 0.15f); say(String.format(Locale.US, "OUTPUT %d%%", Math.round(output * 100))); } }));
            c.add(new Control("DECREASE OUTPUT", "core", new Runnable() { @Override public void run() {
                output = Math.max(0.1f, output - 0.15f); say(String.format(Locale.US, "OUTPUT %d%%", Math.round(output * 100))); } }));
            c.add(new Control("AUTHORIZE", "authorize", new Runnable() { @Override public void run() { say("COMMAND CODES VERIFIED"); } }));
            c.add(new Control(Skin.collective() ? "SEVER NODE" : "EJECT CORE", "eject", new Runnable() { @Override public void run() {
                if (eject >= 0) return;
                if (armed <= 0) { armed = 3; say("PRESS AGAIN TO CONFIRM"); return; }
                armed = 0; eject = 0; say(Skin.collective() ? "NODE SEVERED" : "WARP CORE EJECTED"); } }));
            return c;
        }

        @Override void step(float dt) {
            phase += dt * (0.6f + output * 2.4f);
            armed = Math.max(0, armed - dt);
            if (eject >= 0) {
                eject += dt;
                if (eject > 5) { eject = -1; say(Skin.collective() ? "NODE RESTORED" : "REPLACEMENT CORE ONLINE"); }
            }
        }

        @Override void paint(Canvas cv, float w, float h) {
            float cx = w / 2f, top = dp(30), bot = h - dp(34), cw = dp(34);
            float drop = eject >= 0 ? Math.min(1, eject / 1.2f) * (h + dp(40)) : 0;
            if (eject >= 1.2f && eject < 5) {
                text(cv, Skin.collective() ? "NODE OFFLINE" : "CORE EJECTED", cx, h / 2f, 18, 0xFFFF4040, Paint.Align.CENTER, true);
            } else {
                cv.save();
                cv.translate(0, drop);
                int n = 9;
                float sh = (bot - top) / n;
                for (int i = 0; i < n; i++) {
                    float y = top + i * sh;
                    // pulses travel from both ends toward the reaction chamber in the middle
                    float dist = Math.abs(i - (n - 1) / 2f) / ((n - 1) / 2f);
                    float glow = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(phase * 6 + dist * 5)) * output;
                    int c = Skin.collective() ? 0xFF79C98B : Skin.imperial() ? 0xFFF0821E : 0xFF6FA8FF;
                    if (Skin.collective()) {
                        CollectiveDraw.node(cv, cx, y + sh / 2, Math.min(cw / 2, sh * 0.42f), c, d, glow);
                    } else if (Skin.imperial()) {
                        ImperialDraw.tri(cv, cx, y + sh / 2, cw, sh * 0.85f, i % 2 == 0, c, glow);
                    } else {
                        fill.setColor(Ui.alpha(c, glow));
                        r.set(cx - cw / 2, y + dp(2), cx + cw / 2, y + sh - dp(2));
                        cv.drawRoundRect(r, dp(6), dp(6), fill);
                    }
                }
                line.setStrokeWidth(dp(2));
                line.setColor(col(1));
                r.set(cx - cw / 2 - dp(8), top - dp(4), cx + cw / 2 + dp(8), bot + dp(4));
                cv.drawRoundRect(r, Skin.command() ? dp(10) : dp(2), Skin.command() ? dp(10) : dp(2), line);
                cv.restore();
            }
            text(cv, String.format(Locale.US, "OUTPUT %d%%", Math.round(output * 100)), dp(16), dp(24), 12, col(0), Paint.Align.LEFT, true);
            text(cv, Skin.collective() ? "PLEXUS STABLE" : "M/AM RATIO 1:1", w - dp(16), dp(24), 12, col(2), Paint.Align.RIGHT, true);
            text(cv, String.format(Locale.US, "%.1f", 1000 + output * 2400) + (Skin.collective() ? " NODES" : " COCHRANES"), w - dp(16), h / 2f, 11, col(4), Paint.Align.RIGHT, false);
            if (armed > 0) text(cv, "EJECTION ARMED", dp(16), h / 2f, 12, 0xFFFF4040, Paint.Align.LEFT, true);
        }
    }

    // ---- SCIENCE: sensor sweep ------------------------------------------------------------------------------------

    static final class Science extends Sim {
        final float[][] blips = new float[7][3]; // angle, distance (0..1), last-seen
        float sweep, probe = -1;
        boolean longRange;
        static final String[] FINDINGS = {"TEMPERATE PLANET • NITROGEN-OXYGEN ATMOSPHERE", "NEBULA • HIGH IONISATION", "SUBSPACE ANOMALY",
                "ASTEROID FIELD • CRYSTAL TRACES", "VESSEL • TRANSPONDER SILENT", "DERELICT • NO LIFE SIGNS", "COMET • ICE AND DUST"};

        Science(Context c, Palette p) {
            super(c, p);
            for (float[] b : blips) { b[0] = rnd.nextFloat() * 360; b[1] = 0.2f + rnd.nextFloat() * 0.75f; b[2] = -9; }
        }

        @Override String title() { return Skin.collective() ? "SENSOR ARRAY • LONG RANGE" : Skin.imperial() ? "SENSORS • SWEEP" : "SCIENCE • SENSOR SWEEP"; }

        @Override List<Control> controls() {
            List<Control> c = new ArrayList<>();
            c.add(new Control(Skin.collective() ? "SCAN SPECIES" : "SCAN CONTACT", "scan", new Runnable() { @Override public void run() {
                if (Skin.collective()) say("SPECIES " + (100 + rnd.nextInt(8900)) + " • INTEGRATION VIABLE");
                else say(FINDINGS[rnd.nextInt(FINDINGS.length)]); } }));
            // the label is fixed when the console is built, so it names the toggle, not a state
            c.add(new Control("SENSOR RANGE", "scan", new Runnable() { @Override public void run() {
                longRange = !longRange;
                for (float[] b : blips) { b[0] = rnd.nextFloat() * 360; b[1] = 0.2f + rnd.nextFloat() * 0.75f; }
                say(longRange ? "LONG RANGE SENSORS" : "SHORT RANGE SENSORS"); } }));
            c.add(new Control("LAUNCH PROBE", "probe", new Runnable() { @Override public void run() { probe = 0; say("PROBE LAUNCHED"); } }));
            c.add(new Control("RECALIBRATE", "course", new Runnable() { @Override public void run() { sweep = 0; say("ARRAY RECALIBRATED"); } }));
            return c;
        }

        @Override void step(float dt) {
            sweep = (sweep + dt * 120) % 360;
            for (float[] b : blips) {
                float diff = (sweep - b[0] + 360) % 360;
                if (diff < 6) b[2] = 0; else b[2] += dt;
            }
            if (probe >= 0) { probe += dt * 0.35f; if (probe > 1) probe = -1; }
        }

        @Override void paint(Canvas cv, float w, float h) {
            float cx = w / 2f, cy = h / 2f - dp(6), R = Math.min(w, h) * 0.4f;
            line.setStrokeWidth(dp(1));
            for (int i = 1; i <= 3; i++) {
                line.setColor(Ui.alpha(col(1), 0.35f));
                if (Skin.imperial()) {
                    float rr = R * i / 3f;
                    path.reset();
                    for (int k = 0; k < 6; k++) {
                        double a = Math.PI / 3 * k;
                        float x = cx + (float) Math.cos(a) * rr, y = cy + (float) Math.sin(a) * rr;
                        if (k == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                    path.close();
                    cv.drawPath(path, line);
                } else {
                    cv.drawCircle(cx, cy, R * i / 3f, line);
                }
            }
            cv.drawLine(cx - R, cy, cx + R, cy, line);
            cv.drawLine(cx, cy - R, cx, cy + R, line);
            // sweep line with trailing fade
            for (int k = 0; k < 18; k++) {
                double a = Math.toRadians(sweep - k * 2);
                line.setStrokeWidth(dp(k == 0 ? 2 : 1.5f));
                line.setColor(Ui.alpha(col(0), (1 - k / 18f) * 0.7f));
                cv.drawLine(cx, cy, cx + (float) Math.cos(a) * R, cy + (float) Math.sin(a) * R, line);
            }
            for (float[] b : blips) {
                float a = Math.max(0, 1 - b[2] / 3f);
                if (a <= 0) continue;
                double ang = Math.toRadians(b[0]);
                float x = cx + (float) Math.cos(ang) * R * b[1], y = cy + (float) Math.sin(ang) * R * b[1];
                if (Skin.collective()) CollectiveDraw.node(cv, x, y, dp(4), col(3), d, a);
                else if (Skin.imperial()) ImperialDraw.tri(cv, x, y, dp(9), dp(8), true, col(3), a);
                else { fill.setColor(Ui.alpha(col(3), a)); cv.drawCircle(x, y, dp(4), fill); }
            }
            if (probe >= 0) {
                fill.setColor(col(2));
                cv.drawCircle(cx + R * probe * 0.9f, cy - R * probe * 0.5f, dp(3), fill);
            }
            text(cv, longRange ? "RANGE 12 LY" : "RANGE 2.4 AU", dp(16), dp(24), 12, col(2), Paint.Align.LEFT, true);
        }
    }

    // ---- MEDICAL: scan bed ---------------------------------------------------------------------------------------------

    static final class Medical extends Sim {
        float bpm = 72, targetBpm = 72, t, scan = -1;
        final float[] trace = new float[160];
        int head;
        float beatClock;

        Medical(Context c, Palette p) { super(c, p); }

        @Override String title() { return Skin.collective() ? "REGENERATION • CHAMBER 3 OF 5" : Skin.imperial() ? "MEDICAL • SCAN BED" : "SICKBAY • SCAN BED"; }

        @Override List<Control> controls() {
            List<Control> c = new ArrayList<>();
            c.add(new Control(Skin.collective() ? "DIAGNOSTIC" : "MEDICAL SCAN", "scanner", new Runnable() { @Override public void run() {
                scan = 0; say(Skin.collective() ? "CORTICAL NODE NOMINAL" : "VITAL SIGNS STABLE"); } }));
            c.add(new Control(Skin.collective() ? "NANITES" : "INJECTOR", "injector", new Runnable() { @Override public void run() {
                targetBpm = 58 + rnd.nextInt(12); say(Skin.collective() ? "NANITES DEPLOYED" : "ADMINISTERED 10 CC • PATIENT RESTING"); } }));
            c.add(new Control(Skin.collective() ? "REGENERATE" : "STIMULANT", Skin.collective() ? "regen" : "injector", new Runnable() { @Override public void run() {
                targetBpm = 96 + rnd.nextInt(20); say(Skin.collective() ? "REGENERATION CYCLE INITIATED" : "STIMULANT • HEART RATE RISING"); } }));
            c.add(new Control(Skin.collective() ? "ALERT COLLECTIVE" : "CALL DOCTOR", "comm", new Runnable() { @Override public void run() {
                say(Skin.collective() ? "THE COLLECTIVE IS AWARE" : Skin.imperial() ? "THE DOCTOR IS ON HIS WAY. TRY NOT TO DIE." : "MEDICAL TEAM NOTIFIED"); } }));
            return c;
        }

        @Override void step(float dt) {
            t += dt;
            bpm += (targetBpm - bpm) * Math.min(1, dt * 0.8f);
            float period = 60f / bpm;
            int steps = Math.max(1, Math.round(dt * 80));
            for (int s = 0; s < steps; s++) {
                beatClock += dt / steps;
                if (beatClock > period) beatClock -= period;
                float ph = beatClock / period;
                float v = 0;
                if (ph < 0.04f) v = ph / 0.04f * 0.15f;
                else if (ph < 0.08f) v = -0.2f;
                else if (ph < 0.11f) v = 1f;
                else if (ph < 0.14f) v = -0.35f;
                else if (ph > 0.3f && ph < 0.42f) v = 0.25f * (float) Math.sin((ph - 0.3f) / 0.12f * Math.PI);
                trace[head] = v;
                head = (head + 1) % trace.length;
            }
            if (scan >= 0) { scan += dt * 0.6f; if (scan > 1) scan = -1; }
        }

        @Override void paint(Canvas cv, float w, float h) {
            float x0 = dp(16), x1 = w - dp(110), mid = h * 0.42f, amp = h * 0.22f;
            line.setStrokeWidth(dp(2));
            line.setColor(col(1));
            path.reset();
            for (int i = 0; i < trace.length; i++) {
                float v = trace[(head + i) % trace.length];
                float x = x0 + (x1 - x0) * i / (trace.length - 1), y = mid - v * amp;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            cv.drawPath(path, line);
            text(cv, Math.round(bpm) + "", w - dp(16), mid, 34, col(0), Paint.Align.RIGHT, true);
            text(cv, Skin.collective() ? "CYCLE" : "BPM", w - dp(16), mid + dp(18), 10, col(0), Paint.Align.RIGHT, false);
            String[] vit = Skin.collective() ? new String[]{"NANITES 98%", "CORTICAL 100%", "LINK STABLE"}
                    : new String[]{"RESP " + (12 + Math.round(bpm / 20)), "BP 120/80", "NEURAL NOMINAL"};
            for (int i = 0; i < vit.length; i++) {
                text(cv, vit[i], x0 + i * (w - dp(32)) / 3f, h - dp(34), 11, col(2 + i), Paint.Align.LEFT, true);
            }
            if (scan >= 0) {
                fill.setColor(Ui.alpha(col(4), 0.35f));
                float y = dp(10) + (h - dp(40)) * scan;
                r.set(dp(10), y, w - dp(10), y + dp(4));
                cv.drawRect(r, fill);
            }
        }
    }
}
