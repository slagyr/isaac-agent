Feature: Turn store — every turn has a durable record and a stable id
  Every submission is written to the TurnStore the moment it is accepted
  and gets a turn id that never changes: queued, held, waiting, running,
  then finished with an outcome (ok, error, cancelled, dropped). Finished
  records are kept. `prompt --queue` accepts a turn without running it
  and prints its id; the queue runs it on a wake. A submission carrying
  an idempotency key that was already accepted returns the existing turn
  and creates nothing. A record left running by a crash is reconciled on
  restart and runs once, under the same id. `turns list` shows unfinished
  turns; `--all` adds finished ones with their outcome. The file store
  (turns/<id>.edn) is the first TurnStore adapter. (isaac-70cr)

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name   |
      | harbor |

  Scenario: a queued turn runs nothing, survives a restart, and runs later under the same id
    Given the following model responses are queued:
      | type | content      | model |
      | text | Setting sail | echo  |
    When isaac is run with "prompt --queue --session harbor -m 'Leave harbor'"
    Then the stdout matches:
      | queued: #"[a-z0-9]+":turn-id |
    And the exit code is 0
    And session "harbor" has transcript not matching:
      | type    | message.role | message.content |
      | message | assistant    | Setting sail    |
    When the comm delivery system is started
    And the turn queue ticks at "2026-03-01T18:00:00"
    Then session "harbor" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Leave harbor    |
      | message | assistant    | Setting sail    |
    When isaac is run with "turns show #turn-id"
    Then the stdout matches:
      | #turn-id |
      | harbor   |
      | finished |
      | ok       |

  Scenario: a repeated idempotency key returns the accepted turn and runs one turn
    Given the following model responses are queued:
      | type | content      | model |
      | text | Setting sail | echo  |
      | text | Sailed twice | echo  |
    When isaac is run with "prompt --queue --key tide-1 --session harbor -m 'Leave harbor'"
    Then the stdout matches:
      | queued: #"[a-z0-9]+":turn-id |
    When isaac is run with "prompt --queue --key tide-1 --session harbor -m 'Leave harbor'"
    Then the stdout contains "already accepted"
    And the exit code is 0
    When the turn queue ticks at "2026-03-01T18:00:00"
    Then session "harbor" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Setting sail    |
    And session "harbor" has transcript not matching:
      | type    | message.role | message.content |
      | message | assistant    | Sailed twice    |
    When isaac is run with "turns show #turn-id"
    Then the stdout matches:
      | #turn-id |
      | harbor   |
      | finished |
      | ok       |

  Scenario: finished turns stay listed with their outcome
    Given the following model responses are queued:
      | type       | status | content  | message          | model |
      | text       |        | On deck. |                  | echo  |
      | http-error | 400    |          | lamp oil spilled | echo  |
    When isaac is run with "prompt --session harbor -m 'Status?'"
    Then the exit code is 0
    When isaac is run with "prompt --session harbor -m 'Status again?'"
    Then the exit code is 1
    When isaac is run with "turns list"
    Then the stdout does not contain "harbor"
    When isaac is run with "turns list --all"
    Then the stdout matches:
      | Status\? |
      | ok        |
      | Status again\? |
      | error     |

  Scenario: a turn left running by a crash runs once after restart
    The record says running, but no process holds the turn and no session
    marker exists — the process died between claiming it and starting it.
    Given the isaac EDN file "turns/tide-9.edn" exists with:
      | path       | value                |
      | id         | tide-9               |
      | session    | harbor               |
      | input      | Leave harbor         |
      | state      | :running             |
      | created-at | 2026-04-21T09:59:00Z |
    And the following model responses are queued:
      | type | content      | model |
      | text | Setting sail | echo  |
      | text | Sailed twice | echo  |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    And the turn queue ticks at "2026-04-21T10:00:01Z"
    Then session "harbor" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Leave harbor    |
      | message | assistant    | Setting sail    |
    And session "harbor" has transcript not matching:
      | type    | message.role | message.content |
      | message | assistant    | Sailed twice    |
    When isaac is run with "turns list --all"
    Then the stdout matches:
      | tide-9   |
      | harbor   |
      | finished |
      | ok       |

  Scenario: merged waiting requests keep their own ids and share the turn's outcome
    Given the following sessions exist:
      | name |
      | dm   |
    And the LLM response is delayed by 2 seconds
    And the following model responses are queued:
      | model | type | content        |
      | echo  | text | Answered one.  |
      | echo  | text | Both answered. |
    When the user sends "one" on session "dm" without waiting via memory comm
    And the user sends "two" on session "dm" with coalesce key "t1" without waiting via memory comm
    And the user sends "three" on session "dm" with coalesce key "t1" without waiting via memory comm
    And the turns on session "dm" finish
    And isaac is run with "turns list --all"
    Then the stdout matches:
      | one        |
      | two        |
      | three      |
      | finished   |
      | ok         |
      | merged-into |

  Scenario: a dropped turn stays listed as dropped
    Given the following sessions exist:
      | name |
      | dm   |
    And the LLM response is delayed by 2 seconds
    And the following model responses are queued:
      | model | type | content       |
      | echo  | text | Answered one. |
      | echo  | text | Never seen.   |
    When the user sends "one" on session "dm" without waiting via memory comm
    And the user sends "two" on session "dm" without waiting via memory comm
    And isaac is run with "turns list"
    Then the stdout matches:
      | waiting: [a-z0-9]+ |
    When isaac is run with "turns drop #held-id"
    Then the stdout contains "dropped"
    When the turns on session "dm" finish
    And isaac is run with "turns list --all"
    Then the stdout matches:
      | two      |
      | finished |
      | dropped  |

  Scenario: a queued turn runs on the server's own tick — nobody ticks it by hand (isaac-2lc4)
    Boots the real runner (scheduler + components) the way `isaac server`
    does. The queue ticks every 10 seconds; 15 covers one real tick.
    Given the Isaac runner is started
    And the following model responses are queued:
      | type | content      | model |
      | text | Setting sail | echo  |
    When isaac is run with "prompt --queue --session harbor -m 'Leave harbor'"
    Then within 15 seconds session "harbor" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Setting sail    |

  @wip
  Scenario: queued turns on different sessions run side by side (isaac-e9jl)
    One long turn must not stall the fleet. The queue tick starts each turn
    it claims and moves on; only a turn's own session waits for it.
    Given the Isaac runner is started
    And the following sessions exist:
      | name  |
      | jetty |
    And the following model responses are queued:
      | type | content      | model | wait |
      | text | Setting sail | echo  | true |
      | text | Tied off     | echo  |      |
    When isaac is run with "prompt --queue --session harbor -m 'Leave harbor'"
    Then within 15 seconds session "harbor" is waiting on the model
    When isaac is run with "prompt --queue --session jetty -m 'Come alongside'"
    Then within 15 seconds session "jetty" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Tied off        |
    When the model releases session "harbor"
    Then within 15 seconds session "harbor" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Setting sail    |
