package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [StatusWidget] sizing rules and battery text (pure JVM). */
class StatusWidgetTest {

    @Test
    fun detailsOnlyWhenTallEnough() {
        assertThat(StatusWidget.showDetails(40f)).isFalse()
        assertThat(StatusWidget.showDetails(StatusWidget.COMPACT_HEIGHT_DP)).isTrue()
        assertThat(StatusWidget.showDetails(200f)).isTrue()
    }

    @Test
    fun clockShrinksOnSmallWidgets() {
        assertThat(StatusWidget.clockSp(300f, 120f)).isEqualTo(40f)
        assertThat(StatusWidget.clockSp(120f, 120f)).isEqualTo(28f)
        assertThat(StatusWidget.clockSp(300f, 40f)).isEqualTo(26f)
    }

    @Test
    fun batteryText() {
        assertThat(StatusWidget.batteryText(73)).isEqualTo("PWR 73%")
        assertThat(StatusWidget.batteryText(-1)).isEqualTo("PWR --%")
    }
}
