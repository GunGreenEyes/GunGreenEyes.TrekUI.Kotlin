package com.gungreeneyes.trekui.lockscreen.logic

import com.google.common.truth.Truth.assertThat
import com.gungreeneyes.trekui.lockscreen.logic.EnableRule.State
import org.junit.Test

/** Resolution between the launcher's SUITE LOCK SCREEN switch, the app's own switch and access. */
class EnableRuleTest {
    @Test
    fun launcherAbsentFollowsOwnSwitch() {
        assertThat(EnableRule.resolve(null, true, true)).isEqualTo(State.ACTIVE)
        assertThat(EnableRule.resolve(null, false, true)).isEqualTo(State.OFF_IN_APP)
    }

    @Test
    fun launcherOffAlwaysWins() {
        assertThat(EnableRule.resolve(false, true, true)).isEqualTo(State.OFF_IN_LAUNCHER)
        assertThat(EnableRule.resolve(false, false, false)).isEqualTo(State.OFF_IN_LAUNCHER)
        assertThat(EnableRule.enabled(false, true, true)).isFalse()
    }

    @Test
    fun launcherOnStillNeedsOwnSwitch() {
        assertThat(EnableRule.resolve(true, true, true)).isEqualTo(State.ACTIVE)
        assertThat(EnableRule.resolve(true, false, true)).isEqualTo(State.OFF_IN_APP)
    }

    @Test
    fun missingAccessBlocksAnOtherwiseEnabledScreen() {
        assertThat(EnableRule.resolve(true, true, false)).isEqualTo(State.NEEDS_ACCESS)
        assertThat(EnableRule.resolve(null, true, false)).isEqualTo(State.NEEDS_ACCESS)
        assertThat(EnableRule.enabled(null, true, false)).isFalse()
        assertThat(EnableRule.enabled(true, true, true)).isTrue()
    }
}
