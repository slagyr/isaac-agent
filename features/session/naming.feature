Feature: Session naming strategy
  New sessions get a name from a configurable strategy when the caller
  does not supply one. Default: random adjective-noun ("happy-turtle").
  Test-friendly alternative: sequential ("session-1", "session-2", ...).

  Background:
    Given an Isaac root at "isaac-state"

  Scenario: sequential strategy names unnamed sessions session-1, session-2, ...
    Given config:
      | sessions.naming-strategy | sequential |
    When a session is created without a name
    And a session is created without a name
    Then session "session-1" exists
    And session "session-2" exists

  Scenario: an explicit name wins over the configured strategy
    Given config:
      | sessions.naming-strategy | sequential |
    When a session is created named "friday-debug"
    Then session "friday-debug" exists
    And session "session-1" does not exist
