package com.gungreeneyes.trekui.messages

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.BlockedNumberContract
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.gungreeneyes.trekui.messages.data.BlockList
import com.gungreeneyes.trekui.messages.data.PhoneNumbers
import com.gungreeneyes.trekui.messages.data.javaTrim

/**
 * Blocked senders. Exact numbers go to the system block list ([BlockedNumberContract], shared with
 * the dialer; only the default SMS / dialer app may write it); prefix rules ending in `*` are kept
 * in this app's settings because the system list cannot hold them. [isBlocked] combines both via
 * the pure [BlockList]. Blocking calls; use off the main thread.
 *
 * Android 6.0 (API 23) has no system block list (BlockedNumberContract is API 24): there every entry,
 * exact numbers included, is kept in this app's own rules, so blocking works the same, only not shared
 * with the dialer.
 */
internal object Blocking {
    /** True below Android 7.0, where every entry (numbers too) lives in this app's own list. */
    fun localOnly(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.N

    /** True if the system block list can be used by this app right now. */
    fun systemAvailable(c: Context): Boolean = try {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && SystemList.canBlock(c) && SmsRole.isDefault(c)
    } catch (e: RuntimeException) {
        false
    }

    /** Every entry: system-blocked numbers, then this app's prefix rules. */
    fun entries(c: Context): List<String> {
        val out = ArrayList<String>()
        if (systemAvailable(c) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                c.contentResolver.query(
                    SystemList.uri(),
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
        if (systemAvailable(c) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                if (SystemList.isBlocked(c, address)) return true
            } catch (ignored: RuntimeException) {
                // fall through to our own rules
            }
        }
        return BlockList(prefixRules(c)).matches(address)
    }

    /**
     * Blocks [entry]: a prefix rule (`+1900*`) is stored locally, a number in the system list.
     *
     * On Android 6.0 numbers are stored locally too ([BlockList.storedLocally]).
     *
     * @return false if the system list is unavailable (Messages is not the default SMS app)
     */
    fun block(c: Context, entry: String): Boolean {
        if (BlockList.storedLocally(entry, systemList = !localOnly())) {
            val rules = HashSet(prefixRules(c))
            rules.add(BlockList.localForm(entry))
            saveRules(c, rules)
            return true
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !systemAvailable(c)) return false
        val v = ContentValues().apply {
            put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, PhoneNumbers.normalize(entry))
        }
        return try {
            c.contentResolver.insert(SystemList.uri(), v) != null
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !systemAvailable(c)) return
        try {
            SystemList.unblock(c, entry)
        } catch (ignored: RuntimeException) {
            // already gone
        }
    }

    private fun saveRules(c: Context, rules: Set<String>) {
        MessagesSettings.prefs(c).edit { putStringSet(MessagesSettings.KEY_BLOCK_RULES, rules) }
    }

    private fun prefixRules(c: Context): List<String> =
        MessagesSettings.prefs(c).getStringSet(MessagesSettings.KEY_BLOCK_RULES, null).orEmpty().sorted()

    /**
     * Every access to the API 24 [BlockedNumberContract] (its non-constant `CONTENT_URI` field included):
     * a direct read, even behind an SDK_INT check, crashed ART on Android 6.0, so it lives in this holder.
     */
    @RequiresApi(Build.VERSION_CODES.N)
    private object SystemList {
        fun uri(): Uri = BlockedNumberContract.BlockedNumbers.CONTENT_URI

        fun canBlock(c: Context): Boolean = BlockedNumberContract.canCurrentUserBlockNumbers(c)

        fun isBlocked(c: Context, address: String): Boolean = BlockedNumberContract.isBlocked(c, address)

        fun unblock(c: Context, entry: String) {
            BlockedNumberContract.unblock(c, entry)
        }
    }
}
