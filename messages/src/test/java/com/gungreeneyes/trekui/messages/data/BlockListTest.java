package com.gungreeneyes.trekui.messages.data;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;

import org.junit.Test;

public class BlockListTest {
    @Test
    public void exactNumbersMatchAnyFormatting() {
        BlockList b = new BlockList(Arrays.asList("+1 555 010 2345"));
        assertThat(b.matches("(555) 010-2345")).isTrue();
        assertThat(b.matches("15550102345")).isTrue();
        assertThat(b.matches("5550102346")).isFalse();
    }

    @Test
    public void prefixRulesMatchStartsWithAndWithoutPlus() {
        BlockList b = new BlockList(Arrays.asList("+1900*"));
        assertThat(b.matches("+19005551234")).isTrue();
        assertThat(b.matches("19005551234")).isTrue();
        assertThat(b.matches("+18005551234")).isFalse();
    }

    @Test
    public void alphanumericSenders() {
        BlockList b = new BlockList(Arrays.asList("PROMO*", "spam@example.com"));
        assertThat(b.matches("PromoDeals")).isTrue();
        assertThat(b.matches("SPAM@example.com")).isTrue();
        assertThat(b.matches("BANK")).isFalse();
    }

    @Test
    public void emptyAndInvalidEntries() {
        BlockList b = new BlockList(Arrays.asList("", "*", null, "  "));
        assertThat(b.size()).isEqualTo(0);
        assertThat(b.matches("5550102345")).isFalse();
        assertThat(b.matches("")).isFalse();
        assertThat(BlockList.isPrefixRule("+44*")).isTrue();
        assertThat(BlockList.isPrefixRule("*")).isFalse();
        assertThat(BlockList.isPrefixRule("+44")).isFalse();
    }
}
