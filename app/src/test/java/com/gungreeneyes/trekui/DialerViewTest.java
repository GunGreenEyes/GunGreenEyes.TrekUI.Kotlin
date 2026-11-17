package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** {@link DialerView} number and T9 matching (pure JVM). */
public class DialerViewTest {

    private static DialerView.Contact contact(String name, String... numbers) {
        DialerView.Contact c = new DialerView.Contact();
        c.name = name;
        c.lower = name.toLowerCase();
        for (String n : numbers) c.numbers.add(new String[]{n, "MOBILE"});
        return c;
    }

    @Test
    public void t9Digit() {
        assertThat(DialerView.t9Digit('a')).isEqualTo('2');
        assertThat(DialerView.t9Digit('S')).isEqualTo('7');
        assertThat(DialerView.t9Digit('z')).isEqualTo('9');
        assertThat(DialerView.t9Digit('5')).isEqualTo('5');
        assertThat(DialerView.t9Digit('-')).isEqualTo('1');
    }

    @Test
    public void t9Match_byNumberFragment() {
        DialerView.Contact c = contact("Jean-Luc Marchand", "+1 (208) 555-0147");
        assertThat(DialerView.t9Match(c, "5550", "5550")[0]).isEqualTo("+1 (208) 555-0147");
        assertThat(DialerView.t9Match(c, "55", "55")).isNull(); // too short for numbers, no name word on 55
    }

    @Test
    public void t9Match_byNameWord() {
        DialerView.Contact c = contact("Jean-Luc Marchand", "2085550147");
        assertThat(DialerView.t9Match(c, "627", "627")).isNotNull();   // MAR
        assertThat(DialerView.t9Match(c, "58", "58")).isNotNull();     // LU(c)
        assertThat(DialerView.t9Match(c, "74*", "74")).isNull();       // symbols switch off name matching
    }

    @Test
    public void t9Match_contactWithoutNumbers() {
        assertThat(DialerView.t9Match(contact("Dana"), "326", "326")).isNull();
    }

    @Test
    public void cleaning() {
        assertThat(DialerView.digitsOnly("+1 (208) 555-0147")).isEqualTo("12085550147");
        assertThat(DialerView.digitsOnly(null)).isEmpty();
        assertThat(DialerView.cleanDialString("tel: +1 208 555 0147 ext *9#")).isEqualTo("+12085550147*9#");
        StringBuilder longNumber = new StringBuilder();
        for (int i = 0; i < 60; i++) longNumber.append('1');
        assertThat(DialerView.cleanDialString(longNumber.toString())).hasLength(DialerView.MAX_DIGITS);
    }

    @Test
    public void contactMatches() {
        DialerView.Contact c = contact("Beverly Crane", "2085550199");
        assertThat(DialerView.contactMatches(c, "")).isTrue();
        assertThat(DialerView.contactMatches(c, "CRAN")).isTrue();
        assertThat(DialerView.contactMatches(c, "555")).isTrue();
        assertThat(DialerView.contactMatches(c, "55")).isFalse();
        assertThat(DialerView.contactMatches(c, "walker")).isFalse();
    }

    @Test
    public void fallbackEmergencyNumbers() {
        assertThat(DialerView.isFallbackEmergency("911")).isTrue();
        assertThat(DialerView.isFallbackEmergency("112")).isTrue();
        assertThat(DialerView.isFallbackEmergency("9110")).isFalse();
    }
}
