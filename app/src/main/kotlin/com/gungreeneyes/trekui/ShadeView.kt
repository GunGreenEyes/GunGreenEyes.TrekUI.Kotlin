package com.gungreeneyes.trekui

import android.app.Notification
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The console shade: opened by swiping down on the console home screen. Quick controls, brightness and
 * volume, and (with notification access) the notification list. The system's own notification
 * panel stays one tap away. Nothing here runs while the shade is closed.
 *
 * Responsive: on compact windows everything is one scrolling column; on wider windows
 * (landscape phones, tablets, unfolded foldables; [ScreenMetrics.isTwoPane]) the controls
 * and sliders form the left pane and the notification log scrolls on its own in the right pane.
 * MainActivity rebuilds the shade on every configuration change, so the choice is made once here.
 */
internal class ShadeView(act: MainActivity) : FrameLayout(act) {
    private val a: MainActivity = act
    private val prefs: Prefs = Prefs.get(act)
    private val pal: Palette = prefs.palette()
    private val metrics: ScreenMetrics = ScreenMetrics.of(act)
    private val frame: ConsoleFrame

    /** The main (left, or only) scrolling column; MainActivity's swipe gestures check its edges. */
    private val scroll: ScrollView
    private val clock: TextView
    private val date: TextView
    private val status: TextView
    private val controls: LinearLayout
    private val notes: LinearLayout
    private val sliders: LinearLayout
    private val notesHeader: TextView
    private var open = false
    private var updating = false
    private var cams: CameraManager? = null
    private var torchId: String? = null
    private var torchOn = false

    private val torchCb: CameraManager.TorchCallback =
        object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(id: String, enabled: Boolean) {
                if (id == torchId) {
                    torchOn = enabled
                    buildControls()
                }
            }
        }

    private val onNotes =
        Runnable {
            if (open) {
                buildNotes()
                buildControls()
            }
        }

    init {
        val twoPane = metrics.isTwoPane
        setBackgroundColor(Ui.alpha(Skin.bg(), 0.97f))
        isClickable = true

        frame = ConsoleFrame(act)
        frame.sidebarW = max(Ui.dp(act, SIDEBAR_DP), metrics.scaledPx(SIDEBAR_DP))
        frame.colorTop = pal.c(3)
        frame.colorBot = pal.c(1)
        frame.topBlocks = intArrayOf(pal.c(0))
        frame.botBlocks = intArrayOf(pal.c(2))
        val title = Ui.bold(act, "OPERATIONS CONTROL", 26f, pal.c(0))
        title.setPadding(Ui.dp(act, Ui.XS.toFloat()), 0, Ui.dp(act, Ui.HAIR.toFloat()), 0)
        frame.setTitle(title)
        status = Ui.text(act, "", 13f, pal.c(1))
        status.setPadding(Ui.dp(act, Ui.XS.toFloat()), 0, Ui.dp(act, Ui.HAIR.toFloat()), 0)
        frame.setFooter(status)

        val side = LinearLayout(act)
        side.orientation = LinearLayout.VERTICAL
        val codes = arrayOf("CTRL", "LOG", "47")
        val cols = intArrayOf(pal.c(0), pal.c(2), pal.c(4))
        for (i in codes.indices) {
            val t = Ui.bold(act, codes[i], 13f, Ui.ink())
            t.gravity = Gravity.BOTTOM or Gravity.END
            t.setPadding(0, 0, Ui.dp(act, Ui.GUTTER.toFloat()), Ui.dp(act, Ui.XS.toFloat()))
            t.background = Ui.shape(act, cols[i], Ui.SQUARE, 0f)
            val last = i == codes.size - 1
            val lp = LinearLayout.LayoutParams(-1, if (last) 0 else Ui.dp(act, SIDE_BLOCK_DP), if (last) 1f else 0f)
            if (i > 0) lp.topMargin = Ui.dp(act, Ui.GUTTER.toFloat())
            side.addView(t, lp)
        }
        frame.setSidebar(side)

        scroll = ScrollView(act)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = OVER_SCROLL_NEVER
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        // the notification log goes in its own column on wide windows
        var logCol = col
        var logScroll: ScrollView? = null
        if (twoPane) {
            val ls = ScrollView(act)
            ls.isVerticalScrollBarEnabled = false
            ls.overScrollMode = OVER_SCROLL_NEVER
            logCol = LinearLayout(act)
            logCol.orientation = LinearLayout.VERTICAL
            ls.addView(logCol)
            logScroll = ls
        }

        val clockRow = LinearLayout(act)
        clockRow.gravity = Gravity.BOTTOM
        clock = Ui.bold(act, "", 46f, pal.textHi)
        clockRow.addView(clock)
        date = Ui.text(act, "", 16f, pal.c(1))
        date.setPadding(Ui.dp(act, Ui.M.toFloat()), 0, 0, Ui.dp(act, Ui.S.toFloat()))
        clockRow.addView(date, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(clockRow)

        controls = LinearLayout(act)
        controls.orientation = LinearLayout.VERTICAL
        val cl = LinearLayout.LayoutParams(-1, -2)
        cl.topMargin = Ui.dp(act, Ui.M.toFloat())
        col.addView(controls, cl)

        sliders = LinearLayout(act)
        sliders.orientation = LinearLayout.VERTICAL
        col.addView(sliders, LinearLayout.LayoutParams(-1, -2))

        val nh = LinearLayout(act)
        nh.gravity = Gravity.CENTER_VERTICAL
        nh.setPadding(0, if (twoPane) 0 else Ui.dp(act, Ui.L.toFloat()), 0, Ui.dp(act, Ui.GUTTER.toFloat()))
        notesHeader = Ui.bold(act, "COMMUNICATIONS LOG", 18f, pal.c(2))
        nh.addView(notesHeader, LinearLayout.LayoutParams(0, -2, 1f))
        val clear = Ui.button(act, "CLEAR ALL", pal.c(3), Ui.PILL) { clearAll() }
        clear.gravity = Gravity.CENTER
        clear.textSize = 13 * Skin.textScale()
        clear.tag = "clear"
        nh.addView(clear, LinearLayout.LayoutParams(Ui.dp(act, 104f), Ui.dp(act, 40f)))
        logCol.addView(nh)
        notes = LinearLayout(act)
        notes.orientation = LinearLayout.VERTICAL
        logCol.addView(notes, LinearLayout.LayoutParams(-1, -2))

        val foot = LinearLayout(act)
        foot.setPadding(0, Ui.dp(act, Ui.L.toFloat()), 0, Ui.dp(act, Ui.GUTTER.toFloat()))
        val systemPanel = if ("samsung".equals(Build.MANUFACTURER, ignoreCase = true)) "SAMSUNG PANEL" else "SYSTEM PANEL"
        val samsung =
            Ui.button(act, systemPanel, pal.c(1), Ui.LEFT) {
                close(false)
                a.expandNotifications()
            }
        samsung.gravity = Gravity.CENTER
        samsung.textSize = 14 * Skin.textScale()
        foot.addView(samsung, LinearLayout.LayoutParams(0, Ui.dp(act, 48f), 1f))
        val done = Ui.button(act, "CLOSE", pal.c(0), Ui.RIGHT) { close(true) }
        done.gravity = Gravity.CENTER
        done.textSize = 14 * Skin.textScale()
        val dl = LinearLayout.LayoutParams(0, Ui.dp(act, 48f), 1f)
        dl.leftMargin = Ui.dp(act, Ui.GUTTER.toFloat())
        foot.addView(done, dl)
        col.addView(foot)

        scroll.addView(col)
        if (twoPane) {
            val panes = LinearLayout(act)
            panes.orientation = LinearLayout.HORIZONTAL
            panes.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
            val rl = LinearLayout.LayoutParams(0, -1, 1f)
            rl.leftMargin = metrics.gutterPx() * 2
            panes.addView(logScroll, rl)
            frame.setContent(panes)
        } else {
            frame.setContent(scroll)
        }
        addView(frame, LayoutParams(-1, -1))

        cams = act.getSystemService(Context.CAMERA_SERVICE) as CameraManager?
        try {
            val cm = cams
            if (cm != null) {
                for (id in cm.cameraIdList) {
                    val ch = cm.getCameraCharacteristics(id)
                    val facing = ch.get(CameraCharacteristics.LENS_FACING)
                    if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        facing != null && facing == CameraCharacteristics.LENS_FACING_BACK
                    ) {
                        torchId = id
                        break
                    }
                }
            }
        } catch (ignored: Exception) {
        }
    }

    /** Keeps the shade clear of the status and navigation bars (top and bottom insets in px). */
    fun setInsets(top: Int, bottom: Int) {
        setInsets(0, top, 0, bottom)
    }

    /**
     * Keeps the shade clear of system bars and display cutouts on every edge (insets in px);
     * the side padding grows to the window margin on wide windows.
     */
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        val g = if (metrics.isTwoPane) metrics.marginPx() else Ui.dp(context, 8f)
        setPadding(left + g, top + Ui.dp(context, Ui.XS.toFloat()), right + g, bottom + Ui.dp(context, Ui.GUTTER.toFloat()))
    }

    fun isOpen(): Boolean = open

    /** True when the main column can't scroll further down (MainActivity's close gestures). */
    fun atBottom(): Boolean = !scroll.canScrollVertically(1)

    /** True when the main column can't scroll further up (MainActivity's close gestures). */
    fun atTop(): Boolean = !scroll.canScrollVertically(-1)

    /** Shows the shade (sliding down when animated) and starts live updates. */
    fun open(animate: Boolean) {
        if (open) return
        open = true
        Sfx.play(context, Sfx.OPEN)
        visibility = VISIBLE
        scroll.scrollTo(0, 0)
        startUpdates()
        if (animate) {
            alpha = 0f
            translationY = -Ui.dp(context, 60f).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(240)
                .setInterpolator(DecelerateInterpolator(1.5f)).withEndAction(null).start()
            frame.reveal()
        } else {
            alpha = 1f
            translationY = 0f
        }
    }

    /** Hides the shade (sliding up when animated) and stops live updates. */
    fun close(animate: Boolean) {
        if (!open) return
        open = false
        stopUpdates()
        Sfx.play(context, Sfx.CLOSE)
        if (animate && Ui.animate(context)) {
            animate().alpha(0f).translationY(-Ui.dp(context, 50f).toFloat()).setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { if (!open) visibility = GONE }
                .start()
        } else {
            animate().cancel()
            visibility = GONE
        }
    }

    /** Called from MainActivity.onPause / onResume while the shade is open. */
    fun stopUpdates() {
        if (!updating) return
        updating = false
        MediaListener.setOnChange(null)
        val cm = cams
        if (cm != null && torchId != null) {
            try {
                cm.unregisterTorchCallback(torchCb)
            } catch (ignored: Exception) {
            }
        }
    }

    /** Starts notification and torch callbacks and refreshes everything (only while open). */
    fun startUpdates() {
        if (!open || updating) return
        updating = true
        MediaListener.setOnChange(onNotes)
        val cm = cams
        if (cm != null && torchId != null) {
            try {
                cm.registerTorchCallback(torchCb, a.handler())
            } catch (ignored: Exception) {
            }
        }
        refresh()
    }

    /** Re-reads clock, power, quick controls, sliders and notifications. */
    fun refresh() {
        val d = Date()
        clock.text = SimpleDateFormat(if (a.use24h()) "HH:mm" else "h:mm", Locale.getDefault()).format(d)
        val dp = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEMMMd")
        date.text = SimpleDateFormat(dp, Locale.getDefault()).format(d).uppercase(Locale.getDefault())
        val b = a.battery()
        status.text = if (b != null) "PWR " + MainActivity.batteryPct(b) + "%" else ""
        buildControls()
        buildSliders()
        buildNotes()
    }

    // ---- quick controls ---------------------------------------------------------------------------

    /** One quick-control key: label, state line, lit when on. */
    private fun tile(row: LinearLayout, label: String, state: String?, on: Boolean, color: Int, kind: Int, act: (View) -> Unit) {
        val c = context
        val t = Ui.button(c, label, if (on) color else Ui.darken(color, 0.38f), kind, null)
        t.setOnClickListener(Ui.feedback { v -> act(v) })
        t.text = label + (if (state != null) "\n" + state else "")
        t.isSingleLine = false
        t.maxLines = 2
        t.textSize = 13 * Skin.textScale()
        t.setLineSpacing(0f, 0.95f)
        t.setPadding(Ui.dp(c, Ui.XS.toFloat()), 0, Ui.dp(c, Ui.S.toFloat()), Ui.dp(c, Ui.GUTTER.toFloat()))
        if (!on) t.setTextColor(Ui.alpha(Ui.ink(), 0.85f))
        val lp = LinearLayout.LayoutParams(0, -1, 1f)
        if (row.childCount > 0) lp.leftMargin = Ui.dp(c, Ui.XS.toFloat())
        row.addView(t, lp)
    }

    private fun row(): LinearLayout {
        val r = LinearLayout(context)
        val lp = LinearLayout.LayoutParams(-1, Ui.dp(context, TILE_ROW_DP))
        if (controls.childCount > 0) lp.topMargin = Ui.dp(context, Ui.XS.toFloat())
        controls.addView(r, lp)
        return r
    }

    /** Two rows of four quick controls reflecting the current system state. */
    private fun buildControls() {
        controls.removeAllViews()
        val net = network()
        val r1 = row()
        tile(r1, "NETWORK", net, "OFFLINE" != net, pal.c(0), Ui.LEFT) { v ->
            a.startSafe(
                Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS),
                v,
            )
        }
        val bt = bluetoothOn()
        tile(r1, "BLUETOOTH", if (bt) "ON" else "OFF", bt, pal.c(2), Ui.SQUARE) { v ->
            a.startSafe(Intent(Settings.ACTION_BLUETOOTH_SETTINGS), v)
        }
        tile(r1, "TORCH", if (torchOn) "ON" else "OFF", torchOn, pal.c(4), Ui.SQUARE) { toggleTorch() }
        val dnd = dndOn()
        tile(r1, "SILENCE", if (dnd) "DND ON" else "OFF", dnd, pal.c(3), Ui.RIGHT) { v -> toggleDnd(v, dnd) }
        val r2 = row()
        val rot = rotationOn()
        tile(r2, "ROTATE", if (rot) "AUTO" else "LOCKED", rot, pal.c(1), Ui.LEFT) { v -> toggleRotation(v, rot) }
        val loc = locationOn()
        tile(r2, "LOCATION", if (loc) "ON" else "OFF", loc, pal.c(5), Ui.SQUARE) { v ->
            a.startSafe(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), v)
        }
        val nfc = nfcOn()
        tile(r2, "NFC", if (nfc) "ON" else "OFF", nfc, pal.c(0), Ui.SQUARE) { v ->
            a.startSafe(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS), v)
        }
        tile(r2, "SETTINGS", null, true, pal.c(2), Ui.RIGHT) { v -> a.startSafe(Intent(Settings.ACTION_SETTINGS), v) }
    }

    /** Active network type for the NETWORK tile. */
    private fun network(): String =
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork
            val nc = if (n == null) null else cm.getNetworkCapabilities(n)
            when {
                nc == null -> "OFFLINE"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WI-FI"
                nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                else -> "ONLINE"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }

    private fun bluetoothOn(): Boolean =
        try {
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
            val ad = bm?.adapter
            ad != null && ad.isEnabled
        } catch (e: Exception) {
            false
        }

    private fun locationOn(): Boolean =
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (Build.VERSION.SDK_INT >= 28) {
                lm.isLocationEnabled
            } else {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) || lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            }
        } catch (e: Exception) {
            false
        }

    private fun nfcOn(): Boolean =
        try {
            val n = NfcAdapter.getDefaultAdapter(context)
            n != null && n.isEnabled
        } catch (e: Exception) {
            false
        }

    private fun rotationOn(): Boolean =
        try {
            Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        } catch (e: Exception) {
            false
        }

    private fun canWrite(): Boolean = Settings.System.canWrite(context)

    /** Explains and opens the "Modify system settings" permission. */
    private fun askWrite() {
        Sfx.play(context, Sfx.DENY)
        a.showing(
            ConsoleDialog.message(
                context,
                "SYSTEM CONTROL ACCESS",
                "To change brightness and auto-rotate from the TrekUI shade, allow TrekUI to “Modify system settings”. " +
                    "It is used only when you move these controls.",
                arrayOf("OPEN PERMISSION", "CANCEL"),
            ) { w ->
                if (w == 0) {
                    a.startSafe(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + context.packageName)), null)
                }
            },
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun toggleRotation(v: View, on: Boolean) {
        if (!canWrite()) {
            askWrite()
            return
        }
        try {
            Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
        } catch (e: Exception) {
            Sfx.play(context, Sfx.ERROR)
        }
        buildControls()
    }

    private fun dndOn(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
        return nm != null && nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
    }

    /** Toggles Do Not Disturb through the notification listener, else opens the DND settings. */
    private fun toggleDnd(v: View, on: Boolean) {
        val l = MediaListener.get()
        if (l != null) {
            try {
                l.requestInterruptionFilter(
                    if (on) NotificationListenerService.INTERRUPTION_FILTER_ALL else NotificationListenerService.INTERRUPTION_FILTER_PRIORITY,
                )
                a.handler().postDelayed({ if (open) buildControls() }, 400)
                return
            } catch (ignored: Exception) {
            }
        }
        if (!a.startSafe(Intent("android.settings.ZEN_MODE_SETTINGS"), v)) a.startSafe(Intent(Settings.ACTION_SOUND_SETTINGS), v)
    }

    private fun toggleTorch() {
        val id = torchId
        val cm = cams
        if (id == null || cm == null) {
            Sfx.play(context, Sfx.DENY)
            return
        }
        try {
            cm.setTorchMode(id, !torchOn)
        } catch (e: Exception) {
            Sfx.play(context, Sfx.ERROR)
        }
    }

    // ---- brightness & volume ----------------------------------------------------------------------

    /** Brightness (gamma-mapped, with AUTO/MANUAL) and media volume sliders. */
    private fun buildSliders() {
        sliders.removeAllViews()
        val c = context
        // Brightness
        var auto = false
        var bright = 128
        try {
            auto = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) ==
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            bright = Settings.System.getInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (ignored: Exception) {
        }
        val autoNow = auto
        val pos = sliderForBrightness(bright)
        sliderRow(
            "ILLUMINATION",
            pal.c(4),
            SLIDER_MAX,
            pos,
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    if (!user || !canWrite()) return
                    val v = brightnessForSlider(p)
                    try {
                        Settings.System.putInt(
                            c.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                        )
                        Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v)
                    } catch (ignored: Exception) {
                    }
                }

                override fun onStartTrackingTouch(s: SeekBar) {
                    if (!canWrite()) askWrite()
                }

                override fun onStopTrackingTouch(s: SeekBar) {
                    if (canWrite()) buildSliders()
                }
            },
            if (autoNow) "AUTO" else "MANUAL",
            autoNow,
        ) {
            if (!canWrite()) {
                askWrite()
            } else {
                try {
                    Settings.System.putInt(
                        c.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        if (autoNow) Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL else Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                    )
                } catch (ignored: Exception) {
                }
                buildSliders()
            }
        }
        // Media volume
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        if (am != null) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            sliderRow(
                "AUDIO LEVEL",
                pal.c(2),
                max,
                am.getStreamVolume(AudioManager.STREAM_MUSIC),
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                        if (!user) return
                        try {
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0)
                        } catch (ignored: Exception) {
                        }
                    }

                    override fun onStartTrackingTouch(s: SeekBar) {}

                    override fun onStopTrackingTouch(s: SeekBar) {}
                },
                "SOUND",
                false,
            ) { v ->
                if (!a.startSafe(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS), v)) {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                }
            }
        }
    }

    /** A labelled slider with a button at its end. */
    private fun sliderRow(
        label: String,
        color: Int,
        max: Int,
        value: Int,
        l: SeekBar.OnSeekBarChangeListener,
        btn: String,
        btnOn: Boolean,
        bl: OnClickListener,
    ) {
        val c = context
        val r = LinearLayout(c)
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, Ui.dp(c, Ui.M.toFloat()), 0, 0)
        val t = Ui.bold(c, label, 13f, color)
        r.addView(t, LinearLayout.LayoutParams(Ui.dp(c, 84f), -2))
        val sb = SeekBar(c)
        sb.max = max
        sb.progress = value
        sb.progressTintList = ColorStateList.valueOf(color)
        sb.thumbTintList = ColorStateList.valueOf(pal.textHi)
        sb.progressBackgroundTintList = ColorStateList.valueOf(Ui.alpha(color, 0.3f))
        sb.setOnSeekBarChangeListener(l)
        r.addView(sb, LinearLayout.LayoutParams(0, Ui.dp(c, 44f), 1f))
        val b = Ui.button(c, btn, if (btnOn) color else Ui.darken(color, 0.4f), Ui.PILL, null)
        b.setOnClickListener(Ui.feedback(bl))
        b.gravity = Gravity.CENTER
        b.textSize = 12 * Skin.textScale()
        r.addView(b, LinearLayout.LayoutParams(Ui.dp(c, 72f), Ui.dp(c, 38f)))
        sliders.addView(r)
    }

    // ---- notifications ------------------------------------------------------------------------------

    private fun hasAccess(): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(ComponentName(context, MediaListener::class.java).flattenToString())
    }

    /** The notification log (newest first), or the reason it is empty. */
    private fun buildNotes() {
        notes.removeAllViews()
        val clear: View = (notesHeader.parent as View).findViewWithTag("clear")
        val c = context
        if (!prefs.b("shadeNotes", true)) {
            notesHeader.text = "COMMUNICATIONS LOG • OFF"
            clear.visibility = GONE
            return
        }
        val l = MediaListener.get()
        if (!hasAccess() || l == null) {
            notesHeader.text = "COMMUNICATIONS LOG"
            clear.visibility = GONE
            val t =
                Ui.text(
                    c,
                    if (hasAccess()) {
                        "CONNECTING TO NOTIFICATION SERVICE…"
                    } else {
                        "To list notifications here, grant TrekUI notification access (the same access that shows track info in the audio panel)."
                    },
                    14f,
                    pal.textLo,
                )
            t.setLineSpacing(0f, 1.1f)
            notes.addView(t)
            if (!hasAccess()) {
                val g =
                    Ui.button(c, "GRANT NOTIFICATION ACCESS", pal.c(2), Ui.PILL) { v ->
                        a.startSafe(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), v)
                    }
                val lp = LinearLayout.LayoutParams(-1, Ui.dp(c, 48f))
                lp.topMargin = Ui.dp(c, Ui.S.toFloat())
                notes.addView(g, lp)
            }
            return
        }
        val list = ArrayList<StatusBarNotification>()
        try {
            val all: Array<StatusBarNotification>? = l.activeNotifications
            if (all != null) list.addAll(all)
        } catch (ignored: Exception) {
        }
        val shown = ArrayList<StatusBarNotification>()
        for (s in list) {
            if (s.packageName == c.packageName) continue
            val n = s.notification
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            if (title(n) == null && body(n) == null) continue
            shown.add(s)
        }
        shown.sortByDescending { it.postTime }
        val anyClearable = shown.any { it.isClearable }
        clear.visibility = if (anyClearable) VISIBLE else GONE
        notesHeader.text = "COMMUNICATIONS LOG • " + shown.size
        if (shown.isEmpty()) {
            notes.addView(Ui.text(c, "NO ACTIVE NOTIFICATIONS", 14f, pal.textLo))
            return
        }
        var i = 0
        while (i < shown.size && i < MAX_NOTES) {
            notes.addView(noteRow(shown[i], i))
            i++
        }
    }

    /** One notification: app and age, title, two lines of text, REPLY / action keys, dismiss key; tap opens it. */
    private fun noteRow(s: StatusBarNotification, i: Int): View {
        val c = context
        val n = s.notification
        val color = pal.c(i % 6)
        val row = LinearLayout(c)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(c, Ui.GUTTER.toFloat()), 0, Ui.dp(c, Ui.GUTTER.toFloat()))
        row.background = Ui.touchArea(c, 12f)
        val bar = View(c)
        bar.background = Ui.shape(c, color, Ui.PILL, 0f)
        row.addView(bar, LinearLayout.LayoutParams(Ui.dp(c, 7f), Ui.dp(c, 48f)))
        val icon = ImageView(c)
        val ai = AppRepo.get(c).findPackage(s.packageName)
        val raw = ai?.rawIcon
        var d: Drawable? = if (raw != null) IconFactory.copy(raw) else null
        if (d == null && n.smallIcon != null) {
            try {
                d = n.smallIcon.loadDrawable(c)
            } catch (ignored: Exception) {
            }
            if (d != null) icon.setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }
        icon.setImageDrawable(d)
        val il = LinearLayout.LayoutParams(Ui.dp(c, 34f), Ui.dp(c, 34f))
        il.leftMargin = Ui.dp(c, Ui.M.toFloat())
        row.addView(icon, il)
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(c, Ui.M.toFloat()), 0, Ui.dp(c, Ui.GUTTER.toFloat()), 0)
        val app = ai?.label ?: s.packageName
        col.addView(Ui.oneLine(Ui.text(c, (app + "  •  " + ago(s.postTime)).uppercase(Locale.getDefault()), 11f, color)))
        val t = title(n)
        val b = body(n)
        if (t != null) col.addView(Ui.oneLine(Ui.bold(c, t, 16f, pal.textHi)))
        if (b != null) {
            val bt = Ui.text(c, b, 14f, pal.textLo)
            bt.maxLines = 2
            bt.ellipsize = TextUtils.TruncateAt.END
            col.addView(bt)
        }
        NotificationActions.addTo(a, col, s, color) // LCA-T23: REPLY and the app's actions
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (s.isClearable) {
            val x = Ui.button(c, "×", Ui.darken(color, 0.55f), Ui.PILL, null)
            x.gravity = Gravity.CENTER
            x.textSize = 20 * Skin.textScale()
            x.setPadding(0, 0, 0, 0)
            x.contentDescription = "Dismiss"
            x.setOnClickListener(
                Ui.feedbackSoft {
                    val l = MediaListener.get()
                    if (l != null) {
                        try {
                            l.cancelNotification(s.key)
                        } catch (ignored: Exception) {
                        }
                    }
                },
            )
            row.addView(x, LinearLayout.LayoutParams(Ui.dp(c, 44f), Ui.dp(c, 44f)))
        }
        row.setOnClickListener {
            val pi = n.contentIntent
            if (pi == null) {
                Sfx.play(context, Sfx.DENY)
                return@setOnClickListener
            }
            Sfx.play(context, Sfx.LAUNCH)
            if (a.sendPending(pi)) {
                if ((n.flags and Notification.FLAG_AUTO_CANCEL) != 0) {
                    val l = MediaListener.get()
                    if (l != null) {
                        try {
                            l.cancelNotification(s.key)
                        } catch (ignored: Exception) {
                        }
                    }
                }
                close(false)
            } else {
                Sfx.play(context, Sfx.ERROR)
            }
        }
        return row
    }

    private fun ago(t: Long): String = ago(System.currentTimeMillis(), t, Locale.getDefault())

    private fun clearAll() {
        val l = MediaListener.get() ?: return
        try {
            l.cancelAllNotifications()
        } catch (ignored: Exception) {
        }
        Sfx.play(context, Sfx.CONFIRM)
    }

    companion object {
        /** Sidebar width and the height of its upper blocks, quick-control row height, in dp (design scale). */
        private const val SIDEBAR_DP = 58f
        private const val SIDE_BLOCK_DP = 70f
        private const val TILE_ROW_DP = 64f

        /** Most notifications listed. */
        private const val MAX_NOTES = 40

        /** Gamma used to map the brightness setting (0..255) onto the slider (0..1000) so it feels linear. */
        private const val BRIGHTNESS_GAMMA = 2.2
        private const val SLIDER_MAX = 1000

        /** Slider position (0..1000) for a system brightness value (0..255). */
        @JvmStatic
        fun sliderForBrightness(brightness: Int): Int =
            Math.round(SLIDER_MAX * (max(0, min(255, brightness)) / 255.0).pow(1 / BRIGHTNESS_GAMMA)).toInt()

        /** System brightness value (1..255) for a slider position (0..1000). */
        @JvmStatic
        fun brightnessForSlider(position: Int): Int {
            val f = max(0, min(SLIDER_MAX, position)) / SLIDER_MAX.toDouble()
            return max(1, Math.round(255 * f.pow(BRIGHTNESS_GAMMA)).toInt())
        }

        /** Age of a notification posted at t, seen at now: NOW, 5M, 3H, or the date. */
        @JvmStatic
        fun ago(now: Long, t: Long, locale: Locale): String {
            val m = (now - t) / 60000
            if (m < 1) return "NOW"
            if (m < 60) return m.toString() + "M"
            if (m < 24 * 60) return (m / 60).toString() + "H"
            return SimpleDateFormat("MMM d", locale).format(Date(t))
        }

        private fun title(n: Notification): CharSequence? {
            val e = n.extras
            val t = e?.getCharSequence(Notification.EXTRA_TITLE)
            return if (t != null && t.length > 0) t else null
        }

        private fun body(n: Notification): CharSequence? {
            val e = n.extras ?: return null
            var t = e.getCharSequence(Notification.EXTRA_BIG_TEXT)
            if (t == null || t.length == 0) t = e.getCharSequence(Notification.EXTRA_TEXT)
            return if (t != null && t.length > 0) t else null
        }
    }
}
