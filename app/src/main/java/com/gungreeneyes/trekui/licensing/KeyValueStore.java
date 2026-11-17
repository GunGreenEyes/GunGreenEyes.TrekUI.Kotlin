package com.gungreeneyes.trekui.licensing;

/**
 * Minimal persistent key/value abstraction used by the licensing logic.
 *
 * <p>{@link TrialClock} and {@link Entitlement} only talk to this interface so their rules can be
 * unit-tested on the plain JVM with an in-memory map; on device the implementation is
 * {@link PrefsKeyValueStore} backed by a private {@code SharedPreferences} file.
 */
interface KeyValueStore {
    long getLong(String key, long defaultValue);

    void putLong(String key, long value);

    String getString(String key, String defaultValue);

    void putString(String key, String value);

    boolean getBoolean(String key, boolean defaultValue);

    void putBoolean(String key, boolean value);

    void remove(String key);
}
