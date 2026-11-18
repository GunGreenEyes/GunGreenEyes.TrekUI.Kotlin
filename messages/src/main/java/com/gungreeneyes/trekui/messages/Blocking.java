package com.gungreeneyes.trekui.messages;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.provider.BlockedNumberContract;

import com.gungreeneyes.trekui.messages.data.BlockList;
import com.gungreeneyes.trekui.messages.data.PhoneNumbers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Blocked senders. Exact numbers go to the system block list ({@link BlockedNumberContract}, shared with
 * the dialer; only the default SMS / dialer app may write it); prefix rules ending in {@code *} are kept
 * in this app's settings because the system list cannot hold them. {@link #isBlocked} combines both via
 * the pure {@link BlockList}. Blocking calls; use off the main thread.
 */
public final class Blocking {
    private Blocking() {}

    /** True if the system block list can be used by this app right now. */
    public static boolean systemAvailable(Context c) {
        try {
            return BlockedNumberContract.canCurrentUserBlockNumbers(c) && SmsRole.isDefault(c);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Every entry: system-blocked numbers, then this app's prefix rules. */
    public static List<String> entries(Context c) {
        List<String> out = new ArrayList<>();
        if (systemAvailable(c)) {
            try (Cursor cur = c.getContentResolver().query(BlockedNumberContract.BlockedNumbers.CONTENT_URI,
                    new String[]{BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER}, null, null, null)) {
                if (cur != null) while (cur.moveToNext()) out.add(cur.getString(0));
            } catch (RuntimeException ignored) {
                // list unavailable for this user
            }
        }
        out.addAll(prefixRules(c));
        return out;
    }

    /** True if messages from {@code address} must be dropped. */
    public static boolean isBlocked(Context c, String address) {
        if (address == null || address.isEmpty()) return false;
        if (systemAvailable(c)) {
            try {
                if (BlockedNumberContract.isBlocked(c, address)) return true;
            } catch (RuntimeException ignored) {
                // fall through to our own rules
            }
        }
        return new BlockList(prefixRules(c)).matches(address);
    }

    /**
     * Blocks {@code entry}: a prefix rule ({@code +1900*}) is stored locally, a number in the system list.
     *
     * @return false if the system list is unavailable (Messages is not the default SMS app)
     */
    public static boolean block(Context c, String entry) {
        if (BlockList.isPrefixRule(entry)) {
            Set<String> rules = new HashSet<>(prefixRules(c));
            rules.add(entry.trim());
            MessagesSettings.prefs(c).edit().putStringSet(MessagesSettings.KEY_BLOCK_RULES, rules).apply();
            return true;
        }
        if (!systemAvailable(c)) return false;
        ContentValues v = new ContentValues();
        v.put(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER, PhoneNumbers.normalize(entry));
        try {
            return c.getContentResolver().insert(BlockedNumberContract.BlockedNumbers.CONTENT_URI, v) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Removes {@code entry} from whichever list holds it. */
    public static void unblock(Context c, String entry) {
        Set<String> rules = new HashSet<>(prefixRules(c));
        if (rules.remove(entry.trim())) {
            MessagesSettings.prefs(c).edit().putStringSet(MessagesSettings.KEY_BLOCK_RULES, rules).apply();
            return;
        }
        if (!systemAvailable(c)) return;
        try {
            BlockedNumberContract.unblock(c, entry);
        } catch (RuntimeException ignored) {
            // already gone
        }
    }

    private static List<String> prefixRules(Context c) {
        Set<String> s = MessagesSettings.prefs(c).getStringSet(MessagesSettings.KEY_BLOCK_RULES, null);
        List<String> out = new ArrayList<>();
        if (s != null) out.addAll(s);
        java.util.Collections.sort(out);
        return out;
    }
}
