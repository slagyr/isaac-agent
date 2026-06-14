Feature: Session Identity
  Sessions have user-chosen names (or auto-generated ones).
  The name is display-friendly. The id is a slugified version
  used for filenames and API references. Sessions are stored
  flat under ~/.isaac/sessions/, independent of any crew member.

  Background:
    Given default Grover setup

  Scenario: creating a session with a chosen name
    Given the following sessions exist:
      | name         |
      | friday-debug |
    Then the session "friday-debug" exists
    And the session file is "sessions/friday-debug.jsonl"

  Scenario: session name is converted to a valid filename
    Given the following sessions exist:
      | name          |
      | Friday Debug! |
    Then the session "Friday Debug!" exists
    And the session file is "sessions/friday-debug.jsonl"

  Scenario: session with no name gets an auto-generated name
    When a session is created with a random name
    Then the session count is 1

  Scenario: session has a name and an id
    Given the following sessions exist:
      | name          |
      | Friday Debug! |
    Then the following sessions match:
      | id           | name          | file                            |
      | friday-debug | Friday Debug! | sessions/friday-debug.jsonl     |

  Scenario: session id must be unique
    Given the following sessions exist:
      | name         |
      | friday-debug |
    When a session is created with name "Friday Debug"
    Then the error contains "session already exists: friday-debug"

  Scenario: most recent session is found by updated time
    Given the following sessions exist:
      | name    | updated-at           |
      | old-one | 2026-04-10T10:00:00 |
      | new-one | 2026-04-12T15:00:00 |
    Then the most recent session is "new-one"

  Scenario: session uses default crew member when none specified
    Given the following sessions exist:
      | name         |
      | friday-debug |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When the user sends "hi" on session "friday-debug"
    Then session "friday-debug" has transcript matching:
      | type    | message.role | message.crew |
      | message | assistant    | main         |

  Scenario: transcript records crew and model per message
    Given the following sessions exist:
      | name         |
      | friday-debug |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When the user sends "hi" on session "friday-debug"
    Then session "friday-debug" has transcript matching:
      | type    | message.role | message.crew | message.model |
      | message | user         |              |               |
      | message | assistant    | main         | echo          |
