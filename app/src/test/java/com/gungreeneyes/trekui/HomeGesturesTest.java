package com.gungreeneyes.trekui;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/** JVM tests for the gesture classification thresholds and assignment encoding in {@link HomeGestures}. */
public class HomeGesturesTest {

    // ---- two fingers ---------------------------------------------------------------------------

    @Test
    public void twoFinger_verticalMovesAreSwipes() {
        assertThat(HomeGestures.classifyTwoFinger(5, -120, 200, 205)).isEqualTo(HomeGestures.TWO_FINGER_UP);
        assertThat(HomeGestures.classifyTwoFinger(-10, 140, 180, 170)).isEqualTo(HomeGestures.TWO_FINGER_DOWN);
    }

    @Test
    public void twoFinger_swipeNeedsMinimumTravel() {
        float justShort = HomeGestures.TWO_FINGER_MIN_TRAVEL_DP - 1;
        assertThat(HomeGestures.classifyTwoFinger(0, -justShort, 200, 200)).isNull();
        assertThat(HomeGestures.classifyTwoFinger(0, -HomeGestures.TWO_FINGER_MIN_TRAVEL_DP, 200, 200))
                .isEqualTo(HomeGestures.TWO_FINGER_UP);
    }

    @Test
    public void twoFinger_diagonalIsNothing() {
        assertThat(HomeGestures.classifyTwoFinger(100, -100, 200, 200)).isNull();
        assertThat(HomeGestures.classifyTwoFinger(150, 10, 200, 200)).isNull();
    }

    @Test
    public void pinch_spanChangeDecidesInOrOut() {
        assertThat(HomeGestures.classifyTwoFinger(2, 3, 300, 120)).isEqualTo(HomeGestures.PINCH_IN);
        assertThat(HomeGestures.classifyTwoFinger(-4, 2, 120, 300)).isEqualTo(HomeGestures.PINCH_OUT);
    }

    @Test
    public void pinch_needsMinimumChangeAndRatio() {
        // 50dp change: under the 60dp minimum
        assertThat(HomeGestures.classifyTwoFinger(0, 0, 200, 150)).isNull();
        // 70dp change on a wide start: ratio 0.82 is above the pinch-in ratio
        assertThat(HomeGestures.classifyTwoFinger(0, 0, 400, 330)).isNull();
        // exactly at the thresholds
        assertThat(HomeGestures.classifyTwoFinger(0, 0, 240, 240 * HomeGestures.PINCH_IN_RATIO)).isEqualTo(HomeGestures.PINCH_IN);
    }

    @Test
    public void pinch_losesToALargerSwipe() {
        // fingers drifted apart while swiping down a long way: a swipe, not a pinch
        assertThat(HomeGestures.classifyTwoFinger(0, 200, 150, 220)).isEqualTo(HomeGestures.TWO_FINGER_DOWN);
    }

    // ---- one finger ----------------------------------------------------------------------------

    @Test
    public void swipe_horizontalFlingsInBothDirections() {
        assertThat(HomeGestures.classifySwipe(-120, 10, -800)).isEqualTo(HomeGestures.SWIPE_LEFT);
        assertThat(HomeGestures.classifySwipe(130, -20, 900)).isEqualTo(HomeGestures.SWIPE_RIGHT);
    }

    @Test
    public void swipe_rejectsShortSlowVerticalOrReversed() {
        assertThat(HomeGestures.classifySwipe(HomeGestures.SWIPE_MIN_TRAVEL_DP - 1, 0, 900)).isNull();
        assertThat(HomeGestures.classifySwipe(150, 0, HomeGestures.SWIPE_MIN_VELOCITY_DP - 1)).isNull();
        assertThat(HomeGestures.classifySwipe(100, 80, 900)).isNull(); // not 1.5x more horizontal
        assertThat(HomeGestures.classifySwipe(150, 0, -900)).isNull(); // flung back the other way
    }

    @Test
    public void swipe_verticalIsLeftToGestureRoot() {
        assertThat(HomeGestures.classifySwipe(0, -300, 0)).isNull();
        assertThat(HomeGestures.classifySwipe(10, 300, 50)).isNull();
    }

    // ---- taps ----------------------------------------------------------------------------------

    @Test
    public void tap_limitsDurationAndMovement() {
        assertThat(HomeGestures.isTap(120, 3)).isTrue();
        assertThat(HomeGestures.isTap(HomeGestures.TAP_MAX_MS, HomeGestures.TAP_SLOP_DP)).isTrue();
        assertThat(HomeGestures.isTap(HomeGestures.TAP_MAX_MS + 1, 0)).isFalse();
        assertThat(HomeGestures.isTap(50, HomeGestures.TAP_SLOP_DP + 1)).isFalse();
    }

    @Test
    public void doubleTap_needsASecondTapSoonAndNearby() {
        HomeGestures.TapTracker taps = new HomeGestures.TapTracker();
        assertThat(taps.isSecondTap(1000, 100, 100)).isFalse();
        taps.tapped(1000, 100, 100);
        assertThat(taps.isSecondTap(1000 + HomeGestures.DOUBLE_TAP_TIMEOUT_MS, 120, 110)).isTrue();
        assertThat(taps.isSecondTap(1000 + HomeGestures.DOUBLE_TAP_TIMEOUT_MS + 1, 100, 100)).isFalse();
        assertThat(taps.isSecondTap(1100, 100 + HomeGestures.DOUBLE_TAP_SLOP_DP + 1, 100)).isFalse();
        assertThat(taps.isSecondTap(900, 100, 100)).isFalse(); // earlier than the first tap
        taps.reset();
        assertThat(taps.isSecondTap(1100, 100, 100)).isFalse();
    }

    // ---- assignments ---------------------------------------------------------------------------

    @Test
    public void keysAndTwoFingerSet() {
        assertThat(HomeGestures.key(HomeGestures.PINCH_IN)).isEqualTo("gesture.pinchIn");
        assertThat(HomeGestures.twoFinger(HomeGestures.PINCH_OUT)).isTrue();
        assertThat(HomeGestures.twoFinger(HomeGestures.DOUBLE_TAP)).isFalse();
        assertThat(HomeGestures.ALL).asList().containsAtLeastElementsIn(HomeGestures.TWO_FINGER);
    }

    @Test
    public void decode_readsTilesAndIgnoresJunk() {
        Tile t = new Tile();
        t.action = Tile.A_GROUP;
        t.arg = "g3";
        t.label = "WORK";
        Tile back = HomeGestures.decode(t.toJson().toString());
        assertThat(back).isNotNull();
        assertThat(back.action).isEqualTo(Tile.A_GROUP);
        assertThat(back.arg).isEqualTo("g3");
        assertThat(HomeGestures.decode(null)).isNull();
        assertThat(HomeGestures.decode("garbage")).isNull();
        assertThat(HomeGestures.decode(new Tile().toJson().toString())).isNull(); // empty tile = unassigned
    }
}
