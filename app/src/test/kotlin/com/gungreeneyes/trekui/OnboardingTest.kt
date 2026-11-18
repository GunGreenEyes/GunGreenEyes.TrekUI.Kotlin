package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [Onboarding]: when the tour starts and how its stops step. */
class OnboardingTest {

    @Test
    fun firstRunWaitsForTheDefaultHomeWelcome() {
        // first resume: welcome not shown yet and not default home -> the welcome goes first
        assertThat(Onboarding.shouldStart(false, false, false, false)).isFalse()
        // already the default home on first run -> no welcome, the tour starts
        assertThat(Onboarding.shouldStart(false, false, false, true)).isTrue()
        // after the welcome (set as default or LATER) -> the tour starts
        assertThat(Onboarding.shouldStart(false, false, true, false)).isTrue()
        assertThat(Onboarding.shouldStart(false, false, true, true)).isTrue()
    }

    @Test
    fun shownOnceUnlessReplayed() {
        assertThat(Onboarding.shouldStart(true, false, true, true)).isFalse()
        assertThat(Onboarding.shouldStart(true, true, true, true)).isTrue()
        assertThat(Onboarding.shouldStart(true, true, false, false)).isTrue()
    }

    @Test
    fun stepping() {
        assertThat(Onboarding.next(Onboarding.STEP_WELCOME)).isEqualTo(Onboarding.STEP_ASSIGN)
        assertThat(Onboarding.next(Onboarding.STEP_BACKUP)).isEqualTo(Onboarding.STEP_HOME)
        assertThat(Onboarding.next(Onboarding.STEP_HOME)).isEqualTo(-1)
        assertThat(Onboarding.back(Onboarding.STEP_WELCOME)).isEqualTo(Onboarding.STEP_WELCOME)
        assertThat(Onboarding.back(Onboarding.STEP_SHADE)).isEqualTo(Onboarding.STEP_DRAWER)
        assertThat(Onboarding.STEP_HOME).isEqualTo(Onboarding.STEPS - 1)
        assertThat(Onboarding.counter(2)).isEqualTo("3 / 8")
    }

    @Test
    fun tourFlagsAreNeverArchived() {
        assertThat(LayoutArchive.isExcluded(Onboarding.KEY_SEEN)).isTrue()
        assertThat(LayoutArchive.isExcluded(Onboarding.KEY_REPLAY)).isTrue()
    }
}
