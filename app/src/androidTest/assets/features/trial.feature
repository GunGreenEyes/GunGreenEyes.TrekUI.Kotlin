# Instrumented by com.gungreeneyes.trekui.TrialScenarios (develop debug builds; skipped when the
# licensing debug hooks are missing).
Feature: Trial and purchase

  Scenario: trialExpiryScreenAppears - the purchase screen appears when the trial runs out
    Given an unlicensed develop build whose trial session has run out
    When I open the launcher
    Then the trial-expired purchase screen is shown
    And it fits the screen
