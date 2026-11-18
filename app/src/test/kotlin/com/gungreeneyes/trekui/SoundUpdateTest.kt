package com.gungreeneyes.trekui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.gungreeneyes.trekui.core.CoreConfig
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The sound update channel (TUK-T21): version comparison, update-channel-first source order, manifest
 * validation and size caps, checksum rejection, the hot-fix override of a bundled synthesized sound and
 * the 24-hour throttle with an injected clock, and the redirect rule ([SoundRedirect]). The network is a
 * fake [SoundUpdate.Fetcher] (or [SoundRedirect.Hop]) and the decoder a copying [SoundUpdate.Renderer], so
 * nothing leaves the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SoundUpdateTest {
    private lateinit var ctx: Context

    /** Serves a map of url -> body and counts requests; unknown urls fail like a 404. */
    private class FakeServer : SoundUpdate.Fetcher {
        val files = HashMap<String, ByteArray>()
        val requests = ArrayList<String>()

        override fun get(url: String, maxBytes: Int): ByteArray {
            requests.add(url)
            val body = files[url] ?: throw IOException("HTTP 404")
            if (body.size > maxBytes) throw IOException("REPLY TOO LARGE")
            return body
        }
    }

    private val copyRenderer = SoundUpdate.Renderer { original, _, dst -> original.copyTo(dst, overwrite = true) }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        reset()
    }

    @After
    fun tearDown() = reset()

    private fun reset() {
        SoundImporter.deleteTree(SoundUpdateFiles.dir(ctx))
        SoundCache.clear(ctx)
        ctx.getSharedPreferences("trekui_sfx_update", Context.MODE_PRIVATE).edit().clear().commit()
        SoundCatalog.invalidate()
    }

    // ---- version comparison ----------------------------------------------------------------------

    @Test
    fun onlyAHigherVersionReplacesTheBundledManifest() {
        assertThat(SoundCatalog.isNewer(202611181200L, 0L)).isTrue()
        assertThat(SoundCatalog.isNewer(5L, 5L)).isFalse()
        assertThat(SoundCatalog.isNewer(4L, 5L)).isFalse()

        val bundled = SoundCatalog.parse(manifest(5, entry("tap_rot_1", "https://example.org/a.mp3", SHA_A)))
        val older = SoundCatalog.parse(manifest(5, entry("tap_rot_1", CH + "a.ogg", SHA_B)))
        assertThat(bundled.merge(older)).isSameInstanceAs(bundled)

        val newer = SoundCatalog.parse(manifest(6, entry("tap_rot_1", CH + "a.ogg", SHA_B), owned("owned_new", SHA_A)))
        val merged = bundled.merge(newer)
        assertThat(merged.version).isEqualTo(6L)
        assertThat(merged.get("tap_rot_1")!!.source!!.sha256).isEqualTo(SHA_B)
        assertThat(merged.get("owned_new")).isNotNull()
    }

    @Test
    fun bundledManifestHasNoVersionSoAnyPublishedOneIsNewer() {
        assertThat(SoundCatalog.loadBundled(ctx).version).isEqualTo(0L)
    }

    // ---- source order ----------------------------------------------------------------------------

    @Test
    fun updateChannelSourcesAreTriedFirst() {
        val s = SoundCatalog.parse(
            "{\"sounds\":[{\"id\":\"mixed\"," +
                "\"source\":{\"url\":\"https://www.trekcore.com/a.mp3\",\"sha256\":\"$SHA_A\"}," +
                "\"alternates\":[{\"url\":\"https://raw.githubusercontent.com/x/a.mp3\",\"sha256\":\"$SHA_A\"}," +
                "{\"url\":\"${CH}a.ogg\",\"sha256\":\"$SHA_A\"}]}]}",
        ).get("mixed")!!
        assertThat(s.allSources().map { it.url }).containsExactly(
            CH + "a.ogg", "https://www.trekcore.com/a.mp3", "https://raw.githubusercontent.com/x/a.mp3",
        ).inOrder()
        assertThat(s.owned()).isFalse()
        assertThat(CH).isEqualTo(CoreConfig.SFX_BASE_URL)
    }

    @Test
    fun anyHttpsMindatticAddressCountsAsTheChannelSite() {
        assertThat(SoundCatalog.isUpdateChannel(CH + "a.ogg")).isTrue()
        assertThat(SoundCatalog.isUpdateChannel(WWW + "a.ogg")).isTrue()
        assertThat(SoundCatalog.isUpdateChannel("https://MindAttic.com/other/b.ogg")).isTrue()
        assertThat(SoundCatalog.isUpdateChannel("http://mindattic.com/gungreeneyes/trekui/sfx/a.ogg")).isFalse()
        assertThat(SoundCatalog.isUpdateChannel("https://mindattic.com.evil.example/a.ogg")).isFalse()
        assertThat(SoundCatalog.isUpdateChannel("https://notmindattic.com/a.ogg")).isFalse()
        assertThat(SoundCatalog.isUpdateChannel("not a url")).isFalse()
        assertThat(SoundCatalog.isUpdateChannel(null)).isFalse()
    }

    // ---- redirects -------------------------------------------------------------------------------

    /** Serves scripted replies per url (code, body, Location) and records each hop. */
    private class FakeHops : SoundRedirect.Hop {
        val replies = HashMap<String, SoundRedirect.Reply>()
        val hops = ArrayList<String>()

        override fun get(url: String, maxBytes: Int): SoundRedirect.Reply {
            hops.add(url)
            return replies[url] ?: SoundRedirect.Reply(404, ByteArray(0), null)
        }

        fun redirect(from: String, code: Int, to: String?) {
            replies[from] = SoundRedirect.Reply(code, ByteArray(0), to)
        }
    }

    @Test
    fun theLiveWwwRedirectToTheBareHostIsFollowed() {
        // what www.mindattic.com answers today (HTTP 301 to the bare host, where the files live)
        val net = FakeHops()
        net.redirect(WWW + "manifest.json", 301, CH + "manifest.json")
        net.replies[CH + "manifest.json"] = SoundRedirect.Reply(200, "{}".toByteArray(), null)
        assertThat(String(SoundRedirect.follow(WWW + "manifest.json", 100, net))).isEqualTo("{}")
        assertThat(net.hops).containsExactly(WWW + "manifest.json", CH + "manifest.json").inOrder()
        // a relative Location resolves against the address it came from
        assertThat(SoundRedirect.target(CH + "a.ogg", "b.ogg")).isEqualTo(CH + "b.ogg")
    }

    @Test
    fun redirectsLeavingHttpsOrMindatticAreRefused() {
        assertThat(SoundRedirect.target(CH, "http://mindattic.com/gungreeneyes/trekui/sfx/a.ogg")).isNull()
        assertThat(SoundRedirect.target(CH, "https://cdn.example.com/a.ogg")).isNull()
        assertThat(SoundRedirect.target(CH, null)).isNull()
        assertThat(SoundRedirect.target(CH, " ")).isNull()

        val net = FakeHops()
        net.redirect(CH + "a.ogg", 302, "https://cdn.example.com/a.ogg")
        val e = assertThrows(IOException::class.java) { SoundRedirect.follow(CH + "a.ogg", 100, net) }
        assertThat(e).hasMessageThat().contains("refused")
        assertThat(net.hops).containsExactly(CH + "a.ogg") // the foreign host was never contacted
    }

    @Test
    fun redirectLoopsAndErrorsStopTheDownload() {
        val net = FakeHops()
        net.redirect(CH + "a.ogg", 307, CH + "b.ogg")
        net.redirect(CH + "b.ogg", 308, CH + "a.ogg")
        assertThrows(IOException::class.java) { SoundRedirect.follow(CH + "a.ogg", 100, net) }
        assertThat(net.hops).hasSize(SoundRedirect.MAX_HOPS + 1)

        val e = assertThrows(IOException::class.java) { SoundRedirect.follow(CH + "gone.ogg", 100, FakeHops()) }
        assertThat(e).hasMessageThat().isEqualTo("HTTP 404")
    }

    @Test
    fun ownedSoundsAreLeftOutOfTheLibraryDownload() {
        val show = entry("tap_rot_1", "https://example.org/a.mp3", SHA_B)
        val c = SoundCatalog.parse(manifest(1, owned("synth_tap_1", SHA_A), show))
        assertThat(c.owned().map { it.id }).containsExactly("synth_tap_1")
        assertThat(c.available(false).map { it.id }).containsExactly("tap_rot_1")
    }

    // ---- manifest validation ---------------------------------------------------------------------

    @Test
    fun malformedManifestsAreRejected() {
        val bad = listOf(
            "not json", "[]", "{\"sounds\":[]}", "{\"version\":\"7\",\"sounds\":[]}", "{\"version\":1.5,\"sounds\":[]}",
            "{\"version\":0,\"sounds\":[]}", "{\"version\":3}", "{\"version\":3,\"format\":99,\"sounds\":[]}",
        )
        for (json in bad) {
            assertThrows(json, IOException::class.java) { SoundUpdate.parseRemote(json.toByteArray()) }
        }
        assertThat(SoundUpdate.parseRemote(manifest(202611181230L).toByteArray()).version).isEqualTo(202611181230L)
    }

    @Test
    fun oversizedManifestIsRejected() {
        val big = ByteArray(SoundUpdate.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }
        assertThrows(IOException::class.java) { SoundUpdate.parseRemote(big) }
    }

    @Test
    fun failuresLeaveEverythingAsBundled() {
        val server = FakeServer()
        server.files[SoundUpdate.manifestUrl()] = "{\"version\":".toByteArray()
        val r = SoundUpdate.run(ctx, server, copyRenderer)
        assertThat(r.manifestAccepted).isFalse()
        assertThat(r.changed()).isFalse()
        assertThat(r.errors).isNotEmpty()
        assertThat(SoundUpdateFiles.manifestFile(ctx).exists()).isFalse()

        server.files.clear() // offline / 404
        assertThat(SoundUpdate.run(ctx, server, copyRenderer).changed()).isFalse()
        assertThat(SoundCatalog.load(ctx).version).isEqualTo(0L)
    }

    // ---- downloads -------------------------------------------------------------------------------

    @Test
    fun checksumMismatchIsRejected() {
        val audio = clip(1)
        val server = FakeServer()
        server.files[SoundUpdate.manifestUrl()] = manifest(7, owned("owned_beep", sha(audio))).toByteArray()
        server.files[CH + "owned_beep.ogg"] = clip(2) // not the published bytes

        val r = SoundUpdate.run(ctx, server, copyRenderer)
        assertThat(r.manifestAccepted).isTrue()
        assertThat(r.failed).isEqualTo(1)
        assertThat(r.updated).isEqualTo(0)
        assertThat(SoundCache.isCached(ctx, "owned_beep")).isFalse()

        server.files[CH + "owned_beep.ogg"] = audio
        val ok = SoundUpdate.run(ctx, server, copyRenderer)
        assertThat(ok.updated).isEqualTo(1)
        assertThat(SoundCache.file(ctx, "owned_beep").readBytes()).isEqualTo(audio)
        assertThat(SoundCatalog.load(ctx).get("owned_beep")).isNotNull()

        server.requests.clear()
        assertThat(SoundUpdate.run(ctx, server, copyRenderer).updated).isEqualTo(0) // already current
        assertThat(server.requests).containsExactly(SoundUpdate.manifestUrl())
    }

    @Test
    fun oversizedSoundIsRejected() {
        val audio = ByteArray(SoundUpdate.MAX_SOUND_BYTES + 1)
        val server = FakeServer()
        server.files[SoundUpdate.manifestUrl()] = manifest(7, owned("owned_big", sha(audio))).toByteArray()
        server.files[CH + "owned_big.ogg"] = audio
        assertThat(SoundUpdate.run(ctx, server, copyRenderer).failed).isEqualTo(1)
        assertThat(SoundCache.isCached(ctx, "owned_big")).isFalse()
    }

    @Test
    fun aFixedSynthesizedSoundOverridesTheBundledOneUntilTheChannelMatchesItAgain() {
        assertThat(SoundCache.hasAsset(ctx, "synth_tap_1")).isTrue()
        val bundledSha = bundledShaOf("synth_tap_1")
        val fixed = clip(3)
        val server = FakeServer()
        server.files[SoundUpdate.manifestUrl()] =
            manifest(8, owned("synth_tap_1", sha(fixed)), owned("synth_nav", bundledShaOf("synth_nav"))).toByteArray()
        server.files[CH + "synth_tap_1.ogg"] = fixed

        val r = SoundUpdate.run(ctx, server, copyRenderer)
        assertThat(r.updated).isEqualTo(1) // synth_nav equals the bundled asset: nothing to fetch
        assertThat(server.requests).doesNotContain(CH + "synth_nav.ogg")
        assertThat(SoundCache.where(ctx, "synth_tap_1")).isEqualTo(SoundCache.CACHE)
        assertThat(SoundCache.where(ctx, "synth_nav")).isEqualTo(SoundCache.ASSET)

        server.files[SoundUpdate.manifestUrl()] = manifest(9, owned("synth_tap_1", bundledSha)).toByteArray()
        val back = SoundUpdate.run(ctx, server, copyRenderer)
        assertThat(back.reverted).isEqualTo(1)
        assertThat(SoundCache.where(ctx, "synth_tap_1")).isEqualTo(SoundCache.ASSET)
    }

    // ---- 24-hour throttle ------------------------------------------------------------------------

    @Test
    fun throttleIsPure() {
        val day = SoundUpdate.INTERVAL_MS
        assertThat(SoundUpdate.isDue(0L, 1_000L)).isTrue()
        assertThat(SoundUpdate.isDue(10_000L, 10_000L + day - 1)).isFalse()
        assertThat(SoundUpdate.isDue(10_000L, 10_000L + day)).isTrue()
        assertThat(SoundUpdate.isDue(10_000L, 5_000L)).isTrue() // clock moved back
    }

    @Test
    fun checksRunAtMostOnceADayUnlessForced() {
        val server = FakeServer()
        server.files[SoundUpdate.manifestUrl()] = manifest(3).toByteArray()
        var now = 1_700_000_000_000L
        assertThat(SoundUpdate.checkIfDue(ctx, now, false, server, copyRenderer)).isNotNull()
        now += SoundUpdate.INTERVAL_MS - 60_000L
        assertThat(SoundUpdate.checkIfDue(ctx, now, false, server, copyRenderer)).isNull()
        // the user opened the sound settings: no throttle
        assertThat(SoundUpdate.checkIfDue(ctx, now, true, server, copyRenderer)).isNotNull()
        now += SoundUpdate.INTERVAL_MS - 1
        assertThat(SoundUpdate.checkIfDue(ctx, now, false, server, copyRenderer)).isNull()
        now += 1
        assertThat(SoundUpdate.checkIfDue(ctx, now, false, server, copyRenderer)).isNotNull()
        assertThat(server.requests).hasSize(3)
    }

    private fun bundledShaOf(id: String): String =
        ctx.assets.open(SoundCache.assetPath(id)).use { SoundProcessor.sha256Hex(it) }

    private companion object {
        const val CH = "https://mindattic.com/gungreeneyes/trekui/sfx/"
        const val WWW = "https://www.mindattic.com/gungreeneyes/trekui/sfx/"
        val SHA_A = "a".repeat(64)
        val SHA_B = "b".repeat(64)

        /** A fake "audio" body bigger than a WAV header, distinct per seed. */
        fun clip(seed: Int): ByteArray = ByteArray(256) { (it * seed + seed).toByte() }

        fun entry(id: String, url: String, sha: String): String =
            "{\"id\":\"$id\",\"group\":\"SYNTHESIZED\",\"available\":true," +
                "\"source\":{\"url\":\"$url\",\"sha256\":\"$sha\",\"bytes\":256}," +
                "\"start\":0,\"end\":0,\"gain_db\":0,\"mono\":true,\"fade_in_ms\":0,\"fade_out_ms\":0}"

        /** An owned sound: its only source is on the update channel. */
        fun owned(id: String, sha: String): String = entry(id, "$CH$id.ogg", sha)

        fun sha(bytes: ByteArray): String = SoundProcessor.sha256Hex(bytes)

        fun manifest(version: Long, vararg entries: String): String =
            "{\"format\":1,\"version\":$version,\"sounds\":[" + entries.joinToString(",") + "]}"
    }
}
