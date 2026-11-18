package com.gungreeneyes.trekui.lockscreen.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The screen-on trigger's conditions and debounce. */
class WakeTriggerTest {
    @Test
    fun showsOnALockedScreenOn() {
        val t = WakeTrigger()
        assertThat(t.onScreenOn(10_000, true, true, false, false)).isTrue()
    }

    @Test
    fun neverCoversAnUnlockedPhoneACallOrItself() {
        val t = WakeTrigger()
        assertThat(t.onScreenOn(10_000, false, true, false, false)).isFalse()
        assertThat(t.onScreenOn(20_000, true, false, false, false)).isFalse()
        assertThat(t.onScreenOn(30_000, true, true, true, false)).isFalse()
        assertThat(t.onScreenOn(40_000, true, true, false, true)).isFalse()
        // refused attempts do not start the debounce window
        assertThat(t.onScreenOn(40_001, true, true, false, false)).isTrue()
    }

    @Test
    fun foldsScreenOnsInsideTheWindow() {
        val t = WakeTrigger()
        assertThat(t.onScreenOn(10_000, true, true, false, false)).isTrue()
        assertThat(t.onScreenOn(10_000 + WakeTrigger.MIN_INTERVAL_MS - 1, true, true, false, false)).isFalse()
        assertThat(t.onScreenOn(10_000 + WakeTrigger.MIN_INTERVAL_MS, true, true, false, false)).isTrue()
    }

    @Test
    fun resetLetsTheNextOneThrough() {
        val t = WakeTrigger()
        assertThat(t.onScreenOn(10_000, true, true, false, false)).isTrue()
        t.reset()
        assertThat(t.onScreenOn(10_100, true, true, false, false)).isTrue()
    }

    @Test
    fun aClockThatJumpedBackwardsDoesNotBlockForever() {
        val t = WakeTrigger()
        assertThat(t.onScreenOn(50_000, true, true, false, false)).isTrue()
        assertThat(t.onScreenOn(1_000, true, true, false, false)).isTrue()
    }
}
