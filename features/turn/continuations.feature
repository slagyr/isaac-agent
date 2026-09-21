Feature: Continuations — the drive re-drives a wrapped-up turn within a cycle.continuations budget (isaac-xpkf)
  A wrap-up is a checkpoint, not an ending. When the cycle budget runs out
  and the comm answers :wrap-up, the drive runs the final cycle and the
  tool-less note (isaac-ntt6, isaac-x0cw) and then hands the session a
  CONTINUATION: a fresh turn on the same session, parked on the durable turn
  queue like a resumed turn (isaac-yxch), whose input tells the model to carry
  on from its own note. The budget is :cycle {:continuations n} — crew,
  overlaid by the charge (hail band, cron) exactly like :limit — built-in
  default 2. Each continuation is one more turn; when the count reaches the
  budget the drive logs :turn/continuations-exhausted, posts attention, and
  sends the comm a bulletin. Hail used to own this loop (continue-delivery! /
  wrap-up-delivery!); it is turn orchestration and belongs here. A comm that
  answers :stop (attended origins) never continues. The drive still never
  knows the origin — it reads the policy answer and the cycle map.
  Decision (Micah, 2026-09-21, isaac-9azm): hail's responsibility ends when
  a turn starts; the drive owns continuations.

  Background:
    Given default Grover setup
    And the built-in tools are registered

  Scenario: a wrapped-up turn is re-driven as a continuation on the same session
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
    When the user sends "count the cans" on session "trash-can" via memory comm
    Then the memory comm has events matching:
      | event    | result.ended-by | result.exhaustion |
      | turn-end | :cycle-limit    | :wrapped-up       |
    And the log has entries matching:
      | level | event           | session   | continuation | budget |
      | :info | :turn/continued | trash-can | 1            | 2      |
    And the log has entries matching:
      | level | event            | session   |
      | :info | :turn.queue/held | trash-can |
    When isaac is run with "turns list"
    Then the stdout matches:
      | session   | state |
      | trash-can | held  |
    When the turn queue ticks at "2026-04-21T10:00:05Z"
    Then session "trash-can" has transcript matching:
      | type     | message.role | message.content                        | #comment                      |
      | message  | user         | count the cans                         |                               |
      | toolCall | assistant    | #*                                     | cycle 1                       |
      | message  | toolResult   | #*                                     |                               |
      | toolCall | assistant    | #"(?s).*echo checkpoint.*"             | the wrap-up cycle             |
      | message  | toolResult   | #"(?s).*checkpoint.*"                  |                               |
      | message  | assistant    | Checkpoint committed; next: count lids | the wrap-up note (isaac-x0cw) |
      | message  | user         | #"(?s).*continue.*"                    | the continuation's input      |
      | message  | assistant    | Lids counted; all done.                | continuation 1 — a fresh turn |
    And the log has entries matching:
      | level | event       | session   | ended-by |
      | :info | :turn/ended | trash-can | :reply   |

  Scenario: the continuation budget exhausts with attention and a bulletin
    Given the memory comm answers :wrap-up on exhaustion
    And config:
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path                | value  |
      | model               | grover |
      | cycle.limit         | 1      |
      | cycle.continuations | 1      |
    And the crew "oscar" allows tools: "exec/run"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | type      | tool_call | arguments           | content                    | model |
      | tool_call | exec__run | {"command": "true"} |                            | echo  |
      | tool_call | exec__run | {"command": "true"} |                            | echo  |
      | text      |           |                     | Checkpoint; still not done | echo  |
      | tool_call | exec__run | {"command": "true"} |                            | echo  |
      | tool_call | exec__run | {"command": "true"} |                            | echo  |
      | text      |           |                     | Checkpoint; still not done | echo  |
    When the user sends "count the cans" on session "trash-can" via memory comm
    And the turn queue ticks at "2026-04-21T10:00:05Z"
    Then the log has entries matching:
      | level  | event                         | session   | continuation | budget |
      | :error | :turn/continuations-exhausted | trash-can | 1            | 1      |
    And the memory comm has events matching:
      | event    | text                                |
      | bulletin | #"(?s).*trash-can.*continuations.*" |
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                    |
      | comm    | :discord                                 |
      | target  | boiler-room                              |
      | content | contains "trash-can" and "continuations" |
    When isaac is run with "turns list"
    Then the stdout does not contain "trash-can"

  Scenario: the default continuation budget is 2
    A crew that sets no :continuations gets two continuations, then the
    drive gives up with attention. Continuations are a last resort;
    checkpoints inside the turn are the save point (isaac-tic5).
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
      | type      | tool_call | arguments           | content                 | model |
      | tool_call | exec__run | {"command": "true"} |                         | echo  |
      | tool_call | exec__run | {"command": "true"} |                         | echo  |
      | text      |           |                     | Checkpoint; next: seal. | echo  |
      | tool_call | exec__run | {"command": "true"} |                         | echo  |
      | tool_call | exec__run | {"command": "true"} |                         | echo  |
      | text      |           |                     | Checkpoint; next: seal. | echo  |
      | tool_call | exec__run | {"command": "true"} |                         | echo  |
      | tool_call | exec__run | {"command": "true"} |                         | echo  |
      | text      |           |                     | Checkpoint; next: seal. | echo  |
    When the user sends "count the cans" on session "trash-can" via memory comm
    And the turn queue ticks at "2026-04-21T10:00:05Z"
    And the turn queue ticks at "2026-04-21T10:00:10Z"
    Then the log has entries matching:
      | level | event           | session   | continuation | budget |
      | :info | :turn/continued | trash-can | 1            | 2      |
      | :info | :turn/continued | trash-can | 2            | 2      |
    And the log has entries matching:
      | level  | event                         | session   | continuation | budget |
      | :error | :turn/continuations-exhausted | trash-can | 2            | 2      |

  Scenario: a comm that answers :stop never continues
    The attended default. The summary reply is the end of it; nothing is
    queued for the session.
    Given the isaac EDN file "config/crew/oscar.edn" exists with:
      | path        | value  |
      | model       | grover |
      | cycle.limit | 1      |
    And the crew "oscar" allows tools: "exec/run"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | type      | tool_call | arguments           | content                  | model |
      | tool_call | exec__run | {"command": "true"} |                          | echo  |
      | tool_call | exec__run | {"command": "true"} |                          | echo  |
      | text      |           |                     | Summary: one can counted | echo  |
    When the user sends "count the cans" on session "trash-can" via memory comm
    Then the log has entries matching:
      | level | event       | session   | ended-by     | exhaustion |
      | :info | :turn/ended | trash-can | :cycle-limit | :stopped   |
    When isaac is run with "turns list"
    Then the stdout does not contain "trash-can"
