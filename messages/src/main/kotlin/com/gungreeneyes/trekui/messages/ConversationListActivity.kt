package com.gungreeneyes.trekui.messages

import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.toDrawable
import com.gungreeneyes.trekui.MessagesLook
import com.gungreeneyes.trekui.TrekPrefs
import com.gungreeneyes.trekui.licensing.Licensing
import com.gungreeneyes.trekui.messages.data.PhoneNumbers
import com.gungreeneyes.trekui.messages.data.ThreadGrouper
import java.util.Locale

/**
 * The main screen: every conversation, newest first, in the console frame. Sidebar: NEW, SEARCH,
 * BLOCKED, CONFIG (interface / sub-theme when the launcher is absent, delivery reports, unlock) and
 * SET DEFAULT while Messages is not the default SMS app (a banner explains why). Search filters by name
 * and number and finds message text (SMS and MMS), grouped per conversation with a match count.
 * Licensing runs here ([Licensing.init] / [Licensing.onAppResumed]).
 */
class ConversationListActivity : BaseScreen() {
    private val rows = ArrayList<ConversationRowView.Row>()
    private var conversations: List<MessageStore.Conversation> = ArrayList()
    private var adapter: RowAdapter? = null
    private var searching = false
    private var query = ""
    private var loadToken = 0
    private var askedPermissions = false
    private var builtAsDefault = false
    private lateinit var observer: ContentObserver
    private val reloadTask = Runnable { reload() }

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { afterRole() }
    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { reload() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Licensing.init(this)
        observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                main.removeCallbacks(reloadTask)
                main.postDelayed(reloadTask, 300)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Licensing.onAppResumed(this)
        try {
            contentResolver.registerContentObserver(MessageStore.CHANGES, true, observer)
        } catch (ignored: SecurityException) {
            // no READ_SMS yet
        }
        if (!askedPermissions && SmsRole.isDefault(this) && SmsRole.missingPermissions(this).isNotEmpty()) {
            askedPermissions = true
            permissionRequest.launch(SmsRole.missingPermissions(this))
        }
        rebuildIfDefaultChanged()
        reload()
    }

    override fun onPause() {
        super.onPause()
        contentResolver.unregisterContentObserver(observer)
        main.removeCallbacks(reloadTask)
    }

    private fun rebuildIfDefaultChanged() {
        if (builtAsDefault != SmsRole.isDefault(this)) rebuild()
    }

    override fun build() {
        builtAsDefault = SmsRole.isDefault(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (!builtAsDefault) {
            content.addView(
                banner(),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        if (searching) {
            val field = field(getString(R.string.search_hint))
            field.tag = "search_field"
            field.setText(query)
            field.setSelection(query.length)
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    query = s.toString()
                    main.removeCallbacks(reloadTask)
                    main.postDelayed(reloadTask, 250)
                }
            })
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = MessagesLook.dp(this, MessagesLook.S)
            content.addView(field, lp)
            field.requestFocus()
        }

        val rowAdapter = RowAdapter()
        adapter = rowAdapter
        val list = ListView(this).apply {
            tag = "conversation_list"
            divider = null
            selector = 0.toDrawable()
            isVerticalScrollBarEnabled = false
            this.adapter = rowAdapter
        }
        val empty = MessagesLook.text(
            this, getString(if (searching) R.string.search_empty else R.string.list_empty), 16f, MessagesLook.textLo(),
        )
        empty.gravity = Gravity.CENTER
        empty.setPadding(0, MessagesLook.dp(this, 32f), 0, 0)
        list.emptyView = empty
        list.setOnItemClickListener { _, v, pos, _ ->
            MessagesLook.tap(v)
            val r = rows[pos]
            startActivity(ThreadActivity.intent(this, r.threadId, r.addresses))
        }
        list.setOnItemLongClickListener { _, v, pos, _ ->
            MessagesLook.longPress(v)
            rowMenu(rows[pos])
            true
        }
        val holder = FrameLayout(this)
        holder.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        holder.addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val side = buttons()
        side.add(SideButton(getString(R.string.btn_new), 0, "btn_new") { startActivity(ThreadActivity.intent(this, -1, null)) })
        side.add(
            SideButton(getString(if (searching) R.string.btn_close_search else R.string.btn_search), 2, "btn_search") {
                toggleSearch()
            },
        )
        side.add(
            SideButton(getString(R.string.btn_blocked), 3, "btn_blocked") {
                startActivity(Intent(this, BlockedActivity::class.java))
            },
        )
        side.add(SideButton(getString(R.string.btn_config), 4, "btn_config") { config() })
        if (!builtAsDefault && SmsRole.available(this)) {
            side.add(SideButton(getString(R.string.btn_set_default), 5, "btn_default") { requestRole() })
        }
        frame(getString(R.string.title_messages), getString(R.string.footer_messages, version()), side, content)
        rowAdapter.notifyDataSetChanged()
    }

    /** "Not the default SMS app" notice with its fix. */
    private fun banner(): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.tag = "default_banner"
        val pad = MessagesLook.dp(this, MessagesLook.M)
        box.setPadding(pad, pad, pad, pad)
        box.background = MessagesLook.outline(this, MessagesLook.panel(), MessagesLook.alert(), 14f, 1.5f)
        val t = MessagesLook.text(this, getString(R.string.banner_not_default), 15f, MessagesLook.textHi())
        t.setLineSpacing(0f, 1.1f)
        box.addView(t)
        if (SmsRole.available(this)) {
            val b = MessagesLook.button(this, getString(R.string.btn_set_default), MessagesLook.accent(0), MessagesLook.PILL) {
                requestRole()
            }
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = MessagesLook.dp(this, MessagesLook.S)
            lp.gravity = Gravity.END
            box.addView(b, lp)
        }
        val outer = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        outer.bottomMargin = MessagesLook.dp(this, MessagesLook.S)
        box.layoutParams = outer
        return box
    }

    /** A text field in the interface style. */
    private fun field(hint: String): EditText = EditText(this).apply {
        this.hint = hint
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT
        setTextColor(MessagesLook.textHi())
        setHintTextColor(MessagesLook.alpha(MessagesLook.textLo(), 0.6f))
        typeface = MessagesLook.font(this@ConversationListActivity, false)
        MessagesLook.setTextSp(this, 17f)
        background = MessagesLook.outline(this@ConversationListActivity, MessagesLook.panel(), MessagesLook.accent(2), 18f, 1.5f)
        val p = MessagesLook.dp(this@ConversationListActivity, MessagesLook.M)
        setPadding(p, p, p, p)
    }

    private fun requestRole() {
        try {
            roleRequest.launch(SmsRole.requestIntent(this))
        } catch (e: RuntimeException) {
            MessagesLook.message(
                this, getString(R.string.title_messages), getString(R.string.role_unavailable),
                arrayOf(getString(R.string.ok)), null,
            )
        }
    }

    private fun afterRole() {
        val missing = SmsRole.missingPermissions(this)
        if (missing.isNotEmpty()) permissionRequest.launch(missing)
        rebuild()
        reload()
    }

    private fun toggleSearch() {
        searching = !searching
        if (!searching) query = ""
        rebuild()
        reload()
    }

    // ---- loading ----------------------------------------------------------------------------------

    private fun reload() {
        if (!SmsRole.canRead(this)) {
            if (!askedPermissions && !SmsRole.isDefault(this)) {
                askedPermissions = true
                permissionRequest.launch(SmsRole.missingPermissions(this))
            }
            rows.clear()
            adapter?.notifyDataSetChanged()
            return
        }
        val token = ++loadToken
        val q = if (searching) query.trim { it <= ' ' } else ""
        load({ if (q.isEmpty()) listRows() else searchRows(q) }) { result ->
            if (token != loadToken || result == null) return@load
            rows.clear()
            rows.addAll(result)
            adapter?.notifyDataSetChanged()
        }
    }

    /** The conversation list rows with names and drafts (background). */
    private fun listRows(): List<ConversationRowView.Row> {
        val convs = MessageStore.conversations(this)
        conversations = convs
        val drafts = MessagesSettings.drafts(this).all()
        val imageMarker = getString(R.string.image_marker)
        return convs.map { c ->
            ConversationRowView.Row(
                threadId = c.threadId,
                addresses = c.addresses,
                title = if (c.addresses.isEmpty()) getString(R.string.unknown_sender) else Contacts.names(this, c.addresses),
                snippet = if (c.snippet.isEmpty() && c.attachment) imageMarker else c.snippet,
                dateMillis = c.dateMillis,
                unread = c.unread,
                draft = drafts[PhoneNumbers.setKey(c.addresses)]?.text,
            )
        }
    }

    /** Search: conversations whose name or number match, then message hits grouped per thread. */
    private fun searchRows(q: String): List<ConversationRowView.Row> {
        if (conversations.isEmpty()) conversations = MessageStore.conversations(this)
        val lower = q.lowercase(Locale.getDefault())
        val digits = PhoneNumbers.key(q)
        val out = ArrayList<ConversationRowView.Row>()
        val seen = ArrayList<Long>()
        for (c in conversations) {
            val names = Contacts.names(this, c.addresses)
            val match = names.lowercase(Locale.getDefault()).contains(lower) ||
                (PhoneNumbers.isPhoneLike(q) && c.addresses.any { PhoneNumbers.key(it).contains(digits) })
            if (!match) continue
            out.add(
                ConversationRowView.Row(
                    threadId = c.threadId,
                    addresses = c.addresses,
                    title = names,
                    snippet = c.snippet,
                    dateMillis = c.dateMillis,
                ),
            )
            seen.add(c.threadId)
        }
        val hits = MessageStore.search(this, q)
        val imageMarker = getString(R.string.image_marker)
        for (s in ThreadGrouper.group(hits)) {
            // the hits name only who wrote the matched messages; an existing thread opens with all its members
            val members = when {
                s.threadId <= 0 -> null
                else -> conversations.firstOrNull { it.threadId == s.threadId }?.addresses
                    ?: MessageStore.recipients(this, s.threadId)
            }
            val who = if (members.isNullOrEmpty()) s.addresses else members
            val r = ConversationRowView.Row(
                threadId = s.threadId,
                addresses = who,
                title = Contacts.names(this, who),
                snippet = s.latest.preview(imageMarker),
                dateMillis = s.latest.dateMillis,
                badge = resources.getQuantityString(R.plurals.matches, s.count, s.count),
            )
            if (s.threadId in seen) out.removeAll { it.threadId == s.threadId }
            out.add(r)
        }
        return out
    }

    // ---- menus ------------------------------------------------------------------------------------

    private fun rowMenu(r: ConversationRowView.Row) {
        val items = arrayOf(
            getString(R.string.menu_mark_read), getString(R.string.menu_delete),
            getString(R.string.menu_block), getString(R.string.cancel),
        )
        MessagesLook.options(this, r.title, items) { which ->
            when (which) {
                0 -> Background.run {
                    MessageStore.markRead(this, r.threadId)
                    Notifier.cancel(this, r.threadId)
                }
                1 -> confirmDelete(r)
                2 -> ThreadActivity.confirmBlock(this, r.addresses, null)
            }
        }
    }

    private fun confirmDelete(r: ConversationRowView.Row) {
        MessagesLook.message(
            this, getString(R.string.menu_delete), getString(R.string.confirm_delete_thread, r.title),
            arrayOf(getString(R.string.menu_delete), getString(R.string.cancel)),
        ) { which ->
            if (which != 0) return@message
            Background.run {
                MessageStore.deleteThread(this, r.threadId)
                Notifier.cancel(this, r.threadId)
                MessagesSettings.drafts(this).clear(r.addresses)
                main.post { reload() }
            }
        }
    }

    /** CONFIG: interface (own choice only while the launcher is absent), delivery reports, unlock. */
    private fun config() {
        val following = MessagesTheme.followingLauncher()
        val reports = MessagesSettings.deliveryReports(this)
        val unlocked = Licensing.isUnlocked(this)
        val items = buildList {
            add(getString(if (following) R.string.config_interface_following else R.string.config_interface))
            add(getString(if (reports) R.string.config_reports_on else R.string.config_reports_off))
            if (!unlocked) add(getString(R.string.config_unlock))
            add(getString(R.string.cancel))
        }
        MessagesLook.options(this, getString(R.string.btn_config), items.toTypedArray()) { which ->
            when {
                which == 0 && following -> MessagesLook.message(
                    this, getString(R.string.config_interface), getString(R.string.following_launcher_note),
                    arrayOf(getString(R.string.ok)), null,
                )
                which == 0 -> pickInterface()
                which == 1 -> MessagesSettings.setDeliveryReports(this, !reports)
                which == 2 && !unlocked -> Licensing.openPurchaseScreen(this)
            }
        }
    }

    private fun pickInterface() {
        val names = MessagesLook.interfaceNames()
        MessagesLook.options(this, getString(R.string.config_interface), names) { which ->
            val prefs = TrekPrefs.get(this)
            val f = MessagesLook.interfaceAt(which)
            prefs.setFaction(f)
            if (MessagesLook.hasSubThemes(f)) {
                MessagesLook.options(this, names[which], MessagesLook.subThemeNames(f)) { s ->
                    prefs.setSubTheme(f, s)
                    rebuild()
                }
            } else {
                rebuild()
            }
        }
    }

    /** Adapter over [rows]. */
    private inner class RowAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = rows[position].threadId

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView as? ConversationRowView ?: ConversationRowView(this@ConversationListActivity)
            v.bind(rows[position])
            return v
        }
    }
}
