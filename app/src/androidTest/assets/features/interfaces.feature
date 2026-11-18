# Instrumented by com.gungreeneyes.trekui.InterfaceScenarios (one run per interface).
Feature: Every interface renders and fits the screen
  The launcher ships three console factions (COMMAND, IMPERIAL, COLLECTIVE), their alternative
  layouts (COMMAND reveal, IMPERIAL reveal, COLLECTIVE hub) and the CLASSIC CONSOLE and
  NEXT CONSOLE eras.

  Scenario Outline: interfaceRenders - <interface> renders its home screen
    Given the launcher is installed
    When I switch to the <interface> interface and open home
    Then the home screen is shown in that interface
    And the activity is still running after a few seconds

  Scenario Outline: interfaceFitsScreen - nothing on <interface> is clipped
    Given the <interface> interface is showing
    Then no labelled or clickable view is clipped off-screen or under a system bar

  Scenario Outline: drawerOpensAndListsApps - the app drawer opens over <interface>
    Given the <interface> interface is showing
    When I open the app drawer
    Then the drawer is open and lists apps
    And nothing in the drawer is clipped

    Examples:
      | interface          |
      | COMMAND            |
      | COMMAND_REVEAL     |
      | IMPERIAL           |
      | IMPERIAL_REVEAL    |
      | COLLECTIVE         |
      | COLLECTIVE_HUB     |
      | CLASSIC_CONSOLE    |
      | NEXT_CONSOLE       |
