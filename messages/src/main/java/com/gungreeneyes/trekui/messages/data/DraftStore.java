package com.gungreeneyes.trekui.messages.data;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Unsent message text per conversation (pure logic over a small key/value {@link Backing}, unit-tested;
 * the app backs it with SharedPreferences).
 *
 * <p>A draft belongs to a recipient set, keyed by {@link PhoneNumbers#setKey}, so it survives the
 * conversation being created, renamed or re-sorted, and a new conversation typed to the same people finds
 * it. Stored value: {@code <savedMillis>:<text>}. Blank text removes the draft.
 */
public final class DraftStore {
    /** Minimal storage the store needs. */
    public interface Backing {
        String get(String key);
        void put(String key, String value);
        void remove(String key);
        Set<String> keys();
    }

    /** One saved draft. */
    public static final class Draft {
        public final String text;
        public final long savedMillis;

        Draft(String text, long savedMillis) {
            this.text = text;
            this.savedMillis = savedMillis;
        }
    }

    /** Prefix of every draft key in the backing store (other keys are ignored). */
    public static final String PREFIX = "draft.";

    private final Backing backing;

    public DraftStore(Backing backing) {
        this.backing = backing;
    }

    /** Saves (or, for blank text, removes) the draft for {@code recipients}. */
    public void save(Collection<String> recipients, String text, long nowMillis) {
        String key = keyFor(recipients);
        if (key == null) return;
        if (text == null || text.trim().isEmpty()) backing.remove(key);
        else backing.put(key, nowMillis + ":" + text);
    }

    /** The draft for {@code recipients}, or null. */
    public Draft load(Collection<String> recipients) {
        String key = keyFor(recipients);
        return key == null ? null : decode(backing.get(key));
    }

    /** Removes the draft for {@code recipients} (after sending). */
    public void clear(Collection<String> recipients) {
        String key = keyFor(recipients);
        if (key != null) backing.remove(key);
    }

    /** Every stored draft by recipient-set key ({@link PhoneNumbers#setKey}). */
    public Map<String, Draft> all() {
        Map<String, Draft> out = new LinkedHashMap<>();
        for (String k : backing.keys()) {
            if (!k.startsWith(PREFIX)) continue;
            Draft d = decode(backing.get(k));
            if (d != null) out.put(k.substring(PREFIX.length()), d);
        }
        return out;
    }

    /** Backing-store key for a recipient set, or null when the set is empty. */
    static String keyFor(Collection<String> recipients) {
        String set = PhoneNumbers.setKey(recipients);
        return set.isEmpty() ? null : PREFIX + set;
    }

    /** Parses a stored value; null when missing or malformed. */
    static Draft decode(String value) {
        if (value == null) return null;
        int colon = value.indexOf(':');
        if (colon <= 0) return null;
        try {
            long t = Long.parseLong(value.substring(0, colon));
            String text = value.substring(colon + 1);
            return text.trim().isEmpty() ? null : new Draft(text, t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
