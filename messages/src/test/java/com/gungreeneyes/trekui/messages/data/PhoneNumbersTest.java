package com.gungreeneyes.trekui.messages.data;

import static com.google.common.truth.Truth.assertThat;

import java.util.Arrays;

import org.junit.Test;

public class PhoneNumbersTest {
    @Test
    public void normalizeStripsFormattingAndSuffixes() {
        assertThat(PhoneNumbers.normalize(" +1 (555) 010-2345 ")).isEqualTo("+15550102345");
        assertThat(PhoneNumbers.normalize("+15550102345/TYPE=PLMN")).isEqualTo("+15550102345");
        assertThat(PhoneNumbers.normalize("tel:555.010.2345")).isEqualTo("5550102345");
        assertThat(PhoneNumbers.normalize("0044 20 7946 0000")).isEqualTo("+442079460000");
    }

    @Test
    public void normalizeKeepsEmailAndAlphanumericSenders() {
        assertThat(PhoneNumbers.normalize("Bob@Example.COM")).isEqualTo("bob@example.com");
        assertThat(PhoneNumbers.normalize("MyBank")).isEqualTo("MYBANK");
        assertThat(PhoneNumbers.normalize(null)).isEmpty();
        assertThat(PhoneNumbers.normalize("   ")).isEmpty();
    }

    @Test
    public void keyMatchesNationalAndInternationalForms() {
        assertThat(PhoneNumbers.same("+1 555 010 2345", "(555) 010-2345")).isTrue();
        assertThat(PhoneNumbers.same("15550102345", "5550102345")).isTrue();
        assertThat(PhoneNumbers.same("5550102345", "5550102346")).isFalse();
        assertThat(PhoneNumbers.same("", "")).isFalse();
    }

    @Test
    public void shortCodesCompareWhole() {
        assertThat(PhoneNumbers.key("12345")).isEqualTo("12345");
        assertThat(PhoneNumbers.same("12345", "2345")).isFalse();
    }

    @Test
    public void splitRecipientsDeduplicatesInOrder() {
        assertThat(PhoneNumbers.splitRecipients("555 010 2345; +15550102345, 555-999-0000"))
                .containsExactly("5550102345", "5559990000").inOrder();
        assertThat(PhoneNumbers.splitRecipients(null)).isEmpty();
    }

    @Test
    public void schemePartDropsQueryAndSlashes() {
        assertThat(PhoneNumbers.fromSchemePart("//5550102345,5559990000?body=hi"))
                .containsExactly("5550102345", "5559990000").inOrder();
    }

    @Test
    public void setKeyIgnoresOrderAndFormatting() {
        assertThat(PhoneNumbers.setKey(Arrays.asList("+1 555 010 2345", "5559990000")))
                .isEqualTo(PhoneNumbers.setKey(Arrays.asList("555-999-0000", "5550102345")));
    }

    @Test
    public void initials() {
        assertThat(PhoneNumbers.initials("ada lovelace")).isEqualTo("AL");
        assertThat(PhoneNumbers.initials("5550102345")).isEqualTo("45");
        assertThat(PhoneNumbers.initials("")).isEqualTo("#");
    }
}
