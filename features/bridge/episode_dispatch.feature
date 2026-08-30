@wip
Feature: Bridge dispatch of a pre-built charge runs the episode router
  For crews with :conversation :episodes the inbound session-key is the
  conversation thread (not a process thread). resolve-thread! maps it to
  the current open episode. Today that only runs when dispatch! gets a
  request map; a pre-built charge (Discord, ACP) skips it. Chronicle
  crews still use the session-key as the session.

  Background:
    Given default Grover setup

  Scenario: a pre-built charge on an episodes crew opens an episode on that thread
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
    When a charge is dispatched with:
      | key         | value          |
      | session-key | lantern-room   |
      | crew        | cordelia       |
      | input       | Light the lamp |
    Then an episode exists for crew "cordelia" matching:
      | key    | value                          |
      | id     | #"\d{4}-\d{2}-\d{2}-\d{4}-\w+" |
      | status | open                           |
      | thread | lantern-room                   |
    And the following sessions match:
      | id                             | crew     |
      | #"\d{4}-\d{2}-\d{2}-\d{4}-\w+" | cordelia |
    And session "lantern-room" does not exist

  Scenario: a warm second charge on the same thread appends to the open episode
    Given the isaac EDN file "config/crew/cordelia.edn" exists with:
      | path         | value            |
      | model        | echo             |
      | soul         | You are Cordelia |
      | conversation | episodes         |
    And the current time is "2026-03-01T10:00:00"
    And the following model responses are queued:
      | type | content            | model |
      | text | Charted, keep west | echo  |
      | text | Wick trimmed       | echo  |
    When a charge is dispatched with:
      | key         | value          |
      | session-key | lantern-room   |
      | crew        | cordelia       |
      | input       | Light the lamp |
    Given the current time is "2026-03-01T10:10:00"
    When a charge is dispatched with:
      | key         | value         |
      | session-key | lantern-room  |
      | crew        | cordelia      |
      | input       | Trim the wick |
    Then crew "cordelia" has 1 episode
    And an episode exists for crew "cordelia" matching:
      | key    | value        |
      | status | open         |
      | thread | lantern-room |
    And that episode's backing session has transcript matching:
      | type    | message.role | message.content    |
      | message | user         | Light the lamp     |
      | message | assistant    | Charted, keep west |
      | message | user         | Trim the wick      |
      | message | assistant    | Wick trimmed       |

  Scenario: a pre-built charge on a chronicle crew uses the session-key as the session
    Given the following model responses are queued:
      | type | content | model |
      | text | Aye     | echo  |
    When a charge is dispatched with:
      | key         | value        |
      | session-key | lantern-room |
      | crew        | main         |
      | input       | Status?      |
    Then the following sessions match:
      | id           |
      | lantern-room |
    And crew "main" has 0 episodes
