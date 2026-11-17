# Instrumented by com.gungreeneyes.trekui.SettingsScenarios.
Feature: Launcher settings

  Scenario: settingsOpens - settings open and fit the screen
    Given the launcher home screen is showing
    When I open the launcher settings
    Then the settings screen is shown
    And nothing on it is clipped or under a system bar
    When I scroll to the bottom of the settings
    Then the settings screen is still running
