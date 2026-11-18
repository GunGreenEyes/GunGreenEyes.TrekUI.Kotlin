package com.gungreeneyes.trekui

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * The sound update channel's redirect rule (TUK-T21). The launcher's HTTPS helper never follows a
 * redirect on its own; the channel follows one only when the new address is https on the channel's site
 * ([SoundCatalog.isUpdateChannel]: mindattic.com or a subdomain), and at most [MAX_HOPS] times. Anything
 * else (plain http, another host, a missing Location) fails the download, which the caller treats like
 * being offline.
 *
 * The configured address ([com.gungreeneyes.trekui.core.CoreConfig.SFX_BASE_URL]) is already the final
 * one (www.mindattic.com answers 301 to mindattic.com), so normally no redirect happens at all.
 */
object SoundRedirect {
    /** Most redirects followed for one file. */
    const val MAX_HOPS = 3

    private const val TEMPORARY_REDIRECT = 307
    private const val PERMANENT_REDIRECT = 308

    private val REDIRECTS = setOf(
        HttpURLConnection.HTTP_MOVED_PERM,
        HttpURLConnection.HTTP_MOVED_TEMP,
        HttpURLConnection.HTTP_SEE_OTHER,
        TEMPORARY_REDIRECT,
        PERMANENT_REDIRECT,
    )

    /** One HTTP exchange that followed no redirect: the status, the body and the Location header. */
    class Reply(
        @JvmField val code: Int,
        @JvmField val body: ByteArray,
        @JvmField val location: String?,
    )

    /** One GET without following redirects, reading at most `maxBytes` of the body. */
    fun interface Hop {
        @Throws(IOException::class)
        fun get(url: String, maxBytes: Int): Reply
    }

    /**
     * Where a redirect from `from` may go: the Location resolved against `from` when it is https on the
     * channel's site, else null (refused).
     */
    @JvmStatic
    fun target(from: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val resolved = try {
            URI.create(from).resolve(location.trim()).toString()
        } catch (ignored: IllegalArgumentException) {
            null
        }
        return resolved?.takeIf { SoundCatalog.isUpdateChannel(it) }
    }

    /**
     * GETs `url` through `hop`, following allowed redirects ([target]), and returns the HTTP 200 body.
     *
     * @throws IOException for any other status, a refused redirect or more than [MAX_HOPS] of them
     */
    @JvmStatic
    @Throws(IOException::class)
    fun follow(url: String, maxBytes: Int, hop: Hop): ByteArray {
        var current: String? = url
        var why = "too many redirects"
        var hops = 0
        while (current != null && hops <= MAX_HOPS) {
            val r = hop.get(current, maxBytes)
            if (r.code == HttpURLConnection.HTTP_OK) return r.body
            hops++
            if (r.code in REDIRECTS) {
                current = target(current, r.location)
                if (current == null) why = "redirect refused"
            } else {
                why = "HTTP " + r.code
                current = null
            }
        }
        throw IOException(why)
    }
}
