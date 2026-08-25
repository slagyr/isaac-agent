Feature: Turn-request queue — the waiting room in front of the turnstiles
  A submitted turn whose turnstile stack answers :hold is PARKED, not dropped:
  a durable held record under the isaac root, visible via `isaac turns list`
  and evictable via `isaac turns drop <id>`. Two wake sources: the turn-queue
  worker TICKS on the scheduler (the clock path — tide can only wake by clock;
  also the fallback that keeps holds alive across anything), and a RELEASE
  TOKEN firing from a finished turn's finalization nudges the queue at once
  (the token path — "turn finished behind turnstile X, re-admit whoever waits
  at X"). On each wake the queue walks held requests in submit order and runs
  every one whose whole stack now passes. At the CLI a hold parks and returns
  (exit 0, prints the held id); the reply lands in the session transcript
  when the turn runs. :refuse and unknown names still refuse loudly before
  dispatch — see features/turn/turnstiles.feature. (isaac-ohsy)

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name   |
      | harbor |

  Scenario: a tide hold parks the turn and the clock tick runs it
    Given the current time is "2026-03-01T14:00:00"
    And the following model responses are queued:
      | type | content      | model |
      | text | Setting sail | echo  |
    When isaac is run with "prompt -m 'Leave harbor' --session harbor --turnstile tide:22:00-06:00"
    Then the stdout contains "held"
    And the stdout contains "tide 22:00-06:00"
    And the exit code is 0
    When isaac is run with "turns list"
    Then the stdout matches:
      | session | turnstiles       | state |
      | harbor  | tide:22:00-06:00 | held  |
    When the turn queue ticks at "2026-03-01T21:00:00"
    Then session "harbor" has transcript matching:
      | type    | message.role | message.content | #comment                          |
      | message | user         | Leave harbor    | outside the window: no reply yet  |
    When the turn queue ticks at "2026-03-01T23:30:00"
    Then session "harbor" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Leave harbor    |
      | message | assistant    | Setting sail    |
    When isaac is run with "turns list"
    Then the stdout does not contain "harbor"

  Scenario: a held turn survives a restart and still runs on wake
    Given the current time is "2026-03-01T14:00:00"
    And the following model responses are queued:
      | type | content   | model |
      | text | Anchor up | echo  |
    When isaac is run with "prompt -m 'Weigh anchor' --session harbor --turnstile tide:22:00-06:00"
    And the comm delivery system is started
    And the turn queue ticks at "2026-03-01T23:00:00"
    Then session "harbor" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Anchor up       |

  Scenario: a closed turnstile parks the turn and opening it wakes the queue
    Given a turnstile "dock" is registered that admits 1 at a time
    And turnstile "dock" is closed
    And the following model responses are queued:
      | type | content  | model |
      | text | Tied off | echo  |
    When isaac is run with "prompt -m 'Come alongside' --session harbor --turnstile dock"
    Then the stdout contains "held"
    And the stdout contains "dock"
    And the exit code is 0
    When turnstile "dock" is opened
    Then session "harbor" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Tied off        |
    When isaac is run with "turns list"
    Then the stdout does not contain "harbor"

  Scenario: a finished turn's release token admits the next held turn in submit order
    Given a turnstile "dock" is registered that admits 1 at a time
    And the following sessions exist:
      | name  |
      | jetty |
      | quay  |
    And the following model responses are queued:
      | type | content | model | wait |
      | text | First   | echo  | true |
      | text | Second  | echo  |      |
      | text | Third   | echo  |      |
    When the user sends "berth one" on session "harbor" with turnstiles "dock"
    And isaac is run with "prompt -m 'berth two' --session jetty --turnstile dock"
    And isaac is run with "prompt -m 'berth three' --session quay --turnstile dock"
    And isaac is run with "turns list"
    Then the stdout lines contain in order:
      | jetty |
      | quay  |
    When the turn ends on session "harbor"
    Then session "jetty" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Second          |
    And session "quay" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Third           |

  Scenario: turns drop evicts a held turn and it never runs
    Given the current time is "2026-03-01T14:00:00"
    And the following model responses are queued:
      | type | content    | model |
      | text | Never seen | echo  |
    When isaac is run with "prompt -m 'Leave harbor' --session harbor --turnstile tide:22:00-06:00"
    Then the stdout matches:
      | held: #"[a-z0-9-]+":held-id |
    When isaac is run with "turns drop #held-id"
    Then the stdout contains "dropped"
    When the turn queue ticks at "2026-03-01T23:30:00"
    And isaac is run with "turns list"
    Then the stdout does not contain "harbor"
    When isaac is run with "prompt -m 'Leave harbor' --session harbor"
    Then the stdout contains "Never seen"
    And the exit code is 0
