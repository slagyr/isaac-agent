Feature: Inherited attribution — delegated and carried-over turns keep their owner
  A turn records :from, who started it, and :for, the outside party it is
  done for (isaac-v403). Two rules keep that ownership from being lost.

  Delegation. A turn submitted from inside a running turn is from the crew
  member running that turn, {:kind :crew :id …}, and for whoever the
  running turn is for. A running turn that is for nobody starts turns that
  are for nobody. The submitter cannot override either field: a tool
  cannot claim to act for someone else.

  Carry-over. A continuation (isaac-xpkf) and a resumed turn (isaac-ziqg)
  are the same work under a new record. The new record keeps the :from
  and :for of the record it carries on from.

  Fixture: isaac.relay.pigeon contributes the tool pigeon__send. It calls
  isaac.agent.turn.submit/submit! for the session and input it is given,
  with the id it is given. Called with a "for" argument it also passes
  :from {:kind :cli} and :for {:kind :contact :id <for>}, to try to
  override the rule.

  Tracked by isaac-4615.

  Background:
    Given default Grover setup
    And the built-in tools are registered
    And the isaac EDN file "config/isaac.edn" exists with:
      | path    | value                                                            |
      | modules | {:isaac.relay.pigeon {:local/root "modules/isaac.relay.pigeon"}} |
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path  | value |
      | model | echo  |
    And the isaac EDN file "config/crew/lookout.edn" exists with:
      | path  | value |
      | model | echo  |
    And the crew "ketch" allows tools: "pigeon/send"
    And the following sessions exist:
      | name       | crew    |
      | mooring    | ketch   |
      | crows-nest | lookout |

  @wip
  Scenario: a turn a crew starts from inside a turn is from the crew and for the original party
    Given the following model responses are queued:
      | type      | tool_call    | arguments                                                       | content | model |
      | tool_call | pigeon__send | {"session":"crows-nest","input":"Watch the tide","id":"gull-2"} |         | echo  |
      | text      |              |                                                                 | Sent.   | echo  |
      | text      |              |                                                                 | Aye.    | echo  |
    When a turn is submitted with:
      | key                | value        |
      | id                 | tide-9       |
      | input              | Leave harbor |
      | session            | mooring      |
      | from.kind          | handle       |
      | from.comm          | logbook      |
      | from.id            | cordelia-7   |
      | from.authenticated | true         |
    And the turn queue ticks at "2026-10-11T09:15:00Z"
    And isaac is run with "turns show gull-2"
    Then the stdout matches:
      | pattern                   |
      | (?m)^session: crows-nest$ |
      | from.kind: crew           |
      | (?m)^from\.id: ketch$     |
      | for.kind: handle          |
      | for.comm: logbook         |
      | (?m)^for\.id: cordelia-7$ |
      | for.authenticated: true   |

  @wip
  Scenario: a turn started from inside a turn that is for nobody is for nobody
    Given the following model responses are queued:
      | type      | tool_call    | arguments                                                       | content | model |
      | tool_call | pigeon__send | {"session":"crows-nest","input":"Watch the tide","id":"gull-2"} |         | echo  |
      | text      |              |                                                                 | Sent.   | echo  |
      | text      |              |                                                                 | Aye.    | echo  |
    When a turn with input "Leave harbor" is submitted with frequencies:
      | key     | value   |
      | session | mooring |
    And the turn queue ticks at "2026-10-11T09:15:00Z"
    And isaac is run with "turns show gull-2"
    Then the stdout matches:
      | pattern               |
      | from.kind: crew       |
      | (?m)^from\.id: ketch$ |
    And the stdout does not contain "for."

  @wip
  Scenario: a tool cannot claim to act for someone else
    Given the following model responses are queued:
      | type      | tool_call    | arguments                                                                          | content | model |
      | tool_call | pigeon__send | {"session":"crows-nest","input":"Watch the tide","id":"gull-2","for":"hieronymus"} |         | echo  |
      | text      |              |                                                                                    | Sent.   | echo  |
      | text      |              |                                                                                    | Aye.    | echo  |
    When a turn is submitted with:
      | key       | value        |
      | id        | tide-9       |
      | input     | Leave harbor |
      | session   | mooring      |
      | from.kind | handle       |
      | from.comm | logbook      |
      | from.id   | cordelia-7   |
    And the turn queue ticks at "2026-10-11T09:15:00Z"
    And isaac is run with "turns show gull-2"
    Then the stdout matches:
      | pattern                   |
      | from.kind: crew           |
      | (?m)^from\.id: ketch$     |
      | for.kind: handle          |
      | (?m)^for\.id: cordelia-7$ |
    And the stdout does not contain "hieronymus"

  @wip
  Scenario: a continuation keeps who the turn is from and for
    Given the memory comm answers :wrap-up on exhaustion
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path        | value  |
      | model       | grover |
      | cycle.limit | 1      |
    And the crew "oscar" allows tools: "exec/run"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | type      | tool_call | arguments                      | content                                | model |
      | tool_call | exec__run | {"command": "true"}            |                                        | echo  |
      | tool_call | exec__run | {"command": "echo checkpoint"} |                                        | echo  |
      | text      |           |                                | Checkpoint committed; next: count lids | echo  |
      | text      |           |                                | Lids counted; all done.                | echo  |
    When the user sends "count the cans" on session "trash-can" via memory comm as handle "logbook/cordelia-7"
    Then the log has entries matching:
      | level | event           | session   | continuation |
      | :info | :turn/continued | trash-can | 1            |
    And the latest turn on session "trash-can" matches:
      | key                 | value      |
      | origin.continuation | 1          |
      | from.kind           | handle     |
      | from.comm           | logbook    |
      | from.id             | cordelia-7 |
      | for.kind            | handle     |
      | for.id              | cordelia-7 |

  @wip
  Scenario: a resumed turn keeps who the turn is from and for
    Given the following sessions exist:
      | name   |
      | harbor |
    And session "harbor" has transcript:
      | type    | message.role | message.content |
      | message | user         | Daily check     |
    And the isaac EDN file "turns/t-orig.edn" exists with:
      | path       | value                                           |
      | id         | t-orig                                          |
      | session    | harbor                                          |
      | input      | Daily check                                     |
      | origin     | {:kind :comm :name "logbook"}                   |
      | from       | {:kind :crew :id "ketch"}                       |
      | for        | {:kind :handle :comm :logbook :id "cordelia-7"} |
      | state      | :running                                        |
      | created-at | 2026-04-21T09:59:00Z                            |
    And the isaac EDN file "sessions/turns/harbor.edn" exists with:
      | path       | value                |
      | source     | :comm                |
      | started-at | 2026-04-21T09:59:30Z |
    When interrupted turns are resumed at "2026-04-21T10:00:00Z"
    Then the latest turn on session "harbor" matches:
      | key       | value      |
      | resumes   | t-orig     |
      | from.kind | crew       |
      | from.id   | ketch      |
      | for.kind  | handle     |
      | for.id    | cordelia-7 |
