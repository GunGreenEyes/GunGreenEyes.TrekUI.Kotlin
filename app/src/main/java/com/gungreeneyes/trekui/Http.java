package com.gungreeneyes.trekui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The launcher's one small HTTPS client, shared by {@link Weather} (Open-Meteo), {@link Govee}
 * (cloud API) and {@link Nest} (Google Smart Device Management).
 *
 * <p>Every request is HTTPS only (plain HTTP is refused before a socket is opened, on top of the
 * manifest's {@code usesCleartextTraffic="false"}), has connect and read timeouts, and reads at
 * most {@link #MAX_BODY_BYTES} of the reply so a misbehaving server cannot exhaust memory.
 * Requests block, so callers run them on a background executor ({@link #newExecutor}), never on
 * the main thread.
 *
 * <p>One narrow exception: {@link #requestAllowingLocalHttp} (used only by {@link HomeAssistant})
 * also accepts http:// to a {@code .local} / {@code .home.arpa} host name, matching the cleartext
 * domain-config in {@code res/xml/network_security_config.xml}.
 */
final class Http {
    /** Default time to establish the TCP/TLS connection. */
    static final int CONNECT_TIMEOUT_MS = 10_000;
    /** Default time to wait for each read on an open connection. */
    static final int READ_TIMEOUT_MS = 12_000;
    /** Largest reply body accepted (1 MiB); weather and device replies are a few KB. */
    static final int MAX_BODY_BYTES = 1 << 20;
    /** Sent with every request; identifies the app only, never the person. */
    static final String USER_AGENT = "TrekUI-Launcher (Android)";

    private static final int BUFFER_BYTES = 8192;

    /** A completed request: HTTP status code and the reply text (the error body for 4xx/5xx). */
    static final class Response {
        final int code;
        final String body;

        Response(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() { return code >= 200 && code < 300; }
    }

    private Http() {}

    /**
     * A single-thread executor with a named daemon thread, for one feature's background I/O
     * (requests from the same feature run in order and never keep the process alive).
     */
    static ExecutorService newExecutor(final String name) {
        final AtomicInteger n = new AtomicInteger();
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + n.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** True when the address is an absolute https:// URL (the only kind this client will fetch). */
    static boolean isHttps(String url) {
        if (url == null) return false;
        try {
            URI u = URI.create(url);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null && !u.getHost().isEmpty();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Host-name suffixes for which plain {@code http://} is allowed, and only through
     * {@link #requestAllowingLocalHttp}. They must match the cleartext {@code <domain-config>} in
     * {@code res/xml/network_security_config.xml}, which is what actually lets the platform open the
     * socket: {@code .local} (mDNS names such as {@code homeassistant.local}) and {@code .home.arpa}
     * (RFC 8375, the residential-network domain). Both can only name devices on the local network.
     * IP literals are deliberately not here: a network security config cannot scope cleartext to
     * the private address ranges, so an IP address must use HTTPS.
     */
    static final String[] LOCAL_CLEARTEXT_SUFFIXES = {".local", ".home.arpa"};

    /**
     * True when {@code url} is an absolute http:// address whose host is a local-network name
     * (see {@link #LOCAL_CLEARTEXT_SUFFIXES}); IP literals and every other host return false.
     */
    static boolean isLocalCleartext(String url) {
        if (url == null) return false;
        try {
            URI u = URI.create(url);
            return "http".equalsIgnoreCase(u.getScheme()) && isLocalHostName(u.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * True for a host name (not an IP literal) ending in one of {@link #LOCAL_CLEARTEXT_SUFFIXES}
     * with at least one label in front of the suffix, e.g. {@code homeassistant.local}.
     */
    static boolean isLocalHostName(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        if (h.startsWith("[") || !h.matches("[a-z0-9.-]+")) return false; // IPv6 literal or odd characters
        for (String suffix : LOCAL_CLEARTEXT_SUFFIXES) {
            if (h.endsWith(suffix) && h.length() > suffix.length() && h.charAt(h.length() - suffix.length() - 1) != '.') return true;
        }
        return false;
    }

    /** GET with JSON accepted and default timeouts. */
    static Response get(String url) throws IOException {
        return request("GET", url, Collections.singletonMap("Accept", "application/json"), null,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
    }

    /**
     * Performs one request and returns the status and body. Throws {@link IOException} for a
     * non-HTTPS URL, a network failure, a timeout or an over-long reply; HTTP error statuses are
     * returned (not thrown) so callers can turn the error body into a readable message.
     *
     * @param method    GET or POST
     * @param headers   request headers (may be empty)
     * @param body      request body, or null for none
     * @param connectMs connect timeout in milliseconds
     * @param readMs    read timeout in milliseconds
     */
    static Response request(String method, String url, Map<String, String> headers, byte[] body,
                            int connectMs, int readMs) throws IOException {
        if (!isHttps(url)) throw new IOException("INSECURE ADDRESS REFUSED");
        return execute(method, url, headers, body, connectMs, readMs, MAX_BODY_BYTES);
    }

    /**
     * Like {@link #request}, but also accepts plain http:// to a local-network host name
     * ({@link #isLocalCleartext}); used only for a self-hosted Home Assistant on the home network.
     * Every other http:// address is still refused before a socket is opened.
     *
     * @param maxBytes largest reply accepted (a big install's state list can exceed {@link #MAX_BODY_BYTES})
     */
    static Response requestAllowingLocalHttp(String method, String url, Map<String, String> headers, byte[] body,
                                             int connectMs, int readMs, int maxBytes) throws IOException {
        if (!isHttps(url) && !isLocalCleartext(url)) throw new IOException("INSECURE ADDRESS REFUSED");
        return execute(method, url, headers, body, connectMs, readMs, maxBytes);
    }

    /** Opens the connection and performs the request; callers have already checked the scheme. */
    private static Response execute(String method, String url, Map<String, String> headers, byte[] body,
                                    int connectMs, int readMs, int maxBytes) throws IOException {
        URL u = URI.create(url).toURL();
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(false); // a redirect could leave HTTPS or the expected host
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", USER_AGENT);
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) { os.write(body); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                try (InputStream stream = in) { text = readLimited(stream, maxBytes); }
            }
            return new Response(code, text);
        } finally {
            c.disconnect();
        }
    }

    /** Reads a stream as UTF-8 text, failing once more than {@code limit} bytes arrive. */
    static String readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER_BYTES];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > limit) throw new IOException("REPLY TOO LARGE");
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
