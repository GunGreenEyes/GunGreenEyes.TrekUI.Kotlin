package com.gungreeneyes.trekui.licensing;

import java.util.HashMap;
import java.util.Map;

/** In-memory {@link KeyValueStore} for JVM unit tests; {@link #raw()} allows simulating tampering. */
final class MemoryKeyValueStore implements KeyValueStore {
    private final Map<String, Object> values = new HashMap<>();

    Map<String, Object> raw() {
        return values;
    }

    @Override public long getLong(String key, long defaultValue) {
        Object v = values.get(key);
        return v instanceof Long ? (Long) v : defaultValue;
    }

    @Override public void putLong(String key, long value) { values.put(key, value); }

    @Override public String getString(String key, String defaultValue) {
        Object v = values.get(key);
        return v instanceof String ? (String) v : defaultValue;
    }

    @Override public void putString(String key, String value) { values.put(key, value); }

    @Override public boolean getBoolean(String key, boolean defaultValue) {
        Object v = values.get(key);
        return v instanceof Boolean ? (Boolean) v : defaultValue;
    }

    @Override public void putBoolean(String key, boolean value) { values.put(key, value); }

    @Override public void remove(String key) { values.remove(key); }
}
