package com.gungreeneyes.trekui.lockscreen.logic

import java.util.Locale
import kotlin.math.floor

/**
 * The wake screen's decimal date: an original "how far through the year" reading shown under the clock.
 *
 * Formula (TrekUI's own): `value = (year - 2000) * 1000 + 1000 * elapsed / yearLength`, where `elapsed` is
 * the time since 1 January 00:00 local time (whole days plus the fraction of today) and `yearLength` is
 * 365 or 366 days. So every year spans exactly 1000 units, a unit is about 8.8 hours, the integer part
 * names the year (26xxx = 2026) and the value only ever grows through a year. It is shown with one decimal
 * (a tenth is about 53 minutes), for example `26878.0` at noon on 17 November 2026.
 *
 * Pure Kotlin (no Android calls) so it is unit tested on the JVM.
 */
object DecimalDate {
    /** Units per year. */
    const val UNITS_PER_YEAR = 1000

    /** The year whose 1 January reads 0.0. */
    const val EPOCH_YEAR = 2000
    private const val SECONDS_PER_DAY = 86_400

    /** True for a Gregorian leap year. */
    fun leap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    /**
     * The decimal date.
     *
     * @param year calendar year (local time)
     * @param dayOfYear 1-based day of the year (1 = 1 January); clamped into the year
     * @param secondOfDay seconds since local midnight; clamped into 0..86399 (a leap second or a DST jump
     *   never moves the value backwards within a day)
     */
    fun value(year: Int, dayOfYear: Int, secondOfDay: Int): Double {
        val days = if (leap(year)) 366 else 365
        val day = dayOfYear.coerceIn(1, days)
        val sec = secondOfDay.coerceIn(0, SECONDS_PER_DAY - 1)
        val elapsedDays = (day - 1) + sec / SECONDS_PER_DAY.toDouble()
        return (year - EPOCH_YEAR) * UNITS_PER_YEAR.toDouble() + UNITS_PER_YEAR * elapsedDays / days
    }

    /**
     * [value] truncated (never rounded up, so 31 December never shows the next year's number) to one
     * decimal, formatted with a dot regardless of locale, e.g. "26878.1".
     */
    fun format(year: Int, dayOfYear: Int, secondOfDay: Int): String {
        val v = value(year, dayOfYear, secondOfDay)
        val tenths = floor(v * 10.0 + 1e-9) / 10.0
        return "%.1f".format(Locale.ROOT, tenths)
    }
}
