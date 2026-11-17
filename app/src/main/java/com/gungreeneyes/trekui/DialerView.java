package com.gungreeneyes.trekui;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * console dialer: keypad (with contact matching), recent calls and contacts. Calls are placed with
 * ACTION_CALL when "make phone calls" is granted (otherwise handed to the phone app pre-filled);
 * the in-call screen stays the system's. Emergency numbers always go through the system phone app.
 *
 * <p>Responsive: on short windows (landscape phones, split screen) the keypad sits beside the
 * number and suggestions instead of under them, and the sidebar tabs share the height instead of
 * using fixed heights. MainActivity rebuilds the dialer on configuration changes.
 *
 * <p>Number matching is pure ({@link #t9Match}, {@link #t9Digit}, {@link #digitsOnly},
 * {@link #cleanDialString}) and unit-tested in {@code DialerViewTest}.
 */
class DialerView extends FrameLayout {
    static final int TAB_KEYPAD = 0, TAB_RECENTS = 1, TAB_CONTACTS = 2;
    /** Letters on each phone key, indexed by digit. */
    private static final String[] LETTERS = {"", "", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ"};
    /** Longest number the keypad accepts. */
    static final int MAX_DIGITS = 40;
    /** Suggestions under the number, and digits needed before number / name (T9) matching. */
    private static final int MAX_SUGGESTIONS = 3, MIN_NUMBER_MATCH = 3, MIN_T9_MATCH = 2, FULL_NUMBER = 7;
    /** Recent calls listed. */
    private static final int MAX_RECENTS = 100;
    /** Windows shorter than this (dp) get the side-by-side keypad and flexible sidebar. */
    private static final float SHORT_WINDOW_DP = 560;
    /** Sidebar width, sidebar key height and keypad row height, in dp at the design scale. */
    private static final float SIDEBAR_DP = 76, SIDE_KEY_DP = 62, KEY_ROW_DP = 60;
    /** Fallback list of emergency numbers when TelephonyManager can't say (before API 29). */
    private static final String[] EMERGENCY = {"911", "112", "999", "000", "110", "119"};

    private final ScreenMetrics metrics;
    /** True on short windows: keypad beside the number, flexible sidebar. */
    private final boolean shortWindow;

    private final MainActivity a;
    private final Palette pal;
    private final ConsoleFrame frame;
    private final FrameLayout content;
    private final LinearLayout side;
    private final TextView titleView, footer;
    private int tab = TAB_KEYPAD;
    private boolean open;
    private final StringBuilder digits = new StringBuilder();
    private TextView numberView, matchView;
    private LinearLayout suggest;
    private String pendingCall;

    static final class Contact {
        long id;
        String name, lower;
        boolean starred;
        final List<String[]> numbers = new ArrayList<>(); // {number, label}
    }

    static final class Recent {
        String number, name;
        int type;
        long date, duration;
    }

    private List<Contact> contacts;
    private List<Recent> recents;
    /** Contacts and call-log queries run here, never on the main thread. */
    private static final java.util.concurrent.ExecutorService IO = Http.newExecutor("trekui-dialer");
    /** Bumped whenever cached lists are dropped; background loads from an older one are discarded. */
    private int loadGeneration;
    private boolean loadingContacts, loadingRecents;

    DialerView(MainActivity act) {
        super(act);
        a = act;
        pal = Prefs.get(act).palette();
        metrics = ScreenMetrics.of(act);
        shortWindow = metrics.heightDp < SHORT_WINDOW_DP;
        setBackgroundColor(Ui.alpha(Skin.bg(), 0.97f));
        setClickable(true);

        frame = new ConsoleFrame(act);
        frame.sidebarW = Math.max(Ui.dp(act, SIDEBAR_DP), metrics.scaledPx(SIDEBAR_DP));
        frame.colorTop = pal.c(2);
        frame.colorBot = pal.c(0);
        frame.topBlocks = new int[]{pal.c(4)};
        frame.botBlocks = new int[]{pal.c(1), pal.c(3)};
        titleView = Ui.bold(act, "COMMUNICATIONS", 28, pal.c(0));
        titleView.setPadding(Ui.dp(act, Ui.XS), 0, Ui.dp(act, Ui.HAIR), 0);
        frame.setTitle(titleView);
        footer = Ui.text(act, "SUBSPACE CHANNEL READY", 13, pal.c(1));
        footer.setPadding(Ui.dp(act, Ui.XS), 0, Ui.dp(act, Ui.HAIR), 0);
        frame.setFooter(footer);

        side = new LinearLayout(act);
        side.setOrientation(LinearLayout.VERTICAL);
        frame.setSidebar(side);
        content = new FrameLayout(act);
        frame.setContent(content);
        addView(frame, new LayoutParams(-1, -1));
    }

    /** Keeps the dialer clear of the system bars and the keyboard (insets in px). */
    void setInsets(int top, int bottom, int ime) {
        setInsets(0, top, 0, bottom, ime);
    }

    /** Keeps the dialer clear of system bars, display cutouts and the keyboard on every edge (px). */
    void setInsets(int left, int top, int right, int bottom, int ime) {
        int g = metrics.isTwoPane() ? metrics.marginPx() : Ui.dp(getContext(), 8);
        setPadding(left + g, top + Ui.dp(getContext(), Ui.XS), right + g,
                Math.max(bottom, ime + bottom) + Ui.dp(getContext(), Ui.GUTTER));
    }

    boolean isOpen() { return open; }

    /** Shows the dialer on the keypad (sliding up when animated); contacts and recents reload. */
    void open(boolean animate) {
        if (!open) {
            open = true;
            Sfx.play(getContext(), Sfx.COMM);
            setVisibility(VISIBLE);
            if (animate) {
                setAlpha(0f);
                setTranslationY(Ui.dp(getContext(), 40));
                animate().alpha(1f).translationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator(1.5f)).withEndAction(null).start();
                frame.reveal();
            } else {
                setAlpha(1f);
                setTranslationY(0);
            }
        }
        dropCaches();
        show(TAB_KEYPAD);
    }

    /** Forgets loaded contacts and recents; loads still running are ignored when they finish. */
    private void dropCaches() {
        contacts = null;
        recents = null;
        loadGeneration++;
        loadingContacts = loadingRecents = false;
    }

    /** Loads contacts in the background (once at a time), then runs {@code then} if still current and attached. */
    private void loadContactsAsync(final Runnable then) {
        if (loadingContacts) return;
        loadingContacts = true;
        final int gen = loadGeneration;
        final android.content.ContentResolver cr = getContext().getApplicationContext().getContentResolver();
        final android.content.res.Resources res = getResources();
        IO.execute(new Runnable() {
            @Override public void run() {
                final List<Contact> list = loadContacts(cr, res);
                post(new Runnable() {
                    @Override public void run() {
                        if (gen != loadGeneration) return;
                        loadingContacts = false;
                        if (!isAttachedToWindow()) return;
                        contacts = list;
                        then.run();
                    }
                });
            }
        });
    }

    /** Loads recent calls in the background, then runs {@code then} if still current and attached. */
    private void loadRecentsAsync(final Runnable then) {
        if (loadingRecents) return;
        loadingRecents = true;
        final int gen = loadGeneration;
        final android.content.ContentResolver cr = getContext().getApplicationContext().getContentResolver();
        IO.execute(new Runnable() {
            @Override public void run() {
                final List<Recent> list = loadRecents(cr);
                post(new Runnable() {
                    @Override public void run() {
                        if (gen != loadGeneration) return;
                        loadingRecents = false;
                        if (!isAttachedToWindow()) return;
                        recents = list;
                        then.run();
                    }
                });
            }
        });
    }

    /** Hides the dialer and the keyboard. */
    void close(boolean animate) {
        if (!open) return;
        open = false;
        hideIme();
        Sfx.play(getContext(), Sfx.CHANNEL_CLOSE);
        if (animate && Ui.animate(getContext())) {
            animate().alpha(0f).translationY(Ui.dp(getContext(), 30)).setDuration(180).withEndAction(new Runnable() {
                @Override public void run() { if (!open) setVisibility(GONE); }
            }).start();
        } else {
            animate().cancel();
            setVisibility(GONE);
        }
    }

    /** Back: clear the number first, then close. */
    void back() {
        if (tab == TAB_KEYPAD && digits.length() > 0) { digits.setLength(0); updateNumber(); return; }
        if (tab != TAB_KEYPAD) { show(TAB_KEYPAD); return; }
        close(true);
    }

    private void hideIme() {
        android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    // ---- sidebar / tabs ----------------------------------------------------------------------------

    /** Sidebar: the three tabs, PHONE APP, and a filler block. */
    private void buildSide() {
        side.removeAllViews();
        String[] names = {"KEYPAD", "RECENTS", "CONTACTS"};
        int[] cols = {pal.c(0), pal.c(1), pal.c(3)};
        for (int i = 0; i < names.length; i++) {
            final int t = i;
            boolean sel = i == tab;
            TextView b = Ui.button(getContext(), names[i], sel ? cols[i] : Ui.darken(cols[i], 0.45f), Ui.SQUARE, new OnClickListener() {
                @Override public void onClick(View v) { if (t != tab) { Sfx.play(getContext(), Sfx.PAGE); show(t); } }
            });
            b.setBackground(Ui.touch(Ui.shape(getContext(), sel ? cols[i] : Ui.darken(cols[i], 0.45f), Ui.SQUARE, 0)));
            b.setTextSize((14) * Skin.textScale());
            b.setPadding(Ui.dp(getContext(), Ui.XS), 0, Ui.dp(getContext(), Ui.S), Ui.dp(getContext(), Ui.XS));
            LinearLayout.LayoutParams lp = sideKeyParams();
            if (i > 0) lp.topMargin = Ui.dp(getContext(), Ui.GUTTER);
            side.addView(b, lp);
        }
        TextView phone = Ui.button(getContext(), "PHONE APP", pal.c(2), Ui.SQUARE, new OnClickListener() {
            @Override public void onClick(View v) { openPhoneApp(); }
        });
        phone.setBackground(Ui.touch(Ui.shape(getContext(), pal.c(2), Ui.SQUARE, 0)));
        phone.setTextSize((13) * Skin.textScale());
        phone.setPadding(Ui.dp(getContext(), Ui.XS), 0, Ui.dp(getContext(), Ui.S), Ui.dp(getContext(), Ui.XS));
        LinearLayout.LayoutParams pl = sideKeyParams();
        pl.topMargin = Ui.dp(getContext(), Ui.GUTTER);
        side.addView(phone, pl);
        View filler = new View(getContext());
        filler.setBackground(Ui.shape(getContext(), pal.c(4), Ui.SQUARE, 0));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, 0, shortWindow ? 0.3f : 1);
        fl.topMargin = Ui.dp(getContext(), Ui.GUTTER);
        side.addView(filler, fl);
    }

    /** Sidebar keys: fixed height normally; sharing the height on short windows so all four fit. */
    private LinearLayout.LayoutParams sideKeyParams() {
        return shortWindow ? new LinearLayout.LayoutParams(-1, 0, 1)
                : new LinearLayout.LayoutParams(-1, Ui.dp(getContext(), SIDE_KEY_DP));
    }

    /** Switches tab: KEYPAD, RECENTS or CONTACTS. */
    void show(int t) {
        tab = t;
        buildSide();
        content.removeAllViews();
        hideIme();
        if (t == TAB_KEYPAD) buildKeypad();
        else if (t == TAB_RECENTS) buildRecents();
        else buildContacts();
    }

    // ---- keypad ----------------------------------------------------------------------------------

    /**
     * Number display, suggestions, the 12-key pad and CALL. On short windows the pad is a second
     * column beside the number, its rows sharing the height.
     */
    private void buildKeypad() {
        Context c = getContext();
        footer.setText("SUBSPACE CHANNEL READY");
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout pad = col;
        if (shortWindow) {
            pad = new LinearLayout(c);
            pad.setOrientation(LinearLayout.VERTICAL);
        }

        LinearLayout numRow = new LinearLayout(c);
        numRow.setGravity(Gravity.CENTER_VERTICAL);
        numberView = Ui.oneLine(Ui.bold(c, "", 36, pal.textHi));
        numberView.setGravity(Gravity.CENTER);
        numberView.setEllipsize(android.text.TextUtils.TruncateAt.START);
        numberView.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) { Ui.longPress(v); pasteNumber(); return true; }
        });
        numRow.addView(numberView, new LinearLayout.LayoutParams(0, Ui.dp(c, 56), 1));
        TextView del = Ui.button(c, "DEL", pal.c(3), Ui.PILL, null);
        del.setGravity(Gravity.CENTER);
        del.setTextSize((15) * Skin.textScale());
        del.setOnClickListener(Ui.feedbackSoft(new OnClickListener() {
            @Override public void onClick(View v) { if (digits.length() > 0) { digits.setLength(digits.length() - 1); updateNumber(); } }
        }));
        del.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) { Ui.longPress(v); digits.setLength(0); updateNumber(); return true; }
        });
        numRow.addView(del, new LinearLayout.LayoutParams(Ui.dp(c, 70), Ui.dp(c, 44)));
        col.addView(numRow);
        matchView = Ui.oneLine(Ui.text(c, "", 14, pal.c(1)));
        matchView.setGravity(Gravity.CENTER);
        col.addView(matchView, new LinearLayout.LayoutParams(-1, -2));
        suggest = new LinearLayout(c);
        suggest.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, 0, 1);
        sl.topMargin = Ui.dp(c, Ui.XS);
        col.addView(suggest, sl);

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"};
        LinearLayout row = null;
        for (int i = 0; i < keys.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(c);
                LinearLayout.LayoutParams rl = shortWindow ? new LinearLayout.LayoutParams(-1, 0, 1)
                        : new LinearLayout.LayoutParams(-1, Ui.dp(c, KEY_ROW_DP));
                if (i > 0 || !shortWindow) rl.topMargin = Ui.dp(c, Ui.GUTTER);
                pad.addView(row, rl);
            }
            final String k = keys[i];
            int color = pal.c(new int[]{0, 1, 2, 3, 4, 5}[(i / 3 + i % 3) % 6]);
            int kind = i % 3 == 0 ? Ui.LEFT : i % 3 == 2 ? Ui.RIGHT : Ui.SQUARE;
            TextView key = Ui.button(c, "", color, kind, null);
            String sub = k.equals("0") ? "+" : (Character.isDigit(k.charAt(0)) ? LETTERS[k.charAt(0) - '0'] : "");
            key.setText(sub.isEmpty() ? k : k + "  " + sub);
            key.setTextSize((22) * Skin.textScale());
            key.setGravity(Gravity.CENTER);
            key.setContentDescription(k);
            key.setOnClickListener(Ui.feedback(new OnClickListener() {
                @Override public void onClick(View v) { if (digits.length() < MAX_DIGITS) { digits.append(k); updateNumber(); } }
            }));
            if (k.equals("0")) key.setOnLongClickListener(new OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    Ui.longPress(v);
                    if (digits.length() < MAX_DIGITS) { digits.append('+'); updateNumber(); }
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            if (i % 3 != 0) lp.leftMargin = Ui.dp(c, Ui.GUTTER);
            row.addView(key, lp);
        }
        TextView call = Ui.button(c, "CALL", pal.c(0), Ui.PILL, null);
        call.setGravity(Gravity.CENTER);
        call.setTextSize((22) * Skin.textScale());
        call.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                if (digits.length() == 0) {
                    // Like most dialers: CALL on an empty pad recalls the last dialled number.
                    String last = lastDialled();
                    if (last != null) { digits.append(last); updateNumber(); Ui.tap(v); }
                    else Sfx.play(getContext(), Sfx.DENY);
                    return;
                }
                call(digits.toString());
            }
        });
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, Ui.dp(c, KEY_ROW_DP));
        cl.topMargin = Ui.dp(c, Ui.S);
        col.addView(call, cl);
        if (shortWindow) {
            LinearLayout panes = new LinearLayout(c);
            panes.setOrientation(LinearLayout.HORIZONTAL);
            panes.addView(col, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, -1, 1);
            pp.leftMargin = metrics.gutterPx() * 2;
            panes.addView(pad, pp);
            content.addView(panes, new LayoutParams(-1, -1));
        } else {
            content.addView(col, new LayoutParams(-1, -1));
        }
        updateNumber();
    }

    /** Long-press on the number: paste a number from the clipboard (dialable characters only). */
    private void pasteNumber() {
        ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) return;
        CharSequence t = cm.getPrimaryClip().getItemAt(0).coerceToText(getContext());
        String clean = t == null ? "" : cleanDialString(t.toString());
        if (clean.isEmpty()) return;
        digits.setLength(0);
        digits.append(clean);
        updateNumber();
    }

    /** Shows the formatted number and up to three matching contacts (by number or T9 name). */
    private void updateNumber() {
        if (numberView == null) return;
        String d = digits.toString();
        String f = d;
        try {
            String iso = Locale.getDefault().getCountry();
            String formatted = PhoneNumberUtils.formatNumber(d, iso.isEmpty() ? "US" : iso);
            if (formatted != null) f = formatted;
        } catch (Exception ignored) {
        }
        numberView.setText(d.isEmpty() ? "" : f);
        numberView.setTextSize((d.length() > 14 ? 26 : 36) * Skin.textScale());
        suggest.removeAllViews();
        matchView.setText(d.isEmpty() ? "ENTER NUMBER OR NAME (T9)" : "");
        if (d.isEmpty() || !granted(Manifest.permission.READ_CONTACTS)) return;
        if (contacts == null) {
            loadContactsAsync(new Runnable() { @Override public void run() { if (open && tab == TAB_KEYPAD) updateNumber(); } });
            return;
        }
        int shown = 0;
        String digitsOnly = digitsOnly(d);
        for (Contact ct : contacts) {
            String[] hit = t9Match(ct, d, digitsOnly);
            if (hit == null) continue;
            if (shown == 0 && digitsOnly(hit[0]).endsWith(digitsOnly) && digitsOnly.length() >= FULL_NUMBER) matchView.setText(ct.name.toUpperCase(Locale.getDefault()));
            suggest.addView(contactRow(ct, hit[0], hit[1], shown));
            if (++shown >= MAX_SUGGESTIONS) break;
        }
    }

    /** Only the digits 0-9 of a string. */
    static String digitsOnly(String s) {
        return s == null ? "" : s.replaceAll("[^0-9]", "");
    }

    /** A pasted or edited number reduced to dialable characters (digits, +, *, #), at most {@link #MAX_DIGITS}. */
    static String cleanDialString(String s) {
        String c = s == null ? "" : s.replaceAll("[^0-9+*#]", "");
        return c.length() > MAX_DIGITS ? c.substring(0, MAX_DIGITS) : c;
    }

    /** Matches dialled digits against the contact's number or its name on the phone keypad (T9). */
    static String[] t9Match(Contact ct, String typed, String digitsOnly) {
        if (ct.numbers.isEmpty()) return null;
        for (String[] n : ct.numbers) {
            String nd = digitsOnly(n[0]);
            if (digitsOnly.length() >= MIN_NUMBER_MATCH && nd.contains(digitsOnly)) return n;
        }
        if (digitsOnly.length() >= MIN_T9_MATCH && digitsOnly.equals(typed) && ct.lower != null) {
            for (String word : ct.lower.split("[\\s\\-]+")) {
                if (word.isEmpty()) continue;
                StringBuilder t9 = new StringBuilder();
                for (char ch : word.toCharArray()) t9.append(t9Digit(ch));
                if (t9.toString().startsWith(digitsOnly)) return ct.numbers.get(0);
            }
        }
        return null;
    }

    /** The phone key for a letter (2-9), the digit itself, or '1' for anything else. */
    static char t9Digit(char ch) {
        ch = Character.toUpperCase(ch);
        for (int d = 2; d <= 9; d++) if (LETTERS[d].indexOf(ch) >= 0) return (char) ('0' + d);
        return Character.isDigit(ch) ? ch : '1';
    }

    // ---- recents ---------------------------------------------------------------------------------

    /** Recent calls (READ_CALL_LOG), or the permission prompt. */
    private void buildRecents() {
        footer.setText("COMMUNICATIONS LOG");
        if (!BuildConfig.CALL_LOG_ENABLED) {
            // Play build: no READ_CALL_LOG (allowed only for default phone apps); the system phone
            // app shows the history instead.
            callHistoryHandOff();
            return;
        }
        if (!granted(Manifest.permission.READ_CALL_LOG)) {
            permissionPrompt("Allow TrekUI to read your call history to show recent calls here. Nothing leaves your phone.",
                    Manifest.permission.READ_CALL_LOG, MainActivity.REQ_CALLLOG);
            return;
        }
        if (recents == null) {
            message("ACCESSING COMMUNICATIONS LOG…");
            loadRecentsAsync(new Runnable() { @Override public void run() { if (open && tab == TAB_RECENTS) show(TAB_RECENTS); } });
            return;
        }
        if (recents.isEmpty()) { message("NO RECENT CALLS"); return; }
        ListView lv = list();
        lv.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return recents.size(); }
            @Override public Object getItem(int i) { return recents.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) { return recentRow(recents.get(i), i); }
        });
        content.addView(lv, new LayoutParams(-1, -1));
    }

    /** Explains that call history lives in the phone app and offers a key that opens it there. */
    private void callHistoryHandOff() {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(c, "Recent calls are kept by your phone app. Open it to see your call history.", 16, pal.textHi);
        t.setLineSpacing(0, 1.15f);
        col.addView(t);
        TextView b = Ui.button(c, "OPEN CALL HISTORY", pal.c(0), Ui.PILL, new OnClickListener() {
            @Override public void onClick(View v) { openCallHistory(v); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 52));
        lp.topMargin = Ui.dp(c, Ui.M);
        col.addView(b, lp);
        content.addView(col, new LayoutParams(-1, -2));
    }

    /** Opens the system call log (ACTION_VIEW on CallLog.Calls), falling back to the dial pad. */
    private void openCallHistory(View v) {
        Intent log = new Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI);
        if (!a.startSafe(log, v)) a.startSafe(new Intent(Intent.ACTION_DIAL), v);
    }

    /** One call: direction, who, when and duration; tap calls back, long-press for more. */
    private View recentRow(final Recent r, int i) {
        Context c = getContext();
        boolean missed = r.type == CallLog.Calls.MISSED_TYPE || r.type == CallLog.Calls.REJECTED_TYPE;
        String dir = r.type == CallLog.Calls.OUTGOING_TYPE ? "↗ OUT" : missed ? "✕ MISSED"
                : r.type == CallLog.Calls.INCOMING_TYPE ? "↙ IN" : r.type == CallLog.Calls.VOICEMAIL_TYPE ? "VOICEMAIL"
                : r.type == CallLog.Calls.BLOCKED_TYPE ? "BLOCKED" : "CALL";
        int color = missed ? pal.alert : pal.c(i % 6);
        String who = r.name != null && !r.name.isEmpty() ? r.name : (r.number == null || r.number.isEmpty() ? "PRIVATE NUMBER" : r.number);
        String when = when(r.date) + (r.duration > 0 ? "  •  " + (r.duration / 60) + ":" + String.format(Locale.US, "%02d", r.duration % 60) : "");
        LinearLayout row = rowBase(c, color, who, dir + "  •  " + when, missed);
        row.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { if (r.number != null && !r.number.isEmpty()) call(r.number); else Sfx.play(getContext(), Sfx.DENY); }
        });
        row.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) { Ui.longPress(v); numberMenu(who(r), r.number); return true; }
        });
        return row;
    }

    private static String who(Recent r) {
        return r.name != null && !r.name.isEmpty() ? r.name : r.number;
    }

    /** "TODAY 9:41 AM", weekday and time within six days, else the date. */
    private String when(long t) {
        long now = System.currentTimeMillis();
        SimpleDateFormat time = new SimpleDateFormat(a.use24h() ? "HH:mm" : "h:mm a", Locale.getDefault());
        java.util.Calendar a1 = java.util.Calendar.getInstance(), b1 = java.util.Calendar.getInstance();
        b1.setTimeInMillis(t);
        boolean today = a1.get(java.util.Calendar.YEAR) == b1.get(java.util.Calendar.YEAR) && a1.get(java.util.Calendar.DAY_OF_YEAR) == b1.get(java.util.Calendar.DAY_OF_YEAR);
        if (today) return "TODAY " + time.format(new Date(t)).toUpperCase(Locale.getDefault());
        if (now - t < 6L * 86400000) return new SimpleDateFormat("EEE", Locale.getDefault()).format(new Date(t)).toUpperCase(Locale.getDefault())
                + " " + time.format(new Date(t)).toUpperCase(Locale.getDefault());
        return new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(t)).toUpperCase(Locale.getDefault());
    }

    // ---- contacts --------------------------------------------------------------------------------

    /** Searchable contact list (READ_CONTACTS), or the permission prompt. */
    private void buildContacts() {
        footer.setText("PERSONNEL DATABASE");
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            permissionPrompt("Allow TrekUI to read your contacts to list and search them here (and to match names on the keypad). Nothing leaves your phone.",
                    Manifest.permission.READ_CONTACTS, MainActivity.REQ_CONTACTS);
            return;
        }
        if (contacts == null) {
            message("ACCESSING PERSONNEL DATABASE…");
            loadContactsAsync(new Runnable() { @Override public void run() { if (open && tab == TAB_CONTACTS) show(TAB_CONTACTS); } });
            return;
        }
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        final EditText search = new EditText(c);
        search.setSingleLine(true);
        search.setHint("SEARCH PERSONNEL");
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setTextColor(pal.textHi);
        search.setHintTextColor(Ui.alpha(pal.textLo, 0.65f));
        search.setTypeface(Ui.font(c, false));
        search.setTextSize((18) * Skin.textScale());
        search.setBackground(Ui.outline(c, pal.panel, pal.c(3), 24, 2));
        search.setPadding(Ui.dp(c, Ui.L), 0, Ui.dp(c, Ui.M), 0);
        col.addView(search, new LinearLayout.LayoutParams(-1, Ui.dp(c, 50)));
        final List<Contact> all = contacts;
        final List<Contact> shown = new ArrayList<>(all);
        final ListView lv = list();
        final BaseAdapter ad = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }
            @Override public View getView(int i, View cv, ViewGroup parent) {
                Contact ct = shown.get(i);
                String[] n = ct.numbers.get(0);
                return contactRow(ct, n[0], (ct.starred ? "★ " : "") + n[1] + (ct.numbers.size() > 1 ? "  +" + (ct.numbers.size() - 1) : ""), i);
            }
        };
        lv.setAdapter(ad);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int ct, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int ct) {
                String q = s.toString();
                shown.clear();
                for (Contact x : all) if (contactMatches(x, q)) shown.add(x);
                ad.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable e) { }
        });
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 0, 1);
        ll.topMargin = Ui.dp(c, Ui.GUTTER);
        col.addView(lv, ll);
        content.addView(col, new LayoutParams(-1, -1));
        if (contacts.isEmpty()) message("NO CONTACTS WITH PHONE NUMBERS");
    }

    /** True when a contact matches a search: name contains it, or (3+ digits) a number contains them. */
    static boolean contactMatches(Contact x, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty() || (x.lower != null && x.lower.contains(q))) return true;
        String qd = digitsOnly(q);
        if (qd.length() < MIN_NUMBER_MATCH) return false;
        for (String[] n : x.numbers) if (digitsOnly(n[0]).contains(qd)) return true;
        return false;
    }

    /** A contact with one number; tap calls it, long-press for every number and actions. */
    private View contactRow(final Contact ct, final String number, String detail, int i) {
        LinearLayout row = rowBase(getContext(), pal.c(i % 6), ct.name, (detail + "  •  " + number).toUpperCase(Locale.getDefault()), false);
        row.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { call(number); }
        });
        row.setOnLongClickListener(new OnLongClickListener() {
            @Override public boolean onLongClick(View v) { Ui.longPress(v); contactMenu(ct); return true; }
        });
        return row;
    }

    /** Call any of the contact's numbers, message, or open the contact. */
    private void contactMenu(final Contact ct) {
        final List<String> labels = new ArrayList<>();
        final List<Runnable> acts = new ArrayList<>();
        for (final String[] n : ct.numbers) {
            labels.add("CALL " + n[1].toUpperCase(Locale.getDefault()) + " • " + n[0]);
            acts.add(new Runnable() { @Override public void run() { call(n[0]); } });
        }
        final String first = ct.numbers.get(0)[0];
        labels.add("SEND MESSAGE");
        acts.add(new Runnable() { @Override public void run() { message(first, true); } });
        labels.add("OPEN CONTACT");
        acts.add(new Runnable() {
            @Override public void run() {
                a.startSafe(new Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, ct.id)), null);
            }
        });
        a.showing(ConsoleDialog.options(getContext(), ct.name.toUpperCase(Locale.getDefault()), labels.toArray(new String[0]), new ConsoleDialog.OnPick() {
            @Override public void pick(int w) { acts.get(w).run(); }
        }));
    }

    /** Call, message, edit before calling, or copy a number from the call log. */
    private void numberMenu(String title, final String number) {
        if (number == null || number.isEmpty()) return;
        a.showing(ConsoleDialog.options(getContext(), title.toUpperCase(Locale.getDefault()),
                new String[]{"CALL", "SEND MESSAGE", "EDIT BEFORE CALLING", "COPY NUMBER"}, new ConsoleDialog.OnPick() {
                    @Override public void pick(int w) {
                        if (w == 0) call(number);
                        else if (w == 1) message(number, true);
                        else if (w == 2) { digits.setLength(0); digits.append(cleanDialString(number)); show(TAB_KEYPAD); }
                        else {
                            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("number", number));
                            a.toast("NUMBER COPIED");
                        }
                    }
                }));
    }

    private void message(String number, boolean sms) {
        a.startSafe(new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))), null);
    }

    // ---- shared row / list helpers -------------------------------------------------------------------

    /** A plain list without dividers or selector (rows draw their own touch feedback). */
    private ListView list() {
        ListView lv = new ListView(getContext());
        lv.setDivider(null);
        lv.setVerticalScrollBarEnabled(false);
        lv.setFastScrollEnabled(false);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        return lv;
    }

    /** Common row: colour badge with initial, name and a detail line. */
    private LinearLayout rowBase(Context c, int color, String name, String detail, boolean alert) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(c, Ui.GUTTER), 0, Ui.dp(c, Ui.GUTTER));
        row.setBackground(Ui.touchArea(c, 12));
        row.setMinimumHeight(Ui.dp(c, 56));
        TextView badge = Ui.bold(c, name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.getDefault()), 18, Ui.ink());
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.shape(c, color, Ui.LEFT, 18));
        row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, Ui.M), 0, Ui.dp(c, Ui.XS), 0);
        col.addView(Ui.oneLine(Ui.bold(c, name, 17, alert ? pal.alert : pal.textHi)));
        col.addView(Ui.oneLine(Ui.text(c, detail, 12, alert ? pal.alert : pal.textLo)));
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void message(String text) {
        TextView t = Ui.bold(getContext(), text, 16, pal.textLo);
        t.setGravity(Gravity.CENTER);
        content.addView(t, new LayoutParams(-1, Ui.dp(getContext(), 120), Gravity.CENTER));
    }

    /** Explanation and GRANT key; after a permanent denial the key opens App info instead. */
    private void permissionPrompt(String why, final String perm, final int req) {
        Context c = getContext();
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = Ui.text(c, why, 16, pal.textHi);
        t.setLineSpacing(0, 1.15f);
        col.addView(t);
        TextView b = Ui.button(c, "GRANT ACCESS", pal.c(0), Ui.PILL, new OnClickListener() {
            @Override public void onClick(View v) {
                Prefs p = Prefs.get(getContext());
                if (p.b("asked_" + perm, false) && !a.shouldShowRequestPermissionRationale(perm)) {
                    a.startSafe(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getContext().getPackageName())), v);
                } else {
                    p.put("asked_" + perm, true);
                    a.requestPermissions(new String[]{perm}, req);
                }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(c, 52));
        lp.topMargin = Ui.dp(c, Ui.M);
        col.addView(b, lp);
        content.addView(col, new LayoutParams(-1, -2));
    }

    private boolean granted(String perm) {
        return getContext().checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    /** Permission results routed from MainActivity (call, contacts, call log). */
    void onPermission(int code, boolean ok) {
        if (code == MainActivity.REQ_CALL) {
            String n = pendingCall;
            pendingCall = null;
            if (n == null) return;
            if (ok) place(n);
            else handToPhoneApp(n);
        } else if (code == MainActivity.REQ_CONTACTS || code == MainActivity.REQ_CALLLOG) {
            dropCaches();
            if (open) show(tab);
        }
    }

    // ---- data ----------------------------------------------------------------------------------

    /** Contacts with phone numbers, starred first then by name; numbers de-duplicated, primary first. */
    private static List<Contact> loadContacts(android.content.ContentResolver resolver, android.content.res.Resources res) {
        Map<Long, Contact> map = new LinkedHashMap<>();
        String[] proj = {ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL, ContactsContract.CommonDataKinds.Phone.STARRED,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY};
        try (Cursor cur = resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                ContactsContract.CommonDataKinds.Phone.STARRED + " DESC, " + ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY
                        + " COLLATE LOCALIZED ASC")) {
            if (cur != null) while (cur.moveToNext()) {
                long id = cur.getLong(0);
                String number = cur.getString(2);
                if (number == null || number.trim().isEmpty()) continue;
                Contact ct = map.get(id);
                if (ct == null) {
                    ct = new Contact();
                    ct.id = id;
                    ct.name = cur.isNull(1) ? number : cur.getString(1);
                    ct.lower = ct.name.toLowerCase(Locale.getDefault());
                    ct.starred = cur.getInt(5) == 1;
                    map.put(id, ct);
                }
                String label;
                try {
                    label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(res, cur.getInt(3), cur.getString(4)).toString();
                } catch (Exception e) {
                    label = "PHONE";
                }
                boolean dup = false;
                String nd = number.replaceAll("[^0-9+]", "");
                for (String[] n : ct.numbers) if (n[0].replaceAll("[^0-9+]", "").equals(nd)) { dup = true; break; }
                if (dup) continue;
                String[] entry = {number.trim(), label};
                if (cur.getInt(6) == 1) ct.numbers.add(0, entry); else ct.numbers.add(entry);
            }
        } catch (RuntimeException ignored) {
            // provider unavailable or permission revoked mid-query: show what was read
        }
        return new ArrayList<>(map.values());
    }

    /** The latest {@link #MAX_RECENTS} calls, newest first. */
    private static List<Recent> loadRecents(android.content.ContentResolver resolver) {
        List<Recent> out = new ArrayList<>();
        String[] proj = {CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION};
        try (Cursor cur = resolver.query(CallLog.Calls.CONTENT_URI, proj, null, null, CallLog.Calls.DATE + " DESC")) {
            if (cur != null) while (cur.moveToNext() && out.size() < MAX_RECENTS) {
                Recent r = new Recent();
                r.number = cur.getString(0);
                r.name = cur.getString(1);
                r.type = cur.getInt(2);
                r.date = cur.getLong(3);
                r.duration = cur.getLong(4);
                out.add(r);
            }
        } catch (RuntimeException ignored) {
            // provider unavailable or permission revoked mid-query
        }
        return out;
    }

    /** The last outgoing number (for CALL on an empty pad), or null. */
    private String lastDialled() {
        if (!BuildConfig.CALL_LOG_ENABLED || !granted(Manifest.permission.READ_CALL_LOG)) return null;
        try { return CallLog.Calls.getLastOutgoingCall(getContext()); } catch (Exception e) { return null; }
    }

    // ---- placing calls -------------------------------------------------------------------------------

    /** True for an emergency number (asks the system on API 29+, else a fixed list). */
    static boolean isEmergency(Context c, String number) {
        String n = number.replaceAll("[^0-9+]", "");
        try {
            TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
            if (Build.VERSION.SDK_INT >= 29 && tm != null) return tm.isEmergencyNumber(n);
        } catch (RuntimeException ignored) {
            // some devices throw without telephony: use the list
        }
        return isFallbackEmergency(n);
    }

    /** The fixed emergency-number list used when the system can't be asked. */
    static boolean isFallbackEmergency(String digits) {
        for (String e : EMERGENCY) if (e.equals(digits)) return true;
        return false;
    }

    /**
     * Places a call: emergency numbers via the phone app; directly when CALL_PHONE is granted
     * (asked once); otherwise handed to the phone app pre-filled.
     */
    void call(String number) {
        String n = number.trim();
        if (n.isEmpty()) { Sfx.play(getContext(), Sfx.DENY); return; }
        if (isEmergency(getContext(), n)) {
            // Emergency calls always go through the system phone app.
            a.toast("EMERGENCY CALL • OPENING PHONE APP");
            handToPhoneApp(n);
            return;
        }
        if (granted(Manifest.permission.CALL_PHONE)) { place(n); return; }
        Prefs p = Prefs.get(getContext());
        if (p.b("asked_call", false) && !a.shouldShowRequestPermissionRationale(Manifest.permission.CALL_PHONE)) {
            handToPhoneApp(n); // user declined direct calling: one extra tap in the phone app
            return;
        }
        p.put("asked_call", true);
        pendingCall = n;
        a.requestPermissions(new String[]{Manifest.permission.CALL_PHONE}, MainActivity.REQ_CALL);
    }

    /** ACTION_CALL (CALL_PHONE granted). */
    private void place(String n) {
        Sfx.play(getContext(), Sfx.COMM);
        if (a.startSafe(new Intent(Intent.ACTION_CALL, Uri.fromParts("tel", n, null)), null)) {
            digits.setLength(0);
            close(false);
        }
    }

    /** ACTION_DIAL: the phone app opens with the number filled in. */
    private void handToPhoneApp(String n) {
        if (a.startSafe(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n, null)), null)) {
            digits.setLength(0);
            close(false);
        }
    }

    private void openPhoneApp() {
        String d = digits.toString();
        if (a.startSafe(new Intent(Intent.ACTION_DIAL, d.isEmpty() ? null : Uri.fromParts("tel", d, null)), null)) close(false);
    }
}
