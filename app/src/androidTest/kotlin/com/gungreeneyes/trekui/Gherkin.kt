package com.gungreeneyes.trekui

import android.util.Log

/**
 * Tiny Given/When/Then step recorder used by the instrumented scenarios.
 *
 * The scenario text lives in the `.feature` files in `app/src/androidTest/assets/features` so it reads as
 * Gherkin; each test method mirrors one scenario and announces its steps through this class. Steps go
 * to logcat under the `console-GHERKIN` tag (the capture runner saves logcat next to the media), so
 * a failing run shows exactly which step it reached. Cucumber itself is deliberately not used: plain
 * JUnit4 keeps the toolchain small and works on every API level from 26 up.
 */
class Gherkin(private val scenario: String) {

    /** The last step announced, quoted in assertion messages so failures point at the scenario line. */
    var lastStep: String = ""
        private set

    init {
        Log.i(TAG, "Scenario: $scenario")
    }

    fun given(text: String): Gherkin = step("Given", text)

    fun `when`(text: String): Gherkin = step("When", text)

    fun then(text: String): Gherkin = step("Then", text)

    fun and(text: String): Gherkin = step("And", text)

    private fun step(keyword: String, text: String): Gherkin {
        lastStep = "$keyword $text"
        Log.i(TAG, "$scenario | $lastStep")
        return this
    }

    companion object {
        const val TAG = "TREKUI-GHERKIN"
    }
}
