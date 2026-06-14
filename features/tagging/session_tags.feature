Feature: Session tags
  Sessions carry a flat set of keyword tags for routing and discovery.
  Tags are set at creation (CLI flag, or by Hail's fan-out worker)
  and persist in the session store. Listings and the detail view
  expose them.

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: isaac prompt --tag tags the created session
    Given default Grover setup
    And the following model responses are queued:
      | type | content | model |
      | text | Hi      | echo  |
    When isaac is run with "prompt -m 'Hi' --tag project/chess --tag wip"
    Then the exit code is 0
    When isaac is run with "sessions list --json"
    Then the stdout JSON contains:
      | path   | value                    |
      | 0.tags | ["project/chess", "wip"] |

  Scenario: isaac prompt without --tag creates session with empty tags
    Given default Grover setup
    And the following model responses are queued:
      | type | content | model |
      | text | Hi      | echo  |
    When isaac is run with "prompt -m 'Hi'"
    Then the exit code is 0
    When isaac is run with "sessions list --json"
    Then the stdout JSON contains:
      | path   | value |
      | 0.tags | []    |

  Scenario: isaac sessions list shows a Tags column
    Given default Grover setup
    And the following sessions exist:
      | name | crew | tags                   |
      | joe  | main | #{:project/chess :wip} |
    When isaac is run with "sessions list"
    Then the stdout matches:
      | pattern               |
      | Name .* Crew .* Tags  |
      | joe .* :project/chess |
      | joe .* :wip           |
    And the exit code is 0

  Scenario: isaac sessions list --json includes tags on each record
    Given default Grover setup
    And the following sessions exist:
      | name | crew | tags                   |
      | joe  | main | #{:project/chess :wip} |
    When isaac is run with "sessions list --json"
    Then the stdout JSON contains:
      | path   | value                    |
      | 0.name | "joe"                    |
      | 0.tags | ["project/chess", "wip"] |
    And the exit code is 0

  Scenario: isaac sessions list --tag filters to sessions carrying that tag
    Given default Grover setup
    And the following sessions exist:
      | name | crew | tags              |
      | joe  | main | #{:project/chess} |
      | sue  | main | #{:project/poker} |
    When isaac is run with "sessions list --tag project/chess"
    Then the stdout contains "joe"
    And the stdout does not contain "sue"
    And the exit code is 0

  Scenario: isaac sessions list --tag composes with --not-in-flight
    Given default Grover setup
    And the following sessions exist:
      | name | crew | tags              |
      | joe  | main | #{:project/chess} |
      | sue  | main | #{:project/chess} |
    And session "joe" is in flight
    When isaac is run with "sessions list --tag project/chess --not-in-flight"
    Then the stdout contains "sue"
    And the stdout does not contain "joe"
    And the exit code is 0

  Scenario: isaac sessions show <id> displays tags in the detail view
    Given default Grover setup
    And the following sessions exist:
      | name | crew | tags                           |
      | joe  | main | #{:project/chess :role/worker} |
    When isaac is run with "sessions show joe"
    Then the stdout contains "Tags"
    And the stdout contains ":project/chess"
    And the stdout contains ":role/worker"
    And the exit code is 0

  Scenario: isaac sessions show <id> --json includes tags
    Given default Grover setup
    And the following sessions exist:
      | name | crew | tags                           |
      | joe  | main | #{:project/chess :role/worker} |
    When isaac is run with "sessions show joe --json"
    Then the stdout JSON contains:
      | path | value                            |
      | name | "joe"                            |
      | tags | ["project/chess", "role/worker"] |
    And the exit code is 0
