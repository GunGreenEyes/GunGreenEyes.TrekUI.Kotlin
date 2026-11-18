package com.gungreeneyes.trekui.messages;

import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gungreeneyes.trekui.MessagesLook;
import com.gungreeneyes.trekui.messages.data.BlockList;

import java.util.List;

/**
 * BLOCKED: the system block list (shared with the dialer; editable while Messages is the default SMS
 * app) and this app's prefix rules ({@code +1900*}). ADD takes a number or a rule; tapping an entry
 * offers to unblock it. Messages from blocked senders are dropped on arrival and never notify.
 */
public final class BlockedActivity extends BaseScreen {
    private LinearLayout list;

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setTag("blocked_list");
        scroll.addView(list);

        List<SideButton> side = buttons();
        side.add(new SideButton(getString(R.string.btn_back), 0, "btn_back", v -> finish()));
        side.add(new SideButton(getString(R.string.btn_add), 2, "btn_add", v -> add()));
        frame(getString(R.string.title_blocked), getString(R.string.footer_blocked), side, scroll);
        reload();
    }

    private void reload() {
        load(() -> Blocking.entries(this), entries -> {
            if (entries == null || list == null) return;
            list.removeAllViews();
            if (!Blocking.systemAvailable(this)) {
                TextView note = MessagesLook.text(this, getString(R.string.block_needs_default), 15f, MessagesLook.alert());
                note.setPadding(0, 0, 0, MessagesLook.dp(this, MessagesLook.M));
                list.addView(note);
            }
            if (entries.isEmpty()) {
                TextView empty = MessagesLook.text(this, getString(R.string.blocked_empty), 16f, MessagesLook.textLo());
                empty.setGravity(Gravity.CENTER);
                empty.setPadding(0, MessagesLook.dp(this, 32f), 0, 0);
                list.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                return;
            }
            int i = 0;
            for (String e : entries) {
                String label = BlockList.isPrefixRule(e) ? getString(R.string.prefix_rule, e) : e;
                TextView row = MessagesLook.button(this, label, MessagesLook.accent(1 + (i++ % 5)), MessagesLook.PILL,
                        v -> confirmUnblock(e));
                row.setTag("blocked_" + e);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = MessagesLook.dp(this, MessagesLook.S);
                list.addView(row, lp);
            }
        });
    }

    private void add() {
        MessagesLook.prompt(this, getString(R.string.btn_add), "", getString(R.string.block_hint), value -> {
            if (value == null || value.trim().isEmpty()) return;
            Background.run(() -> {
                boolean ok = Blocking.block(this, value);
                main.post(() -> {
                    if (!ok) Toast.makeText(this, R.string.block_needs_default, Toast.LENGTH_SHORT).show();
                    reload();
                });
            });
        });
    }

    private void confirmUnblock(String entry) {
        MessagesLook.message(this, getString(R.string.unblock), getString(R.string.confirm_unblock, entry),
                new String[]{getString(R.string.unblock), getString(R.string.cancel)}, which -> {
                    if (which != 0) return;
                    Background.run(() -> {
                        Blocking.unblock(this, entry);
                        main.post(this::reload);
                    });
                });
    }
}
