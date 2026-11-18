package com.gungreeneyes.trekui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Media panel with an console source selector (default: Audible, YouTube Music, VLC). Choosing a
 * source never opens the app; it re-targets what the panel shows and controls:
 *   1. the app's live media session (needs the optional notification access), else
 *   2. a link to the app's MediaBrowserService (the background service Android Auto uses), else
 *   3. media-button key events addressed to that app only.
 * With no source configured the transport keys drive whatever is playing (system media keys).
 *
 * <p>Session listeners, controller callbacks and the browser link exist only between resume()
 * and pause(); the browser and controllers use the application context, so nothing outlives the
 * activity. The source-name rules are pure ({@link #knownName}, {@link #abbreviate}).
 */
class MediaPanel extends Panel {
    static final String[] DEFAULT_SOURCES = {"com.audible.application", "com.google.android.apps.youtube.music", "org.videolan.vlc"};
    static final int SLOTS = 3;
    private static final String BROWSER_ACTION = "android.media.browse.MediaBrowserService";
    /** State of the MediaBrowser link to the selected app. */
    private static final int LINK_IDLE = 0, LINK_CONNECTING = 1, LINK_UP = 2, LINK_FAILED = 3;
    /** Longest source-button label. */
    static final int MAX_NAME = 10;
    /** Re-check for a session this long after sending a key to an app without one; redraw after any key. */
    private static final long REATTACH_MS = 900, RERENDER_MS = 600;
    /** Artwork size, source row and transport row heights, in dp at the design scale. */
    private static final float ART_DP = 66, SOURCE_ROW_DP = 44, TRANSPORT_ROW_DP = 54;

    private ImageView art;
    private TextView track, artist, hint;
    private ImageView playBtn;
    private LinearLayout sourceRow;
    private MediaSessionManager msm;
    private MediaController controller;
    private boolean controllerFromLink;
    private ComponentName listener;
    private boolean listening, resumed;
    private MediaBrowser browser;
    private int link = LINK_IDLE;
    private final Prefs prefs;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override public void onActiveSessionsChanged(List<MediaController> list) { onSessions(list); }
            };

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { render(); }
        @Override public void onMetadataChanged(MediaMetadata m) { render(); }
        @Override public void onSessionDestroyed() { setController(null, false); if (resumed) attach(); }
    };

    MediaPanel(MainActivity a, int accent) {
        super(a, accent);
        prefs = Prefs.get(a);
    }

    @Override public String id() { return Prefs.W_MEDIA; }
    @Override public String title() { return "AUDIO SYSTEMS"; }

    // ---- sources ---------------------------------------------------------------------------------

    /** The package assigned to a source button (defaults: Audible, YouTube Music, VLC). */
    private String source(int slot) {
        return prefs.s("mediaSrc" + slot, slot < DEFAULT_SOURCES.length ? DEFAULT_SOURCES[slot] : "");
    }

    private boolean installed(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            a.getPackageManager().getApplicationInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Installed, distinct source packages in button order. */
    private List<String> activeSources() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            String p = source(i);
            if (installed(p) && !out.contains(p)) out.add(p);
        }
        return out;
    }

    /** The selected source if still a button, else the first; null when there are none. */
    private String selected() {
        List<String> src = activeSources();
        String sel = prefs.s("mediaSel", null);
        if (sel != null && src.contains(sel)) return sel;
        return src.isEmpty() ? null : src.get(0);
    }

    /** Button label for a source: a known short name, else the app label (or package tail) abbreviated. */
    static String shortName(Context c, String pkg) {
        if (pkg == null) return "";
        String known = knownName(pkg);
        if (known != null) return known;
        AppInfo ai = AppRepo.get(c).findPackage(pkg);
        return abbreviate(ai != null ? ai.label : pkg.substring(pkg.lastIndexOf('.') + 1));
    }

    /** Short names for well-known players, or null. */
    static String knownName(String pkg) {
        switch (pkg) {
            case "com.audible.application": return "AUDIBLE";
            case "com.google.android.apps.youtube.music": return "YT MUSIC";
            case "org.videolan.vlc": return "VLC";
            case "com.spotify.music": return "SPOTIFY";
            default: return null;
        }
    }

    /** Upper-case and cut to {@link #MAX_NAME} characters. */
    static String abbreviate(String label) {
        String l = label == null ? "" : label.trim().toUpperCase(Locale.getDefault());
        return l.length() > MAX_NAME ? l.substring(0, MAX_NAME) : l;
    }

    /** Services that answer the MediaBrowserService action (optionally in one package). */
    private List<ResolveInfo> browserServices(String pkg) {
        Intent i = new Intent(BROWSER_ACTION);
        if (pkg != null) i.setPackage(pkg);
        PackageManager pm = a.getPackageManager();
        if (Build.VERSION.SDK_INT >= 33) return pm.queryIntentServices(i, PackageManager.ResolveInfoFlags.of(0));
        return legacyQueryServices(pm, i);
    }

    @SuppressWarnings("deprecation") // int-flags form is the only one before API 33
    private static List<ResolveInfo> legacyQueryServices(PackageManager pm, Intent i) {
        return pm.queryIntentServices(i, 0);
    }

    /** Rebuilds the row of source buttons (plus "+" while a slot is free). */
    private void buildSources() {
        sourceRow.removeAllViews();
        final List<String> src = activeSources();
        String sel = selected();
        boolean room = src.size() < SLOTS;
        int total = src.size() + (room ? 1 : 0);
        for (int i = 0; i < total; i++) {
            boolean add = i == src.size();
            final String pkg = add ? null : src.get(i);
            boolean on = pkg != null && pkg.equals(sel);
            int kind = total == 1 ? Ui.PILL : i == 0 ? Ui.LEFT : i == total - 1 ? Ui.RIGHT : Ui.SQUARE;
            int color = add ? Ui.darken(pal.c(1), 0.35f) : on ? accent : Ui.darken(pal.c(1), 0.5f);
            TextView b = Ui.button(a, add ? (src.isEmpty() ? "+ ADD AUDIO SOURCE" : "+") : shortName(a, pkg), color, kind, null);
            b.setGravity(Gravity.CENTER);
            b.setTextSize((14) * Skin.textScale());
            b.setPadding(Ui.dp(a, Ui.XS), 0, Ui.dp(a, Ui.XS), 0);
            b.setContentDescription(add ? "Add audio source" : "Audio source " + shortName(a, pkg));
            final int slotIndex = add ? firstFreeSlot() : slotOf(pkg);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (pkg == null) { Ui.tap(v); chooseApp(slotIndex); }
                    else select(pkg);
                }
            });
            b.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { Ui.longPress(v); chooseApp(slotIndex); return true; }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, add && !src.isEmpty() ? 0.45f : 1f);
            if (i > 0) lp.leftMargin = Ui.dp(a, Ui.XS);
            sourceRow.addView(b, lp);
        }
    }

    private int slotOf(String pkg) {
        for (int i = 0; i < SLOTS; i++) if (pkg.equals(source(i))) return i;
        return 0;
    }

    private int firstFreeSlot() {
        for (int i = 0; i < SLOTS; i++) if (!installed(source(i))) return i;
        return SLOTS - 1;
    }

    /** Re-targets the panel at another source (never opens the app). */
    private void select(String pkg) {
        if (pkg.equals(selected()) && (controller != null || link == LINK_CONNECTING)) {
            Sfx.play(a, Sfx.PAGE);
            return;
        }
        prefs.put("mediaSel", pkg);
        Sfx.play(a, Sfx.PAGE);
        disconnectLink();
        setController(null, false);
        buildSources();
        attach();
    }

    /** Media-capable apps: those exposing a MediaBrowserService, plus launchable MEDIA-category apps. */
    private void chooseApp(final int slot) {
        List<String> pkgs = new ArrayList<>();
        try {
            for (ResolveInfo ri : browserServices(null)) {
                if (ri.serviceInfo != null && !pkgs.contains(ri.serviceInfo.packageName)) pkgs.add(ri.serviceInfo.packageName);
            }
        } catch (RuntimeException ignored) {
            // package manager unavailable: fall back to media-category apps
        }
        for (AppInfo ai : AppRepo.get(a).apps()) {
            if (ai.mainProfile && ai.category == AppInfo.CAT_MEDIA && !pkgs.contains(ai.pkg)) pkgs.add(ai.pkg);
        }
        final List<String> choices = new ArrayList<>();
        for (String p : pkgs) if (AppRepo.get(a).findPackage(p) != null && !p.equals(a.getPackageName())) choices.add(p);
        Collections.sort(choices, new Comparator<String>() {
            @Override public int compare(String x, String y) { return shortName(a, x).compareTo(shortName(a, y)); }
        });
        List<String> labels = new ArrayList<>();
        for (String p : choices) {
            AppInfo ai = AppRepo.get(a).findPackage(p);
            labels.add((p.equals(source(slot)) ? "● " : "") + (ai != null ? ai.label.toUpperCase(Locale.getDefault()) : p));
        }
        final boolean canRemove = installed(source(slot));
        if (canRemove) labels.add("REMOVE THIS BUTTON");
        labels.add("RESTORE DEFAULTS (AUDIBLE • YT MUSIC • VLC)");
        a.showing(ConsoleDialog.options(a, "AUDIO SOURCE " + (slot + 1), labels.toArray(new String[0]), new ConsoleDialog.OnPick() {
            @Override public void pick(int w) {
                if (w < choices.size()) {
                    String p = choices.get(w);
                    for (int i = 0; i < SLOTS; i++) if (i != slot && p.equals(source(i))) prefs.put("mediaSrc" + i, "");
                    prefs.put("mediaSrc" + slot, p);
                    prefs.put("mediaSel", p);
                } else if (canRemove && w == choices.size()) {
                    prefs.put("mediaSrc" + slot, "");
                } else {
                    for (int i = 0; i < SLOTS; i++) prefs.sp.edit().remove("mediaSrc" + i).apply();
                }
                Sfx.play(a, Sfx.CONFIRM);
                disconnectLink();
                setController(null, false);
                buildSources();
                attach();
            }
        }));
    }

    @Override public String[] extraMenu() {
        String sel = selected();
        return sel == null ? new String[]{"CHANGE SOURCE BUTTONS"} : new String[]{"OPEN " + shortName(a, sel), "CHANGE SOURCE BUTTONS"};
    }

    @Override public void onExtraMenu(int w) {
        String sel = selected();
        if (sel != null && w == 0) openApp(null);
        else {
            String[] slots = new String[SLOTS];
            for (int i = 0; i < SLOTS; i++) slots[i] = "BUTTON " + (i + 1) + ": " + (installed(source(i)) ? shortName(a, source(i)) : "EMPTY");
            a.showing(ConsoleDialog.options(a, "SOURCE BUTTONS", slots, new ConsoleDialog.OnPick() {
                @Override public void pick(int s) { chooseApp(s); }
            }));
        }
    }

    // ---- UI --------------------------------------------------------------------------------------

    @Override
    public void build() {
        msm = (MediaSessionManager) a.getSystemService(Context.MEDIA_SESSION_SERVICE);
        listener = new ComponentName(a, MediaListener.class);

        sourceRow = new LinearLayout(a);
        ScreenMetrics m = ScreenMetrics.of(a);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, Ui.dp(a, SOURCE_ROW_DP));
        sl.bottomMargin = Ui.dp(a, Ui.M);
        card.body.addView(sourceRow, sl);

        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout artBox = new FrameLayout(a);
        artBox.setBackground(Ui.outline(a, pal.panel, accent, 14, 2));
        art = new ImageView(a);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), Ui.dp(a, 12)); }
        });
        art.setClipToOutline(true);
        artBox.addView(art, new FrameLayout.LayoutParams(-1, -1));
        int artPx = Math.max(Ui.dp(a, ART_DP), m.scaledPx(ART_DP));
        row.addView(artBox, new LinearLayout.LayoutParams(artPx, artPx));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(a, Ui.M), 0, 0, 0);
        track = Ui.oneLine(Ui.bold(a, "STANDBY", 19, pal.textHi));
        artist = Ui.oneLine(Ui.text(a, "", 14, pal.textLo));
        col.addView(track);
        col.addView(artist);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        // Opening the player is deliberate only: long-press the artwork (or use the panel menu).
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { Ui.longPress(v); openApp(v); return true; }
        });
        card.body.addView(row);

        LinearLayout ctl = new LinearLayout(a);
        transport(ctl, MediaGlyph.PREV, pal.c(1), Ui.LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        playBtn = transport(ctl, MediaGlyph.PLAY, accent, Ui.SQUARE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        transport(ctl, MediaGlyph.NEXT, pal.c(1), Ui.RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, Ui.dp(a, TRANSPORT_ROW_DP));
        cl.topMargin = Ui.dp(a, Ui.M);
        card.body.addView(ctl, cl);

        hint = Ui.text(a, "TIP: GRANT NOTIFICATION ACCESS FOR LIVE TRACK INFO FROM EVERY SOURCE", 12, Ui.alpha(pal.textLo, 0.75f));
        hint.setPadding(0, Ui.dp(a, Ui.S), 0, 0);
        hint.setOnClickListener(Ui.feedback(new View.OnClickListener() {
            @Override public void onClick(View v) { explainAccess(); }
        }));
        card.body.addView(hint);
        buildSources();
        render();
    }

    /** A transport key (previous / play-pause / next). */
    private ImageView transport(LinearLayout ctl, int glyph, int color, int shape, final int key) {
        ImageView b = new ImageView(a);
        b.setImageDrawable(new MediaGlyph(glyph));
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = Ui.dp(a, 14);
        b.setPadding(p, p, p, p);
        b.setBackground(Ui.touch(Ui.shape(a, color, shape, 22)));
        b.setContentDescription(glyph == MediaGlyph.PREV ? "Previous" : glyph == MediaGlyph.NEXT ? "Next" : "Play or pause");
        b.setOnClickListener(Ui.feedbackSoft(new View.OnClickListener() {
            @Override public void onClick(View v) { control(key); }
        }));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ? 1.4f : 1f);
        if (ctl.getChildCount() > 0) lp.leftMargin = Ui.dp(a, Ui.GUTTER);
        ctl.addView(b, lp);
        return b;
    }

    /** True when console has notification access (needed to read other apps' media sessions). */
    boolean hasAccess() {
        String flat = Settings.Secure.getString(a.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(listener.flattenToString());
    }

    /** Explains notification access and opens its settings (or App info for restricted settings). */
    private void explainAccess() {
        if (hasAccess()) return;
        a.showing(ConsoleDialog.message(a, "TRACK DISPLAY",
                "Source buttons and play/pause/skip work without this. Granting “Notification access” lets TrekUI read the live "
                        + "playback session of any source, so titles, artwork and controls are always current. The same access lets the "
                        + "TrekUI shade list your notifications. Nothing is stored or sent anywhere.\n\n"
                        + "If the switch is greyed out (Android 13+ for apps installed from a file): open App info, tap ⋮ (top-right) › "
                        + "“Allow restricted settings”, then try again.",
                new String[]{"OPEN NOTIFICATION ACCESS", "OPEN APP INFO", "CANCEL"}, new ConsoleDialog.OnPick() {
                    @Override public void pick(int w) {
                        if (w == 0) {
                            Intent i;
                            if (Build.VERSION.SDK_INT >= 30) {
                                i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
                                i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString());
                                if (!a.startSafe(i, null)) a.startSafe(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), null);
                            } else {
                                a.startSafe(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), null);
                            }
                        } else if (w == 1) {
                            a.startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())), null);
                        }
                    }
                }));
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    @Override
    public void resume() {
        resumed = true;
        hint.setVisibility(hasAccess() ? View.GONE : View.VISIBLE);
        if (hasAccess() && msm != null && !listening) {
            try {
                msm.addOnActiveSessionsChangedListener(sessions, listener, a.handler());
                listening = true;
            } catch (SecurityException e) {
                listening = false;
            }
        }
        buildSources();
        attach();
    }

    @Override
    public void pause() {
        resumed = false;
        if (listening && msm != null) {
            try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { }
            listening = false;
        }
        disconnectLink();
        setController(null, false);
    }

    @Override public void tick() { if (controller == null) render(); }

    // ---- targeting the selected app ----------------------------------------------------------------

    /** Point the panel at the selected source: live session first, then a MediaBrowser link. */
    private void attach() {
        String pkg = selected();
        if (pkg == null) { render(); return; }
        if (hasAccess() && msm != null) {
            try {
                for (MediaController c : msm.getActiveSessions(listener)) {
                    if (pkg.equals(c.getPackageName())) { disconnectLink(); setController(c, false); return; }
                }
            } catch (SecurityException ignored) {
            }
        }
        if (controller != null && pkg.equals(controller.getPackageName())) { render(); return; }
        if (link == LINK_IDLE || link == LINK_FAILED) connectLink(pkg);
        else render();
    }

    /** Active sessions changed: follow the selected app's session, or drop a vanished one. */
    private void onSessions(List<MediaController> list) {
        String pkg = selected();
        if (pkg == null || list == null) return;
        for (MediaController c : list) {
            if (pkg.equals(c.getPackageName())) {
                if (controller == null || controllerFromLink || !c.getSessionToken().equals(controller.getSessionToken())) {
                    disconnectLink();
                    setController(c, false);
                }
                return;
            }
        }
        if (controller != null && !controllerFromLink) {
            setController(null, false);
            if (resumed) attach();
        }
    }

    /** Connects to the app's MediaBrowserService; its session token gives a controller. */
    private void connectLink(final String pkg) {
        disconnectLink();
        ComponentName svc = null;
        try {
            List<ResolveInfo> l = browserServices(pkg);
            if (l != null && !l.isEmpty() && l.get(0).serviceInfo != null) {
                svc = new ComponentName(l.get(0).serviceInfo.packageName, l.get(0).serviceInfo.name);
            }
        } catch (RuntimeException ignored) {
            // treated as no service below
        }
        if (svc == null) { link = LINK_FAILED; render(); return; }
        final MediaBrowser[] holder = new MediaBrowser[1];
        MediaBrowser.ConnectionCallback callback = new MediaBrowser.ConnectionCallback() {
            @Override public void onConnected() {
                if (holder[0] != browser || browser == null) return;
                try {
                    link = LINK_UP;
                    setController(new MediaController(a.getApplicationContext(), browser.getSessionToken()), true);
                } catch (Exception e) {
                    link = LINK_FAILED;
                    render();
                }
            }
            @Override public void onConnectionFailed() {
                if (holder[0] != browser) return;
                link = LINK_FAILED;
                browser = null;
                render();
            }
            @Override public void onConnectionSuspended() {
                if (holder[0] != browser) return;
                link = LINK_FAILED;
                browser = null;
                if (controllerFromLink) setController(null, false);
                render();
            }
        };
        try {
            browser = new MediaBrowser(a.getApplicationContext(), svc, callback, null);
            holder[0] = browser;
            link = LINK_CONNECTING;
            browser.connect();
        } catch (Exception e) {
            browser = null;
            link = LINK_FAILED;
        }
        render();
    }

    private void disconnectLink() {
        if (browser != null) {
            try { browser.disconnect(); } catch (Exception ignored) { }
            browser = null;
        }
        link = LINK_IDLE;
    }

    /** Swaps the controller the panel shows and drives (callbacks move with it). */
    private void setController(MediaController c, boolean fromLink) {
        if (controller != null) {
            try { controller.unregisterCallback(cb); } catch (Exception ignored) { }
        }
        controller = c;
        controllerFromLink = c != null && fromLink;
        if (controller != null) controller.registerCallback(cb, a.handler());
        render();
    }

    private boolean isPlaying() {
        if (controller != null) {
            PlaybackState s = controller.getPlaybackState();
            return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
        }
        if (selected() != null) return false;
        AudioManager am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
        return am != null && am.isMusicActive();
    }

    /** A transport key: through the controller, else a media key addressed to the selected app, else a system media key. */
    private void control(int key) {
        String pkg = selected();
        if (controller != null) {
            MediaController.TransportControls tc = controller.getTransportControls();
            if (key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) tc.skipToPrevious();
            else if (key == KeyEvent.KEYCODE_MEDIA_NEXT) tc.skipToNext();
            else if (isPlaying()) tc.pause();
            else tc.play();
        } else if (pkg != null) {
            // No session or link: address the media key to the selected app only.
            long t = SystemClock.uptimeMillis();
            int k = key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ? KeyEvent.KEYCODE_MEDIA_PLAY : key;
            for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
                Intent i = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg);
                i.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(t, t, action, k, 0));
                try { a.sendBroadcast(i); } catch (Exception ignored) { }
            }
            a.handler().postDelayed(new Runnable() { @Override public void run() { if (resumed) attach(); } }, REATTACH_MS);
        } else {
            AudioManager am = (AudioManager) a.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0));
        }
        a.handler().postDelayed(new Runnable() { @Override public void run() { if (resumed) render(); } }, RERENDER_MS);
    }

    /** Opens the player (its session activity if it has one). */
    private void openApp(View v) {
        String pkg = selected();
        if (controller != null && controller.getSessionActivity() != null) {
            if (a.sendPending(controller.getSessionActivity())) return;
        }
        AppInfo ai = pkg != null ? AppRepo.get(a).findPackage(pkg) : null;
        if (ai != null) a.launchApp(ai, v);
    }

    /** Updates title, artist, artwork, play/pause glyph and status from the current target. */
    private void render() {
        if (track == null) return;
        String pkg = selected();
        String name = pkg != null ? shortName(a, pkg) : "";
        boolean playing = isPlaying();
        ((MediaGlyph) playBtn.getDrawable()).setType(playing ? MediaGlyph.PAUSE : MediaGlyph.PLAY);
        playBtn.invalidate();
        Bitmap bmp = null;
        String t = null, ar = null;
        if (controller != null) {
            MediaMetadata m = controller.getMetadata();
            if (m != null) {
                t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (t == null) t = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                ar = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (ar == null) ar = m.getString(MediaMetadata.METADATA_KEY_AUTHOR);
                if (ar == null) ar = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
                bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
        }
        String state;
        if (pkg == null) {
            if (t == null) t = playing ? "AUDIO STREAM ACTIVE" : "NO AUDIO SOURCE";
            state = playing ? "PLAYING" : "STANDBY";
            if (ar == null) ar = "TAP + TO ADD A SOURCE";
        } else if (controller != null) {
            state = playing ? "PLAYING" : "PAUSED";
            if (t == null) t = name + (playing ? " • PLAYING" : " • READY");
            ar = (ar != null ? ar + "  •  " : "") + name;
        } else if (link == LINK_CONNECTING) {
            state = "LINKING";
            t = name;
            ar = "ESTABLISHING LINK…";
        } else {
            state = "STANDBY";
            t = name;
            ar = link == LINK_FAILED ? "NO LIVE LINK • ▶ SENDS PLAY TO " + name : "STANDBY";
        }
        track.setText(t);
        artist.setText(ar.toUpperCase(Locale.getDefault()));
        if (bmp != null) {
            art.setImageBitmap(bmp);
            art.clearColorFilter();
            art.setPadding(0, 0, 0, 0);
        } else {
            AppInfo ai = pkg != null ? AppRepo.get(a).findPackage(pkg) : null;
            int p = Ui.dp(a, 12);
            art.setPadding(p, p, p, p);
            if (ai != null && ai.rawIcon != null) {
                art.setImageDrawable(IconFactory.copy(ai.rawIcon));
                art.clearColorFilter();
            } else {
                art.setImageBitmap(IconFactory.glyph(a, "music"));
                art.setColorFilter(accent);
            }
        }
        card.status.setText(name.isEmpty() ? state : name + " • " + state);
    }

    /** Black transport glyphs drawn to the button size. */
    static final class MediaGlyph extends Drawable {
        static final int PREV = 0, PLAY = 1, PAUSE = 2, NEXT = 3;
        private int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        MediaGlyph(int type) {
            this.type = type;
            p.setColor(Ui.ink());
        }

        void setType(int t) { if (t != type) { type = t; invalidateSelf(); } }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height());
            float x = b.centerX() - s / 2f, y = b.centerY() - s / 2f;
            path.reset();
            switch (type) {
                case PLAY:
                    path.moveTo(x + s * 0.22f, y + s * 0.1f);
                    path.lineTo(x + s * 0.86f, y + s * 0.5f);
                    path.lineTo(x + s * 0.22f, y + s * 0.9f);
                    path.close();
                    c.drawPath(path, p);
                    break;
                case PAUSE:
                    c.drawRoundRect(x + s * 0.2f, y + s * 0.12f, x + s * 0.42f, y + s * 0.88f, s * 0.05f, s * 0.05f, p);
                    c.drawRoundRect(x + s * 0.58f, y + s * 0.12f, x + s * 0.8f, y + s * 0.88f, s * 0.05f, s * 0.05f, p);
                    break;
                default:
                    boolean next = type == NEXT;
                    float dir = next ? 1 : -1, cx = x + s / 2f;
                    for (int k = 0; k < 2; k++) {
                        float off = (k == 0 ? -0.32f : 0.02f) * s * dir;
                        path.moveTo(cx + off, y + s * 0.18f);
                        path.lineTo(cx + off + dir * s * 0.32f, y + s * 0.5f);
                        path.lineTo(cx + off, y + s * 0.82f);
                        path.close();
                    }
                    c.drawPath(path, p);
                    float bx = next ? cx + s * 0.36f : cx - s * 0.36f - s * 0.08f;
                    c.drawRect(bx, y + s * 0.18f, bx + s * 0.08f, y + s * 0.82f, p);
            }
        }

        @Override public int getIntrinsicWidth() { return 64; }
        @Override public int getIntrinsicHeight() { return 64; }
        @Override public void setAlpha(int alpha) { p.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
