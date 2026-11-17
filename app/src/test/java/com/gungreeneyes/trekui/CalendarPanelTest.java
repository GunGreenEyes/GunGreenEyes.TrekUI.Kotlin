package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/** {@link CalendarPanel} day labels (pure JVM). */
public class CalendarPanelTest {

    @Test
    public void relativeDay() {
        assertThat(CalendarPanel.relativeDay(0)).isEqualTo("TODAY");
        assertThat(CalendarPanel.relativeDay(1)).isEqualTo("TOMORROW");
        assertThat(CalendarPanel.relativeDay(2)).isNull();
    }

    @Test
    public void dayDiff_countsCalendarDaysNotHours() {
        Calendar today = Calendar.getInstance();
        today.set(2026, Calendar.NOVEMBER, 17, 23, 30);
        Calendar early = Calendar.getInstance();
        early.set(2026, Calendar.NOVEMBER, 18, 0, 15);
        assertThat(CalendarPanel.dayDiff(today, early, false)).isEqualTo(1);
        Calendar later = Calendar.getInstance();
        later.set(2026, Calendar.DECEMBER, 1, 9, 0);
        assertThat(CalendarPanel.dayDiff(today, later, false)).isEqualTo(14);
    }

    @Test
    public void dayDiff_allDayUsesUtcDates() {
        Calendar today = Calendar.getInstance();
        today.set(2026, Calendar.NOVEMBER, 17, 8, 0);
        Calendar event = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        event.clear();
        event.set(2026, Calendar.NOVEMBER, 17);
        assertThat(CalendarPanel.dayDiff(today, event, true)).isEqualTo(0);
    }
}
