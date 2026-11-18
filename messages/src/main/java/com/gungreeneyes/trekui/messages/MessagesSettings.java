package com.gungreeneyes.trekui.messages;

import android.content.Context;
import android.content.SharedPreferences;

import com.gungreeneyes.trekui.messages.data.DraftStore;

import java.util.Set;

/**
 * Messages' own settings (delivery reports, preferred SIM, local block prefix rules) and the
 * SharedPreferences backing of the {@link DraftStore}. The look-and-sound choice lives in :core's
 * {@code TrekPrefs} instead.
 */
public final class MessagesSettings {
    private static final String FILE = "messages";
    private static final String DRAFTS = "messages_drafts";
    private static final String KEY_DELIVERY = "delivery_reports";
    private static final String KEY_SUB = "send_sub";
    static final String KEY_BLOCK_RULES = "block_prefix_rules";

    private MessagesSettings() {}

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Ask for delivery reports on outgoing SMS (default on). */
    public static boolean deliveryReports(Context c) {
        return prefs(c).getBoolean(KEY_DELIVERY, true);
    }

    public static void setDeliveryReports(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_DELIVERY, on).apply();
    }

    /** The SIM chosen for a conversation ({@code -1}: the system default). */
    public static int subFor(Context c, String conversationKey) {
        return prefs(c).getInt(KEY_SUB + "." + conversationKey, -1);
    }

    public static void setSubFor(Context c, String conversationKey, int subId) {
        prefs(c).edit().putInt(KEY_SUB + "." + conversationKey, subId).apply();
    }

    /** The draft store over its own preferences file. */
    public static DraftStore drafts(Context c) {
        final SharedPreferences sp = c.getApplicationContext().getSharedPreferences(DRAFTS, Context.MODE_PRIVATE);
        return new DraftStore(new DraftStore.Backing() {
            @Override public String get(String key) { return sp.getString(key, null); }
            @Override public void put(String key, String value) { sp.edit().putString(key, value).apply(); }
            @Override public void remove(String key) { sp.edit().remove(key).apply(); }
            @Override public Set<String> keys() { return sp.getAll().keySet(); }
        });
    }
}
