@wip
Feature: A turn fails when the session policy stamp disagrees with the crew
  The session record keeps the policy that opened it. The turn reads the
  crew's current policy. When those names differ, the turn fails before it
  appends. The error names the session and both policies. A session that
  does not exist yet is not a conflict: opening it stamps the crew policy.
  Logbook stands in for any non-chronicle policy, including episodes.
  Decision (2026-09-27, Micah).

  Background:
    Given default Grover setup
    And a recording session policy "logbook" is registered

  Scenario: a chronicle session refuses a turn from a logbook crew
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value            |
      | model | echo             |
      | soul  | You are Cordelia |
    And the following sessions exist:
      | name         | crew      |
      | lantern-room | cordelia  |
    And the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value   |
      | session-policy | logbook |
    And the following model responses are queued:
      | type | content | model |
      | text | Lit     | echo  |
    When isaac is run with "prompt --crew cordelia --session lantern-room -m 'Light the lamp'"
    Then the exit code is 1
    And the stderr contains "lantern-room"
    And the stderr contains "chronicle"
    And the stderr contains "logbook"
    And the logbook policy recorded no calls
    And session "lantern-room" has no transcript entries with role "user"

  Scenario: a logbook session refuses a turn once its crew is chronicle
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | logbook          |
    And the following model responses are queued:
      | type | content | model |
      | text | Lit     | echo  |
    When isaac is run with "prompt --crew cordelia --session lantern-room -m 'Light the lamp'"
    Then the exit code is 0
    And the following sessions match:
      | id           | crew     | session-policy |
      | lantern-room | cordelia | logbook        |
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value   |
      | session-policy | #delete |
    And the following model responses are queued:
      | type | content | model |
      | text | Again   | echo  |
    When isaac is run with "prompt --crew cordelia --session lantern-room -m 'Light it again'"
    Then the exit code is 1
    And the stderr contains "lantern-room"
    And the stderr contains "logbook"
    And the stderr contains "chronicle"
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Light the lamp  |
      | message | assistant    | Lit             |

  Scenario: a new session takes the crew policy and the turn runs
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path           | value            |
      | model          | echo             |
      | soul           | You are Cordelia |
      | session-policy | logbook          |
    And the following model responses are queued:
      | type | content | model |
      | text | Lit     | echo  |
    When isaac is run with "prompt --crew cordelia --session lantern-room -m 'Light the lamp'"
    Then the exit code is 0
    And the stdout contains "Lit"
    And the following sessions match:
      | id           | crew     | session-policy |
      | lantern-room | cordelia | logbook        |

  Scenario: a chronicle session runs when the crew is chronicle
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path  | value            |
      | model | echo             |
      | soul  | You are Cordelia |
    And the following sessions exist:
      | name         | crew     |
      | lantern-room | cordelia |
    And the following model responses are queued:
      | type | content | model |
      | text | Lit     | echo  |
    When isaac is run with "prompt --crew cordelia --session lantern-room -m 'Light the lamp'"
    Then the exit code is 0
    And the stdout contains "Lit"
    And session "lantern-room" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Light the lamp  |
      | message | assistant    | Lit             |
