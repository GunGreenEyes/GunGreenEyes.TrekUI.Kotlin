package com.gungreeneyes.trekui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.math.max

/**
 * console dialer: keypad (with contact matching), recent calls and contacts. Calls are placed with
 * ACTION_CALL when "make phone calls" is granted (otherwise handed to the phone app pre-filled);
 * the in-call screen stays the system's. Emergency numbers always go through the system phone app.
 *
 * Responsive: on short windows (landscape phones, split screen) the keypad sits beside the
 * number and suggestions instead of under them, and the sidebar tabs share the height instead of
 * using fixed heights. MainActivity rebuilds the dialer on configuration changes.
 *
 * Number matching is pure ([t9Match], [t9Digit], [digitsOnly], [cleanDialString]) and
 * unit-tested in `DialerViewTest`.
 *
 * Java interop: MainActivity creates it with `new DialerView(this)` and calls [setInsets], [isOpen],
 * [open], [close], [back] and [onPermission].
 */
internal class DialerView(act: MainActivity) : FrameLayout(act) {

    private val a: MainActivity = act
    private val pal: Palette = Prefs.get(act).palette()
    private val metrics: ScreenMetrics = ScreenMetrics.of(act)

    /** True on short windows: keypad beside the number, flexible sidebar. */
    private val shortWindow: Boolean = metrics.heightDp < SHORT_WINDOW_DP

    private val frame: ConsoleFrame
    private val content: FrameLayout
    private val side: LinearLayout
    private val titleView: TextView
    private val footer: TextView
    private var tab = TAB_KEYPAD
    private var opened = false
    private val digits = StringBuilder()

    /** The typed number, so the activity can carry it into a rebuilt dialer. */
    var typed: String
        get() = digits.toString()
        set(value) {
            digits.setLength(0)
            digits.append(value)
            updateNumber()
        }
    private var numberView: TextView? = null
    private var matchView: TextView? = null
    private var suggest: LinearLayout? = null
    private var pendingCall: String? = null

    class Contact {
        @JvmField var id: Long = 0
        @JvmField var name: String = ""
        @JvmField var lower: String? = null
        @JvmField var starred: Boolean = false

        /** {number, label} */
        @JvmField val numbers: MutableList<Array<String>> = ArrayList()
    }

    class Recent {
        @JvmField var number: String? = null
        @JvmField var name: String? = null
        @JvmField var type: Int = 0
        @JvmField var date: Long = 0
        @JvmField var duration: Long = 0
    }

    private var contacts: List<Contact>? = null
    private var recents: List<Recent>? = null

    /** Bumped whenever cached lists are dropped; background loads from an older one are discarded. */
    private var loadGeneration = 0
    private var loadingContacts = false
    private var loadingRecents = false

    init {
        setBackgroundColor(Ui.alpha(Skin.bg(), 0.97f))
        isClickable = true

        frame = ConsoleFrame(act)
        frame.sidebarW = max(Ui.dp(act, SIDEBAR_DP), metrics.scaledPx(SIDEBAR_DP))
        frame.colorTop = pal.c(2)
        frame.colorBot = pal.c(0)
        frame.topBlocks = intArrayOf(pal.c(4))
        frame.botBlocks = intArrayOf(pal.c(1), pal.c(3))
        titleView = Ui.bold(act, "COMMUNICATIONS", 28f, pal.c(0))
        titleView.setPadding(Ui.dp(act, Ui.XS.toFloat()), 0, Ui.dp(act, Ui.HAIR.toFloat()), 0)
        frame.setTitle(titleView)
        footer = Ui.text(act, "SUBSPACE CHANNEL READY", 13f, pal.c(1))
        footer.setPadding(Ui.dp(act, Ui.XS.toFloat()), 0, Ui.dp(act, Ui.HAIR.toFloat()), 0)
        frame.setFooter(footer)

        side = LinearLayout(act)
        side.orientation = LinearLayout.VERTICAL
        frame.setSidebar(side)
        content = FrameLayout(act)
        frame.setContent(content)
        addView(frame, LayoutParams(-1, -1))
    }

    /** Keeps the dialer clear of the system bars and the keyboard (insets in px). */
    fun setInsets(top: Int, bottom: Int, ime: Int) {
        setInsets(0, top, 0, bottom, ime)
    }

    /** Keeps the dialer clear of system bars, display cutouts and the keyboard on every edge (px). */
    fun setInsets(left: Int, top: Int, right: Int, bottom: Int, ime: Int) {
        val g = if (metrics.isTwoPane) metrics.marginPx() else Ui.dp(context, 8f)
        setPadding(
            left + g, top + Ui.dp(context, Ui.XS.toFloat()), right + g,
            max(bottom, ime + bottom) + Ui.dp(context, Ui.GUTTER.toFloat()),
        )
    }

    fun isOpen(): Boolean = opened

    /** Shows the dialer on the keypad (sliding up when animated); contacts and recents reload. */
    fun open(animated: Boolean) {
        if (!opened) {
            opened = true
            Sfx.play(context, Sfx.COMM)
            visibility = VISIBLE
            if (animated) {
                alpha = 0f
                translationY = Ui.dp(context, 40f).toFloat()
                animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator(1.5f))
                    .withEndAction(null).start()
                frame.reveal()
            } else {
                alpha = 1f
                translationY = 0f
            }
        }
        dropCaches()
        show(TAB_KEYPAD)
    }

    /** Forgets loaded contacts and recents; loads still running are ignored when they finish. */
    private fun dropCaches() {
        contacts = null
        recents = null
        loadGeneration++
        loadingContacts = false
        loadingRecents = false
    }

    /** Loads contacts in the background (once at a time), then runs [then] if still current and attached. */
    private fun loadContactsAsync(then: () -> Unit) {
        if (loadingContacts) return
        loadingContacts = true
        val gen = loadGeneration
        val cr = context.applicationContext.contentResolver
        val res = resources
        IO.execute {
            val list = loadContacts(cr, res)
            post {
                if (gen != loadGeneration) return@post
                loadingContacts = false
                if (!isAttachedToWindow) return@post
                contacts = list
                then()
            }
        }
    }

    /** Loads recent calls in the background, then runs [then] if still current and attached. */
    private fun loadRecentsAsync(then: () -> Unit) {
        if (loadingRecents) return
        loadingRecents = true
        val gen = loadGeneration
        val cr = context.applicationContext.contentResolver
        IO.execute {
            val list = loadRecents(cr)
            post {
                if (gen != loadGeneration) return@post
                loadingRecents = false
                if (!isAttachedToWindow) return@post
                recents = list
                then()
            }
        }
    }

    /** Hides the dialer and the keyboard. */
    fun close(animated: Boolean) {
        if (!opened) return
        opened = false
        hideIme()
        Sfx.play(context, Sfx.CHANNEL_CLOSE)
        if (animated && Ui.animate(context)) {
            animate().alpha(0f).translationY(Ui.dp(context, 30f).toFloat()).setDuration(180)
                .withEndAction { if (!opened) visibility = GONE }.start()
        } else {
            animate().cancel()
            visibility = GONE
        }
    }

    /** Back: clear the number first, then close. */
    fun back() {
        if (tab == TAB_KEYPAD && digits.isNotEmpty()) {
            digits.setLength(0)
            updateNumber()
            return
        }
        if (tab != TAB_KEYPAD) {
            show(TAB_KEYPAD)
            return
        }
        close(true)
    }

    private fun hideIme() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    // ---- sidebar / tabs ----------------------------------------------------------------------------

    /** Sidebar: the three tabs, PHONE APP, and a filler block. */
    private fun buildSide() {
        side.removeAllViews()
        val names = arrayOf("KEYPAD", "RECENTS", "CONTACTS")
        val cols = intArrayOf(pal.c(0), pal.c(1), pal.c(3))
        for (i in names.indices) {
            val sel = i == tab
            val b = Ui.button(context, names[i], if (sel) cols[i] else Ui.darken(cols[i], 0.45f), Ui.SQUARE) {
                if (i != tab) {
                    Sfx.play(context, Sfx.PAGE)
                    show(i)
                }
            }
            b.background = Ui.touch(Ui.shape(context, if (sel) cols[i] else Ui.darken(cols[i], 0.45f), Ui.SQUARE, 0f))
            b.textSize = 14 * Skin.textScale()
            b.setPadding(Ui.dp(context, Ui.XS.toFloat()), 0, Ui.dp(context, Ui.S.toFloat()), Ui.dp(context, Ui.XS.toFloat()))
            val lp = sideKeyParams()
            if (i > 0) lp.topMargin = Ui.dp(context, Ui.GUTTER.toFloat())
            side.addView(b, lp)
        }
        val phone = Ui.button(context, "PHONE APP", pal.c(2), Ui.SQUARE) { openPhoneApp() }
        phone.background = Ui.touch(Ui.shape(context, pal.c(2), Ui.SQUARE, 0f))
        phone.textSize = 13 * Skin.textScale()
        phone.setPadding(Ui.dp(context, Ui.XS.toFloat()), 0, Ui.dp(context, Ui.S.toFloat()), Ui.dp(context, Ui.XS.toFloat()))
        val pl = sideKeyParams()
        pl.topMargin = Ui.dp(context, Ui.GUTTER.toFloat())
        side.addView(phone, pl)
        val filler = View(context)
        filler.background = Ui.shape(context, pal.c(4), Ui.SQUARE, 0f)
        val fl = LinearLayout.LayoutParams(-1, 0, if (shortWindow) 0.3f else 1f)
        fl.topMargin = Ui.dp(context, Ui.GUTTER.toFloat())
        side.addView(filler, fl)
    }

    /** Sidebar keys: fixed height normally; sharing the height on short windows so all four fit. */
    private fun sideKeyParams(): LinearLayout.LayoutParams =
        if (shortWindow) LinearLayout.LayoutParams(-1, 0, 1f)
        else LinearLayout.LayoutParams(-1, Ui.dp(context, SIDE_KEY_DP))

    /** Switches tab: KEYPAD, RECENTS or CONTACTS. */
    fun show(t: Int) {
        tab = t
        buildSide()
        content.removeAllViews()
        hideIme()
        when (t) {
            TAB_KEYPAD -> buildKeypad()
            TAB_RECENTS -> buildRecents()
            else -> buildContacts()
        }
    }

    // ---- keypad ----------------------------------------------------------------------------------

    /**
     * Number display, suggestions, the 12-key pad and CALL. On short windows the pad is a second
     * column beside the number, its rows sharing the height.
     */
    private fun buildKeypad() {
        val c = context
        footer.text = "SUBSPACE CHANNEL READY"
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        var pad = col
        if (shortWindow) {
            pad = LinearLayout(c)
            pad.orientation = LinearLayout.VERTICAL
        }

        val numRow = LinearLayout(c)
        numRow.gravity = Gravity.CENTER_VERTICAL
        val number = Ui.oneLine(Ui.bold(c, "", 36f, pal.textHi))
        numberView = number
        number.gravity = Gravity.CENTER
        number.ellipsize = TextUtils.TruncateAt.START
        number.setOnLongClickListener { v ->
            Ui.longPress(v)
            pasteNumber()
            true
        }
        numRow.addView(number, LinearLayout.LayoutParams(0, Ui.dp(c, 56f), 1f))
        val del = Ui.button(c, "DEL", pal.c(3), Ui.PILL, null)
        del.gravity = Gravity.CENTER
        del.textSize = 15 * Skin.textScale()
        del.setOnClickListener(
            Ui.feedbackSoft {
                if (digits.isNotEmpty()) {
                    digits.setLength(digits.length - 1)
                    updateNumber()
                }
            },
        )
        del.setOnLongClickListener { v ->
            Ui.longPress(v)
            digits.setLength(0)
            updateNumber()
            true
        }
        numRow.addView(del, LinearLayout.LayoutParams(Ui.dp(c, 70f), Ui.dp(c, 44f)))
        col.addView(numRow)
        val match = Ui.oneLine(Ui.text(c, "", 14f, pal.c(1)))
        matchView = match
        match.gravity = Gravity.CENTER
        col.addView(match, LinearLayout.LayoutParams(-1, -2))
        val sug = LinearLayout(c)
        suggest = sug
        sug.orientation = LinearLayout.VERTICAL
        val sl = LinearLayout.LayoutParams(-1, 0, 1f)
        sl.topMargin = Ui.dp(c, Ui.XS.toFloat())
        col.addView(sug, sl)

        val keys = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")
        var row: LinearLayout? = null
        for (i in keys.indices) {
            if (i % 3 == 0) {
                row = LinearLayout(c)
                val rl = if (shortWindow) LinearLayout.LayoutParams(-1, 0, 1f)
                else LinearLayout.LayoutParams(-1, Ui.dp(c, KEY_ROW_DP))
                if (i > 0 || !shortWindow) rl.topMargin = Ui.dp(c, Ui.GUTTER.toFloat())
                pad.addView(row, rl)
            }
            val k = keys[i]
            val color = pal.c((i / 3 + i % 3) % 6)
            val kind = if (i % 3 == 0) Ui.LEFT else if (i % 3 == 2) Ui.RIGHT else Ui.SQUARE
            val key = Ui.button(c, "", color, kind, null)
            val sub = if (k == "0") "+" else if (Character.isDigit(k[0])) LETTERS[k[0] - '0'] else ""
            key.text = if (sub.isEmpty()) k else "$k  $sub"
            key.textSize = 22 * Skin.textScale()
            key.gravity = Gravity.CENTER
            key.contentDescription = k
            key.setOnClickListener(
                Ui.feedback {
                    if (digits.length < MAX_DIGITS) {
                        digits.append(k)
                        updateNumber()
                    }
                },
            )
            if (k == "0") {
                key.setOnLongClickListener { v ->
                    Ui.longPress(v)
                    if (digits.length < MAX_DIGITS) {
                        digits.append('+')
                        updateNumber()
                    }
                    true
                }
            }
            val lp = LinearLayout.LayoutParams(0, -1, 1f)
            if (i % 3 != 0) lp.leftMargin = Ui.dp(c, Ui.GUTTER.toFloat())
            row!!.addView(key, lp)
        }
        val call = Ui.button(c, "CALL", pal.c(0), Ui.PILL, null)
        call.gravity = Gravity.CENTER
        call.textSize = 22 * Skin.textScale()
        call.setOnClickListener { v ->
            if (digits.isEmpty()) {
                // Like most dialers: CALL on an empty pad recalls the last dialled number.
                lastDialledAsync { last ->
                    if (last != null && digits.isEmpty()) {
                        digits.append(last)
                        updateNumber()
                        Ui.tap(v)
                    } else if (last == null) {
                        Sfx.play(context, Sfx.DENY)
                    }
                }
            } else {
                call(digits.toString())
            }
        }
        val cl = LinearLayout.LayoutParams(-1, Ui.dp(c, KEY_ROW_DP))
        cl.topMargin = Ui.dp(c, Ui.S.toFloat())
        col.addView(call, cl)
        if (shortWindow) {
            val panes = LinearLayout(c)
            panes.orientation = LinearLayout.HORIZONTAL
            panes.addView(col, LinearLayout.LayoutParams(0, -1, 1f))
            val pp = LinearLayout.LayoutParams(0, -1, 1f)
            pp.leftMargin = metrics.gutterPx() * 2
            panes.addView(pad, pp)
            content.addView(panes, LayoutParams(-1, -1))
        } else {
            content.addView(col, LayoutParams(-1, -1))
        }
        updateNumber()
    }

    /** Long-press on the number: paste a number from the clipboard (dialable characters only). */
    private fun pasteNumber() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
        if (cm == null || !cm.hasPrimaryClip()) return
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) return
        val t: CharSequence? = clip.getItemAt(0).coerceToText(context)
        val clean = if (t == null) "" else cleanDialString(t.toString())
        if (clean.isEmpty()) return
        digits.setLength(0)
        digits.append(clean)
        updateNumber()
    }

    /** Shows the formatted number and up to three matching contacts (by number or T9 name). */
    private fun updateNumber() {
        val number = numberView ?: return
        val match = matchView ?: return
        val sug = suggest ?: return
        val d = digits.toString()
        var f = d
        try {
            val iso = Locale.getDefault().country
            val formatted: String? = PhoneNumberUtils.formatNumber(d, if (iso.isEmpty()) "US" else iso)
            if (formatted != null) f = formatted
        } catch (ignored: Exception) {
        }
        number.text = if (d.isEmpty()) "" else f
        number.textSize = (if (d.length > 14) 26 else 36) * Skin.textScale()
        sug.removeAllViews()
        match.text = if (d.isEmpty()) "ENTER NUMBER OR NAME (T9)" else ""
        if (d.isEmpty() || !granted(Manifest.permission.READ_CONTACTS)) return
        val list = contacts
        if (list == null) {
            loadContactsAsync { if (opened && tab == TAB_KEYPAD) updateNumber() }
            return
        }
        var shown = 0
        val digitsOnly = digitsOnly(d)
        for (ct in list) {
            val hit = t9Match(ct, d, digitsOnly) ?: continue
            if (shown == 0 && digitsOnly(hit[0]).endsWith(digitsOnly) && digitsOnly.length >= FULL_NUMBER) {
                match.text = ct.name.uppercase(Locale.getDefault())
            }
            sug.addView(contactRow(ct, hit[0], hit[1], shown))
            if (++shown >= MAX_SUGGESTIONS) break
        }
    }

    // ---- recents ---------------------------------------------------------------------------------

    /** Recent calls (READ_CALL_LOG), or the permission prompt. */
    private fun buildRecents() {
        footer.text = "COMMUNICATIONS LOG"
        if (!BuildConfig.CALL_LOG_ENABLED) {
            // Play build: no READ_CALL_LOG (allowed only for default phone apps); the system phone
            // app shows the history instead.
            callHistoryHandOff()
            return
        }
        if (!granted(Manifest.permission.READ_CALL_LOG)) {
            permissionPrompt(
                "Allow TrekUI to read your call history to show recent calls here. Nothing leaves your phone.",
                Manifest.permission.READ_CALL_LOG, MainActivity.REQ_CALLLOG,
            )
            return
        }
        val list = recents
        if (list == null) {
            message("ACCESSING COMMUNICATIONS LOG…")
            loadRecentsAsync { if (opened && tab == TAB_RECENTS) show(TAB_RECENTS) }
            return
        }
        if (list.isEmpty()) {
            message("NO RECENT CALLS")
            return
        }
        val lv = list()
        lv.adapter = object : BaseAdapter() {
            override fun getCount(): Int = list.size
            override fun getItem(i: Int): Any = list[i]
            override fun getItemId(i: Int): Long = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View = recentRow(list[i], i)
        }
        content.addView(lv, LayoutParams(-1, -1))
    }

    /** Explains that call history lives in the phone app and offers a key that opens it there. */
    private fun callHistoryHandOff() {
        val c = context
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        val t = Ui.text(c, "Recent calls are kept by your phone app. Open it to see your call history.", 16f, pal.textHi)
        t.setLineSpacing(0f, 1.15f)
        col.addView(t)
        val b = Ui.button(c, "OPEN CALL HISTORY", pal.c(0), Ui.PILL) { v -> openCallHistory(v) }
        val lp = LinearLayout.LayoutParams(-1, Ui.dp(c, 52f))
        lp.topMargin = Ui.dp(c, Ui.M.toFloat())
        col.addView(b, lp)
        content.addView(col, LayoutParams(-1, -2))
    }

    /** Opens the system call log (ACTION_VIEW on CallLog.Calls), falling back to the dial pad. */
    private fun openCallHistory(v: View) {
        val log = Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI)
        if (!a.startSafe(log, v)) a.startSafe(Intent(Intent.ACTION_DIAL), v)
    }

    /** One call: direction, who, when and duration; tap calls back, long-press for more. */
    private fun recentRow(r: Recent, i: Int): View {
        val c = context
        val missed = r.type == CallLog.Calls.MISSED_TYPE ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && r.type == CallLog.Calls.REJECTED_TYPE) // API 24
        val dir = when {
            r.type == CallLog.Calls.OUTGOING_TYPE -> "↗ OUT"
            missed -> "✕ MISSED"
            r.type == CallLog.Calls.INCOMING_TYPE -> "↙ IN"
            r.type == CallLog.Calls.VOICEMAIL_TYPE -> "VOICEMAIL"
            r.type == CallLog.Calls.BLOCKED_TYPE -> "BLOCKED"
            else -> "CALL"
        }
        val color = if (missed) pal.alert else pal.c(i % 6)
        val number = r.number
        val name = r.name
        val who = if (!name.isNullOrEmpty()) name else if (number.isNullOrEmpty()) "PRIVATE NUMBER" else number
        val whenText = whenText(r.date) +
            (if (r.duration > 0) "  •  " + (r.duration / 60) + ":" + String.format(Locale.US, "%02d", r.duration % 60) else "")
        val row = rowBase(c, color, who, "$dir  •  $whenText", missed)
        row.setOnClickListener { if (!number.isNullOrEmpty()) call(number) else Sfx.play(context, Sfx.DENY) }
        row.setOnLongClickListener { v ->
            Ui.longPress(v)
            numberMenu(whoOf(r), number)
            true
        }
        return row
    }

    private fun whoOf(r: Recent): String? = if (!r.name.isNullOrEmpty()) r.name else r.number

    /** "TODAY 9:41 AM", weekday and time within six days, else the date. */
    private fun whenText(t: Long): String {
        val now = System.currentTimeMillis()
        val time = SimpleDateFormat(if (a.use24h()) "HH:mm" else "h:mm a", Locale.getDefault())
        val a1 = Calendar.getInstance()
        val b1 = Calendar.getInstance()
        b1.timeInMillis = t
        val today = a1.get(Calendar.YEAR) == b1.get(Calendar.YEAR) && a1.get(Calendar.DAY_OF_YEAR) == b1.get(Calendar.DAY_OF_YEAR)
        if (today) return "TODAY " + time.format(Date(t)).uppercase(Locale.getDefault())
        if (now - t < 6L * 86400000) {
            return SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t)).uppercase(Locale.getDefault()) +
                " " + time.format(Date(t)).uppercase(Locale.getDefault())
        }
        return SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(t)).uppercase(Locale.getDefault())
    }

    // ---- contacts --------------------------------------------------------------------------------

    /** Searchable contact list (READ_CONTACTS), or the permission prompt. */
    private fun buildContacts() {
        footer.text = "PERSONNEL DATABASE"
        if (!granted(Manifest.permission.READ_CONTACTS)) {
            permissionPrompt(
                "Allow TrekUI to read your contacts to list and search them here (and to match names on the keypad). Nothing leaves your phone.",
                Manifest.permission.READ_CONTACTS, MainActivity.REQ_CONTACTS,
            )
            return
        }
        val all = contacts
        if (all == null) {
            message("ACCESSING PERSONNEL DATABASE…")
            loadContactsAsync { if (opened && tab == TAB_CONTACTS) show(TAB_CONTACTS) }
            return
        }
        val c = context
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        val search = EditText(c)
        search.isSingleLine = true
        search.hint = "SEARCH PERSONNEL"
        search.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        search.setTextColor(pal.textHi)
        search.setHintTextColor(Ui.alpha(pal.textLo, 0.65f))
        search.typeface = Ui.font(c, false)
        search.textSize = 18 * Skin.textScale()
        search.background = Ui.outline(c, pal.panel, pal.c(3), 24f, 2f)
        search.setPadding(Ui.dp(c, Ui.L.toFloat()), 0, Ui.dp(c, Ui.M.toFloat()), 0)
        col.addView(search, LinearLayout.LayoutParams(-1, Ui.dp(c, 50f)))
        val shown = ArrayList(all)
        val lv = list()
        val ad = object : BaseAdapter() {
            override fun getCount(): Int = shown.size
            override fun getItem(i: Int): Any = shown[i]
            override fun getItemId(i: Int): Long = i.toLong()
            override fun getView(i: Int, cv: View?, parent: ViewGroup?): View {
                val ct = shown[i]
                val n = ct.numbers[0]
                return contactRow(
                    ct, n[0],
                    (if (ct.starred) "★ " else "") + n[1] + (if (ct.numbers.size > 1) "  +" + (ct.numbers.size - 1) else ""), i,
                )
            }
        }
        lv.adapter = ad
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, ct: Int, af: Int) {}

                override fun onTextChanged(s: CharSequence, st: Int, b: Int, ct: Int) {
                    val q = s.toString()
                    shown.clear()
                    for (x in all) if (contactMatches(x, q)) shown.add(x)
                    ad.notifyDataSetChanged()
                }

                override fun afterTextChanged(e: Editable?) {}
            },
        )
        val ll = LinearLayout.LayoutParams(-1, 0, 1f)
        ll.topMargin = Ui.dp(c, Ui.GUTTER.toFloat())
        col.addView(lv, ll)
        content.addView(col, LayoutParams(-1, -1))
        if (all.isEmpty()) message("NO CONTACTS WITH PHONE NUMBERS")
    }

    /** A contact with one number; tap calls it, long-press for every number and actions. */
    private fun contactRow(ct: Contact, number: String, detail: String, i: Int): View {
        val row = rowBase(context, pal.c(i % 6), ct.name, "$detail  •  $number".uppercase(Locale.getDefault()), false)
        row.setOnClickListener { call(number) }
        row.setOnLongClickListener { v ->
            Ui.longPress(v)
            contactMenu(ct)
            true
        }
        return row
    }

    /** Call any of the contact's numbers, message, or open the contact. */
    private fun contactMenu(ct: Contact) {
        val labels = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        for (n in ct.numbers) {
            labels.add("CALL " + n[1].uppercase(Locale.getDefault()) + " • " + n[0])
            acts.add { call(n[0]) }
        }
        val first = ct.numbers[0][0]
        labels.add("SEND MESSAGE")
        acts.add { message(first, true) }
        labels.add("OPEN CONTACT")
        acts.add {
            a.startSafe(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, ct.id)), null)
        }
        a.showing(ConsoleDialog.options(context, ct.name.uppercase(Locale.getDefault()), labels.toTypedArray()) { w -> acts[w]() })
    }

    /** Call, message, edit before calling, or copy a number from the call log. */
    private fun numberMenu(title: String?, number: String?) {
        if (number.isNullOrEmpty()) return
        a.showing(
            ConsoleDialog.options(
                context, title.orEmpty().uppercase(Locale.getDefault()),
                arrayOf("CALL", "SEND MESSAGE", "EDIT BEFORE CALLING", "COPY NUMBER"),
            ) { w ->
                when (w) {
                    0 -> call(number)
                    1 -> message(number, true)
                    2 -> {
                        digits.setLength(0)
                        digits.append(cleanDialString(number))
                        show(TAB_KEYPAD)
                    }
                    else -> {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                        cm?.setPrimaryClip(ClipData.newPlainText("number", number))
                        a.toast("NUMBER COPIED")
                    }
                }
            },
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun message(number: String, sms: Boolean) {
        a.startSafe(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number))), null)
    }

    // ---- shared row / list helpers -------------------------------------------------------------------

    /** A plain list without dividers or selector (rows draw their own touch feedback). */
    private fun list(): ListView {
        val lv = ListView(context)
        lv.divider = null
        lv.isVerticalScrollBarEnabled = false
        lv.isFastScrollEnabled = false
        lv.selector = ColorDrawable(Color.TRANSPARENT)
        return lv
    }

    /** Common row: colour badge with initial, name and a detail line. */
    private fun rowBase(c: Context, color: Int, name: String, detail: String, alert: Boolean): LinearLayout {
        val row = LinearLayout(c)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.dp(c, Ui.GUTTER.toFloat()), 0, Ui.dp(c, Ui.GUTTER.toFloat()))
        row.background = Ui.touchArea(c, 12f)
        row.minimumHeight = Ui.dp(c, 56f)
        val badge = Ui.bold(c, if (name.isEmpty()) "?" else name.substring(0, 1).uppercase(Locale.getDefault()), 18f, Ui.ink())
        badge.gravity = Gravity.CENTER
        badge.background = Ui.shape(c, color, Ui.LEFT, 18f)
        row.addView(badge, LinearLayout.LayoutParams(Ui.dp(c, 40f), Ui.dp(c, 40f)))
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(c, Ui.M.toFloat()), 0, Ui.dp(c, Ui.XS.toFloat()), 0)
        col.addView(Ui.oneLine(Ui.bold(c, name, 17f, if (alert) pal.alert else pal.textHi)))
        col.addView(Ui.oneLine(Ui.text(c, detail, 12f, if (alert) pal.alert else pal.textLo)))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun message(text: String) {
        val t = Ui.bold(context, text, 16f, pal.textLo)
        t.gravity = Gravity.CENTER
        content.addView(t, LayoutParams(-1, Ui.dp(context, 120f), Gravity.CENTER))
    }

    /** Explanation and GRANT key; after a permanent denial the key opens App info instead. */
    private fun permissionPrompt(why: String, perm: String, req: Int) {
        val c = context
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        val t = Ui.text(c, why, 16f, pal.textHi)
        t.setLineSpacing(0f, 1.15f)
        col.addView(t)
        val b = Ui.button(c, "GRANT ACCESS", pal.c(0), Ui.PILL) { v ->
            val p = Prefs.get(context)
            if (p.b("asked_$perm", false) && !a.shouldShowRequestPermissionRationale(perm)) {
                a.startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)), v)
            } else {
                p.put("asked_$perm", true)
                a.requestPermissions(arrayOf(perm), req)
            }
        }
        val lp = LinearLayout.LayoutParams(-1, Ui.dp(c, 52f))
        lp.topMargin = Ui.dp(c, Ui.M.toFloat())
        col.addView(b, lp)
        content.addView(col, LayoutParams(-1, -2))
    }

    private fun granted(perm: String): Boolean = context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Permission results routed from MainActivity (call, contacts, call log). */
    fun onPermission(code: Int, ok: Boolean) {
        if (code == MainActivity.REQ_CALL) {
            val n = pendingCall
            pendingCall = null
            if (n == null) return
            if (ok) place(n) else handToPhoneApp(n)
        } else if (code == MainActivity.REQ_CONTACTS || code == MainActivity.REQ_CALLLOG) {
            dropCaches()
            if (opened) show(tab)
        }
    }

    /**
     * Looks up the last outgoing number (for CALL on an empty pad) on [IO] and hands it, or null,
     * to [then] on the UI thread if the dialer is still attached.
     */
    private fun lastDialledAsync(then: (String?) -> Unit) {
        if (!BuildConfig.CALL_LOG_ENABLED || !granted(Manifest.permission.READ_CALL_LOG)) {
            then(null)
            return
        }
        val app = context.applicationContext
        IO.execute {
            val last = try {
                CallLog.Calls.getLastOutgoingCall(app)
            } catch (e: Exception) {
                null
            }
            post {
                if (isAttachedToWindow) then(last?.takeIf { it.isNotEmpty() })
            }
        }
    }

    // ---- placing calls -------------------------------------------------------------------------------

    /**
     * Places a call: emergency numbers via the phone app; directly when CALL_PHONE is granted
     * (asked once); otherwise handed to the phone app pre-filled.
     */
    fun call(number: String) {
        val n = number.trim()
        if (n.isEmpty()) {
            Sfx.play(context, Sfx.DENY)
            return
        }
        if (isEmergency(context, n)) {
            // Emergency calls always go through the system phone app.
            a.toast("EMERGENCY CALL • OPENING PHONE APP")
            handToPhoneApp(n)
            return
        }
        if (granted(Manifest.permission.CALL_PHONE)) {
            place(n)
            return
        }
        val p = Prefs.get(context)
        if (p.b("asked_call", false) && !a.shouldShowRequestPermissionRationale(Manifest.permission.CALL_PHONE)) {
            handToPhoneApp(n) // user declined direct calling: one extra tap in the phone app
            return
        }
        p.put("asked_call", true)
        pendingCall = n
        a.requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), MainActivity.REQ_CALL)
    }

    /** ACTION_CALL (CALL_PHONE granted). */
    private fun place(n: String) {
        Sfx.play(context, Sfx.COMM)
        if (a.startSafe(Intent(Intent.ACTION_CALL, Uri.fromParts("tel", n, null)), null)) {
            digits.setLength(0)
            close(false)
        }
    }

    /** ACTION_DIAL: the phone app opens with the number filled in. */
    private fun handToPhoneApp(n: String) {
        if (a.startSafe(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", n, null)), null)) {
            digits.setLength(0)
            close(false)
        }
    }

    private fun openPhoneApp() {
        val d = digits.toString()
        if (a.startSafe(Intent(Intent.ACTION_DIAL, if (d.isEmpty()) null else Uri.fromParts("tel", d, null)), null)) close(false)
    }

    companion object {
        const val TAB_KEYPAD = 0
        const val TAB_RECENTS = 1
        const val TAB_CONTACTS = 2

        /** Letters on each phone key, indexed by digit. */
        private val LETTERS = arrayOf("", "", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ")

        /** Longest number the keypad accepts. */
        const val MAX_DIGITS = 40

        /** Suggestions under the number, and digits needed before number / name (T9) matching. */
        private const val MAX_SUGGESTIONS = 3
        private const val MIN_NUMBER_MATCH = 3
        private const val MIN_T9_MATCH = 2
        private const val FULL_NUMBER = 7

        /** Recent calls listed. */
        private const val MAX_RECENTS = 100

        /** Windows shorter than this (dp) get the side-by-side keypad and flexible sidebar. */
        private const val SHORT_WINDOW_DP = 560f

        /** Sidebar width, sidebar key height and keypad row height, in dp at the design scale. */
        private const val SIDEBAR_DP = 76f
        private const val SIDE_KEY_DP = 62f
        private const val KEY_ROW_DP = 60f

        /** Fallback list of emergency numbers when TelephonyManager can't say (before API 29). */
        private val EMERGENCY = arrayOf("911", "112", "999", "000", "110", "119")

        private val NON_DIGITS = Regex("[^0-9]")
        private val NON_DIALABLE = Regex("[^0-9+*#]")
        private val NON_NUMBER = Regex("[^0-9+]")
        private val WORD_BREAK = Regex("[\\s\\-]+")

        /** Contacts and call-log queries run here, never on the main thread. */
        private val IO: ExecutorService = Http.newExecutor("trekui-dialer")

        /** Only the digits 0-9 of a string. */
        @JvmStatic
        fun digitsOnly(s: String?): String = s?.replace(NON_DIGITS, "") ?: ""

        /** A pasted or edited number reduced to dialable characters (digits, +, *, #), at most [MAX_DIGITS]. */
        @JvmStatic
        fun cleanDialString(s: String?): String {
            val c = s?.replace(NON_DIALABLE, "") ?: ""
            return if (c.length > MAX_DIGITS) c.substring(0, MAX_DIGITS) else c
        }

        /** Matches dialled digits against the contact's number or its name on the phone keypad (T9). */
        @JvmStatic
        fun t9Match(ct: Contact, typed: String, digitsOnly: String): Array<String>? {
            if (ct.numbers.isEmpty()) return null
            for (n in ct.numbers) {
                val nd = digitsOnly(n[0])
                if (digitsOnly.length >= MIN_NUMBER_MATCH && nd.contains(digitsOnly)) return n
            }
            val lower = ct.lower
            if (digitsOnly.length >= MIN_T9_MATCH && digitsOnly == typed && lower != null) {
                for (word in lower.split(WORD_BREAK)) {
                    if (word.isEmpty()) continue
                    val t9 = StringBuilder()
                    for (ch in word) t9.append(t9Digit(ch))
                    if (t9.toString().startsWith(digitsOnly)) return ct.numbers[0]
                }
            }
            return null
        }

        /** The phone key for a letter (2-9), the digit itself, or '1' for anything else. */
        @JvmStatic
        fun t9Digit(c: Char): Char {
            val ch = Character.toUpperCase(c)
            for (d in 2..9) if (LETTERS[d].indexOf(ch) >= 0) return '0' + d
            return if (Character.isDigit(ch)) ch else '1'
        }

        /** True when a contact matches a search: name contains it, or (3+ digits) a number contains them. */
        @JvmStatic
        fun contactMatches(x: Contact, query: String?): Boolean {
            val q = query?.trim()?.lowercase(Locale.getDefault()) ?: ""
            if (q.isEmpty() || (x.lower?.contains(q) == true)) return true
            val qd = digitsOnly(q)
            if (qd.length < MIN_NUMBER_MATCH) return false
            for (n in x.numbers) if (digitsOnly(n[0]).contains(qd)) return true
            return false
        }

        /** True for an emergency number (asks the system on API 29+, else a fixed list). */
        @JvmStatic
        fun isEmergency(c: Context, number: String): Boolean {
            val n = number.replace(NON_NUMBER, "")
            try {
                val tm = c.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager?
                if (Build.VERSION.SDK_INT >= 29 && tm != null) return tm.isEmergencyNumber(n)
            } catch (ignored: RuntimeException) {
                // some devices throw without telephony: use the list
            }
            return isFallbackEmergency(n)
        }

        /** The fixed emergency-number list used when the system can't be asked. */
        @JvmStatic
        fun isFallbackEmergency(digits: String): Boolean = EMERGENCY.any { it == digits }

        /** Contacts with phone numbers, starred first then by name; numbers de-duplicated, primary first. */
        private fun loadContacts(resolver: ContentResolver, res: Resources): List<Contact> {
            val map = LinkedHashMap<Long, Contact>()
            val proj = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
                ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL, ContactsContract.CommonDataKinds.Phone.STARRED,
                ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
            )
            try {
                resolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null,
                    ContactsContract.CommonDataKinds.Phone.STARRED + " DESC, " +
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC",
                )?.use { cur ->
                    while (cur.moveToNext()) {
                        val id = cur.getLong(0)
                        val number: String? = cur.getString(2)
                        if (number == null || number.trim().isEmpty()) continue
                        var ct = map[id]
                        if (ct == null) {
                            ct = Contact()
                            ct.id = id
                            ct.name = if (cur.isNull(1)) number else cur.getString(1)
                            ct.lower = ct.name.lowercase(Locale.getDefault())
                            ct.starred = cur.getInt(5) == 1
                            map[id] = ct
                        }
                        val label = try {
                            ContactsContract.CommonDataKinds.Phone.getTypeLabel(res, cur.getInt(3), cur.getString(4)).toString()
                        } catch (e: Exception) {
                            "PHONE"
                        }
                        val nd = number.replace(NON_NUMBER, "")
                        if (ct.numbers.any { it[0].replace(NON_NUMBER, "") == nd }) continue
                        val entry = arrayOf(number.trim(), label)
                        if (cur.getInt(6) == 1) ct.numbers.add(0, entry) else ct.numbers.add(entry)
                    }
                }
            } catch (ignored: RuntimeException) {
                // provider unavailable or permission revoked mid-query: show what was read
            }
            return ArrayList(map.values)
        }

        /** The latest [MAX_RECENTS] calls, newest first. */
        private fun loadRecents(resolver: ContentResolver): List<Recent> {
            val out = ArrayList<Recent>()
            val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls.DURATION)
            try {
                resolver.query(CallLog.Calls.CONTENT_URI, proj, null, null, CallLog.Calls.DATE + " DESC")?.use { cur ->
                    while (cur.moveToNext() && out.size < MAX_RECENTS) {
                        val r = Recent()
                        r.number = cur.getString(0)
                        r.name = cur.getString(1)
                        r.type = cur.getInt(2)
                        r.date = cur.getLong(3)
                        r.duration = cur.getLong(4)
                        out.add(r)
                    }
                }
            } catch (ignored: RuntimeException) {
                // provider unavailable or permission revoked mid-query
            }
            return out
        }
    }
}
