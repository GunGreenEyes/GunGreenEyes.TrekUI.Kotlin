package com.gungreeneyes.trekui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The first-run tour: eight short stops (welcome, long-press to ASSIGN, the app drawer, the shade,
 * CONFIG, switching interfaces, layout backup, set as default home), shown once on the home screen
 * after the "set as default home" welcome and replayable from CONFIG › DEFAULT HOME › REPLAY TOUR.
 *
 * The tour is one [ConsoleDialog] panel whose body is redrawn per stop, so it takes the
 * current interface's frame (console outline, Imperial or Collective tray) and its key names
 * ([Skin.nav]: APPS / CONFIG). SKIP, BACK
 * and the system back key end or step it; it never blocks the home screen. The flags live in
 * [Prefs] ([KEY_SEEN], [KEY_REPLAY]) and are excluded from layout archives.
 */
internal object Onboarding {
    /** Set once the tour has been shown (skipped or finished). */
    const val KEY_SEEN = "tourSeen"

    /** Set by CONFIG's REPLAY TOUR; home shows the tour on its next resume and clears it. */
    const val KEY_REPLAY = "tourReplay"

    /** Delay before the first-run tour, so the boot sequence and the home screen settle first. */
    const val FIRST_RUN_DELAY_MS = 2500L

    /** Delay before a replayed tour (back from CONFIG). */
    const val REPLAY_DELAY_MS = 500L

    const val STEP_WELCOME = 0
    const val STEP_ASSIGN = 1
    const val STEP_DRAWER = 2
    const val STEP_SHADE = 3
    const val STEP_CONFIG = 4
    const val STEP_INTERFACES = 5
    const val STEP_BACKUP = 6
    const val STEP_HOME = 7

    /** Number of stops. */
    const val STEPS = 8

    private val HEADINGS = intArrayOf(
        R.string.tour_welcome_heading, R.string.tour_assign_heading, R.string.tour_drawer_heading,
        R.string.tour_shade_heading, R.string.tour_config_heading, R.string.tour_interfaces_heading,
        R.string.tour_backup_heading, R.string.tour_home_heading,
    )
    private val BODIES = intArrayOf(
        R.string.tour_welcome_body, R.string.tour_assign_body, R.string.tour_drawer_body,
        R.string.tour_shade_body, R.string.tour_config_body, R.string.tour_interfaces_body,
        R.string.tour_backup_body, R.string.tour_home_body,
    )

    // ---- pure logic (unit-tested) ---------------------------------------------------------------------

    /**
     * Whether home should start the tour now. A replay request always does; otherwise only once, and
     * not on the very first resume while the "set as default home" welcome is still due (it comes first).
     */
    @JvmStatic
    fun shouldStart(seen: Boolean, replay: Boolean, welcomed: Boolean, defaultHome: Boolean): Boolean {
        if (replay) return true
        if (seen) return false
        return welcomed || defaultHome
    }

    /** The stop after `step` (or -1 when the tour is over). */
    @JvmStatic
    fun next(step: Int): Int = if (step + 1 >= STEPS) -1 else step + 1

    /** The stop before `step` (never below the first). */
    @JvmStatic
    fun back(step: Int): Int = Math.max(0, step - 1)

    /** "3 / 8" style counter text. */
    @JvmStatic
    fun counter(step: Int): String = "${step + 1} / $STEPS"

    // ---- entry points -------------------------------------------------------------------------------

    /** Asks home to replay the tour the next time it is shown (CONFIG's REPLAY TOUR). */
    @JvmStatic
    fun requestReplay(c: Context) = Prefs.get(c).put(KEY_REPLAY, true)

    /** Called from home's onResume: schedules the tour when [shouldStart] says so. */
    @JvmStatic
    fun maybeStart(a: MainActivity) {
        val p = Prefs.get(a)
        val replay = p.b(KEY_REPLAY, false)
        if (!shouldStart(p.b(KEY_SEEN, false), replay, p.b("welcomed", false), MainActivity.isDefaultHome(a))) return
        a.handler().postDelayed({
            // checked again: an earlier resume may already have started it
            if (!a.isResumedNow() || a.isFinishing) return@postDelayed
            if (!p.b(KEY_REPLAY, false) && p.b(KEY_SEEN, false)) return@postDelayed
            p.put(KEY_SEEN, true)
            p.put(KEY_REPLAY, false)
            a.showing(show(a, 0))
        }, if (replay) REPLAY_DELAY_MS else FIRST_RUN_DELAY_MS)
    }

    // ---- the panel ----------------------------------------------------------------------------------

    /** Shows the tour from `step`; returns the dialog (already showing). */
    @JvmStatic
    fun show(a: Activity, step: Int): Dialog {
        Sfx.play(a, Sfx.ALERT)
        val b = ConsoleDialog.base(a, a.getString(R.string.tour_title_console))
        b.body.tag = "tour_body"
        render(a, b, step)
        b.dialog.show()
        return b.dialog
    }

    /** Fills the panel body for one stop: progress cells, heading, text and the button row. */
    private fun render(a: Activity, b: ConsoleDialog.Built, step: Int) {
        val p = Prefs.get(a).palette()
        b.body.removeAllViews()

        val progress = LinearLayout(a)
        progress.tag = "tour_progress"
        for (i in 0 until STEPS) {
            val cell = View(a)
            val color = when {
                i == step -> p.c(0)
                i < step -> Ui.alpha(p.c(2), 0.75f)
                else -> Ui.alpha(p.c(1), 0.3f)
            }
            val kind = when (i) {
                0 -> Ui.LEFT
                STEPS - 1 -> Ui.RIGHT
                else -> Ui.SQUARE
            }
            cell.background = Ui.shape(a, color, kind, 6f)
            val lp = LinearLayout.LayoutParams(0, Ui.dp(a, 10f), 1f)
            if (i > 0) lp.leftMargin = Ui.dp(a, Ui.HAIR.toFloat())
            progress.addView(cell, lp)
        }
        b.body.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val headRow = LinearLayout(a)
        headRow.setPadding(Ui.dp(a, Ui.XS.toFloat()), Ui.dp(a, Ui.M.toFloat()), Ui.dp(a, Ui.XS.toFloat()), Ui.dp(a, Ui.S.toFloat()))
        val heading = Ui.bold(a, a.getString(HEADINGS[step]), 20f, p.c(0))
        heading.tag = "tour_heading"
        headRow.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headRow.addView(Ui.bold(a, counter(step), 16f, p.textLo))
        b.body.addView(headRow)

        val isDefault = MainActivity.isDefaultHome(a)
        val body = if (step == STEP_HOME && isDefault) {
            a.getString(R.string.tour_home_body_done)
        } else {
            a.getString(BODIES[step], Skin.nav(0), Skin.nav(2), LayoutArchive.interfaceName(Skin.faction))
        }
        val text = Ui.text(a, body, 16f, p.textHi)
        text.tag = "tour_text"
        text.setLineSpacing(0f, 1.15f)
        text.setPadding(Ui.dp(a, Ui.XS.toFloat()), 0, Ui.dp(a, Ui.XS.toFloat()), Ui.dp(a, Ui.L.toFloat()))
        b.body.addView(text)

        if (step == STEP_HOME && !isDefault) {
            val setHome = ConsoleDialog.option(a, a.getString(R.string.welcome_set_default), p.c(2)) {
                Sfx.play(a, Sfx.CONFIRM)
                b.dialog.dismiss()
                MainActivity.requestDefaultHome(a)
            }
            setHome.tag = "tour_set_home"
            ConsoleDialog.addOption(b, setHome)
        }

        val row = LinearLayout(a)
        val nextStep = next(step)
        val skip = ConsoleDialog.option(a, a.getString(if (step == 0) R.string.tour_skip else R.string.tour_back), p.c(3)) {
            if (step == 0) {
                Sfx.play(a, Sfx.CANCEL)
                b.dialog.dismiss()
            } else {
                Sfx.play(a, Sfx.TAP)
                render(a, b, back(step))
            }
        }
        skip.tag = if (step == 0) "tour_skip" else "tour_back"
        val nextButton = ConsoleDialog.option(a, a.getString(if (nextStep < 0) R.string.tour_done else R.string.tour_next), p.c(0)) {
            if (nextStep < 0) {
                Sfx.play(a, Sfx.STATUS_OK)
                b.dialog.dismiss()
            } else {
                Sfx.play(a, Sfx.PAGE)
                render(a, b, nextStep)
            }
        }
        nextButton.tag = if (nextStep < 0) "tour_done" else "tour_next"
        val h = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        h.rightMargin = Ui.dp(a, Ui.S.toFloat())
        row.addView(skip, h)
        row.addView(nextButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        b.body.addView(row)
        if (step > 0) {
            val end = Ui.text(a, a.getString(R.string.tour_skip), 14f, p.textLo)
            end.tag = "tour_skip"
            end.setPadding(Ui.dp(a, Ui.XS.toFloat()), Ui.dp(a, Ui.S.toFloat()), Ui.dp(a, Ui.XS.toFloat()), 0)
            end.minHeight = Ui.dp(a, Ui.MIN_TOUCH_DP)
            end.gravity = Gravity.CENTER
            end.setOnClickListener {
                Sfx.play(a, Sfx.CANCEL)
                b.dialog.dismiss()
            }
            b.body.addView(end, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}
