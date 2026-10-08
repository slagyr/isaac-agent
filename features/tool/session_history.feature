@wip
Feature: session_list and session_read tools
  A crew reads its own conversations by time window. session_list finds
  the crew's sessions with messages in the window; session_read returns
  one session's messages as labelled lines: time, role, text. Tool results
  are left out and tool calls are shown as short markers.

  Both are scoped to the calling crew: another crew's session is never
  listed and reads as if it did not exist. Neither tool is granted by
  default.

  session_read pages at the configured tool output cap
  (defaults.tools.max-lines / max-bytes), ending on a whole message and
  naming the offset to resume from. Transcripts are append-only, so an
  offset into a fixed window stays valid.

  Tracked by isaac-d3qj.

  Background:
    Given default Grover setup
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name       | crew  |
      | logbook    | main  |
      | almanac    | main  |
      | ketch-chat | ketch |
    And the current session is "logbook"

  Scenario: session_list lists the crew's sessions with messages in the window
    Given session "logbook" has transcript:
      | timestamp            | message.role | message.content                    |
      | 2026-04-19T09:00:00Z | user         | Orpheus is sulking under the porch. |
      | 2026-04-21T14:00:00Z | user         | Always water the moonflowers first. |
      | 2026-04-21T14:00:05Z | assistant    | Moonflowers first, noted.           |
    And session "almanac" has transcript:
      | timestamp            | message.role | message.content          |
      | 2026-04-18T08:00:00Z | user         | The geode is in the attic. |
    And session "ketch-chat" has transcript:
      | timestamp            | message.role | message.content |
      | 2026-04-21T15:00:00Z | user         | Arr.            |
    When the tool "session__list" is called with:
      | since | 2026-04-21T00:00:00Z |
    Then the tool result is not an error
    And the tool result lines match:
      | text                                                           |
      | logbook 2 messages 2026-04-21T14:00:00Z..2026-04-21T14:00:05Z |
    And the tool result does not contain "almanac"
    And the tool result does not contain "ketch-chat"

  Scenario: session_read returns labelled lines inside the window, without tool output
    Given session "logbook" has transcript:
      | type       | timestamp            | message.role | message.content                     | name     | arguments              | id |
      | message    | 2026-04-19T09:00:00Z | user         | Orpheus is sulking under the porch. |          |                        |    |
      | message    | 2026-04-21T14:00:00Z | user         | Always water the moonflowers first. |          |                        |    |
      | toolCall   | 2026-04-21T14:00:02Z |              |                                     | fs__read | {"path":"garden.csv"}  | c1 |
      | toolResult | 2026-04-21T14:00:03Z |              | 9000 rows of seedlings              |          |                        | c1 |
      | message    | 2026-04-21T14:00:05Z | assistant    | Moonflowers first, noted.           |          |                        |    |
    When the tool "session__read" is called with:
      | session | logbook              |
      | since   | 2026-04-21T00:00:00Z |
    Then the tool result is not an error
    And the tool result lines match:
      | text                                                           |
      | 2026-04-21T14:00:00Z user: Always water the moonflowers first. |
      | 2026-04-21T14:00:02Z assistant: (tool fs__read                 |
      | 2026-04-21T14:00:05Z assistant: Moonflowers first, noted.      |
    And the tool result does not contain "Orpheus"
    And the tool result does not contain "9000 rows"

  Scenario: session_read pages at the configured tool output cap
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                     | value |
      | defaults.tools.max-lines | 3     |
    And session "logbook" has transcript:
      | timestamp            | message.role | message.content |
      | 2026-04-21T14:00:00Z | user         | one             |
      | 2026-04-21T14:00:01Z | assistant    | two             |
      | 2026-04-21T14:00:02Z | user         | three           |
    When the tool "session__read" is called with:
      | session | logbook              |
      | since   | 2026-04-21T00:00:00Z |
    Then the tool result lines match:
      | text                           |
      | user: one                      |
      | assistant: two                 |
      | 2 of 3 messages; next offset 2 |
    And the tool result does not contain "three"
    And the tool result does not contain "truncated"
    When the tool "session__read" is called with:
      | session | logbook              |
      | since   | 2026-04-21T00:00:00Z |
      | offset  | 2                    |
    Then the tool result lines match:
      | text        |
      | user: three |
    And the tool result does not contain "next offset"

  Scenario: until closes the window
    Given session "logbook" has transcript:
      | timestamp            | message.role | message.content           |
      | 2026-04-21T14:00:00Z | user         | The vault door creaks.    |
      | 2026-04-22T09:00:00Z | user         | The hedgehog fell over.   |
    When the tool "session__read" is called with:
      | session | logbook              |
      | since   | 2026-04-21T00:00:00Z |
      | until   | 2026-04-22T00:00:00Z |
    Then the tool result contains "The vault door creaks."
    And the tool result does not contain "hedgehog"

  Scenario: session_read refuses a session that belongs to another crew
    Given session "ketch-chat" has transcript:
      | timestamp            | message.role | message.content |
      | 2026-04-21T15:00:00Z | user         | Arr.            |
    When the tool "session__read" is called with:
      | session | ketch-chat           |
      | since   | 2026-04-21T00:00:00Z |
    Then the tool result is an error
    And the tool result contains "no session ketch-chat"
    And the tool result does not contain "Arr."
