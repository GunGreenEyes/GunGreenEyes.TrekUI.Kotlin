package com.gungreeneyes.trekui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.max

/**
 * console controls for one Home Assistant entity, and what a home tile assigned to one does.
 *
 * [launch] is the tile / dock / segment tap: lights, switches, fans and input booleans
 * toggle, scenes and scripts activate, climate opens [ThermostatDialog] through
 * [HomeAssistant.Climate], and a tile assigned with "CONTROLS" opens [show].
 * [show] is the control panel: ON / OFF, an intensity slider and a spectrum row of colour
 * keys for lights that support them, ACTIVATE for scenes and scripts.
 *
 * All network work goes through [HomeAssistant.async] with the application context;
 * replies that arrive after the dialog closed are dropped. The panel is a [ConsoleDialog], so
 * it is styled for every interface and sized for phones through tablets in both orientations.
 */
internal class HomeAssistantDialog private constructor(
    private val c: Context,
    private val entityId: String,
) {
    private val app: Context = c.applicationContext
    private val pal: Palette = Prefs.get(c).palette()
    private var built: ConsoleDialog.Built? = null
    private lateinit var status: TextView
    private lateinit var line: TextView
    private lateinit var controls: LinearLayout
    private var entity: HomeAssistant.Entity? = null

    /** Receives the tile argument and caption picked in [pick]. */
    fun interface Picked {
        fun picked(arg: String, label: String)
    }

    // ---- control panel ------------------------------------------------------------------------------

    private fun open(title: String?) {
        val built = ConsoleDialog.base(c, c.getString(R.string.ha_dialog_title))
        this.built = built
        val b = built.body
        val name = Ui.oneLine(Ui.bold(c, title, 16f, pal.c(1)))
        name.gravity = Gravity.END
        b.addView(name)
        status = Ui.bold(c, c.getString(R.string.ha_contacting), 22f, pal.textHi)
        status.tag = "ha_status"
        b.addView(status)
        controls = LinearLayout(c)
        controls.orientation = LinearLayout.VERTICAL
        controls.setPadding(0, Ui.dp(c, Ui.M.toFloat()), 0, 0)
        b.addView(controls)
        line = Ui.text(c, "", 13f, pal.textLo)
        line.setPadding(0, Ui.dp(c, Ui.S.toFloat()), 0, Ui.dp(c, Ui.GUTTER.toFloat()))
        b.addView(line)
        ConsoleDialog.addOption(
            built,
            ConsoleDialog.option(c, c.getString(R.string.ha_close), pal.c(2)) {
                Sfx.play(c, Sfx.CLOSE)
                built.dialog.dismiss()
            },
        )
        built.dialog.show()
        Sfx.play(c, Sfx.OPEN)
        refresh(false)
    }

    private fun alive(): Boolean = built?.dialog?.isShowing == true

    /** Reads the entity and rebuilds the controls. */
    private fun refresh(quiet: Boolean) {
        HomeAssistant.async({ HomeAssistant.state(app, entityId) }) { e, error ->
            if (!alive()) return@async
            if (error != null) {
                status.text = c.getString(R.string.ha_link_failed)
                status.setTextColor(pal.alert)
                line.text = error
                if (!quiet) Sfx.play(c, Sfx.ERROR)
                return@async
            }
            entity = e
            render()
        }
    }

    /** Status text and the controls that suit the entity's domain. */
    private fun render() {
        val entity = entity!!
        val domain = entity.domain()
        if (!entity.available()) {
            status.text = c.getString(R.string.ha_unavailable)
            status.setTextColor(pal.alert)
        } else if ("scene" == domain || "script" == domain) {
            status.text = c.getString(R.string.ha_ready)
            status.setTextColor(pal.c(2))
        } else {
            val pct = entity.brightnessPercent()
            status.text =
                if (entity.on() && pct >= 0) {
                    c.getString(R.string.ha_status_level, pct)
                } else {
                    c.getString(R.string.ha_status, entity.state.uppercase(Locale.getDefault()))
                }
            status.setTextColor(if (entity.on()) pal.c(0) else pal.textLo)
        }
        controls.removeAllViews()
        if ("scene" == domain || "script" == domain) {
            controls.addView(
                key(c.getString(R.string.ha_activate), pal.c(0), "ha_activate") { send(HomeAssistant.primary(entityId)) },
                keyParams(-1, true),
            )
            return
        }
        val power = LinearLayout(c)
        power.addView(
            key(c.getString(R.string.ha_on), if (entity.on()) pal.c(0) else Ui.alpha(pal.c(0), 0.4f), "ha_on") {
                send(HomeAssistant.power(entityId, true))
            },
            keyParams(0, true),
        )
        power.addView(
            key(c.getString(R.string.ha_off), if (!entity.on()) pal.c(3) else Ui.alpha(pal.c(3), 0.4f), "ha_off") {
                send(HomeAssistant.power(entityId, false))
            },
            keyParams(0, false),
        )
        controls.addView(power)
        if ("light" != domain) return
        if (entity.supportsBrightness()) addIntensity(entity)
        if (entity.supportsColor()) addSpectrum()
    }

    /** INTENSITY slider (0..100 %), sent when the finger lifts. */
    private fun addIntensity(entity: HomeAssistant.Entity) {
        val row = LinearLayout(c)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(c, Ui.M.toFloat()), 0, 0)
        row.addView(Ui.bold(c, c.getString(R.string.ha_intensity), 13f, pal.c(4)), LinearLayout.LayoutParams(Ui.dp(c, 92f), -2))
        val bar = SeekBar(c)
        bar.tag = "ha_intensity"
        bar.max = 100
        bar.progress = max(0, if (entity.on()) entity.brightnessPercent() else 0)
        bar.progressTintList = ColorStateList.valueOf(pal.c(4))
        bar.thumbTintList = ColorStateList.valueOf(pal.textHi)
        bar.progressBackgroundTintList = ColorStateList.valueOf(Ui.alpha(pal.c(4), 0.3f))
        bar.contentDescription = c.getString(R.string.ha_intensity)
        bar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    if (user) line.text = c.getString(R.string.ha_status_level, p)
                }

                override fun onStartTrackingTouch(s: SeekBar) {
                    Sfx.play(c, Sfx.TAP_SOFT)
                }

                override fun onStopTrackingTouch(s: SeekBar) {
                    val p = s.progress
                    send(if (p == 0) HomeAssistant.power(entityId, false) else HomeAssistant.light(entityId, p, -1))
                }
            },
        )
        row.addView(bar, LinearLayout.LayoutParams(0, Ui.dp(c, 44f), 1f))
        controls.addView(row)
    }

    /** SPECTRUM: a wrapping row of colour keys. */
    private fun addSpectrum() {
        controls.addView(Ui.bold(c, c.getString(R.string.ha_spectrum), 13f, pal.c(5)))
        val perRow = if (ScreenMetrics.of(c).isTwoPane) SPECTRUM.size else SPECTRUM.size / 2
        var row: LinearLayout? = null
        for (i in SPECTRUM.indices) {
            if (i % perRow == 0) {
                row = LinearLayout(c)
                val lp = LinearLayout.LayoutParams(-1, Ui.dp(c, 40f))
                lp.topMargin = Ui.dp(c, Ui.GUTTER.toFloat())
                controls.addView(row, lp)
            }
            val rgb = SPECTRUM[i]
            val swatch = View(c)
            swatch.tag = "ha_color_$i"
            swatch.background = Ui.touch(Ui.shape(c, Color.rgb((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF), Ui.PILL, 0f))
            swatch.contentDescription = c.getString(R.string.ha_color_cd, String.format(Locale.US, "#%06X", rgb))
            swatch.setOnClickListener {
                Sfx.play(c, Sfx.TAP)
                send(HomeAssistant.light(entityId, -1, rgb))
            }
            val sl = LinearLayout.LayoutParams(0, -1, 1f)
            if (i % perRow != 0) sl.leftMargin = Ui.dp(c, Ui.GUTTER.toFloat())
            row!!.addView(swatch, sl)
        }
    }

    private fun key(label: String, color: Int, tag: String, action: Runnable): TextView {
        val k = Ui.button(c, label, color, Ui.PILL) {
            Sfx.play(c, Sfx.TAP)
            action.run()
        }
        k.tag = tag
        k.gravity = Gravity.CENTER
        Ui.setTextSp(k, 15f)
        return k
    }

    private fun keyParams(width: Int, first: Boolean): LinearLayout.LayoutParams {
        val lp =
            if (width == 0) {
                LinearLayout.LayoutParams(0, Ui.dp(c, KEY_DP), 1f)
            } else {
                LinearLayout.LayoutParams(-1, Ui.dp(c, KEY_DP))
            }
        if (!first) lp.leftMargin = Ui.dp(c, Ui.GUTTER.toFloat())
        return lp
    }

    /** Sends a call, reports the result and reads the entity back. */
    private fun send(call: HomeAssistant.Call?) {
        if (call == null) return
        line.setTextColor(pal.c(4))
        line.text = c.getString(R.string.ha_transmitting)
        HomeAssistant.async({ HomeAssistant.call(app, call) }) { _, error ->
            if (!alive()) {
                if (error == null) Sfx.play(app, Sfx.CONFIRM)
                return@async
            }
            if (error != null) {
                Sfx.play(c, Sfx.ERROR)
                line.setTextColor(pal.alert)
                line.text = c.getString(R.string.ha_unable, error)
                return@async
            }
            Sfx.play(c, Sfx.CONFIRM)
            line.setTextColor(pal.c(2))
            line.text = c.getString(R.string.ha_confirmed)
            refresh(true)
        }
    }

    companion object {
        /** Colour keys offered for RGB lights (warm white first). */
        @JvmField
        val SPECTRUM = intArrayOf(0xFFE2B0, 0xFF9C00, 0xFF2200, 0xFF33CC, 0x9966FF, 0x3366FF, 0x00CCFF, 0x33FF66)

        /** Height of the control keys, in dp. */
        private const val KEY_DP = 46f

        // ---- home tile tap -----------------------------------------------------------------------------

        /**
         * Runs a home tile, dock key or segment assigned to a Home Assistant entity (`arg` is the
         * entity_id, optionally with [HomeAssistant.PANEL_SUFFIX]).
         *
         * @param feedback view to give haptic feedback on success; may be null
         */
        @JvmStatic
        fun launch(c: Context, arg: String?, feedback: View?) {
            val id = HomeAssistant.entityOfArg(arg)
            val stored = HomeAssistant.find(c, id)
            if (!HomeAssistant.configured(c) || stored == null) {
                Sfx.play(c, Sfx.ERROR)
                Toast.makeText(c, c.getString(R.string.ha_toast_not_set_up), Toast.LENGTH_SHORT).show()
                return
            }
            val title = stored.title()
            if ("climate" == HomeAssistant.domainOf(id)) {
                ThermostatDialog.show(c, HomeAssistant.Climate(id), title)
                return
            }
            val call = HomeAssistant.primary(id)
            if (HomeAssistant.panelArg(arg) || call == null) {
                show(c, id, title)
                return
            }
            val app = c.applicationContext
            HomeAssistant.async({ HomeAssistant.call(app, call) }) { state, error ->
                if (error != null) {
                    Sfx.play(app, Sfx.ERROR)
                    Toast.makeText(app, app.getString(R.string.ha_unable, error), Toast.LENGTH_LONG).show()
                    return@async
                }
                val activated = "turn_on" == call.service && ("scene" == call.domain || "script" == call.domain)
                Sfx.play(app, if ("off" == state) Sfx.CLOSE else Sfx.CONFIRM)
                if (feedback != null) Ui.tap(feedback)
                val what = if (activated || state!!.isEmpty()) app.getString(R.string.ha_activated) else state.uppercase(Locale.getDefault())
                Toast.makeText(app, app.getString(R.string.ha_toast_state, title, what), Toast.LENGTH_SHORT).show()
            }
        }

        // ---- assigning ----------------------------------------------------------------------------------

        /**
         * The "HOME ASSISTANT…" step of ASSIGN console FUNCTION: lists the imported entities (lights also
         * get a "CONTROLS" entry that opens the panel instead of toggling). Without a set-up instance
         * it leads to CONFIG.
         */
        @JvmStatic
        fun pick(a: MainActivity, cb: Picked) {
            val entities = HomeAssistant.entities(a)
            if (!HomeAssistant.configured(a) || entities.isEmpty()) {
                a.toast(a.getString(R.string.ha_link_first))
                a.openConfig()
                return
            }
            val labels = ArrayList<String>()
            val args = ArrayList<String>()
            val captions = ArrayList<String>()
            for (e in entities) {
                labels.add(a.getString(R.string.ha_pick_toggle, e.title()))
                args.add(e.id)
                captions.add(e.title())
                if ("light" == e.domain()) {
                    labels.add(a.getString(R.string.ha_pick_controls, e.title()))
                    args.add(e.id + HomeAssistant.PANEL_SUFFIX)
                    captions.add(e.title())
                }
            }
            a.showing(
                ConsoleDialog.options(a, a.getString(R.string.ha_pick_title), labels.toTypedArray()) { which ->
                    cb.picked(args[which], captions[which])
                },
            )
        }

        // ---- CONFIG prompts -----------------------------------------------------------------------------

        /**
         * One-line prompt for the address (URL keyboard) or the token (hidden, no suggestions, never
         * pre-filled). Unlike [ConsoleDialog.prompt] it keeps the keyboard in normal case, since
         * both values are case-sensitive.
         */
        @JvmStatic
        fun prompt(c: Context, title: String?, initial: String?, hint: String?, secret: Boolean, cb: ConsoleDialog.OnText) {
            val p = Prefs.get(c).palette()
            val b = ConsoleDialog.base(c, title)
            val e = EditText(c)
            e.tag = if (secret) "ha_token_field" else "ha_url_field"
            e.setText(initial)
            e.hint = hint
            e.setSingleLine(true)
            val variation =
                if (secret) {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                } else {
                    InputType.TYPE_TEXT_VARIATION_URI
                }
            e.inputType = InputType.TYPE_CLASS_TEXT or variation
            e.imeOptions = EditorInfo.IME_ACTION_DONE
            e.importantForAutofill = if (secret) View.IMPORTANT_FOR_AUTOFILL_NO else View.IMPORTANT_FOR_AUTOFILL_AUTO
            e.setTextColor(p.textHi)
            e.setHintTextColor(Ui.alpha(p.textLo, 0.6f))
            e.typeface = Ui.font(c, false)
            Ui.setTextSp(e, 17f)
            e.background = Ui.outline(c, p.panel, p.c(2), 18f, 2f)
            e.setPadding(Ui.dp(c, Ui.L.toFloat()), Ui.dp(c, Ui.M.toFloat()), Ui.dp(c, Ui.L.toFloat()), Ui.dp(c, Ui.M.toFloat()))
            e.setSelection(e.text.length)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.bottomMargin = Ui.dp(c, Ui.M.toFloat())
            b.body.addView(e, lp)
            val ok = Runnable {
                b.dialog.dismiss()
                cb.text(e.text.toString().trim())
            }
            e.setOnEditorActionListener { _, _, _ ->
                ok.run()
                true
            }
            val row = LinearLayout(c)
            val cancel = ConsoleDialog.option(c, c.getString(R.string.note_cancel), p.c(3)) {
                Sfx.play(c, Sfx.CANCEL)
                b.dialog.dismiss()
            }
            val accept = ConsoleDialog.option(c, c.getString(R.string.ha_accept), p.c(0)) {
                Sfx.play(c, Sfx.CONFIRM)
                ok.run()
            }
            val h = LinearLayout.LayoutParams(0, -2, 1f)
            h.rightMargin = Ui.dp(c, Ui.S.toFloat())
            row.addView(cancel, h)
            row.addView(accept, LinearLayout.LayoutParams(0, -2, 1f))
            b.body.addView(row)
            b.dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
            )
            b.dialog.show()
            e.requestFocus()
        }

        /** Opens the control panel for an entity. */
        @JvmStatic
        fun show(c: Context, entityId: String, title: String?) {
            HomeAssistantDialog(c, entityId).open(title)
        }
    }
}
