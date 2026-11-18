package com.gungreeneyes.trekui

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The launcher's one small HTTPS client, shared by [Weather] (Open-Meteo), [Govee]
 * (cloud API), [Nest] (Google Smart Device Management) and the sound update channel ([SoundUpdateCheck]).
 *
 * Every request is HTTPS only (plain HTTP is refused before a socket is opened, on top of the
 * manifest's `usesCleartextTraffic="false"`), has connect and read timeouts, and reads at
 * most [MAX_BODY_BYTES] of the reply so a misbehaving server cannot exhaust memory.
 * Requests block, so callers run them on a background executor ([newExecutor]), never on
 * the main thread.
 *
 * One narrow exception: [requestAllowingLocalHttp] (used only by [HomeAssistant])
 * also accepts http:// to a `.local` / `.home.arpa` host name, matching the cleartext
 * domain-config in `res/xml/network_security_config.xml`.
 */
internal object Http {
    /** Default time to establish the TCP/TLS connection. */
    const val CONNECT_TIMEOUT_MS = 10_000

    /** Default time to wait for each read on an open connection. */
    const val READ_TIMEOUT_MS = 12_000

    /** Largest reply body accepted (1 MiB); weather and device replies are a few KB. */
    const val MAX_BODY_BYTES = 1 shl 20

    /** Sent with every request; identifies the app only, never the person. */
    const val USER_AGENT = "TrekUI-Launcher (Android)"

    private const val BUFFER_BYTES = 8192

    /** A completed request: HTTP status code and the reply text (the error body for 4xx/5xx). */
    class Response(
        @JvmField val code: Int,
        @JvmField val body: String,
    ) {
        fun ok(): Boolean = code in 200..299
    }

    /**
     * A single-thread executor with a named daemon thread, for one feature's background I/O
     * (requests from the same feature run in order and never keep the process alive).
     */
    @JvmStatic
    fun newExecutor(name: String): ExecutorService {
        val n = AtomicInteger()
        return Executors.newSingleThreadExecutor { r ->
            Thread(r, name + "-" + n.incrementAndGet()).apply { isDaemon = true }
        }
    }

    /** True when the address is an absolute https:// URL (the only kind this client will fetch). */
    @JvmStatic
    fun isHttps(url: String?): Boolean {
        if (url == null) return false
        return try {
            val u = URI.create(url)
            "https".equals(u.scheme, ignoreCase = true) && !u.host.isNullOrEmpty()
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * Host-name suffixes for which plain `http://` is allowed, and only through
     * [requestAllowingLocalHttp]. They must match the cleartext `<domain-config>` in
     * `res/xml/network_security_config.xml`, which is what actually lets the platform open the
     * socket: `.local` (mDNS names such as `homeassistant.local`) and `.home.arpa`
     * (RFC 8375, the residential-network domain). Both can only name devices on the local network.
     * IP literals are deliberately not here: a network security config cannot scope cleartext to
     * the private address ranges, so an IP address must use HTTPS.
     */
    @JvmField
    val LOCAL_CLEARTEXT_SUFFIXES: Array<String> = arrayOf(".local", ".home.arpa")

    /**
     * True when `url` is an absolute http:// address whose host is a local-network name
     * (see [LOCAL_CLEARTEXT_SUFFIXES]); IP literals and every other host return false.
     */
    @JvmStatic
    fun isLocalCleartext(url: String?): Boolean {
        if (url == null) return false
        return try {
            val u = URI.create(url)
            "http".equals(u.scheme, ignoreCase = true) && isLocalHostName(u.host)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /**
     * True for a host name (not an IP literal) ending in one of [LOCAL_CLEARTEXT_SUFFIXES]
     * with at least one label in front of the suffix, e.g. `homeassistant.local`.
     */
    @JvmStatic
    fun isLocalHostName(host: String?): Boolean {
        if (host == null) return false
        var h = host.lowercase(Locale.ROOT)
        if (h.endsWith(".")) h = h.substring(0, h.length - 1)
        if (h.startsWith("[") || !h.matches(Regex("[a-z0-9.-]+"))) return false // IPv6 literal or odd characters
        return LOCAL_CLEARTEXT_SUFFIXES.any { suffix ->
            h.endsWith(suffix) && h.length > suffix.length && h[h.length - suffix.length - 1] != '.'
        }
    }

    /** GET with JSON accepted and default timeouts. */
    @JvmStatic
    @Throws(IOException::class)
    fun get(url: String?): Response =
        request("GET", url, mapOf("Accept" to "application/json"), null, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)

    /**
     * Performs one request and returns the status and body. Throws [IOException] for a
     * non-HTTPS URL, a network failure, a timeout or an over-long reply; HTTP error statuses are
     * returned (not thrown) so callers can turn the error body into a readable message.
     *
     * @param method    GET or POST
     * @param headers   request headers (may be empty or null)
     * @param body      request body, or null for none
     * @param connectMs connect timeout in milliseconds
     * @param readMs    read timeout in milliseconds
     */
    @JvmStatic
    @Throws(IOException::class)
    fun request(
        method: String,
        url: String?,
        headers: Map<String, String>?,
        body: ByteArray?,
        connectMs: Int,
        readMs: Int,
    ): Response {
        if (!isHttps(url)) throw IOException("INSECURE ADDRESS REFUSED")
        val r = execute(method, url!!, headers, body, connectMs, readMs, MAX_BODY_BYTES)
        return Response(r.code, String(r.body, Charsets.UTF_8))
    }

    /**
     * Like [request], but also accepts plain http:// to a local-network host name
     * ([isLocalCleartext]); used only for a self-hosted Home Assistant on the home network.
     * Every other http:// address is still refused before a socket is opened.
     *
     * @param maxBytes largest reply accepted (a big install's state list can exceed [MAX_BODY_BYTES])
     */
    @JvmStatic
    @Throws(IOException::class)
    fun requestAllowingLocalHttp(
        method: String,
        url: String?,
        headers: Map<String, String>?,
        body: ByteArray?,
        connectMs: Int,
        readMs: Int,
        maxBytes: Int,
    ): Response {
        if (!isHttps(url) && !isLocalCleartext(url)) throw IOException("INSECURE ADDRESS REFUSED")
        val r = execute(method, url!!, headers, body, connectMs, readMs, maxBytes)
        return Response(r.code, String(r.body, Charsets.UTF_8))
    }

    /**
     * One GET of a binary file for the sound update channel: HTTPS only, default timeouts, at most `maxBytes`
     * of the body, and no redirect followed here. [SoundRedirect.follow] decides which redirects to take.
     */
    @JvmStatic
    @Throws(IOException::class)
    fun getOnce(url: String?, maxBytes: Int): SoundRedirect.Reply {
        if (!isHttps(url)) throw IOException("INSECURE ADDRESS REFUSED")
        val r = execute("GET", url!!, mapOf("Accept" to "*/*"), null, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, maxBytes)
        return SoundRedirect.Reply(r.code, r.body, r.location)
    }

    /** A raw reply: status, at most the caller's limit of body bytes, and the Location header. */
    private class Raw(val code: Int, val body: ByteArray, val location: String?)

    /**
     * Opens the connection and performs the request: the status, at most `maxBytes` of the body (the
     * error body for 4xx/5xx) and the Location header. Callers have already checked the scheme.
     */
    private fun execute(
        method: String,
        url: String,
        headers: Map<String, String>?,
        body: ByteArray?,
        connectMs: Int,
        readMs: Int,
        maxBytes: Int,
    ): Raw {
        val c = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.instanceFollowRedirects = false // a redirect could leave HTTPS or the expected host
            c.useCaches = false
            c.setRequestProperty("User-Agent", USER_AGENT)
            headers?.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            val input: InputStream? = if (code >= 400) c.errorStream else c.inputStream
            val reply = input?.use { readLimitedBytes(it, maxBytes) } ?: ByteArray(0)
            return Raw(code, reply, c.getHeaderField("Location"))
        } finally {
            c.disconnect()
        }
    }

    /** Reads a stream's bytes (callers decode text as UTF-8), failing once more than `limit` bytes arrive. */
    @JvmStatic
    @Throws(IOException::class)
    fun readLimitedBytes(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER_BYTES)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            if (out.size() + n > limit) throw IOException("REPLY TOO LARGE")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
