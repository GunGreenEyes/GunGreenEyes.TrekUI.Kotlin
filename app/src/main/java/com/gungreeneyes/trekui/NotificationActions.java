package com.gungreeneyes.trekui;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Action keys under a notification in the console shade: REPLY for actions that carry a
 * {@link RemoteInput} (Google Messages, Signal, WhatsApp and any app with inline reply) and the
 * app's other actions ("MARK AS READ", "ARCHIVE"…).
 *
 * <p>REPLY opens an console reply panel (sentence-case keyboard, the app's suggested replies as keys
 * when it offers them) and fires the action's PendingIntent with the text filled in through
 * {@link RemoteInput#addResultsToIntent}, exactly as the system shade does; console never becomes a
 * messaging app and needs no SMS permission. The reply text and the notification's content are
 * only held by the open panel: nothing is stored, logged or sent anywhere but to the app that
 * posted the notification.
 */
final class NotificationActions {
    /** Most action keys under one notification. */
    static final int MAX_ACTIONS = 3;
    /** Height of an action key, in dp. */
    private static final float KEY_DP = 36;

    private NotificationActions() {}

    /** A reply-capable input of an action: free-form text, or fixed choices (or both). */
    static RemoteInput replyInput(Notification.Action action) {
        if (action == null || action.actionIntent == null) return null;
        RemoteInput[] inputs = action.getRemoteInputs();
        if (inputs == null) return null;
        for (RemoteInput in : inputs) {
            if (in == null) continue;
            if (in.getAllowFreeFormInput()) return in;
            CharSequence[] choices = in.getChoices();
            if (choices != null && choices.length > 0) return in;
        }
        return null;
    }

    /** The actions worth a key: titled, with an intent, not system "smart" contextual suggestions. */
    static List<Notification.Action> usable(Notification n) {
        List<Notification.Action> out = new ArrayList<>();
        if (n == null || n.actions == null) return out;
        for (Notification.Action a : n.actions) {
            if (a == null || a.actionIntent == null || a.title == null || a.title.length() == 0) continue;
            if (Build.VERSION.SDK_INT >= 29 && a.isContextual()) continue;
            out.add(a);
            if (out.size() >= MAX_ACTIONS) break;
        }
        return out;
    }

    /**
     * Adds a row of action keys under a shade notification (nothing when it has no usable action).
     *
     * @param column the row's text column
     */
    static void addTo(final MainActivity a, LinearLayout column, final StatusBarNotification s, int color) {
        final Notification n = s.getNotification();
        List<Notification.Action> actions = usable(n);
        if (actions.isEmpty()) return;
        LinearLayout row = new LinearLayout(a);
        row.setTag("note_actions");
        row.setPadding(0, Ui.dp(a, Ui.XS), 0, 0);
        for (int i = 0; i < actions.size(); i++) {
            final Notification.Action action = actions.get(i);
            final RemoteInput input = replyInput(action);
            String label = input != null ? a.getString(R.string.note_reply)
                    : action.title.toString().toUpperCase(Locale.getDefault());
            TextView key = Ui.button(a, label, i == 0 ? color : Ui.darken(color, 0.7f), Ui.PILL, null);
            key.setTag(input != null ? "note_reply" : "note_action_" + i);
            key.setGravity(Gravity.CENTER);
            key.setSingleLine(true);
            key.setEllipsize(android.text.TextUtils.TruncateAt.END);
            key.setPadding(Ui.dp(a, Ui.S), 0, Ui.dp(a, Ui.S), 0);
            Ui.setTextSp(key, 12);
            key.setOnClickListener(Ui.feedbackSoft(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (input != null) reply(a, s, action, input);
                    else fire(a, action.actionIntent, null);
                }
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(a, KEY_DP), 1);
            if (i > 0) lp.leftMargin = Ui.dp(a, Ui.GUTTER);
            row.addView(key, lp);
        }
        column.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Opens the console reply panel for one notification action. */
    static void reply(final MainActivity a, StatusBarNotification s, final Notification.Action action, final RemoteInput input) {
        Palette p = Prefs.get(a).palette();
        final ConsoleDialog.Built b = ConsoleDialog.base(a, a.getString(R.string.note_reply_title));
        Notification n = s.getNotification();
        Bundle extras = n.extras;
        CharSequence who = extras != null ? extras.getCharSequence(Notification.EXTRA_TITLE) : null;
        CharSequence last = extras != null ? extras.getCharSequence(Notification.EXTRA_TEXT) : null;
        if (who != null) b.body.addView(Ui.oneLine(Ui.bold(a, who, 16, p.c(1))));
        if (last != null) {
            TextView t = Ui.text(a, last, 14, p.textLo);
            t.setMaxLines(4);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(0, 0, 0, Ui.dp(a, Ui.M));
            b.body.addView(t);
        }
        CharSequence[] choices = input.getChoices();
        if (choices != null) {
            for (int i = 0; i < choices.length && i < MAX_ACTIONS; i++) {
                final CharSequence choice = choices[i];
                ConsoleDialog.addOption(b, ConsoleDialog.option(a, choice.toString(), p.c(4), new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        b.dialog.dismiss();
                        send(a, action, input, choice, true);
                    }
                }));
            }
        }
        if (!input.getAllowFreeFormInput()) {
            b.dialog.show();
            return;
        }
        final EditText e = new EditText(a);
        e.setTag("note_reply_text");
        CharSequence hint = input.getLabel();
        e.setHint(hint != null && hint.length() > 0 ? hint : a.getString(R.string.note_reply_hint));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        e.setMaxLines(5);
        e.setImeOptions(EditorInfo.IME_ACTION_SEND);
        e.setTextColor(p.textHi);
        e.setHintTextColor(Ui.alpha(p.textLo, 0.6f));
        e.setTypeface(Ui.font(a, false));
        Ui.setTextSp(e, 17);
        e.setBackground(Ui.outline(a, p.panel, p.c(2), 18, 2));
        e.setPadding(Ui.dp(a, Ui.L), Ui.dp(a, Ui.M), Ui.dp(a, Ui.L), Ui.dp(a, Ui.M));
        if (Build.VERSION.SDK_INT >= 26) e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(a, Ui.M);
        b.body.addView(e, lp);
        final Runnable ok = new Runnable() {
            @Override public void run() {
                String text = e.getText().toString().trim();
                if (text.isEmpty()) { Sfx.play(a, Sfx.DENY); return; }
                b.dialog.dismiss();
                send(a, action, input, text, false);
            }
        };
        e.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
                if (id == EditorInfo.IME_ACTION_SEND) { ok.run(); return true; }
                return false;
            }
        });
        LinearLayout row = new LinearLayout(a);
        TextView cancel = ConsoleDialog.option(a, a.getString(R.string.note_cancel), p.c(3), new View.OnClickListener() {
            @Override public void onClick(View v) { Sfx.play(a, Sfx.CANCEL); b.dialog.dismiss(); }
        });
        TextView transmit = ConsoleDialog.option(a, a.getString(R.string.note_send), p.c(0), new View.OnClickListener() {
            @Override public void onClick(View v) { ok.run(); }
        });
        transmit.setTag("note_reply_send");
        LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        h.rightMargin = Ui.dp(a, Ui.S);
        row.addView(cancel, h);
        row.addView(transmit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        b.body.addView(row);
        Window w = b.dialog.getWindow();
        if (w != null) {
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        b.dialog.show();
        e.requestFocus();
    }

    /** Fills the reply into the action's intent and fires it; reports with a chirp and a toast. */
    private static void send(Context c, Notification.Action action, RemoteInput input, CharSequence text, boolean choice) {
        Intent fill = new Intent();
        Bundle results = new Bundle();
        results.putCharSequence(input.getResultKey(), text);
        RemoteInput.addResultsToIntent(action.getRemoteInputs(), fill, results);
        if (Build.VERSION.SDK_INT >= 28) {
            RemoteInput.setResultsSource(fill, choice ? RemoteInput.SOURCE_CHOICE : RemoteInput.SOURCE_FREE_FORM_INPUT);
        }
        boolean ok = fire(c, action.actionIntent, fill);
        Toast.makeText(c, c.getString(ok ? R.string.note_reply_sent : R.string.note_reply_failed), Toast.LENGTH_SHORT).show();
    }

    /**
     * Sends an action's PendingIntent (with an optional fill-in intent), allowing it to open the
     * app from the foreground home screen on API 34+. Returns false when the app cancelled it.
     */
    static boolean fire(Context c, PendingIntent pi, Intent fill) {
        if (pi == null) return false;
        try {
            Bundle options = null;
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions basic = ActivityOptions.makeBasic();
                basic.setPendingIntentBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                        ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                options = basic.toBundle();
            }
            pi.send(c, 0, fill, null, null, null, options);
            Sfx.play(c, Sfx.CONFIRM);
            return true;
        } catch (PendingIntent.CanceledException | RuntimeException e) {
            Sfx.play(c, Sfx.ERROR);
            return false;
        }
    }
}
