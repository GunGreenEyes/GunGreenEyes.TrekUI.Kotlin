package com.gungreeneyes.trekui.licensing

/**
 * Minimal persistent key/value abstraction used by the licensing logic.
 *
 * [TrialClock] and [Entitlement] only talk to this interface so their rules can be unit-tested on the
 * plain JVM with an in-memory map; on device the implementation is [PrefsKeyValueStore] backed by a
 * private `SharedPreferences` file.
 *
 * Public (not package-private as in Java) because `:app`'s JVM tests implement it with an in-memory map.
 */
interface KeyValueStore {
    fun getLong(key: String, defaultValue: Long): Long

    fun putLong(key: String, value: Long)

    fun getString(key: String, defaultValue: String?): String?

    fun putString(key: String, value: String?)

    fun getBoolean(key: String, defaultValue: Boolean): Boolean

    fun putBoolean(key: String, value: Boolean)

    fun remove(key: String)
}
