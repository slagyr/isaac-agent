@wip
Feature: Turn inspection — turns show and the turn__get tool
  Every turn record answers "what was asked, who asked, and how did it
  end". The submitter supplies an opaque :origin map that Agent stores
  and returns without interpreting (the CLI records {:kind :cli}; a
  submitter that names none gets {:kind :submit}). Finished records carry
  a :reason for error and cancelled outcomes, and created-at, started-at,
  and finished-at timestamps. `isaac turns show <id>` prints the whole
  record, origin fields as origin.<key>; the turn__get crew tool returns
  the same record as data. (isaac-d6pw)

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name   |
      | harbor |

  Scenario: turns show reports a finished turn in full
    Given the following model responses are queued:
      | type | content      | model |
      | text | Setting sail | echo  |
    When isaac is run with "prompt --queue --session harbor -m 'Leave harbor'"
    Then the stdout matches:
      | queued: #"[a-z0-9]+":turn-id |
    When the turn queue ticks at "2026-03-01T18:00:00"
    And isaac is run with "turns show #turn-id"
    Then the stdout matches:
      | #turn-id              |
      | input: Leave harbor   |
      | session: harbor       |
      | state: finished       |
      | outcome: ok           |
      | origin.kind: cli      |
      | created-at: \S+ |
      | started-at: \S+ |
      | finished-at: \S+ |
    And the exit code is 0

  Scenario: a failed turn shows its outcome and reason
    Given the following model responses are queued:
      | type       | status | message          | model |
      | http-error | 400    | lamp oil spilled | echo  |
    When isaac is run with "prompt --queue --session harbor -m 'Trim the lamp'"
    Then the stdout matches:
      | queued: #"[a-z0-9]+":turn-id |
    When the turn queue ticks at "2026-03-01T18:00:00"
    And isaac is run with "turns show #turn-id"
    Then the stdout matches:
      | state: finished                     |
      | outcome: error                      |
      | reason:.*lamp oil spilled |

  Scenario: a queued turn has no start time until it runs
    Given the following model responses are queued:
      | type | content      | model |
      | text | Setting sail | echo  |
    When isaac is run with "prompt --queue --session harbor -m 'Leave harbor'"
    Then the stdout matches:
      | queued: #"[a-z0-9]+":turn-id |
    When isaac is run with "turns show #turn-id"
    Then the stdout matches:
      | state: queued      |
      | created-at: \S+ |
    And the stdout does not contain "started-at: 2"
    When the turn queue ticks at "2026-03-01T18:00:00"
    And isaac is run with "turns show #turn-id"
    Then the stdout matches:
      | state: finished    |
      | started-at: \S+ |

  Scenario: a crew reads a turn with the turn__get tool
    Given the isaac EDN file "turns/tide-9.edn" exists with:
      | path        | value                |
      | id          | tide-9               |
      | session     | harbor               |
      | input       | Leave harbor         |
      | state       | :finished            |
      | outcome     | :ok                  |
      | origin      | {:kind :cli}         |
      | created-at  | 2026-03-01T17:59:00Z |
      | finished-at | 2026-03-01T18:00:00Z |
    And the crew "bartholomew" allows tools: "turn/get"
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | model | tool_call | arguments        |
      | echo  | turn__get | {"id": "tide-9"} |
      | model | type      | content          |
      | echo  | text      | It sailed.       |
    When the user sends "did the harbor turn finish?" on session "engine-room"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.content                                            |
      | message | toolResult   | #"(?s).*tide-9.*Leave harbor.*finished.*ok.*cli.*"          |

  Scenario: an unknown turn id fails clearly in the CLI and the tool
    When isaac is run with "turns show ghost-1"
    Then the stderr contains "turn not found: ghost-1"
    And the exit code is 1
    Given the crew "bartholomew" allows tools: "turn/get"
    And the isaac EDN file "config/crew/bartholomew.edn" exists with:
      | path  | value  |
      | model | grover |
    And the following sessions exist:
      | name        | crew        |
      | engine-room | bartholomew |
    And the following model responses are queued:
      | model | tool_call | arguments         |
      | echo  | turn__get | {"id": "ghost-1"} |
      | model | type      | content           |
      | echo  | text      | No such turn.     |
    When the user sends "look up ghost-1" on session "engine-room"
    Then session "engine-room" has transcript matching:
      | type    | message.role | message.isError | message.content                   |
      | message | toolResult   | true            | #"(?s).*turn not found: ghost-1.*" |
