# Instrumented by com.gungreeneyes.trekui.HomeScenarios (app/src/androidTest).
Feature: The launcher replaces the home screen
  As a phone or tablet owner
  I want TrekUI to be my home screen
  So that the whole device wears the TrekUI skin

  Scenario: launcherStartsAndShowsHome - the launcher starts and shows home
    Given the launcher is installed with default settings
    When I start it from the app list
    Then the TrekUI home screen is shown
    And nothing on it is clipped or under a system bar

  Scenario: setAsDefaultHome - set as the default home app
    Given another app is the default home
    When I ask the launcher to become the default home app
    And I accept the system dialog (RoleManager on Android 10+, home picker before)
    Then it holds the home role
    And pressing Home shows the TrekUI home screen
