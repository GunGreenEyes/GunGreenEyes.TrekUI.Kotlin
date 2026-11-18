package com.gungreeneyes.trekui.lockscreen.logic;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** The wake screen's decimal date formula. */
public class DecimalDateTest {
    @Test
    public void firstMomentOfAYearIsAWholeThousand() {
        assertThat(DecimalDate.value(2026, 1, 0)).isEqualTo(26000.0);
        assertThat(DecimalDate.format(2026, 1, 0)).isEqualTo("26000.0");
        assertThat(DecimalDate.value(2000, 1, 0)).isEqualTo(0.0);
    }

    @Test
    public void midYearIsHalfway() {
        // 2 July 12:00 in a common year is exactly 182.5 days in: half of 365
        assertThat(DecimalDate.value(2026, 183, 12 * 3600)).isWithin(1e-9).of(26500.0);
    }

    @Test
    public void knownDayFormatsWithOneTruncatedDecimal() {
        // 17 Nov 2026 is day 321; at noon 320.5 / 365 * 1000 = 878.08...
        assertThat(DecimalDate.format(2026, 321, 12 * 3600)).isEqualTo("26878.0");
    }

    @Test
    public void lastSecondOfTheYearNeverRollsIntoTheNext() {
        assertThat(DecimalDate.format(2026, 365, 86_399)).isEqualTo("26999.9");
        assertThat(DecimalDate.value(2026, 365, 86_399)).isLessThan(27000.0);
    }

    @Test
    public void leapYearsSpreadOver366Days() {
        assertThat(DecimalDate.leap(2028)).isTrue();
        assertThat(DecimalDate.leap(2100)).isFalse();
        assertThat(DecimalDate.leap(2000)).isTrue();
        assertThat(DecimalDate.value(2028, 184, 0)).isWithin(1e-9).of(28000.0 + 1000.0 * 183 / 366);
        // day 366 exists in a leap year, and is clamped to 365 in a common year
        assertThat(DecimalDate.value(2028, 366, 0)).isGreaterThan(DecimalDate.value(2028, 365, 0));
        assertThat(DecimalDate.value(2026, 366, 0)).isEqualTo(DecimalDate.value(2026, 365, 0));
    }

    @Test
    public void valuesOnlyGrowThroughADay() {
        double prev = -1;
        for (int s = 0; s < 86_400; s += 997) {
            double v = DecimalDate.value(2026, 100, s);
            assertThat(v).isGreaterThan(prev);
            prev = v;
        }
    }

    @Test
    public void outOfRangeInputsAreClamped() {
        assertThat(DecimalDate.value(2026, 0, -5)).isEqualTo(26000.0);
        assertThat(DecimalDate.value(2026, 1, 100_000)).isLessThan(DecimalDate.value(2026, 2, 0));
    }
}
