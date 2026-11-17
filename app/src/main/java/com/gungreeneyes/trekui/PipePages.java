package com.gungreeneyes.trekui;

import android.content.Intent;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Pages that open from the painted scenes (and from any pipe interface): the pipe register, the
 * blend catalogue, the journal, today's ritual with the session clock, the reference shelves and
 * backup. Everything is stored on the phone (PipeDataStore); nothing is pre-filled or sent anywhere.
 *
 * <p>A page is a full-screen FrameLayout (tagged {@link #TAG}) added over the activity's content
 * view: a header with BACK and the title, a scrolling body, and a footer of the four most-used
 * pages. MainActivity closes it on BACK / HOME and forwards the export/import document results to
 * {@link #onResult}.
 *
 * <p>Responsive: the page column is centred and capped at {@link #MAX_CONTENT_DP} on wide windows
 * (tablets, landscape, desktop) instead of stretching edge to edge, and keeps clear of the system
 * bars and display cutouts on every side. The padding is recomputed whenever the page is laid out,
 * so rotation and multi-window resizes (the activity handles those itself) stay correct.
 */
final class PipePages {
    /** Activity request codes for the export (create document) and import (open document) pickers. */
    static final int REQ_EXPORT = 714, REQ_IMPORT = 715;
    /** View tag of the page root, used to find and close it. */
    static final String TAG = "pipePages";
    /** The five steps of today's ritual; bit i of {@link PipeDataStore#ritualMask()} ticks step i. */
    static final String[] RITUAL = {"Select a blend", "Pack the pipe", "Light & settle", "Read or reflect", "Record notes"};
    /** Widest the page column grows (design dp, zoomed on tablets). */
    static final float MAX_CONTENT_DP = 720f;
    /** Largest import file accepted, in characters (matches PipeDataStore's limit). */
    static final int MAX_IMPORT_CHARS = 4_000_000;
    /** Scrim over the backdrop behind the page. */
    private static final int SCRIM = 0xB8000000;

    private PipePages() {}

    /** The open page, or null. */
    static FrameLayout find(MainActivity a) {
        View root = a.getWindow().getDecorView().findViewWithTag(TAG);
        return root instanceof FrameLayout ? (FrameLayout) root : null;
    }

    /** Closes the open page (if any) and refreshes the painted scene. @return whether a page was open */
    static boolean close(MainActivity a) {
        FrameLayout f = find(a);
        if (f == null) return false;
        ViewGroup g = (ViewGroup) f.getParent();
        if (g != null) g.removeView(f);
        PipeScene.refresh();
        return true;
    }

    /** The page title for a page id; the ritual page is named per scene. */
    static String title(String id) {
        if ("pipes".equals(id)) return "PIPE REGISTER";
        if ("blends".equals(id)) return "TOBACCO & BLENDS";
        if ("journal".equals(id)) return "PRIVATE JOURNAL";
        if ("ritual".equals(id)) return Skin.faction == Skin.BRIAR ? "THE QUIET HOUR" : Skin.faction == Skin.LEDGER ? "TODAY'S RITUAL" : "CEREMONY & SESSION";
        if ("tools".equals(id)) return "INSTRUMENT WORKBENCH";
        if ("care".equals(id)) return "PIPE CARE";
        if ("library".equals(id)) return "REFERENCE LIBRARY";
        if ("backup".equals(id)) return "FILING & RECORDS";
        return "THE STUDY";
    }

    /**
     * Opens (or re-opens, replacing the current one) a page: "pipes", "blends", "journal", "ritual",
     * "backup", or a reference shelf ("tools", "care", "library").
     */
    static void open(final MainActivity a, final String id) {
        FrameLayout old = find(a);
        ViewGroup host = (ViewGroup) a.getWindow().getDecorView().findViewById(android.R.id.content);
        if (old != null) ((ViewGroup) old.getParent()).removeView(old);
        if (host == null) return;
        Palette pal = a.pal;
        FrameLayout root = new FrameLayout(a);
        root.setTag(TAG);
        root.setClickable(true);
        root.setBackground(Skin.backdrop(a));
        View scrim = new View(a);
        scrim.setBackgroundColor(SCRIM);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        final LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ScreenMetrics metrics = ScreenMetrics.of(a);
        fitColumn(a, root, col, metrics.px(metrics.widthDp));
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l == or - ol && b - t == ob - ot) return;
                final int width = r - l;
                // re-pad on the next frame, not in the middle of this layout pass
                v.post(new Runnable() {
                    @Override public void run() { fitColumn(a, root, col, width); }
                });
            }
        });

        // header: back key and title
        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = Ui.button(a, "‹ BACK", pal.c(0), Ui.LEFT, new View.OnClickListener() {
            @Override public void onClick(View v) { close(a); }
        });
        back.setGravity(Gravity.CENTER);
        head.addView(back, new LinearLayout.LayoutParams(Ui.dp(a, 96.0f), Ui.dp(a, 48.0f)));
        TextView t = Ui.oneLine(Ui.bold(a, title(id), 24.0f, pal.c(0)));
        t.setPadding(Ui.dp(a, 12.0f), 0, 0, 0);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1.0f));
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));
        View rule = new View(a);
        rule.setBackground(Skin.rule(a, pal.c(0)));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 4.0f));
        rp.topMargin = Ui.dp(a, 8.0f);
        rp.bottomMargin = Ui.dp(a, 6.0f);
        col.addView(rule, rp);

        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, Ui.dp(a, 4.0f), 0, Ui.dp(a, 12.0f));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1.0f));

        // footer: the four places you go most
        LinearLayout foot = new LinearLayout(a);
        String[] ids = {"pipes", "blends", "journal", "ritual"};
        String[] labels = {"PIPES", "BLENDS", "JOURNAL", "RITUAL"};
        for (int i = 0; i < 4; i++) {
            final String go = ids[i];
            TextView b = Ui.button(a, labels[i], id.equals(go) ? pal.c(0) : Ui.alpha(pal.c(i + 1), 0.4f), i == 0 ? Ui.LEFT : i == 3 ? Ui.RIGHT : Ui.SQUARE, new View.OnClickListener() {
                @Override public void onClick(View v) { open(a, go); }
            });
            b.setGravity(Gravity.CENTER);
            Ui.setTextSp(b, 13.0f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 48.0f), 1.0f);
            if (i > 0) lp.leftMargin = Ui.dp(a, 5.0f);
            foot.addView(b, lp);
        }
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = Ui.dp(a, 8.0f);
        col.addView(foot, fp);

        if ("pipes".equals(id) || "blends".equals(id) || "journal".equals(id)) collection(a, body, id);
        else if ("ritual".equals(id)) ritual(a, body);
        else if ("backup".equals(id)) backup(a, body);
        else reference(a, body, id);
        host.addView(root, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        Ui.tap(root);
    }

    /**
     * Horizontal padding (px) that centres a column of at most {@code maxContent} inside
     * {@code width}, never less than {@code minPadding} on each side.
     */
    static int sidePadding(int width, int minPadding, int maxContent) {
        return Math.max(minPadding, (width - maxContent) / 2);
    }

    /** Pads the page column for the page width, the system bars / cutouts and the content cap. */
    private static void fitColumn(MainActivity a, View root, LinearLayout col, int widthPx) {
        int left = 0, right = 0, top = a.insetTop, bottom = a.insetBottom;
        WindowInsets wi = root.getRootWindowInsets();
        if (wi != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets in = wi.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = in.left;
                right = in.right;
                top = Math.max(top, in.top);
                bottom = Math.max(bottom, in.bottom);
            } else {
                left = legacyInset(wi, true);
                right = legacyInset(wi, false);
            }
        }
        ScreenMetrics m = ScreenMetrics.of(a);
        int minPad = Math.max(Ui.dp(a, 12.0f), m.marginPx());
        int usable = Math.max(0, widthPx - left - right);
        int side = sidePadding(usable, minPad, Ui.dp(a, MAX_CONTENT_DP));
        col.setPadding(left + side, top + Ui.dp(a, 6.0f), right + side, bottom + Ui.dp(a, 8.0f));
    }

    /** Left or right system-window inset before API 30. */
    @SuppressWarnings("deprecation")
    private static int legacyInset(WindowInsets wi, boolean left) {
        return left ? wi.getSystemWindowInsetLeft() : wi.getSystemWindowInsetRight();
    }

    // ---- building blocks -------------------------------------------------------------------------

    /** Adds an outlined card to the body and returns it for content. */
    private static LinearLayout card(MainActivity a, LinearLayout body, int accent) {
        LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(Ui.outline(a, Ui.alpha(a.pal.panel, 0.92f), Ui.alpha(accent, 0.7f), Skin.trayRadius(12.0f), 1.3f));
        c.setPadding(Ui.dp(a, 14.0f), Ui.dp(a, 10.0f), Ui.dp(a, 14.0f), Ui.dp(a, 12.0f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(a, 8.0f);
        body.addView(c, lp);
        return c;
    }

    /** A bold heading line and an optional wrapped detail paragraph inside a card. */
    private static void line(MainActivity a, LinearLayout c, String head, String detail, int color) {
        TextView h = Ui.bold(a, head, 18.0f, color);
        c.addView(h);
        if (detail != null && !detail.isEmpty()) {
            TextView d = Ui.text(a, detail, 15.0f, a.pal.textHi);
            d.setLineSpacing(0, 1.15f);
            d.setPadding(0, Ui.dp(a, 4.0f), 0, 0);
            c.addView(d);
        }
    }

    private static void heading(MainActivity a, LinearLayout body, String s) {
        TextView t = Ui.bold(a, s, 16.0f, a.pal.c(2));
        t.setPadding(Ui.dp(a, 2.0f), Ui.dp(a, 10.0f), 0, Ui.dp(a, 6.0f));
        body.addView(t);
    }

    private static void note(MainActivity a, LinearLayout body, String s) {
        TextView t = Ui.text(a, s, 14.0f, a.pal.textLo);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(Ui.dp(a, 2.0f), Ui.dp(a, 4.0f), Ui.dp(a, 2.0f), Ui.dp(a, 8.0f));
        body.addView(t);
    }

    /** A full-width pill button in the body. */
    private static TextView action(MainActivity a, LinearLayout body, String label, int color, View.OnClickListener l) {
        TextView b = Ui.button(a, label, color, Ui.PILL, l);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 50.0f));
        lp.bottomMargin = Ui.dp(a, 8.0f);
        body.addView(b, lp);
        return b;
    }

    /** The singular noun for a collection, used in button and dialog titles. */
    static String noun(String type) {
        return "pipes".equals(type) ? "PIPE" : "blends".equals(type) ? "BLEND" : "JOURNAL ENTRY";
    }

    // ---- collections ----------------------------------------------------------------------------

    /**
     * A collection page (pipes, blends or journal): ADD, then one card per record, newest first, with
     * SET CURRENT (not for the journal), EDIT and DELETE.
     */
    private static void collection(final MainActivity a, LinearLayout body, final String type) {
        final PipeDataStore st = new PipeDataStore(a);
        Palette pal = a.pal;
        action(a, body, "+ ADD " + noun(type), pal.c(0), new View.OnClickListener() {
            @Override public void onClick(View v) { edit(a, type, null); }
        });
        JSONArray all = st.records(type);
        if (all.length() == 0) {
            note(a, body, "pipes".equals(type) ? "Your register is empty. Add the pipes you own: maker, shape, finish, when you bought it, how it smokes. Mark one as current and it appears on the home scene."
                    : "blends".equals(type) ? "Your catalogue is empty. Add the tobaccos and blends you keep: components, cut, tin date, how much is cellared. Mark one as current and it appears on the home scene."
                    : "Your journal is empty. Each entry remembers the pipe and blend that were current when you wrote it.");
            return;
        }
        String sel = "journal".equals(type) ? "" : st.selected(type);
        for (int i = all.length() - 1; i >= 0; i--) {
            final JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            final String rid = o.optString("id");
            boolean current = rid.equals(sel);
            LinearLayout c = card(a, body, current ? pal.c(0) : pal.c(2));
            String name = o.optString("name", "Untitled");
            String detail = "journal".equals(type) ? o.optString("notes", "") : o.optString("description", "");
            if ("journal".equals(type)) {
                long at = o.optLong("createdAt", o.optLong("updatedAt", 0L));
                String when = at > 0 ? new SimpleDateFormat("EEE d MMM yyyy • h:mm a", Locale.getDefault()).format(new Date(at)) : "";
                detail = journalDetail(when, withLine(st, o), detail);
            }
            line(a, c, (current ? "◆  " : "") + name, detail, current ? pal.c(0) : pal.textHi);
            String notes = o.optString("notes", "");
            if (!"journal".equals(type) && !notes.isEmpty()) {
                TextView n = Ui.text(a, notes, 13.0f, pal.textLo);
                n.setPadding(0, Ui.dp(a, 4.0f), 0, 0);
                c.addView(n);
            }
            LinearLayout row = new LinearLayout(a);
            row.setPadding(0, Ui.dp(a, 10.0f), 0, 0);
            if (!"journal".equals(type)) {
                TextView setb = Ui.button(a, current ? "CURRENT" : "SET CURRENT", current ? pal.c(0) : Ui.alpha(pal.c(0), 0.4f), Ui.LEFT, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        st.select(type, rid);
                        open(a, type);
                    }
                });
                setb.setGravity(Gravity.CENTER);
                Ui.setTextSp(setb, 13.0f);
                row.addView(setb, new LinearLayout.LayoutParams(0, Ui.dp(a, 44.0f), 1.0f));
            }
            TextView eb = Ui.button(a, "EDIT", pal.c(3), "journal".equals(type) ? Ui.LEFT : Ui.SQUARE, new View.OnClickListener() {
                @Override public void onClick(View v) { edit(a, type, o); }
            });
            eb.setGravity(Gravity.CENTER);
            Ui.setTextSp(eb, 13.0f);
            LinearLayout.LayoutParams el = new LinearLayout.LayoutParams(0, Ui.dp(a, 44.0f), 1.0f);
            if (row.getChildCount() > 0) el.leftMargin = Ui.dp(a, 5.0f);
            row.addView(eb, el);
            TextView db = Ui.button(a, "DELETE", Ui.alpha(pal.alert, 0.45f), Ui.RIGHT, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    a.showing(ConsoleDialog.message(a, "DELETE " + noun(type) + "?", "“" + o.optString("name", "Untitled") + "” will be removed. This can't be undone.", new String[]{"DELETE", "KEEP"}, new ConsoleDialog.OnPick() {
                        @Override public void pick(int k) {
                            if (k != 0) return;
                            st.delete(type, rid);
                            if (!"journal".equals(type) && rid.equals(st.selected(type))) st.select(type, "");
                            open(a, type);
                        }
                    }));
                }
            });
            db.setGravity(Gravity.CENTER);
            Ui.setTextSp(db, 13.0f);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, Ui.dp(a, 44.0f), 1.0f);
            dl.leftMargin = Ui.dp(a, 5.0f);
            row.addView(db, dl);
            c.addView(row);
        }
    }

    /** "Pipe with Blend" for the pipe and blend a journal entry was written with. */
    private static String withLine(PipeDataStore st, JSONObject entry) {
        JSONObject pipe = st.get("pipes", entry.optString("pipeId", ""));
        JSONObject blend = st.get("blends", entry.optString("blendId", ""));
        return withLine(pipe == null ? null : pipe.optString("name", ""), blend == null ? null : blend.optString("name", ""));
    }

    /**
     * Joins a pipe and a blend name: "Pipe with Blend", or whichever one exists (null = no record).
     * An empty pipe name falls back to the blend name alone.
     */
    static String withLine(String pipeName, String blendName) {
        String s = pipeName == null ? "" : pipeName;
        if (blendName != null) s = s.isEmpty() ? blendName : s + " with " + blendName;
        return s;
    }

    /** The journal card's detail: "when  •  with" on its own line (each part optional), then the notes. */
    static String journalDetail(String when, String with, String notes) {
        if (when.isEmpty()) return notes;
        return when + (with.isEmpty() ? "" : "  •  " + with) + "\n" + notes;
    }

    /** Adds a themed text field (single line with word caps, or multi-line with sentence caps). */
    private static EditText field(MainActivity a, LinearLayout body, String hint, String value, boolean multi) {
        EditText e = new EditText(a);
        e.setText(value);
        e.setHint(hint);
        e.setTextColor(a.pal.textHi);
        e.setHintTextColor(Ui.alpha(a.pal.textLo, 0.6f));
        e.setTypeface(Ui.font(a, false));
        Ui.setTextSp(e, 17.0f);
        e.setBackground(Ui.outline(a, a.pal.panel, a.pal.c(2), 12.0f, 1.5f));
        e.setPadding(Ui.dp(a, 14.0f), Ui.dp(a, 10.0f), Ui.dp(a, 14.0f), Ui.dp(a, 10.0f));
        if (multi) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(3);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(a, 10.0f);
        body.addView(e, lp);
        return e;
    }

    /** Add or edit a pipe, a blend or a journal entry. */
    static void edit(final MainActivity a, final String type, final JSONObject previous) {
        final ConsoleDialog.Built built = ConsoleDialog.base(a, (previous == null ? "NEW " : "EDIT ") + noun(type));
        boolean j = "journal".equals(type);
        final EditText name = field(a, built.body, j ? "Title, or leave blank for the date" : "pipes".equals(type) ? "Name (e.g. Peterson System 317)" : "Name (e.g. London Mixture)",
                previous == null ? "" : previous.optString("name"), false);
        final EditText info = field(a, built.body, j ? "How it smoked, what you noticed" : "pipes".equals(type) ? "Maker, shape, finish, stem, filter" : "Components, cut, tin date, quantity",
                previous == null ? "" : previous.optString(j ? "notes" : "description"), true);
        final EditText notes = j ? null : field(a, built.body, "Private notes", previous == null ? "" : previous.optString("notes"), true);
        ConsoleDialog.addOption(built, ConsoleDialog.option(a, "SAVE", a.pal.c(0), new View.OnClickListener() {
            @Override public void onClick(View v) {
                String n = name.getText().toString().trim();
                boolean jj = "journal".equals(type);
                if (n.isEmpty() && jj) n = new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(new Date());
                if (n.isEmpty()) {
                    name.setError("Give it a name");
                    return;
                }
                try {
                    PipeDataStore st = new PipeDataStore(a);
                    JSONObject r = previous == null ? new JSONObject() : previous;
                    r.put("name", n);
                    r.put(jj ? "notes" : "description", info.getText().toString().trim());
                    if (!jj) r.put("notes", notes.getText().toString().trim());
                    if (jj && previous == null) {
                        r.put("createdAt", System.currentTimeMillis());
                        r.put("pipeId", st.selected("pipes"));
                        r.put("blendId", st.selected("blends"));
                    }
                    String rid = st.upsert(type, r);
                    if (!jj && st.selected(type).isEmpty()) st.select(type, rid);
                } catch (Exception e) {
                    Toast.makeText(a, "COULD NOT SAVE", Toast.LENGTH_SHORT).show();
                    return;
                }
                built.dialog.dismiss();
                Sfx.play(a, Sfx.CONFIRM);
                if (find(a) != null) open(a, type);
                PipeScene.refresh();
            }
        }));
        ConsoleDialog.addOption(built, ConsoleDialog.option(a, "CANCEL", a.pal.c(3), new View.OnClickListener() {
            @Override public void onClick(View v) { built.dialog.dismiss(); }
        }));
        built.dialog.show();
        a.showing(built.dialog);
    }

    // ---- ritual and session clock ---------------------------------------------------------------

    /** Toggles ritual step {@code bit} in a checklist mask. */
    static int toggleStep(int mask, int bit) {
        return mask ^ (1 << bit);
    }

    /** Whether ritual step {@code bit} is ticked in a checklist mask. */
    static boolean stepDone(int mask, int bit) {
        return (mask & (1 << bit)) != 0;
    }

    /** The ritual page: the five-step checklist and the session clock. */
    private static void ritual(final MainActivity a, LinearLayout body) {
        final PipeDataStore st = new PipeDataStore(a);
        Palette pal = a.pal;
        heading(a, body, "TODAY'S RITUAL");
        int mask = st.ritualMask();
        for (int i = 0; i < RITUAL.length; i++) {
            final int bit = i;
            boolean on = stepDone(mask, i);
            TextView b = Ui.button(a, (on ? "✓  " : "    ") + RITUAL[i].toUpperCase(Locale.getDefault()), on ? pal.c(i) : Ui.alpha(pal.c(i), 0.4f), Ui.PILL, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    st.ritualMask(toggleStep(st.ritualMask(), bit));
                    open(a, "ritual");
                }
            });
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setPadding(Ui.dp(a, 20.0f), 0, Ui.dp(a, 14.0f), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 50.0f));
            lp.bottomMargin = Ui.dp(a, 6.0f);
            body.addView(b, lp);
        }
        action(a, body, "CLEAR THE CHECKLIST", Ui.alpha(pal.c(3), 0.45f), new View.OnClickListener() {
            @Override public void onClick(View v) { st.ritualMask(0); open(a, "ritual"); }
        });
        heading(a, body, "SESSION CLOCK");
        LinearLayout c = card(a, body, pal.c(0));
        final TextView clock = Ui.bold(a, PipeScene.timerText(a.prefs), 44.0f, pal.textHi);
        clock.setGravity(Gravity.CENTER);
        c.addView(clock, new LinearLayout.LayoutParams(-1, -2));
        clock.postDelayed(new Runnable() {
            @Override public void run() {
                if (clock.getWindowToken() == null) return;
                clock.setText(PipeScene.timerText(a.prefs));
                clock.postDelayed(this, 500L);
            }
        }, 500L);
        action(a, body, PipeScene.timerRunning(a.prefs) ? "PAUSE" : "START", pal.c(0), new View.OnClickListener() {
            @Override public void onClick(View v) { PipeScene.timerToggle(a.prefs); open(a, "ritual"); }
        });
        action(a, body, "RESET", Ui.alpha(pal.c(3), 0.45f), new View.OnClickListener() {
            @Override public void onClick(View v) { PipeScene.timerReset(a.prefs); open(a, "ritual"); }
        });
        action(a, body, "WRITE IN THE JOURNAL", pal.c(2), new View.OnClickListener() {
            @Override public void onClick(View v) { edit(a, "journal", null); }
        });
        note(a, body, "The checklist and clock are yours to use as you like. Nothing here schedules, counts or reminds.");
    }

    // ---- reference shelves ----------------------------------------------------------------------

    /** A reference shelf: "tools", "care", or (anything else) the library of leaf, wood and shapes. */
    private static void reference(final MainActivity a, LinearLayout body, String kind) {
        String[][] items;
        if ("tools".equals(kind)) {
            items = new String[][]{
                    {"Tamper", "Settles the ash and the tobacco as you go. Light pressure, often. It's a smoothing tool, not a press."},
                    {"Pick and spoon", "The other two blades of a pipe tool: the pick loosens a tight draw or the dottle, the spoon lifts it out."},
                    {"Pipe cleaners", "Soft for drying the stem and shank after a smoke, bristled for scrubbing. Use them while the pipe is warm."},
                    {"Reamer", "Trims the carbon cake back to the thickness of a coin, so it never pressures the briar."},
                    {"Pipe stand and rack", "Rest a pipe bowl-down or upright to let it dry, and rotate so each briar gets a day or more off."},
                    {"Tins, jars and pouches", "Sealed glass keeps cellared tobacco; a pouch carries the day's bowl. Label the jar date."}};
        } else if ("care".equals(kind)) {
            items = new String[][]{
                    {"Let it cool", "Never remove the stem from a warm pipe. The shank can crack and the tenon can loosen."},
                    {"After each smoke", "Empty the dottle, run a cleaner through the stem, and let the pipe rest a day before refilling."},
                    {"The cake", "A thin, even layer of carbon protects the bowl. Ream it back when it gets thicker than a coin."},
                    {"The stem", "Vulcanite oxidises in light and warmth; a little polish keeps it black. Acrylic just needs wiping."},
                    {"Briar finish", "A soft cloth after smoking. A light wax a few times a year keeps the grain bright."},
                    {"Meerschaum", "Hold it with clean hands or a cloth while warm; it colours from white to honey to amber as you smoke it."}};
        } else {
            items = new String[][]{
                    {"Virginia", "Flue-cured, bright to dark. Sweet and grassy when young, deeper and fruitier with age; the base of most English and many aromatic blends."},
                    {"Burley", "Air-cured, low in sugar, nutty and full. Takes casings well and burns steadily."},
                    {"Oriental / Turkish", "Small-leaf, sun-cured tobaccos from the eastern Mediterranean: spicy, floral, a little sour."},
                    {"Latakia", "Oriental leaf cured over smouldering aromatic woods and herbs. The smoky heart of an English blend."},
                    {"Perique", "Pressed and fermented in barrels in St. James Parish, Louisiana. A few percent adds pepper and dark fruit."},
                    {"Cavendish", "A process, not a leaf: pressed and steamed or heat-treated, often with a topping. Mild and sweet."},
                    {"Briar", "The burl of the tree heath (Erica arborea). Dense, heat-resistant, and cut into ebauchons that dry for years."},
                    {"Meerschaum", "Sepiolite, carved soft and fired hard. Light, cool and slow to colour."},
                    {"Morta", "Bog oak preserved for thousands of years. Light and dark-grained; it smokes without a break-in."},
                    {"Shapes", "Billiard, Dublin, Apple, Bulldog, Prince, Pot, Churchwarden, Calabash. The bowl shape changes how a blend smokes."}};
        }
        for (String[] it : items) {
            LinearLayout c = card(a, body, a.pal.c(2));
            line(a, c, it[0].toUpperCase(Locale.getDefault()), it[1], a.pal.c(0));
        }
        action(a, body, "A PAGE OF LORE", a.pal.c(1), new View.OnClickListener() {
            @Override public void onClick(View v) { PipeHome.lore(a); }
        });
    }

    // ---- backup ---------------------------------------------------------------------------------

    /** File name suggested for an export made on {@code date}. */
    static String exportFileName(Date date) {
        return "pipe_collection_" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date) + ".json";
    }

    /** The backup page: counts, export to a document, import-and-merge from a document. */
    private static void backup(final MainActivity a, LinearLayout body) {
        PipeDataStore st = new PipeDataStore(a);
        LinearLayout c = card(a, body, a.pal.c(0));
        line(a, c, "ON THIS PHONE", st.count("pipes") + " pipes  •  " + st.count("blends") + " blends  •  " + st.count("journal") + " journal entries", a.pal.c(0));
        action(a, body, "EXPORT TO A FILE", a.pal.c(0), new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                i.putExtra(Intent.EXTRA_TITLE, exportFileName(new Date()));
                try { a.startActivityForResult(i, REQ_EXPORT); } catch (Exception e) { Toast.makeText(a, "NO FILE PICKER", Toast.LENGTH_SHORT).show(); }
            }
        });
        action(a, body, "IMPORT AND MERGE A FILE", a.pal.c(2), new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("application/json");
                try { a.startActivityForResult(i, REQ_IMPORT); } catch (Exception e) { Toast.makeText(a, "NO FILE PICKER", Toast.LENGTH_SHORT).show(); }
            }
        });
        note(a, body, "Your pipes, blends, journal and ritual are kept by the launcher on this phone. Export writes them to a file you choose; import merges a file back in by record, keeping anything that's only on the phone.");
    }

    /**
     * Called from MainActivity.onActivityResult: writes the export or merges the import.
     *
     * @return true if the request was one of ours (handled, even if cancelled)
     */
    static boolean onResult(MainActivity a, int req, int res, Intent data) {
        if (req != REQ_EXPORT && req != REQ_IMPORT) return false;
        if (res != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        PipeDataStore st = new PipeDataStore(a);
        try {
            if (req == REQ_EXPORT) {
                OutputStream out = a.getContentResolver().openOutputStream(uri);
                if (out == null) throw new IOException("The file could not be opened for writing");
                try {
                    out.write(st.exportAll().toString(2).getBytes(StandardCharsets.UTF_8));
                } finally {
                    out.close();
                }
                Toast.makeText(a, "COLLECTION EXPORTED", Toast.LENGTH_SHORT).show();
            } else {
                StringBuilder sb = new StringBuilder();
                InputStream in = a.getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("The file could not be opened");
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                try {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = r.read(buf)) >= 0) {
                        sb.append(buf, 0, n);
                        if (sb.length() > MAX_IMPORT_CHARS) throw new IOException("The file is larger than 4 MB");
                    }
                } finally {
                    r.close();
                }
                int n = st.importJson(sb.toString());
                Toast.makeText(a, "MERGED " + n + " RECORDS", Toast.LENGTH_SHORT).show();
                PipeScene.refresh();
                if (find(a) != null) open(a, "backup");
            }
            Sfx.play(a, Sfx.CONFIRM);
        } catch (Exception e) {
            a.showing(ConsoleDialog.message(a, "FILE NOT READ", String.valueOf(e.getMessage()), new String[]{"OK"}, null));
        }
        return true;
    }
}
