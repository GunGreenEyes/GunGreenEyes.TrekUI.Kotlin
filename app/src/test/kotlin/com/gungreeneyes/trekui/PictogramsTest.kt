package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import java.util.Locale

/** [Pictograms]: the catalogue, the keyword table and app → pictogram matching (plus [IconFactory.resolveGlyph]). */
class PictogramsTest {

    private fun g(pkg: String, label: String?, cat: Int): String? = Pictograms.defaultGlyph(pkg, label, cat)

    @Test
    fun catalogueHasAboutAHundredUniqueNames() {
        val names = Pictograms.ALL.toHashSet()
        assertThat(names).hasSize(Pictograms.ALL.size)
        assertThat(Pictograms.ALL.size).isAtLeast(100)
    }

    @Test
    fun everyCoreRuleGlyphIsInTheCatalogue() {
        for (rule in IconFactory.GLYPH_RULES) assertThat(Pictograms.exists(rule[0])).isTrue()
    }

    @Test
    fun everyKeywordRowNamesAKnownPictogramAndHasKeywords() {
        for (row in Pictograms.KEYWORDS) {
            assertThat(Pictograms.exists(row[0])).isTrue()
            assertThat(row.size).isAtLeast(2)
            for (i in 1 until row.size) assertThat(row[i]).isEqualTo(row[i].lowercase(Locale.US))
        }
    }

    @Test
    fun everyPictogramHasAPng() {
        // the glyph PNGs ship from :core's assets (shared with the companion apps)
        var assets = File("../core/src/main/assets")
        if (!assets.isDirectory) assets = File("core/src/main/assets")
        assertThat(assets.isDirectory).isTrue()
        for (n in Pictograms.ALL) {
            assertWithMessage("glyphs/$n").that(File(assets, "glyphs/$n.png").isFile).isTrue()
        }
    }

    @Test
    fun commonAppsByPackage() {
        assertThat(g("com.spotify.music", "Spotify", AppInfo.CAT_MEDIA)).isEqualTo("headphones")
        assertThat(g("com.chase.sig.android", "Chase", AppInfo.CAT_OTHER)).isEqualTo("bank")
        assertThat(g("com.ubercab", "Uber", AppInfo.CAT_NAV)).isEqualTo("car")
        assertThat(g("com.netflix.mediaclient", "Netflix", AppInfo.CAT_MEDIA)).isEqualTo("clapper")
        assertThat(g("com.whatsapp", "WhatsApp", AppInfo.CAT_COMM)).isEqualTo("chat")
        assertThat(g("com.paypal.android.p2pmobile", "PayPal", AppInfo.CAT_OTHER)).isEqualTo("wallet")
        assertThat(g("com.amazon.mshop.android.shopping", "Amazon Shopping", AppInfo.CAT_OTHER)).isEqualTo("cart")
        assertThat(g("com.airbnb.android", "Airbnb", AppInfo.CAT_NAV)).isEqualTo("bed")
        assertThat(g("com.duolingo", "Duolingo", AppInfo.CAT_OTHER)).isEqualTo("translate")
        assertThat(g("com.x8bit.bitwarden", "Bitwarden", AppInfo.CAT_OTHER)).isEqualTo("key")
        assertThat(g("com.termux", "Termux", AppInfo.CAT_SYSTEM)).isEqualTo("code")
    }

    @Test
    fun labelWordsWhenThePackageSaysNothing() {
        assertThat(g("com.acme.x1", "Acme Bank", AppInfo.CAT_OTHER)).isEqualTo("bank")
        assertThat(g("com.acme.x2", "Pet Diary", AppInfo.CAT_OTHER)).isEqualTo("paw")
    }

    @Test
    fun packageBeatsLabel() {
        // the label says "music" but the package names a podcast app
        assertThat(g("au.com.shiftyjelly.pocketcasts", "Music & Podcasts", AppInfo.CAT_MEDIA)).isEqualTo("mic")
    }

    @Test
    fun gamesWithoutAKeywordGetTheController() {
        assertThat(g("com.studio.xyzzy", "Xyzzy", AppInfo.CAT_GAMES)).isEqualTo("gamepad")
        assertThat(g("com.studio.xyzzy", "Xyzzy", AppInfo.CAT_OTHER)).isNull()
    }

    @Test
    fun unknownAppsKeepTheirIcon() {
        assertThat(g("com.example.thing", "Thing", AppInfo.CAT_OTHER)).isNull()
        assertThat(g("", null, AppInfo.CAT_OTHER)).isNull()
    }

    @Test
    fun noiseWordsNeverMatch() {
        // "android", "google", "apps" are removed before matching ("apps" would otherwise hit the launcher row)
        assertThat(g("com.google.android.apps.xyzzy", "Xyzzy", AppInfo.CAT_OTHER)).isNull()
    }

    @Test
    fun shortKeywordsNeedWholeWords() {
        assertThat(Pictograms.hit("ai", Pictograms.words("com.mail.client"), "com.mail.client")).isFalse()
        assertThat(Pictograms.hit("ai", Pictograms.words("com.example.ai"), "com.example.ai")).isTrue()
        assertThat(Pictograms.hit("ev", Pictograms.words("com.every.day"), "com.every.day")).isFalse()
        assertThat(Pictograms.hit("uber", Pictograms.words("com.ubercab"), "com.ubercab")).isTrue()
        assertThat(Pictograms.hit("android.auto", Pictograms.words("com.google.android.auto"), "com.google.android.auto")).isTrue()
    }

    @Test
    fun words_splitsOnPunctuationAndLowercases() {
        assertThat(Pictograms.words("Com.Foo_Bar-baz 2")).containsExactly("com", "foo", "bar", "baz", "2").inOrder()
        assertThat(Pictograms.words(null)).isEmpty()
    }

    @Test
    fun resolveGlyph_choiceThenCoreThenKeywords() {
        // a picked pictogram wins over the core rule
        assertThat(IconFactory.resolveGlyph("phone", "glyph:rocket", true, "com.x", "X", AppInfo.CAT_OTHER)).isEqualTo("rocket")
        // an unknown picked name falls back to the core glyph
        assertThat(IconFactory.resolveGlyph("phone", "glyph:nope", true, "com.x", "X", AppInfo.CAT_OTHER)).isEqualTo("phone")
        // "own icon" and pack choices remove the pictogram
        assertThat(IconFactory.resolveGlyph("phone", "app", true, "com.x", "X", AppInfo.CAT_OTHER)).isNull()
        assertThat(IconFactory.resolveGlyph("phone", "pack:com.pack/phone_icon", true, "com.x", "X", AppInfo.CAT_OTHER)).isNull()
        // no choice: core, else keyword default only when auto is on
        assertThat(IconFactory.resolveGlyph("phone", null, false, "com.x", "X", AppInfo.CAT_OTHER)).isEqualTo("phone")
        assertThat(IconFactory.resolveGlyph(null, null, true, "com.spotify.music", "Spotify", AppInfo.CAT_MEDIA)).isEqualTo("headphones")
        assertThat(IconFactory.resolveGlyph(null, null, false, "com.spotify.music", "Spotify", AppInfo.CAT_MEDIA)).isNull()
    }
}
