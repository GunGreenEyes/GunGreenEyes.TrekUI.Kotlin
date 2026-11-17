package com.gungreeneyes.trekui;

import android.content.Intent;
import android.provider.AlarmClock;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.provider.CalendarContract;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;
import java.util.Random;

/**
 * Home screens for the three pipe-lounge interfaces. Every slot, tile, panel and dock function is the
 * same as on the console home (long-press anything to customise it); only the arrangement and the
 * materials change:
 *
 *  TOBACCONIST   leather-inset header plaque, then a walnut cabinet with a row of brass plates on
 *                its top shelf and three columns of drawers; a pipe rack for the dock.
 *  EMBER LOUNGE  gilt header with the clock on a sunburst, a marquee of lacquer keys beneath it,
 *                then two columns of tall stepped panels; a gilt bar for the dock.
 *  BLEND JOURNAL stitched leather cover with the clock on a tin lid, then a page of kraft tags
 *                with index tabs down the right edge; a leather strap for the dock.
 *
 * <p>MainActivity calls {@link #build} for factions 3-5 (the painted scenes 6-8 use
 * {@link PipeScene}) and {@link #tile} for each home tile. On wide landscape windows (landscape
 * phones, landscape tablets, desktop windows) the header plaque and the main plaque sit side by
 * side instead of stacked, so the 70sp clock no longer leaves the tiles a sliver of height; the
 * dock stays full-width underneath. See {@link #sideBySide}.
 */
final class PipeHome {
    /** Share of the width given to the header pane in the side-by-side layout. */
    static final float HEADER_PANE_WEIGHT = 0.42f;
    /** Minimum window width (dp) for the side-by-side layout (matches a MEDIUM width size class). */
    static final float SIDE_BY_SIDE_MIN_WIDTH_DP = 600f;

    private PipeHome() {}

    /**
     * True when the header and the main plaque should be laid out side by side: the window is
     * landscape and at least {@link #SIDE_BY_SIDE_MIN_WIDTH_DP} wide.
     */
    static boolean sideBySide(float widthDp, float heightDp) {
        return widthDp > heightDp && widthDp >= SIDE_BY_SIDE_MIN_WIDTH_DP;
    }

    /**
     * Builds the whole home layout for the current pipe interface and wires its views into the
     * activity's shared fields (headerFrame, mainFrame, clockView, tilesBox, homeScroll...), which the
     * activity's clock, tile and panel code then fill.
     */
    static LinearLayout build(final MainActivity a) {
        Palette pal = a.pal;
        boolean tob = Skin.tob(), lounge = Skin.lounge(), journal = Skin.journal();
        int ink = Ui.ink();
        ScreenMetrics metrics = ScreenMetrics.of(a);
        boolean sideBySide = sideBySide(metrics.widthDp, metrics.heightDp);
        LinearLayout home = new LinearLayout(a);
        home.setOrientation(LinearLayout.VERTICAL);
        home.setClipToPadding(false);
        // the header column: home itself when stacked, the left pane when side by side
        LinearLayout panes = null;
        LinearLayout headerPane = home;
        if (sideBySide) {
            panes = new LinearLayout(a);
            panes.setOrientation(LinearLayout.HORIZONTAL);
            headerPane = new LinearLayout(a);
            headerPane.setOrientation(LinearLayout.VERTICAL);
            panes.addView(headerPane, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, HEADER_PANE_WEIGHT));
            home.addView(panes, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));
        }
        final Runnable lore = new Runnable() {
            @Override public void run() {
                lore(a);
            }
        };

        // ---- header plaque ------------------------------------------------------------------
        a.headerFrame = new ConsoleFrame(a);
        a.headerFrame.plaque = true;
        a.headerFrame.plaqueHeader = true;
        a.headerFrame.plaqueBorder = Ui.dp(a, tob ? 14.0f : 10.0f);
        a.headerFrame.colorTop = a.slotColor("hElbowT", pal.c(journal ? 2 : 0));
        a.headerFrame.colorBot = a.slotColor("hElbowB", pal.c(journal ? 2 : 0));
        a.headerFrame.topBlocks = new int[]{a.slotColor("hBlockT0", pal.c(0))};
        a.headerFrame.botBlocks = new int[]{a.slotColor("hBlockB0", pal.c(3)), a.slotColor("hBlockB1", pal.c(1))};
        a.headerFrame.setPartListener(a.partListener("h"));
        TextView title = Ui.oneLine(Ui.bold(a, a.slotLabel("title", Skin.title(), false), 26.0f, a.slotColor("title", lounge ? pal.c(0) : ink)));
        title.setPadding(Ui.dp(a, 6.0f), Ui.dp(a, 5.0f), Ui.dp(a, 6.0f), Ui.dp(a, 5.0f));
        a.bindSlot(title, "title", lore);
        a.headerFrame.setTitle(title);

        LinearLayout head = new LinearLayout(a);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setGravity(sideBySide ? Gravity.CENTER : Gravity.CENTER_HORIZONTAL);
        head.setPadding(0, Ui.dp(a, 2.0f), 0, Ui.dp(a, 4.0f));
        a.shipView = Ui.oneLine(Ui.text(a, a.prefs.headerText().toUpperCase(Locale.getDefault()), 14.0f, a.slotColor("ship", pal.c(journal ? 1 : 4))));
        a.shipView.setGravity(Gravity.CENTER);
        a.bindSlot(a.shipView, "ship", null);
        head.addView(a.shipView, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout clockRow = new LinearLayout(a);
        clockRow.setGravity(Gravity.CENTER);
        a.clockView = Ui.bold(a, "--:--", 70.0f, a.slotColor("clock", pal.textHi));
        a.clockView.setLetterSpacing(0.01f);
        clockRow.addView(a.clockView);
        a.ampmView = Ui.bold(a, "", 20.0f, pal.c(1));
        a.ampmView.setPadding(Ui.dp(a, 6.0f), 0, 0, Ui.dp(a, 10.0f));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.gravity = Gravity.BOTTOM;
        clockRow.addView(a.ampmView, ap);
        a.bindSlot(clockRow, "clock", new Runnable() {
            @Override public void run() {
                a.startSafe(new Intent(AlarmClock.ACTION_SHOW_ALARMS), a.clockView);
            }
        });
        Drawable orn = Skin.headerOrnament(a, pal);
        if (orn != null) clockRow.setBackground(orn);
        if (tob) clockRow.setPadding(Ui.dp(a, 54.0f), Ui.dp(a, 10.0f), Ui.dp(a, 54.0f), Ui.dp(a, 8.0f));
        else if (lounge) clockRow.setPadding(Ui.dp(a, 30.0f), Ui.dp(a, 26.0f), Ui.dp(a, 30.0f), Ui.dp(a, 6.0f));
        else clockRow.setPadding(Ui.dp(a, 56.0f), Ui.dp(a, 18.0f), Ui.dp(a, 56.0f), Ui.dp(a, 16.0f));
        a.clockRowView = clockRow;
        clockRow.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol) a.fitClock();
            }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = Ui.dp(a, 4.0f);
        head.addView(clockRow, cp);

        a.dateView = Ui.bold(a, "", 20.0f, a.slotColor("date", pal.c(journal ? 2 : 0)));
        a.dateView.setGravity(Gravity.CENTER);
        a.bindSlot(a.dateView, "date", new Runnable() {
            @Override public void run() {
                a.startSafe(new Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(String.valueOf(System.currentTimeMillis())).build()), a.dateView);
            }
        });
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(-1, -2);
        dateParams.topMargin = Ui.dp(a, 6.0f);
        head.addView(a.dateView, dateParams);
        a.infoView = Ui.oneLine(Ui.text(a, "", 14.0f, pal.textLo));
        a.infoView.setGravity(Gravity.CENTER);
        a.infoView.setPadding(Ui.dp(a, 4.0f), Ui.dp(a, 6.0f), Ui.dp(a, 4.0f), Ui.dp(a, 2.0f));
        head.addView(a.infoView, new LinearLayout.LayoutParams(-1, -2));
        a.headerFrame.setContent(head);
        headerPane.addView(a.headerFrame, sideBySide
                ? new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f)
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- the Ember Lounge marquee sits between the header and the panels ---------------------
        if (lounge) {
            LinearLayout row = navRow(a, lore, false);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 46.0f));
            rp.topMargin = Ui.dp(a, 8.0f);
            rp.leftMargin = Ui.dp(a, 12.0f);
            rp.rightMargin = Ui.dp(a, 12.0f);
            headerPane.addView(row, rp);
        }

        // ---- main plaque ----------------------------------------------------------------------
        a.mainFrame = new ConsoleFrame(a);
        a.mainFrame.plaque = true;
        a.mainFrame.plaqueHeader = false;
        a.mainFrame.plaqueBorder = Ui.dp(a, tob ? 14.0f : 10.0f);
        a.mainFrame.colorTop = a.slotColor("mElbowT", pal.c(lounge ? 0 : 4));
        a.mainFrame.colorBot = a.slotColor("mElbowB", pal.c(2));
        a.mainFrame.topBlocks = new int[]{a.slotColor("mBlockT0", pal.c(4))};
        a.mainFrame.botBlocks = new int[]{a.slotColor("mBlockB0", pal.c(2))};
        a.mainFrame.setPartListener(a.partListener("m"));
        TextView ops = Ui.oneLine(Ui.bold(a, a.slotLabel("ops", Skin.ops(), false), 19.0f, a.slotColor("ops", lounge ? pal.c(4) : ink)));
        ops.setPadding(Ui.dp(a, 6.0f), Ui.dp(a, 4.0f), Ui.dp(a, 6.0f), Ui.dp(a, 4.0f));
        a.bindSlot(ops, "ops", null);
        a.mainFrame.setTitle(ops);
        TextView foot = Ui.oneLine(Ui.text(a, a.slotLabel("foot", Skin.foot(), false), 13.0f, a.slotColor("foot", lounge ? pal.c(2) : ink)));
        foot.setPadding(Ui.dp(a, 6.0f), Ui.dp(a, 3.0f), Ui.dp(a, 6.0f), Ui.dp(a, 3.0f));
        a.bindSlot(foot, "foot", null);
        a.mainFrame.setFooter(foot);

        a.homeScroll = new ScrollView(a);
        a.homeScroll.setVerticalScrollBarEnabled(false);
        a.homeScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        a.homeScroll.setFillViewport(true);
        LinearLayout inner = new LinearLayout(a);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(0, Ui.dp(a, 2.0f), 0, Ui.dp(a, 4.0f));
        a.tilesBox = new LinearLayout(a);
        a.tilesBox.setOrientation(LinearLayout.VERTICAL);
        inner.addView(a.tilesBox, new LinearLayout.LayoutParams(-1, -2));
        a.buildPanels(inner);
        a.homeScroll.addView(inner);

        View content;
        if (tob) {
            // the cabinet's top shelf: brass plates above the drawers
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(navRow(a, lore, false), new LinearLayout.LayoutParams(-1, Ui.dp(a, 44.0f)));
            View shelf = new View(a);
            shelf.setBackground(Skin.rule(a, pal.c(0)));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 4.0f));
            sp.topMargin = Ui.dp(a, 8.0f);
            sp.bottomMargin = Ui.dp(a, 8.0f);
            col.addView(shelf, sp);
            col.addView(a.homeScroll, new LinearLayout.LayoutParams(-1, 0, 1.0f));
            content = col;
        } else if (journal) {
            // the journal page with index tabs down its right edge
            LinearLayout rowL = new LinearLayout(a);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            rowL.addView(a.homeScroll, new LinearLayout.LayoutParams(0, -1, 1.0f));
            LinearLayout tabs = navRow(a, lore, true);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(Ui.dp(a, 74.0f), -1);
            tp.leftMargin = Ui.dp(a, 8.0f);
            rowL.addView(tabs, tp);
            content = rowL;
        } else {
            content = a.homeScroll;
        }
        a.mainFrame.setContent(content);
        if (panes != null) {
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - HEADER_PANE_WEIGHT);
            mp.leftMargin = Math.max(metrics.gutterPx(), Ui.dp(a, 8.0f));
            panes.addView(a.mainFrame, mp);
        } else {
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
            mp.topMargin = Ui.dp(a, 8.0f);
            home.addView(a.mainFrame, mp);
        }
        a.addDock(home);
        return home;
    }

    /** The three navigation keys plus LORE: a row of plates/keys, or index tabs for the journal. */
    private static LinearLayout navRow(final MainActivity a, Runnable lore, boolean vertical) {
        Palette pal = a.pal;
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        String[] keys = {"nav0", "nav1", "nav2", "nav3"};
        int[] colors = {pal.c(0), pal.c(2), pal.c(3), pal.c(1)};
        Runnable[] actions = {
                new Runnable() { @Override public void run() { a.openDrawer(null, false); } },
                new Runnable() { @Override public void run() { a.openDrawer(null, true); } },
                new Runnable() { @Override public void run() { a.openConfig(); } },
                lore};
        for (int i = 0; i < 4; i++) {
            String label = i < 3 ? Skin.nav(i) : "LORE";
            int kind = vertical ? Ui.RIGHT : i == 0 ? Ui.LEFT : i == 3 ? Ui.RIGHT : Ui.SQUARE;
            TextView b = Ui.oneLine(Ui.bold(a, a.slotLabel(keys[i], label, true), i < 3 ? 15.0f : 13.0f, Ui.ink()));
            b.setGravity(Gravity.CENTER);
            b.setPadding(Ui.dp(a, 4.0f), 0, Ui.dp(a, 4.0f), 0);
            b.setBackground(Ui.touch(Ui.shape(a, a.slotColor(keys[i], colors[i]), kind, 0.0f)));
            b.setContentDescription(label);
            a.bindSlot(b, keys[i], actions[i]);
            LinearLayout.LayoutParams lp;
            if (vertical) {
                lp = new LinearLayout.LayoutParams(-1, Ui.dp(a, i < 3 ? 60.0f : 46.0f));
                if (i > 0) lp.topMargin = Ui.dp(a, 8.0f);
            } else {
                lp = new LinearLayout.LayoutParams(0, -1, i < 3 ? 1.0f : 0.62f);
                if (i > 0) lp.leftMargin = Ui.dp(a, 6.0f);
            }
            row.addView(b, lp);
        }
        return row;
    }

    /**
     * One home tile: a drawer front with a card holder (Tobacconist), a stepped lacquer panel
     * (Ember Lounge) or a numbered kraft tag (Blend Journal); empty tiles show "+ ASSIGN".
     *
     * @param i index into {@code a.tiles}
     */
    static View tile(final MainActivity a, int i) {
        Tile tile = a.tiles.get(i);
        int color = a.tileColor(tile, i);
        boolean tob = Skin.tob(), lounge = Skin.lounge();
        FrameLayout f = new FrameLayout(a);
        f.setClipChildren(false);
        boolean empty = tile.isEmpty();
        f.setBackground(Ui.touch(PipeDraw.tile(a, color, empty, i)));
        if (empty) {
            TextView t = Ui.bold(a, "+ ASSIGN", 14.0f, Ui.alpha(tob ? a.pal.c(0) : color, 0.85f));
            f.addView(t, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            f.setContentDescription("Assign tile " + (i + 1));
        } else {
            AppInfo find = tile.comp != null ? a.repo.find(tile.comp, tile.user) : null;
            String glyph = (find != null && find.glyph != null) ? find.glyph : (tile.action != null ? HomeLogic.actionGlyph(tile.action) : null);
            Bitmap art = a.prefs.iconStyle() == Prefs.ICON_FACTION ? IconFactory.factionArt(a, glyph) : null;
            ImageView icon = a.tileIcon(tile, find, Skin.tileInk(), art);
            String label = (tile.label != null && tile.label.length() > 0) ? tile.label : find != null ? find.label : Tile.actionLabel(tile.action);
            label = label.toUpperCase(Locale.getDefault());
            if (tob) {
                LinearLayout card = new LinearLayout(a);
                card.setGravity(Gravity.CENTER);
                int is = Ui.dp(a, art != null ? 26.0f : 18.0f);
                card.addView(icon, new LinearLayout.LayoutParams(is, is));
                TextView t = Ui.oneLine(Ui.bold(a, label, 13.0f, Skin.ink()));
                LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
                tl.leftMargin = Ui.dp(a, 5.0f);
                card.addView(t, tl);
                FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, Ui.dp(a, PipeDraw.TOB_CARD_H), Gravity.TOP);
                cl.leftMargin = Ui.dp(a, PipeDraw.TOB_CARD_SIDE + 3);
                cl.rightMargin = Ui.dp(a, PipeDraw.TOB_CARD_SIDE + 3);
                cl.topMargin = Ui.dp(a, PipeDraw.TOB_CARD_TOP);
                f.addView(card, cl);
            } else if (lounge) {
                int is = Ui.dp(a, art != null ? 40.0f : 32.0f);
                FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(is, is, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                il.topMargin = Ui.dp(a, art != null ? 20.0f : 29.0f);
                f.addView(icon, il);
                TextView t = Ui.oneLine(Ui.bold(a, label, 12.0f, a.pal.c(5)));
                t.setLetterSpacing(0.14f);
                t.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
                tl.bottomMargin = Ui.dp(a, 15.0f);
                tl.leftMargin = Ui.dp(a, 10.0f);
                tl.rightMargin = Ui.dp(a, 10.0f);
                f.addView(t, tl);
            } else {
                int is = Ui.dp(a, art != null ? 34.0f : 26.0f);
                FrameLayout.LayoutParams il = new FrameLayout.LayoutParams(is, is, Gravity.CENTER_VERTICAL | Gravity.START);
                il.leftMargin = Ui.dp(a, art != null ? 28.0f : 32.0f);
                f.addView(icon, il);
                TextView t = Ui.oneLine(Ui.text(a, label, 15.0f, Skin.ink()));
                FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL);
                tl.leftMargin = Ui.dp(a, 66.0f);
                tl.rightMargin = Ui.dp(a, 20.0f);
                tl.topMargin = Ui.dp(a, 2.0f);
                f.addView(t, tl);
                TextView n = Ui.text(a, String.format(Locale.US, "Nº %02d", i + 1), 10.0f, Ui.alpha(PipeDraw.RED_INK, 0.85f));
                FrameLayout.LayoutParams nl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
                nl.topMargin = Ui.dp(a, 6.0f);
                nl.rightMargin = Ui.dp(a, 20.0f);
                f.addView(n, nl);
            }
            f.setContentDescription(label);
        }
        a.wireTile(f, tile, i);
        NotificationBadges.attach(f, tile, tob || lounge ? NotificationBadges.TOP_END : NotificationBadges.BOTTOM_START, 0); // LCA-T23
        return f;
    }

    // ---- LORE: a page of pipe craft, one at a time ---------------------------------------------

    static final String[] LORE = {
            "Rest a briar for a day between smokes. The wood needs time to dry, and a rested pipe smokes cooler and sweeter.",
            "The three-layer fill: drop tobacco in loosely to the rim and press it lightly; add more and press a little firmer; top it off and press firm. It should draw like a slightly stiff straw.",
            "The charring light: pass the flame over the whole surface while drawing gently. The tobacco will rise; tamp it level, then give it the true light.",
            "Sip, don't puff. A slow cadence keeps the bowl cool enough to hold in your palm. If it gets too hot to hold, set it down and let it rest.",
            "Tamp lightly and often. The tamper is for settling the ash, not packing the bowl.",
            "Run a pipe cleaner through the stem while the pipe is still warm, and let the moisture go with it.",
            "Never knock a briar against a hard surface to empty it. Tap it into your palm, or loosen the dottle with the pick.",
            "Let a new pipe build its cake slowly. Half bowls at first, then full ones. A cake the thickness of a coin is plenty.",
            "Latakia is cured over smouldering aromatic wood and herbs; a little of it goes a long way in a blend.",
            "Perique is pressed in barrels and fermented in its own juices, and is grown in St. James Parish, Louisiana.",
            "Virginias deepen and sweeten with age. Put a few tins away and open them in a few years.",
            "A meerschaum colours as it is smoked, from white through honey to amber. Handle it with clean hands, or with a glove, while it is warm.",
            "Wooden matches or a soft-flame lighter, held a little above the rim: never let a jet flame scorch the rim of the bowl.",
            "Unscrew nothing while the pipe is warm. Wait until the briar has cooled before removing the stem, or you can crack the shank.",
            "“It is quite a three pipe problem, and I beg that you won't speak to me for fifty minutes.”\n— Sherlock Holmes, The Red-Headed League (Arthur Conan Doyle, 1891)",
    };

    private static final Random RANDOM = new Random();
    private static int lastLore = -1;

    /** Picks a random index in {@code 0..count-1} that differs from {@code previous} (when count > 1). */
    static int nextLore(Random random, int count, int previous) {
        int i;
        do { i = random.nextInt(count); } while (i == previous && count > 1);
        return i;
    }

    /** Shows a random LORE page (never the same one twice running) with ANOTHER / CLOSE. */
    static void lore(final MainActivity a) {
        int i = nextLore(RANDOM, LORE.length, lastLore);
        lastLore = i;
        String title = Skin.faction == Skin.BRIAR ? "BY THE FIRE" : Skin.faction == Skin.LEDGER ? "FROM THE LEDGER" : Skin.faction == Skin.MIDNIGHT ? "AFTER HOURS" : Skin.tob() ? "FROM THE TOBACCONIST" : Skin.lounge() ? "OVERHEARD IN THE LOUNGE" : "FROM THE JOURNAL";
        a.showing(ConsoleDialog.message(a, title, LORE[i], new String[]{"ANOTHER", "CLOSE"}, new ConsoleDialog.OnPick() {
            @Override public void pick(int k) {
                if (k == 0) lore(a);
            }
        }));
    }
}
