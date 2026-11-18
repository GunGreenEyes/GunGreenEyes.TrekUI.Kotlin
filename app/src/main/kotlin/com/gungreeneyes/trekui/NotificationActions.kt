package com.gungreeneyes.trekui

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import java.util.Locale

/**
 * Action keys under a notification in the console shade: REPLY for actions that carry a
 * [RemoteInput] (Google Messages, Signal, WhatsApp and any app with inline reply) and the
 * app's other actions ("MARK AS READ", "ARCHIVE"…).
 *
 * REPLY opens an console reply panel (sentence-case keyboard, the app's suggested replies as keys
 * when it offers them) and fires the action's PendingIntent with the text filled in through
 * [RemoteInput.addResultsToIntent], exactly as the system shade does; console never becomes a
 * messaging app and needs no SMS permission. The reply text and the notification's content are
 * only held by the open panel: nothing is stored, logged or sent anywhere but to the app that
 * posted the notification.
 */
internal object NotificationActions {
    /** Most action keys under one notification. */
    const val MAX_ACTIONS = 3

    /** Height of an action key, in dp. */
    private const val KEY_DP = 36f

    /** A reply-capable input of an action: free-form text, or fixed choices (or both). */
    @JvmStatic
    fun replyInput(action: Notification.Action?): RemoteInput? {
        if (action == null || action.actionIntent == null) return null
        val inputs: Array<RemoteInput?> = action.remoteInputs ?: return null
        for (input in inputs) {
            if (input == null) continue
            if (input.allowFreeFormInput) return input
            val choices: Array<CharSequence>? = input.choices
            if (choices != null && choices.isNotEmpty()) return input
        }
        return null
    }

    /** The actions worth a key: titled, with an intent, not system "smart" contextual suggestions. */
    @JvmStatic
    fun usable(n: Notification?): List<Notification.Action> {
        val out = ArrayList<Notification.Action>()
        val actions: Array<Notification.Action?> = n?.actions ?: return out
        for (a in actions) {
            if (a == null || a.actionIntent == null || a.title.isNullOrEmpty()) continue
            if (Build.VERSION.SDK_INT >= 29 && a.isContextual) continue
            out.add(a)
            if (out.size >= MAX_ACTIONS) break
        }
        return out
    }

    /**
     * Adds a row of action keys under a shade notification (nothing when it has no usable action).
     *
     * @param column the row's text column
     */
    @JvmStatic
    fun addTo(a: MainActivity, column: LinearLayout, s: StatusBarNotification, color: Int) {
        val n = s.notification
        val actions = usable(n)
        if (actions.isEmpty()) return
        val row = LinearLayout(a)
        row.tag = "note_actions"
        row.setPadding(0, Ui.dp(a, Ui.XS.toFloat()), 0, 0)
        for (i in actions.indices) {
            val action = actions[i]
            val input = replyInput(action)
            val label = if (input != null) {
                a.getString(R.string.note_reply)
            } else {
                action.title.toString().uppercase(Locale.getDefault())
            }
            val key = Ui.button(a, label, if (i == 0) color else Ui.darken(color, 0.7f), Ui.PILL, null)
            key.tag = if (input != null) "note_reply" else "note_action_$i"
            key.gravity = Gravity.CENTER
            key.isSingleLine = true
            key.ellipsize = TextUtils.TruncateAt.END
            key.setPadding(Ui.dp(a, Ui.S.toFloat()), 0, Ui.dp(a, Ui.S.toFloat()), 0)
            Ui.setTextSp(key, 12f)
            key.setOnClickListener(
                Ui.feedbackSoft {
                    if (input != null) reply(a, s, action, input) else fire(a, action.actionIntent, null)
                },
            )
            val lp = LinearLayout.LayoutParams(0, Ui.dp(a, KEY_DP), 1f)
            if (i > 0) lp.leftMargin = Ui.dp(a, Ui.GUTTER.toFloat())
            row.addView(key, lp)
        }
        column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** Opens the console reply panel for one notification action. */
    @JvmStatic
    fun reply(a: MainActivity, s: StatusBarNotification, action: Notification.Action, input: RemoteInput) {
        val p = Prefs.get(a).palette()
        val b = ConsoleDialog.base(a, a.getString(R.string.note_reply_title))
        val n = s.notification
        val extras: Bundle? = n.extras
        val who = extras?.getCharSequence(Notification.EXTRA_TITLE)
        val last = extras?.getCharSequence(Notification.EXTRA_TEXT)
        if (who != null) b.body.addView(Ui.oneLine(Ui.bold(a, who, 16f, p.c(1))))
        if (last != null) {
            val t = Ui.text(a, last, 14f, p.textLo)
            t.maxLines = 4
            t.ellipsize = TextUtils.TruncateAt.END
            t.setPadding(0, 0, 0, Ui.dp(a, Ui.M.toFloat()))
            b.body.addView(t)
        }
        val choices: Array<CharSequence>? = input.choices
        if (choices != null) {
            var i = 0
            while (i < choices.size && i < MAX_ACTIONS) {
                val choice = choices[i]
                ConsoleDialog.addOption(
                    b,
                    ConsoleDialog.option(a, choice.toString(), p.c(4)) {
                        b.dialog.dismiss()
                        send(a, action, input, choice, true)
                    },
                )
                i++
            }
        }
        if (!input.allowFreeFormInput) {
            b.dialog.show()
            return
        }
        val e = EditText(a)
        e.tag = "note_reply_text"
        val hint = input.label
        e.hint = if (!hint.isNullOrEmpty()) hint else a.getString(R.string.note_reply_hint)
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        e.maxLines = 5
        e.imeOptions = EditorInfo.IME_ACTION_SEND
        e.setTextColor(p.textHi)
        e.setHintTextColor(Ui.alpha(p.textLo, 0.6f))
        e.typeface = Ui.font(a, false)
        Ui.setTextSp(e, 17f)
        e.background = Ui.outline(a, p.panel, p.c(2), 18f, 2f)
        e.setPadding(Ui.dp(a, Ui.L.toFloat()), Ui.dp(a, Ui.M.toFloat()), Ui.dp(a, Ui.L.toFloat()), Ui.dp(a, Ui.M.toFloat()))
        if (Build.VERSION.SDK_INT >= 26) e.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = Ui.dp(a, Ui.M.toFloat())
        b.body.addView(e, lp)
        val ok = Runnable {
            val text = e.text.toString().javaTrim()
            if (text.isEmpty()) {
                Sfx.play(a, Sfx.DENY)
                return@Runnable
            }
            b.dialog.dismiss()
            send(a, action, input, text, false)
        }
        e.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) {
                ok.run()
                true
            } else {
                false
            }
        }
        val row = LinearLayout(a)
        val cancel = ConsoleDialog.option(a, a.getString(R.string.note_cancel), p.c(3)) {
            Sfx.play(a, Sfx.CANCEL)
            b.dialog.dismiss()
        }
        val transmit = ConsoleDialog.option(a, a.getString(R.string.note_send), p.c(0)) { ok.run() }
        transmit.tag = "note_reply_send"
        val h = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        h.rightMargin = Ui.dp(a, Ui.S.toFloat())
        row.addView(cancel, h)
        row.addView(transmit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        b.body.addView(row)
        b.dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        b.dialog.show()
        e.requestFocus()
    }

    /** Fills the reply into the action's intent and fires it; reports with a chirp and a toast. */
    private fun send(c: Context, action: Notification.Action, input: RemoteInput, text: CharSequence, choice: Boolean) {
        val fill = Intent()
        val results = Bundle()
        results.putCharSequence(input.resultKey, text)
        RemoteInput.addResultsToIntent(action.remoteInputs, fill, results)
        if (Build.VERSION.SDK_INT >= 28) {
            RemoteInput.setResultsSource(fill, if (choice) RemoteInput.SOURCE_CHOICE else RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        val ok = fire(c, action.actionIntent, fill)
        Toast.makeText(c, c.getString(if (ok) R.string.note_reply_sent else R.string.note_reply_failed), Toast.LENGTH_SHORT).show()
    }

    /**
     * Sends an action's PendingIntent (with an optional fill-in intent), allowing it to open the
     * app from the foreground home screen on API 34+. Returns false when the app cancelled it.
     */
    @JvmStatic
    fun fire(c: Context, pi: PendingIntent?, fill: Intent?): Boolean {
        if (pi == null) return false
        try {
            var options: Bundle? = null
            if (Build.VERSION.SDK_INT >= 34) {
                val basic = ActivityOptions.makeBasic()
                @Suppress("DEPRECATION")
                basic.setPendingIntentBackgroundActivityStartMode(
                    if (Build.VERSION.SDK_INT >= 36) {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                    } else {
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    },
                )
                options = basic.toBundle()
            }
            pi.send(c, 0, fill, null, null, null, options)
            Sfx.play(c, Sfx.CONFIRM)
            return true
        } catch (e: PendingIntent.CanceledException) {
            Sfx.play(c, Sfx.ERROR)
            return false
        } catch (e: RuntimeException) {
            Sfx.play(c, Sfx.ERROR)
            return false
        }
    }
}
