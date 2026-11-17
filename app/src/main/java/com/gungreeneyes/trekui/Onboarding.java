package com.gungreeneyes.trekui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The first-run tour: eight short stops (welcome, long-press to ASSIGN, the app drawer, the shade,
 * CONFIG, switching interfaces, layout backup, set as default home), shown once on the home screen
 * after the "set as default home" welcome and replayable from CONFIG › DEFAULT HOME › REPLAY TOUR.
 *
 * <p>The tour is one {@link ConsoleDialog} panel whose body is redrawn per stop, so it takes the
 * current interface's frame (console outline, Imperial or Collective tray, pipe material) and its key names
 * ({@link Skin#nav}: APPS / CONFIG on console, CABINET / WORKSHOP on the Tobacconist, …). SKIP, BACK
 * and the system back key end or step it; it never blocks the home screen. The flags live in
 * {@link Prefs} ({@link #KEY_SEEN}, {@link #KEY_REPLAY}) and are excluded from layout archives.
 */
final class Onboarding {
    /** Set once the tour has been shown (skipped or finished). */
    static final String KEY_SEEN = "tourSeen";
    /** Set by CONFIG's REPLAY TOUR; home shows the tour on its next resume and clears it. */
    static final String KEY_REPLAY = "tourReplay";
    /** Delay before the first-run tour, so the boot sequence and the home screen settle first. */
    static final long FIRST_RUN_DELAY_MS = 2500L;
    /** Delay before a replayed tour (back from CONFIG). */
    static final long REPLAY_DELAY_MS = 500L;

    static final int STEP_WELCOME = 0, STEP_ASSIGN = 1, STEP_DRAWER = 2, STEP_SHADE = 3, STEP_CONFIG = 4,
            STEP_INTERFACES = 5, STEP_BACKUP = 6, STEP_HOME = 7;
    /** Number of stops. */
    static final int STEPS = 8;

    private static final int[] HEADINGS = {R.string.tour_welcome_heading, R.string.tour_assign_heading, R.string.tour_drawer_heading,
            R.string.tour_shade_heading, R.string.tour_config_heading, R.string.tour_interfaces_heading,
            R.string.tour_backup_heading, R.string.tour_home_heading};
    private static final int[] BODIES = {R.string.tour_welcome_body, R.string.tour_assign_body, R.string.tour_drawer_body,
            R.string.tour_shade_body, R.string.tour_config_body, R.string.tour_interfaces_body,
            R.string.tour_backup_body, R.string.tour_home_body};

    private Onboarding() {}

    // ---- pure logic (unit-tested) ---------------------------------------------------------------------

    /**
     * Whether home should start the tour now. A replay request always does; otherwise only once, and
     * not on the very first resume while the "set as default home" welcome is still due (it comes first).
     */
    static boolean shouldStart(boolean seen, boolean replay, boolean welcomed, boolean defaultHome) {
        if (replay) return true;
        if (seen) return false;
        return welcomed || defaultHome;
    }

    /** The stop after {@code step} (or -1 when the tour is over). */
    static int next(int step) { return step + 1 >= STEPS ? -1 : step + 1; }

    /** The stop before {@code step} (never below the first). */
    static int back(int step) { return Math.max(0, step - 1); }

    /** "3 / 8" style counter text. */
    static String counter(int step) { return (step + 1) + " / " + STEPS; }

    // ---- entry points -------------------------------------------------------------------------------

    /** Asks home to replay the tour the next time it is shown (CONFIG's REPLAY TOUR). */
    static void requestReplay(Context c) { Prefs.get(c).put(KEY_REPLAY, true); }

    /** Called from home's onResume: schedules the tour when {@link #shouldStart} says so. */
    static void maybeStart(final MainActivity a) {
        final Prefs p = Prefs.get(a);
        boolean replay = p.b(KEY_REPLAY, false);
        if (!shouldStart(p.b(KEY_SEEN, false), replay, p.b("welcomed", false), MainActivity.isDefaultHome(a))) return;
        a.handler().postDelayed(new Runnable() {
            @Override public void run() {
                // checked again: an earlier resume may already have started it
                if (!a.isResumedNow() || a.isFinishing()) return;
                if (!p.b(KEY_REPLAY, false) && p.b(KEY_SEEN, false)) return;
                p.put(KEY_SEEN, true);
                p.put(KEY_REPLAY, false);
                a.showing(show(a, 0));
            }
        }, replay ? REPLAY_DELAY_MS : FIRST_RUN_DELAY_MS);
    }

    // ---- the panel ----------------------------------------------------------------------------------

    /** Shows the tour from {@code step}; returns the dialog (already showing). */
    static Dialog show(final Activity a, int step) {
        Sfx.play(a, Sfx.ALERT);
        final ConsoleDialog.Built b = ConsoleDialog.base(a, a.getString(Skin.pipe() ? R.string.tour_title_pipe : R.string.tour_title_console));
        b.body.setTag("tour_body");
        render(a, b, step);
        b.dialog.show();
        return b.dialog;
    }

    /** Fills the panel body for one stop: progress cells, heading, text and the button row. */
    private static void render(final Activity a, final ConsoleDialog.Built b, final int step) {
        Palette p = Prefs.get(a).palette();
        b.body.removeAllViews();

        LinearLayout progress = new LinearLayout(a);
        progress.setTag("tour_progress");
        for (int i = 0; i < STEPS; i++) {
            View cell = new View(a);
            int color = i == step ? p.c(0) : i < step ? Ui.alpha(p.c(2), 0.75f) : Ui.alpha(p.c(1), 0.3f);
            cell.setBackground(Ui.shape(a, color, i == 0 ? Ui.LEFT : i == STEPS - 1 ? Ui.RIGHT : Ui.SQUARE, 6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, 10), 1);
            if (i > 0) lp.leftMargin = Ui.dp(a, Ui.HAIR);
            progress.addView(cell, lp);
        }
        b.body.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout headRow = new LinearLayout(a);
        headRow.setPadding(Ui.dp(a, Ui.XS), Ui.dp(a, Ui.M), Ui.dp(a, Ui.XS), Ui.dp(a, Ui.S));
        TextView heading = Ui.bold(a, a.getString(HEADINGS[step]), 20, p.c(0));
        heading.setTag("tour_heading");
        headRow.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        headRow.addView(Ui.bold(a, counter(step), 16, p.textLo));
        b.body.addView(headRow);

        boolean isDefault = MainActivity.isDefaultHome(a);
        String body = step == STEP_HOME && isDefault ? a.getString(R.string.tour_home_body_done)
                : a.getString(BODIES[step], Skin.nav(0), Skin.nav(2), LayoutArchive.interfaceName(Skin.faction));
        TextView text = Ui.text(a, body, 16, p.textHi);
        text.setTag("tour_text");
        text.setLineSpacing(0, 1.15f);
        text.setPadding(Ui.dp(a, Ui.XS), 0, Ui.dp(a, Ui.XS), Ui.dp(a, Ui.L));
        b.body.addView(text);

        if (step == STEP_HOME && !isDefault) {
            TextView setHome = ConsoleDialog.option(a, a.getString(R.string.welcome_set_default), p.c(2), v -> {
                Sfx.play(a, Sfx.CONFIRM);
                b.dialog.dismiss();
                MainActivity.requestDefaultHome(a);
            });
            setHome.setTag("tour_set_home");
            ConsoleDialog.addOption(b, setHome);
        }

        LinearLayout row = new LinearLayout(a);
        final int nextStep = next(step);
        TextView skip = ConsoleDialog.option(a, a.getString(step == 0 ? R.string.tour_skip : R.string.tour_back), p.c(3), v -> {
            if (step == 0) {
                Sfx.play(a, Sfx.CANCEL);
                b.dialog.dismiss();
            } else {
                Sfx.play(a, Sfx.TAP);
                render(a, b, back(step));
            }
        });
        skip.setTag(step == 0 ? "tour_skip" : "tour_back");
        TextView next = ConsoleDialog.option(a, a.getString(nextStep < 0 ? R.string.tour_done : R.string.tour_next), p.c(0), v -> {
            if (nextStep < 0) {
                Sfx.play(a, Sfx.STATUS_OK);
                b.dialog.dismiss();
            } else {
                Sfx.play(a, Sfx.PAGE);
                render(a, b, nextStep);
            }
        });
        next.setTag(nextStep < 0 ? "tour_done" : "tour_next");
        LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        h.rightMargin = Ui.dp(a, Ui.S);
        row.addView(skip, h);
        row.addView(next, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        b.body.addView(row);
        if (step > 0) {
            TextView end = Ui.text(a, a.getString(R.string.tour_skip), 14, p.textLo);
            end.setTag("tour_skip");
            end.setPadding(Ui.dp(a, Ui.XS), Ui.dp(a, Ui.S), Ui.dp(a, Ui.XS), 0);
            end.setMinHeight(Ui.dp(a, Ui.MIN_TOUCH_DP));
            end.setGravity(android.view.Gravity.CENTER);
            end.setOnClickListener(v -> {
                Sfx.play(a, Sfx.CANCEL);
                b.dialog.dismiss();
            });
            b.body.addView(end, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }
}
