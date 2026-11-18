package com.gungreeneyes.trekui.messages

import android.content.ContentValues
import android.content.Context
import android.provider.BlockedNumberContract
import androidx.core.content.edit
import com.gungreeneyes.trekui.messages.data.BlockList
import com.gungreeneyes.trekui.messages.data.PhoneNumbers
import com.gungreeneyes.trekui.messages.data.javaTrim

/**
 * Blocked senders. Exact numbers go to the system block list ([BlockedNumberContract], shared with
 * the dialer; only the default SMS / dialer app may write it); prefix rules ending in `*` are kept
 * in this app's settings because the system list cannot hold them. [isBlocked] combines both via
 * the pure [BlockList]. Blocking calls; use off the main thread.
 */
internal object Blocking {
    /** True if the system block list can be used by this app right now. */
    fun systemAvailable(c: Context): Boolean = try {
        BlockedNumberContract.canCurrentUserBlockNumbers(c) && SmsRole.isDefault(c)
    } catch (e: RuntimeException) {
        false
    }

    /** Every entry: system-blocked numbers, then this app's prefix rules. */
    fun entries(c: Context): List<String> {
        val out = ArrayList<String>()
        if (systemAvailable(c)) {
            try {
                c.contentResolver.query(
                    BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                    arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null,
                )?.use { cur -> while (cur.moveToNext()) out.add(cur.getString(0)) }
            } catch (ignored: RuntimeException) {
                // list unavailable for this user
            }
        }
        out.addAll(prefixRules(c))
        return out
    }

    /** True if messages from [address] must be dropped. */
    fun isBlocked(c: Context, address: String?): Boolean {
        if (address.isNullOrEmpty()) return false
        if (systemAvailable(c)) {
            try {
                if (BlockedNumberContract.isBlocked(c, address)) return true
            } catch (ignored: RuntimeException) {
                // fall through to our own rules
            }
        }
        return BlockList(prefixRules(c)).matches(address)
    }

    /**
     * Blocks [entry]: a prefix rule (`+1900*`) is stored locally, a number in the system list.
     *
     * @return false if the system list is unavailable (Messages is not the default SMS app)
     */
    fun block(c: Context, entry: String): Boolean {
        if (BlockList.isPrefixRule(entry)) {
            val rules = HashSet(prefixRules(c))
            rules.add(entry.javaTrim())
            saveRules(c, rules)
            return true
        }
        if (!systemAvailable(c)) return false
        val v = ContentValues().apply {
            put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, PhoneNumbers.normalize(entry))
        }
        return try {
            c.contentResolver.insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, v) != null
        } catch (e: RuntimeException) {
            false
        }
    }

    /** Removes [entry] from whichever list holds it. */
    fun unblock(c: Context, entry: String) {
        val rules = HashSet(prefixRules(c))
        if (rules.remove(entry.javaTrim())) {
            saveRules(c, rules)
            return
        }
        if (!systemAvailable(c)) return
        try {
            BlockedNumberContract.unblock(c, entry)
        } catch (ignored: RuntimeException) {
            // already gone
        }
    }

    private fun saveRules(c: Context, rules: Set<String>) {
        MessagesSettings.prefs(c).edit { putStringSet(MessagesSettings.KEY_BLOCK_RULES, rules) }
    }

    private fun prefixRules(c: Context): List<String> =
        MessagesSettings.prefs(c).getStringSet(MessagesSettings.KEY_BLOCK_RULES, null).orEmpty().sorted()
}
