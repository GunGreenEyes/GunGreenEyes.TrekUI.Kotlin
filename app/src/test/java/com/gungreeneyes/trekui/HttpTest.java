package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** {@link Http}: HTTPS-only check and the bounded body reader (pure JVM, no network). */
public class HttpTest {

    @Test
    public void isHttps() {
        assertThat(Http.isHttps("https://api.open-meteo.com/v1/forecast?x=1")).isTrue();
        assertThat(Http.isHttps("HTTPS://example.com")).isTrue();
        assertThat(Http.isHttps("http://example.com")).isFalse();
        assertThat(Http.isHttps("file:///etc/hosts")).isFalse();
        assertThat(Http.isHttps("https:///nohost")).isFalse();
        assertThat(Http.isHttps("not a url")).isFalse();
        assertThat(Http.isHttps(null)).isFalse();
    }

    @Test(expected = IOException.class)
    public void request_refusesCleartext() throws IOException {
        Http.request("GET", "http://example.com", null, null, 1000, 1000);
    }

    @Test
    public void readLimited_readsUtf8() throws IOException {
        String s = "TREKUI • 47";
        assertThat(Http.readLimited(new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)), 100)).isEqualTo(s);
    }

    @Test(expected = IOException.class)
    public void readLimited_rejectsOversizedReplies() throws IOException {
        Http.readLimited(new ByteArrayInputStream(new byte[101]), 100);
    }
}
