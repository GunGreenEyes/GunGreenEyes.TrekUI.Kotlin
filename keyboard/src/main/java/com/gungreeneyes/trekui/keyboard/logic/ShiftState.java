package com.gungreeneyes.trekui.keyboard.logic;

/**
 * The shift key's state machine.
 *
 * <ul>
 *   <li>{@link #OFF} → tap → {@link #ONCE} (next letter upper case, then back to OFF).</li>
 *   <li>{@link #ONCE} → second tap within {@link #DOUBLE_TAP_MS} → {@link #LOCKED} (caps lock);
 *       a slower second tap → OFF.</li>
 *   <li>{@link #LOCKED} → tap → OFF. Typing never releases caps lock.</li>
 *   <li>Automatic capitals ({@link #setAuto}) switch between OFF and ONCE from the field's caps mode
 *       but never touch a manual choice or caps lock. Tapping an automatic capital turns it off; a
 *       quick second tap then locks, so a double tap always means caps lock.</li>
 * </ul>
 * Time is passed in, so the machine is deterministic in tests.
 */
public final class ShiftState {
    public static final int OFF = 0, ONCE = 1, LOCKED = 2;
    /** Two taps on shift within this many milliseconds lock capitals. */
    public static final long DOUBLE_TAP_MS = 400;

    private int state = OFF;
    /** True while the current ONCE state was set by automatic capitals, not by the user. */
    private boolean auto;
    private long lastTap = Long.MIN_VALUE / 2;
    /** True if the last tap turned an automatic capital off (a quick next tap then locks). */
    private boolean lastTapReleasedAuto;

    public int state() { return state; }

    /** True while the next letter is upper case. */
    public boolean upper() { return state != OFF; }

    public boolean locked() { return state == LOCKED; }

    /** The user tapped shift at {@code now} (ms). */
    public void tap(long now) {
        boolean quick = now - lastTap <= DOUBLE_TAP_MS;
        boolean turnedOff = false;
        switch (state) {
            case OFF:
                // second tap of a double tap that started on an automatic capital: lock
                state = quick && lastTapReleasedAuto ? LOCKED : ONCE;
                break;
            case ONCE:
                if (auto) {
                    state = OFF; // tapping an automatic capital turns it off (or starts a double tap)
                    turnedOff = true;
                } else {
                    state = quick ? LOCKED : OFF;
                }
                break;
            default:
                state = OFF;
        }
        auto = false;
        lastTapReleasedAuto = turnedOff;
        lastTap = now;
    }

    /** A character was typed: a one-shot shift is used up. */
    public void typed() {
        if (state == ONCE) {
            state = OFF;
            auto = false;
        }
    }

    /**
     * Automatic capitals from the field ({@code wantCaps}: the cursor sits at a sentence / word start).
     * Only moves between OFF and an automatic ONCE; a manual ONCE or caps lock is left alone.
     */
    public void setAuto(boolean wantCaps) {
        if (state == LOCKED) return;
        if (state == ONCE && !auto) return;
        if (wantCaps) {
            state = ONCE;
            auto = true;
        } else {
            state = OFF;
            auto = false;
        }
    }

    /** Back to lower case (a new field, or a page switch away from letters). */
    public void reset() {
        state = OFF;
        auto = false;
        lastTapReleasedAuto = false;
    }
}
