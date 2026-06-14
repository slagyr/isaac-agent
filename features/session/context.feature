Feature: Turn context resolution
  Resolving per-turn context (soul, model, provider) from a session
  key and config. This is the single source of truth — channels
  never resolve these independently.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/grover.edn" exists with:
      | path | value |
      | model | echo |
      | provider | grover |
      | context-window | 32768 |

  Scenario: soul falls back to default when none is configured
    When turn context is resolved for crew "main"
    Then the resolved soul is "You are Isaac, a helpful AI assistant."

  Scenario: soul from crew config is used when present
    Given the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | grover |
      | soul | Custom soul text |
    When turn context is resolved for crew "main"
    Then the resolved soul is "Custom soul text"

  Scenario: model and provider resolved from config defaults
    When turn context is resolved for crew "main"
    Then the resolved model is not nil
    And the resolved provider is not nil
