package com.gungreeneyes.trekui.messages.data;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class DraftStoreTest {
    /** In-memory backing. */
    private static final class MapBacking implements DraftStore.Backing {
        final Map<String, String> map = new HashMap<>();
        @Override public String get(String key) { return map.get(key); }
        @Override public void put(String key, String value) { map.put(key, value); }
        @Override public void remove(String key) { map.remove(key); }
        @Override public Set<String> keys() { return map.keySet(); }
    }

    @Test
    public void saveAndLoadByRecipientSetIgnoringOrderAndFormat() {
        DraftStore s = new DraftStore(new MapBacking());
        s.save(Arrays.asList("555-010-2345", "5559990000"), "hello: world", 42L);
        DraftStore.Draft d = s.load(Arrays.asList("+1 555 999 0000", "5550102345"));
        assertThat(d).isNotNull();
        assertThat(d.text).isEqualTo("hello: world");
        assertThat(d.savedMillis).isEqualTo(42L);
    }

    @Test
    public void blankTextRemovesDraft() {
        DraftStore s = new DraftStore(new MapBacking());
        s.save(Collections.singletonList("5550102345"), "x", 1L);
        s.save(Collections.singletonList("5550102345"), "   ", 2L);
        assertThat(s.load(Collections.singletonList("5550102345"))).isNull();
    }

    @Test
    public void clearAndAll() {
        MapBacking b = new MapBacking();
        b.put("unrelated", "1:x");
        DraftStore s = new DraftStore(b);
        s.save(Collections.singletonList("5550102345"), "a", 1L);
        s.save(Collections.singletonList("5559990000"), "b", 2L);
        assertThat(s.all().keySet()).containsExactly("5550102345", "5559990000");
        s.clear(Collections.singletonList("5550102345"));
        assertThat(s.all().keySet()).containsExactly("5559990000");
    }

    @Test
    public void emptyRecipientsAreIgnoredAndMalformedValuesRead() {
        DraftStore s = new DraftStore(new MapBacking());
        s.save(Collections.emptyList(), "x", 1L);
        assertThat(s.load(Collections.emptyList())).isNull();
        assertThat(DraftStore.decode("notanumber:x")).isNull();
        assertThat(DraftStore.decode(":x")).isNull();
        assertThat(DraftStore.decode(null)).isNull();
    }
}
