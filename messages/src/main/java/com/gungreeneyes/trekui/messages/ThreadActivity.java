package com.gungreeneyes.trekui.messages;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import com.gungreeneyes.trekui.MessagesLook;
import com.gungreeneyes.trekui.messages.data.DraftStore;
import com.gungreeneyes.trekui.messages.data.MessageItem;
import com.gungreeneyes.trekui.messages.data.PhoneNumbers;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One conversation and its compose bar (also the new-message screen and the default-SMS SENDTO / share
 * target). Messages are drawn as {@link BubbleView}s; the compose bar has ATTACH (system photo picker),
 * the text field, a SIM switch on dual-SIM phones and SEND. Text to one person goes out as SMS; images or
 * several recipients make it an MMS. Unsent text is kept as a draft per recipient set. Opening the thread
 * marks it read and clears its notification. Sidebar: BACK, CALL, BLOCK, DELETE.
 */
public final class ThreadActivity extends BaseScreen {
    static final String EXTRA_THREAD = "thread_id";
    static final String EXTRA_ADDRESSES = "addresses";
    /** Most images in one MMS. */
    static final int MAX_IMAGES = 5;

    /** The thread currently in front (its messages need no notification), or -1. */
    private static volatile long showing = -1;

    private long threadId = -1;
    private List<String> recipients = new ArrayList<>();
    private String title = "";
    private final List<MessageItem> messages = new ArrayList<>();
    private final List<File> attachments = new ArrayList<>();
    private List<Sims.Sim> sims = new ArrayList<>();
    private int subId = -1;
    private String pendingText;
    private boolean draftLoaded;
    private int loadToken;

    private EditText input;
    private EditText toField;
    private BubbleAdapter adapter;
    private ListView list;
    private ContentObserver observer;
    private final Runnable reload = this::reload;

    private final ActivityResultLauncher<PickVisualMediaRequest> pickImage = registerForActivityResult(
            new ActivityResultContracts.PickVisualMedia(), this::addAttachment);
    private final ActivityResultLauncher<Intent> pickContact = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> {
                if (r.getResultCode() == Activity.RESULT_OK && r.getData() != null) addContact(r.getData().getData());
            });
    private final ActivityResultLauncher<Intent> roleRequest = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> rebuild());

    /** Opens thread {@code threadId} (or a new message to {@code addresses}; both may be empty). */
    public static Intent intent(Context c, long threadId, List<String> addresses) {
        Intent i = new Intent(c, ThreadActivity.class).putExtra(EXTRA_THREAD, threadId);
        if (addresses != null) i.putExtra(EXTRA_ADDRESSES, addresses.toArray(new String[0]));
        return i;
    }

    /** True while thread {@code threadId} is on screen. */
    static boolean isShowing(long threadId) {
        return threadId > 0 && showing == threadId;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        readIntent(getIntent());
        sims = Sims.active(this);
        super.onCreate(savedInstanceState);
        observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                main.removeCallbacks(reload);
                main.postDelayed(reload, 250);
            }
        };
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        saveDraft();
        threadId = -1;
        recipients = new ArrayList<>();
        messages.clear();
        draftLoaded = false;
        readIntent(intent);
        rebuild();
        reload();
    }

    /** Thread id / recipients / prefilled text / shared images from any of the entry intents. */
    private void readIntent(Intent i) {
        threadId = i.getLongExtra(EXTRA_THREAD, -1);
        String[] a = i.getStringArrayExtra(EXTRA_ADDRESSES);
        if (a != null) recipients = new ArrayList<>(Arrays.asList(a));
        Uri data = i.getData();
        String action = i.getAction();
        if (data != null && (Intent.ACTION_SENDTO.equals(action) || Intent.ACTION_VIEW.equals(action))) {
            recipients = PhoneNumbers.fromSchemePart(data.getSchemeSpecificPart());
            String body = data.getQueryParameter("body");
            if (body == null) body = i.getStringExtra("sms_body");
            if (body == null) body = i.getStringExtra(Intent.EXTRA_TEXT);
            pendingText = body;
        } else if (Intent.ACTION_SEND.equals(action)) {
            pendingText = i.getStringExtra(Intent.EXTRA_TEXT);
            Uri stream = streamExtra(i);
            if (stream != null) addAttachment(stream);
        }
    }

    @SuppressWarnings("deprecation")
    private static Uri streamExtra(Intent i) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return i.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        return i.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showing = threadId;
        try {
            getContentResolver().registerContentObserver(MessageStore.CHANGES, true, observer);
        } catch (SecurityException ignored) {
            // no READ_SMS
        }
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        showing = -1;
        getContentResolver().unregisterContentObserver(observer);
        main.removeCallbacks(reload);
        saveDraft();
    }

    // ---- building ---------------------------------------------------------------------------------

    @Override
    protected void build() {
        String keep = input != null ? input.getText().toString() : null;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        if (recipients.isEmpty()) content.addView(recipientRow(), matchWrap(MessagesLook.S));

        list = new ListView(this);
        list.setTag("message_list");
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setStackFromBottom(true);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_NORMAL);
        list.setVerticalScrollBarEnabled(false);
        adapter = new BubbleAdapter();
        list.setAdapter(adapter);
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            MessagesLook.longPress(v);
            messageMenu(messages.get(pos));
            return true;
        });
        content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (!attachments.isEmpty()) content.addView(attachmentStrip(), matchWrap(MessagesLook.XS));
        content.addView(composeRow(), matchWrap(0));
        if (keep != null) {
            input.setText(keep);
            input.setSelection(keep.length());
        } else if (pendingText != null) {
            input.setText(pendingText);
            input.setSelection(pendingText.length());
            pendingText = null;
            draftLoaded = true;
        }

        List<SideButton> side = buttons();
        side.add(new SideButton(getString(R.string.btn_back), 0, "btn_back", v -> finish()));
        if (recipients.size() == 1) {
            side.add(new SideButton(getString(R.string.btn_call), 2, "btn_call", v -> call()));
        }
        if (!recipients.isEmpty()) {
            side.add(new SideButton(getString(R.string.btn_block), 3, "btn_block",
                    v -> confirmBlock(this, recipients, this::finish)));
        }
        if (threadId > 0) side.add(new SideButton(getString(R.string.btn_delete), 4, "btn_delete", v -> confirmDelete()));
        String heading = recipients.isEmpty() ? getString(R.string.title_new) : (title.isEmpty() ? String.join(", ", recipients) : title);
        String footer = recipients.isEmpty() ? getString(R.string.footer_new) : String.join(", ", recipients);
        frame(heading, footer, side, content);
        adapter.notifyDataSetChanged();
    }

    private LinearLayout.LayoutParams matchWrap(int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = MessagesLook.dp(this, bottomDp);
        return lp;
    }

    private EditText field(String hint, boolean multiLine) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(MessagesLook.textHi());
        e.setHintTextColor(MessagesLook.alpha(MessagesLook.textLo(), 0.6f));
        e.setTypeface(MessagesLook.font(this, false));
        MessagesLook.setTextSp(e, 17f);
        e.setBackground(MessagesLook.outline(this, MessagesLook.panel(), MessagesLook.accent(2), 18f, 1.5f));
        int p = MessagesLook.dp(this, MessagesLook.M);
        e.setPadding(p, p, p, p);
        if (multiLine) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMaxLines(5);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    /** TO field + CONTACT picker for a new message. */
    private View recipientRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        toField = field(getString(R.string.to_hint), false);
        toField.setTag("to_field");
        toField.setInputType(InputType.TYPE_CLASS_PHONE);
        toField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        toField.setOnEditorActionListener((v, actionId, e) -> {
            applyTypedRecipients();
            return true;
        });
        row.addView(toField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView contact = MessagesLook.button(this, getString(R.string.btn_contact), MessagesLook.accent(1),
                MessagesLook.PILL, v -> pickContact.launch(new Intent(Intent.ACTION_PICK,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI)));
        contact.setTag("btn_contact");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = MessagesLook.dp(this, MessagesLook.S);
        row.addView(contact, lp);
        toField.requestFocus();
        return row;
    }

    /** ATTACH, the text field, the SIM switch (dual SIM) and SEND. */
    private View composeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        int gap = MessagesLook.dp(this, MessagesLook.GUTTER);

        TextView attach = MessagesLook.button(this, getString(R.string.btn_attach), MessagesLook.accent(1),
                MessagesLook.LEFT, v -> {
                    if (attachments.size() >= MAX_IMAGES) {
                        toast(getString(R.string.too_many_images, MAX_IMAGES));
                        return;
                    }
                    pickImage.launch(new PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
                });
        attach.setTag("btn_attach");
        attach.setContentDescription(getString(R.string.attach_description));
        row.addView(attach, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        input = field(getString(R.string.compose_hint), true);
        input.setTag("compose_field");
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ip.leftMargin = gap;
        ip.rightMargin = gap;
        row.addView(input, ip);

        if (sims.size() > 1) {
            TextView sim = MessagesLook.button(this, simLabel(), MessagesLook.accent(4), MessagesLook.SQUARE, v -> nextSim());
            sim.setTag("btn_sim");
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            sp.rightMargin = gap;
            row.addView(sim, sp);
        }

        TextView send = MessagesLook.button(this, getString(R.string.btn_send), MessagesLook.accent(0),
                MessagesLook.RIGHT, v -> send());
        send.setTag("btn_send");
        row.addView(send, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** Thumbnails of the attached images; tapping one removes it. */
    private View attachmentStrip() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        int side = MessagesLook.dp(this, 72f);
        for (File f : new ArrayList<>(attachments)) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setContentDescription(getString(R.string.remove_attachment));
            iv.setBackground(MessagesLook.outline(this, MessagesLook.panel(), MessagesLook.accent(1), 8f, 1.5f));
            int p = MessagesLook.dp(this, 3f);
            iv.setPadding(p, p, p, p);
            String uri = Uri.fromFile(f).toString();
            Bitmap cached = Images.cached(uri, side);
            if (cached != null) iv.setImageBitmap(cached);
            else Images.load(this, uri, side, b -> { if (b != null) iv.setImageBitmap(b); });
            iv.setOnClickListener(MessagesLook.feedback(v -> {
                attachments.remove(f);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                rebuild();
            }));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
            lp.rightMargin = MessagesLook.dp(this, MessagesLook.GUTTER);
            strip.addView(iv, lp);
        }
        scroll.addView(strip);
        return scroll;
    }

    // ---- data -------------------------------------------------------------------------------------

    private void reload() {
        final int token = ++loadToken;
        final long knownThread = threadId;
        final List<String> who = new ArrayList<>(recipients);
        load(() -> {
            Loaded l = new Loaded();
            l.threadId = knownThread;
            l.recipients = who;
            if (l.threadId <= 0 && !who.isEmpty()) l.threadId = MessageStore.findThread(this, who);
            if (l.threadId > 0 && who.isEmpty()) l.recipients = MessageStore.recipients(this, l.threadId);
            l.title = Contacts.names(this, l.recipients);
            l.messages = MessageStore.thread(this, l.threadId);
            if (l.threadId > 0) {
                MessageStore.markRead(this, l.threadId);
                Notifier.cancel(this, l.threadId);
            }
            DraftStore.Draft d = MessagesSettings.drafts(this).load(l.recipients);
            l.draft = d == null ? null : d.text;
            return l;
        }, l -> {
            if (l == null || token != loadToken) return;
            boolean headerChanged = !l.recipients.equals(recipients) || !l.title.equals(title) || l.threadId != threadId;
            threadId = l.threadId;
            recipients = l.recipients;
            title = l.title;
            showing = threadId;
            if (subId < 0) subId = MessagesSettings.subFor(this, convKey());
            messages.clear();
            messages.addAll(l.messages);
            if (headerChanged) rebuild();
            if (!draftLoaded && l.draft != null && input != null && input.getText().length() == 0) {
                input.setText(l.draft);
                input.setSelection(l.draft.length());
            }
            draftLoaded = true;
            if (adapter != null) adapter.notifyDataSetChanged();
        });
    }

    /** What one background load produced. */
    private static final class Loaded {
        long threadId;
        List<String> recipients;
        String title;
        List<MessageItem> messages;
        String draft;
    }

    private String convKey() {
        return threadId > 0 ? String.valueOf(threadId) : PhoneNumbers.setKey(recipients);
    }

    private void saveDraft() {
        if (input == null || recipients.isEmpty()) return;
        final String text = input.getText().toString();
        final List<String> who = new ArrayList<>(recipients);
        Background.run(() -> MessagesSettings.drafts(this).save(who, text, System.currentTimeMillis()));
    }

    private void applyTypedRecipients() {
        if (toField == null) return;
        List<String> typed = PhoneNumbers.splitRecipients(toField.getText().toString());
        if (typed.isEmpty()) return;
        recipients = typed;
        threadId = -1;
        rebuild();
        reload();
    }

    private void addContact(Uri phoneRow) {
        if (phoneRow == null) return;
        load(() -> {
            try (Cursor c = getContentResolver().query(phoneRow,
                    new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null)) {
                return c != null && c.moveToFirst() ? c.getString(0) : null;
            }
        }, number -> {
            if (number == null || toField == null) return;
            String cur = toField.getText().toString().trim();
            toField.setText(cur.isEmpty() ? number : cur + ", " + number);
            applyTypedRecipients();
        });
    }

    private void addAttachment(Uri picked) {
        if (picked == null) return;
        load(() -> Images.importForSending(this, picked), f -> {
            if (f == null) {
                toast(getString(R.string.image_unreadable));
                return;
            }
            attachments.add(f);
            rebuild();
        });
    }

    // ---- actions ----------------------------------------------------------------------------------

    private void send() {
        if (recipients.isEmpty()) applyTypedRecipients();
        if (recipients.isEmpty()) {
            toast(getString(R.string.need_recipient));
            return;
        }
        final String text = input.getText().toString().trim();
        if (text.isEmpty() && attachments.isEmpty()) return;
        if (!SmsRole.isDefault(this)) {
            MessagesLook.message(this, getString(R.string.btn_send), getString(R.string.need_default_to_send),
                    new String[]{getString(R.string.btn_set_default), getString(R.string.cancel)}, which -> {
                        if (which == 0) {
                            try {
                                roleRequest.launch(SmsRole.requestIntent(this));
                            } catch (RuntimeException ignored) {
                                toast(getString(R.string.role_unavailable));
                            }
                        }
                    });
            return;
        }
        final List<String> to = new ArrayList<>(recipients);
        final List<File> images = new ArrayList<>(attachments);
        final int sub = subId;
        attachments.clear();
        input.setText("");
        Background.run(() -> {
            MessagesSettings.drafts(this).clear(to);
            boolean ok = true;
            if (images.isEmpty() && to.size() == 1) SmsSender.send(this, to.get(0), text, sub);
            else ok = MmsSender.send(this, to, text, images, sub);
            final boolean sent = ok;
            main.post(() -> {
                if (!sent) toast(getString(R.string.error_send));
                reload();
            });
        });
        if (!images.isEmpty()) rebuild();
    }

    private String simLabel() {
        String l = Sims.shortLabel(sims, subId < 0 ? Sims.defaultSubId() : subId);
        return l.isEmpty() ? "SIM" : l;
    }

    private void nextSim() {
        if (sims.size() < 2) return;
        int current = subId < 0 ? Sims.defaultSubId() : subId;
        int idx = 0;
        for (int i = 0; i < sims.size(); i++) if (sims.get(i).subId == current) idx = i;
        subId = sims.get((idx + 1) % sims.size()).subId;
        MessagesSettings.setSubFor(this, convKey(), subId);
        rebuild();
    }

    private void call() {
        if (recipients.isEmpty()) return;
        try {
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", recipients.get(0), null)));
        } catch (RuntimeException e) {
            toast(getString(R.string.no_dialer));
        }
    }

    private void confirmDelete() {
        MessagesLook.message(this, getString(R.string.menu_delete),
                getString(R.string.confirm_delete_thread, title.isEmpty() ? String.join(", ", recipients) : title),
                new String[]{getString(R.string.menu_delete), getString(R.string.cancel)}, which -> {
                    if (which != 0) return;
                    final long t = threadId;
                    final List<String> who = new ArrayList<>(recipients);
                    Background.run(() -> {
                        MessageStore.deleteThread(this, t);
                        MessagesSettings.drafts(this).clear(who);
                    });
                    input.setText("");
                    finish();
                });
    }

    /** Asks before blocking every one of {@code addresses}; {@code after} runs once blocked (may be null). */
    static void confirmBlock(Activity a, List<String> addresses, Runnable after) {
        if (addresses == null || addresses.isEmpty()) return;
        String who = String.join(", ", addresses);
        MessagesLook.message(a, a.getString(R.string.btn_block), a.getString(R.string.confirm_block, who),
                new String[]{a.getString(R.string.btn_block), a.getString(R.string.cancel)}, which -> {
                    if (which != 0) return;
                    Background.run(() -> {
                        boolean ok = true;
                        for (String x : addresses) ok &= Blocking.block(a, x);
                        final boolean done = ok;
                        a.runOnUiThread(() -> {
                            Toast.makeText(a, done ? R.string.blocked_done : R.string.block_needs_default,
                                    Toast.LENGTH_SHORT).show();
                            if (done && after != null) after.run();
                        });
                    });
                });
    }

    private void messageMenu(MessageItem m) {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.menu_copy));
        items.add(getString(R.string.menu_delete));
        boolean resend = m.state == MessageItem.State.FAILED && !m.mms && m.images.isEmpty();
        if (resend) items.add(getString(R.string.menu_resend));
        items.add(getString(R.string.cancel));
        MessagesLook.options(this, getString(R.string.menu_message), items.toArray(new String[0]), which -> {
            if (which == 0) {
                ClipboardManager cm = getSystemService(ClipboardManager.class);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("message", m.body));
            } else if (which == 1) {
                Background.run(() -> {
                    MessageStore.deleteMessage(this, m);
                    main.post(this::reload);
                });
            } else if (which == 2 && resend) {
                Background.run(() -> {
                    MessageStore.deleteMessage(this, m);
                    SmsSender.send(this, m.address, m.body, m.subId);
                    main.post(this::reload);
                });
            }
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /** Adapter over {@link #messages}. */
    private final class BubbleAdapter extends BaseAdapter {
        @Override public int getCount() { return messages.size(); }
        @Override public Object getItem(int position) { return messages.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            BubbleView v = convertView instanceof BubbleView ? (BubbleView) convertView
                    : new BubbleView(ThreadActivity.this, metrics != null && metrics.isTwoPane());
            MessageItem m = messages.get(position);
            v.bind(m, Sims.shortLabel(sims, m.subId));
            return v;
        }
    }
}
