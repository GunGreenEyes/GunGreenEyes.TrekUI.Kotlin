package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * BRIDGE STATIONS: six playful consoles reached from INFO › STATIONS on the deploying home
 * layouts. Each one is an animated display with controls that act on it, drawn in the current
 * interface's style, with sounds chosen by role.
 *
 *  OPS          power distribution across ship systems, transporter, hailing
 *  CONN         heading and speed with a starfield that follows the warp factor
 *  TACTICAL     target reticle, shields, pulse beams / torpedoes, red alert
 *  ENGINEERING  warp core output, authorisation, core ejection (and its return)
 *  SCIENCE      sensor sweep with contacts, range, contact analysis, probe
 *  MEDICAL      scan bed: heart trace and vitals, scan, injector
 *
 * Imperial and Collective get their own names for the stations and some controls. Nothing here is real
 * ship data; it is a toy, and says so on screen ("SIMULATION").
 *
 * Sounds: every control plays a ROLE ("beam", "warp_engage"...). A role resolves to the owner's
 * choice for it (long-press any control), else the first imported clip whose name matches the
 * role's keywords (so downloaded clips imported in CONFIG › AUDIO are picked up automatically),
 * else a bundled clip that suits it.
 *
 * Responsive: the display's height follows the window (shorter on landscape phones) and wide
 * windows get three controls per row instead of two. The OPS power maths is pure
 * ([Ops.redistribute]) and unit-tested.
 *
 * Java interop: MainActivity calls [build], [pageTitle] and [entryName] (all [JvmStatic]) and reads
 * the [Station] fields directly ([JvmField]); BootSequence calls [play].
 */
internal object Stations {
    /** Display height at the design scale, and the least it may shrink to, in dp. */
    private const val SIM_HEIGHT_DP = 230f
    private const val SIM_MIN_HEIGHT_DP = 150f

    /** Share of the window height the display may take. */
    private const val SIM_HEIGHT_SHARE = 0.45f

    /** Control key height, in dp. */
    private const val CONTROL_DP = 46f

    /** MainActivity: lets the owner pick sounds for controls. */
    fun interface Host {
        /** Let the owner choose the sound for a role (long-press on a control). */
        fun assignSound(role: String, title: String)
    }

    /** A station page: name, sidebar glyph, colour and its console view. */
    class Station(
        @JvmField val name: String,
        @JvmField val glyph: String,
        @JvmField val color: Int,
        @JvmField val view: View,
    )

    /** Title of the stations page in the current interface. */
    @JvmStatic
    fun pageTitle(): String =
        if (Skin.imperial()) "BATTLE STATIONS" else if (Skin.collective()) "SUBUNIT FUNCTIONS" else "BRIDGE STATIONS"

    @JvmStatic
    fun entryName(): String = if (Skin.collective()) "SUBUNITS" else "STATIONS"

    private fun n(command: String, imperial: String, collective: String): String =
        if (Skin.imperial()) imperial else if (Skin.collective()) collective else command

    /** The six consoles, named for the current interface. */
    @JvmStatic
    fun build(c: Context, p: Palette, host: Host): MutableList<Station> {
        val out = ArrayList<Station>()
        out.add(Station(n("OPS", "SYSTEMS", "CORE NEXUS"), "computer", p.c(0), Console(c, p, host, Ops(c, p))))
        out.add(Station(n("CONN", "HELM", "CONDUIT DRIVE"), "maps", p.c(1), Console(c, p, host, Conn(c, p))))
        out.add(Station(n("TACTICAL", "WEAPONS", "DEFENSE GRID"), "alert", p.c(3), Console(c, p, host, Tactical(c, p))))
        out.add(Station(n("ENGINEERING", "ENGINEERING", "POWER NODES"), "settings", p.c(2), Console(c, p, host, Engineering(c, p))))
        out.add(Station(n("SCIENCE", "SENSORS", "SENSOR ARRAY"), "search", p.c(4), Console(c, p, host, Science(c, p))))
        out.add(Station(n("MEDICAL", "MEDICAL", "REGENERATION"), "fitness", p.c(5), Console(c, p, host, Medical(c, p))))
        return out
    }

    // ---- sounds by role ---------------------------------------------------------------------------------

    /** keywords (matched against imported clip names), then bundled fallbacks per faction. */
    private fun keywords(role: String): Array<String> = when (role) {
        "beam" -> arrayOf("PULSE", "BEAM", "LASER", "DISRUPTOR")
        "torpedo" -> arrayOf("TORPEDO")
        "shields" -> arrayOf("SHIELDS UP", "SHIELD", "FORCEFIELD", "FORCE FIELD")
        "red_alert" -> arrayOf("RED ALERT")
        "warp" -> arrayOf("WARP ENGAGE", "JUMP TO WARP", "GO TO WARP", "WARP")
        "impulse" -> arrayOf("IMPULSE")
        "all_stop" -> arrayOf("ALL STOP", "WARP DOWN", "POWER DOWN")
        "course" -> arrayOf("HELM", "CONSOLE", "COMPUTER BEEP")
        "scan" -> arrayOf("SENSOR", "SCAN", "SCIENCE")
        "probe" -> arrayOf("PROBE", "LAUNCH")
        "scanner" -> arrayOf("MEDICAL SCAN", "SCANNER", "BIO SCAN", "SCAN BED")
        "injector" -> arrayOf("INJECTOR", "INJECT")
        "eject" -> arrayOf("EJECT", "CORE EJECT")
        "core" -> arrayOf("WARP CORE", "ENGINE ROOM", "ENGINEERING")
        "authorize" -> arrayOf("AUTHORIZ", "COMMAND CODE")
        "reroute" -> arrayOf("POWER TRANSFER", "COMPUTER", "PANEL")
        "transporter" -> arrayOf("TRANSPORTER", "ENERGIZE", "BEAM")
        "hail" -> arrayOf("HAILING", "HAIL")
        "alert" -> arrayOf("ALERT")
        "tractor" -> arrayOf("TRACTOR")
        "warp9" -> arrayOf("WARP FLASH", "WARP")
        "cloak" -> arrayOf("CLOAK", "POWER LOSS")
        "regen" -> arrayOf("REGENERATION")
        "comm" -> arrayOf("CHIRP", "COMMUNICATOR")
        "boot_start" -> when {
            Skin.imperial() -> arrayOf("IMPERIAL BRIDGE", "IMPERIAL")
            Skin.collective() -> arrayOf("COLLECTIVE VESSEL", "COLLECTIVE")
            else -> emptyArray()
        }
        "boot_ready" -> when {
            Skin.imperial() -> arrayOf("IMPERIAL ALERT", "BATTLE STATIONS")
            Skin.collective() -> arrayOf("COLLECTIVE COMPUTER", "COLLECTIVE")
            else -> emptyArray()
        }
        else -> emptyArray()
    }

    /** Bundled clip used when nothing better is available for a role. */
    private fun fallback(role: String): String {
        val k = Skin.imperial()
        val b = Skin.collective()
        return when (role) {
            "shields" -> "alt_forcefield_touch"
            "red_alert" -> "alert_red_tng"
            "alert" -> "alert_warning"
            "warp" -> if (b) "alt_borg_computer" else "ui_screen_open"
            "impulse" -> "ui_panel_open"
            "all_stop" -> "ui_screen_close"
            "scan", "scanner" -> if (b) "alt_borg_computer" else "system_transfer_complete"
            "eject" -> "voice_decoupling_sequence"
            "authorize" -> "voice_command_codes_verified"
            "transporter" -> if (k) "alt_klingon_transporter" else "tng_transporter"
            "hail" -> "comms_hailing_open"
            "beam", "torpedo", "probe" -> "alert_general_short"
            "injector", "reroute", "course", "core" -> "ui_button_tap"
            "boot_start" -> if (b) "alt_borg_computer" else "ui_screen_open"
            "boot_mid" -> "none"
            "tractor", "warp9" -> if (b) "alt_borg_computer" else "ui_screen_open"
            "cloak" -> "ui_screen_close"
            "regen" -> "alt_borg_computer"
            "comm" -> "ui_button_tap"
            "boot_ready" -> if (b) "system_transfer_complete" else "alert_general_short"
            else -> "ui_button_tap"
        }
    }

    /**
     * Hand-picked clips from the downloadable source library for each role and interface, best first
     * (used when present in the build; otherwise the keyword search and bundled fallbacks apply).
     */
    private fun picks(role: String): Array<String> {
        val k = Skin.imperial()
        val b = Skin.collective()
        return when (role) {
            "beam" -> arrayOf(if (k) "tk_wpn_disruptor" else if (b) "tk_wpn_weapons_2" else "tk_wpn_phaser_1")
            "tractor" -> arrayOf("tk_misc_tractor_beam_1")
            "torpedo" -> arrayOf("tk_wpn_torpedo_1")
            "shields" -> arrayOf(if (b) "alt_forcefield_touch" else "tk_misc_powering_up")
            "red_alert" -> arrayOf(if (b) "tk_cvox_tactical_alert_vessel_approaching" else "tk_alrt_red_alert_3")
            "warp" -> arrayOf(if (b) "tk_warp_warp_flash" else "tk_warp_warp_2")
            "warp9" -> arrayOf("tk_warp_warp_flash")
            "impulse" -> arrayOf("tk_amb_engine_2")
            "all_stop" -> arrayOf("tk_warp_warp_out_1")
            "course" -> arrayOf("tk_cvox_helm_2")
            "cloak" -> arrayOf("tk_misc_power_loss")
            "scan" -> arrayOf(if (b) "tk_calrt_alert_26_sensors" else "tk_cvox_sensors")
            "probe" -> arrayOf("tk_wpn_torpedo_3")
            "scanner" -> arrayOf(if (b) "tk_cvox_diagnostic_complete" else "tk_tric_tricorder_5")
            "injector" -> arrayOf("tk_door_swoosh")
            "regen" -> arrayOf("tk_cvox_regeneration_cycle_complete")
            "eject" -> arrayOf("tk_cvox_initiating_decoupling_sequence")
            "core" -> arrayOf("tk_misc_engineering_device_3")
            "authorize" -> arrayOf(if (b) "tk_cvox_command_codes_verified" else "tk_cvox_security_authorization_accepted")
            "reroute" -> arrayOf("tk_cui_input_ok_1")
            "transporter" -> arrayOf(if (k) "alt_klingon_transporter" else if (b) "tk_trn_transporter_2" else "tk_trn_transporter_5")
            "hail" -> arrayOf(
                if (k) "tk_ccomm_hail_alert_1" else if (b) "tk_ccomm_communications_star_trransmission" else "tk_ccomm_hailing_frequencies_open_1",
            )
            "comm" -> arrayOf(if (b) "tk_ccomm_incoming_transmission" else "tk_comm_chirp_1")
            "alert" -> arrayOf(if (b) "alt_borg_computer" else "tk_calrt_damage_alarm")
            "boot_start" -> arrayOf(if (k) "tk_wpn_disruptor" else "tk_misc_powering_up")
            "boot_mid" -> arrayOf(if (k) "tk_misc_powering_up" else "tk_cui_computer_beep_sequence_1")
            "boot_ready" -> arrayOf(if (k) "tk_wpn_weapons_2" else "tk_cvox_regeneration_cycle_complete")
            else -> emptyArray()
        }
    }

    /** Clip for a role: the owner's choice, a hand-picked clip, an imported match, then the fallback. */
    @JvmStatic
    fun clipFor(c: Context, role: String): String {
        val own = Prefs.get(c).s("stnSnd_$role", null)
        if (own != null) return own
        for (pk in picks(role)) if (Sfx.available(c, pk)) return pk // downloaded, bundled or synthesized
        val imported = SoundLibrary.imported(c)
        for (kw in keywords(role)) {
            for (e in imported) {
                if (e.label.uppercase(Locale.US).contains(kw)) return e.id
            }
        }
        return fallback(role)
    }

    /** Plays the clip for a role ("none" plays nothing). */
    @JvmStatic
    fun play(c: Context, role: String) {
        var clip = clipFor(c, role)
        // library not downloaded: start-up still sounds, with the interface's synthesized power-up
        if (role == "boot_start" && clip != "none" && !Sfx.available(c, clip)) clip = Sfx.synthBootFor(Skin.faction)
        if (clip != "none") Sfx.playClip(c, clip, false)
    }

    // ---- console shell: title, display, controls ------------------------------------------------------

    /** A control on a console: label, sound role, and what it does to the display. */
    class Control(val label: String, val role: String, val action: () -> Unit)

    /** Base for a station display: a canvas view that animates while it is on screen. */
    abstract class Sim(c: Context, protected val p: Palette) : View(c), HomeGestureDetector.OwnsTouches {
        protected val d: Float = c.resources.displayMetrics.density
        protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        protected val line = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        protected val r = RectF()
        protected val path = Path()
        protected val rnd = Random()
        private var last = 0L
        private var status = ""

        init {
            line.style = Paint.Style.STROKE
            textPaint.typeface = Ui.font(c, false)
        }

        abstract fun title(): String

        abstract fun controls(): List<Control>

        /** Advance the simulation by dt seconds. */
        open fun step(dt: Float) {}

        abstract fun paint(cv: Canvas, w: Float, h: Float)

        protected fun dp(v: Float): Float = v * d

        protected fun col(i: Int): Int = if (Ui.cloak) Ui.alpha(p.c(i), 0.6f) else Skin.glow(p.c(i))

        protected fun say(s: String) {
            status = s
            invalidate()
        }

        protected fun text(cv: Canvas, s: String, x: Float, y: Float, sp: Float, color: Int, a: Paint.Align, heavy: Boolean) {
            textPaint.typeface = Ui.font(context, heavy)
            textPaint.textSize = Ui.px(context, sp)
            textPaint.textAlign = a
            textPaint.color = color
            cv.drawText(s, x, y, textPaint)
        }

        /** The display's frame, in the interface's style. */
        private fun frame(cv: Canvas, w: Float, h: Float) {
            val m = dp(2f)
            if (Skin.imperial()) {
                val o = ImperialDraw.octagon(Path(), m, m, w - m, h - m, dp(18f), true, true)
                val inner = ImperialDraw.octagon(Path(), m + dp(4f), m + dp(4f), w - m - dp(4f), h - m - dp(4f), dp(16f), true, true)
                cv.save()
                cv.clipPath(inner)
                r.set(0f, 0f, w, h)
                ImperialDraw.grid(cv, r, dp(22f), ImperialDraw.RED, 0.1f, d)
                cv.restore()
                ImperialDraw.frame(cv, o, inner, ImperialDraw.RED, d, 0.8f)
            } else if (Skin.collective()) {
                line.strokeWidth = dp(1.2f)
                line.color = Ui.alpha(p.c(0), 0.45f)
                r.set(m, m, w - m, h - m)
                cv.drawRoundRect(r, dp(18f), dp(18f), line)
                CollectiveDraw.node(cv, dp(14f), dp(14f), dp(5f), p.c(0), d, 0.9f)
                CollectiveDraw.via(cv, w - dp(14f), h - dp(14f), dp(3f), p.c(0), d, 0.9f)
            } else {
                line.strokeWidth = dp(2f)
                line.color = p.c(1)
                r.set(m, m, w - m, h - m)
                cv.drawRoundRect(r, dp(16f), dp(16f), line)
                fill.color = p.c(0)
                r.set(dp(18f), 0f, dp(58f), dp(6f))
                cv.drawRoundRect(r, dp(3f), dp(3f), fill)
                fill.color = p.c(3)
                r.set(dp(64f), 0f, dp(84f), dp(6f))
                cv.drawRoundRect(r, dp(3f), dp(3f), fill)
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            last = SystemClock.uptimeMillis()
        }

        override fun onDraw(cv: Canvas) {
            val now = SystemClock.uptimeMillis()
            val dt = min(0.1f, (now - last) / 1000f)
            last = now
            val animate = Ui.animate(context)
            step(if (animate) dt else 0.05f)
            val w = width.toFloat()
            val h = height.toFloat()
            frame(cv, w, h)
            paint(cv, w, h)
            text(cv, "SIMULATION", w - dp(14f), h - dp(10f), 9f, Ui.alpha(p.textLo, 0.6f), Paint.Align.RIGHT, false)
            if (status.isNotEmpty()) text(cv, status, dp(16f), h - dp(10f), 11f, p.textHi, Paint.Align.LEFT, true)
            if (animate && isShown) postInvalidateOnAnimation()
        }
    }

    /** Title, display and a grid of controls; long-press a control to choose its sound. */
    class Console(c: Context, p: Palette, host: Host, sim: Sim) : LinearLayout(c) {
        init {
            orientation = VERTICAL
            val m = ScreenMetrics.of(c)
            val t = Ui.bold(c, sim.title(), 18f, p.c(0))
            t.setPadding(Ui.dp(c, Ui.S.toFloat()), 0, 0, Ui.dp(c, Ui.XS.toFloat()))
            addView(t, LayoutParams(-1, -2))
            val simDp = max(SIM_MIN_HEIGHT_DP, min(SIM_HEIGHT_DP * m.uiScale, m.heightDp * SIM_HEIGHT_SHARE))
            addView(sim, LayoutParams(-1, m.px(simDp)))
            val perRow = if (m.isTwoPane) 3 else 2
            var row: LinearLayout? = null
            for ((k, ctl) in sim.controls().withIndex()) {
                val col = k % perRow
                if (col == 0) {
                    row = LinearLayout(c)
                    val rl = LayoutParams(-1, -2)
                    rl.topMargin = Ui.dp(c, Ui.GUTTER.toFloat())
                    addView(row, rl)
                }
                val color = p.c(CONTROL_COLORS[k % 6])
                val shape = if (col == 0) Ui.LEFT else if (col == perRow - 1) Ui.RIGHT else Ui.SQUARE
                val b = Ui.button(c, ctl.label, color, shape, null)
                b.setOnClickListener { v ->
                    // the control's own sound replaces the usual key tap
                    if (Prefs.get(c).haptics()) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    play(c, ctl.role)
                    ctl.action()
                    sim.invalidate()
                }
                b.textSize = 13 * Skin.textScale()
                b.setOnLongClickListener { v ->
                    Ui.longPress(v)
                    host.assignSound(ctl.role, ctl.label)
                    true
                }
                val bl = LayoutParams(0, Ui.dp(c, CONTROL_DP), 1f)
                if (col > 0) bl.leftMargin = Ui.dp(c, Ui.GUTTER.toFloat())
                row!!.addView(b, bl)
            }
            val hint = Ui.text(c, "LONG-PRESS A CONTROL TO CHOOSE ITS SOUND", 10f, Ui.alpha(p.textLo, 0.7f))
            hint.gravity = Gravity.CENTER
            hint.setPadding(0, Ui.dp(c, Ui.S.toFloat()), 0, 0)
            addView(hint, LayoutParams(-1, -2))
        }

        private companion object {
            /** Accent slot of each control, in order. */
            val CONTROL_COLORS = intArrayOf(0, 1, 3, 2, 4, 5)
        }
    }

    // ---- OPS: power distribution --------------------------------------------------------------------------

    class Ops(c: Context, p: Palette) : Sim(c, p) {
        private val sys = arrayOf("WARP DRIVE", "IMPULSE", "SHIELDS", "SENSORS", "LIFE SUPPORT", "WEAPONS")
        private val target = floatArrayOf(22f, 12f, 18f, 14f, 20f, 14f)
        private val shown = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        private var shimmer = 0f

        init {
            if (Skin.collective()) {
                sys[0] = "CONDUIT DRIVE"
                sys[4] = "CHAMBERS"
                sys[5] = "CUTTING BEAMS"
            }
            if (Skin.imperial()) {
                sys[5] = "DISRUPTORS"
                sys[2] = "DEFLECTORS"
            }
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_UP) {
                    val i = ((e.y - dp(30f)) / rowH()).toInt()
                    if (i >= 0 && i < sys.size) boost(i)
                }
                true
            }
        }

        private fun rowH(): Float = (height - dp(56f)) / sys.size

        private fun boost(i: Int) {
            redistribute(target, i)
            play(context, "reroute")
            say("POWER REROUTED TO " + sys[i])
        }

        override fun title(): String =
            if (Skin.collective()) "CORE NEXUS • POWER DISTRIBUTION"
            else if (Skin.imperial()) "SYSTEMS • POWER DISTRIBUTION"
            else "OPS • POWER DISTRIBUTION"

        override fun controls(): List<Control> {
            val c = ArrayList<Control>()
            c.add(Control("BALANCE", "reroute") {
                for (k in sys.indices) target[k] = 100f / sys.size
                say("POWER EQUALISED")
            })
            c.add(Control(if (Skin.collective()) "ADAPT" else "EMERGENCY POWER", "alert") {
                val t = floatArrayOf(6f, 6f, 34f, 8f, 40f, 6f)
                t.copyInto(target)
                say(if (Skin.collective()) "ADAPTING" else "EMERGENCY POWER TO SHIELDS AND LIFE SUPPORT")
            })
            c.add(Control(if (Skin.collective()) "INTEGRATE" else "ENERGIZE", "transporter") {
                shimmer = 2.2f
                say(if (Skin.collective()) "TRANSPORTING UNITS" else "TRANSPORTER: ENERGIZING")
            })
            c.add(Control(if (Skin.collective()) "LINK" else "HAIL", "hail") {
                say(if (Skin.collective()) "SUBSPACE LINK ESTABLISHED" else "HAILING FREQUENCIES OPEN")
            })
            return c
        }

        override fun step(dt: Float) {
            for (k in sys.indices) shown[k] += (target[k] - shown[k]) * min(1f, dt * 5)
            shimmer = max(0f, shimmer - dt)
        }

        override fun paint(cv: Canvas, w: Float, h: Float) {
            text(cv, "TAP A SYSTEM TO REROUTE POWER", dp(16f), dp(22f), 10f, Ui.alpha(p.textLo, 0.8f), Paint.Align.LEFT, false)
            val rh = rowH()
            val x0 = dp(110f)
            val x1 = w - dp(54f)
            for (k in sys.indices) {
                val y = dp(30f) + k * rh
                val bh = min(rh * 0.55f, dp(14f))
                val cy = y + rh / 2f
                text(cv, sys[k], dp(16f), cy + dp(4f), 10f, p.textHi, Paint.Align.LEFT, false)
                val len = min((x1 - x0) * shown[k] / 50f, x1 - x0)
                val c = col(k % 6)
                fill.color = Ui.alpha(c, 0.18f)
                r.set(x0, cy - bh / 2, x1, cy + bh / 2)
                cv.drawRect(r, fill)
                fill.color = c
                if (Skin.imperial()) {
                    // segmented in triangles
                    val s = bh * 1.1f
                    var x = x0
                    while (x + s / 2 < x0 + len) {
                        ImperialDraw.tri(cv, x + s / 2, cy, s * 0.92f, bh, ((x - x0) / (s / 2)).toInt() % 2 == 0, c, 1f)
                        x += s / 2
                    }
                } else if (Skin.collective()) {
                    var x = x0 + dp(4f)
                    while (x < x0 + len) {
                        cv.drawCircle(x, cy, bh * 0.35f, fill)
                        x += dp(9f)
                    }
                } else {
                    r.set(x0, cy - bh / 2, x0 + len, cy + bh / 2)
                    cv.drawRoundRect(r, bh / 2, bh / 2, fill)
                }
                text(cv, "${Math.round(shown[k])}%", w - dp(16f), cy + dp(4f), 11f, c, Paint.Align.RIGHT, true)
            }
            if (shimmer > 0) {
                // transporter: sparkling column in the middle of the display
                val a = min(1f, shimmer)
                for (i in 0 until 40) {
                    val sparkle = if (Skin.collective()) Skin.collectiveLit() else if (Skin.imperial()) ImperialDraw.ORANGE else 0xFFCFE3FF.toInt()
                    fill.color = Ui.alpha(sparkle, a * rnd.nextFloat())
                    cv.drawCircle(w / 2f + (rnd.nextFloat() - 0.5f) * dp(40f), dp(30f) + rnd.nextFloat() * (h - dp(60f)), dp(1.5f), fill)
                }
            }
        }

        companion object {
            /**
             * Moves up to 10 points of power to system i, taken evenly from the others (none below
             * 4); the shares always total 100.
             */
            @JvmStatic
            fun redistribute(target: FloatArray, i: Int) {
                val n = target.size
                val add = min(10f, 100 - target[i])
                val each = add / (n - 1)
                for (k in 0 until n) if (k != i) target[k] = max(4f, target[k] - each)
                var sum = 0f
                for (k in 0 until n) if (k != i) sum += target[k]
                target[i] = 100 - sum
            }
        }
    }

    // ---- CONN: course and speed ---------------------------------------------------------------------------------

    class Conn(c: Context, p: Palette) : Sim(c, p) {
        private val stars = Array(90) { FloatArray(3) }
        private var speed = 0.2f
        private var targetSpeed = 0.2f
        private var heading = 47
        private var mark = 12
        private var speedName = "IMPULSE"

        init {
            for (s in stars) reset(s, true)
        }

        private fun reset(s: FloatArray, anyZ: Boolean) {
            s[0] = (rnd.nextFloat() - 0.5f) * 2
            s[1] = (rnd.nextFloat() - 0.5f) * 2
            s[2] = if (anyZ) rnd.nextFloat() else 1f
        }

        override fun title(): String =
            if (Skin.collective()) "CONDUIT DRIVE • COURSE AND VELOCITY"
            else if (Skin.imperial()) "HELM • COURSE AND SPEED"
            else "CONN • COURSE AND SPEED"

        private fun setSpeed(s: Float, name: String) {
            targetSpeed = s
            speedName = name
            say(if (name == "ALL STOP") "ALL STOP" else "SPEED: $name")
        }

        override fun controls(): List<Control> {
            val c = ArrayList<Control>()
            c.add(Control("SET COURSE", "course") {
                heading = rnd.nextInt(360)
                mark = rnd.nextInt(90)
                say(String.format(Locale.US, "COURSE LAID IN: %03d MARK %03d", heading, mark))
            })
            c.add(Control("IMPULSE", "impulse") { setSpeed(0.25f, if (Skin.collective()) "SUBLIGHT" else "FULL IMPULSE") })
            c.add(Control(if (Skin.collective()) "CONDUIT DRIVE" else "WARP 6", "warp") {
                setSpeed(if (Skin.collective()) 4f else 1.6f, if (Skin.collective()) "CONDUIT OPEN" else "WARP 6")
            })
            c.add(Control(if (Skin.collective()) "FULL STOP" else "ALL STOP", "all_stop") { setSpeed(0f, "ALL STOP") })
            if (!Skin.collective()) c.add(Control("WARP 9", "warp9") { setSpeed(3f, "WARP 9") })
            if (!Skin.collective()) {
                c.add(Control(if (Skin.imperial()) "CLOAK" else "EVASIVE", if (Skin.imperial()) "cloak" else "course") {
                    heading = (heading + 90 + rnd.nextInt(180)) % 360
                    say(if (Skin.imperial()) "CLOAKING DEVICE ENGAGED" else "EVASIVE PATTERN DELTA")
                })
            }
            return c
        }

        override fun step(dt: Float) {
            speed += (targetSpeed - speed) * min(1f, dt * 1.6f)
            for (s in stars) {
                s[2] -= dt * (0.05f + speed * 0.6f)
                if (s[2] <= 0.02f) reset(s, false)
            }
        }

        override fun paint(cv: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val cy = h / 2f - dp(10f)
            val sc = min(w, h) * 0.45f
            cv.save()
            r.set(dp(8f), dp(8f), w - dp(8f), h - dp(28f))
            cv.clipRect(r)
            line.strokeCap = Paint.Cap.ROUND
            for (s in stars) {
                val x = cx + s[0] / s[2] * sc * 0.25f
                val y = cy + s[1] / s[2] * sc * 0.25f
                val z2 = s[2] + 0.02f + speed * 0.05f
                val x2 = cx + s[0] / z2 * sc * 0.25f
                val y2 = cy + s[1] / z2 * sc * 0.25f
                val b = min(1f, (1 - s[2]) * 1.4f)
                line.strokeWidth = dp(1 + (1 - s[2]) * 1.5f)
                val star = if (Skin.collective()) Skin.collectiveTheme.c[1] else if (Skin.imperial()) ImperialDraw.YELLOW else 0xFFE6DEFF.toInt()
                line.color = Ui.alpha(star, b)
                cv.drawLine(x2, y2, x, y, line)
            }
            cv.restore()
            line.strokeCap = Paint.Cap.BUTT
            text(cv, String.format(Locale.US, "HEADING %03d MARK %03d", heading, mark), dp(16f), dp(24f), 13f, col(0), Paint.Align.LEFT, true)
            text(cv, speedName, w - dp(16f), dp(24f), 13f, col(1), Paint.Align.RIGHT, true)
        }
    }

    // ---- TACTICAL: shields, weapons, alert -------------------------------------------------------------------

    class Tactical(c: Context, p: Palette) : Sim(c, p) {
        private var t = 0f
        private var beam = 0f
        private var torp = -1f
        private var hit = 0f
        private var alertT = 0f
        private var shields = false
        private var red = false

        override fun title(): String =
            if (Skin.collective()) "DEFENSE GRID • TARGETING"
            else if (Skin.imperial()) "WEAPONS • TARGETING"
            else "TACTICAL • TARGETING"

        private fun tx(): Float = width * (0.5f + 0.32f * sin(t * 0.7f))

        private fun ty(): Float = height * (0.38f + 0.12f * sin(t * 1.3f))

        override fun controls(): List<Control> {
            val c = ArrayList<Control>()
            c.add(Control(if (Skin.collective()) "ADAPT SHIELDS" else "SHIELDS", "shields") {
                shields = !shields
                say(if (shields) (if (Skin.collective()) "SHIELDS ADAPTED" else "SHIELDS UP") else "SHIELDS DOWN")
            })
            c.add(Control(if (Skin.collective()) "CUTTING BEAM" else if (Skin.imperial()) "DISRUPTORS" else "PULSE BEAMS", "beam") {
                beam = 0.6f
                hit = 0.5f
                say(if (Skin.collective()) "CUTTING BEAM ENGAGED" else if (Skin.imperial()) "DISRUPTORS FIRING" else "FIRING PULSE BEAMS")
            })
            c.add(Control(if (Skin.collective()) "TRACTOR BEAM" else "TORPEDO", if (Skin.collective()) "tractor" else "torpedo") {
                if (Skin.collective()) {
                    beam = 1.4f
                    say("TRACTOR BEAM LOCKED")
                } else {
                    torp = 0f
                    say("TORPEDO AWAY")
                }
            })
            c.add(Control(if (Skin.collective()) "ALERT" else "RED ALERT", "red_alert") {
                red = !red
                say(if (red) (if (Skin.collective()) "THREAT DETECTED" else "RED ALERT") else "CONDITION GREEN")
            })
            return c
        }

        override fun step(dt: Float) {
            t += dt
            beam = max(0f, beam - dt)
            hit = max(0f, hit - dt)
            alertT += dt
            if (torp >= 0) {
                torp += dt * 1.4f
                if (torp >= 1) {
                    torp = -1f
                    hit = 0.6f
                }
            }
        }

        override fun paint(cv: Canvas, w: Float, h: Float) {
            val sx = w / 2f
            val sy = h - dp(40f)
            val x = tx()
            val y = ty()
            // own ship and shield bubble
            if (shields) {
                line.strokeWidth = dp(2f)
                line.color = Ui.alpha(col(1), 0.5f + 0.3f * sin(t * 4))
                if (Skin.imperial()) {
                    cv.drawPath(ImperialDraw.octagon(Path(), sx - dp(36f), sy - dp(22f), sx + dp(36f), sy + dp(14f), dp(10f), true, true), line)
                } else {
                    r.set(sx - dp(36f), sy - dp(22f), sx + dp(36f), sy + dp(14f))
                    cv.drawOval(r, line)
                }
            }
            fill.color = col(0)
            if (Skin.collective()) {
                r.set(sx - dp(9f), sy - dp(9f), sx + dp(9f), sy + dp(9f))
                cv.drawRect(r, fill)
            } else if (Skin.imperial()) {
                ImperialDraw.tri(cv, sx, sy, dp(26f), dp(14f), false, col(0), 1f)
            } else {
                r.set(sx - dp(16f), sy - dp(5f), sx + dp(16f), sy + dp(5f))
                cv.drawOval(r, fill)
            }
            // target and reticle
            val rr = dp(26f)
            line.strokeWidth = dp(1.5f)
            line.color = col(3)
            if (Skin.imperial()) {
                cv.drawPath(ImperialDraw.octagon(Path(), x - rr, y - rr, x + rr, y + rr, rr * 0.4f, true, true), line)
            } else if (Skin.collective()) {
                CollectiveDraw.ringNode(cv, x, y, rr, col(3), d, 1f)
            } else {
                cv.drawCircle(x, y, rr, line)
                cv.drawLine(x - rr * 1.5f, y, x - rr * 0.5f, y, line)
                cv.drawLine(x + rr * 0.5f, y, x + rr * 1.5f, y, line)
                cv.drawLine(x, y - rr * 1.5f, x, y - rr * 0.5f, line)
                cv.drawLine(x, y + rr * 0.5f, x, y + rr * 1.5f, line)
            }
            fill.color = Ui.alpha(col(4), 0.9f)
            cv.drawCircle(x, y, dp(5f), fill)
            if (beam > 0) {
                line.strokeWidth = dp(3f)
                line.color = if (Skin.collective()) 0xFF79C98B.toInt() else if (Skin.imperial()) 0xFF4CE05A.toInt() else 0xFFFF9C00.toInt()
                cv.drawLine(sx, sy - dp(6f), x, y, line)
            }
            if (torp >= 0) {
                val px = sx + (x - sx) * torp
                val py = sy + (y - sy) * torp
                fill.color = if (Skin.imperial()) 0xFF4CE05A.toInt() else 0xFFFF5040.toInt()
                cv.drawCircle(px, py, dp(5f), fill)
                fill.color = Ui.alpha(Color.WHITE, 0.6f)
                cv.drawCircle(px, py, dp(2.5f), fill)
            }
            if (hit > 0) {
                fill.color = Ui.alpha(0xFFFFE08A.toInt(), hit)
                cv.drawCircle(x, y, dp(10f) + (0.6f - hit) * dp(40f), fill)
            }
            if (red && (alertT * 2).toInt() % 2 == 0) {
                line.strokeWidth = dp(5f)
                line.color = Ui.alpha(0xFFFF2020.toInt(), 0.7f)
                r.set(dp(6f), dp(6f), w - dp(6f), h - dp(6f))
                cv.drawRoundRect(r, dp(14f), dp(14f), line)
                text(cv, if (Skin.collective()) "THREAT" else "RED ALERT", w / 2f, dp(28f), 16f, 0xFFFF4040.toInt(), Paint.Align.CENTER, true)
            }
            text(
                cv, String.format(Locale.US, "TARGET %03d.%d", (x / w * 360).toInt(), (y / h * 9).toInt()),
                dp(16f), dp(24f), 11f, col(2), Paint.Align.LEFT, true,
            )
        }
    }

    // ---- ENGINEERING: warp core ------------------------------------------------------------------------------------

    class Engineering(c: Context, p: Palette) : Sim(c, p) {
        private var output = 0.6f
        private var phase = 0f
        private var eject = -1f
        private var armed = 0f

        override fun title(): String =
            if (Skin.collective()) "POWER NODES • CENTRAL PLEXUS"
            else if (Skin.imperial()) "ENGINEERING • WARP CORE"
            else "ENGINEERING • WARP CORE"

        override fun controls(): List<Control> {
            val c = ArrayList<Control>()
            c.add(Control("INCREASE OUTPUT", "core") {
                output = min(1f, output + 0.15f)
                say(String.format(Locale.US, "OUTPUT %d%%", Math.round(output * 100)))
            })
            c.add(Control("DECREASE OUTPUT", "core") {
                output = max(0.1f, output - 0.15f)
                say(String.format(Locale.US, "OUTPUT %d%%", Math.round(output * 100)))
            })
            c.add(Control("AUTHORIZE", "authorize") { say("COMMAND CODES VERIFIED") })
            c.add(Control(if (Skin.collective()) "SEVER NODE" else "EJECT CORE", "eject") {
                if (eject < 0) {
                    if (armed <= 0) {
                        armed = 3f
                        say("PRESS AGAIN TO CONFIRM")
                    } else {
                        armed = 0f
                        eject = 0f
                        say(if (Skin.collective()) "NODE SEVERED" else "WARP CORE EJECTED")
                    }
                }
            })
            return c
        }

        override fun step(dt: Float) {
            phase += dt * (0.6f + output * 2.4f)
            armed = max(0f, armed - dt)
            if (eject >= 0) {
                eject += dt
                if (eject > 5) {
                    eject = -1f
                    say(if (Skin.collective()) "NODE RESTORED" else "REPLACEMENT CORE ONLINE")
                }
            }
        }

        override fun paint(cv: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val top = dp(30f)
            val bot = h - dp(34f)
            val cw = dp(34f)
            val drop = if (eject >= 0) min(1f, eject / 1.2f) * (h + dp(40f)) else 0f
            if (eject >= 1.2f && eject < 5) {
                text(cv, if (Skin.collective()) "NODE OFFLINE" else "CORE EJECTED", cx, h / 2f, 18f, 0xFFFF4040.toInt(), Paint.Align.CENTER, true)
            } else {
                cv.save()
                cv.translate(0f, drop)
                val n = 9
                val sh = (bot - top) / n
                for (i in 0 until n) {
                    val y = top + i * sh
                    // pulses travel from both ends toward the reaction chamber in the middle
                    val dist = abs(i - (n - 1) / 2f) / ((n - 1) / 2f)
                    val glow = 0.35f + 0.65f * (0.5f + 0.5f * sin(phase * 6 + dist * 5)) * output
                    val c = if (Skin.collective()) Skin.collectiveLit() else if (Skin.imperial()) ImperialDraw.ORANGE else 0xFF6FA8FF.toInt()
                    if (Skin.collective()) {
                        CollectiveDraw.node(cv, cx, y + sh / 2, min(cw / 2, sh * 0.42f), c, d, glow)
                    } else if (Skin.imperial()) {
                        ImperialDraw.tri(cv, cx, y + sh / 2, cw, sh * 0.85f, i % 2 == 0, c, glow)
                    } else {
                        fill.color = Ui.alpha(c, glow)
                        r.set(cx - cw / 2, y + dp(2f), cx + cw / 2, y + sh - dp(2f))
                        cv.drawRoundRect(r, dp(6f), dp(6f), fill)
                    }
                }
                line.strokeWidth = dp(2f)
                line.color = col(1)
                r.set(cx - cw / 2 - dp(8f), top - dp(4f), cx + cw / 2 + dp(8f), bot + dp(4f))
                val corner = if (Skin.command()) dp(10f) else dp(2f)
                cv.drawRoundRect(r, corner, corner, line)
                cv.restore()
            }
            text(cv, String.format(Locale.US, "OUTPUT %d%%", Math.round(output * 100)), dp(16f), dp(24f), 12f, col(0), Paint.Align.LEFT, true)
            text(cv, if (Skin.collective()) "PLEXUS STABLE" else "M/AM RATIO 1:1", w - dp(16f), dp(24f), 12f, col(2), Paint.Align.RIGHT, true)
            text(
                cv, String.format(Locale.US, "%.1f", 1000 + output * 2400) + (if (Skin.collective()) " NODES" else " COCHRANES"),
                w - dp(16f), h / 2f, 11f, col(4), Paint.Align.RIGHT, false,
            )
            if (armed > 0) text(cv, "EJECTION ARMED", dp(16f), h / 2f, 12f, 0xFFFF4040.toInt(), Paint.Align.LEFT, true)
        }
    }

    // ---- SCIENCE: sensor sweep ------------------------------------------------------------------------------------

    class Science(c: Context, p: Palette) : Sim(c, p) {
        /** angle, distance (0..1), last-seen */
        private val blips = Array(7) { FloatArray(3) }
        private var sweep = 0f
        private var probe = -1f
        private var longRange = false

        init {
            for (b in blips) {
                b[0] = rnd.nextFloat() * 360
                b[1] = 0.2f + rnd.nextFloat() * 0.75f
                b[2] = -9f
            }
        }

        override fun title(): String =
            if (Skin.collective()) "SENSOR ARRAY • LONG RANGE"
            else if (Skin.imperial()) "SENSORS • SWEEP"
            else "SCIENCE • SENSOR SWEEP"

        override fun controls(): List<Control> {
            val c = ArrayList<Control>()
            c.add(Control(if (Skin.collective()) "SCAN SPECIES" else "SCAN CONTACT", "scan") {
                if (Skin.collective()) say("SPECIES " + (100 + rnd.nextInt(8900)) + " • INTEGRATION VIABLE")
                else say(FINDINGS[rnd.nextInt(FINDINGS.size)])
            })
            // the label is fixed when the console is built, so it names the toggle, not a state
            c.add(Control("SENSOR RANGE", "scan") {
                longRange = !longRange
                for (b in blips) {
                    b[0] = rnd.nextFloat() * 360
                    b[1] = 0.2f + rnd.nextFloat() * 0.75f
                }
                say(if (longRange) "LONG RANGE SENSORS" else "SHORT RANGE SENSORS")
            })
            c.add(Control("LAUNCH PROBE", "probe") {
                probe = 0f
                say("PROBE LAUNCHED")
            })
            c.add(Control("RECALIBRATE", "course") {
                sweep = 0f
                say("ARRAY RECALIBRATED")
            })
            return c
        }

        override fun step(dt: Float) {
            sweep = (sweep + dt * 120) % 360
            for (b in blips) {
                val diff = (sweep - b[0] + 360) % 360
                if (diff < 6) b[2] = 0f else b[2] += dt
            }
            if (probe >= 0) {
                probe += dt * 0.35f
                if (probe > 1) probe = -1f
            }
        }

        override fun paint(cv: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val cy = h / 2f - dp(6f)
            val radius = min(w, h) * 0.4f
            line.strokeWidth = dp(1f)
            for (i in 1..3) {
                line.color = Ui.alpha(col(1), 0.35f)
                if (Skin.imperial()) {
                    val rr = radius * i / 3f
                    path.reset()
                    for (k in 0 until 6) {
                        val a = Math.PI / 3 * k
                        val x = cx + cos(a).toFloat() * rr
                        val y = cy + sin(a).toFloat() * rr
                        if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    cv.drawPath(path, line)
                } else {
                    cv.drawCircle(cx, cy, radius * i / 3f, line)
                }
            }
            cv.drawLine(cx - radius, cy, cx + radius, cy, line)
            cv.drawLine(cx, cy - radius, cx, cy + radius, line)
            // sweep line with trailing fade
            for (k in 0 until 18) {
                val a = Math.toRadians((sweep - k * 2).toDouble())
                line.strokeWidth = dp(if (k == 0) 2f else 1.5f)
                line.color = Ui.alpha(col(0), (1 - k / 18f) * 0.7f)
                cv.drawLine(cx, cy, cx + cos(a).toFloat() * radius, cy + sin(a).toFloat() * radius, line)
            }
            for (b in blips) {
                val a = max(0f, 1 - b[2] / 3f)
                if (a <= 0) continue
                val ang = Math.toRadians(b[0].toDouble())
                val x = cx + cos(ang).toFloat() * radius * b[1]
                val y = cy + sin(ang).toFloat() * radius * b[1]
                if (Skin.collective()) {
                    CollectiveDraw.node(cv, x, y, dp(4f), col(3), d, a)
                } else if (Skin.imperial()) {
                    ImperialDraw.tri(cv, x, y, dp(9f), dp(8f), true, col(3), a)
                } else {
                    fill.color = Ui.alpha(col(3), a)
                    cv.drawCircle(x, y, dp(4f), fill)
                }
            }
            if (probe >= 0) {
                fill.color = col(2)
                cv.drawCircle(cx + radius * probe * 0.9f, cy - radius * probe * 0.5f, dp(3f), fill)
            }
            text(cv, if (longRange) "RANGE 12 LY" else "RANGE 2.4 AU", dp(16f), dp(24f), 12f, col(2), Paint.Align.LEFT, true)
        }

        private companion object {
            val FINDINGS = arrayOf(
                "TEMPERATE PLANET • NITROGEN-OXYGEN ATMOSPHERE", "NEBULA • HIGH IONISATION", "SUBSPACE ANOMALY",
                "ASTEROID FIELD • CRYSTAL TRACES", "VESSEL • TRANSPONDER SILENT", "DERELICT • NO LIFE SIGNS", "COMET • ICE AND DUST",
            )
        }
    }

    // ---- MEDICAL: scan bed ---------------------------------------------------------------------------------------------

    class Medical(c: Context, p: Palette) : Sim(c, p) {
        private var bpm = 72f
        private var targetBpm = 72f
        private var t = 0f
        private var scan = -1f
        private val trace = FloatArray(160)
        private var head = 0
        private var beatClock = 0f

        override fun title(): String =
            if (Skin.collective()) "REGENERATION • CHAMBER 3 OF 5"
            else if (Skin.imperial()) "MEDICAL • SCAN BED"
            else "SICKBAY • SCAN BED"

        override fun controls(): List<Control> {
            val c = ArrayList<Control>()
            c.add(Control(if (Skin.collective()) "DIAGNOSTIC" else "MEDICAL SCAN", "scanner") {
                scan = 0f
                say(if (Skin.collective()) "CORTICAL NODE NOMINAL" else "VITAL SIGNS STABLE")
            })
            c.add(Control(if (Skin.collective()) "NANITES" else "INJECTOR", "injector") {
                targetBpm = (58 + rnd.nextInt(12)).toFloat()
                say(if (Skin.collective()) "NANITES DEPLOYED" else "ADMINISTERED 10 CC • PATIENT RESTING")
            })
            c.add(Control(if (Skin.collective()) "REGENERATE" else "STIMULANT", if (Skin.collective()) "regen" else "injector") {
                targetBpm = (96 + rnd.nextInt(20)).toFloat()
                say(if (Skin.collective()) "REGENERATION CYCLE INITIATED" else "STIMULANT • HEART RATE RISING")
            })
            c.add(Control(if (Skin.collective()) "ALERT COLLECTIVE" else "CALL DOCTOR", "comm") {
                say(
                    if (Skin.collective()) "THE COLLECTIVE IS AWARE"
                    else if (Skin.imperial()) "THE DOCTOR IS ON HIS WAY. TRY NOT TO DIE."
                    else "MEDICAL TEAM NOTIFIED",
                )
            })
            return c
        }

        override fun step(dt: Float) {
            t += dt
            bpm += (targetBpm - bpm) * min(1f, dt * 0.8f)
            val period = 60f / bpm
            val steps = max(1, Math.round(dt * 80))
            for (s in 0 until steps) {
                beatClock += dt / steps
                if (beatClock > period) beatClock -= period
                val ph = beatClock / period
                var v = 0f
                if (ph < 0.04f) v = ph / 0.04f * 0.15f
                else if (ph < 0.08f) v = -0.2f
                else if (ph < 0.11f) v = 1f
                else if (ph < 0.14f) v = -0.35f
                else if (ph > 0.3f && ph < 0.42f) v = 0.25f * sin((ph - 0.3f) / 0.12f * Math.PI).toFloat()
                trace[head] = v
                head = (head + 1) % trace.size
            }
            if (scan >= 0) {
                scan += dt * 0.6f
                if (scan > 1) scan = -1f
            }
        }

        override fun paint(cv: Canvas, w: Float, h: Float) {
            val x0 = dp(16f)
            val x1 = w - dp(110f)
            val mid = h * 0.42f
            val amp = h * 0.22f
            line.strokeWidth = dp(2f)
            line.color = col(1)
            path.reset()
            for (i in trace.indices) {
                val v = trace[(head + i) % trace.size]
                val x = x0 + (x1 - x0) * i / (trace.size - 1)
                val y = mid - v * amp
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            cv.drawPath(path, line)
            text(cv, Math.round(bpm).toString(), w - dp(16f), mid, 34f, col(0), Paint.Align.RIGHT, true)
            text(cv, if (Skin.collective()) "CYCLE" else "BPM", w - dp(16f), mid + dp(18f), 10f, col(0), Paint.Align.RIGHT, false)
            val vit = if (Skin.collective()) arrayOf("NANITES 98%", "CORTICAL 100%", "LINK STABLE")
            else arrayOf("RESP " + (12 + Math.round(bpm / 20)), "BP 120/80", "NEURAL NOMINAL")
            for (i in vit.indices) {
                text(cv, vit[i], x0 + i * (w - dp(32f)) / 3f, h - dp(34f), 11f, col(2 + i), Paint.Align.LEFT, true)
            }
            if (scan >= 0) {
                fill.color = Ui.alpha(col(4), 0.35f)
                val y = dp(10f) + (h - dp(40f)) * scan
                r.set(dp(10f), y, w - dp(10f), y + dp(4f))
                cv.drawRect(r, fill)
            }
        }
    }
}
