Feature: Exhausted turns — every turn says how it ended, and the Comm decides what exhaustion means (isaac-ntt6)
  A turn's result carries :ended-by on every path: :reply | :cycle-limit |
  :cancelled | :error | :context-exhausted. It is logged as :turn/ended and
  handed to comms on turn end. When the cycle budget runs out with tools
  still pending, the loop asks the Comm's on-exhausted hook what to do:
  :stop (default — today's summary cycle without tools, for attended
  origins) or :wrap-up (one final cycle WITH tools and a checkpoint nudge,
  then a tool-less note; for unattended origins such as hail). An exhausted
  turn that ends with empty content is never a success.
  Decisions (2026-09-08, Micah): the loop invokes the abstraction and never
  knows the origin; the cycle budget key is :cycle-limit (clean cutover,
  built-in default 100); the dispatcher may override the limit on the charge.

  Background:
    Given default Grover setup
    And the built-in tools are registered
    And the crew "main" allows tools: "exec/run"

  Scenario: a plain reply ends with :reply and says so in the log and to the comm
    Given the following sessions exist:
      | name    |
      | on-deck |
    And the following model responses are queued:
      | type | content   | model |
      | text | All quiet | echo  |
    When the user sends "status?" on session "on-deck" via memory comm
    Then the memory comm has events matching:
      | event    | result.ended-by |
      | turn-end | :reply          |
    And the log has entries matching:
      | level | event       | session | ended-by |
      | :info | :turn/ended | on-deck | :reply   |

  Scenario: a cancelled turn ends with :cancelled
    Given a blocking tool "test__anchor" is registered that returns cancelled once the turn is cancelled
    And the following sessions exist:
      | name   |
      | cancel |
    And the following model responses are queued:
      | type      | tool_call    | arguments | content | model |
      | tool_call | test__anchor | {}        |         | echo  |
      | text      |              |           | never   | echo  |
    And the turn is cancelled on session "cancel" after 1 tool call
    When the user sends "hold" on session "cancel" via memory comm
    Then the memory comm has events matching:
      | event    | result.ended-by |
      | turn-end | :cancelled      |
    And the log has entries matching:
      | level | event       | session | ended-by   |
      | :info | :turn/ended | cancel  | :cancelled |

  Scenario: the default policy at the cycle limit is the summary reply, marked :cycle-limit
    Given the isaac EDN file "config/crew/oscar.edn" exists with:
      | path        | value  |
      | model       | grover |
      | cycle-limit | 1      |
    And the crew "oscar" allows tools: "exec/run"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | type      | tool_call | arguments           | content                      | model |
      | tool_call | exec__run | {"command": "true"} |                              | echo  |
      | tool_call | exec__run | {"command": "true"} |                              | echo  |
      | text      |           |                     | Summary: one can counted     | echo  |
    When the user sends "count the cans" on session "trash-can" via memory comm
    Then session "trash-can" has transcript matching:
      | type     | message.role | message.content          |
      | toolCall | assistant    | #*                       |
      | message  | toolResult   | #*                       |
      | message  | assistant    | Summary: one can counted |
    And the last LLM request matches:
      | key   | value |
      | tools | []    |
    And the memory comm has events matching:
      | event    | result.ended-by |
      | turn-end | :cycle-limit    |
    And the log has entries matching:
      | level | event       | session   | ended-by     | exhaustion |
      | :info | :turn/ended | trash-can | :cycle-limit | :stopped   |

  Scenario: a comm that answers :wrap-up gets one final cycle with tools, then a tool-less note
    Given the memory comm answers :wrap-up on exhaustion
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path        | value  |
      | model       | grover |
      | cycle-limit | 1      |
    And the crew "oscar" allows tools: "exec/run"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | type      | tool_call | arguments                      | content                                  | model |
      | tool_call | exec__run | {"command": "true"}            |                                          | echo  |
      | tool_call | exec__run | {"command": "echo checkpoint"} |                                          | echo  |
      | text      |           |                                | Checkpoint committed; next: count lids   | echo  |
    When the user sends "count the cans" on session "trash-can" via memory comm
    Then session "trash-can" has transcript matching:
      | type     | message.role | message.content                        |
      | toolCall | assistant    | #*                                     |
      | message  | toolResult   | #*                                     |
      | toolCall | assistant    | #"(?s).*echo checkpoint.*"             |
      | message  | toolResult   | #"(?s).*checkpoint.*"                  |
      | message  | assistant    | Checkpoint committed; next: count lids |
    And the memory comm has events matching:
      | event    | result.ended-by | result.exhaustion |
      | turn-end | :cycle-limit    | :wrapped-up       |
    And the log has entries matching:
      | level | event       | session   | ended-by     | exhaustion  |
      | :info | :turn/ended | trash-can | :cycle-limit | :wrapped-up |

  Scenario: an empty note after wrap-up fails the turn instead of completing it
    Given the memory comm answers :wrap-up on exhaustion
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path        | value  |
      | model       | grover |
      | cycle-limit | 1      |
    And the crew "oscar" allows tools: "exec/run"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | type      | tool_call | arguments           | content | model |
      | tool_call | exec__run | {"command": "true"} |         | echo  |
      | text      |           |                     |         | echo  |
      | text      |           |                     |         | echo  |
    When the user sends "count the cans" on session "trash-can" via memory comm
    Then the memory comm has events matching:
      | event    | result.ended-by | result.error             |
      | turn-end | :error          | :empty-terminal-response |
    And the log has entries matching:
      | level  | event       | session   | ended-by | error                    |
      | :info  | :turn/ended | trash-can | :error   | :empty-terminal-response |

  @wip
  Scenario: the wrap-up note is persisted as the turn's final assistant message so the continuation can read it (isaac-wrapup-note)
    Field 2026-09-10 (isaac-mmod, isaac-work-2): three wrap-ups produced a
    note (:exhaustion :wrapped-up) and none of them appear in the transcript —
    the continuation turn's prompt is rebuilt from the transcript and never
    sees the done/next note it was supposed to start from.
    Given the memory comm answers :wrap-up on exhaustion
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path        | value  |
      | model       | grover |
      | cycle-limit | 1      |
    And the crew "oscar" allows tools: "exec/run"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | type      | tool_call | arguments           | content                                | model |
      | tool_call | exec__run | {"command": "true"} |                                        | echo  |
      | text      |           |                     | Done: counted one can. Next: count lids | echo  |
    When the user sends "count the cans" on session "trash-can" via memory comm
    Then session "trash-can" has transcript matching:
      | type     | message.role | message.content                         |
      | toolCall | assistant    | #*                                      |
      | message  | toolResult   | #*                                      |
      | message  | assistant    | Done: counted one can. Next: count lids |
    And the memory comm has events matching:
      | event    | result.ended-by | result.exhaustion |
      | turn-end | :cycle-limit    | :wrapped-up       |
