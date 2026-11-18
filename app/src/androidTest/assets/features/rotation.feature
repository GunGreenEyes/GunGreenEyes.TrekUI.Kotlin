# Instrumented by com.gungreeneyes.trekui.RotationScenarios.
Feature: Rotation, landscape and resizing
  The orientation lock was removed: every screen must work in portrait and landscape,
  phone through tablet, folded and unfolded, and in resized windows.

  Scenario: rotationKeepsState - rotating keeps the launcher's state
    Given the Imperial interface is showing in portrait with the app drawer open
    When I rotate the device to landscape
    Then the same launcher instance is showing, still Imperial, drawer still open
    And the landscape layout fits the screen
    When I rotate back to portrait
    Then the drawer is still open and the layout fits

  Scenario: landscapeHomeFits - home screens fit in landscape
    Given the device is in landscape
    When I show the COMMAND, IMPERIAL, COLLECTIVE_HUB and NEXT_CONSOLE interfaces in turn
    Then each fits the landscape screen

  Scenario: resizeDoesNotCrash - resizing the window does not crash
    Given the Command interface is showing
    When the window is resized to half height, a narrow column and the swapped aspect
    Then the launcher is still running and shows content
    When the display density is raised (larger display size setting)
    Then the launcher is still running, shows content and fits
