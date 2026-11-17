package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import org.junit.Test;

/** {@link IconPackParser}: appfilter.xml parsing, driven from the JDK's SAX parser like the app drives it from XmlPullParser. */
public class IconPackParserTest {

    private static final String APPFILTER = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<resources>\n"
            + "  <iconback img1=\"iconback_a\" img2=\"iconback_b\" img3=\"iconback_c\"/>\n"
            + "  <iconmask img1=\"iconmask\"/>\n"
            + "  <iconupon img1=\"iconupon\"/>\n"
            + "  <scale factor=\"0.75\"/>\n"
            + "  <!-- Phone -->\n"
            + "  <item component=\"ComponentInfo{com.google.android.dialer/com.google.android.dialer.extensions.GoogleDialtactsActivity}\" drawable=\"phone\"/>\n"
            + "  <item component=\"ComponentInfo{com.android.chrome/com.google.android.apps.chrome.Main}\" drawable=\"chrome\"/>\n"
            + "  <item component=\"ComponentInfo{com.android.chrome/org.chromium.chrome.browser.ChromeTabbedActivity}\" drawable=\"chrome_alt\"/>\n"
            + "  <item component=\"ComponentInfo{com.whatsapp/.Main}\" drawable=\"whatsapp\"/>\n"
            + "  <item component=\"ComponentInfo{com.whatsapp/com.whatsapp.Main}\" drawable=\"whatsapp_dupe\"/>\n"
            + "  <item component=\":LAUNCHER_ACTION_APP_DRAWER\" drawable=\"drawer\"/>\n"
            + "  <item component=\"ComponentInfo{broken}\" drawable=\"broken\"/>\n"
            + "  <item component=\"ComponentInfo{com.example/x}\" drawable=\"\"/>\n"
            + "  <calendar component=\"ComponentInfo{com.google.android.calendar/com.android.calendar.AllInOneActivity}\" prefix=\"calendar_\"/>\n"
            + "</resources>\n";

    /** Parses XML with the JDK's SAX parser and feeds start tags into a parser, as IconPacks does with XmlPullParser. */
    static IconPackParser.Result parse(String xml) throws Exception {
        final IconPackParser p = new IconPackParser();
        SAXParserFactory.newInstance().newSAXParser().parse(new InputSource(new StringReader(xml)), new DefaultHandler() {
            @Override public void startElement(String uri, String local, String qName, Attributes a) {
                Map<String, String> attrs = new HashMap<>();
                for (int i = 0; i < a.getLength(); i++) attrs.put(a.getQName(i), a.getValue(i));
                p.element(qName, attrs);
            }
        });
        return p.result();
    }

    @Test
    public void exactComponentMatches() throws Exception {
        IconPackParser.Result r = parse(APPFILTER);
        assertThat(r.drawableFor("com.google.android.dialer", "com.google.android.dialer.extensions.GoogleDialtactsActivity")).isEqualTo("phone");
        assertThat(r.drawableFor("com.android.chrome", "org.chromium.chrome.browser.ChromeTabbedActivity")).isEqualTo("chrome_alt");
    }

    @Test
    public void unknownActivityFallsBackToFirstDrawableOfPackage() throws Exception {
        IconPackParser.Result r = parse(APPFILTER);
        assertThat(r.drawableFor("com.android.chrome", "com.example.Renamed")).isEqualTo("chrome");
        assertThat(r.drawableFor("com.android.chrome", null)).isEqualTo("chrome");
        assertThat(r.drawableFor("com.unknown", "com.unknown.Main")).isNull();
        assertThat(r.drawableFor(null, "x")).isNull();
    }

    @Test
    public void relativeClassNamesExpandAndFirstEntryWins() throws Exception {
        IconPackParser.Result r = parse(APPFILTER);
        assertThat(r.drawableFor("com.whatsapp", "com.whatsapp.Main")).isEqualTo("whatsapp");
    }

    @Test
    public void malformedAndPseudoComponentsAreSkippedButDrawablesKept() throws Exception {
        IconPackParser.Result r = parse(APPFILTER);
        assertThat(r.byComponent).doesNotContainKey(":LAUNCHER_ACTION_APP_DRAWER");
        assertThat(r.byPackage).doesNotContainKey("broken");
        assertThat(r.drawables).contains("drawer");
        assertThat(r.drawables).doesNotContain("");
        assertThat(r.size()).isEqualTo(4);
    }

    @Test
    public void unthemedAppRecipe() throws Exception {
        IconPackParser.Result r = parse(APPFILTER);
        assertThat(r.backs).containsExactly("iconback_a", "iconback_b", "iconback_c").inOrder();
        assertThat(r.mask).isEqualTo("iconmask");
        assertThat(r.upon).isEqualTo("iconupon");
        assertThat(r.scale).isWithin(1e-6f).of(0.75f);
        assertThat(r.themesUnknownApps()).isTrue();
        String back = r.backFor("com.example.app");
        assertThat(r.backs).contains(back);
        assertThat(r.backFor("com.example.app")).isEqualTo(back); // stable per package
    }

    @Test
    public void calendarPrefixPlusDay() throws Exception {
        IconPackParser.Result r = parse(APPFILTER);
        assertThat(r.calendarFor("com.google.android.calendar", "com.android.calendar.AllInOneActivity", 17)).isEqualTo("calendar_17");
        assertThat(r.calendarFor("com.android.chrome", "com.google.android.apps.chrome.Main", 3)).isNull();
    }

    @Test
    public void packWithoutRecipeLeavesUnknownAppsAlone() throws Exception {
        IconPackParser.Result r = parse("<resources><item component=\"ComponentInfo{a.b/a.b.C}\" drawable=\"abc\"/></resources>");
        assertThat(r.themesUnknownApps()).isFalse();
        assertThat(r.backFor("x")).isNull();
        assertThat(r.scale).isEqualTo(1f);
    }

    @Test
    public void outOfRangeOrBadScaleIgnored() throws Exception {
        assertThat(parse("<resources><scale factor=\"7\"/></resources>").scale).isEqualTo(1f);
        assertThat(parse("<resources><scale factor=\"abc\"/></resources>").scale).isEqualTo(1f);
    }

    @Test
    public void iconbackWithGapsAndImg0() throws Exception {
        IconPackParser.Result r = parse("<resources><iconback img0=\"z\" img2=\"b\" img5=\"e\"/></resources>");
        assertThat(r.backs).containsExactly("z", "b", "e").inOrder();
    }

    @Test
    public void component_normalisation() {
        assertThat(IconPackParser.component("ComponentInfo{com.a/com.a.Main}")).isEqualTo("com.a/com.a.Main");
        assertThat(IconPackParser.component(" ComponentInfo{com.a/.Main} ")).isEqualTo("com.a/com.a.Main");
        assertThat(IconPackParser.component("com.a/com.a.Main")).isEqualTo("com.a/com.a.Main");
        assertThat(IconPackParser.component("ComponentInfo{com.a}")).isNull();
        assertThat(IconPackParser.component("ComponentInfo{/x}")).isNull();
        assertThat(IconPackParser.component("ComponentInfo{com.a/}")).isNull();
        assertThat(IconPackParser.component(null)).isNull();
    }

    @Test
    public void clean_stripsDrawablePrefixAndBlanks() {
        assertThat(IconPackParser.clean("@drawable/foo")).isEqualTo("foo");
        assertThat(IconPackParser.clean("  bar ")).isEqualTo("bar");
        assertThat(IconPackParser.clean("   ")).isNull();
    }

    @Test
    public void drawableCatalogueAddsIconsForThePicker() throws Exception {
        IconPackParser p = new IconPackParser();
        p.catalogueItem("extra_one");
        p.catalogueItem("");
        p.catalogueItem("extra_one");
        assertThat(p.result().drawableList()).containsExactly("extra_one");
    }
}
