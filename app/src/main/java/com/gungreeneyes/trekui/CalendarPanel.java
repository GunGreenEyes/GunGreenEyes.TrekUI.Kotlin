package com.gungreeneyes.trekui;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;

/**
 * Home panel "SCHEDULE": the next appointments (up to {@link #MAX_EVENTS} within
 * {@link #DAYS_AHEAD} days) from the device calendar, read-only. Needs READ_CALENDAR, asked only
 * when the person taps GRANT. Watches the calendar provider while visible and plays a comm alert
 * once shortly before an appointment starts (and a hail as it starts) while the home screen is on.
 */
class CalendarPanel extends Panel {
    /** How far ahead to look, and how many entries to show. */
    static final int DAYS_AHEAD = 7, MAX_EVENTS = 4;
    /** Alert this long before an appointment, and treat it as "starting now" for this long after. */
    static final long ALERT_BEFORE_MS = 10 * 60 * 1000L, STARTING_WINDOW_MS = 2 * 60 * 1000L;
    private static final long DAY_MS = 24L * 3600 * 1000;
    /** Alert keys remembered; older ones are dropped so the set can't grow without bound. */
    private static final int MAX_ALERT_KEYS = 200;
    /** Events already announced ("id:begin", plus "!" for the starting-now hail). */
    private static final Set<String> alerted = new HashSet<>();
    /** Calendar provider queries run here, never on the main thread. */
    private static final ExecutorService IO = Http.newExecutor("trekui-calendar");
    /** Bumped on every refresh and pause; query results from an older one are dropped. */
    private int generation;
    private boolean observing;
    private final ContentObserver observer;

    private static final class Ev {
        long id, begin, end;
        String title, where;
        boolean allDay;
        int color;
    }

    CalendarPanel(MainActivity a, int accent) {
        super(a, accent);
        observer = new ContentObserver(a.handler()) {
            @Override public void onChange(boolean self) { refresh(); }
        };
    }

    @Override public String id() { return Prefs.W_CALENDAR; }
    @Override public String title() { return "SCHEDULE"; }

    @Override
    public void build() {
        card.status.setOnClickListener(Ui.feedback(new View.OnClickListener() {
            @Override public void onClick(View v) { openCalendar(System.currentTimeMillis(), v); }
        }));
        refresh();
    }

    /** True when READ_CALENDAR is granted. */
    private boolean granted() {
        return a.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void resume() {
        if (granted() && !observing) {
            try {
                a.getContentResolver().registerContentObserver(CalendarContract.Events.CONTENT_URI, true, observer);
                observing = true;
            } catch (Exception ignored) {
            }
        }
        refresh();
    }

    @Override
    public void pause() {
        generation++;
        if (observing) {
            a.getContentResolver().unregisterContentObserver(observer);
            observing = false;
        }
    }

    @Override public void tick() { refresh(); }

    @Override
    public void onPermissionResult(int code, boolean ok) {
        if (code != MainActivity.REQ_CALENDAR) return;
        if (ok) resume();
        else refresh();
    }

    /** Opens the calendar app at a time. */
    private void openCalendar(long when, View v) {
        Uri u = CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(String.valueOf(when)).build();
        a.startSafe(new Intent(Intent.ACTION_VIEW, u), v);
    }

    /**
     * Shows the permission prompt, or queries the calendar in the background and then rebuilds
     * the list and plays any due alerts (results are dropped if a newer refresh or a pause came
     * first, or the activity is gone).
     */
    void refresh() {
        final int gen = ++generation;
        if (!granted()) {
            LinearLayout b = card.body;
            b.removeAllViews();
            card.status.setText("ACCESS REQUIRED");
            TextView t = Ui.text(a, "Allow read-only calendar access to show your upcoming appointments here. "
                    + "Nothing leaves your phone.", 15, pal.textHi);
            t.setLineSpacing(0, 1.1f);
            b.addView(t);
            TextView btn = Ui.button(a, "GRANT CALENDAR ACCESS", accent, Ui.PILL, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (Prefs.get(a).b("calAsked", false) && !a.shouldShowRequestPermissionRationale(Manifest.permission.READ_CALENDAR)) {
                        // Permanently denied: send to app settings.
                        a.startSafe(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.getPackageName())), v);
                    } else {
                        Prefs.get(a).put("calAsked", true);
                        a.requestPermissions(new String[]{Manifest.permission.READ_CALENDAR}, MainActivity.REQ_CALENDAR);
                    }
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(a, 52));
            lp.topMargin = Ui.dp(a, Ui.M);
            b.addView(btn, lp);
            return;
        }
        final ContentResolver resolver = a.getApplicationContext().getContentResolver();
        IO.execute(new Runnable() {
            @Override public void run() {
                final List<Ev> evs = query(resolver);
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (gen != generation || a.isFinishing() || a.isDestroyed()) return;
                        show(evs);
                    }
                });
            }
        });
    }

    /** Rebuilds the list from query results (null = provider unavailable). */
    private void show(List<Ev> evs) {
        LinearLayout b = card.body;
        b.removeAllViews();
        if (evs == null) {
            card.status.setText("UNAVAILABLE");
            b.addView(Ui.text(a, "CALENDAR PROVIDER UNAVAILABLE", 15, pal.textLo));
            return;
        }
        card.status.setText(evs.isEmpty() ? "CLEAR" : evs.size() + (evs.size() == 1 ? " ENTRY" : " ENTRIES"));
        if (evs.isEmpty()) {
            b.addView(Ui.text(a, "NO APPOINTMENTS IN THE NEXT 7 DAYS", 15, pal.textLo));
            return;
        }
        long now = System.currentTimeMillis();
        if (alerted.size() > MAX_ALERT_KEYS) alerted.clear();
        for (final Ev e : evs) {
            LinearLayout row = new LinearLayout(a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(a, Ui.GUTTER), 0, Ui.dp(a, Ui.GUTTER));
            row.setBackground(Ui.touchArea(a, 12));
            View chip = new View(a);
            chip.setBackground(Ui.shape(a, e.color != 0 ? (e.color | 0xFF000000) : accent, Ui.PILL, 0));
            row.addView(chip, new LinearLayout.LayoutParams(Ui.dp(a, 7), Ui.dp(a, 38)));
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Ui.dp(a, Ui.M), 0, 0, 0);
            boolean live = !e.allDay && e.begin <= now && e.end > now;
            col.addView(Ui.oneLine(Ui.bold(a, e.title, 17, live ? pal.c(0) : pal.textHi)));
            String when = when(e) + (e.where != null && !e.where.isEmpty() ? "  •  " + e.where : "");
            col.addView(Ui.oneLine(Ui.text(a, when.toUpperCase(Locale.getDefault()), 13, live ? pal.c(0) : pal.textLo)));
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(Ui.feedback(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent i = new Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id));
                    i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin);
                    i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end);
                    a.startSafe(i, v);
                }
            }));
            b.addView(row, new LinearLayout.LayoutParams(-1, -2));
            // Comm alert once when an appointment is about to start (only while home is visible).
            String k = e.id + ":" + e.begin;
            if (!e.allDay && e.begin > now && e.begin - now <= ALERT_BEFORE_MS && !alerted.contains(k) && a.isResumedNow()) {
                alerted.add(k);
                Sfx.play(a, Sfx.ALERT);
            } else if (!e.allDay && e.begin <= now && now - e.begin < STARTING_WINDOW_MS && !alerted.contains(k + "!") && a.isResumedNow()) {
                alerted.add(k + "!");
                alerted.add(k);
                Sfx.play(a, Sfx.HAIL); // appointment starting now
            }
        }
    }

    /** "TODAY 9:30 AM – 10:00 AM", "TOMORROW  •  ALL DAY", "FRI JUN 6 …". All-day events are UTC dates. */
    private String when(Ev e) {
        Calendar today = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        if (e.allDay) c.setTimeZone(TimeZone.getTimeZone("UTC"));
        c.setTimeInMillis(e.begin);
        String day = relativeDay(dayDiff(today, c, e.allDay));
        if (day == null) {
            SimpleDateFormat f = new SimpleDateFormat("EEE MMM d", Locale.getDefault());
            if (e.allDay) f.setTimeZone(TimeZone.getTimeZone("UTC"));
            day = f.format(new Date(e.begin));
        }
        if (e.allDay) return day + "  •  ALL DAY";
        SimpleDateFormat t = new SimpleDateFormat(a.use24h() ? "HH:mm" : "h:mm a", Locale.getDefault());
        return day + "  " + t.format(new Date(e.begin)) + " – " + t.format(new Date(e.end));
    }

    /** "TODAY" / "TOMORROW" for a day difference of 0 / 1, else null (show the date). */
    static String relativeDay(int dayDiff) {
        return dayDiff == 0 ? "TODAY" : dayDiff == 1 ? "TOMORROW" : null;
    }

    /** Whole calendar days from today's date to c's date (UTC dates for all-day events). */
    static int dayDiff(Calendar today, Calendar c, boolean allDay) {
        Calendar t = Calendar.getInstance(allDay ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
        t.clear();
        t.set(today.get(Calendar.YEAR), today.get(Calendar.MONTH), today.get(Calendar.DAY_OF_MONTH));
        Calendar d = Calendar.getInstance(allDay ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
        d.clear();
        d.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        return (int) Math.round((d.getTimeInMillis() - t.getTimeInMillis()) / (double) DAY_MS);
    }

    /**
     * Visible event instances from yesterday (to catch ones still running) to {@link #DAYS_AHEAD}
     * days ahead, skipping finished ones. Null when the provider fails; empty without permission.
     */
    private static List<Ev> query(ContentResolver resolver) {
        long now = System.currentTimeMillis();
        Uri.Builder ub = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(ub, now - DAY_MS);
        ContentUris.appendId(ub, now + DAYS_AHEAD * DAY_MS);
        String[] proj = {CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.DISPLAY_COLOR};
        List<Ev> out = new ArrayList<>();
        try (Cursor c = resolver.query(ub.build(), proj,
                CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC")) {
            if (c == null) return null;
            // Midnight today in UTC terms, for all-day events (stored as UTC dates).
            Calendar local = Calendar.getInstance();
            Calendar utcToday = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            utcToday.clear();
            utcToday.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH));
            while (c.moveToNext() && out.size() < MAX_EVENTS) {
                Ev e = new Ev();
                e.id = c.getLong(0);
                e.title = c.isNull(1) || c.getString(1).trim().isEmpty() ? "(NO TITLE)" : c.getString(1).trim();
                e.begin = c.getLong(2);
                e.end = c.getLong(3);
                e.allDay = c.getInt(4) == 1;
                e.where = c.isNull(5) ? null : c.getString(5).trim();
                e.color = c.isNull(6) ? 0 : c.getInt(6);
                if (e.allDay ? e.end <= utcToday.getTimeInMillis() : e.end <= now) continue;
                out.add(e);
            }
        } catch (SecurityException se) {
            return new ArrayList<>();
        } catch (RuntimeException ex) {
            return null; // provider missing or crashed
        }
        return out;
    }
}
