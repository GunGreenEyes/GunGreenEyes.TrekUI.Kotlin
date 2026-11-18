package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** [CalendarPanel] day labels (pure JVM). */
class CalendarPanelTest {

    @Test
    fun relativeDay() {
        assertThat(CalendarPanel.relativeDay(0)).isEqualTo("TODAY")
        assertThat(CalendarPanel.relativeDay(1)).isEqualTo("TOMORROW")
        assertThat(CalendarPanel.relativeDay(2)).isNull()
    }

    @Test
    fun dayDiff_countsCalendarDaysNotHours() {
        val today = Calendar.getInstance()
        today.set(2026, Calendar.NOVEMBER, 17, 23, 30)
        val early = Calendar.getInstance()
        early.set(2026, Calendar.NOVEMBER, 18, 0, 15)
        assertThat(CalendarPanel.dayDiff(today, early, false)).isEqualTo(1)
        val later = Calendar.getInstance()
        later.set(2026, Calendar.DECEMBER, 1, 9, 0)
        assertThat(CalendarPanel.dayDiff(today, later, false)).isEqualTo(14)
    }

    @Test
    fun dayDiff_allDayUsesUtcDates() {
        val today = Calendar.getInstance()
        today.set(2026, Calendar.NOVEMBER, 17, 8, 0)
        val event = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        event.clear()
        event.set(2026, Calendar.NOVEMBER, 17)
        assertThat(CalendarPanel.dayDiff(today, event, true)).isEqualTo(0)
    }
}
