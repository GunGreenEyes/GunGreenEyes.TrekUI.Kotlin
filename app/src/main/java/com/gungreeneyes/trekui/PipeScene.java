package com.gungreeneyes.trekui;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The three painted-scene homes (Ember & Briar, the Tobacconist's Ledger, Midnight Ember). Each is one
 * complete painting with native controls laid over it:
 *  - every plaque, tab, key and card is a launcher slot: tap runs its default, long-press assigns an
 *    app, a launcher function or a sound, exactly like an console elbow or block;
 *  - clock, date, weather, battery, the session clock, the current pipe and blend, the latest journal
 *    note and the ritual checklist are drawn live over the painting's sample values;
 *  - the dock keys show and launch your real dock apps.
 * Coordinates are in the source paintings' 941x1672 space; the painting is cropped to its screen area.
 *
 * <h2>Any screen shape</h2>
 * The painting is placed by the pure {@link #fitScene}: whole painting visible ("contain") on every
 * aspect ratio, zoomed toward "cover" only while every tap target stays on screen and at most
 * {@link #MAX_CROP} of the art is cut. Children are laid out through the same {@link Fit} as the bitmap
 * ({@link #toView}), so tap targets match the art on 16:9 to 21:9 phones, tablets, landscape and
 * near-square foldable screens. Any letterbox shows the scene's material.
 */
final class PipeScene {
    private PipeScene() {}

    /** Per scene: the region of the 941x1672 painting kept on screen (left, top, right, bottom); assets are pre-cropped to it. */
    static final int[][] CROP = {{31, 74, 906, 1614}, {24, 74, 904, 1614}, {60, 74, 884, 1614}};
    /** Per scene: asset name under {@link #SCENE_ASSET_DIR}. */
    static final String[] FILE = {"ember", "ledger", "midnight"};
    static final String SCENE_ASSET_DIR = "pipe/scenes/";
    /** Per scene: tiled material in the letterbox (Briar walnut, Ledger leather, Midnight plain lacquer). */
    static final String[] LETTERBOX_TEXTURE = {"walnut.jpg", "leather.jpg", null};
    /** Midnight Ember's black lacquer. */
    static final int LACQUER = 0xFF0D0807;
    /** Keyline between the painting and the letterbox. */
    static final int EDGE_COLOR = 0xCC000000;
    /** Painting and letterbox alpha while CLOAK (night mode) is engaged. */
    static final int CLOAK_ALPHA = 110;
    /** Most of the painting's width or height that zoom-to-fill may crop away. */
    static final float MAX_CROP = 0.08f;
    /** Margin (painting px) kept visible around the outermost tap targets. */
    static final float KEEP_PAD = 8f;
    /**
     * Per scene: keep the painting's foot visible too. Midnight's dock keys sit in a painted tray that
     * runs to the bottom edge; keeping only the keys cut the tray's lower frame off (landscape 720p).
     */
    static final boolean[] KEEP_FOOT = {false, false, true};
    private static WeakReference<Stage> current;

    /** The scene index (0 Briar, 1 Ledger, 2 Midnight) of the current faction. */
    static int idx() {
        return Skin.faction - Skin.BRIAR;
    }

    // ---- session clock (shared by the scenes and the ritual page) -------------------------------

    /** Elapsed session-clock time in ms: the banked total plus the running stretch, if running. */
    static long timerMillis(Prefs p) {
        long acc = p.l("pipeTimerAcc", 0L);
        if (p.b("pipeTimerRun", false)) acc += Math.max(0L, System.currentTimeMillis() - p.l("pipeTimerStart", System.currentTimeMillis()));
        return acc;
    }

    /** True while the session clock runs. */
    static boolean timerRunning(Prefs p) {
        return p.b("pipeTimerRun", false);
    }

    /** Starts or pauses the session clock, banking the elapsed time, and refreshes the scene. */
    static void timerToggle(Prefs p) {
        long now = timerMillis(p);
        boolean run = !timerRunning(p);
        p.put("pipeTimerAcc", now);
        p.put("pipeTimerStart", System.currentTimeMillis());
        p.put("pipeTimerRun", run);
        refresh();
    }

    /** Stops the session clock and sets it to zero. */
    static void timerReset(Prefs p) {
        p.put("pipeTimerAcc", 0L);
        p.put("pipeTimerRun", false);
        refresh();
    }

    /** The session clock as HH:MM:SS. */
    static String timerText(Prefs p) {
        long n = timerMillis(p) / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", n / 3600, (n / 60) % 60, n % 60);
    }

    // ---- live text ------------------------------------------------------------------------------

    /** The last weather the (hidden) weather panel cached, or null. */
    static Weather.Data weather(Prefs p) {
        String s = p.s("wxCache", null);
        if (s == null) return null;
        try {
            return Weather.parse(s, p.b("wxCacheF", true));
        } catch (Exception e) {
            return null;
        }
    }

    /** Name of the selected pipe/blend, or {@code empty} when none is chosen. */
    static String selName(PipeDataStore st, String type, String empty) {
        JSONObject o = st.get(type, st.selected(type));
        return o == null ? empty : o.optString("name", empty);
    }

    /** Description of the selected pipe/blend, or "". */
    static String selDesc(PipeDataStore st, String type) {
        JSONObject o = st.get(type, st.selected(type));
        return o == null ? "" : o.optString("description", "");
    }

    /** The newest journal entry's notes (or its name), or null when the journal is empty. */
    static String latestNote(PipeDataStore st) {
        JSONArray a = st.records("journal");
        for (int i = a.length() - 1; i >= 0; i--) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            String n = o.optString("notes", "");
            if (n.isEmpty()) n = o.optString("name", "");
            if (!n.isEmpty()) return n;
        }
        return null;
    }

    /** Refreshes the live overlays of the scene on screen, if any. */
    static void refresh() {
        Stage s = current != null ? current.get() : null;
        if (s != null) s.update();
    }

    // ---- build ----------------------------------------------------------------------------------

    /**
     * Builds the scene home ({@link SceneHome}): a panel (clock, header, info line) and the
     * {@link Stage}, plus a hidden weather panel that keeps the weather cache fresh. Portrait windows
     * stack a slim strip above the painting; landscape windows put the painting at full height beside a
     * control pane (the same clock and header, the session clock and its buttons, the dock apps and the
     * app drawer), so landscape phones don't shrink the painting under a strip. Also points
     * MainActivity's shared clock/info fields at the panel's views.
     */
    static LinearLayout build(final MainActivity a) {
        final int k = idx();
        final Palette pal = a.pal;

        // these exist so the shared clock/info code has somewhere to write; the scene draws its own
        a.headerFrame = new ConsoleFrame(a);
        a.mainFrame = new ConsoleFrame(a);
        a.tilesBox = null;
        a.homeScroll = null;
        a.dockIcons = null;
        a.dateView = Ui.text(a, "", 12.0f, pal.textLo);
        a.ampmView = Ui.bold(a, "", 14.0f, pal.c(1));
        a.clockView = Ui.bold(a, "", 26.0f, a.slotColor("clock", pal.textHi));
        a.clockRowView = null;

        // ---- the panel: time (Midnight has no clock of its own), header, status
        LinearLayout panel = new LinearLayout(a);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout clk = new LinearLayout(a);
        clk.setGravity(Gravity.BOTTOM);
        clk.addView(a.clockView);
        a.ampmView.setPadding(Ui.dp(a, 4.0f), 0, 0, Ui.dp(a, 3.0f));
        clk.addView(a.ampmView);
        a.bindSlot(clk, "clock", showAlarms(a));
        panel.addView(clk, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout txt = new LinearLayout(a);
        txt.setOrientation(LinearLayout.VERTICAL);
        a.shipView = Ui.oneLine(Ui.bold(a, a.prefs.headerText().toUpperCase(Locale.getDefault()), 12.0f, a.slotColor("ship", pal.c(0))));
        a.bindSlot(a.shipView, "ship", null);
        txt.addView(a.shipView, new LinearLayout.LayoutParams(-1, -2));
        a.infoView = Ui.oneLine(Ui.text(a, "", 12.0f, pal.textLo));
        txt.addView(a.infoView, new LinearLayout.LayoutParams(-1, -2));
        panel.addView(txt, new LinearLayout.LayoutParams(0, -2, 1.0f));

        // ---- the painting
        final Stage stage = new Stage(a, k);
        current = new WeakReference<Stage>(stage);
        if (k == 0) buildBriar(a, stage);
        else if (k == 1) buildLedger(a, stage);
        else buildMidnight(a, stage);

        // ---- landscape-only controls under the header
        View extras = landscapeControls(a, stage, k);
        panel.addView(extras, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        SceneHome home = new SceneHome(a, panel, clk, txt, extras, stage);

        // keep weather fresh: the weather panel runs unseen, the scene shows its numbers
        if (!a.prefs.hiddenWidgets().contains("weather")) {
            Panel wp = a.createPanel("weather", pal.c(0));
            if (wp != null) {
                a.panels.add(wp);
                FrameLayout hidden = new FrameLayout(a);
                hidden.setVisibility(View.GONE);
                hidden.addView(wp.create());
                home.addView(hidden, new LinearLayout.LayoutParams(0, 0));
            }
        }
        return home;
    }

    /** Opens the clock app's alarms. */
    private static Runnable showAlarms(final MainActivity a) {
        return new Runnable() {
            @Override public void run() {
                a.startSafe(new Intent(AlarmClock.ACTION_SHOW_ALARMS), a.clockView);
            }
        };
    }

    // ---- side-by-side layout (landscape) ----------------------------------------------------------

    /** Share of the window width the landscape control pane takes, before limits. */
    static final float PANE_FRACTION = 0.34f;
    /** Landscape control pane width limits, dp. */
    static final float PANE_MIN_DP = 184f, PANE_MAX_DP = 340f;
    /** Height of the portrait strip above the painting, design dp. */
    static final float STRIP_DP = 52f;

    /** True when the scene should sit beside its control pane rather than under a strip. */
    static boolean sideBySide(int width, int height) {
        return width > height;
    }

    /**
     * Width (dp) of the landscape control pane for a window {@code widthDp} wide: about a third,
     * between {@link #PANE_MIN_DP} and {@link #PANE_MAX_DP}, and never more than half the window.
     */
    static float paneWidthDp(float widthDp) {
        float w = Math.max(PANE_MIN_DP, Math.min(PANE_MAX_DP, widthDp * PANE_FRACTION));
        return Math.min(w, widthDp * 0.5f);
    }

    /**
     * The scene home. Chooses its arrangement from its own size on every measure, so rotation,
     * folding and multi-window resizes need no rebuild: portrait stacks the strip (clock row) over the
     * painting; landscape puts the control pane (clock, header, extras) beside a full-height painting.
     */
    static final class SceneHome extends LinearLayout {
        private final LinearLayout panel, clock, text;
        private final View extras;
        private final Stage stage;
        private final GradientDrawable stripBg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF0100806, 0xC0100806, 0x00100806});
        private final GradientDrawable paneBg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF0100806, 0xE0100806, 0xA0100806});
        private int mode = -1; // -1 undecided, 0 stacked, 1 side by side

        SceneHome(Context c, LinearLayout panel, LinearLayout clock, LinearLayout text, View extras, Stage stage) {
            super(c);
            this.panel = panel;
            this.clock = clock;
            this.text = text;
            this.extras = extras;
            this.stage = stage;
            setClipToPadding(false);
            addView(panel, new LayoutParams(-1, Ui.dp(c, STRIP_DP)));
            addView(stage, new LayoutParams(-1, 0, 1f));
            arrange(false, 0);
        }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws) - getPaddingLeft() - getPaddingRight();
            int h = MeasureSpec.getSize(hs) - getPaddingTop() - getPaddingBottom();
            if (w > 0 && h > 0) arrange(sideBySide(w, h), w);
            super.onMeasure(ws, hs);
        }

        /** Switches between the stacked and side-by-side arrangement (layout params are edited in place). */
        private void arrange(boolean side, int widthPx) {
            Context c = getContext();
            LayoutParams pl = (LayoutParams) panel.getLayoutParams();
            LayoutParams sl = (LayoutParams) stage.getLayoutParams();
            if (side) {
                float density = getResources().getDisplayMetrics().density;
                pl.width = Math.round(paneWidthDp(widthPx / density) * density);
            }
            int next = side ? 1 : 0;
            if (next == mode) return;
            mode = next;
            LayoutParams tl = (LayoutParams) text.getLayoutParams();
            int pad = Ui.dp(c, 14.0f);
            if (side) {
                setOrientation(HORIZONTAL);
                panel.setOrientation(VERTICAL);
                panel.setGravity(Gravity.TOP | Gravity.START);
                panel.setPadding(pad, pad, pad, pad);
                panel.setBackground(paneBg);
                pl.height = LayoutParams.MATCH_PARENT;
                sl.width = 0;
                sl.height = LayoutParams.MATCH_PARENT;
                tl.width = LayoutParams.MATCH_PARENT;
                tl.weight = 0;
                tl.leftMargin = 0;
                tl.topMargin = Ui.dp(c, 6.0f);
                text.setGravity(Gravity.START);
                setTextGravity(Gravity.START);
                extras.setVisibility(VISIBLE);
            } else {
                setOrientation(VERTICAL);
                panel.setOrientation(HORIZONTAL);
                panel.setGravity(Gravity.CENTER_VERTICAL);
                panel.setPadding(pad, 0, pad, 0);
                panel.setBackground(stripBg);
                pl.width = LayoutParams.MATCH_PARENT;
                pl.height = Ui.dp(c, STRIP_DP);
                sl.width = LayoutParams.MATCH_PARENT;
                sl.height = 0;
                tl.width = 0;
                tl.weight = 1f;
                tl.leftMargin = Ui.dp(c, 12.0f);
                tl.topMargin = 0;
                text.setGravity(Gravity.END);
                setTextGravity(Gravity.END);
                extras.setVisibility(GONE);
            }
            sl.weight = 1f;
            clock.requestLayout();
        }

        private void setTextGravity(int gravity) {
            for (int i = 0; i < text.getChildCount(); i++) {
                View v = text.getChildAt(i);
                if (v instanceof TextView) ((TextView) v).setGravity(gravity);
            }
        }
    }

    /**
     * Landscape pane controls: the session clock with start/pause and reset, the dock apps on keys
     * matching the painting, and the app drawer. Scrolls when the window is short.
     */
    private static View landscapeControls(final MainActivity a, Stage s, int scene) {
        Palette pal = a.pal;
        int style = scene == 2 ? Live.DECO : Live.LEATHER;
        ScrollView scroll = new ScrollView(a);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVisibility(View.GONE);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, Ui.dp(a, 10.0f), 0, 0);
        scroll.addView(col);

        Live timer = new Live(a, Live.TIMER, style);
        timer.setContentDescription("session clock");
        timer.setOnClickListener(Ui.feedback(new View.OnClickListener() {
            @Override public void onClick(View v) { PipePages.open(a, "ritual"); }
        }));
        s.track(timer);
        col.addView(timer, new LinearLayout.LayoutParams(-1, Ui.dp(a, 44.0f)));

        LinearLayout row = new LinearLayout(a);
        TextView startPause = Ui.button(a, "START / PAUSE", pal.c(0), Ui.PILL, new View.OnClickListener() {
            @Override public void onClick(View v) { timerToggle(a.prefs); }
        });
        TextView reset = Ui.button(a, "RESET", pal.c(3), Ui.PILL, new View.OnClickListener() {
            @Override public void onClick(View v) { timerReset(a.prefs); }
        });
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(0, -2, 1.6f);
        bl.rightMargin = Ui.dp(a, Ui.GUTTER);
        row.addView(startPause, bl);
        row.addView(reset, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = Ui.dp(a, Ui.GUTTER);
        col.addView(row, rl);

        // the dock: same keys as on the painting, larger and always reachable
        LinearLayout dockRow = new LinearLayout(a);
        List<Live> keys = new ArrayList<Live>();
        int count = s.dockCount > 0 ? s.dockCount : DOCK_KEYS;
        for (int i = 0; i < count; i++) {
            Live key = dockKey(a, i, style);
            keys.add(key);
            dockRow.addView(key, new LinearLayout.LayoutParams(0, Ui.dp(a, 52.0f), 1f));
        }
        s.paneDockKeys = keys;
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, -2);
        dl.topMargin = Ui.dp(a, Ui.M);
        col.addView(dockRow, dl);

        TextView apps = Ui.button(a, Skin.drawerTitle(), pal.c(2), Ui.PILL, new View.OnClickListener() {
            @Override public void onClick(View v) { a.openDrawer(null, false); }
        });
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, -2);
        al.topMargin = Ui.dp(a, Ui.M);
        col.addView(apps, al);
        return scroll;
    }

    // ---- Ember & Briar: the fireside study ------------------------------------------------------

    private static void buildBriar(final MainActivity a, Stage s) {
        String[][] circles = {{"tobacco", "470", "322"}, {"journal", "296", "358"}, {"pipes", "643", "360"}, {"library", "202", "510"},
                {"tools", "740", "510"}, {"blends", "222", "690"}, {"care", "722", "690"}};
        for (String[] c : circles) {
            int x = Integer.parseInt(c[1]), y = Integer.parseInt(c[2]);
            spot(a, s, c[0].toUpperCase(Locale.US) + " PLAQUE", c[0], new RectF(x - 66, y - 66, x + 66, y + 66), true);
        }
        title(a, s, new RectF(240, 80, 710, 215));
        Live face = new Live(a, Live.FACE, 0);
        s.put(face, new RectF(470 - 161, 568 - 161, 470 + 161, 568 + 161));
        a.bindSlot(face, "clock", new Runnable() {
            @Override public void run() {
                a.startSafe(new Intent(AlarmClock.ACTION_SHOW_ALARMS), a.clockView);
            }
        });
        weatherBox(a, s, new RectF(88, 792, 290, 910), Live.LEATHER);
        Live timer = new Live(a, Live.TIMER, Live.LEATHER);
        s.put(timer, new RectF(668, 816, 866, 864));
        a.bindSlot(timer, "session clock", page(a, "ritual"));
        btn(a, s, "SESSION START/PAUSE", 718, 884, 22, new Runnable() { @Override public void run() { timerToggle(a.prefs); } }, Live.PLAYPAUSE_LEATHER);
        spot(a, s, "SESSION RESET", null, circle(795, 884, 22), false, new Runnable() { @Override public void run() { timerReset(a.prefs); } });
        spot(a, s, "HEARTH", "ritual", new RectF(430, 760, 510, 830), true);
        // the current blend and pipe cards
        spot(a, s, "CURRENT BLEND CARD", "blends", new RectF(85, 1218, 457, 1362), true);
        s.put(new Live(a, Live.BLEND, Live.PARCHMENT), new RectF(226, 1262, 448, 1342));
        spot(a, s, "PIPE OF THE DAY CARD", "pipes", new RectF(485, 1218, 857, 1362), true);
        s.put(new Live(a, Live.PIPE, Live.PARCHMENT), new RectF(703, 1262, 848, 1346));
        dock(a, s, 1495, new int[]{137, 272, 405, 540, 672}, 96, Live.LEATHER);
        Runnable drawer = new Runnable() { @Override public void run() { a.openDrawer(null, false); } };
        spot(a, s, "APPS KEY", null, sq(805, 1495, 96), true, drawer);
        spot(a, s, "DRAWER HANDLE", null, new RectF(380, 1550, 560, 1610), true, drawer);
    }

    // ---- The Tobacconist's Ledger: the merchant's folio ----------------------------------------

    private static void buildLedger(final MainActivity a, Stage s) {
        String[] tabs = {"home", "journal", "blends", "pipes", "library", "tools", "care", "library", "settings"};
        String[] names = {"HOME TAB", "JOURNAL TAB", "BLENDS TAB", "PIPES TAB", "TOBACCO TAB", "TOOLS TAB", "CARE TAB", "LIBRARY TAB", "SETTINGS TAB"};
        int[] ys = {350, 425, 492, 560, 628, 696, 764, 836, 905};
        for (int i = 0; i < tabs.length; i++) {
            Runnable r;
            if (tabs[i].equals("home")) r = new Runnable() { @Override public void run() { a.openDrawer(null, false); } };
            else if (tabs[i].equals("settings")) r = new Runnable() { @Override public void run() { a.openConfig(); } };
            else r = page(a, tabs[i]);
            spot(a, s, names[i], null, new RectF(75, ys[i] - 30, 222, ys[i] + 30), true, r);
        }
        title(a, s, new RectF(400, 85, 720, 250));
        spot(a, s, "WAX SEAL", null, new RectF(752, 95, 880, 235), true, new Runnable() { @Override public void run() { a.openConfig(); } });
        Live date = new Live(a, Live.DATE_LONG, Live.PARCHMENT);
        s.put(date, new RectF(262, 310, 514, 345));
        a.bindSlot(date, "date", calendar(a, date));
        Live time = new Live(a, Live.TIME, Live.PARCHMENT);
        s.put(time, new RectF(262, 350, 514, 422));
        a.bindSlot(time, "clock", new Runnable() {
            @Override public void run() {
                a.startSafe(new Intent(AlarmClock.ACTION_SHOW_ALARMS), a.clockView);
            }
        });
        weatherBox(a, s, new RectF(522, 320, 644, 430), Live.PARCHMENT);
        Live note = new Live(a, Live.NOTE, Live.PAPER_NOTE);
        s.put(note, new RectF(688, 330, 886, 434));
        a.bindSlot(note, "merchant's note", page(a, "journal"));
        // today's ritual: live checkboxes, tap a line to tick it
        spot(a, s, "RITUAL CARD TITLE", "ritual", new RectF(238, 458, 545, 505), true);
        int[] cy = {528, 563, 598, 633, 668};
        for (int i = 0; i < cy.length; i++) {
            final int bit = i;
            Live box = new Live(a, Live.CHECK, Live.PARCHMENT);
            box.bit = bit;
            s.put(box, new RectF(342, cy[i] - 14, 370, cy[i] + 14));
            spot(a, s, "RITUAL STEP " + (i + 1), null, new RectF(240, cy[i] - 17, 545, cy[i] + 17), false, new Runnable() {
                @Override public void run() {
                    PipeDataStore st = new PipeDataStore(a);
                    st.ritualMask(st.ritualMask() ^ (1 << bit));
                    refresh();
                }
            });
        }
        spot(a, s, "CURRENT BLEND PANEL", "blends", new RectF(558, 458, 890, 705), true);
        s.put(new Live(a, Live.BLEND_NAME, Live.PARCHMENT), new RectF(576, 490, 800, 530));
        s.put(new Live(a, Live.BLEND_DESC, Live.PARCHMENT), new RectF(576, 530, 774, 554));
        spot(a, s, "TOBACCO SPECIMEN", "library", new RectF(238, 718, 560, 960), true);
        spot(a, s, "PIPE CARD", "pipes", new RectF(578, 718, 890, 960), true);
        s.put(new Live(a, Live.PIPE, Live.PARCHMENT), new RectF(733, 820, 886, 872));
        s.put(new Live(a, Live.PIPE_DESC, Live.PARCHMENT), new RectF(600, 884, 886, 942));
        spot(a, s, "CELLAR INVENTORY", "blends", new RectF(85, 990, 495, 1210), true);
        spot(a, s, "CATALOG AND ARCHIVE", null, new RectF(510, 980, 890, 1192), true, new Runnable() { @Override public void run() { a.openDrawer(null, false); } });
        spot(a, s, "JOURNAL EXCERPT", "journal", new RectF(100, 1220, 495, 1395), true);
        s.put(new Live(a, Live.JOURNAL, Live.PARCHMENT), new RectF(266, 1256, 490, 1384));
        spot(a, s, "FILING AND RECORDS", "backup", new RectF(515, 1205, 895, 1400), true);
        Runnable search = new Runnable() { @Override public void run() { a.openDrawer(null, true); } };
        spot(a, s, "TAB INDEX", null, new RectF(80, 1402, 252, 1460), true, new Runnable() { @Override public void run() { a.openDrawer(null, false); } });
        spot(a, s, "A TO Z TABS", null, new RectF(258, 1405, 785, 1458), true, search);
        spot(a, s, "SEARCH TAB", null, new RectF(800, 1405, 878, 1458), true, search);
        dock(a, s, 1515, new int[]{240, 355, 470, 585, 700}, 92, Live.LEATHER);
        spot(a, s, "DRAWER HANDLE", null, new RectF(380, 1550, 560, 1610), true, new Runnable() { @Override public void run() { a.openDrawer(null, false); } });
    }

    // ---- Midnight Ember: the Art Deco night lounge ----------------------------------------------

    private static void buildMidnight(final MainActivity a, Stage s) {
        title(a, s, new RectF(330, 110, 612, 300));
        weatherBox(a, s, new RectF(122, 166, 245, 314), Live.DECO);
        Live date = new Live(a, Live.DATE_STACK, Live.DECO);
        s.put(date, new RectF(700, 160, 828, 305));
        a.bindSlot(date, "date", calendar(a, date));
        spot(a, s, "CONCIERGE", null, new RectF(360, 365, 585, 430), true, new Runnable() {
            @Override public void run() {
                if (a.shade != null) a.shade.open(Ui.animate(a));
            }
        });
        String[] menu = {"LOUNGE", "PIPES", "TOBACCO", "BLENDS", "TOOLS", "JOURNAL", "LIBRARY"};
        String[] dest = {null, "pipes", "library", "blends", "tools", "journal", "library"};
        int[] ys = {476, 545, 610, 676, 741, 806, 871};
        for (int i = 0; i < menu.length; i++) {
            Runnable r = dest[i] == null ? new Runnable() { @Override public void run() { a.openDrawer(null, false); } } : page(a, dest[i]);
            spot(a, s, menu[i] + " KEY", null, new RectF(350, ys[i] - 29, 590, ys[i] + 29), true, r);
        }
        Live timer = new Live(a, Live.TIMER, Live.DECO);
        s.put(timer, new RectF(393, 993, 552, 1039));
        a.bindSlot(timer, "session clock", page(a, "ritual"));
        spot(a, s, "SESSION RESET", null, circle(381, 1066, 24), false, new Runnable() { @Override public void run() { timerReset(a.prefs); } });
        btn(a, s, "SESSION START/PAUSE", 470, 1064, 25, new Runnable() { @Override public void run() { timerToggle(a.prefs); } }, Live.PLAYPAUSE_DECO);
        spot(a, s, "SESSION STOP", null, circle(559, 1066, 24), false, new Runnable() {
            @Override public void run() {
                if (timerRunning(a.prefs)) timerToggle(a.prefs);
            }
        });
        String[] quick = {"LIGHT", "PACK", "CLEAN", "BLEND", "NOTE", "TIMER"};
        int[] qx = {200, 312, 418, 528, 642, 748};
        for (int i = 0; i < quick.length; i++) {
            Runnable r;
            if (i == 0) r = new Runnable() { @Override public void run() { if (!timerRunning(a.prefs)) timerToggle(a.prefs); PipePages.open(a, "ritual"); } };
            else if (i == 1) r = page(a, "ritual");
            else if (i == 2) r = page(a, "care");
            else if (i == 3) r = page(a, "blends");
            else if (i == 4) r = new Runnable() { @Override public void run() { PipePages.edit(a, "journal", null); } };
            else r = page(a, "ritual");
            spot(a, s, quick[i] + " QUICK ACTION", null, new RectF(qx[i] - 50, 1127, qx[i] + 50, 1213), true, r);
        }
        dock(a, s, 1540, new int[]{205, 338, 470, 603, 735}, 95, Live.DECO);
    }

    // ---- building blocks ------------------------------------------------------------------------

    private static RectF circle(float x, float y, float r) {
        return new RectF(x - r, y - r, x + r, y + r);
    }

    private static RectF sq(float x, float y, float s) {
        return new RectF(x - s / 2f, y - s / 2f, x + s / 2f, y + s / 2f);
    }

    private static Runnable page(final MainActivity a, final String id) {
        return new Runnable() {
            @Override public void run() {
                PipePages.open(a, id);
            }
        };
    }

    private static Runnable calendar(final MainActivity a, final View v) {
        return new Runnable() {
            @Override public void run() {
                a.startSafe(new Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(String.valueOf(System.currentTimeMillis())).build()), v);
            }
        };
    }

    private static void title(final MainActivity a, Stage s, RectF r) {
        spot(a, s, "TITLE", null, r, true, new Runnable() {
            @Override public void run() {
                PipeHome.lore(a);
            }
        });
    }

    private static void weatherBox(final MainActivity a, Stage s, RectF r, int style) {
        Live w = new Live(a, Live.WEATHER, style);
        s.put(w, r);
        a.bindSlot(w, "weather", new Runnable() {
            @Override public void run() {
                Intent i = a.getPackageManager().getLaunchIntentForPackage("com.sec.android.daemonapp");
                if (i == null) i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=weather"));
                a.startSafe(i, null);
            }
        });
    }

    /** A transparent launcher slot over {@code r} that opens page {@code pageId} (or nothing) by default. */
    private static void spot(MainActivity a, Stage s, String slot, String pageId, RectF r, boolean rounded) {
        spot(a, s, slot, pageId, r, rounded, pageId != null ? page(a, pageId) : null);
    }

    /** A transparent launcher slot over {@code r} with a ripple and {@code action} as its default. */
    private static void spot(MainActivity a, Stage s, String slot, String pageId, RectF r, boolean rounded, Runnable action) {
        View v = new View(a);
        v.setContentDescription(slot.toLowerCase(Locale.US));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(Ui.dp(a, rounded ? 12.0f : 999.0f));
        v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x66F4CB77), null, mask));
        a.bindSlot(v, slot.toLowerCase(Locale.US), action);
        s.put(v, r);
    }

    /** A live play/pause button of radius {@code r} centred on (x, y). */
    private static void btn(MainActivity a, Stage s, String slot, float x, float y, float r, Runnable action, int style) {
        Live v = new Live(a, Live.PLAYPAUSE, style);
        a.bindSlot(v, slot.toLowerCase(Locale.US), action);
        s.put(v, circle(x, y, r));
    }

    /** Number of dock keys painted into every scene. */
    static final int DOCK_KEYS = 5;

    /** Dock keys: your dock apps, drawn on keys that match the painting. */
    private static void dock(final MainActivity a, Stage s, float y, int[] xs, float size, int style) {
        List<Live> keys = new ArrayList<Live>();
        for (int i = 0; i < xs.length; i++) {
            Live key = dockKey(a, i, style);
            s.put(key, sq(xs[i], y, size * 0.94f));
            keys.add(key);
        }
        s.dockKeys = keys;
        s.dockCount = xs.length;
    }

    /** A dock key for dock position {@code n}: tap launches (or assigns, when empty), long-press assigns. */
    private static Live dockKey(final MainActivity a, final int n, int style) {
        Live key = new Live(a, Live.DOCK, style);
        key.bit = n;
        key.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (a.dock == null || n >= a.dock.size()) return;
                Tile t = a.dock.get(n);
                if (t.isEmpty()) {
                    Ui.tap(v);
                    a.editTile(a.dock, "dockJson", n, false);
                } else {
                    a.launchTile(t, v);
                }
            }
        });
        key.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (a.dock == null || n >= a.dock.size()) return true;
                Ui.longPress(v);
                a.editTile(a.dock, "dockJson", n, false);
                return true;
            }
        });
        return key;
    }

    /** Called by MainActivity.refreshDock(): put the current dock apps on the painted keys (and the landscape pane's). */
    static void refreshDock(MainActivity a) {
        Stage s = current != null ? current.get() : null;
        if (s == null || s.dockKeys == null || a.dock == null) return;
        while (a.dock.size() < s.dockCount) a.dock.add(new Tile());
        List<Live> all = new ArrayList<Live>(s.dockKeys);
        if (s.paneDockKeys != null) all.addAll(s.paneDockKeys);
        for (Live key : all) {
            if (key.bit >= a.dock.size()) continue;
            Tile t = a.dock.get(key.bit);
            Drawable icon = null;
            Bitmap glyph = null;
            String label = "Empty dock key";
            if (t.comp != null) {
                AppInfo find = a.repo.find(t.comp, t.user);
                if (find != null) {
                    label = find.label;
                    if (find.glyph != null && a.prefs.iconStyle() != Prefs.ICON_NATIVE) glyph = IconFactory.glyph(a, find.glyph);
                    else if (find.rawIcon != null) icon = IconFactory.copy(find.rawIcon);
                }
            } else if (t.action != null) {
                glyph = IconFactory.glyph(a, HomeLogic.actionGlyph(t.action));
                label = Tile.actionLabel(t.action);
            }
            key.icon = icon;
            key.glyph = glyph;
            key.setContentDescription(label);
            NotificationBadges.attach(key, t, NotificationBadges.TOP_END, 0); // LCA-T23
            key.invalidate();
        }
    }

    // ---- scene fitting (pure, unit-tested) ------------------------------------------------------

    /**
     * Where the painting sits in a view: {@link #scale} view pixels per painting pixel, and the view
     * position ({@link #left}, {@link #top}) of the crop rectangle's top-left corner. Every child of
     * the stage and the bitmap itself go through the same transform, so tap targets always line up
     * with the art at any aspect ratio.
     */
    static final class Fit {
        final float scale, left, top;

        Fit(float scale, float left, float top) {
            this.scale = scale;
            this.left = left;
            this.top = top;
        }

        @Override public String toString() {
            return String.format(Locale.US, "Fit[scale=%.4f left=%.1f top=%.1f]", scale, left, top);
        }
    }

    /**
     * Fits a {@code cropW} x {@code cropH} painting into a {@code viewW} x {@code viewH} view.
     *
     * <p>Starts from "contain" (the whole painting visible, centred, letterboxed) and zooms toward
     * "cover" to use the letterbox, but only as far as (a) every pixel of {@code keep} (the union of
     * the tap targets, in crop-relative painting pixels) stays on screen and (b) no more than
     * {@code maxCrop} of the painting's width or height is cut off. The painting is centred, then
     * nudged so {@code keep} is fully visible, and never leaves a gap on an axis it overflows.
     *
     * @param keep    crop-relative rectangle that must stay visible, or null for plain "contain"
     * @param maxCrop largest fraction (0..1) of each painting dimension that may be cropped away
     */
    static Fit fitScene(float viewW, float viewH, float cropW, float cropH, RectF keep, float maxCrop) {
        if (viewW <= 0 || viewH <= 0 || cropW <= 0 || cropH <= 0) return new Fit(1f, 0f, 0f);
        float contain = Math.min(viewW / cropW, viewH / cropH);
        float cover = Math.max(viewW / cropW, viewH / cropH);
        float scale = contain;
        if (keep != null && maxCrop > 0f) {
            float limit = cover;
            // keep the tap targets on screen
            if (keep.width() > 0) limit = Math.min(limit, viewW / keep.width());
            if (keep.height() > 0) limit = Math.min(limit, viewH / keep.height());
            // never crop more than maxCrop of either dimension
            float keepFraction = Math.max(0.01f, 1f - maxCrop);
            limit = Math.min(limit, Math.min(viewW / (cropW * keepFraction), viewH / (cropH * keepFraction)));
            scale = Math.max(contain, limit);
        }
        float left = placeAxis(viewW, cropW * scale, keep == null ? 0 : keep.left * scale, keep == null ? 0 : keep.right * scale);
        float top = placeAxis(viewH, cropH * scale, keep == null ? 0 : keep.top * scale, keep == null ? 0 : keep.bottom * scale);
        return new Fit(scale, left, top);
    }

    /**
     * Offset of a painting {@code size} long on a view axis {@code view} long: centred; when it
     * overflows, clamped so it still covers the axis and the kept span [{@code keepStart}, {@code keepEnd}]
     * (scaled, painting-relative) is visible.
     */
    static float placeAxis(float view, float size, float keepStart, float keepEnd) {
        float offset = (view - size) / 2f;
        if (size <= view) return offset;
        if (keepEnd > keepStart) {
            if (offset + keepStart < 0) offset = -keepStart;
            if (offset + keepEnd > view) offset = view - keepEnd;
        }
        // + 0f folds the -0f that -keepStart produces for keepStart == 0 into a plain 0
        return Math.max(view - size, Math.min(0f, offset)) + 0f;
    }

    /** Maps a rectangle in painting coordinates (941x1672 space) to view pixels. */
    static RectF toView(RectF painting, int[] crop, Fit f) {
        return new RectF(f.left + (painting.left - crop[0]) * f.scale, f.top + (painting.top - crop[1]) * f.scale,
                f.left + (painting.right - crop[0]) * f.scale, f.top + (painting.bottom - crop[1]) * f.scale);
    }

    /** Maps a view point back to painting coordinates: {x, y}. */
    static float[] toPainting(float viewX, float viewY, int[] crop, Fit f) {
        return new float[]{crop[0] + (viewX - f.left) / f.scale, crop[1] + (viewY - f.top) / f.scale};
    }

    /**
     * Union of painting-space rectangles, made crop-relative, padded by {@code pad} painting pixels
     * and limited to the crop; null for an empty list.
     */
    static RectF keepBounds(List<RectF> rects, int[] crop, float pad) {
        if (rects.isEmpty()) return null;
        float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (RectF q : rects) {
            l = Math.min(l, q.left);
            t = Math.min(t, q.top);
            r = Math.max(r, q.right);
            b = Math.max(b, q.bottom);
        }
        float w = crop[2] - crop[0], h = crop[3] - crop[1];
        return new RectF(Math.max(0, l - pad - crop[0]), Math.max(0, t - pad - crop[1]),
                Math.min(w, r + pad - crop[0]), Math.min(h, b + pad - crop[1]));
    }

    // ---- the stage: draws the painting and places children in painting coordinates -------------

    /**
     * The painting plus its overlays. Children are added with {@link #put} in painting coordinates;
     * measure/layout place them through the same {@link Fit} used to draw the bitmap. Where the
     * painting does not fill the view (tablets, landscape, foldables) the letterbox is filled with the
     * scene's material (walnut, leather or lacquer) unless the user shows their wallpaper behind the home.
     * Ticks once a second (on the second) while attached to refresh the live overlays.
     */
    static final class Stage extends FrameLayout {
        private final int[] crop;
        private final boolean keepFoot;
        private Bitmap art;
        private final Rect src = new Rect();
        private final RectF dst = new RectF();
        private final Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Paint letterbox;
        private final List<View> kids = new ArrayList<View>();
        private final List<RectF> rects = new ArrayList<RectF>();
        private Fit fit = new Fit(1f, 0f, 0f);
        private int fitW = -1, fitH = -1;
        List<Live> dockKeys;
        /** The landscape pane's copies of the dock keys (null until built). */
        List<Live> paneDockKeys;
        int dockCount;
        /** Live overlays outside the stage (the landscape pane's session clock) refreshed with it. */
        private final List<Live> tracked = new ArrayList<Live>();
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                update();
                postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L) + 10L);
            }
        };

        Stage(Context c, int scene) {
            super(c);
            this.crop = CROP[scene];
            this.keepFoot = KEEP_FOOT[scene];
            setWillNotDraw(false);
            setClipChildren(false);
            InputStream in = null;
            try {
                in = c.getAssets().open(SCENE_ASSET_DIR + FILE[scene] + ".jpg");
                art = BitmapFactory.decodeStream(in);
            } catch (Exception e) {
                art = null;
            } finally {
                try { if (in != null) in.close(); } catch (Exception e) { }
            }
            // the assets are pre-cropped to CROP; without art, keep the crop's proportions
            src.set(0, 0, art != null ? art.getWidth() : crop[2] - crop[0], art != null ? art.getHeight() : crop[3] - crop[1]);
            if (Prefs.get(c).bgMode() != Prefs.BG_WALLPAPER) {
                if (LETTERBOX_TEXTURE[scene] != null) {
                    letterbox = PipeDraw.material(c, LETTERBOX_TEXTURE[scene], 0, 1f);
                } else {
                    letterbox = new Paint();
                    letterbox.setColor(LACQUER);
                }
            }
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setColor(EDGE_COLOR);
        }

        /** Adds an overlay or touch target covering {@code r} (painting coordinates). */
        void put(View v, RectF r) {
            kids.add(v);
            rects.add(r);
            fitW = -1; // the kept area changed
            addView(v, new FrameLayout.LayoutParams(1, 1));
        }

        /** Refreshes every live overlay from one shared snapshot of the pipe collection. */
        void update() {
            PipeDataStore store = new PipeDataStore(getContext());
            for (View v : kids) {
                if (v instanceof Live) ((Live) v).refresh(store);
            }
            for (Live v : tracked) v.refresh(store);
        }

        /** Refreshes {@code v} on every tick although it lives outside the stage. */
        void track(Live v) {
            tracked.add(v);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            removeCallbacks(tick);
            post(tick);
        }

        @Override protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        /** Recomputes the painting placement for a w x h view (cached until the size or targets change). */
        private void fit(int w, int h) {
            if (w == fitW && h == fitH) return;
            fitW = w;
            fitH = h;
            RectF keep = keepBounds(rects, crop, KEEP_PAD);
            if (keep != null && keepFoot) keep.bottom = crop[3] - crop[1];
            fit = fitScene(w, h, crop[2] - crop[0], crop[3] - crop[1], keep, MAX_CROP);
            dst.set(fit.left, fit.top, fit.left + (crop[2] - crop[0]) * fit.scale, fit.top + (crop[3] - crop[1]) * fit.scale);
        }

        @Override protected void onMeasure(int ws, int hs) {
            int w = MeasureSpec.getSize(ws), h = MeasureSpec.getSize(hs);
            setMeasuredDimension(w, h);
            fit(w, h);
            for (int i = 0; i < kids.size(); i++) {
                Rect q = viewRect(i);
                kids.get(i).measure(MeasureSpec.makeMeasureSpec(Math.max(1, q.width()), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(Math.max(1, q.height()), MeasureSpec.EXACTLY));
            }
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            fit(r - l, b - t);
            for (int i = 0; i < kids.size(); i++) {
                Rect q = viewRect(i);
                View v = kids.get(i);
                v.layout(q.left, q.top, q.left + v.getMeasuredWidth(), q.top + v.getMeasuredHeight());
            }
        }

        /** Child {@code i}'s bounds in view pixels: both edges rounded, so neighbours never drift apart. */
        private Rect viewRect(int i) {
            RectF v = toView(rects.get(i), crop, fit);
            return new Rect(Math.round(v.left), Math.round(v.top), Math.round(v.right), Math.round(v.bottom));
        }

        @Override protected void onDraw(Canvas cv) {
            boolean letterboxed = dst.left > 0.5f || dst.top > 0.5f || dst.right < getWidth() - 0.5f || dst.bottom < getHeight() - 0.5f;
            if (letterboxed && letterbox != null) {
                letterbox.setAlpha(Ui.cloak ? CLOAK_ALPHA : 255);
                cv.drawRect(0, 0, getWidth(), getHeight(), letterbox);
            }
            if (art != null) {
                artPaint.setAlpha(Ui.cloak ? CLOAK_ALPHA : 255);
                cv.drawBitmap(art, src, dst, artPaint);
            }
            if (letterboxed && letterbox != null) {
                // a dark keyline where the painting meets the material, so the letterbox reads as a mount
                edgePaint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 1.5f));
                cv.drawRect(dst, edgePaint);
            }
        }
    }

    // ---- live overlays --------------------------------------------------------------------------

    /** Everything drawn live over the painting: plates that hide the sample values, then real ones. */
    static final class Live extends View {
        static final int FACE = 1, TIME = 2, DATE_LONG = 3, DATE_STACK = 4, WEATHER = 5, TIMER = 6, BLEND = 7, PIPE = 8, BLEND_NAME = 9,
                BLEND_DESC = 10, PIPE_DESC = 11, NOTE = 12, JOURNAL = 13, CHECK = 14, DOCK = 15, PLAYPAUSE = 16;
        static final int LEATHER = 1, PARCHMENT = 2, DECO = 3, PAPER_NOTE = 4, PLAYPAUSE_LEATHER = 5, PLAYPAUSE_DECO = 6;
        final int kind, style;
        int bit;
        Drawable icon;
        Bitmap glyph;
        private final MainActivity a;
        private final float d;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private String[] lines = new String[0];
        private String lastKey = "";

        Live(MainActivity a, int kind, int style) {
            super(a);
            this.a = a;
            this.kind = kind;
            this.style = style;
            this.d = a.getResources().getDisplayMetrics().density;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null); // BlurMaskFilter plates need a software layer
            refresh(new PipeDataStore(a));
        }

        private Typeface face(boolean heavy) {
            return Ui.font(getContext(), heavy);
        }

        /**
         * Recomputes this overlay's text lines (from the clock, weather cache, session clock or the
         * pipe collection {@code store}) and invalidates only when they changed.
         */
        void refresh(PipeDataStore store) {
            Prefs pr = a.prefs;
            String[] next;
            Date now = new Date();
            switch (kind) {
                case TIME:
                case FACE: {
                    boolean h24 = a.use24h();
                    next = new String[]{new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now),
                            h24 ? "" : new SimpleDateFormat("a", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()),
                            new SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now), String.valueOf(now.getTime() / 1000L)};
                    break;
                }
                case DATE_LONG:
                    next = new String[]{new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault())};
                    break;
                case DATE_STACK:
                    next = new String[]{new SimpleDateFormat("EEE", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()),
                            new SimpleDateFormat("MMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()),
                            new SimpleDateFormat("d", Locale.getDefault()).format(now)};
                    break;
                case WEATHER: {
                    Weather.Data w = weather(pr);
                    if (w == null) next = new String[]{"—", "NO WEATHER YET", ""};
                    else {
                        String hl = "";
                        if (!w.days.isEmpty()) hl = "H " + Math.round(w.days.get(0).hi) + "°   L " + Math.round(w.days.get(0).lo) + "°";
                        next = new String[]{Math.round(w.temp) + "°", cap(Weather.describe(w.code)), hl, String.valueOf(Weather.kind(w.code)), w.day ? "d" : "n"};
                    }
                    break;
                }
                case TIMER:
                    next = new String[]{timerText(pr), timerRunning(pr) ? "run" : "stop"};
                    break;
                case PLAYPAUSE:
                    next = new String[]{timerRunning(pr) ? "run" : "stop"};
                    break;
                case BLEND:
                    next = new String[]{selName(store, "blends", "Choose a blend"), selDesc(store, "blends")};
                    break;
                case BLEND_NAME:
                    next = new String[]{selName(store, "blends", "Choose a blend")};
                    break;
                case BLEND_DESC:
                    next = new String[]{selDesc(store, "blends")};
                    break;
                case PIPE:
                    next = new String[]{selName(store, "pipes", "Choose a pipe")};
                    break;
                case PIPE_DESC:
                    next = new String[]{selDesc(store, "pipes")};
                    break;
                case NOTE:
                case JOURNAL: {
                    String n = latestNote(store);
                    next = new String[]{n == null ? (kind == NOTE ? "Tap to write a note." : "Your journal is empty. Tap to begin.") : n};
                    break;
                }
                case CHECK:
                    next = new String[]{((store.ritualMask() >> bit) & 1) == 1 ? "1" : "0"};
                    break;
                default:
                    next = new String[0];
            }
            String key = String.valueOf(Ui.cloak) + "|" + join(next);
            if (!key.equals(lastKey)) {
                lastKey = key;
                lines = next;
                if (kind == FACE || kind == WEATHER || kind == TIMER || kind == DATE_STACK || kind == DATE_LONG || kind == TIME) {
                    setContentDescription(join(next).replace('|', ' '));
                }
                invalidate();
            }
        }

        private static String join(String[] a) {
            StringBuilder b = new StringBuilder();
            for (String s : a) b.append(s).append('|');
            return b.toString();
        }

        private static String cap(String s) {
            if (s == null || s.isEmpty()) return "";
            String l = s.toLowerCase(Locale.getDefault());
            return Character.toUpperCase(l.charAt(0)) + l.substring(1);
        }

        // plates ------------------------------------------------------------------------------

        private void plate(Canvas cv, float inset) {
            r.set(inset, inset, getWidth() - inset, getHeight() - inset);
            float rad = Math.min(r.height() * 0.25f, 10 * d);
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setMaskFilter(new BlurMaskFilter(Math.max(1f, 2.4f * d), BlurMaskFilter.Blur.NORMAL));
            if (style == PARCHMENT || style == PAPER_NOTE) {
                p.setShader(new RadialGradient(r.centerX(), r.centerY(), Math.max(r.width(), r.height()) * 0.7f,
                        new int[]{0xFFF4E6C6, 0xFFEAD6AE, 0xFFDDC496}, new float[]{0f, 0.7f, 1f}, Shader.TileMode.CLAMP));
            } else if (style == DECO || style == PLAYPAUSE_DECO) {
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[]{0xFF4A161B, 0xFF33100F, 0xFF240A0B}, null, Shader.TileMode.CLAMP));
            } else {
                p.setShader(new LinearGradient(0, r.top, 0, r.bottom, new int[]{0xFF46241A, 0xFF331811, 0xFF27120C}, null, Shader.TileMode.CLAMP));
            }
            cv.drawRoundRect(r, rad, rad, p);
            p.setMaskFilter(null);
            p.setShader(null);
            if (style == PARCHMENT || style == PAPER_NOTE) {
                Paint grain = PipeDraw.material(getContext(), "paper_grain.jpg", 0, 1f);
                grain.setAlpha(70);
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    grain.setBlendMode(android.graphics.BlendMode.MULTIPLY);
                    cv.drawRoundRect(r, rad, rad, grain);
                }
            }
        }

        private int ink() {
            if (style == PARCHMENT || style == PAPER_NOTE) return 0xFF4A2414;
            if (style == DECO || style == PLAYPAUSE_DECO) return 0xFFF0CD86;
            return 0xFFF2CF93;
        }

        /** Draw one line of text centred in a box, shrunk to fit. */
        private void text(Canvas cv, String s, float cx, float cy, float maxW, float size, boolean heavy, int color, Paint.Align align) {
            if (s == null || s.isEmpty()) return;
            tp.setTypeface(face(heavy));
            tp.setColor(color);
            tp.setTextAlign(align);
            tp.setTextSize(size);
            float w = tp.measureText(s);
            if (w > maxW && w > 0) tp.setTextSize(Math.max(size * 0.45f, size * maxW / w));
            Paint.FontMetrics fm = tp.getFontMetrics();
            cv.drawText(s, cx, cy - (fm.ascent + fm.descent) / 2f, tp);
        }

        /** Wrap text into up to n lines, centred vertically in the view. */
        private void wrapped(Canvas cv, String s, float size, boolean heavy, int color, int n, boolean italic) {
            tp.setTypeface(italic ? Typeface.create(face(false), Typeface.ITALIC) : face(heavy));
            tp.setColor(color);
            tp.setTextAlign(Paint.Align.LEFT);
            tp.setTextSize(size);
            float maxW = getWidth() - 10 * d;
            List<String> out = new ArrayList<String>();
            String[] words = s.split("\\s+");
            StringBuilder line = new StringBuilder();
            for (String w : words) {
                String t = line.length() == 0 ? w : line + " " + w;
                if (tp.measureText(t) > maxW && line.length() > 0) {
                    out.add(line.toString());
                    line = new StringBuilder(w);
                    if (out.size() == n) break;
                } else line = new StringBuilder(t);
            }
            if (out.size() < n && line.length() > 0) out.add(line.toString());
            if (out.size() == n && words.length > 0 && !s.endsWith(out.get(n - 1))) {
                String last = out.get(n - 1);
                while (last.length() > 1 && tp.measureText(last + "…") > maxW) last = last.substring(0, last.length() - 1);
                out.set(n - 1, last + "…");
            }
            Paint.FontMetrics fm = tp.getFontMetrics();
            float lh = (fm.descent - fm.ascent) * 1.05f;
            float y = getHeight() / 2f - lh * out.size() / 2f - fm.ascent;
            for (String l : out) {
                cv.drawText(l, 5 * d, y, tp);
                y += lh;
            }
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            if (w < 2 || h < 2) return;
            switch (kind) {
                case FACE: drawFace(cv, w, h); return;
                case DOCK: drawDock(cv, w, h); return;
                case CHECK: drawCheck(cv, w, h); return;
                case PLAYPAUSE: drawPlayPause(cv, w, h); return;
                default: break;
            }
            plate(cv, 0);
            int ink = ink();
            switch (kind) {
                case TIME: {
                    float big = h * 0.86f;
                    tp.setTypeface(face(true));
                    tp.setTextSize(big);
                    float tw = tp.measureText(lines[0]);
                    tp.setTextSize(big * 0.42f);
                    float aw = lines[1].isEmpty() ? 0 : tp.measureText(lines[1]) + 6 * d;
                    float total = Math.min(w - 6 * d, tw + aw);
                    float x0 = (w - total) / 2f;
                    float sc = (tw + aw) > (w - 6 * d) ? (w - 6 * d) / (tw + aw) : 1f;
                    text(cv, lines[0], x0, h * 0.54f, tw * sc + 1, big * sc, true, 0xFF6E1E16, Paint.Align.LEFT);
                    text(cv, lines[1], x0 + tw * sc + 6 * d * sc, h * 0.66f, aw * sc + 1, big * 0.42f * sc, true, 0xFF6E1E16, Paint.Align.LEFT);
                    break;
                }
                case DATE_LONG:
                    text(cv, lines[0], w / 2f, h / 2f, w - 8 * d, h * 0.62f, true, ink, Paint.Align.CENTER);
                    break;
                case DATE_STACK:
                    text(cv, lines[0], w / 2f, h * 0.2f, w - 8 * d, h * 0.2f, true, ink, Paint.Align.CENTER);
                    text(cv, lines[1], w / 2f, h * 0.43f, w - 8 * d, h * 0.2f, true, ink, Paint.Align.CENTER);
                    text(cv, lines[2], w / 2f, h * 0.72f, w - 8 * d, h * 0.36f, true, ink, Paint.Align.CENTER);
                    break;
                case WEATHER:
                    drawWeather(cv, w, h, ink);
                    break;
                case TIMER:
                    text(cv, lines[0], w / 2f, h / 2f, w - 8 * d, h * 0.78f, true, ink, Paint.Align.CENTER);
                    break;
                case BLEND:
                    text(cv, lines[0], 5 * d, h * 0.36f, w - 10 * d, h * 0.34f, true, ink, Paint.Align.LEFT);
                    text(cv, lines[1], 5 * d, h * 0.74f, w - 10 * d, h * 0.2f, false, PipeDraw.a(ink, 0.8f), Paint.Align.LEFT);
                    break;
                case PIPE:
                    wrapped(cv, lines[0], Math.min(h * 0.36f, 18 * d), true, ink, 2, false);
                    break;
                case BLEND_NAME:
                    text(cv, lines[0], 4 * d, h / 2f, w - 8 * d, h * 0.72f, true, ink, Paint.Align.LEFT);
                    break;
                case BLEND_DESC:
                case PIPE_DESC:
                    wrapped(cv, lines[0], Math.min(h * 0.62f, 13 * d), false, PipeDraw.a(ink, 0.85f), kind == PIPE_DESC ? 2 : 1, false);
                    break;
                case NOTE:
                case JOURNAL:
                    wrapped(cv, lines[0], Math.min(h * 0.2f, 14 * d), false, 0xFF3A2114, kind == NOTE ? 3 : 4, true);
                    break;
                default:
                    break;
            }
        }

        private void drawWeather(Canvas cv, float w, float h, int ink) {
            if (lines.length < 3) return;
            boolean tall = h > w * 0.9f;
            if (lines.length >= 5) {
                // a small weather mark in the painting's line style
                Bitmap g = IconFactory.glyph(getContext(), "weather");
                if (g != null) {
                    float s = tall ? Math.min(w * 0.42f, h * 0.3f) : h * 0.42f;
                    float x = tall ? w / 2f - s / 2f : 6 * d, y = tall ? h * 0.05f : h * 0.08f;
                    p.setColorFilter(new android.graphics.PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
                    cv.drawBitmap(g, null, new RectF(x, y, x + s, y + s), p);
                    p.setColorFilter(null);
                }
            }
            if (tall) {
                text(cv, lines[0], w / 2f, h * 0.55f, w - 6 * d, h * 0.26f, true, ink, Paint.Align.CENTER);
                text(cv, lines[1], w / 2f, h * 0.8f, w - 6 * d, h * 0.11f, false, ink, Paint.Align.CENTER);
                text(cv, lines[2], w / 2f, h * 0.93f, w - 6 * d, h * 0.08f, false, PipeDraw.a(ink, 0.8f), Paint.Align.CENTER);
            } else {
                text(cv, lines[0], w * 0.62f, h * 0.3f, w * 0.6f, h * 0.36f, true, ink, Paint.Align.CENTER);
                text(cv, lines[1], w / 2f, h * 0.64f, w - 8 * d, h * 0.17f, false, ink, Paint.Align.CENTER);
                text(cv, lines[2], w / 2f, h * 0.86f, w - 8 * d, h * 0.14f, false, PipeDraw.a(ink, 0.8f), Paint.Align.CENTER);
            }
        }

        /** A whole new face for the Ember & Briar clock: Roman dial, live hands, live time and date. */
        private void drawFace(Canvas cv, float w, float h) {
            float cx = w / 2f, cy = h / 2f, R = Math.min(w, h) / 2f - d;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx - R * 0.15f, cy - R * 0.2f, R * 1.25f, new int[]{0xFFF7EAD0, 0xFFEFDDB7, 0xFFD9C08E, 0xFFB8955E},
                    new float[]{0f, 0.55f, 0.88f, 1f}, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, R, p);
            p.setShader(null);
            Paint grain = PipeDraw.material(getContext(), "paper_grain.jpg", 0, 1f);
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                grain.setAlpha(80);
                grain.setBlendMode(android.graphics.BlendMode.MULTIPLY);
                cv.drawCircle(cx, cy, R, grain);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, 1.2f * d));
            p.setColor(0xFF2A1A10);
            cv.drawCircle(cx, cy, R * 0.97f, p);
            p.setStrokeWidth(Math.max(1, 0.7f * d));
            cv.drawCircle(cx, cy, R * 0.68f, p);
            // minute ticks
            for (int i = 0; i < 60; i++) {
                double ang = Math.PI * 2 * i / 60.0;
                float r0 = R * (i % 5 == 0 ? 0.88f : 0.92f), r1 = R * 0.96f;
                p.setStrokeWidth((i % 5 == 0 ? 1.6f : 0.8f) * d);
                cv.drawLine(cx + (float) Math.sin(ang) * r0, cy - (float) Math.cos(ang) * r0, cx + (float) Math.sin(ang) * r1, cy - (float) Math.cos(ang) * r1, p);
            }
            // Roman numerals, upright
            String[] rn = {"XII", "I", "II", "III", "IIII", "V", "VI", "VII", "VIII", "IX", "X", "XI"};
            tp.setTypeface(Ui.font(getContext(), true));
            tp.setColor(0xFF1E120A);
            tp.setTextAlign(Paint.Align.CENTER);
            tp.setTextSize(R * 0.17f);
            Paint.FontMetrics fm = tp.getFontMetrics();
            for (int i = 0; i < 12; i++) {
                double ang = Math.PI * 2 * i / 12.0;
                float rr = R * 0.78f;
                cv.drawText(rn[i], cx + (float) Math.sin(ang) * rr, cy - (float) Math.cos(ang) * rr - (fm.ascent + fm.descent) / 2f, tp);
            }
            // the maker's line, live time and date
            tp.setTypeface(Typeface.create(Ui.font(getContext(), false), Typeface.ITALIC));
            tp.setTextSize(R * 0.11f);
            tp.setColor(0xFF3A2414);
            cv.drawText("Ember & Briar", cx, cy - R * 0.36f, tp);
            String t = lines.length > 0 ? lines[0] : "";
            tp.setTypeface(Ui.font(getContext(), true));
            tp.setColor(0xFF1E120A);
            tp.setTextSize(R * 0.26f);
            cv.drawText(t, cx, cy + R * 0.36f, tp);
            tp.setTypeface(Ui.font(getContext(), false));
            tp.setTextSize(R * 0.1f);
            String ds = (lines.length > 2 ? lines[2] : "") + (lines.length > 1 && !lines[1].isEmpty() ? "  •  " + lines[1] : "");
            cv.drawText(ds, cx, cy + R * 0.52f, tp);
            // hands
            Calendar c = Calendar.getInstance();
            float mins = c.get(Calendar.MINUTE) + c.get(Calendar.SECOND) / 60f;
            float hrs = (c.get(Calendar.HOUR) + mins / 60f);
            hand(cv, cx, cy, hrs / 12f * 360f, R * 0.46f, R * 0.07f);
            hand(cv, cx, cy, mins / 60f * 360f, R * 0.66f, R * 0.05f);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx - R * 0.02f, cy - R * 0.02f, R * 0.07f, new int[]{0xFFF4DE9C, 0xFFC9A04E, 0xFF6B4A1C}, null, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, R * 0.055f, p);
            p.setShader(null);
        }

        private void hand(Canvas cv, float cx, float cy, float deg, float len, float wid) {
            cv.save();
            cv.rotate(deg, cx, cy);
            path.reset();
            path.moveTo(cx, cy - len);
            path.lineTo(cx + wid * 0.55f, cy - len * 0.72f);
            path.lineTo(cx + wid * 0.22f, cy - len * 0.62f);
            path.lineTo(cx + wid * 0.18f, cy + len * 0.14f);
            path.lineTo(cx - wid * 0.18f, cy + len * 0.14f);
            path.lineTo(cx - wid * 0.22f, cy - len * 0.62f);
            path.lineTo(cx - wid * 0.55f, cy - len * 0.72f);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x40000000);
            cv.translate(0.8f * d, 1.2f * d);
            cv.drawPath(path, p);
            cv.translate(-0.8f * d, -1.2f * d);
            p.setColor(0xFF140C08);
            cv.drawPath(path, p);
            cv.restore();
        }

        private void drawDock(Canvas cv, float w, float h) {
            r.set(2 * d, 2 * d, w - 2 * d, h - 2 * d);
            float rad = w * 0.18f;
            p.setStyle(Paint.Style.FILL);
            p.setMaskFilter(new BlurMaskFilter(1.6f * d, BlurMaskFilter.Blur.NORMAL));
            p.setShader(new LinearGradient(0, r.top, 0, r.bottom, style == DECO ? new int[]{0xFF5A1B20, 0xFF3A0F13} : new int[]{0xFF5A2318, 0xFF3A160F},
                    null, Shader.TileMode.CLAMP));
            cv.drawRoundRect(r, rad, rad, p);
            p.setMaskFilter(null);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.4f * d);
            p.setColor(0xCCD8A658);
            r.inset(2 * d, 2 * d);
            cv.drawRoundRect(r, rad * 0.85f, rad * 0.85f, p);
            float s = w * 0.56f;
            RectF ir = new RectF(w / 2f - s / 2f, h / 2f - s / 2f, w / 2f + s / 2f, h / 2f + s / 2f);
            if (glyph != null) {
                p.setStyle(Paint.Style.FILL);
                p.setColorFilter(new android.graphics.PorterDuffColorFilter(Ui.cloak ? 0x99D8A658 : 0xFFF0C680, PorterDuff.Mode.SRC_IN));
                cv.drawBitmap(glyph, null, ir, p);
                p.setColorFilter(null);
            } else if (icon != null) {
                icon.setBounds(Math.round(ir.left), Math.round(ir.top), Math.round(ir.right), Math.round(ir.bottom));
                icon.draw(cv);
            } else {
                text(cv, "+", w / 2f, h / 2f, w, h * 0.5f, true, 0x99F0C680, Paint.Align.CENTER);
            }
        }

        private void drawCheck(Canvas cv, float w, float h) {
            boolean on = lines.length > 0 && "1".equals(lines[0]);
            r.set(0, 0, w, h);
            p.setStyle(Paint.Style.FILL);
            p.setMaskFilter(new BlurMaskFilter(1.2f * d, BlurMaskFilter.Blur.NORMAL));
            p.setColor(0xFFEEDDB8);
            cv.drawRect(r, p);
            p.setMaskFilter(null);
            float k = Math.min(w, h) * 0.18f;
            r.set(k, k, w - k, h - k);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, 1.5f * d));
            p.setColor(0xFF3A2114);
            cv.drawRect(r, p);
            if (on) {
                p.setStrokeWidth(2.6f * d);
                p.setColor(0xFF7A1E14);
                p.setStrokeCap(Paint.Cap.ROUND);
                path.reset();
                path.moveTo(r.left + r.width() * 0.15f, r.top + r.height() * 0.5f);
                path.lineTo(r.left + r.width() * 0.42f, r.bottom - r.height() * 0.1f);
                path.lineTo(r.right + r.width() * 0.15f, r.top - r.height() * 0.25f);
                cv.drawPath(path, p);
                p.setStrokeCap(Paint.Cap.BUTT);
            }
        }

        private void drawPlayPause(Canvas cv, float w, float h) {
            boolean run = lines.length > 0 && "run".equals(lines[0]);
            float cx = w / 2f, cy = h / 2f, R = Math.min(w, h) / 2f - d;
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx, cy - R * 0.3f, R * 1.3f, style == PLAYPAUSE_DECO ? new int[]{0xFF6A2026, 0xFF3A0F13} : new int[]{0xFF5A2A1C, 0xFF2E150E},
                    null, Shader.TileMode.CLAMP));
            cv.drawCircle(cx, cy, R, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.6f * d);
            p.setColor(0xFFD8A658);
            cv.drawCircle(cx, cy, R * 0.9f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFF0C680);
            float s = R * 0.42f;
            if (run) {
                cv.drawRect(cx - s * 0.75f, cy - s, cx - s * 0.25f, cy + s, p);
                cv.drawRect(cx + s * 0.25f, cy - s, cx + s * 0.75f, cy + s, p);
            } else {
                path.reset();
                path.moveTo(cx - s * 0.6f, cy - s);
                path.lineTo(cx + s * 0.9f, cy);
                path.lineTo(cx - s * 0.6f, cy + s);
                path.close();
                cv.drawPath(path, p);
            }
        }
    }
}
