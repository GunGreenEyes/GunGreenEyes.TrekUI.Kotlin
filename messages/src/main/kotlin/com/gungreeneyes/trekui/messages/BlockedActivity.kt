package com.gungreeneyes.trekui.messages

import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.gungreeneyes.trekui.MessagesLook
import com.gungreeneyes.trekui.messages.data.BlockList
import com.gungreeneyes.trekui.messages.data.javaTrim

/**
 * BLOCKED: the system block list (shared with the dialer; editable while Messages is the default SMS
 * app) and this app's prefix rules (`+1900*`). ADD takes a number or a rule; tapping an entry
 * offers to unblock it. Messages from blocked senders are dropped on arrival and never notify.
 */
class BlockedActivity : BaseScreen() {
    private var list: LinearLayout? = null

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun build() {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "blocked_list"
        }
        list = l
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(l)
        }

        val side = buttons()
        side.add(SideButton(getString(R.string.btn_back), 0, "btn_back") { finish() })
        side.add(SideButton(getString(R.string.btn_add), 2, "btn_add") { add() })
        frame(getString(R.string.title_blocked), getString(R.string.footer_blocked), side, scroll)
        reload()
    }

    private fun reload() {
        load({ Blocking.entries(this) }) { entries ->
            val l = list
            if (entries == null || l == null) return@load
            l.removeAllViews()
            // Android 6.0 keeps every entry in the app's own list, so it needs no SMS role to block
            if (!Blocking.localOnly() && !Blocking.systemAvailable(this)) {
                val note = MessagesLook.text(this, getString(R.string.block_needs_default), 15f, MessagesLook.alert())
                note.setPadding(0, 0, 0, MessagesLook.dp(this, MessagesLook.M))
                l.addView(note)
            }
            if (entries.isEmpty()) {
                val empty = MessagesLook.text(this, getString(R.string.blocked_empty), 16f, MessagesLook.textLo())
                empty.gravity = Gravity.CENTER
                empty.setPadding(0, MessagesLook.dp(this, 32f), 0, 0)
                l.addView(
                    empty,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                return@load
            }
            entries.forEachIndexed { i, e ->
                val label = if (BlockList.isPrefixRule(e)) getString(R.string.prefix_rule, e) else e
                val row = MessagesLook.button(this, label, MessagesLook.accent(1 + (i % 5)), MessagesLook.PILL) {
                    confirmUnblock(e)
                }
                row.tag = "blocked_$e"
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = MessagesLook.dp(this, MessagesLook.S)
                l.addView(row, lp)
            }
        }
    }

    private fun add() {
        MessagesLook.prompt(this, getString(R.string.btn_add), "", getString(R.string.block_hint)) { value ->
            if (value.javaTrim().isEmpty()) return@prompt
            Background.run {
                val ok = Blocking.block(this, value)
                main.post {
                    if (!ok) Toast.makeText(this, R.string.block_needs_default, Toast.LENGTH_SHORT).show()
                    reload()
                }
            }
        }
    }

    private fun confirmUnblock(entry: String) {
        MessagesLook.message(
            this, getString(R.string.unblock), getString(R.string.confirm_unblock, entry),
            arrayOf(getString(R.string.unblock), getString(R.string.cancel)),
        ) { which ->
            if (which != 0) return@message
            Background.run {
                Blocking.unblock(this, entry)
                main.post(::reload)
            }
        }
    }
}
