package com.gungreeneyes.trekui;

import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The console shade: opened by swiping down on the console home screen. Quick controls, brightness and
 * volume, and (with notification access) the notification list. The system's own notification
 * panel stays one tap away. Nothing here runs while the shade is closed.
 *
 * <p>Responsive: on compact windows everything is one scrolling column; on wider windows
 * (landscape phones, tablets, unfolded foldables; {@link ScreenMetrics#isTwoPane()}) the controls
 * and sliders form the left pane and the notification log scrolls on its own in the right pane.
 * MainActivity rebuilds the shade on every configuration change, so the choice is made once here.
 */
class ShadeView extends FrameLayout {
    /** Sidebar width and the height of its upper blocks, quick-control row height, in dp (design scale). */
    private static final float SIDEBAR_DP = 58, SIDE_BLOCK_DP = 70, TILE_ROW_DP = 64;
    /** Most notifications listed. */
    private static final int MAX_NOTES = 40;
    /** Gamma used to map the brightness setting (0..255) onto the slider (0..1000) so it feels linear. */
    private static final double BRIGHTNESS_GAMMA = 2.2;
    private static final int SLIDER_MAX = 1000;

    private final MainActivity a;
    private final Palette pal;
    private final Prefs prefs;
    private final ConsoleFrame frame;
    /** The main (left, or only) scrolling column; MainActivity's swipe gestures check its edges. */
    private final ScrollView scroll;
    private final ScreenMetrics metrics;
    private final TextView clock, date, status;
    private final LinearLayout controls, notes, sliders;
    private final TextView notesHeader;
    private boolean open, updating;
    private CameraManager cams;
    private String torchId;
    private boolean torchOn;

    private final CameraManager.TorchCallback torchCb = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String id, boolean enabled) {
            if (id.equals(torchId)) { torchOn = enabled; buildControls(); }
        }
    };

    private final Runnable onNotes = new Runnable() {
        @Override public void run() { if (open) { buildNotes(); buildControls(); } }
    };

    ShadeView(MainActivity act) {
        super(act);
        a = act;
        prefs = Prefs.get(act);
        pal = prefs.palette();
        metrics = ScreenMetrics.of(act);
        boolean twoPane = metrics.isTwoPane();
        setBackgroundColor(Ui.alpha(Skin.bg(), 0.97f));
        setClickable(true);

        frame = new ConsoleFrame(act);
        frame.sidebarW = Math.max(Ui.dp(act, SIDEBAR_DP), metrics.scaledPx(SIDEBAR_DP));
        frame.colorTop = pal.c(3);
        frame.colorBot = pal.c(1);
        frame.topBlocks = new int[]{pal.c(0)};
        frame.botBlocks = new int[]{pal.c(2)};
        TextView title = Ui.bold(act, "OPERATIONS CONTROL", 26, pal.c(0));
        title.setPadding(Ui.dp(act, Ui.XS), 0, Ui.dp(act, Ui.HAIR), 0);
        frame.setTitle(title);
        status = Ui.text(act, "", 13, pal.c(1));
        status.setPadding(Ui.dp(act, Ui.XS), 0, Ui.dp(act, Ui.HAIR), 0);
        frame.setFooter(status);

        LinearLayout side = new LinearLayout(act);
        side.setOrientation(LinearLayout.VERTICAL);
        String[] codes = {"CTRL", "LOG", "47"};
        int[] cols = {pal.c(0), pal.c(2), pal.c(4)};
        for (int i = 0; i < codes.length; i++) {
            TextView t = Ui.bold(act, codes[i], 13, Ui.ink());
            t.setGravity(Gravity.BOTTOM | Gravity.END);
            t.setPadding(0, 0, Ui.dp(act, Ui.GUTTER), Ui.dp(act, Ui.XS));
            t.setBackground(Ui.shape(act, cols[i], Ui.SQUARE, 0));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, i == codes.length - 1 ? 0 : Ui.dp(act, SIDE_BLOCK_DP), i == codes.length - 1 ? 1 : 0);
            if (i > 0) lp.topMargin = Ui.dp(act, Ui.GUTTER);
            side.addView(t, lp);
        }
        frame.setSidebar(side);

        scroll = new ScrollView(act);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        // the notification log goes in its own column on wide windows
        LinearLayout logCol = col;
        ScrollView logScroll = null;
        if (twoPane) {
            logScroll = new ScrollView(act);
            logScroll.setVerticalScrollBarEnabled(false);
            logScroll.setOverScrollMode(OVER_SCROLL_NEVER);
            logCol = new LinearLayout(act);
            logCol.setOrientation(LinearLayout.VERTICAL);
            logScroll.addView(logCol);
        }

        LinearLayout clockRow = new LinearLayout(act);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = Ui.bold(act, "", 46, pal.textHi);
        clockRow.addView(clock);
        date = Ui.text(act, "", 16, pal.c(1));
        date.setPadding(Ui.dp(act, Ui.M), 0, 0, Ui.dp(act, Ui.S));
        clockRow.addView(date, new LinearLayout.LayoutParams(0, -2, 1));
        col.addView(clockRow);

        controls = new LinearLayout(act);
        controls.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = Ui.dp(act, Ui.M);
        col.addView(controls, cl);

        sliders = new LinearLayout(act);
        sliders.setOrientation(LinearLayout.VERTICAL);
        col.addView(sliders, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout nh = new LinearLayout(act);
        nh.setGravity(Gravity.CENTER_VERTICAL);
        nh.setPadding(0, twoPane ? 0 : Ui.dp(act, Ui.L), 0, Ui.dp(act, Ui.GUTTER));
        notesHeader = Ui.bold(act, "COMMUNICATIONS LOG", 18, pal.c(2));
        nh.addView(notesHeader, new LinearLayout.LayoutParams(0, -2, 1));
        TextView clear = Ui.button(act, "CLEAR ALL", pal.c(3), Ui.PILL, new OnClickListener() {
            @Override public void onClick(View v) { clearAll(); }
        });
        clear.setGravity(Gravity.CENTER);
        clear.setTextSize((13) * Skin.textScale());
        clear.setTag("clear");
        nh.addView(clear, new LinearLayout.LayoutParams(Ui.dp(act, 104), Ui.dp(act, 40)));
        logCol.addView(nh);
        notes = new LinearLayout(act);
        notes.setOrientation(LinearLayout.VERTICAL);
        logCol.addView(notes, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout foot = new LinearLayout(act);
        foot.setPadding(0, Ui.dp(act, Ui.L), 0, Ui.dp(act, Ui.GUTTER));
        String systemPanel = "samsung".equalsIgnoreCase(Build.MANUFACTURER) ? "SAMSUNG PANEL" : "SYSTEM PANEL";
        TextView samsung = Ui.button(act, systemPanel, pal.c(1), Ui.LEFT, new OnClickListener() {
            @Override public void onClick(View v) { close(false); a.expandNotifications(); }
        });
        samsung.setGravity(Gravity.CENTER);
        samsung.setTextSize((14) * Skin.textScale());
        foot.addView(samsung, new LinearLayout.LayoutParams(0, Ui.dp(act, 48), 1));
        TextView done = Ui.button(act, "CLOSE", pal.c(0), Ui.RIGHT, new OnClickListener() {
            @Override public void onClick(View v) { close(true); }
        });
        done.setGravity(Gravity.CENTER);
        done.setTextSize((14) * Skin.textScale());
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, Ui.dp(act, 48), 1);
        dl.leftMargin = Ui.dp(act, Ui.GUTTER);
        foot.addView(done, dl);
        col.addView(foot);

        scroll.addView(col);
        if (twoPane) {
            LinearLayout panes = new LinearLayout(act);
            panes.setOrientation(LinearLayout.HORIZONTAL);
            panes.addView(scroll, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, -1, 1);
            rl.leftMargin = metrics.gutterPx() * 2;
            panes.addView(logScroll, rl);
            frame.setContent(panes);
        } else {
            frame.setContent(scroll);
        }
        addView(frame, new LayoutParams(-1, -1));

        cams = (CameraManager) act.getSystemService(Context.CAMERA_SERVICE);
        try {
            if (cams != null) for (String id : cams.getCameraIdList()) {
                CameraCharacteristics ch = cams.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))
                        && facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { torchId = id; break; }
            }
        } catch (Exception ignored) {
        }
    }

    /** Keeps the shade clear of the status and navigation bars (top and bottom insets in px). */
    void setInsets(int top, int bottom) {
        setInsets(0, top, 0, bottom);
    }

    /**
     * Keeps the shade clear of system bars and display cutouts on every edge (insets in px);
     * the side padding grows to the window margin on wide windows.
     */
    void setInsets(int left, int top, int right, int bottom) {
        int g = metrics.isTwoPane() ? metrics.marginPx() : Ui.dp(getContext(), 8);
        setPadding(left + g, top + Ui.dp(getContext(), Ui.XS), right + g, bottom + Ui.dp(getContext(), Ui.GUTTER));
    }

    boolean isOpen() { return open; }

    /** True when the main column can't scroll further down / up (MainActivity's close gestures). */

    boolean atBottom() { return !scroll.canScrollVertically(1); }
    boolean atTop() { return !scroll.canScrollVertically(-1); }

    /** Shows the shade (sliding down when animated) and starts live updates. */
    void open(boolean animate) {
        if (open) return;
        open = true;
        Sfx.play(getContext(), Sfx.OPEN);
        setVisibility(VISIBLE);
        scroll.scrollTo(0, 0);
        startUpdates();
        if (animate) {
            setAlpha(0f);
            setTranslationY(-Ui.dp(getContext(), 60));
            animate().alpha(1f).translationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator(1.5f)).withEndAction(null).start();
            frame.reveal();
        } else {
            setAlpha(1f);
            setTranslationY(0);
        }
    }

    /** Hides the shade (sliding up when animated) and stops live updates. */
    void close(boolean animate) {
        if (!open) return;
        open = false;
        stopUpdates();
        Sfx.play(getContext(), Sfx.CLOSE);
        if (animate && Ui.animate(getContext())) {
            animate().alpha(0f).translationY(-Ui.dp(getContext(), 50)).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator()).withEndAction(new Runnable() {
                        @Override public void run() { if (!open) setVisibility(GONE); }
                    }).start();
        } else {
            animate().cancel();
            setVisibility(GONE);
        }
    }

    /** Called from MainActivity.onPause / onResume while the shade is open. */
    void stopUpdates() {
        if (!updating) return;
        updating = false;
        MediaListener.setOnChange(null);
        if (cams != null && torchId != null) {
            try { cams.unregisterTorchCallback(torchCb); } catch (Exception ignored) { }
        }
    }

    /** Starts notification and torch callbacks and refreshes everything (only while open). */
    void startUpdates() {
        if (!open || updating) return;
        updating = true;
        MediaListener.setOnChange(onNotes);
        if (cams != null && torchId != null) {
            try { cams.registerTorchCallback(torchCb, a.handler()); } catch (Exception ignored) { }
        }
        refresh();
    }

    /** Re-reads clock, power, quick controls, sliders and notifications. */
    void refresh() {
        Date d = new Date();
        clock.setText(new SimpleDateFormat(a.use24h() ? "HH:mm" : "h:mm", Locale.getDefault()).format(d));
        String dp = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd");
        date.setText(new SimpleDateFormat(dp, Locale.getDefault()).format(d).toUpperCase(Locale.getDefault()));
        Intent b = a.battery();
        status.setText(b != null ? "PWR " + MainActivity.batteryPct(b) + "%" : "");
        buildControls();
        buildSliders();
        buildNotes();
    }

    // ---- quick controls ---------------------------------------------------------------------------

    private interface Act { void run(View v); }

    /** One quick-control key: label, state line, lit when on. */
    private void tile(LinearLayout row, String label, String state, boolean on, int color, int kind, final Act act) {
        TextView t = Ui.button(getContext(), label, on ? color : Ui.darken(color, 0.38f), kind, null);
        t.setOnClickListener(Ui.feedback(new OnClickListener() {
            @Override public void onClick(View v) { act.run(v); }
        }));
        t.setText(label + (state != null ? "\n" + state : ""));
        t.setSingleLine(false);
        t.setMaxLines(2);
        t.setTextSize((13) * Skin.textScale());
        t.setLineSpacing(0, 0.95f);
        t.setPadding(Ui.dp(getContext(), Ui.XS), 0, Ui.dp(getContext(), Ui.S), Ui.dp(getContext(), Ui.GUTTER));
        if (!on) t.setTextColor(Ui.alpha(Ui.ink(), 0.85f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
        if (row.getChildCount() > 0) lp.leftMargin = Ui.dp(getContext(), Ui.XS);
        row.addView(t, lp);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(getContext(), TILE_ROW_DP));
        if (controls.getChildCount() > 0) lp.topMargin = Ui.dp(getContext(), Ui.XS);
        controls.addView(r, lp);
        return r;
    }

    /** Two rows of four quick controls reflecting the current system state. */
    private void buildControls() {
        controls.removeAllViews();
        Context c = getContext();
        String net = network();
        LinearLayout r1 = row();
        tile(r1, "NETWORK", net, !"OFFLINE".equals(net), pal.c(0), Ui.LEFT, new Act() {
            @Override public void run(View v) {
                a.startSafe(new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIFI_SETTINGS), v);
            }
        });
        boolean bt = bluetoothOn();
        tile(r1, "BLUETOOTH", bt ? "ON" : "OFF", bt, pal.c(2), Ui.SQUARE, new Act() {
            @Override public void run(View v) { a.startSafe(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS), v); }
        });
        tile(r1, "TORCH", torchOn ? "ON" : "OFF", torchOn, pal.c(4), Ui.SQUARE, new Act() {
            @Override public void run(View v) { toggleTorch(); }
        });
        final boolean dnd = dndOn();
        tile(r1, "SILENCE", dnd ? "DND ON" : "OFF", dnd, pal.c(3), Ui.RIGHT, new Act() {
            @Override public void run(View v) { toggleDnd(v, dnd); }
        });
        LinearLayout r2 = row();
        final boolean rot = rotationOn();
        tile(r2, "ROTATE", rot ? "AUTO" : "LOCKED", rot, pal.c(1), Ui.LEFT, new Act() {
            @Override public void run(View v) { toggleRotation(v, rot); }
        });
        boolean loc = locationOn();
        tile(r2, "LOCATION", loc ? "ON" : "OFF", loc, pal.c(5), Ui.SQUARE, new Act() {
            @Override public void run(View v) { a.startSafe(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), v); }
        });
        boolean nfc = nfcOn();
        tile(r2, "NFC", nfc ? "ON" : "OFF", nfc, pal.c(0), Ui.SQUARE, new Act() {
            @Override public void run(View v) {
                a.startSafe(new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_NFC : Settings.ACTION_NFC_SETTINGS), v);
            }
        });
        tile(r2, "SETTINGS", null, true, pal.c(2), Ui.RIGHT, new Act() {
            @Override public void run(View v) { a.startSafe(new Intent(Settings.ACTION_SETTINGS), v); }
        });
    }

    /** Active network type for the NETWORK tile. */
    private String network() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) return "OFFLINE";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WI-FI";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "CELLULAR";
            return "ONLINE";
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private boolean bluetoothOn() {
        try {
            BluetoothManager bm = (BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm != null ? bm.getAdapter() : null;
            return ad != null && ad.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean locationOn() {
        try {
            LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 28) return lm.isLocationEnabled();
            return lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean nfcOn() {
        try {
            NfcAdapter n = NfcAdapter.getDefaultAdapter(getContext());
            return n != null && n.isEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean rotationOn() {
        try {
            return Settings.System.getInt(getContext().getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean canWrite() {
        return Settings.System.canWrite(getContext());
    }

    /** Explains and opens the "Modify system settings" permission. */
    private void askWrite() {
        Sfx.play(getContext(), Sfx.DENY);
        a.showing(ConsoleDialog.message(getContext(), "SYSTEM CONTROL ACCESS",
                "To change brightness and auto-rotate from the TrekUI shade, allow TrekUI to “Modify system settings”. "
                        + "It is used only when you move these controls.",
                new String[]{"OPEN PERMISSION", "CANCEL"}, new ConsoleDialog.OnPick() {
                    @Override public void pick(int w) {
                        if (w == 0) a.startSafe(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:" + getContext().getPackageName())), null);
                    }
                }));
    }

    private void toggleRotation(View v, boolean on) {
        if (!canWrite()) { askWrite(); return; }
        try {
            Settings.System.putInt(getContext().getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, on ? 0 : 1);
        } catch (Exception e) {
            Sfx.play(getContext(), Sfx.ERROR);
        }
        buildControls();
    }

    private boolean dndOn() {
        NotificationManager nm = (NotificationManager) getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && nm.getCurrentInterruptionFilter() > NotificationManager.INTERRUPTION_FILTER_ALL;
    }

    /** Toggles Do Not Disturb through the notification listener, else opens the DND settings. */
    private void toggleDnd(View v, boolean on) {
        MediaListener l = MediaListener.get();
        if (l != null) {
            try {
                l.requestInterruptionFilter(on ? MediaListener.INTERRUPTION_FILTER_ALL : MediaListener.INTERRUPTION_FILTER_PRIORITY);
                a.handler().postDelayed(new Runnable() { @Override public void run() { if (open) buildControls(); } }, 400);
                return;
            } catch (Exception ignored) {
            }
        }
        if (!a.startSafe(new Intent("android.settings.ZEN_MODE_SETTINGS"), v)) a.startSafe(new Intent(Settings.ACTION_SOUND_SETTINGS), v);
    }

    private void toggleTorch() {
        if (torchId == null || cams == null) { Sfx.play(getContext(), Sfx.DENY); return; }
        try {
            cams.setTorchMode(torchId, !torchOn);
        } catch (Exception e) {
            Sfx.play(getContext(), Sfx.ERROR);
        }
    }

    // ---- brightness & volume ----------------------------------------------------------------------

    /** Brightness (gamma-mapped, with AUTO/MANUAL) and media volume sliders. */
    private void buildSliders() {
        sliders.removeAllViews();
        final Context c = getContext();
        // Brightness
        boolean auto = false;
        int bright = 128;
        try {
            auto = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
            bright = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS);
        } catch (Exception ignored) {
        }
        final boolean autoNow = auto;
        int pos = sliderForBrightness(bright);
        sliderRow("ILLUMINATION", pal.c(4), SLIDER_MAX, pos, new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (!user || !canWrite()) return;
                int v = brightnessForSlider(p);
                try {
                    Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                    Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, v);
                } catch (Exception ignored) {
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) { if (!canWrite()) askWrite(); }
            @Override public void onStopTrackingTouch(SeekBar s) { if (canWrite()) buildSliders(); }
        }, autoNow ? "AUTO" : "MANUAL", autoNow, new OnClickListener() {
            @Override public void onClick(View v) {
                if (!canWrite()) { askWrite(); return; }
                try {
                    Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                            autoNow ? Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL : Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
                } catch (Exception ignored) {
                }
                buildSliders();
            }
        });
        // Media volume
        final AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            final int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            sliderRow("AUDIO LEVEL", pal.c(2), max, am.getStreamVolume(AudioManager.STREAM_MUSIC), new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean user) {
                    if (!user) return;
                    try { am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0); } catch (Exception ignored) { }
                }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { }
            }, "SOUND", false, new OnClickListener() {
                @Override public void onClick(View v) {
                    if (!a.startSafe(new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_VOLUME : Settings.ACTION_SOUND_SETTINGS), v)) {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
                    }
                }
            });
        }
    }

    /** Slider position (0..1000) for a system brightness value (0..255). */
    static int sliderForBrightness(int brightness) {
        return (int) Math.round(SLIDER_MAX * Math.pow(Math.max(0, Math.min(255, brightness)) / 255.0, 1 / BRIGHTNESS_GAMMA));
    }

    /** System brightness value (1..255) for a slider position (0..1000). */
    static int brightnessForSlider(int position) {
        double f = Math.max(0, Math.min(SLIDER_MAX, position)) / (double) SLIDER_MAX;
        return Math.max(1, (int) Math.round(255 * Math.pow(f, BRIGHTNESS_GAMMA)));
    }

    /** A labelled slider with a button at its end. */
    private void sliderRow(String label, int color, int max, int value, SeekBar.OnSeekBarChangeListener l,
                           String btn, boolean btnOn, OnClickListener bl) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(c, Ui.M), 0, 0);
        TextView t = Ui.bold(c, label, 13, color);
        r.addView(t, new LinearLayout.LayoutParams(Ui.dp(c, 84), -2));
        SeekBar sb = new SeekBar(c);
        sb.setMax(max);
        sb.setProgress(value);
        sb.setProgressTintList(ColorStateList.valueOf(color));
        sb.setThumbTintList(ColorStateList.valueOf(pal.textHi));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.alpha(color, 0.3f)));
        sb.setOnSeekBarChangeListener(l);
        r.addView(sb, new LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1));
        TextView b = Ui.button(c, btn, btnOn ? color : Ui.darken(color, 0.4f), Ui.PILL, null);
        b.setOnClickListener(Ui.feedback(bl));
        b.setGravity(Gravity.CENTER);
        b.setTextSize((12) * Skin.textScale());
        r.addView(b, new LinearLayout.LayoutParams(Ui.dp(c, 72), Ui.dp(c, 38)));
        sliders.addView(r);
    }

    // ---- notifications ------------------------------------------------------------------------------

    private boolean hasAccess() {
        String flat = Settings.Secure.getString(getContext().getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new android.content.ComponentName(getContext(), MediaListener.class).flattenToString());
    }

    /** The notification log (newest first), or the reason it is empty. */
    private void buildNotes() {
        notes.removeAllViews();
        View clear = ((View) notesHeader.getParent()).findViewWithTag("clear");
        Context c = getContext();
        if (!prefs.b("shadeNotes", true)) {
            notesHeader.setText("COMMUNICATIONS LOG • OFF");
            clear.setVisibility(GONE);
            return;
        }
        MediaListener l = MediaListener.get();
        if (!hasAccess() || l == null) {
            notesHeader.setText("COMMUNICATIONS LOG");
            clear.setVisibility(GONE);
            TextView t = Ui.text(c, hasAccess() ? "CONNECTING TO NOTIFICATION SERVICE…"
                    : "To list notifications here, grant TrekUI notification access (the same access that shows track info in the audio panel).", 14, pal.textLo);
            t.setLineSpacing(0, 1.1f);
            notes.addView(t);
            if (!hasAccess()) {
                TextView g = Ui.button(c, "GRANT NOTIFICATION ACCESS", pal.c(2), Ui.PILL, new OnClickListener() {
                    @Override public void onClick(View v) { a.startSafe(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), v); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 48));
                lp.topMargin = Ui.dp(c, Ui.S);
                notes.addView(g, lp);
            }
            return;
        }
        List<StatusBarNotification> list = new ArrayList<>();
        try {
            StatusBarNotification[] all = l.getActiveNotifications();
            if (all != null) list.addAll(Arrays.asList(all));
        } catch (Exception ignored) {
        }
        List<StatusBarNotification> shown = new ArrayList<>();
        for (StatusBarNotification s : list) {
            if (s.getPackageName().equals(c.getPackageName())) continue;
            Notification n = s.getNotification();
            if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
            if (title(n) == null && body(n) == null) continue;
            shown.add(s);
        }
        Collections.sort(shown, new Comparator<StatusBarNotification>() {
            @Override public int compare(StatusBarNotification x, StatusBarNotification y) { return Long.compare(y.getPostTime(), x.getPostTime()); }
        });
        boolean anyClearable = false;
        for (StatusBarNotification s : shown) if (s.isClearable()) { anyClearable = true; break; }
        clear.setVisibility(anyClearable ? VISIBLE : GONE);
        notesHeader.setText("COMMUNICATIONS LOG • " + shown.size());
        if (shown.isEmpty()) {
            notes.addView(Ui.text(c, "NO ACTIVE NOTIFICATIONS", 14, pal.textLo));
            return;
        }
        for (int i = 0; i < shown.size() && i < MAX_NOTES; i++) notes.addView(noteRow(shown.get(i), i));
    }

    private static CharSequence title(Notification n) {
        Bundle e = n.extras;
        CharSequence t = e != null ? e.getCharSequence(Notification.EXTRA_TITLE) : null;
        return t != null && t.length() > 0 ? t : null;
    }

    private static CharSequence body(Notification n) {
        Bundle e = n.extras;
        if (e == null) return null;
        CharSequence t = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (t == null || t.length() == 0) t = e.getCharSequence(Notification.EXTRA_TEXT);
        return t != null && t.length() > 0 ? t : null;
    }

    /** One notification: app and age, title, two lines of text, REPLY / action keys, dismiss key; tap opens it. */
    private View noteRow(final StatusBarNotification s, int i) {
        Context c = getContext();
        final Notification n = s.getNotification();
        int color = pal.c(i % 6);
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(c, Ui.GUTTER), 0, Ui.dp(c, Ui.GUTTER));
        row.setBackground(Ui.touchArea(c, 12));
        View bar = new View(c);
        bar.setBackground(Ui.shape(c, color, Ui.PILL, 0));
        row.addView(bar, new LinearLayout.LayoutParams(Ui.dp(c, 7), Ui.dp(c, 48)));
        ImageView icon = new ImageView(c);
        AppInfo ai = AppRepo.get(c).findPackage(s.getPackageName());
        Drawable d = ai != null && ai.rawIcon != null ? IconFactory.copy(ai.rawIcon) : null;
        if (d == null && n.getSmallIcon() != null) {
            try { d = n.getSmallIcon().loadDrawable(c); } catch (Exception ignored) { }
            if (d != null) icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
        icon.setImageDrawable(d);
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(Ui.dp(c, 34), Ui.dp(c, 34));
        il.leftMargin = Ui.dp(c, Ui.M);
        row.addView(icon, il);
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, Ui.M), 0, Ui.dp(c, Ui.GUTTER), 0);
        String app = ai != null ? ai.label : s.getPackageName();
        col.addView(Ui.oneLine(Ui.text(c, (app + "  •  " + ago(s.getPostTime())).toUpperCase(Locale.getDefault()), 11, color)));
        CharSequence t = title(n), b = body(n);
        if (t != null) col.addView(Ui.oneLine(Ui.bold(c, t, 16, pal.textHi)));
        if (b != null) {
            TextView bt = Ui.text(c, b, 14, pal.textLo);
            bt.setMaxLines(2);
            bt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(bt);
        }
        NotificationActions.addTo(a, col, s, color); // LCA-T23: REPLY and the app's actions
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        if (s.isClearable()) {
            TextView x = Ui.button(c, "×", Ui.darken(color, 0.55f), Ui.PILL, null);
            x.setGravity(Gravity.CENTER);
            x.setTextSize((20) * Skin.textScale());
            x.setPadding(0, 0, 0, 0);
            x.setContentDescription("Dismiss");
            x.setOnClickListener(Ui.feedbackSoft(new OnClickListener() {
                @Override public void onClick(View v) {
                    MediaListener l = MediaListener.get();
                    if (l != null) try { l.cancelNotification(s.getKey()); } catch (Exception ignored) { }
                }
            }));
            row.addView(x, new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        }
        row.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (n.contentIntent == null) { Sfx.play(getContext(), Sfx.DENY); return; }
                Sfx.play(getContext(), Sfx.LAUNCH);
                if (a.sendPending(n.contentIntent)) {
                    if ((n.flags & Notification.FLAG_AUTO_CANCEL) != 0) {
                        MediaListener l = MediaListener.get();
                        if (l != null) try { l.cancelNotification(s.getKey()); } catch (Exception ignored) { }
                    }
                    close(false);
                } else {
                    Sfx.play(getContext(), Sfx.ERROR);
                }
            }
        });
        return row;
    }

    private String ago(long t) {
        return ago(System.currentTimeMillis(), t, Locale.getDefault());
    }

    /** Age of a notification posted at t, seen at now: NOW, 5M, 3H, or the date. */
    static String ago(long now, long t, Locale locale) {
        long m = (now - t) / 60000;
        if (m < 1) return "NOW";
        if (m < 60) return m + "M";
        if (m < 24 * 60) return (m / 60) + "H";
        return new SimpleDateFormat("MMM d", locale).format(new Date(t));
    }

    private void clearAll() {
        MediaListener l = MediaListener.get();
        if (l == null) return;
        try { l.cancelAllNotifications(); } catch (Exception ignored) { }
        Sfx.play(getContext(), Sfx.CONFIRM);
    }
}
