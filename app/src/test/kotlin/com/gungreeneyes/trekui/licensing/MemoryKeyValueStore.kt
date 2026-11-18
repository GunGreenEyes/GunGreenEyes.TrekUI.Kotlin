package com.gungreeneyes.trekui.licensing

/** In-memory [KeyValueStore] for JVM unit tests; [raw] allows simulating tampering. */
internal class MemoryKeyValueStore : KeyValueStore {
    private val values = HashMap<String, Any?>()

    fun raw(): MutableMap<String, Any?> = values

    override fun getLong(key: String, defaultValue: Long): Long = values[key] as? Long ?: defaultValue

    override fun putLong(key: String, value: Long) {
        values[key] = value
    }

    override fun getString(key: String, defaultValue: String?): String? = values[key] as? String ?: defaultValue

    override fun putString(key: String, value: String?) {
        values[key] = value
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}
