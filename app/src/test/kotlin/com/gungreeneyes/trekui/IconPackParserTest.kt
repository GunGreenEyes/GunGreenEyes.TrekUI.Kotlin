package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory

/** [IconPackParser]: appfilter.xml parsing, driven from the JDK's SAX parser like the app drives it from XmlPullParser. */
class IconPackParserTest {

    @Test
    fun exactComponentMatches() {
        val r = parse(APPFILTER)
        assertThat(r.drawableFor("com.google.android.dialer", "com.google.android.dialer.extensions.GoogleDialtactsActivity")).isEqualTo("phone")
        assertThat(r.drawableFor("com.android.chrome", "org.chromium.chrome.browser.ChromeTabbedActivity")).isEqualTo("chrome_alt")
    }

    @Test
    fun unknownActivityFallsBackToFirstDrawableOfPackage() {
        val r = parse(APPFILTER)
        assertThat(r.drawableFor("com.android.chrome", "com.example.Renamed")).isEqualTo("chrome")
        assertThat(r.drawableFor("com.android.chrome", null)).isEqualTo("chrome")
        assertThat(r.drawableFor("com.unknown", "com.unknown.Main")).isNull()
        assertThat(r.drawableFor(null, "x")).isNull()
    }

    @Test
    fun relativeClassNamesExpandAndFirstEntryWins() {
        val r = parse(APPFILTER)
        assertThat(r.drawableFor("com.whatsapp", "com.whatsapp.Main")).isEqualTo("whatsapp")
    }

    @Test
    fun malformedAndPseudoComponentsAreSkippedButDrawablesKept() {
        val r = parse(APPFILTER)
        assertThat(r.byComponent).doesNotContainKey(":LAUNCHER_ACTION_APP_DRAWER")
        assertThat(r.byPackage).doesNotContainKey("broken")
        assertThat(r.drawables).contains("drawer")
        assertThat(r.drawables).doesNotContain("")
        assertThat(r.size()).isEqualTo(4)
    }

    @Test
    fun unthemedAppRecipe() {
        val r = parse(APPFILTER)
        assertThat(r.backs).containsExactly("iconback_a", "iconback_b", "iconback_c").inOrder()
        assertThat(r.mask).isEqualTo("iconmask")
        assertThat(r.upon).isEqualTo("iconupon")
        assertThat(r.scale).isWithin(1e-6f).of(0.75f)
        assertThat(r.themesUnknownApps()).isTrue()
        val back = r.backFor("com.example.app")
        assertThat(r.backs).contains(back)
        assertThat(r.backFor("com.example.app")).isEqualTo(back) // stable per package
    }

    @Test
    fun calendarPrefixPlusDay() {
        val r = parse(APPFILTER)
        assertThat(r.calendarFor("com.google.android.calendar", "com.android.calendar.AllInOneActivity", 17)).isEqualTo("calendar_17")
        assertThat(r.calendarFor("com.android.chrome", "com.google.android.apps.chrome.Main", 3)).isNull()
    }

    @Test
    fun packWithoutRecipeLeavesUnknownAppsAlone() {
        val r = parse("<resources><item component=\"ComponentInfo{a.b/a.b.C}\" drawable=\"abc\"/></resources>")
        assertThat(r.themesUnknownApps()).isFalse()
        assertThat(r.backFor("x")).isNull()
        assertThat(r.scale).isEqualTo(1f)
    }

    @Test
    fun outOfRangeOrBadScaleIgnored() {
        assertThat(parse("<resources><scale factor=\"7\"/></resources>").scale).isEqualTo(1f)
        assertThat(parse("<resources><scale factor=\"abc\"/></resources>").scale).isEqualTo(1f)
    }

    @Test
    fun iconbackWithGapsAndImg0() {
        val r = parse("<resources><iconback img0=\"z\" img2=\"b\" img5=\"e\"/></resources>")
        assertThat(r.backs).containsExactly("z", "b", "e").inOrder()
    }

    @Test
    fun component_normalisation() {
        assertThat(IconPackParser.component("ComponentInfo{com.a/com.a.Main}")).isEqualTo("com.a/com.a.Main")
        assertThat(IconPackParser.component(" ComponentInfo{com.a/.Main} ")).isEqualTo("com.a/com.a.Main")
        assertThat(IconPackParser.component("com.a/com.a.Main")).isEqualTo("com.a/com.a.Main")
        assertThat(IconPackParser.component("ComponentInfo{com.a}")).isNull()
        assertThat(IconPackParser.component("ComponentInfo{/x}")).isNull()
        assertThat(IconPackParser.component("ComponentInfo{com.a/}")).isNull()
        assertThat(IconPackParser.component(null)).isNull()
    }

    @Test
    fun clean_stripsDrawablePrefixAndBlanks() {
        assertThat(IconPackParser.clean("@drawable/foo")).isEqualTo("foo")
        assertThat(IconPackParser.clean("  bar ")).isEqualTo("bar")
        assertThat(IconPackParser.clean("   ")).isNull()
    }

    @Test
    fun drawableCatalogueAddsIconsForThePicker() {
        val p = IconPackParser()
        p.catalogueItem("extra_one")
        p.catalogueItem("")
        p.catalogueItem("extra_one")
        assertThat(p.result().drawableList()).containsExactly("extra_one")
    }

    companion object {
        private val APPFILTER = """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
              <iconback img1="iconback_a" img2="iconback_b" img3="iconback_c"/>
              <iconmask img1="iconmask"/>
              <iconupon img1="iconupon"/>
              <scale factor="0.75"/>
              <!-- Phone -->
              <item component="ComponentInfo{com.google.android.dialer/com.google.android.dialer.extensions.GoogleDialtactsActivity}" drawable="phone"/>
              <item component="ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}" drawable="chrome"/>
              <item component="ComponentInfo{com.android.chrome/org.chromium.chrome.browser.ChromeTabbedActivity}" drawable="chrome_alt"/>
              <item component="ComponentInfo{com.whatsapp/.Main}" drawable="whatsapp"/>
              <item component="ComponentInfo{com.whatsapp/com.whatsapp.Main}" drawable="whatsapp_dupe"/>
              <item component=":LAUNCHER_ACTION_APP_DRAWER" drawable="drawer"/>
              <item component="ComponentInfo{broken}" drawable="broken"/>
              <item component="ComponentInfo{com.example/x}" drawable=""/>
              <calendar component="ComponentInfo{com.google.android.calendar/com.android.calendar.AllInOneActivity}" prefix="calendar_"/>
            </resources>
        """.trimIndent() + "\n"

        /** Parses XML with the JDK's SAX parser and feeds start tags into a parser, as IconPacks does with XmlPullParser. */
        internal fun parse(xml: String): IconPackParser.Result {
            val p = IconPackParser()
            SAXParserFactory.newInstance().newSAXParser().parse(
                InputSource(StringReader(xml)),
                object : DefaultHandler() {
                    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                        val attrs = HashMap<String, String?>()
                        for (i in 0 until attributes.length) attrs[attributes.getQName(i)] = attributes.getValue(i)
                        p.element(qName, attrs)
                    }
                },
            )
            return p.result()
        }
    }
}
