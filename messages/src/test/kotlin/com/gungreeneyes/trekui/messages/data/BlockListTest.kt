package com.gungreeneyes.trekui.messages.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockListTest {
    @Test
    fun exactNumbersMatchAnyFormatting() {
        val b = BlockList(listOf("+1 555 010 2345"))
        assertThat(b.matches("(555) 010-2345")).isTrue()
        assertThat(b.matches("15550102345")).isTrue()
        assertThat(b.matches("5550102346")).isFalse()
    }

    @Test
    fun prefixRulesMatchStartsWithAndWithoutPlus() {
        val b = BlockList(listOf("+1900*"))
        assertThat(b.matches("+19005551234")).isTrue()
        assertThat(b.matches("19005551234")).isTrue()
        assertThat(b.matches("+18005551234")).isFalse()
    }

    @Test
    fun alphanumericSenders() {
        val b = BlockList(listOf("PROMO*", "spam@example.com"))
        assertThat(b.matches("PromoDeals")).isTrue()
        assertThat(b.matches("SPAM@example.com")).isTrue()
        assertThat(b.matches("BANK")).isFalse()
    }

    @Test
    fun emptyAndInvalidEntries() {
        val b = BlockList(listOf("", "*", null, "  "))
        assertThat(b.size()).isEqualTo(0)
        assertThat(b.matches("5550102345")).isFalse()
        assertThat(b.matches("")).isFalse()
        assertThat(BlockList.isPrefixRule("+44*")).isTrue()
        assertThat(BlockList.isPrefixRule("*")).isFalse()
        assertThat(BlockList.isPrefixRule("+44")).isFalse()
    }

    /** TUK-T14: Android 6.0 has no system block list, so numbers are kept in the app's own rules there. */
    @Test
    fun withoutASystemListEveryEntryIsKeptLocally() {
        assertThat(BlockList.storedLocally("+1 555 010 2345", systemList = false)).isTrue()
        assertThat(BlockList.storedLocally("+1900*", systemList = false)).isTrue()
        assertThat(BlockList.storedLocally("+1 555 010 2345", systemList = true)).isFalse()
        assertThat(BlockList.storedLocally("+1900*", systemList = true)).isTrue()
    }

    @Test
    fun localFormMatchesLikeTheSystemList() {
        val number = BlockList.localForm(" +1 (555) 010-2345 ")
        assertThat(number).isEqualTo(PhoneNumbers.normalize("+1 (555) 010-2345"))
        assertThat(BlockList.localForm(" +1900* ")).isEqualTo("+1900*")
        val b = BlockList(listOf(number, BlockList.localForm("PROMO*")))
        assertThat(b.matches("(555) 010-2345")).isTrue()
        assertThat(b.matches("PromoDeals")).isTrue()
        assertThat(b.matches("5550102346")).isFalse()
    }
}
