package com.gungreeneyes.trekui.licensing

import android.content.Context
import android.content.SharedPreferences

/**
 * [KeyValueStore] backed by the private `trekui_licensing` SharedPreferences file.
 *
 * Writes use [SharedPreferences.Editor.apply] so they never block the UI thread; the in-memory copy
 * SharedPreferences keeps is updated immediately, so reads see the new values. The file is separate
 * from the launcher's own settings so a settings reset never touches the purchase cache or the trial
 * clock.
 */
internal class PrefsKeyValueStore(context: Context) : KeyValueStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // A hand-edited file with the wrong type throws ClassCastException: ignore the value.
    override fun getLong(key: String, defaultValue: Long): Long =
        try {
            prefs.getLong(key, defaultValue)
        } catch (e: ClassCastException) {
            defaultValue
        }

    override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()

    override fun getString(key: String, defaultValue: String?): String? =
        try {
            prefs.getString(key, defaultValue)
        } catch (e: ClassCastException) {
            defaultValue
        }

    override fun putString(key: String, value: String?) = prefs.edit().putString(key, value).apply()

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        try {
            prefs.getBoolean(key, defaultValue)
        } catch (e: ClassCastException) {
            defaultValue
        }

    override fun putBoolean(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()

    override fun remove(key: String) = prefs.edit().remove(key).apply()

    companion object {
        /** Name of the SharedPreferences file holding trial and entitlement state. */
        const val FILE: String = "trekui_licensing"
    }
}
