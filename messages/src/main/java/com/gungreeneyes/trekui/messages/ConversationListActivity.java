package com.gungreeneyes.trekui.messages;

import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.gungreeneyes.trekui.MessagesLook;
import com.gungreeneyes.trekui.TrekPrefs;
import com.gungreeneyes.trekui.licensing.Licensing;
import com.gungreeneyes.trekui.messages.data.DraftStore;
import com.gungreeneyes.trekui.messages.data.MessageItem;
import com.gungreeneyes.trekui.messages.data.PhoneNumbers;
import com.gungreeneyes.trekui.messages.data.ThreadGrouper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The main screen: every conversation, newest first, in the console frame. Sidebar: NEW, SEARCH,
 * BLOCKED, CONFIG (interface / sub-theme when the launcher is absent, delivery reports, unlock) and
 * SET DEFAULT while Messages is not the default SMS app (a banner explains why). Search filters by name
 * and number and finds message text (SMS and MMS), grouped per conversation with a match count.
 * Licensing runs here ({@link Licensing#init} / {@link Licensing#onAppResumed}).
 */
public final class ConversationListActivity extends BaseScreen {
    private final List<ConversationRowView.Row> rows = new ArrayList<>();
    private List<MessageStore.Conversation> conversations = new ArrayList<>();
    private RowAdapter adapter;
    private boolean searching;
    private String query = "";
    private int loadToken;
    private boolean askedPermissions;
    private ContentObserver observer;
    private final Runnable reload = this::reload;

    private final ActivityResultLauncher<Intent> roleRequest = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), r -> afterRole());
    private final ActivityResultLauncher<String[]> permissionRequest = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), r -> reload());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Licensing.init(this);
        observer = new ContentObserver(main) {
            @Override public void onChange(boolean selfChange) {
                main.removeCallbacks(reload);
                main.postDelayed(reload, 300);
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        Licensing.onAppResumed(this);
        try {
            getContentResolver().registerContentObserver(MessageStore.CHANGES, true, observer);
        } catch (SecurityException ignored) {
            // no READ_SMS yet
        }
        if (!askedPermissions && SmsRole.isDefault(this) && SmsRole.missingPermissions(this).length > 0) {
            askedPermissions = true;
            permissionRequest.launch(SmsRole.missingPermissions(this));
        }
        rebuildIfDefaultChanged();
        reload();
    }

    @Override
    protected void onPause() {
        super.onPause();
        getContentResolver().unregisterContentObserver(observer);
        main.removeCallbacks(reload);
    }

    private boolean builtAsDefault;

    private void rebuildIfDefaultChanged() {
        if (builtAsDefault != SmsRole.isDefault(this)) rebuild();
    }

    @Override
    protected void build() {
        builtAsDefault = SmsRole.isDefault(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        if (!builtAsDefault) content.addView(banner(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (searching) {
            EditText field = field(getString(R.string.search_hint));
            field.setTag("search_field");
            field.setText(query);
            field.setSelection(query.length());
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) {
                    query = s.toString();
                    main.removeCallbacks(reload);
                    main.postDelayed(reload, 250);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = MessagesLook.dp(this, MessagesLook.S);
            content.addView(field, lp);
            field.requestFocus();
        }

        ListView list = new ListView(this);
        list.setTag("conversation_list");
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setVerticalScrollBarEnabled(false);
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        TextView empty = MessagesLook.text(this, getString(searching ? R.string.search_empty : R.string.list_empty),
                16f, MessagesLook.textLo());
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, MessagesLook.dp(this, 32f), 0, 0);
        list.setEmptyView(empty);
        list.setOnItemClickListener((AdapterView<?> p, View v, int pos, long id) -> {
            MessagesLook.tap(v);
            ConversationRowView.Row r = rows.get(pos);
            startActivity(ThreadActivity.intent(this, r.threadId, r.addresses));
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            MessagesLook.longPress(v);
            rowMenu(rows.get(pos));
            return true;
        });
        android.widget.FrameLayout holder = new android.widget.FrameLayout(this);
        holder.addView(list, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        holder.addView(empty, new android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(holder, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        List<SideButton> side = buttons();
        side.add(new SideButton(getString(R.string.btn_new), 0, "btn_new",
                v -> startActivity(ThreadActivity.intent(this, -1, null))));
        side.add(new SideButton(getString(searching ? R.string.btn_close_search : R.string.btn_search), 2, "btn_search",
                v -> toggleSearch()));
        side.add(new SideButton(getString(R.string.btn_blocked), 3, "btn_blocked",
                v -> startActivity(new Intent(this, BlockedActivity.class))));
        side.add(new SideButton(getString(R.string.btn_config), 4, "btn_config", v -> config()));
        if (!builtAsDefault && SmsRole.available(this)) {
            side.add(new SideButton(getString(R.string.btn_set_default), 5, "btn_default", v -> requestRole()));
        }
        frame(getString(R.string.title_messages), getString(R.string.footer_messages, version()), side, content);
        adapter.notifyDataSetChanged();
    }

    /** "Not the default SMS app" notice with its fix. */
    private View banner() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setTag("default_banner");
        int pad = MessagesLook.dp(this, MessagesLook.M);
        box.setPadding(pad, pad, pad, pad);
        box.setBackground(MessagesLook.outline(this, MessagesLook.panel(), MessagesLook.alert(), 14f, 1.5f));
        TextView t = MessagesLook.text(this, getString(R.string.banner_not_default), 15f, MessagesLook.textHi());
        t.setLineSpacing(0, 1.1f);
        box.addView(t);
        if (SmsRole.available(this)) {
            TextView b = MessagesLook.button(this, getString(R.string.btn_set_default), MessagesLook.accent(0),
                    MessagesLook.PILL, v -> requestRole());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = MessagesLook.dp(this, MessagesLook.S);
            lp.gravity = Gravity.END;
            box.addView(b, lp);
        }
        LinearLayout.LayoutParams outer = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        outer.bottomMargin = MessagesLook.dp(this, MessagesLook.S);
        box.setLayoutParams(outer);
        return box;
    }

    /** A text field in the interface style. */
    EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        e.setTextColor(MessagesLook.textHi());
        e.setHintTextColor(MessagesLook.alpha(MessagesLook.textLo(), 0.6f));
        e.setTypeface(MessagesLook.font(this, false));
        MessagesLook.setTextSp(e, 17f);
        e.setBackground(MessagesLook.outline(this, MessagesLook.panel(), MessagesLook.accent(2), 18f, 1.5f));
        int p = MessagesLook.dp(this, MessagesLook.M);
        e.setPadding(p, p, p, p);
        return e;
    }

    private void requestRole() {
        try {
            roleRequest.launch(SmsRole.requestIntent(this));
        } catch (RuntimeException e) {
            MessagesLook.message(this, getString(R.string.title_messages), getString(R.string.role_unavailable),
                    new String[]{getString(R.string.ok)}, null);
        }
    }

    private void afterRole() {
        String[] missing = SmsRole.missingPermissions(this);
        if (missing.length > 0) permissionRequest.launch(missing);
        rebuild();
        reload();
    }

    private void toggleSearch() {
        searching = !searching;
        if (!searching) query = "";
        rebuild();
        reload();
    }

    // ---- loading ----------------------------------------------------------------------------------

    private void reload() {
        if (!SmsRole.canRead(this)) {
            if (!askedPermissions && !SmsRole.isDefault(this)) {
                askedPermissions = true;
                permissionRequest.launch(SmsRole.missingPermissions(this));
            }
            rows.clear();
            if (adapter != null) adapter.notifyDataSetChanged();
            return;
        }
        final int token = ++loadToken;
        final String q = searching ? query.trim() : "";
        load(() -> q.isEmpty() ? listRows() : searchRows(q), result -> {
            if (token != loadToken || result == null) return;
            rows.clear();
            rows.addAll(result);
            if (adapter != null) adapter.notifyDataSetChanged();
        });
    }

    /** The conversation list rows with names and drafts (background). */
    private List<ConversationRowView.Row> listRows() {
        conversations = MessageStore.conversations(this);
        Map<String, DraftStore.Draft> drafts = MessagesSettings.drafts(this).all();
        String imageMarker = getString(R.string.image_marker);
        List<ConversationRowView.Row> out = new ArrayList<>();
        for (MessageStore.Conversation c : conversations) {
            ConversationRowView.Row r = new ConversationRowView.Row();
            r.threadId = c.threadId;
            r.addresses = c.addresses;
            r.title = c.addresses.isEmpty() ? getString(R.string.unknown_sender) : Contacts.names(this, c.addresses);
            r.snippet = c.snippet.isEmpty() && c.attachment ? imageMarker : c.snippet;
            r.dateMillis = c.dateMillis;
            r.unread = c.unread;
            DraftStore.Draft d = drafts.get(PhoneNumbers.setKey(c.addresses));
            if (d != null) r.draft = d.text;
            out.add(r);
        }
        return out;
    }

    /** Search: conversations whose name or number match, then message hits grouped per thread. */
    private List<ConversationRowView.Row> searchRows(String q) {
        if (conversations.isEmpty()) conversations = MessageStore.conversations(this);
        String lower = q.toLowerCase(Locale.getDefault());
        String digits = PhoneNumbers.key(q);
        List<ConversationRowView.Row> out = new ArrayList<>();
        List<Long> seen = new ArrayList<>();
        for (MessageStore.Conversation c : conversations) {
            String names = Contacts.names(this, c.addresses);
            boolean match = names.toLowerCase(Locale.getDefault()).contains(lower);
            if (!match && PhoneNumbers.isPhoneLike(q)) {
                for (String a : c.addresses) if (PhoneNumbers.key(a).contains(digits)) match = true;
            }
            if (!match) continue;
            ConversationRowView.Row r = new ConversationRowView.Row();
            r.threadId = c.threadId;
            r.addresses = c.addresses;
            r.title = names;
            r.snippet = c.snippet;
            r.dateMillis = c.dateMillis;
            out.add(r);
            seen.add(c.threadId);
        }
        List<MessageItem> hits = MessageStore.search(this, q);
        String imageMarker = getString(R.string.image_marker);
        for (ThreadGrouper.Summary s : ThreadGrouper.group(hits)) {
            ConversationRowView.Row r = new ConversationRowView.Row();
            r.threadId = s.threadId;
            r.addresses = s.addresses;
            r.title = Contacts.names(this, s.addresses);
            r.snippet = s.latest.preview(imageMarker);
            r.dateMillis = s.latest.dateMillis;
            r.badge = getResources().getQuantityString(R.plurals.matches, s.count, s.count);
            if (seen.contains(s.threadId)) out.removeIf(x -> x.threadId == s.threadId);
            out.add(r);
        }
        return out;
    }

    // ---- menus ------------------------------------------------------------------------------------

    private void rowMenu(ConversationRowView.Row r) {
        String[] items = {getString(R.string.menu_mark_read), getString(R.string.menu_delete),
                getString(R.string.menu_block), getString(R.string.cancel)};
        MessagesLook.options(this, r.title, items, which -> {
            if (which == 0) {
                Background.run(() -> {
                    MessageStore.markRead(this, r.threadId);
                    Notifier.cancel(this, r.threadId);
                });
            } else if (which == 1) {
                confirmDelete(r);
            } else if (which == 2) {
                ThreadActivity.confirmBlock(this, r.addresses, null);
            }
        });
    }

    private void confirmDelete(ConversationRowView.Row r) {
        MessagesLook.message(this, getString(R.string.menu_delete), getString(R.string.confirm_delete_thread, r.title),
                new String[]{getString(R.string.menu_delete), getString(R.string.cancel)}, which -> {
                    if (which != 0) return;
                    Background.run(() -> {
                        MessageStore.deleteThread(this, r.threadId);
                        Notifier.cancel(this, r.threadId);
                        MessagesSettings.drafts(this).clear(r.addresses);
                        main.post(this::reload);
                    });
                });
    }

    /** CONFIG: interface (own choice only while the launcher is absent), delivery reports, unlock. */
    private void config() {
        boolean following = MessagesTheme.followingLauncher();
        boolean reports = MessagesSettings.deliveryReports(this);
        List<String> items = new ArrayList<>();
        items.add(getString(following ? R.string.config_interface_following : R.string.config_interface));
        items.add(getString(reports ? R.string.config_reports_on : R.string.config_reports_off));
        boolean unlocked = Licensing.isUnlocked(this);
        if (!unlocked) items.add(getString(R.string.config_unlock));
        items.add(getString(R.string.cancel));
        MessagesLook.options(this, getString(R.string.btn_config), items.toArray(new String[0]), which -> {
            if (which == 0) {
                if (following) {
                    MessagesLook.message(this, getString(R.string.config_interface),
                            getString(R.string.following_launcher_note), new String[]{getString(R.string.ok)}, null);
                } else {
                    pickInterface();
                }
            } else if (which == 1) {
                MessagesSettings.setDeliveryReports(this, !reports);
            } else if (which == 2 && !unlocked) {
                Licensing.openPurchaseScreen(this);
            }
        });
    }

    private void pickInterface() {
        String[] names = MessagesLook.interfaceNames();
        MessagesLook.options(this, getString(R.string.config_interface), names, f -> {
            TrekPrefs prefs = TrekPrefs.get(this);
            prefs.setFaction(f);
            if (MessagesLook.hasSubThemes(f)) {
                MessagesLook.options(this, names[f], MessagesLook.subThemeNames(f), s -> {
                    prefs.setSubTheme(f, s);
                    rebuild();
                });
            } else {
                rebuild();
            }
        });
    }

    /** Adapter over {@link #rows}. */
    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return rows.get(position).threadId; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ConversationRowView v = convertView instanceof ConversationRowView ? (ConversationRowView) convertView
                    : new ConversationRowView(ConversationListActivity.this);
            v.bind(rows.get(position));
            return v;
        }
    }
}
