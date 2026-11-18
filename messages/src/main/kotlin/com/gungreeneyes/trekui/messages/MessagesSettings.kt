package com.gungreeneyes.trekui.messages

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.gungreeneyes.trekui.messages.data.DraftStore

/**
 * Messages' own settings (delivery reports, preferred SIM, local block prefix rules) and the
 * SharedPreferences backing of the [DraftStore]. The look-and-sound choice lives in :core's
 * `TrekPrefs` instead.
 */
internal object MessagesSettings {
    private const val FILE = "messages"
    private const val DRAFTS = "messages_drafts"
    private const val KEY_DELIVERY = "delivery_reports"
    private const val KEY_SUB = "send_sub"
    const val KEY_BLOCK_RULES = "block_prefix_rules"

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Ask for delivery reports on outgoing SMS (default on). */
    fun deliveryReports(c: Context): Boolean = prefs(c).getBoolean(KEY_DELIVERY, true)

    fun setDeliveryReports(c: Context, on: Boolean) {
        prefs(c).edit { putBoolean(KEY_DELIVERY, on) }
    }

    /** The SIM chosen for a conversation (`-1`: the system default). */
    fun subFor(c: Context, conversationKey: String): Int = prefs(c).getInt("$KEY_SUB.$conversationKey", -1)

    fun setSubFor(c: Context, conversationKey: String, subId: Int) {
        prefs(c).edit { putInt("$KEY_SUB.$conversationKey", subId) }
    }

    /** The draft store over its own preferences file. */
    fun drafts(c: Context): DraftStore {
        val sp = c.applicationContext.getSharedPreferences(DRAFTS, Context.MODE_PRIVATE)
        return DraftStore(object : DraftStore.Backing {
            override fun get(key: String): String? = sp.getString(key, null)
            override fun put(key: String, value: String) = sp.edit { putString(key, value) }
            override fun remove(key: String) = sp.edit { this.remove(key) }
            override fun keys(): Set<String> = sp.all.keys
        })
    }
}
