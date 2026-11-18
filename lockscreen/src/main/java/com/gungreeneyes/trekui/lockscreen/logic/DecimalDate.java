package com.gungreeneyes.trekui.lockscreen.logic;

import java.util.Locale;

/**
 * The wake screen's decimal date: an original "how far through the year" reading shown under the clock.
 *
 * <p>Formula (TrekUI's own): {@code value = (year - 2000) * 1000 + 1000 * elapsed / yearLength}, where
 * {@code elapsed} is the time since 1 January 00:00 local time (whole days plus the fraction of today)
 * and {@code yearLength} is 365 or 366 days. So every year spans exactly 1000 units, a unit is about
 * 8.8 hours, the integer part names the year (26xxx = 2026) and the value only ever grows through a
 * year. It is shown with one decimal (a tenth is about 53 minutes), for example {@code 26878.0} at
 * noon on 17 November 2026.
 *
 * <p>Pure Java (no Android calls) so it is unit tested on the JVM.
 */
public final class DecimalDate {
    /** Units per year. */
    public static final int UNITS_PER_YEAR = 1000;
    /** The year whose 1 January reads 0.0. */
    public static final int EPOCH_YEAR = 2000;
    private static final int SECONDS_PER_DAY = 86_400;

    private DecimalDate() {}

    /** True for a Gregorian leap year. */
    public static boolean leap(int year) {
        return (year % 4 == 0 && year % 100 != 0) || year % 400 == 0;
    }

    /**
     * The decimal date.
     *
     * @param year          calendar year (local time)
     * @param dayOfYear     1-based day of the year (1 = 1 January); clamped into the year
     * @param secondOfDay   seconds since local midnight; clamped into 0..86399 (a leap second or a DST
     *                      jump never moves the value backwards within a day)
     */
    public static double value(int year, int dayOfYear, int secondOfDay) {
        int days = leap(year) ? 366 : 365;
        int day = Math.max(1, Math.min(days, dayOfYear));
        int sec = Math.max(0, Math.min(SECONDS_PER_DAY - 1, secondOfDay));
        double elapsedDays = (day - 1) + sec / (double) SECONDS_PER_DAY;
        return (year - EPOCH_YEAR) * (double) UNITS_PER_YEAR + UNITS_PER_YEAR * elapsedDays / days;
    }

    /**
     * {@link #value} truncated (never rounded up, so 31 December never shows the next year's number) to
     * one decimal, formatted with a dot regardless of locale, e.g. "26878.1".
     */
    public static String format(int year, int dayOfYear, int secondOfDay) {
        double v = value(year, dayOfYear, secondOfDay);
        double tenths = Math.floor(v * 10.0 + 1e-9) / 10.0;
        return String.format(Locale.ROOT, "%.1f", tenths);
    }
}
