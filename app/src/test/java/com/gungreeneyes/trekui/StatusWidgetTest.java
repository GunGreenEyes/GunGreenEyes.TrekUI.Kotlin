package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** {@link StatusWidget} sizing rules and battery text (pure JVM). */
public class StatusWidgetTest {

    @Test
    public void detailsOnlyWhenTallEnough() {
        assertThat(StatusWidget.showDetails(40f)).isFalse();
        assertThat(StatusWidget.showDetails(StatusWidget.COMPACT_HEIGHT_DP)).isTrue();
        assertThat(StatusWidget.showDetails(200f)).isTrue();
    }

    @Test
    public void clockShrinksOnSmallWidgets() {
        assertThat(StatusWidget.clockSp(300f, 120f)).isEqualTo(40f);
        assertThat(StatusWidget.clockSp(120f, 120f)).isEqualTo(28f);
        assertThat(StatusWidget.clockSp(300f, 40f)).isEqualTo(26f);
    }

    @Test
    public void batteryText() {
        assertThat(StatusWidget.batteryText(73)).isEqualTo("PWR 73%");
        assertThat(StatusWidget.batteryText(-1)).isEqualTo("PWR --%");
    }
}
