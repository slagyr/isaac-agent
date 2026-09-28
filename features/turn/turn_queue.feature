Feature: Turn-request queue — the waiting room in front of the resource pools
  A submitted turn whose resource pools cannot all be leased is PARKED, not
  dropped: a durable held record under the isaac root, visible via `isaac
  turns list` and evictable via `isaac turns drop <id>`. Two wake sources:
  the turn-queue worker TICKS on the scheduler (the clock path — a tide pool
  can only open by clock; also the fallback that keeps holds alive across
  anything), and a RELEASE from a finished turn's finalization nudges the
  queue at once (the release path — "a lease on pool X came back, re-admit
  whoever waits on X"). On each wake the queue walks held requests in submit
  order and runs every one whose pools can all be leased now. At the CLI a
  hold parks and returns (exit 0, prints the held id); the reply lands in
  the session transcript when the turn runs. Unknown pool names still refuse
  loudly before dispatch — see features/turn/resource_pools.feature.
  (isaac-ohsy, isaac-ey7a)

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name   |
      | harbor |
    And the isaac EDN file "config/resource-pools/night-watch.edn" exists with:
      | path   | value         |
      | type   | :tide         |
      | window | "22:00-06:00" |

  Scenario: a tide pool hold parks the turn and the clock tick runs it
    Given the current time is "2026-03-01T14:00:00"
    And the following model responses are queued:
      | type | content      | model |
      | text | Setting sail | echo  |
    When isaac is run with "prompt -m 'Leave harbor' --session harbor --pool night-watch"
    Then the stdout contains "held"
    And the stdout contains "night-watch"
    And the exit code is 0
    When isaac is run with "turns list"
    Then the stdout matches:
      | session | resource-pools | state |
      | harbor  | night-watch    | held  |
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
    When isaac is run with "prompt -m 'Weigh anchor' --session harbor --pool night-watch"
    And the comm delivery system is started
    And the turn queue ticks at "2026-03-01T23:00:00"
    Then session "harbor" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Anchor up       |

  Scenario: a closed pool parks the turn and opening it wakes the queue
    Given a scripted resource pool "dock" admits 1 turn at a time
    And resource pool "dock" is closed
    And the following model responses are queued:
      | type | content  | model |
      | text | Tied off | echo  |
    When isaac is run with "prompt -m 'Come alongside' --session harbor --pool dock"
    Then the stdout contains "held"
    And the stdout contains "dock"
    And the exit code is 0
    When resource pool "dock" is opened
    Then session "harbor" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Tied off        |
    When isaac is run with "turns list"
    Then the stdout does not contain "harbor"

  Scenario: a finished turn's release admits the next held turn in submit order
    Given a scripted resource pool "dock" admits 1 turn at a time
    And the following sessions exist:
      | name  |
      | jetty |
      | quay  |
    And the following model responses are queued:
      | type | content | model | wait |
      | text | First   | echo  | true |
      | text | Second  | echo  |      |
      | text | Third   | echo  |      |
    When the user sends "berth one" on session "harbor" with resource pools "dock"
    And isaac is run with "prompt -m 'berth two' --session jetty --pool dock"
    And isaac is run with "prompt -m 'berth three' --session quay --pool dock"
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
    When isaac is run with "prompt -m 'Leave harbor' --session harbor --pool night-watch"
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
