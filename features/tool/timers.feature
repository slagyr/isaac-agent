@wip
Feature: Timers
  A crew sets a timer to come back to something later instead of holding
  its turn: timer__set {after, note} queues a turn on the SAME session, due
  `after` seconds from now (60..3600), with the note as its input and
  origin :timer. Timers are turn records, so they survive a restart.
  timer__list and timer__cancel manage them; a crew's :max-timers (default
  10) caps how many a session has pending. exec__run refuses sleep-polling
  and points at timer__set. (isaac-by8s)

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name   | crew |
      | bridge | main |
    And the current session is "bridge"
    And the current time is "2026-03-01T18:00:00Z"

  Scenario: a timer comes back to the same session when it is due, not before (isaac-by8s)
    Given the following model responses are queued:
      | type | content                                    | model |
      | text | Scouts are still out; I'll check again.    | echo  |
    When the tool "timer__set" is called with:
      | after | 120                  |
      | note  | Check on the scouts. |
    Then the tool result is not an error
    And the tool result JSON has:
      | path   | value                |
      | handle | #*                   |
      | due    | 2026-03-01T18:02:00Z |
    When the turn queue ticks at "2026-03-01T18:01:59"
    Then grover records zero provider requests
    When the turn queue ticks at "2026-03-01T18:02:00"
    Then session "bridge" has transcript matching:
      | #index | type    | message.role | message.content                        |
      | -2     | message | user         | #"(?s).*Check on the scouts\..*"       |
      | -1     | message | assistant    | Scouts are still out; I'll check again. |
    And the latest turn on session "bridge" matches:
      | key         | value    |
      | origin.kind | timer    |
      | state       | finished |

  Scenario: a delay outside 60..3600 seconds is a tool error naming the range (isaac-by8s)
    When the tool "timer__set" is called with:
      | after | 30                   |
      | note  | Check on the scouts. |
    Then the tool result is an error
    And the tool result contains "after must be between 60 and 3600 seconds"

  Scenario: a cancelled timer never fires and drops off the list (isaac-by8s)
    When the tool "timer__set" is called with:
      | after | 120                  |
      | note  | Check on the scouts. |
    And the tool "timer__list" is called
    Then the tool result JSON has:
      | path             | value                |
      | timers[0].handle | #"(.+)":timer        |
      | timers[0].note   | Check on the scouts. |
      | timers[0].due    | 2026-03-01T18:02:00Z |
    When the tool "timer__cancel" is called with:
      | handle | #timer |
    And the turn queue ticks at "2026-03-01T18:05:00"
    Then grover records zero provider requests
    When the tool "timer__list" is called
    Then the tool result JSON has:
      | path   | value |
      | timers | []    |

  Scenario: a session can't have more than :max-timers pending (isaac-by8s)
    Given config:
      | key                      | value |
      | defaults.crew.max-timers | 1     |
    When the tool "timer__set" is called with:
      | after | 120                  |
      | note  | Check on the scouts. |
    And the tool "timer__set" is called with:
      | after | 300                  |
      | note  | Check on the tide.   |
    Then the tool result is an error
    And the tool result contains "timer limit reached (1 pending)"

  Scenario: exec__run refuses a sleep and points at timer__set (isaac-by8s)
    Given the crew "main" allows tools: "exec/run"
    When the tool "exec__run" is called with:
      | command | sleep 45 |
    Then the tool result is an error
    And the tool result contains "timer__set"
