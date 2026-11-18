package com.gungreeneyes.trekui.messages

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import com.gungreeneyes.trekui.messages.data.PhoneNumbers
import java.util.concurrent.ConcurrentHashMap

/**
 * Display names for addresses (contacts PhoneLookup, cached per comparison key for the process). Falls
 * back to the address itself when READ_CONTACTS is missing or nothing matches. Lookups block: call off
 * the main thread.
 */
internal object Contacts {
    private val CACHE = ConcurrentHashMap<String, String>()
    private const val NONE = ""

    /** The contact name for [address], or the address itself. */
    fun name(c: Context, address: String?): String = lookup(c, address) ?: PhoneNumbers.normalize(address)

    /** Names of every participant joined by ", ". */
    fun names(c: Context, addresses: List<String>): String = addresses.joinToString(", ") { name(c, it) }

    /** Forgets cached names (after the user edits contacts). */
    fun clear() {
        CACHE.clear()
    }

    private fun lookup(c: Context, address: String?): String? {
        val key = PhoneNumbers.key(address)
        if (key.isEmpty()) return null
        CACHE[key]?.let { return it.ifEmpty { null } }
        var found: String? = null
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED &&
            PhoneNumbers.isPhoneLike(PhoneNumbers.normalize(address))
        ) {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(PhoneNumbers.normalize(address)),
            )
            try {
                c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) found = cur.getString(0)
                }
            } catch (ignored: RuntimeException) {
                // provider missing or permission revoked: fall back to the number
            }
        }
        CACHE[key] = found ?: NONE
        return found
    }
}
