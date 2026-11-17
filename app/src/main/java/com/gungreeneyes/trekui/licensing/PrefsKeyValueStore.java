package com.gungreeneyes.trekui.licensing;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * {@link KeyValueStore} backed by the private {@code console_licensing} SharedPreferences file.
 *
 * <p>Writes use {@link SharedPreferences.Editor#apply()} so they never block the UI thread; the
 * in-memory copy SharedPreferences keeps is updated immediately, so reads see the new values.
 * The file is separate from the launcher's own settings so a settings reset never touches the
 * purchase cache or the trial clock.
 */
final class PrefsKeyValueStore implements KeyValueStore {
    /** Name of the SharedPreferences file holding trial and entitlement state. */
    static final String FILE = "trekui_licensing";

    private final SharedPreferences prefs;

    PrefsKeyValueStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @Override public long getLong(String key, long defaultValue) {
        try {
            return prefs.getLong(key, defaultValue);
        } catch (ClassCastException e) {
            return defaultValue; // hand-edited file with the wrong type: ignore the value
        }
    }

    @Override public void putLong(String key, long value) { prefs.edit().putLong(key, value).apply(); }

    @Override public String getString(String key, String defaultValue) {
        try {
            return prefs.getString(key, defaultValue);
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    @Override public void putString(String key, String value) { prefs.edit().putString(key, value).apply(); }

    @Override public boolean getBoolean(String key, boolean defaultValue) {
        try {
            return prefs.getBoolean(key, defaultValue);
        } catch (ClassCastException e) {
            return defaultValue;
        }
    }

    @Override public void putBoolean(String key, boolean value) { prefs.edit().putBoolean(key, value).apply(); }

    @Override public void remove(String key) { prefs.edit().remove(key).apply(); }
}
