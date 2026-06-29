Feature: Session mutation
  `isaac sessions set <id>.<key> [<value>]` and `isaac sessions
  unset <id>.<key>` mutate session records via a schema-aware
  walker. The session schema declares which fields are
  operator-mutable, which are immutable, and which are
  system-managed; mutation attempts on non-operator-mutable fields
  fail with clear errors. Successful mutations bump :updated-at.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: isaac sessions set activates a missing session before mutating
    Given default Grover setup
    When isaac is run with "sessions set relay.tags.role/engineer"
    Then the exit code is 0
    When isaac is run with "sessions show relay --json"
    Then the stdout JSON contains:
      | path | value            |
      | tags | ["role/engineer"] |
    And the exit code is 0

  Scenario: isaac sessions set <id>.tags.<keyword> adds a tag
    Given default Grover setup
    And the following sessions exist:
      | name | crew | tags          |
      | joe  | main | #{:project/x} |
    When isaac is run with "sessions set joe.tags.wip"
    Then the exit code is 0
    When isaac is run with "sessions show joe --json"
    Then the stdout JSON contains:
      | path | value                |
      | tags | ["project/x", "wip"] |
    And the exit code is 0

  Scenario: isaac sessions unset <id>.tags.<keyword> removes a tag
    Given default Grover setup
    And the following sessions exist:
      | name | crew | tags               |
      | joe  | main | #{:project/x :wip} |
    When isaac is run with "sessions unset joe.tags.wip"
    Then the exit code is 0
    When isaac is run with "sessions show joe --json"
    Then the stdout JSON contains:
      | path | value         |
      | tags | ["project/x"] |
    And the exit code is 0

  Scenario: isaac sessions set <id>.crew reassigns to a known crew
    Given default Grover setup
    And the isaac EDN file "config/crew/alice.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name | crew |
      | joe  | main |
    When isaac is run with "sessions set joe.crew alice"
    Then the exit code is 0
    When isaac is run with "sessions show joe --json"
    Then the stdout JSON contains:
      | path | value   |
      | crew | "alice" |
    And the exit code is 0

  Scenario: isaac sessions set <id>.crew errors when the target crew doesn't exist
    Given default Grover setup
    And the following sessions exist:
      | name | crew |
      | joe  | main |
    When isaac is run with "sessions set joe.crew nobody"
    Then the stderr contains "nobody"
    And the exit code is 1

  Scenario: isaac sessions set errors on an immutable field
    Given default Grover setup
    And the following sessions exist:
      | name | crew |
      | joe  | main |
    When isaac is run with "sessions set joe.id different-id"
    Then the stderr contains "immutable"
    And the exit code is 1

  Scenario: isaac sessions set errors on a system-managed field
    Given default Grover setup
    And the following sessions exist:
      | name | crew |
      | joe  | main |
    When isaac is run with "sessions set joe.input-tokens 42"
    Then the stderr contains "system-managed"
    And the exit code is 1

  Scenario: isaac sessions set errors on an unknown field
    Given default Grover setup
    And the following sessions exist:
      | name | crew |
      | joe  | main |
    When isaac is run with "sessions set joe.bogus value"
    Then the stderr contains "no such field"
    And the stderr contains "bogus"
    And the exit code is 1

  Scenario: isaac sessions set is idempotent when the value is already present
    Given default Grover setup
    And the following sessions exist:
      | name | crew | tags          |
      | joe  | main | #{:project/x} |
    When isaac is run with "sessions set joe.tags.project/x"
    Then the exit code is 0
    When isaac is run with "sessions show joe --json"
    Then the stdout JSON contains:
      | path | value         |
      | tags | ["project/x"] |
    And the exit code is 0

  Scenario: isaac sessions unset is idempotent when the value is absent
    Given default Grover setup
    And the following sessions exist:
      | name | crew | tags          |
      | joe  | main | #{:project/x} |
    When isaac is run with "sessions unset joe.tags.wip"
    Then the exit code is 0
    When isaac is run with "sessions show joe --json"
    Then the stdout JSON contains:
      | path | value         |
      | tags | ["project/x"] |
    And the exit code is 0

  Scenario: successful session mutation bumps :updated-at
    Given default Grover setup
    And the following sessions exist:
      | name | crew | tags | updated-at           |
      | joe  | main | #{}  | 1999-12-31T23:59:59Z |
    When isaac is run with "sessions set joe.tags.wip"
    Then the exit code is 0
    When isaac is run with "sessions show joe --json"
    Then the stdout does not contain "1999-12-31T23:59:59Z"
    And the stdout contains "wip"
    And the exit code is 0
