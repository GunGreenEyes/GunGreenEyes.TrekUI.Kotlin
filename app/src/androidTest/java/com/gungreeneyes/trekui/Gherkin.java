package com.gungreeneyes.trekui;

import android.util.Log;

/**
 * Tiny Given/When/Then step recorder used by the instrumented scenarios.
 *
 * <p>The scenario text lives in {@code app/src/androidTest/assets/features/*.feature} so it reads as
 * Gherkin; each test method mirrors one scenario and announces its steps through this class. Steps go
 * to logcat under the {@code console-GHERKIN} tag (the capture runner saves logcat next to the media), so
 * a failing run shows exactly which step it reached. Cucumber itself is deliberately not used: plain
 * JUnit4 keeps the toolchain small and works on every API level from 26 up.</p>
 */
final class Gherkin {
    static final String TAG = "TREKUI-GHERKIN";

    private final String scenario;
    private String lastStep = "";

    Gherkin(String scenario) {
        this.scenario = scenario;
        Log.i(TAG, "Scenario: " + scenario);
    }

    Gherkin given(String text) {
        return step("Given", text);
    }

    Gherkin when(String text) {
        return step("When", text);
    }

    Gherkin then(String text) {
        return step("Then", text);
    }

    Gherkin and(String text) {
        return step("And", text);
    }

    /** The last step announced, quoted in assertion messages so failures point at the scenario line. */
    String lastStep() {
        return lastStep;
    }

    private Gherkin step(String keyword, String text) {
        lastStep = keyword + " " + text;
        Log.i(TAG, scenario + " | " + lastStep);
        return this;
    }
}
