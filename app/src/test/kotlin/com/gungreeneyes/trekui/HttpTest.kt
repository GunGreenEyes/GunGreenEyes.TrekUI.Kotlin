package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

/** [Http]: HTTPS-only check and the bounded body reader (pure JVM, no network). */
class HttpTest {

    @Test
    fun isHttps() {
        assertThat(Http.isHttps("https://api.open-meteo.com/v1/forecast?x=1")).isTrue()
        assertThat(Http.isHttps("HTTPS://example.com")).isTrue()
        assertThat(Http.isHttps("http://example.com")).isFalse()
        assertThat(Http.isHttps("file:///etc/hosts")).isFalse()
        assertThat(Http.isHttps("https:///nohost")).isFalse()
        assertThat(Http.isHttps("not a url")).isFalse()
        assertThat(Http.isHttps(null)).isFalse()
    }

    @Test(expected = IOException::class)
    fun request_refusesCleartext() {
        Http.request("GET", "http://example.com", null, null, 1000, 1000)
    }

    @Test
    fun readLimited_readsUtf8() {
        val s = "TREKUI • 47"
        assertThat(Http.readLimited(ByteArrayInputStream(s.toByteArray(Charsets.UTF_8)), 100)).isEqualTo(s)
    }

    @Test(expected = IOException::class)
    fun readLimited_rejectsOversizedReplies() {
        Http.readLimited(ByteArrayInputStream(ByteArray(101)), 100)
    }
}
