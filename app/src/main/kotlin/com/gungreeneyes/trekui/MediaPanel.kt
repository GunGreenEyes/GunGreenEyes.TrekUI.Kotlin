package com.gungreeneyes.trekui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Media panel with an console source selector (default: Audible, YouTube Music, VLC). Choosing a
 * source never opens the app; it re-targets what the panel shows and controls:
 *   1. the app's live media session (needs the optional notification access), else
 *   2. a link to the app's MediaBrowserService (the background service Android Auto uses), else
 *   3. media-button key events addressed to that app only.
 * With no source configured the transport keys drive whatever is playing (system media keys).
 *
 * Session listeners, controller callbacks and the browser link exist only between resume()
 * and pause(); the browser and controllers use the application context, so nothing outlives the
 * activity. The source-name rules are pure ([knownName], [abbreviate]).
 */
internal class MediaPanel(a: MainActivity, accent: Int) : Panel(a, accent) {
    private lateinit var art: ImageView
    private lateinit var track: TextView
    private lateinit var artist: TextView
    private lateinit var hint: TextView
    private lateinit var playBtn: ImageView
    private lateinit var sourceRow: LinearLayout
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var controllerFromLink = false
    private lateinit var listener: ComponentName
    private var listening = false
    private var resumed = false
    private var browser: MediaBrowser? = null
    private var link = LINK_IDLE
    private val prefs: Prefs = Prefs.get(a)

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> onSessions(list) }

    private val cb: MediaController.Callback =
        object : MediaController.Callback() {
            override fun onPlaybackStateChanged(s: PlaybackState?) = render()

            override fun onMetadataChanged(m: MediaMetadata?) = render()

            override fun onSessionDestroyed() {
                setController(null, false)
                if (resumed) attach()
            }
        }

    override fun id(): String = Prefs.W_MEDIA

    override fun title(): String = "AUDIO SYSTEMS"

    // ---- sources ---------------------------------------------------------------------------------

    /** The package assigned to a source button (defaults: Audible, YouTube Music, VLC). */
    private fun source(slot: Int): String =
        prefs.s("mediaSrc$slot", if (slot < DEFAULT_SOURCES.size) DEFAULT_SOURCES[slot] else "").orEmpty()

    private fun installed(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        return try {
            a.packageManager.getApplicationInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Installed, distinct source packages in button order. */
    private fun activeSources(): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until SLOTS) {
            val p = source(i)
            if (installed(p) && !out.contains(p)) out.add(p)
        }
        return out
    }

    /** The selected source if still a button, else the first; null when there are none. */
    private fun selected(): String? {
        val src = activeSources()
        val sel = prefs.s("mediaSel", null)
        if (sel != null && src.contains(sel)) return sel
        return if (src.isEmpty()) null else src[0]
    }

    /** Services that answer the MediaBrowserService action (optionally in one package). */
    private fun browserServices(pkg: String?): List<ResolveInfo> {
        val i = Intent(BROWSER_ACTION)
        if (pkg != null) i.setPackage(pkg)
        val pm = a.packageManager
        if (Build.VERSION.SDK_INT >= 33) return pm.queryIntentServices(i, PackageManager.ResolveInfoFlags.of(0))
        return legacyQueryServices(pm, i)
    }

    /** Rebuilds the row of source buttons (plus "+" while a slot is free). */
    private fun buildSources() {
        sourceRow.removeAllViews()
        val src = activeSources()
        val sel = selected()
        val room = src.size < SLOTS
        val total = src.size + (if (room) 1 else 0)
        for (i in 0 until total) {
            val add = i == src.size
            val pkg = if (add) null else src[i]
            val on = pkg != null && pkg == sel
            val kind = if (total == 1) Ui.PILL else if (i == 0) Ui.LEFT else if (i == total - 1) Ui.RIGHT else Ui.SQUARE
            val color = if (add) Ui.darken(pal.c(1), 0.35f) else if (on) accent else Ui.darken(pal.c(1), 0.5f)
            val b = Ui.button(a, if (add) (if (src.isEmpty()) "+ ADD AUDIO SOURCE" else "+") else shortName(a, pkg), color, kind, null)
            b.gravity = Gravity.CENTER
            b.setTextSize(14 * Skin.textScale())
            b.setPadding(Ui.dp(a, Ui.XS.toFloat()), 0, Ui.dp(a, Ui.XS.toFloat()), 0)
            b.contentDescription = if (add) "Add audio source" else "Audio source " + shortName(a, pkg)
            val slotIndex = if (add) firstFreeSlot() else slotOf(pkg!!)
            b.setOnClickListener { v ->
                if (pkg == null) {
                    Ui.tap(v)
                    chooseApp(slotIndex)
                } else {
                    select(pkg)
                }
            }
            b.setOnLongClickListener { v ->
                Ui.longPress(v)
                chooseApp(slotIndex)
                true
            }
            val lp = LinearLayout.LayoutParams(0, -1, if (add && src.isNotEmpty()) 0.45f else 1f)
            if (i > 0) lp.leftMargin = Ui.dp(a, Ui.XS.toFloat())
            sourceRow.addView(b, lp)
        }
    }

    private fun slotOf(pkg: String): Int {
        for (i in 0 until SLOTS) if (pkg == source(i)) return i
        return 0
    }

    private fun firstFreeSlot(): Int {
        for (i in 0 until SLOTS) if (!installed(source(i))) return i
        return SLOTS - 1
    }

    /** Re-targets the panel at another source (never opens the app). */
    private fun select(pkg: String) {
        if (pkg == selected() && (controller != null || link == LINK_CONNECTING)) {
            Sfx.play(a, Sfx.PAGE)
            return
        }
        prefs.put("mediaSel", pkg)
        Sfx.play(a, Sfx.PAGE)
        disconnectLink()
        setController(null, false)
        buildSources()
        attach()
    }

    /** Media-capable apps: those exposing a MediaBrowserService, plus launchable MEDIA-category apps. */
    private fun chooseApp(slot: Int) {
        val pkgs = ArrayList<String>()
        try {
            for (ri in browserServices(null)) {
                val si = ri.serviceInfo
                if (si != null && !pkgs.contains(si.packageName)) pkgs.add(si.packageName)
            }
        } catch (ignored: RuntimeException) {
            // package manager unavailable: fall back to media-category apps
        }
        for (ai in AppRepo.get(a).apps()) {
            if (ai.mainProfile && ai.category == AppInfo.CAT_MEDIA && !pkgs.contains(ai.pkg)) pkgs.add(ai.pkg)
        }
        val choices = ArrayList<String>()
        for (p in pkgs) if (AppRepo.get(a).findPackage(p) != null && p != a.packageName) choices.add(p)
        choices.sortWith { x, y -> shortName(a, x).compareTo(shortName(a, y)) }
        val labels = ArrayList<String>()
        for (p in choices) {
            val ai = AppRepo.get(a).findPackage(p)
            labels.add((if (p == source(slot)) "● " else "") + (ai?.label?.uppercase(Locale.getDefault()) ?: p))
        }
        val canRemove = installed(source(slot))
        if (canRemove) labels.add("REMOVE THIS BUTTON")
        labels.add("RESTORE DEFAULTS (AUDIBLE • YT MUSIC • VLC)")
        a.showing(
            ConsoleDialog.options(a, "AUDIO SOURCE " + (slot + 1), labels.toTypedArray()) { w ->
                if (w < choices.size) {
                    val p = choices[w]
                    for (i in 0 until SLOTS) if (i != slot && p == source(i)) prefs.put("mediaSrc$i", "")
                    prefs.put("mediaSrc$slot", p)
                    prefs.put("mediaSel", p)
                } else if (canRemove && w == choices.size) {
                    prefs.put("mediaSrc$slot", "")
                } else {
                    for (i in 0 until SLOTS) prefs.sp.edit().remove("mediaSrc$i").apply()
                }
                Sfx.play(a, Sfx.CONFIRM)
                disconnectLink()
                setController(null, false)
                buildSources()
                attach()
            },
        )
    }

    override fun extraMenu(): Array<String> {
        val sel = selected()
        return if (sel == null) arrayOf("CHANGE SOURCE BUTTONS") else arrayOf("OPEN " + shortName(a, sel), "CHANGE SOURCE BUTTONS")
    }

    override fun onExtraMenu(which: Int) {
        val sel = selected()
        if (sel != null && which == 0) {
            openApp(null)
        } else {
            val slots = Array(SLOTS) { i -> "BUTTON " + (i + 1) + ": " + (if (installed(source(i))) shortName(a, source(i)) else "EMPTY") }
            a.showing(ConsoleDialog.options(a, "SOURCE BUTTONS", slots) { s -> chooseApp(s) })
        }
    }

    // ---- UI --------------------------------------------------------------------------------------

    override fun build() {
        val card = card!!
        msm = a.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager?
        listener = ComponentName(a, MediaListener::class.java)

        sourceRow = LinearLayout(a)
        val m = ScreenMetrics.of(a)
        val sl = LinearLayout.LayoutParams(-1, Ui.dp(a, SOURCE_ROW_DP))
        sl.bottomMargin = Ui.dp(a, Ui.M.toFloat())
        card.body.addView(sourceRow, sl)

        val row = LinearLayout(a)
        row.gravity = Gravity.CENTER_VERTICAL
        val artBox = FrameLayout(a)
        artBox.background = Ui.outline(a, pal.panel, accent, 14f, 2f)
        art = ImageView(a)
        art.scaleType = ImageView.ScaleType.CENTER_CROP
        art.outlineProvider =
            object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, Ui.dp(a, 12f).toFloat())
                }
            }
        art.clipToOutline = true
        artBox.addView(art, FrameLayout.LayoutParams(-1, -1))
        val artPx = max(Ui.dp(a, ART_DP), m.scaledPx(ART_DP))
        row.addView(artBox, LinearLayout.LayoutParams(artPx, artPx))
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(a, Ui.M.toFloat()), 0, 0, 0)
        track = Ui.oneLine(Ui.bold(a, "STANDBY", 19f, pal.textHi))
        artist = Ui.oneLine(Ui.text(a, "", 14f, pal.textLo))
        col.addView(track)
        col.addView(artist)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        // Opening the player is deliberate only: long-press the artwork (or use the panel menu).
        row.setOnLongClickListener { v ->
            Ui.longPress(v)
            openApp(v)
            true
        }
        card.body.addView(row)

        val ctl = LinearLayout(a)
        transport(ctl, MediaGlyph.PREV, pal.c(1), Ui.LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        playBtn = transport(ctl, MediaGlyph.PLAY, accent, Ui.SQUARE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        transport(ctl, MediaGlyph.NEXT, pal.c(1), Ui.RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT)
        val cl = LinearLayout.LayoutParams(-1, Ui.dp(a, TRANSPORT_ROW_DP))
        cl.topMargin = Ui.dp(a, Ui.M.toFloat())
        card.body.addView(ctl, cl)

        hint = Ui.text(a, "TIP: GRANT NOTIFICATION ACCESS FOR LIVE TRACK INFO FROM EVERY SOURCE", 12f, Ui.alpha(pal.textLo, 0.75f))
        hint.setPadding(0, Ui.dp(a, Ui.S.toFloat()), 0, 0)
        hint.setOnClickListener(Ui.feedback { explainAccess() })
        card.body.addView(hint)
        buildSources()
        render()
    }

    /** A transport key (previous / play-pause / next). */
    private fun transport(ctl: LinearLayout, glyph: Int, color: Int, shape: Int, key: Int): ImageView {
        val b = ImageView(a)
        b.setImageDrawable(MediaGlyph(glyph))
        b.scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = Ui.dp(a, 14f)
        b.setPadding(p, p, p, p)
        b.background = Ui.touch(Ui.shape(a, color, shape, 22f))
        b.contentDescription = if (glyph == MediaGlyph.PREV) "Previous" else if (glyph == MediaGlyph.NEXT) "Next" else "Play or pause"
        b.setOnClickListener(Ui.feedbackSoft { control(key) })
        val lp = LinearLayout.LayoutParams(0, -1, if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) 1.4f else 1f)
        if (ctl.childCount > 0) lp.leftMargin = Ui.dp(a, Ui.GUTTER.toFloat())
        ctl.addView(b, lp)
        return b
    }

    /** True when console has notification access (needed to read other apps' media sessions). */
    fun hasAccess(): Boolean {
        val flat = Settings.Secure.getString(a.contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(listener.flattenToString())
    }

    /** Explains notification access and opens its settings (or App info for restricted settings). */
    private fun explainAccess() {
        if (hasAccess()) return
        a.showing(
            ConsoleDialog.message(
                a,
                "TRACK DISPLAY",
                "Source buttons and play/pause/skip work without this. Granting “Notification access” lets TrekUI read the live " +
                    "playback session of any source, so titles, artwork and controls are always current. The same access lets the " +
                    "TrekUI shade list your notifications. Nothing is stored or sent anywhere.\n\n" +
                    "If the switch is greyed out (Android 13+ for apps installed from a file): open App info, tap ⋮ (top-right) › " +
                    "“Allow restricted settings”, then try again.",
                arrayOf("OPEN NOTIFICATION ACCESS", "OPEN APP INFO", "CANCEL"),
            ) { w ->
                if (w == 0) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val i = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        i.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
                        if (!a.startSafe(i, null)) a.startSafe(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), null)
                    } else {
                        a.startSafe(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), null)
                    }
                } else if (w == 1) {
                    a.startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)), null)
                }
            },
        )
    }

    // ---- lifecycle -------------------------------------------------------------------------------

    override fun resume() {
        resumed = true
        hint.visibility = if (hasAccess()) View.GONE else View.VISIBLE
        val msm = msm
        if (hasAccess() && msm != null && !listening) {
            try {
                msm.addOnActiveSessionsChangedListener(sessions, listener, a.handler())
                listening = true
            } catch (e: SecurityException) {
                listening = false
            }
        }
        buildSources()
        attach()
    }

    override fun pause() {
        resumed = false
        val msm = msm
        if (listening && msm != null) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessions)
            } catch (ignored: Exception) {
            }
            listening = false
        }
        disconnectLink()
        setController(null, false)
    }

    override fun tick() {
        if (controller == null) render()
    }

    // ---- targeting the selected app ----------------------------------------------------------------

    /** Point the panel at the selected source: live session first, then a MediaBrowser link. */
    private fun attach() {
        val pkg = selected()
        if (pkg == null) {
            render()
            return
        }
        val msm = msm
        if (hasAccess() && msm != null) {
            try {
                for (c in msm.getActiveSessions(listener)) {
                    if (pkg == c.packageName) {
                        disconnectLink()
                        setController(c, false)
                        return
                    }
                }
            } catch (ignored: SecurityException) {
            }
        }
        val controller = controller
        if (controller != null && pkg == controller.packageName) {
            render()
            return
        }
        if (link == LINK_IDLE || link == LINK_FAILED) connectLink(pkg) else render()
    }

    /** Active sessions changed: follow the selected app's session, or drop a vanished one. */
    private fun onSessions(list: List<MediaController>?) {
        val pkg = selected()
        if (pkg == null || list == null) return
        for (c in list) {
            if (pkg == c.packageName) {
                val controller = controller
                if (controller == null || controllerFromLink || c.sessionToken != controller.sessionToken) {
                    disconnectLink()
                    setController(c, false)
                }
                return
            }
        }
        if (controller != null && !controllerFromLink) {
            setController(null, false)
            if (resumed) attach()
        }
    }

    /** Connects to the app's MediaBrowserService; its session token gives a controller. */
    private fun connectLink(pkg: String) {
        disconnectLink()
        var svc: ComponentName? = null
        try {
            val l = browserServices(pkg)
            val si = l.firstOrNull()?.serviceInfo
            if (si != null) svc = ComponentName(si.packageName, si.name)
        } catch (ignored: RuntimeException) {
            // treated as no service below
        }
        if (svc == null) {
            link = LINK_FAILED
            render()
            return
        }
        var mine: MediaBrowser? = null
        val callback =
            object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    val b = browser
                    if (mine !== b || b == null) return
                    try {
                        link = LINK_UP
                        setController(MediaController(a.applicationContext, b.sessionToken), true)
                    } catch (e: Exception) {
                        link = LINK_FAILED
                        render()
                    }
                }

                override fun onConnectionFailed() {
                    if (mine !== browser) return
                    link = LINK_FAILED
                    browser = null
                    render()
                }

                override fun onConnectionSuspended() {
                    if (mine !== browser) return
                    link = LINK_FAILED
                    browser = null
                    if (controllerFromLink) setController(null, false)
                    render()
                }
            }
        try {
            val b = MediaBrowser(a.applicationContext, svc, callback, null)
            browser = b
            mine = b
            link = LINK_CONNECTING
            b.connect()
        } catch (e: Exception) {
            browser = null
            link = LINK_FAILED
        }
        render()
    }

    private fun disconnectLink() {
        val b = browser
        if (b != null) {
            try {
                b.disconnect()
            } catch (ignored: Exception) {
            }
            browser = null
        }
        link = LINK_IDLE
    }

    /** Swaps the controller the panel shows and drives (callbacks move with it). */
    private fun setController(c: MediaController?, fromLink: Boolean) {
        val old = controller
        if (old != null) {
            try {
                old.unregisterCallback(cb)
            } catch (ignored: Exception) {
            }
        }
        controller = c
        controllerFromLink = c != null && fromLink
        c?.registerCallback(cb, a.handler())
        render()
    }

    private fun isPlaying(): Boolean {
        val controller = controller
        if (controller != null) {
            val s = controller.playbackState
            return s != null && (s.state == PlaybackState.STATE_PLAYING || s.state == PlaybackState.STATE_BUFFERING)
        }
        if (selected() != null) return false
        val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
        return am != null && am.isMusicActive
    }

    /** A transport key: through the controller, else a media key addressed to the selected app, else a system media key. */
    private fun control(key: Int) {
        val pkg = selected()
        val controller = controller
        if (controller != null) {
            val tc = controller.transportControls
            if (key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                tc.skipToPrevious()
            } else if (key == KeyEvent.KEYCODE_MEDIA_NEXT) {
                tc.skipToNext()
            } else if (isPlaying()) {
                tc.pause()
            } else {
                tc.play()
            }
        } else if (pkg != null) {
            // No session or link: address the media key to the selected app only.
            val t = SystemClock.uptimeMillis()
            val k = if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) KeyEvent.KEYCODE_MEDIA_PLAY else key
            for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val i = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                i.putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(t, t, action, k, 0))
                try {
                    a.sendBroadcast(i)
                } catch (ignored: Exception) {
                }
            }
            a.handler().postDelayed({ if (resumed) attach() }, REATTACH_MS)
        } else {
            val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager? ?: return
            val t = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, key, 0))
            am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, key, 0))
        }
        a.handler().postDelayed({ if (resumed) render() }, RERENDER_MS)
    }

    /** Opens the player (its session activity if it has one). */
    private fun openApp(v: View?) {
        val pkg = selected()
        val activity = controller?.sessionActivity
        if (activity != null) {
            if (a.sendPending(activity)) return
        }
        val ai = if (pkg != null) AppRepo.get(a).findPackage(pkg) else null
        if (ai != null) a.launchApp(ai, v)
    }

    /** Updates title, artist, artwork, play/pause glyph and status from the current target. */
    private fun render() {
        if (!::track.isInitialized) return
        val pkg = selected()
        val name = if (pkg != null) shortName(a, pkg) else ""
        val playing = isPlaying()
        (playBtn.drawable as MediaGlyph).setType(if (playing) MediaGlyph.PAUSE else MediaGlyph.PLAY)
        playBtn.invalidate()
        var bmp: Bitmap? = null
        var t: String? = null
        var ar: String? = null
        val m = controller?.metadata
        if (m != null) {
            t = m.getString(MediaMetadata.METADATA_KEY_TITLE)
            if (t == null) t = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ar = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
            if (ar == null) ar = m.getString(MediaMetadata.METADATA_KEY_AUTHOR)
            if (ar == null) ar = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART)
            if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        val state: String
        if (pkg == null) {
            if (t == null) t = if (playing) "AUDIO STREAM ACTIVE" else "NO AUDIO SOURCE"
            state = if (playing) "PLAYING" else "STANDBY"
            if (ar == null) ar = "TAP + TO ADD A SOURCE"
        } else if (controller != null) {
            state = if (playing) "PLAYING" else "PAUSED"
            if (t == null) t = name + (if (playing) " • PLAYING" else " • READY")
            ar = (if (ar != null) "$ar  •  " else "") + name
        } else if (link == LINK_CONNECTING) {
            state = "LINKING"
            t = name
            ar = "ESTABLISHING LINK…"
        } else {
            state = "STANDBY"
            t = name
            ar = if (link == LINK_FAILED) "NO LIVE LINK • ▶ SENDS PLAY TO $name" else "STANDBY"
        }
        track.text = t
        artist.text = ar.uppercase(Locale.getDefault())
        if (bmp != null) {
            art.setImageBitmap(bmp)
            art.clearColorFilter()
            art.setPadding(0, 0, 0, 0)
        } else {
            val ai = if (pkg != null) AppRepo.get(a).findPackage(pkg) else null
            val p = Ui.dp(a, 12f)
            art.setPadding(p, p, p, p)
            val icon = ai?.rawIcon
            if (icon != null) {
                art.setImageDrawable(IconFactory.copy(icon))
                art.clearColorFilter()
            } else {
                art.setImageBitmap(IconFactory.glyph(a, "music"))
                art.setColorFilter(accent)
            }
        }
        card!!.status.text = if (name.isEmpty()) state else "$name • $state"
    }

    /** Black transport glyphs drawn to the button size. */
    class MediaGlyph(private var type: Int) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()

        init {
            p.color = Ui.ink()
        }

        fun setType(t: Int) {
            if (t != type) {
                type = t
                invalidateSelf()
            }
        }

        override fun draw(c: Canvas) {
            val b = bounds
            val s = min(b.width(), b.height()).toFloat()
            val x = b.centerX() - s / 2f
            val y = b.centerY() - s / 2f
            path.reset()
            when (type) {
                PLAY -> {
                    path.moveTo(x + s * 0.22f, y + s * 0.1f)
                    path.lineTo(x + s * 0.86f, y + s * 0.5f)
                    path.lineTo(x + s * 0.22f, y + s * 0.9f)
                    path.close()
                    c.drawPath(path, p)
                }
                PAUSE -> {
                    c.drawRoundRect(x + s * 0.2f, y + s * 0.12f, x + s * 0.42f, y + s * 0.88f, s * 0.05f, s * 0.05f, p)
                    c.drawRoundRect(x + s * 0.58f, y + s * 0.12f, x + s * 0.8f, y + s * 0.88f, s * 0.05f, s * 0.05f, p)
                }
                else -> {
                    val next = type == NEXT
                    val dir = if (next) 1f else -1f
                    val cx = x + s / 2f
                    for (k in 0 until 2) {
                        val off = (if (k == 0) -0.32f else 0.02f) * s * dir
                        path.moveTo(cx + off, y + s * 0.18f)
                        path.lineTo(cx + off + dir * s * 0.32f, y + s * 0.5f)
                        path.lineTo(cx + off, y + s * 0.82f)
                        path.close()
                    }
                    c.drawPath(path, p)
                    val bx = if (next) cx + s * 0.36f else cx - s * 0.36f - s * 0.08f
                    c.drawRect(bx, y + s * 0.18f, bx + s * 0.08f, y + s * 0.82f, p)
                }
            }
        }

        override fun getIntrinsicWidth(): Int = 64

        override fun getIntrinsicHeight(): Int = 64

        override fun setAlpha(alpha: Int) {
            p.alpha = alpha
        }

        override fun setColorFilter(cf: ColorFilter?) {
            p.colorFilter = cf
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        companion object {
            const val PREV = 0
            const val PLAY = 1
            const val PAUSE = 2
            const val NEXT = 3
        }
    }

    companion object {
        @JvmField
        val DEFAULT_SOURCES = arrayOf("com.audible.application", "com.google.android.apps.youtube.music", "org.videolan.vlc")
        const val SLOTS = 3
        private const val BROWSER_ACTION = "android.media.browse.MediaBrowserService"

        /** State of the MediaBrowser link to the selected app. */
        private const val LINK_IDLE = 0
        private const val LINK_CONNECTING = 1
        private const val LINK_UP = 2
        private const val LINK_FAILED = 3

        /** Longest source-button label. */
        const val MAX_NAME = 10

        /** Re-check for a session this long after sending a key to an app without one. */
        private const val REATTACH_MS = 900L

        /** Redraw this long after any key. */
        private const val RERENDER_MS = 600L

        /** Artwork size, source row and transport row heights, in dp at the design scale. */
        private const val ART_DP = 66f
        private const val SOURCE_ROW_DP = 44f
        private const val TRANSPORT_ROW_DP = 54f

        /** Button label for a source: a known short name, else the app label (or package tail) abbreviated. */
        fun shortName(c: Context, pkg: String?): String {
            if (pkg == null) return ""
            val known = knownName(pkg)
            if (known != null) return known
            val ai = AppRepo.get(c).findPackage(pkg)
            return abbreviate(ai?.label ?: pkg.substring(pkg.lastIndexOf('.') + 1))
        }

        /** Short names for well-known players, or null. */
        fun knownName(pkg: String): String? =
            when (pkg) {
                "com.audible.application" -> "AUDIBLE"
                "com.google.android.apps.youtube.music" -> "YT MUSIC"
                "org.videolan.vlc" -> "VLC"
                "com.spotify.music" -> "SPOTIFY"
                else -> null
            }

        /** Upper-case and cut to [MAX_NAME] characters. */
        fun abbreviate(label: String?): String {
            val l = label?.trim()?.uppercase(Locale.getDefault()) ?: ""
            return if (l.length > MAX_NAME) l.substring(0, MAX_NAME) else l
        }

        @Suppress("DEPRECATION") // int-flags form is the only one before API 33
        private fun legacyQueryServices(pm: PackageManager, i: Intent): List<ResolveInfo> = pm.queryIntentServices(i, 0)
    }
}
