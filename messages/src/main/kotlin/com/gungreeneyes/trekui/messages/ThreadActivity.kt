package com.gungreeneyes.trekui.messages

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.toDrawable
import com.gungreeneyes.trekui.MessagesLook
import com.gungreeneyes.trekui.messages.data.MessageItem
import com.gungreeneyes.trekui.messages.data.PhoneNumbers
import com.gungreeneyes.trekui.messages.data.javaTrim
import java.io.File

/**
 * One conversation and its compose bar (also the new-message screen and the default-SMS SENDTO / share
 * target). Messages are drawn as [BubbleView]s; the compose bar has ATTACH (system photo picker),
 * the text field, a SIM switch on dual-SIM phones and SEND. Text to one person goes out as SMS; images or
 * several recipients make it an MMS. Unsent text is kept as a draft per recipient set. Opening the thread
 * marks it read and clears its notification. Sidebar: BACK, CALL, BLOCK, DELETE.
 */
class ThreadActivity : BaseScreen() {
    private var threadId = -1L
    private var recipients: List<String> = ArrayList()
    private var title = ""
    private val messages = ArrayList<MessageItem>()
    private val attachments = ArrayList<File>()
    private var sims: List<Sims.Sim> = ArrayList()
    private var subId = -1
    private var pendingText: String? = null
    private var draftLoaded = false
    private var loadToken = 0

    /** True between onResume and onPause: only then is this thread "showing" (its notifications muted). */
    private var resumed = false

    private var input: EditText? = null
    private var toField: EditText? = null
    private var adapter: BubbleAdapter? = null
    private var list: ListView? = null
    private lateinit var observer: ContentObserver
    private val reloadTask = Runnable { reload() }

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { addAttachment(it) }
    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) addContact(data.data)
    }
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { rebuild() }

    override fun onCreate(savedInstanceState: Bundle?) {
        readIntent(intent)
        sims = Sims.active(this)
        super.onCreate(savedInstanceState)
        observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                main.removeCallbacks(reloadTask)
                main.postDelayed(reloadTask, 250)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveDraft()
        threadId = -1
        recipients = ArrayList()
        messages.clear()
        draftLoaded = false
        readIntent(intent)
        rebuild()
        reload()
    }

    /** Thread id / recipients / prefilled text / shared images from any of the entry intents. */
    private fun readIntent(i: Intent) {
        threadId = i.getLongExtra(EXTRA_THREAD, -1)
        i.getStringArrayExtra(EXTRA_ADDRESSES)?.let { recipients = it.toMutableList() }
        val data = i.data
        val action = i.action
        if (data != null && (action == Intent.ACTION_SENDTO || action == Intent.ACTION_VIEW)) {
            // sms:/smsto: URIs are usually opaque (sms:555?body=hi), where getQueryParameter throws
            val part = data.encodedSchemeSpecificPart
            recipients = PhoneNumbers.fromSchemePart(part?.substringBefore('?')?.let(Uri::decode))
            pendingText = PhoneNumbers.schemeQueryValue(part, "body")?.let(Uri::decode)?.takeIf { it.isNotEmpty() }
                ?: i.getStringExtra("sms_body")
                ?: i.getStringExtra(Intent.EXTRA_TEXT)
        } else if (action == Intent.ACTION_SEND) {
            pendingText = i.getStringExtra(Intent.EXTRA_TEXT)
            streamExtra(i)?.let(::addAttachment)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        showing = threadId
        try {
            contentResolver.registerContentObserver(MessageStore.CHANGES, true, observer)
        } catch (ignored: SecurityException) {
            // no READ_SMS
        }
        reload()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        showing = -1
        contentResolver.unregisterContentObserver(observer)
        main.removeCallbacks(reloadTask)
        saveDraft()
    }

    // ---- building ---------------------------------------------------------------------------------

    override fun build() {
        val keep = input?.text?.toString()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        if (recipients.isEmpty()) content.addView(recipientRow(), matchWrap(MessagesLook.S))

        val bubbleAdapter = BubbleAdapter()
        adapter = bubbleAdapter
        val messageList = ListView(this).apply {
            tag = "message_list"
            divider = null
            selector = 0.toDrawable()
            isStackFromBottom = true
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
            isVerticalScrollBarEnabled = false
            this.adapter = bubbleAdapter
            setOnItemLongClickListener { _, v, pos, _ ->
                MessagesLook.longPress(v)
                messageMenu(messages[pos])
                true
            }
        }
        list = messageList
        content.addView(messageList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        if (attachments.isNotEmpty()) content.addView(attachmentStrip(), matchWrap(MessagesLook.XS))
        val field = composeRow(content)
        if (keep != null) {
            field.setText(keep)
            field.setSelection(keep.length)
        } else {
            pendingText?.let {
                field.setText(it)
                field.setSelection(it.length)
                pendingText = null
                draftLoaded = true
            }
        }

        val side = buttons()
        side.add(SideButton(getString(R.string.btn_back), 0, "btn_back") { finish() })
        if (recipients.size == 1) {
            side.add(SideButton(getString(R.string.btn_call), 2, "btn_call") { call() })
        }
        if (recipients.isNotEmpty()) {
            side.add(SideButton(getString(R.string.btn_block), 3, "btn_block") { confirmBlock(this, recipients) { finish() } })
        }
        if (threadId > 0) side.add(SideButton(getString(R.string.btn_delete), 4, "btn_delete") { confirmDelete() })
        val heading = when {
            recipients.isEmpty() -> getString(R.string.title_new)
            title.isEmpty() -> recipients.joinToString(", ")
            else -> title
        }
        val footer = if (recipients.isEmpty()) getString(R.string.footer_new) else recipients.joinToString(", ")
        frame(heading, footer, side, content)
        bubbleAdapter.notifyDataSetChanged()
    }

    private fun matchWrap(bottomDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = MessagesLook.dp(this@ThreadActivity, bottomDp)
        }

    private fun field(hint: String, multiLine: Boolean): EditText = EditText(this).apply {
        this.hint = hint
        setTextColor(MessagesLook.textHi())
        setHintTextColor(MessagesLook.alpha(MessagesLook.textLo(), 0.6f))
        typeface = MessagesLook.font(this@ThreadActivity, false)
        MessagesLook.setTextSp(this, 17f)
        background = MessagesLook.outline(this@ThreadActivity, MessagesLook.panel(), MessagesLook.accent(2), 18f, 1.5f)
        val p = MessagesLook.dp(this@ThreadActivity, MessagesLook.M)
        setPadding(p, p, p, p)
        if (multiLine) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 5
        } else {
            setSingleLine(true)
        }
    }

    /** TO field + CONTACT picker for a new message. */
    private fun recipientRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val to = field(getString(R.string.to_hint), false)
        toField = to
        to.tag = "to_field"
        to.inputType = InputType.TYPE_CLASS_PHONE
        to.imeOptions = EditorInfo.IME_ACTION_NEXT
        to.setOnEditorActionListener { _, _, _ ->
            applyTypedRecipients()
            true
        }
        row.addView(to, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val contact = MessagesLook.button(this, getString(R.string.btn_contact), MessagesLook.accent(1), MessagesLook.PILL) {
            pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
        }
        contact.tag = "btn_contact"
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.leftMargin = MessagesLook.dp(this, MessagesLook.S)
        row.addView(contact, lp)
        to.requestFocus()
        return row
    }

    /** ATTACH, the text field, the SIM switch (dual SIM) and SEND, added to [content]; returns the text field. */
    private fun composeRow(content: LinearLayout): EditText {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        val gap = MessagesLook.dp(this, MessagesLook.GUTTER)

        val attach = MessagesLook.button(this, getString(R.string.btn_attach), MessagesLook.accent(1), MessagesLook.LEFT) {
            if (attachments.size >= MAX_IMAGES) {
                toast(getString(R.string.too_many_images, MAX_IMAGES))
                return@button
            }
            pickImage.launch(
                PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build(),
            )
        }
        attach.tag = "btn_attach"
        attach.contentDescription = getString(R.string.attach_description)
        row.addView(attach, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val field = field(getString(R.string.compose_hint), true)
        input = field
        field.tag = "compose_field"
        val ip = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        ip.leftMargin = gap
        ip.rightMargin = gap
        row.addView(field, ip)

        if (sims.size > 1) {
            val sim = MessagesLook.button(this, simLabel(), MessagesLook.accent(4), MessagesLook.SQUARE) { nextSim() }
            sim.tag = "btn_sim"
            val sp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            sp.rightMargin = gap
            row.addView(sim, sp)
        }

        val send = MessagesLook.button(this, getString(R.string.btn_send), MessagesLook.accent(0), MessagesLook.RIGHT) { send() }
        send.tag = "btn_send"
        row.addView(send, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(row, matchWrap(0))
        return field
    }

    /** Thumbnails of the attached images; tapping one removes it. */
    private fun attachmentStrip(): View {
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val side = MessagesLook.dp(this, 72f)
        for (f in ArrayList(attachments)) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.contentDescription = getString(R.string.remove_attachment)
            iv.background = MessagesLook.outline(this, MessagesLook.panel(), MessagesLook.accent(1), 8f, 1.5f)
            val p = MessagesLook.dp(this, 3f)
            iv.setPadding(p, p, p, p)
            val uri = Uri.fromFile(f).toString()
            val cached = Images.cached(uri, side)
            if (cached != null) {
                iv.setImageBitmap(cached)
            } else {
                Images.load(this, uri, side) { b -> if (b != null) iv.setImageBitmap(b) }
            }
            iv.setOnClickListener(
                MessagesLook.feedback {
                    attachments.remove(f)
                    f.delete()
                    rebuild()
                },
            )
            val lp = LinearLayout.LayoutParams(side, side)
            lp.rightMargin = MessagesLook.dp(this, MessagesLook.GUTTER)
            strip.addView(iv, lp)
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    // ---- data -------------------------------------------------------------------------------------

    private fun reload() {
        val token = ++loadToken
        val knownThread = threadId
        val who = ArrayList(recipients)
        load({
            var thread = knownThread
            var people: List<String> = who
            if (thread <= 0 && who.isNotEmpty()) thread = MessageStore.findThread(this, who)
            if (thread > 0 && who.isEmpty()) people = MessageStore.recipients(this, thread)
            val names = Contacts.names(this, people)
            val messages = MessageStore.thread(this, thread)
            if (thread > 0) {
                MessageStore.markRead(this, thread)
                Notifier.cancel(this, thread)
            }
            Loaded(thread, people, names, messages, MessagesSettings.drafts(this).load(people)?.text)
        }) { l ->
            if (l == null || token != loadToken) return@load
            val headerChanged = l.recipients != recipients || l.title != title || l.threadId != threadId
            threadId = l.threadId
            recipients = l.recipients
            title = l.title
            if (resumed) showing = threadId
            if (subId < 0) subId = MessagesSettings.subFor(this, convKey())
            messages.clear()
            messages.addAll(l.messages)
            if (headerChanged) rebuild()
            val field = input
            if (!draftLoaded && l.draft != null && field != null && field.text.isEmpty()) {
                field.setText(l.draft)
                field.setSelection(l.draft.length)
            }
            draftLoaded = true
            adapter?.notifyDataSetChanged()
        }
    }

    /** What one background load produced. */
    private data class Loaded(
        val threadId: Long,
        val recipients: List<String>,
        val title: String,
        val messages: List<MessageItem>,
        val draft: String?,
    )

    private fun convKey(): String = if (threadId > 0) threadId.toString() else PhoneNumbers.setKey(recipients)

    private fun saveDraft() {
        val field = input ?: return
        if (recipients.isEmpty()) return
        val text = field.text.toString()
        val who = ArrayList(recipients)
        Background.run { MessagesSettings.drafts(this).save(who, text, System.currentTimeMillis()) }
    }

    private fun applyTypedRecipients() {
        val to = toField ?: return
        val typed = PhoneNumbers.splitRecipients(to.text.toString())
        if (typed.isEmpty()) return
        recipients = typed
        threadId = -1
        rebuild()
        reload()
    }

    private fun addContact(phoneRow: Uri?) {
        if (phoneRow == null) return
        load({
            contentResolver.query(phoneRow, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }) { number ->
            val to = toField
            if (number == null || to == null) return@load
            val cur = to.text.toString().javaTrim()
            to.setText(if (cur.isEmpty()) number else "$cur, $number")
            applyTypedRecipients()
        }
    }

    private fun addAttachment(picked: Uri?) {
        if (picked == null) return
        load({ Images.importForSending(this, picked) }) { f ->
            if (f == null) {
                toast(getString(R.string.image_unreadable))
                return@load
            }
            attachments.add(f)
            rebuild()
        }
    }

    // ---- actions ----------------------------------------------------------------------------------

    private fun send() {
        if (recipients.isEmpty()) applyTypedRecipients()
        if (recipients.isEmpty()) {
            toast(getString(R.string.need_recipient))
            return
        }
        val field = input ?: return // the compose field exists once the screen is built
        val text = field.text.toString().javaTrim()
        if (text.isEmpty() && attachments.isEmpty()) return
        if (!SmsRole.isDefault(this)) {
            MessagesLook.message(
                this, getString(R.string.btn_send), getString(R.string.need_default_to_send),
                arrayOf(getString(R.string.btn_set_default), getString(R.string.cancel)),
            ) { which ->
                if (which == 0) {
                    try {
                        roleRequest.launch(SmsRole.requestIntent(this))
                    } catch (ignored: RuntimeException) {
                        toast(getString(R.string.role_unavailable))
                    }
                }
            }
            return
        }
        val to = ArrayList(recipients)
        val images = ArrayList(attachments)
        val sub = subId
        attachments.clear()
        field.setText("")
        Background.run {
            MessagesSettings.drafts(this).clear(to)
            val sent = if (images.isEmpty() && to.size == 1) {
                SmsSender.send(this, to[0], text, sub)
                true
            } else {
                MmsSender.send(this, to, text, images, sub)
            }
            main.post {
                if (!sent) toast(getString(R.string.error_send))
                reload()
            }
        }
        if (images.isNotEmpty()) rebuild()
    }

    private fun simLabel(): String = Sims.shortLabel(sims, if (subId < 0) Sims.defaultSubId() else subId).ifEmpty { "SIM" }

    private fun nextSim() {
        if (sims.size < 2) return
        val current = if (subId < 0) Sims.defaultSubId() else subId
        val idx = sims.indexOfLast { it.subId == current }.coerceAtLeast(0)
        subId = sims[(idx + 1) % sims.size].subId
        MessagesSettings.setSubFor(this, convKey(), subId)
        rebuild()
    }

    private fun call() {
        if (recipients.isEmpty()) return
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", recipients[0], null)))
        } catch (e: RuntimeException) {
            toast(getString(R.string.no_dialer))
        }
    }

    private fun confirmDelete() {
        MessagesLook.message(
            this, getString(R.string.menu_delete),
            getString(R.string.confirm_delete_thread, title.ifEmpty { recipients.joinToString(", ") }),
            arrayOf(getString(R.string.menu_delete), getString(R.string.cancel)),
        ) { which ->
            if (which != 0) return@message
            val t = threadId
            val who = ArrayList(recipients)
            Background.run {
                MessageStore.deleteThread(this, t)
                MessagesSettings.drafts(this).clear(who)
            }
            input?.setText("")
            finish()
        }
    }

    private fun messageMenu(m: MessageItem) {
        val resend = m.state == MessageItem.State.FAILED && !m.mms && m.images.isEmpty()
        val items = buildList {
            add(getString(R.string.menu_copy))
            add(getString(R.string.menu_delete))
            if (resend) add(getString(R.string.menu_resend))
            add(getString(R.string.cancel))
        }
        MessagesLook.options(this, getString(R.string.menu_message), items.toTypedArray()) { which ->
            when {
                which == 0 -> getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("message", m.body))
                which == 1 -> Background.run {
                    MessageStore.deleteMessage(this, m)
                    main.post { reload() }
                }
                which == 2 && resend -> Background.run {
                    MessageStore.deleteMessage(this, m)
                    SmsSender.send(this, m.address, m.body, m.subId)
                    main.post { reload() }
                }
            }
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }

    /** Adapter over [messages]. */
    private inner class BubbleAdapter : BaseAdapter() {
        override fun getCount(): Int = messages.size
        override fun getItem(position: Int): Any = messages[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView as? BubbleView ?: BubbleView(this@ThreadActivity, metrics?.isTwoPane == true)
            val m = messages[position]
            v.bind(m, Sims.shortLabel(sims, m.subId))
            return v
        }
    }

    internal companion object {
        const val EXTRA_THREAD = "thread_id"
        const val EXTRA_ADDRESSES = "addresses"

        /** Most images in one MMS. */
        const val MAX_IMAGES = 5

        /** The thread currently in front (its messages need no notification), or -1. */
        @Volatile
        private var showing = -1L

        /** Opens thread [threadId] (or a new message to [addresses]; both may be empty). */
        fun intent(c: Context, threadId: Long, addresses: List<String>?): Intent {
            val i = Intent(c, ThreadActivity::class.java).putExtra(EXTRA_THREAD, threadId)
            if (addresses != null) i.putExtra(EXTRA_ADDRESSES, addresses.toTypedArray())
            return i
        }

        /** True while thread [threadId] is on screen. */
        fun isShowing(threadId: Long): Boolean = threadId > 0 && showing == threadId

        @Suppress("DEPRECATION")
        private fun streamExtra(i: Intent): Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                i.getParcelableExtra(Intent.EXTRA_STREAM)
            }

        /** Asks before blocking every one of [addresses]; [after] runs once blocked (may be null). */
        fun confirmBlock(a: Activity, addresses: List<String>?, after: (() -> Unit)?) {
            if (addresses.isNullOrEmpty()) return
            val who = addresses.joinToString(", ")
            MessagesLook.message(
                a, a.getString(R.string.btn_block), a.getString(R.string.confirm_block, who),
                arrayOf(a.getString(R.string.btn_block), a.getString(R.string.cancel)),
            ) { which ->
                if (which != 0) return@message
                Background.run {
                    var done = true
                    for (x in addresses) done = done and Blocking.block(a, x)
                    a.runOnUiThread {
                        Toast.makeText(a, if (done) R.string.blocked_done else R.string.block_needs_default, Toast.LENGTH_SHORT)
                            .show()
                        if (done) after?.invoke()
                    }
                }
            }
        }
    }
}
