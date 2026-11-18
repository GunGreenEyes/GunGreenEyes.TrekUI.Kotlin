package com.gungreeneyes.trekui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** JVM tests for the gesture classification thresholds and assignment encoding in [HomeGestures]. */
class HomeGesturesTest {

    // ---- two fingers ---------------------------------------------------------------------------

    @Test
    fun twoFinger_verticalMovesAreSwipes() {
        assertThat(HomeGestures.classifyTwoFinger(5f, -120f, 200f, 205f)).isEqualTo(HomeGestures.TWO_FINGER_UP)
        assertThat(HomeGestures.classifyTwoFinger(-10f, 140f, 180f, 170f)).isEqualTo(HomeGestures.TWO_FINGER_DOWN)
    }

    @Test
    fun twoFinger_swipeNeedsMinimumTravel() {
        val justShort = HomeGestures.TWO_FINGER_MIN_TRAVEL_DP - 1
        assertThat(HomeGestures.classifyTwoFinger(0f, -justShort, 200f, 200f)).isNull()
        assertThat(HomeGestures.classifyTwoFinger(0f, -HomeGestures.TWO_FINGER_MIN_TRAVEL_DP, 200f, 200f))
            .isEqualTo(HomeGestures.TWO_FINGER_UP)
    }

    @Test
    fun twoFinger_diagonalIsNothing() {
        assertThat(HomeGestures.classifyTwoFinger(100f, -100f, 200f, 200f)).isNull()
        assertThat(HomeGestures.classifyTwoFinger(150f, 10f, 200f, 200f)).isNull()
    }

    @Test
    fun pinch_spanChangeDecidesInOrOut() {
        assertThat(HomeGestures.classifyTwoFinger(2f, 3f, 300f, 120f)).isEqualTo(HomeGestures.PINCH_IN)
        assertThat(HomeGestures.classifyTwoFinger(-4f, 2f, 120f, 300f)).isEqualTo(HomeGestures.PINCH_OUT)
    }

    @Test
    fun pinch_needsMinimumChangeAndRatio() {
        // 50dp change: under the 60dp minimum
        assertThat(HomeGestures.classifyTwoFinger(0f, 0f, 200f, 150f)).isNull()
        // 70dp change on a wide start: ratio 0.82 is above the pinch-in ratio
        assertThat(HomeGestures.classifyTwoFinger(0f, 0f, 400f, 330f)).isNull()
        // exactly at the thresholds
        assertThat(HomeGestures.classifyTwoFinger(0f, 0f, 240f, 240 * HomeGestures.PINCH_IN_RATIO)).isEqualTo(HomeGestures.PINCH_IN)
    }

    @Test
    fun pinch_losesToALargerSwipe() {
        // fingers drifted apart while swiping down a long way: a swipe, not a pinch
        assertThat(HomeGestures.classifyTwoFinger(0f, 200f, 150f, 220f)).isEqualTo(HomeGestures.TWO_FINGER_DOWN)
    }

    // ---- one finger ----------------------------------------------------------------------------

    @Test
    fun swipe_horizontalFlingsInBothDirections() {
        assertThat(HomeGestures.classifySwipe(-120f, 10f, -800f)).isEqualTo(HomeGestures.SWIPE_LEFT)
        assertThat(HomeGestures.classifySwipe(130f, -20f, 900f)).isEqualTo(HomeGestures.SWIPE_RIGHT)
    }

    @Test
    fun swipe_rejectsShortSlowVerticalOrReversed() {
        assertThat(HomeGestures.classifySwipe(HomeGestures.SWIPE_MIN_TRAVEL_DP - 1, 0f, 900f)).isNull()
        assertThat(HomeGestures.classifySwipe(150f, 0f, HomeGestures.SWIPE_MIN_VELOCITY_DP - 1)).isNull()
        assertThat(HomeGestures.classifySwipe(100f, 80f, 900f)).isNull() // not 1.5x more horizontal
        assertThat(HomeGestures.classifySwipe(150f, 0f, -900f)).isNull() // flung back the other way
    }

    @Test
    fun swipe_verticalIsLeftToGestureRoot() {
        assertThat(HomeGestures.classifySwipe(0f, -300f, 0f)).isNull()
        assertThat(HomeGestures.classifySwipe(10f, 300f, 50f)).isNull()
    }

    // ---- taps ----------------------------------------------------------------------------------

    @Test
    fun tap_limitsDurationAndMovement() {
        assertThat(HomeGestures.isTap(120, 3f)).isTrue()
        assertThat(HomeGestures.isTap(HomeGestures.TAP_MAX_MS, HomeGestures.TAP_SLOP_DP)).isTrue()
        assertThat(HomeGestures.isTap(HomeGestures.TAP_MAX_MS + 1, 0f)).isFalse()
        assertThat(HomeGestures.isTap(50, HomeGestures.TAP_SLOP_DP + 1)).isFalse()
    }

    @Test
    fun doubleTap_needsASecondTapSoonAndNearby() {
        val taps = HomeGestures.TapTracker()
        assertThat(taps.isSecondTap(1000, 100f, 100f)).isFalse()
        taps.tapped(1000, 100f, 100f)
        assertThat(taps.isSecondTap(1000 + HomeGestures.DOUBLE_TAP_TIMEOUT_MS, 120f, 110f)).isTrue()
        assertThat(taps.isSecondTap(1000 + HomeGestures.DOUBLE_TAP_TIMEOUT_MS + 1, 100f, 100f)).isFalse()
        assertThat(taps.isSecondTap(1100, 100 + HomeGestures.DOUBLE_TAP_SLOP_DP + 1, 100f)).isFalse()
        assertThat(taps.isSecondTap(900, 100f, 100f)).isFalse() // earlier than the first tap
        taps.reset()
        assertThat(taps.isSecondTap(1100, 100f, 100f)).isFalse()
    }

    // ---- assignments ---------------------------------------------------------------------------

    @Test
    fun keysAndTwoFingerSet() {
        assertThat(HomeGestures.key(HomeGestures.PINCH_IN)).isEqualTo("gesture.pinchIn")
        assertThat(HomeGestures.twoFinger(HomeGestures.PINCH_OUT)).isTrue()
        assertThat(HomeGestures.twoFinger(HomeGestures.DOUBLE_TAP)).isFalse()
        assertThat(HomeGestures.ALL).asList().containsAtLeastElementsIn(HomeGestures.TWO_FINGER)
    }

    @Test
    fun decode_readsTilesAndIgnoresJunk() {
        val t = Tile()
        t.action = Tile.A_GROUP
        t.arg = "g3"
        t.label = "WORK"
        val back = HomeGestures.decode(t.toJson().toString())
        assertThat(back).isNotNull()
        assertThat(back!!.action).isEqualTo(Tile.A_GROUP)
        assertThat(back.arg).isEqualTo("g3")
        assertThat(HomeGestures.decode(null)).isNull()
        assertThat(HomeGestures.decode("garbage")).isNull()
        assertThat(HomeGestures.decode(Tile().toJson().toString())).isNull() // empty tile = unassigned
    }
}
