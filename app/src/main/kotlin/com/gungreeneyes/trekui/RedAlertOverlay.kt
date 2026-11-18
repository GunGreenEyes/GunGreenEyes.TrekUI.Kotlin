package com.gungreeneyes.trekui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat

/**
 * The RED ALERT frame drawn over the home screen of every interface while [RedAlert] is on:
 *  - Command console: a red console frame (top and bottom bars joined by a left sidebar through
 *    rounded elbows) with the RED ALERT caption in a notch of the top bar;
 *  - Imperial: angular blades in the corners and chamfered bars in Imperial red;
 *  - Collective: red corner brackets and a threat grid along the edges.
 *
 * Everything pulses (unless animations are off) and the whole screen gets a faint red wash. The view
 * never blocks the phone: it is not clickable, so touches fall through to the home screen below; only
 * its MUTE and STAND DOWN buttons take taps.
 *
 * MainActivity adds one on top of each rebuilt view tree ([attach]); it listens to
 * [RedAlert] while attached and hides itself when the alert stands down.
 */
internal class RedAlertOverlay(c: Context) : FrameLayout(c), RedAlert.Listener {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val mute: TextView
    private val standDown: TextView
    private val controls: LinearLayout
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var lastCheck = 0L
    private var ticking = false

    private val frame: Runnable = Runnable {
        ticking = false
        if (visibility != View.VISIBLE || !isAttachedToWindow) return@Runnable
        val now = System.currentTimeMillis()
        if (now - lastCheck >= CHECK_MS) {
            lastCheck = now
            RedAlert.check(context) // may stand down → onRedAlertChanged hides us
            if (!RedAlert.active(context)) return@Runnable
        }
        invalidate()
        schedule(if (Ui.animate(context)) FRAME_MS else CHECK_MS)
    }

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        tag = "red_alert_overlay"
        id = R.id.red_alert_overlay
        text.typeface = Ui.font(c, true)
        text.textAlign = Paint.Align.LEFT

        val color = alarmColor()
        controls = LinearLayout(c)
        controls.orientation = LinearLayout.HORIZONTAL
        controls.gravity = Gravity.CENTER
        mute = Ui.button(c, c.getString(R.string.red_alert_mute), Ui.darken(color, 0.25f), Ui.PILL) {
            RedAlert.toggleMute(context)
        }
        mute.id = R.id.red_alert_mute
        mute.tag = "red_alert_mute"
        standDown = Ui.button(c, c.getString(R.string.red_alert_stand_down), color, Ui.PILL) {
            RedAlert.standDown(context)
        }
        standDown.id = R.id.red_alert_stand_down
        standDown.tag = "red_alert_stand_down"
        val gap = Ui.dp(c, 8f)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(gap, 0, gap, 0)
        controls.addView(mute, lp)
        controls.addView(standDown, LinearLayout.LayoutParams(lp))
        addView(
            controls,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ),
        )

        setOnApplyWindowInsetsListener { view, insets ->
            val bars = WindowInsetsCompat.toWindowInsetsCompat(insets, view)
                .getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            insetTop = bars.top
            insetBottom = bars.bottom
            insetLeft = bars.left
            insetRight = bars.right
            positionControls()
            insets
        }
        update()
    }

    /** Keeps the buttons clear of the navigation bar and the thick bottom bar. */
    private fun positionControls() {
        val lp = controls.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = insetBottom + barThickness() + Ui.dp(context, 12f)
        lp.leftMargin = insetLeft
        lp.rightMargin = insetRight
        controls.layoutParams = lp
    }

    private fun barThickness(): Int = Ui.dp(context, 18f)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        RedAlert.addListener(this)
        RedAlert.check(context)
        update()
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        RedAlert.removeListener(this)
        removeCallbacks(frame)
        ticking = false
        super.onDetachedFromWindow()
    }

    override fun onRedAlertChanged() {
        post { update() }
    }

    /** Shows or hides the frame and relabels MUTE / UNMUTE. */
    private fun update() {
        val on = RedAlert.active(context)
        visibility = if (on) View.VISIBLE else View.GONE
        mute.text = context.getString(if (RedAlert.muted(context)) R.string.red_alert_unmute else R.string.red_alert_mute)
        positionControls()
        if (on) {
            announceForAccessibility(context.getString(R.string.red_alert))
            schedule(0)
        }
    }

    private fun schedule(delay: Long) {
        if (ticking) return
        ticking = true
        postDelayed(frame, delay)
    }

    // ---- drawing ----------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = RedAlert.state(context)
        val now = System.currentTimeMillis()
        if (!s.active(now)) return
        val pulse = RedAlertLogic.pulse(s.since, now, Ui.animate(context))
        val w = width
        val h = height
        val color = alarmColor()

        canvas.drawColor(Ui.alpha(color, WASH_ALPHA * pulse))
        if (Skin.imperial()) {
            drawImperial(canvas, w, h, color, pulse)
        } else if (Skin.collective()) {
            drawCollective(canvas, w, h, color, pulse)
        } else {
            drawConsole(canvas, w, h, color, pulse)
        }
        drawCaption(canvas, w, color, pulse, s.remainingMs(now))
    }

    /** Command: an console frame (bars + left sidebar with rounded elbows) in red. */
    private fun drawConsole(cv: Canvas, w: Int, h: Int, color: Int, pulse: Float) {
        val bar = barThickness().toFloat()
        val side = Ui.dp(context, 36f).toFloat()
        val outer = Ui.dp(context, 44f).toFloat()
        val inner = Ui.dp(context, 18f).toFloat()
        val top = insetTop.toFloat()
        val bottom = (h - insetBottom).toFloat()
        val left = insetLeft.toFloat()
        val right = (w - insetRight).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Ui.alpha(color, pulse)
        // top elbow + bar
        path.reset()
        path.moveTo(right, top)
        path.lineTo(left + outer, top)
        path.quadTo(left, top, left, top + outer)
        path.lineTo(left, bottom - outer)
        path.quadTo(left, bottom, left + outer, bottom)
        path.lineTo(right, bottom)
        path.lineTo(right, bottom - bar)
        path.lineTo(left + side + inner, bottom - bar)
        path.quadTo(left + side, bottom - bar, left + side, bottom - bar - inner)
        path.lineTo(left + side, top + bar + inner)
        path.quadTo(left + side, top + bar, left + side + inner, top + bar)
        path.lineTo(right, top + bar)
        path.close()
        cv.drawPath(path, paint)
        // segment gaps in the sidebar, console style
        paint.color = Color.BLACK
        val gap = Ui.dp(context, 4f).toFloat()
        for (i in 1..2) {
            val y = top + (bottom - top) * i / 3f
            cv.drawRect(left, y - gap / 2, left + side, y + gap / 2, paint)
        }
    }

    /** Imperial: chamfered bars and angular corner blades. */
    private fun drawImperial(cv: Canvas, w: Int, h: Int, color: Int, pulse: Float) {
        val bar = barThickness().toFloat()
        val blade = Ui.dp(context, 56f).toFloat()
        val cut = Ui.dp(context, 14f).toFloat()
        val top = insetTop.toFloat()
        val bottom = (h - insetBottom).toFloat()
        val left = insetLeft.toFloat()
        val right = (w - insetRight).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Ui.alpha(color, pulse)
        for (edge in 0 until 2) {
            val y0 = if (edge == 0) top else bottom - bar
            path.reset()
            path.moveTo(left + blade + cut, y0)
            path.lineTo(right - blade - cut, y0)
            path.lineTo(right - blade, y0 + bar / 2)
            path.lineTo(right - blade - cut, y0 + bar)
            path.lineTo(left + blade + cut, y0 + bar)
            path.lineTo(left + blade, y0 + bar / 2)
            path.close()
            cv.drawPath(path, paint)
        }
        paint.color = Ui.alpha(ImperialDraw.ORANGE, pulse)
        for (corner in 0 until 4) {
            val cx = if (corner % 2 == 0) left else right
            val cy = if (corner < 2) top else bottom
            val dx = if (corner % 2 == 0) 1f else -1f
            val dy = if (corner < 2) 1f else -1f
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx + dx * blade, cy)
            path.lineTo(cx + dx * bar, cy + dy * bar)
            path.lineTo(cx, cy + dy * blade * 1.6f)
            path.close()
            cv.drawPath(path, paint)
        }
    }

    /** Collective: corner brackets and a fine threat grid along the edges. */
    private fun drawCollective(cv: Canvas, w: Int, h: Int, color: Int, pulse: Float) {
        val stroke = Ui.dp(context, 3f).toFloat()
        val arm = Ui.dp(context, 64f).toFloat()
        val cell = Ui.dp(context, 16f).toFloat()
        val top = insetTop.toFloat()
        val bottom = (h - insetBottom).toFloat()
        val left = insetLeft.toFloat()
        val right = (w - insetRight).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dp(context, 1f).toFloat()
        paint.color = Ui.alpha(color, 0.45f * pulse)
        var x = left
        while (x <= right) {
            cv.drawLine(x, top, x, top + cell * 2, paint)
            cv.drawLine(x, bottom - cell * 2, x, bottom, paint)
            x += cell
        }
        for (row in 0..2) {
            cv.drawLine(left, top + row * cell, right, top + row * cell, paint)
            cv.drawLine(left, bottom - row * cell, right, bottom - row * cell, paint)
        }
        paint.strokeWidth = stroke
        paint.color = Ui.alpha(color, pulse)
        val inset = stroke / 2
        rect.set(left + inset, top + inset, right - inset, bottom - inset)
        cv.drawLine(rect.left, rect.top, rect.left + arm, rect.top, paint)
        cv.drawLine(rect.left, rect.top, rect.left, rect.top + arm, paint)
        cv.drawLine(rect.right, rect.top, rect.right - arm, rect.top, paint)
        cv.drawLine(rect.right, rect.top, rect.right, rect.top + arm, paint)
        cv.drawLine(rect.left, rect.bottom, rect.left + arm, rect.bottom, paint)
        cv.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - arm, paint)
        cv.drawLine(rect.right, rect.bottom, rect.right - arm, rect.bottom, paint)
        cv.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - arm, paint)
        paint.style = Paint.Style.FILL
    }

    /** "RED ALERT • 1:45" (Collective: "THREAT DETECTED") in a black notch under the top edge. */
    private fun drawCaption(cv: Canvas, w: Int, color: Int, pulse: Float, remainingMs: Long) {
        val caption = context.getString(if (Skin.collective()) R.string.red_alert_collective else R.string.red_alert) +
            "  " + RedAlertLogic.countdown(remainingMs)
        text.textSize = Ui.px(context, 18f)
        val pad = Ui.dp(context, 8f).toFloat()
        val tw = text.measureText(caption)
        val x = if (Skin.command()) {
            (insetLeft + Ui.dp(context, 36f) + Ui.dp(context, 30f)).toFloat()
        } else {
            (w - tw) / 2f
        }
        val fm = text.fontMetrics
        val th = fm.descent - fm.ascent
        // Command: a notch cut into the top bar; others: just below their top edge
        val boxTop = (if (Skin.command()) insetTop else insetTop + barThickness() + Ui.dp(context, 6f)).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        rect.set(x - pad, boxTop, x + tw + pad, boxTop + Math.max(th, barThickness().toFloat()))
        cv.drawRect(rect, paint)
        text.color = Ui.alpha(if (Skin.command()) color else Color.WHITE, Math.max(0.6f, pulse))
        cv.drawText(caption, x, rect.top - fm.ascent + (rect.height() - th) / 2f, text)
    }

    companion object {
        /** Frame redraw interval while pulsing (~30 fps is plenty for a slow pulse). */
        private const val FRAME_MS = 33L

        /** How often the overlay re-checks timeout and sound rules. */
        private const val CHECK_MS = 1000L

        /** Strength of the full-screen red wash at the pulse peak (0..1). */
        private const val WASH_ALPHA = 0.10f

        /** Alarm colours per interface family. */
        private const val COMMAND_RED = 0xFFE03030.toInt()
        private const val COLLECTIVE_RED = 0xFFFF3B30.toInt()

        /** Adds a RED ALERT overlay on top of `root` (the home screen's GestureRoot). */
        @JvmStatic
        fun attach(c: Context, root: ViewGroup): RedAlertOverlay {
            val overlay = RedAlertOverlay(c)
            root.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return overlay
        }

        /** Alarm colour of the current interface. */
        private fun alarmColor(): Int =
            when {
                Skin.imperial() -> ImperialDraw.RED
                Skin.collective() -> COLLECTIVE_RED
                else -> COMMAND_RED
            }
    }
}
