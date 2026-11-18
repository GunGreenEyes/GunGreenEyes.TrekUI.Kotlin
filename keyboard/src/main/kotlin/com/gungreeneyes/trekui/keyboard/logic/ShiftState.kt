package com.gungreeneyes.trekui.keyboard.logic

/**
 * The shift key's state machine.
 *
 * - [OFF] → tap → [ONCE] (next letter upper case, then back to OFF).
 * - [ONCE] → second tap within [DOUBLE_TAP_MS] → [LOCKED] (caps lock); a slower second tap → OFF.
 * - [LOCKED] → tap → OFF. Typing never releases caps lock.
 * - Automatic capitals ([setAuto]) switch between OFF and ONCE from the field's caps mode but never
 *   touch a manual choice or caps lock. Tapping an automatic capital turns it off; a quick second tap
 *   then locks, so a double tap always means caps lock.
 *
 * Time is passed in, so the machine is deterministic in tests.
 */
internal class ShiftState {
    /** [OFF], [ONCE] or [LOCKED]. */
    var state: Int = OFF
        private set

    /** True while the current ONCE state was set by automatic capitals, not by the user. */
    private var auto = false
    private var lastTap = Long.MIN_VALUE / 2
    /** True if the last tap turned an automatic capital off (a quick next tap then locks). */
    private var lastTapReleasedAuto = false

    /** True while the next letter is upper case. */
    val upper: Boolean get() = state != OFF

    val locked: Boolean get() = state == LOCKED

    /** The user tapped shift at [now] (ms). */
    fun tap(now: Long) {
        val quick = now - lastTap <= DOUBLE_TAP_MS
        var turnedOff = false
        state = when (state) {
            // second tap of a double tap that started on an automatic capital: lock
            OFF -> if (quick && lastTapReleasedAuto) LOCKED else ONCE
            ONCE -> if (auto) {
                turnedOff = true // tapping an automatic capital turns it off (or starts a double tap)
                OFF
            } else {
                if (quick) LOCKED else OFF
            }
            else -> OFF
        }
        auto = false
        lastTapReleasedAuto = turnedOff
        lastTap = now
    }

    /** A character was typed: a one-shot shift is used up. */
    fun typed() {
        if (state == ONCE) {
            state = OFF
            auto = false
        }
    }

    /**
     * Automatic capitals from the field ([wantCaps]: the cursor sits at a sentence / word start).
     * Only moves between OFF and an automatic ONCE; a manual ONCE or caps lock is left alone.
     */
    fun setAuto(wantCaps: Boolean) {
        if (state == LOCKED) return
        if (state == ONCE && !auto) return
        state = if (wantCaps) ONCE else OFF
        auto = wantCaps
    }

    /** Back to lower case (a new field, or a page switch away from letters). */
    fun reset() {
        state = OFF
        auto = false
        lastTapReleasedAuto = false
    }

    companion object {
        const val OFF = 0
        const val ONCE = 1
        const val LOCKED = 2

        /** Two taps on shift within this many milliseconds lock capitals. */
        const val DOUBLE_TAP_MS = 400L
    }
}
