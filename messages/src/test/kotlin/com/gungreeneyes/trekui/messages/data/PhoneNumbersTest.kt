package com.gungreeneyes.trekui.messages.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhoneNumbersTest {
    @Test
    fun normalizeStripsFormattingAndSuffixes() {
        assertThat(PhoneNumbers.normalize(" +1 (555) 010-2345 ")).isEqualTo("+15550102345")
        assertThat(PhoneNumbers.normalize("+15550102345/TYPE=PLMN")).isEqualTo("+15550102345")
        assertThat(PhoneNumbers.normalize("tel:555.010.2345")).isEqualTo("5550102345")
        assertThat(PhoneNumbers.normalize("0044 20 7946 0000")).isEqualTo("+442079460000")
    }

    @Test
    fun normalizeKeepsEmailAndAlphanumericSenders() {
        assertThat(PhoneNumbers.normalize("Bob@Example.COM")).isEqualTo("bob@example.com")
        assertThat(PhoneNumbers.normalize("MyBank")).isEqualTo("MYBANK")
        assertThat(PhoneNumbers.normalize(null)).isEmpty()
        assertThat(PhoneNumbers.normalize("   ")).isEmpty()
    }

    @Test
    fun keyMatchesNationalAndInternationalForms() {
        assertThat(PhoneNumbers.same("+1 555 010 2345", "(555) 010-2345")).isTrue()
        assertThat(PhoneNumbers.same("15550102345", "5550102345")).isTrue()
        assertThat(PhoneNumbers.same("5550102345", "5550102346")).isFalse()
        assertThat(PhoneNumbers.same("", "")).isFalse()
    }

    @Test
    fun shortCodesCompareWhole() {
        assertThat(PhoneNumbers.key("12345")).isEqualTo("12345")
        assertThat(PhoneNumbers.same("12345", "2345")).isFalse()
    }

    @Test
    fun splitRecipientsDeduplicatesInOrder() {
        assertThat(PhoneNumbers.splitRecipients("555 010 2345; +15550102345, 555-999-0000"))
            .containsExactly("5550102345", "5559990000").inOrder()
        assertThat(PhoneNumbers.splitRecipients(null)).isEmpty()
    }

    @Test
    fun schemePartDropsQueryAndSlashes() {
        assertThat(PhoneNumbers.fromSchemePart("//5550102345,5559990000?body=hi"))
            .containsExactly("5550102345", "5559990000").inOrder()
    }

    @Test
    fun schemeQueryValueReadsOpaqueUris() {
        assertThat(PhoneNumbers.schemeQueryValue("5550102345?body=hi%20there", "body")).isEqualTo("hi%20there")
        assertThat(PhoneNumbers.schemeQueryValue("//5550102345?x=1&BODY=yo", "body")).isEqualTo("yo")
        assertThat(PhoneNumbers.schemeQueryValue("5550102345?body=", "body")).isEmpty()
        assertThat(PhoneNumbers.schemeQueryValue("5550102345", "body")).isNull()
        assertThat(PhoneNumbers.schemeQueryValue(null, "body")).isNull()
    }

    @Test
    fun normalizePrefixTurnsShortZeroZeroIntoPlus() {
        assertThat(PhoneNumbers.normalizePrefix("0044")).isEqualTo("+44")
        assertThat(PhoneNumbers.normalizePrefix("00 1")).isEqualTo("+1")
        assertThat(PhoneNumbers.normalizePrefix("+1900")).isEqualTo("+1900")
        assertThat(PhoneNumbers.normalizePrefix("00")).isEqualTo("00")
        assertThat(PhoneNumbers.normalizePrefix("promo")).isEqualTo("PROMO")
    }

    @Test
    fun mmsParticipantsIncludeToAndCcButNotThisDevice() {
        val own = listOf("+1 555 000 1111")
        val to = listOf("5550001111", "555-999-0000")
        val cc = listOf("5558887777")
        assertThat(PhoneNumbers.mmsParticipants("+15550102345", to, cc, own))
            .containsExactly("+15550102345", "5559990000", "5558887777").inOrder()
    }

    @Test
    fun mmsParticipantsKeepOneToOneMessagesOneToOne() {
        val me = listOf("5550001111")
        val none = emptyList<String>()
        // own number known
        assertThat(PhoneNumbers.mmsParticipants("5550102345", me, none, me)).containsExactly("5550102345")
        // own number unknown: the lone other recipient is this device
        assertThat(PhoneNumbers.mmsParticipants("5550102345", me, none, none)).containsExactly("5550102345")
        // the placeholder and the sender repeated are ignored
        val placeholder = listOf("insert-address-token", "15550102345")
        assertThat(PhoneNumbers.mmsParticipants("5550102345", placeholder, none, none)).containsExactly("5550102345")
        assertThat(PhoneNumbers.mmsParticipants(null, me, none, none)).isEmpty()
    }

    @Test
    fun setKeyIgnoresOrderAndFormatting() {
        assertThat(PhoneNumbers.setKey(listOf("+1 555 010 2345", "5559990000")))
            .isEqualTo(PhoneNumbers.setKey(listOf("555-999-0000", "5550102345")))
    }

    @Test
    fun initials() {
        assertThat(PhoneNumbers.initials("ada lovelace")).isEqualTo("AL")
        assertThat(PhoneNumbers.initials("5550102345")).isEqualTo("45")
        assertThat(PhoneNumbers.initials("")).isEqualTo("#")
    }
}
