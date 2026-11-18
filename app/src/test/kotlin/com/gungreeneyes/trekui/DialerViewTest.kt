package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [DialerView] number and T9 matching (pure JVM). */
class DialerViewTest {

    private fun contact(name: String, vararg numbers: String): DialerView.Contact {
        val c = DialerView.Contact()
        c.name = name
        c.lower = name.lowercase()
        for (n in numbers) c.numbers.add(arrayOf(n, "MOBILE"))
        return c
    }

    @Test
    fun t9Digit() {
        assertThat(DialerView.t9Digit('a')).isEqualTo('2')
        assertThat(DialerView.t9Digit('S')).isEqualTo('7')
        assertThat(DialerView.t9Digit('z')).isEqualTo('9')
        assertThat(DialerView.t9Digit('5')).isEqualTo('5')
        assertThat(DialerView.t9Digit('-')).isEqualTo('1')
    }

    @Test
    fun t9Match_byNumberFragment() {
        val c = contact("Jean-Luc Marchand", "+1 (208) 555-0147")
        assertThat(DialerView.t9Match(c, "5550", "5550")!![0]).isEqualTo("+1 (208) 555-0147")
        assertThat(DialerView.t9Match(c, "55", "55")).isNull() // too short for numbers, no name word on 55
    }

    @Test
    fun t9Match_byNameWord() {
        val c = contact("Jean-Luc Marchand", "2085550147")
        assertThat(DialerView.t9Match(c, "627", "627")).isNotNull() // MAR
        assertThat(DialerView.t9Match(c, "58", "58")).isNotNull() // LU(c)
        assertThat(DialerView.t9Match(c, "74*", "74")).isNull() // symbols switch off name matching
    }

    @Test
    fun t9Match_contactWithoutNumbers() {
        assertThat(DialerView.t9Match(contact("Dana"), "326", "326")).isNull()
    }

    @Test
    fun cleaning() {
        assertThat(DialerView.digitsOnly("+1 (208) 555-0147")).isEqualTo("12085550147")
        assertThat(DialerView.digitsOnly(null)).isEmpty()
        assertThat(DialerView.cleanDialString("tel: +1 208 555 0147 ext *9#")).isEqualTo("+12085550147*9#")
        val longNumber = StringBuilder()
        repeat(60) { longNumber.append('1') }
        assertThat(DialerView.cleanDialString(longNumber.toString())).hasLength(DialerView.MAX_DIGITS)
    }

    @Test
    fun contactMatches() {
        val c = contact("Beverly Crane", "2085550199")
        assertThat(DialerView.contactMatches(c, "")).isTrue()
        assertThat(DialerView.contactMatches(c, "CRAN")).isTrue()
        assertThat(DialerView.contactMatches(c, "555")).isTrue()
        assertThat(DialerView.contactMatches(c, "55")).isFalse()
        assertThat(DialerView.contactMatches(c, "walker")).isFalse()
    }

    @Test
    fun fallbackEmergencyNumbers() {
        assertThat(DialerView.isFallbackEmergency("911")).isTrue()
        assertThat(DialerView.isFallbackEmergency("112")).isTrue()
        assertThat(DialerView.isFallbackEmergency("9110")).isFalse()
    }
}
