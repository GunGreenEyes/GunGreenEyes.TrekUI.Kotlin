package com.gungreeneyes.trekui.licensing

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextPaint
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.gungreeneyes.trekui.Compat
import com.gungreeneyes.trekui.ScreenMetrics
import com.gungreeneyes.trekui.core.CoreConfig
import com.gungreeneyes.trekui.core.SuiteClient
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen console panel that explains the trial and sells the one-time unlock.
 *
 * Opened in two modes:
 * - **expired**: by [Licensing.onHomeResumed] when the trial session is over. Offers BUY, RESTORE
 *   PURCHASE, NEW SESSION (enabled after a short countdown) and, in the launcher
 *   ([CoreConfig.homeApp]), USE ANOTHER HOME APP, so the device is never stuck: the system home picker
 *   and Settings are always one tap away, and Recents / the notification shade keep working. Companion
 *   apps offer CLOSE instead.
 * - **voluntary**: from [Licensing.openPurchaseScreen], to buy early.
 *
 * Drawn with plain views plus a small Canvas elbow header in the classic console palette and the
 * launcher's Antonio typeface, sized with [ScreenMetrics] so it fits phones and tablets in either
 * orientation, edge-to-edge with system-bar/cutout insets applied as padding.
 *
 * Shared by every TrekUI app: the wording uses [CoreConfig.appName], and every variant says that one
 * purchase unlocks every TrekUI app. RESTORE PURCHASE checks this app's own Play purchase and every
 * installed sibling app ([SuiteClient]).
 *
 * develop flavor only: long-pressing the header simulates a purchase (and long-pressing it again while
 * simulated clears it), for tests and screenshots.
 */
class PurchaseActivity : Activity(), BillingManager.Listener {
    private var expired = false
    private lateinit var regular: Typeface
    private lateinit var heavy: Typeface
    private lateinit var metrics: ScreenMetrics
    private lateinit var status: TextView
    private lateinit var buyButton: TextView
    private var relaunchButton: TextView? = null
    private var countdown = 0
    private var closing = false
    private lateinit var config: CoreConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        expired = intent.getBooleanExtra(EXTRA_EXPIRED, false)
        config = CoreConfig.get(this)
        metrics = ScreenMetrics.of(this)
        loadFonts()
        // API 35+ is always edge-to-edge; on 30-34 opt in so the black panel runs under the bars.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM
        ) {
            window.setDecorFitsSystemWindows(false)
        }
        setContentView(buildContent())
        if (expired) startCountdown()
    }

    override fun onStart() {
        super.onStart()
        val billing = Licensing.billing
        billing.addListener(this)
        billing.refreshPurchases() // "restore on open": also finishes pending purchases
    }

    override fun onStop() {
        Licensing.billing.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        relaunchButton?.removeCallbacks(tick)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    private fun buildContent(): View {
        val margin = maxOf(metrics.marginPx(), dp(16))
        val gutter = maxOf(metrics.gutterPx(), dp(8))

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, margin, margin, margin)
        }

        val header = HeaderView(this, if (expired) "SESSION COMPLETE" else "UNLOCK ${config.appName}")
        header.setOnLongClickListener { simulatePurchase() }
        column.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96)))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val elbowInset = dp(SIDEBAR_DP) + gutter * 2
            setPadding(elbowInset, gutter * 2, 0, 0)
        }
        column.addView(
            body,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val lead = if (expired) {
            "YOUR ${describeSessionAdjective(config.trialSessionMinutes)} TRIAL SESSION HAS ENDED."
        } else {
            "${config.appName} IS FREE TO TRY IN SESSIONS OF ${describeSession(config.trialSessionMinutes)}."
        }
        body.addView(text(lead, 24f, ORANGE, true))
        body.addView(spacer(gutter))
        body.addView(text(pitch(config.appName, config.homeApp), 16f, TEXT_HI, false))
        body.addView(spacer(gutter))
        body.addView(text(SUITE_LINE, 16f, GOLD, true))
        body.addView(spacer(gutter))
        val note = when {
            config.homeApp && expired ->
                "Until then you can start a new trial session below, or switch to another home " +
                    "app at any time. Nothing on your device has been changed."
            config.homeApp -> "You can switch to another home app at any time."
            expired -> "Until then you can start a new trial session below. Nothing on your device " +
                "has been changed."
            else -> ""
        }
        if (note.isNotEmpty()) body.addView(text(note, 16f, LAVENDER, false))

        status = text("", 15f, GOLD, true).apply { setPadding(0, gutter * 2, 0, gutter) }
        body.addView(status)

        buyButton = button("BUY $FALLBACK_PRICE", ORANGE) { buy() }
        val restore = button("RESTORE PURCHASE", BLUE) { restore() }
        val other = when {
            config.homeApp -> button("USE ANOTHER HOME APP", LAVENDER) { openHomeSettings() }
            expired -> button("CLOSE", LAVENDER) { finishAffinity() }
            else -> null
        }
        if (expired) {
            relaunchButton = button("NEW SESSION", PINK) { relaunch() }.apply { isEnabled = false }
        }
        val close = if (expired) null else button("NOT NOW", PINK) { finish() }
        body.addView(buttonGrid(gutter, buyButton, restore, if (expired) relaunchButton else close, other))

        return ScrollView(this).apply {
            isFillViewport = true
            addView(
                column,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            setBackgroundColor(BLACK)
            clipToPadding = false
            setOnApplyWindowInsetsListener { v, insets ->
                applyInsets(v, insets)
                insets
            }
        }
    }

    /** Buttons in one column on compact portrait screens, two columns when there is room. */
    private fun buttonGrid(gutter: Int, vararg buttons: TextView?): View {
        val columns = if (metrics.isLandscape || metrics.isTwoPane) 2 else 1
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var row: LinearLayout? = null
        var inRow = 0
        for (b in buttons) {
            if (b == null) continue
            var current = row
            if (current == null || inRow == columns) {
                current = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val rp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                rp.topMargin = gutter
                grid.addView(current, rp)
                row = current
                inRow = 0
            }
            val bp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (inRow > 0) bp.leftMargin = gutter
            current.addView(b, bp)
            inRow++
        }
        row?.let { last ->
            while (inRow < columns) { // keep the last row's buttons the same width as the others
                val bp = LinearLayout.LayoutParams(0, 1, 1f)
                bp.leftMargin = gutter
                last.addView(View(this), bp)
                inRow++
            }
        }
        return grid
    }

    private fun text(value: CharSequence, sp: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * minOf(metrics.uiScale, 1.3f))
            typeface = if (bold) heavy else regular
            setLineSpacing(0f, 1.1f)
        }

    /**
     * console pill button: label bottom-right, black text on a coloured pill, min 48dp tall. The label
     * shrinks to fit the pill (long labels like RESTORE PURCHASE were clipped on narrow phones), and
     * TalkBack announces it as a button whose description follows the live label (price, countdown).
     */
    private fun button(label: String, color: Int, onClick: View.OnClickListener): TextView {
        val b = text(label.uppercase(), 17f, BLACK, true)
        b.gravity = Gravity.BOTTOM or Gravity.END
        b.setSingleLine(true)
        b.ellipsize = TextUtils.TruncateAt.END
        b.minHeight = dp(56)
        b.setPadding(dp(16), dp(6), dp(18), dp(8))
        val pill = GradientDrawable().apply {
            setColor(
                ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(Color.argb(255, 60, 56, 70), color),
                ),
            )
            cornerRadius = dp(999).toFloat()
        }
        b.background = RippleDrawable(ColorStateList.valueOf(RIPPLE), pill, null)
        b.setOnClickListener(onClick)
        b.contentDescription = label
        b.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        b.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Button::class.java.name
            }
        }
        val baseSize = b.textSize
        val fit = Runnable { fitLabel(b, baseSize) }
        b.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) v.post(fit)
        }
        b.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable) {
                b.contentDescription = s.toString()
                b.post(fit)
            }
        })
        return b
    }

    private fun spacer(height: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, height) }

    private fun loadFonts() {
        // variable weights on API 26+; the default instance (bolded for heavy) on 23-25
        regular = Compat.variableFont(assets, "fonts/Antonio.ttf", FONT_REGULAR, false)
        heavy = Compat.variableFont(assets, "fonts/Antonio.ttf", FONT_HEAVY, true)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).roundToInt()

    // ---------------------------------------------------------------- actions

    private fun buy() {
        val r = Licensing.billing.launchPurchase(this)
        if (r == BillingManager.LaunchResult.ALREADY_UNLOCKED) onUnlocked()
    }

    private fun restore() {
        status.text = "CHECKING GOOGLE PLAY FOR YOUR PURCHASE…"
        Licensing.billing.refreshPurchases()
        // A purchase made in any other TrekUI app counts too; a grant notifies the billing listeners.
        SuiteClient.checkSiblingsAsync(this, null)
    }

    /** Opens the system "Default home app" screen; falls back to Settings if a ROM lacks it. */
    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ignored: ActivityNotFoundException) {
                Toast.makeText(this, "Open Settings > Apps > Default apps > Home app", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Explicit relaunch: a fresh trial session starts and the home screen returns. */
    private fun relaunch() {
        Licensing.startNewSession()
        finish()
    }

    /** develop flavor only: toggles a simulated purchase. Returns false (not consumed) otherwise. */
    private fun simulatePurchase(): Boolean {
        if (!Licensing.isDevelopFlavor()) return false
        val ent = Licensing.entitlement
        val nowSimulated = ent.source() != Entitlement.Source.SIMULATED
        ent.setSimulatedPurchase(nowSimulated)
        Toast.makeText(
            this,
            if (nowSimulated) "DEV: purchase simulated" else "DEV: simulated purchase cleared",
            Toast.LENGTH_SHORT,
        ).show()
        Licensing.billing.notifyListeners()
        return true
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            val button = relaunchButton ?: return
            if (countdown <= 0) {
                button.text = "NEW SESSION"
                button.isEnabled = true
                return
            }
            button.text = "NEW SESSION IN $countdown"
            countdown--
            button.postDelayed(this, 1000L)
        }
    }

    private fun startCountdown() {
        countdown = RELAUNCH_DELAY_SECONDS
        tick.run()
    }

    private fun onUnlocked() {
        if (closing) return
        closing = true
        status.setTextColor(GOLD)
        status.text = if (config.homeApp) {
            "UNLOCKED. THANK YOU — TREKUI IS NOW YOUR PERMANENT LAUNCHER."
        } else {
            "UNLOCKED. THANK YOU — EVERY TREKUI APP IS NOW UNLOCKED."
        }
        buyButton.isEnabled = false
        status.postDelayed({ finish() }, 1800L)
    }

    override fun onBillingStateChanged(state: BillingManager.State) {
        if (state.unlocked) {
            onUnlocked()
            return
        }
        buyButton.text = "BUY ${state.formattedPrice ?: FALLBACK_PRICE}"
        val message = state.message
        when {
            message != null -> {
                status.setTextColor(if (state.pending) GOLD else ALERT)
                status.text = message
            }
            state.pending -> {
                status.setTextColor(GOLD)
                status.text = "PURCHASE PENDING — IT UNLOCKS AS SOON AS PAYMENT COMPLETES"
            }
            else -> status.text = ""
        }
    }

    // ---------------------------------------------------------------- header

    /**
     * console elbow: a vertical sidebar joined to a top bar by a rounded outer corner and a smaller inner
     * curve, with the title in the bar's cut-out at the right, plus a few accent segments.
     */
    private inner class HeaderView(context: Context, private val title: String) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = heavy
            color = ORANGE
            textAlign = Paint.Align.RIGHT
        }
        private val elbow = Path()
        private val arc = RectF()

        init {
            contentDescription = title
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val side = dp(SIDEBAR_DP).toFloat()
            val bar = h * 0.32f
            val outer = minOf(h * 0.9f, side)
            val inner = bar * 0.9f
            val gap = dp(6).toFloat()

            label.textSize = bar * 1.15f
            val titleWidth = label.measureText(title)
            val barEnd = maxOf(side + outer, w - titleWidth - gap * 3)

            // Elbow: sidebar down the left, bar across the top, rounded outside, curved inside.
            elbow.reset()
            elbow.moveTo(0f, h)
            elbow.lineTo(0f, outer)
            arc.set(0f, 0f, outer * 2, outer * 2)
            elbow.arcTo(arc, 180f, 90f, false)
            elbow.lineTo(barEnd, 0f)
            elbow.lineTo(barEnd, bar)
            elbow.lineTo(side + inner, bar)
            arc.set(side, bar, side + inner * 2, bar + inner * 2)
            elbow.arcTo(arc, 270f, -90f, false)
            elbow.lineTo(side, h)
            elbow.close()
            fill.color = ORANGE
            canvas.drawPath(elbow, fill)

            // Title in the cut-out, baseline aligned with the bottom of the bar.
            canvas.drawText(title, w, bar - label.descent() * 0.2f, label)

            // Accent segments under the title.
            val segTop = bar + gap * 2
            val segH = maxOf(dp(10).toFloat(), bar * 0.35f)
            val colors = intArrayOf(LAVENDER, BLUE, PINK)
            val start = side + inner * 2 + gap
            val segW = (w - start - gap * (colors.size - 1)) / colors.size
            colors.forEachIndexed { i, c ->
                fill.color = c
                val l = start + i * (segW + gap)
                canvas.drawRect(l, segTop, l + segW, segTop + segH, fill)
            }
        }
    }

    companion object {
        internal const val EXTRA_EXPIRED: String = "com.gungreeneyes.trekui.licensing.EXPIRED"

        /** Seconds before NEW SESSION becomes available on the expiry screen. */
        internal const val RELAUNCH_DELAY_SECONDS: Int = 10

        /** Width of the header elbow's sidebar, in dp. */
        private const val SIDEBAR_DP = 72

        /** Antonio's `wght` for body text and for headings. */
        private const val FONT_REGULAR = 500
        private const val FONT_HEAVY = 700

        /** Price shown until Play has returned the localized one. */
        private const val FALLBACK_PRICE = "\$0.99"

        private const val BLACK = Color.BLACK
        private val ORANGE = Color.parseColor("#FF9C00")
        private val LAVENDER = Color.parseColor("#B4A0F0")
        private val BLUE = Color.parseColor("#99B8FF")
        private val PINK = Color.parseColor("#DD88AA")
        private val GOLD = Color.parseColor("#FFCC66")
        private val TEXT_HI = Color.parseColor("#E6DEFF")
        private val ALERT = Color.parseColor("#FF4433")
        private const val RIPPLE = 0x70FFFFFF

        /** Shown on every purchase screen of every TrekUI app (owner rule: one purchase, every app). */
        internal const val SUITE_LINE: String = "One purchase unlocks every TrekUI app."

        /** Opens the screen; [expired] selects the session-over wording and buttons. */
        internal fun start(context: Context, expired: Boolean) {
            val intent = Intent(context, PurchaseActivity::class.java).putExtra(EXTRA_EXPIRED, expired)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }

        /** Pads the scroll view by the system bars and display cutout so nothing sits under them. */
        @Suppress("DEPRECATION")
        private fun applyInsets(v: View, insets: WindowInsets) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                v.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
        }

        /** Shrinks a single-line label (never below 60% of [baseSize]) so it fits its button. */
        private fun fitLabel(b: TextView, baseSize: Float) {
            val avail = (b.width - b.totalPaddingLeft - b.totalPaddingRight).toFloat()
            if (avail <= 0) return
            val p = TextPaint(b.paint).apply { textSize = baseSize }
            val w = p.measureText(b.text.toString())
            val size = if (w <= avail) baseSize else maxOf(baseSize * 0.6f, baseSize * avail / w * 0.98f)
            if (abs(size - b.textSize) > 0.5f) b.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        }

        /**
         * The main explanation. The launcher keeps its home-screen wording; companions say "keep using".
         * [appName] is the upper-case display name (e.g. "TREKUI MESSAGES").
         */
        internal fun pitch(appName: String?, homeApp: Boolean): String {
            val name = displayName(appName)
            val restart = if (homeApp) "the launcher" else name
            val goal = if (homeApp) {
                "To use $name as your permanent home screen"
            } else {
                "To keep using $name without trial sessions"
            }
            return "$name is free to download and has no ads. Every feature works during a " +
                "trial session; a new session starts after you restart $restart or your device. " +
                "$goal, unlock it once with a single purchase " +
                "of $FALLBACK_PRICE (US price; Google Play shows your local price). There is " +
                "no subscription, and the purchase covers every device signed in to your Google account."
        }

        /**
         * "TREKUI MESSAGES" becomes "TrekUI Messages" (the brand keeps its capitals). Trims like Java's
         * `String.trim()` (every char <= ' '), not Kotlin's Unicode-whitespace `trim()`.
         */
        internal fun displayName(appName: String?): String {
            val trimmed = appName?.trim { it <= ' ' }
            if (trimmed.isNullOrEmpty()) return "TrekUI"
            return trimmed.split(Regex(" +")).joinToString(" ") { w ->
                if (w.equals("TREKUI", ignoreCase = true)) {
                    "TrekUI"
                } else {
                    w.substring(0, 1).uppercase() + w.substring(1).lowercase()
                }
            }
        }

        /** Noun form: "24 HOURS", "5 MINUTES", "90 MINUTES" ("in sessions of 5 MINUTES"). */
        internal fun describeSession(minutes: Long): String {
            if (minutes >= 60 && minutes % 60 == 0L) {
                val h = minutes / 60
                return "$h ${if (h == 1L) "HOUR" else "HOURS"}"
            }
            return "$minutes ${if (minutes == 1L) "MINUTE" else "MINUTES"}"
        }

        /** Adjective form, always singular and hyphenated: "24-HOUR", "5-MINUTE" ("your 5-MINUTE trial"). */
        internal fun describeSessionAdjective(minutes: Long): String =
            if (minutes >= 60 && minutes % 60 == 0L) "${minutes / 60}-HOUR" else "$minutes-MINUTE"
    }
}
