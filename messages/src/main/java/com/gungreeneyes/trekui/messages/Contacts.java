package com.gungreeneyes.trekui.messages;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import com.gungreeneyes.trekui.messages.data.PhoneNumbers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Display names for addresses (contacts PhoneLookup, cached per comparison key for the process). Falls
 * back to the address itself when READ_CONTACTS is missing or nothing matches. Lookups block: call off
 * the main thread.
 */
public final class Contacts {
    private static final ConcurrentHashMap<String, String> CACHE = new ConcurrentHashMap<>();
    private static final String NONE = "";

    private Contacts() {}

    /** The contact name for {@code address}, or the address itself. */
    public static String name(Context c, String address) {
        String n = lookup(c, address);
        return n != null ? n : PhoneNumbers.normalize(address);
    }

    /** Names of every participant joined by ", ". */
    public static String names(Context c, List<String> addresses) {
        List<String> out = new ArrayList<>();
        for (String a : addresses) out.add(name(c, a));
        return String.join(", ", out);
    }

    /** Forgets cached names (after the user edits contacts). */
    public static void clear() {
        CACHE.clear();
    }

    private static String lookup(Context c, String address) {
        String key = PhoneNumbers.key(address);
        if (key.isEmpty()) return null;
        String cached = CACHE.get(key);
        if (cached != null) return cached.isEmpty() ? null : cached;
        String found = null;
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && PhoneNumbers.isPhoneLike(PhoneNumbers.normalize(address))) {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(PhoneNumbers.normalize(address)));
            try (Cursor cur = c.getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) found = cur.getString(0);
            } catch (RuntimeException ignored) {
                // provider missing or permission revoked: fall back to the number
            }
        }
        CACHE.put(key, found == null ? NONE : found);
        return found;
    }
}
