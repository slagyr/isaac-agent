Feature: Hot reload of config-driven components
  Every component that owns a slice of config must react to runtime
  content changes — not just additions and removals of named entries.
  Hooks and cron are wired through the same `configurator/reconcile!`
  machinery as comms; their on-config-change! receives the new slice
  and updates internal state without a server restart.

  Background:
    Given default Grover setup

  Scenario: Hook template content change is picked up at runtime
    Given config:
      | key                          | value                             |
      | hooks.cage-check.template    | Brain checks the lock: {{status}} |
      | hooks.cage-check.session-key | hook:cage-check                   |
      | hooks.cage-check.crew        | main                              |
    And the Isaac server is started
    And the hook "cage-check" registry entry has:
      | path     | value                             |
      | template | Brain checks the lock: {{status}} |
    When config is updated:
      | path                      | value                                    |
      | hooks.cage-check.template | Pinky checks the lock: {{status}}. Narf! |
    Then the hook "cage-check" registry entry has:
      | path     | value                                    |
      | template | Pinky checks the lock: {{status}}. Narf! |

  Scenario: Cron prompt content change is picked up at runtime
    Given config:
      | key                      | value                                   |
      | cron.evening-plan.expr   | 0 21 * * *                              |
      | cron.evening-plan.crew   | main                                    |
      | cron.evening-plan.prompt | What are we going to do tonight, Brain? |
    And the Isaac server is started
    And the cron job "evening-plan" has:
      | path   | value                                   |
      | prompt | What are we going to do tonight, Brain? |
    When config is updated:
      | path                     | value                                                             |
      | cron.evening-plan.prompt | Same thing we do every night, Pinky — try to take over the world. |
    Then the cron job "evening-plan" has:
      | path   | value                                                             |
      | prompt | Same thing we do every night, Pinky — try to take over the world. |
