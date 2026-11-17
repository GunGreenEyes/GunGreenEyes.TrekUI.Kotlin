package com.gungreeneyes.trekui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * console controls for one Home Assistant entity, and what a home tile assigned to one does.
 *
 * <p>{@link #launch} is the tile / dock / segment tap: lights, switches, fans and input booleans
 * toggle, scenes and scripts activate, climate opens {@link ThermostatDialog} through
 * {@link HomeAssistant.Climate}, and a tile assigned with "CONTROLS" opens {@link #show}.
 * {@link #show} is the control panel: ON / OFF, an intensity slider and a spectrum row of colour
 * keys for lights that support them, ACTIVATE for scenes and scripts.
 *
 * <p>All network work goes through {@link HomeAssistant#async} with the application context;
 * replies that arrive after the dialog closed are dropped. The panel is a {@link ConsoleDialog}, so
 * it is styled for every interface and sized for phones through tablets in both orientations.
 */
final class HomeAssistantDialog {
    /** Colour keys offered for RGB lights (warm white first). */
    static final int[] SPECTRUM = {0xFFE2B0, 0xFF9C00, 0xFF2200, 0xFF33CC, 0x9966FF, 0x3366FF, 0x00CCFF, 0x33FF66};
    /** Height of the control keys, in dp. */
    private static final float KEY_DP = 46;

    private final Context c;
    private final Context app;
    private final Palette pal;
    private final String entityId;
    private ConsoleDialog.Built built;
    private TextView status, line;
    private LinearLayout controls;
    private HomeAssistant.Entity entity;

    private HomeAssistantDialog(Context c, String entityId) {
        this.c = c;
        this.app = c.getApplicationContext();
        this.pal = Prefs.get(c).palette();
        this.entityId = entityId;
    }

    // ---- home tile tap -----------------------------------------------------------------------------

    /**
     * Runs a home tile, dock key or segment assigned to a Home Assistant entity ({@code arg} is the
     * entity_id, optionally with {@link HomeAssistant#PANEL_SUFFIX}).
     *
     * @param feedback view to give haptic feedback on success; may be null
     */
    static void launch(final Context c, String arg, final View feedback) {
        final String id = HomeAssistant.entityOfArg(arg);
        HomeAssistant.Entity stored = HomeAssistant.find(c, id);
        if (!HomeAssistant.configured(c) || stored == null) {
            Sfx.play(c, Sfx.ERROR);
            Toast.makeText(c, c.getString(R.string.ha_toast_not_set_up), Toast.LENGTH_SHORT).show();
            return;
        }
        final String title = stored.title();
        if ("climate".equals(HomeAssistant.domainOf(id))) {
            ThermostatDialog.show(c, new HomeAssistant.Climate(id), title);
            return;
        }
        final HomeAssistant.Call call = HomeAssistant.primary(id);
        if (HomeAssistant.panelArg(arg) || call == null) {
            show(c, id, title);
            return;
        }
        final Context app = c.getApplicationContext();
        HomeAssistant.async(new HomeAssistant.Job<String>() {
            @Override public String run() throws java.io.IOException { return HomeAssistant.call(app, call); }
        }, new HomeAssistant.Result<String>() {
            @Override public void done(String state, String error) {
                if (error != null) {
                    Sfx.play(app, Sfx.ERROR);
                    Toast.makeText(app, app.getString(R.string.ha_unable, error), Toast.LENGTH_LONG).show();
                    return;
                }
                boolean activated = "turn_on".equals(call.service) && ("scene".equals(call.domain) || "script".equals(call.domain));
                Sfx.play(app, "off".equals(state) ? Sfx.CLOSE : Sfx.CONFIRM);
                if (feedback != null) Ui.tap(feedback);
                String what = activated || state.isEmpty() ? app.getString(R.string.ha_activated) : state.toUpperCase(Locale.getDefault());
                Toast.makeText(app, app.getString(R.string.ha_toast_state, title, what), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---- assigning ----------------------------------------------------------------------------------

    /** Receives the tile argument and caption picked in {@link #pick}. */
    interface Picked { void picked(String arg, String label); }

    /**
     * The "HOME ASSISTANT…" step of ASSIGN console FUNCTION: lists the imported entities (lights also
     * get a "CONTROLS" entry that opens the panel instead of toggling). Without a set-up instance
     * it leads to CONFIG.
     */
    static void pick(final MainActivity a, final Picked cb) {
        final java.util.List<HomeAssistant.Entity> entities = HomeAssistant.entities(a);
        if (!HomeAssistant.configured(a) || entities.isEmpty()) {
            a.toast(a.getString(R.string.ha_link_first));
            a.openConfig();
            return;
        }
        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<String> args = new java.util.ArrayList<>();
        final java.util.List<String> captions = new java.util.ArrayList<>();
        for (HomeAssistant.Entity e : entities) {
            labels.add(a.getString(R.string.ha_pick_toggle, e.title()));
            args.add(e.id);
            captions.add(e.title());
            if ("light".equals(e.domain())) {
                labels.add(a.getString(R.string.ha_pick_controls, e.title()));
                args.add(e.id + HomeAssistant.PANEL_SUFFIX);
                captions.add(e.title());
            }
        }
        a.showing(ConsoleDialog.options(a, a.getString(R.string.ha_pick_title), labels.toArray(new String[0]), new ConsoleDialog.OnPick() {
            @Override public void pick(int which) { cb.picked(args.get(which), captions.get(which)); }
        }));
    }

    // ---- CONFIG prompts -----------------------------------------------------------------------------

    /**
     * One-line prompt for the address (URL keyboard) or the token (hidden, no suggestions, never
     * pre-filled). Unlike {@link ConsoleDialog#prompt} it keeps the keyboard in normal case, since
     * both values are case-sensitive.
     */
    static void prompt(final Context c, String title, String initial, String hint, boolean secret, final ConsoleDialog.OnText cb) {
        Palette p = Prefs.get(c).palette();
        final ConsoleDialog.Built b = ConsoleDialog.base(c, title);
        final android.widget.EditText e = new android.widget.EditText(c);
        e.setTag(secret ? "ha_token_field" : "ha_url_field");
        e.setText(initial);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (secret
                ? android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                : android.text.InputType.TYPE_TEXT_VARIATION_URI));
        e.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        e.setImportantForAutofill(secret ? View.IMPORTANT_FOR_AUTOFILL_NO : View.IMPORTANT_FOR_AUTOFILL_AUTO);
        e.setTextColor(p.textHi);
        e.setHintTextColor(Ui.alpha(p.textLo, 0.6f));
        e.setTypeface(Ui.font(c, false));
        Ui.setTextSp(e, 17);
        e.setBackground(Ui.outline(c, p.panel, p.c(2), 18, 2));
        e.setPadding(Ui.dp(c, Ui.L), Ui.dp(c, Ui.M), Ui.dp(c, Ui.L), Ui.dp(c, Ui.M));
        e.setSelection(e.getText().length());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(c, Ui.M);
        b.body.addView(e, lp);
        final Runnable ok = new Runnable() {
            @Override public void run() {
                b.dialog.dismiss();
                cb.text(e.getText().toString().trim());
            }
        };
        e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, android.view.KeyEvent ev) { ok.run(); return true; }
        });
        LinearLayout row = new LinearLayout(c);
        TextView cancel = ConsoleDialog.option(c, c.getString(R.string.note_cancel), p.c(3), new View.OnClickListener() {
            @Override public void onClick(View v) { Sfx.play(c, Sfx.CANCEL); b.dialog.dismiss(); }
        });
        TextView accept = ConsoleDialog.option(c, c.getString(R.string.ha_accept), p.c(0), new View.OnClickListener() {
            @Override public void onClick(View v) { Sfx.play(c, Sfx.CONFIRM); ok.run(); }
        });
        LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(0, -2, 1);
        h.rightMargin = Ui.dp(c, Ui.S);
        row.addView(cancel, h);
        row.addView(accept, new LinearLayout.LayoutParams(0, -2, 1));
        b.body.addView(row);
        android.view.Window w = b.dialog.getWindow();
        if (w != null) {
            w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        b.dialog.show();
        e.requestFocus();
    }

    // ---- control panel ------------------------------------------------------------------------------

    /** Opens the control panel for an entity. */
    static void show(Context c, String entityId, String title) {
        new HomeAssistantDialog(c, entityId).open(title);
    }

    private void open(String title) {
        built = ConsoleDialog.base(c, c.getString(R.string.ha_dialog_title));
        LinearLayout b = built.body;
        TextView name = Ui.oneLine(Ui.bold(c, title, 16, pal.c(1)));
        name.setGravity(Gravity.END);
        b.addView(name);
        status = Ui.bold(c, c.getString(R.string.ha_contacting), 22, pal.textHi);
        status.setTag("ha_status");
        b.addView(status);
        controls = new LinearLayout(c);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(0, Ui.dp(c, Ui.M), 0, 0);
        b.addView(controls);
        line = Ui.text(c, "", 13, pal.textLo);
        line.setPadding(0, Ui.dp(c, Ui.S), 0, Ui.dp(c, Ui.GUTTER));
        b.addView(line);
        ConsoleDialog.addOption(built, ConsoleDialog.option(c, c.getString(R.string.ha_close), pal.c(2), new View.OnClickListener() {
            @Override public void onClick(View v) { Sfx.play(c, Sfx.CLOSE); built.dialog.dismiss(); }
        }));
        built.dialog.show();
        Sfx.play(c, Sfx.OPEN);
        refresh(false);
    }

    private boolean alive() { return built != null && built.dialog.isShowing(); }

    /** Reads the entity and rebuilds the controls. */
    private void refresh(final boolean quiet) {
        HomeAssistant.async(new HomeAssistant.Job<HomeAssistant.Entity>() {
            @Override public HomeAssistant.Entity run() throws java.io.IOException { return HomeAssistant.state(app, entityId); }
        }, new HomeAssistant.Result<HomeAssistant.Entity>() {
            @Override public void done(HomeAssistant.Entity e, String error) {
                if (!alive()) return;
                if (error != null) {
                    status.setText(c.getString(R.string.ha_link_failed));
                    status.setTextColor(pal.alert);
                    line.setText(error);
                    if (!quiet) Sfx.play(c, Sfx.ERROR);
                    return;
                }
                entity = e;
                render();
            }
        });
    }

    /** Status text and the controls that suit the entity's domain. */
    private void render() {
        String domain = entity.domain();
        if (!entity.available()) {
            status.setText(c.getString(R.string.ha_unavailable));
            status.setTextColor(pal.alert);
        } else if ("scene".equals(domain) || "script".equals(domain)) {
            status.setText(c.getString(R.string.ha_ready));
            status.setTextColor(pal.c(2));
        } else {
            int pct = entity.brightnessPercent();
            status.setText(entity.on() && pct >= 0 ? c.getString(R.string.ha_status_level, pct)
                    : c.getString(R.string.ha_status, entity.state.toUpperCase(Locale.getDefault())));
            status.setTextColor(entity.on() ? pal.c(0) : pal.textLo);
        }
        controls.removeAllViews();
        if ("scene".equals(domain) || "script".equals(domain)) {
            controls.addView(key(c.getString(R.string.ha_activate), pal.c(0), "ha_activate", new Runnable() {
                @Override public void run() { send(HomeAssistant.primary(entityId)); }
            }), keyParams(-1, true));
            return;
        }
        LinearLayout power = new LinearLayout(c);
        power.addView(key(c.getString(R.string.ha_on), entity.on() ? pal.c(0) : Ui.alpha(pal.c(0), 0.4f), "ha_on", new Runnable() {
            @Override public void run() { send(HomeAssistant.power(entityId, true)); }
        }), keyParams(0, true));
        power.addView(key(c.getString(R.string.ha_off), !entity.on() ? pal.c(3) : Ui.alpha(pal.c(3), 0.4f), "ha_off", new Runnable() {
            @Override public void run() { send(HomeAssistant.power(entityId, false)); }
        }), keyParams(0, false));
        controls.addView(power);
        if (!"light".equals(domain)) return;
        if (entity.supportsBrightness()) addIntensity();
        if (entity.supportsColor()) addSpectrum();
    }

    /** INTENSITY slider (0..100 %), sent when the finger lifts. */
    private void addIntensity() {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(c, Ui.M), 0, 0);
        row.addView(Ui.bold(c, c.getString(R.string.ha_intensity), 13, pal.c(4)), new LinearLayout.LayoutParams(Ui.dp(c, 92), -2));
        SeekBar bar = new SeekBar(c);
        bar.setTag("ha_intensity");
        bar.setMax(100);
        bar.setProgress(Math.max(0, entity.on() ? entity.brightnessPercent() : 0));
        bar.setProgressTintList(ColorStateList.valueOf(pal.c(4)));
        bar.setThumbTintList(ColorStateList.valueOf(pal.textHi));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.alpha(pal.c(4), 0.3f)));
        bar.setContentDescription(c.getString(R.string.ha_intensity));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) line.setText(c.getString(R.string.ha_status_level, p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { Sfx.play(c, Sfx.TAP_SOFT); }
            @Override public void onStopTrackingTouch(SeekBar s) {
                int p = s.getProgress();
                send(p == 0 ? HomeAssistant.power(entityId, false) : HomeAssistant.light(entityId, p, -1));
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1));
        controls.addView(row);
    }

    /** SPECTRUM: a wrapping row of colour keys. */
    private void addSpectrum() {
        controls.addView(Ui.bold(c, c.getString(R.string.ha_spectrum), 13, pal.c(5)));
        int perRow = ScreenMetrics.of(c).isTwoPane() ? SPECTRUM.length : SPECTRUM.length / 2;
        LinearLayout row = null;
        for (int i = 0; i < SPECTRUM.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(c);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 40));
                lp.topMargin = Ui.dp(c, Ui.GUTTER);
                controls.addView(row, lp);
            }
            final int rgb = SPECTRUM[i];
            View swatch = new View(c);
            swatch.setTag("ha_color_" + i);
            swatch.setBackground(Ui.touch(Ui.shape(c, Color.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF), Ui.PILL, 0)));
            swatch.setContentDescription(c.getString(R.string.ha_color_cd, String.format(Locale.US, "#%06X", rgb)));
            swatch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Sfx.play(c, Sfx.TAP);
                    send(HomeAssistant.light(entityId, -1, rgb));
                }
            });
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, -1, 1);
            if (i % perRow != 0) sl.leftMargin = Ui.dp(c, Ui.GUTTER);
            row.addView(swatch, sl);
        }
    }

    private TextView key(String label, int color, String tag, final Runnable action) {
        TextView k = Ui.button(c, label, color, Ui.PILL, new View.OnClickListener() {
            @Override public void onClick(View v) { Sfx.play(c, Sfx.TAP); action.run(); }
        });
        k.setTag(tag);
        k.setGravity(Gravity.CENTER);
        Ui.setTextSp(k, 15);
        return k;
    }

    private LinearLayout.LayoutParams keyParams(int width, boolean first) {
        LinearLayout.LayoutParams lp = width == 0 ? new LinearLayout.LayoutParams(0, Ui.dp(c, KEY_DP), 1)
                : new LinearLayout.LayoutParams(-1, Ui.dp(c, KEY_DP));
        if (!first) lp.leftMargin = Ui.dp(c, Ui.GUTTER);
        return lp;
    }

    /** Sends a call, reports the result and reads the entity back. */
    private void send(final HomeAssistant.Call call) {
        if (call == null) return;
        line.setTextColor(pal.c(4));
        line.setText(c.getString(R.string.ha_transmitting));
        HomeAssistant.async(new HomeAssistant.Job<String>() {
            @Override public String run() throws java.io.IOException { return HomeAssistant.call(app, call); }
        }, new HomeAssistant.Result<String>() {
            @Override public void done(String state, String error) {
                if (!alive()) {
                    if (error == null) Sfx.play(app, Sfx.CONFIRM);
                    return;
                }
                if (error != null) {
                    Sfx.play(c, Sfx.ERROR);
                    line.setTextColor(pal.alert);
                    line.setText(c.getString(R.string.ha_unable, error));
                    return;
                }
                Sfx.play(c, Sfx.CONFIRM);
                line.setTextColor(pal.c(2));
                line.setText(c.getString(R.string.ha_confirmed));
                refresh(true);
            }
        });
    }
}
