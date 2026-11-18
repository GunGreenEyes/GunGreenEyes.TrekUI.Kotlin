package com.gungreeneyes.trekui.messages.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DraftStoreTest {
    /** In-memory backing. */
    private class MapBacking : DraftStore.Backing {
        val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun keys(): Set<String> = map.keys
    }

    @Test
    fun saveAndLoadByRecipientSetIgnoringOrderAndFormat() {
        val s = DraftStore(MapBacking())
        s.save(listOf("555-010-2345", "5559990000"), "hello: world", 42L)
        val d = s.load(listOf("+1 555 999 0000", "5550102345"))
        assertThat(d).isNotNull()
        assertThat(d!!.text).isEqualTo("hello: world")
        assertThat(d.savedMillis).isEqualTo(42L)
    }

    @Test
    fun blankTextRemovesDraft() {
        val s = DraftStore(MapBacking())
        s.save(listOf("5550102345"), "x", 1L)
        s.save(listOf("5550102345"), "   ", 2L)
        assertThat(s.load(listOf("5550102345"))).isNull()
    }

    @Test
    fun clearAndAll() {
        val b = MapBacking()
        b.put("unrelated", "1:x")
        val s = DraftStore(b)
        s.save(listOf("5550102345"), "a", 1L)
        s.save(listOf("5559990000"), "b", 2L)
        assertThat(s.all().keys).containsExactly("5550102345", "5559990000")
        s.clear(listOf("5550102345"))
        assertThat(s.all().keys).containsExactly("5559990000")
    }

    @Test
    fun emptyRecipientsAreIgnoredAndMalformedValuesRead() {
        val s = DraftStore(MapBacking())
        s.save(emptyList(), "x", 1L)
        assertThat(s.load(emptyList())).isNull()
        assertThat(DraftStore.decode("notanumber:x")).isNull()
        assertThat(DraftStore.decode(":x")).isNull()
        assertThat(DraftStore.decode(null)).isNull()
    }
}
