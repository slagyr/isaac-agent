Feature: Checkpoints inside the turn
  A hail continuation is expensive (a fresh turn re-orients for 20–40 cycles)
  and the cycle cap was the only thing making a worker save its work. A
  crew-layered :cycle {:checkpoint-every N} makes the drive append a nudge to
  the request after every Nth cycle — same mechanism and register as the
  wrap-up nudge — so saving happens inside the turn. It costs no cycle,
  is off unless configured (chat crews are never told to save their work),
  is logged and persisted as a transcript marker, and its text is data:
  the drive's default assumes nothing about git, tests or beans; crews and
  bands may replace it (isaac-tic5).

  Background:
    Given default Grover setup
    And the built-in tools are registered

  @wip
  Scenario: the checkpoint nudge rides the request after every Nth cycle
    Given the isaac EDN file "config/crew/oscar.edn" exists with:
      | path                   | value  |
      | model                  | grover |
      | cycle.limit            | 10     |
      | cycle.checkpoint-every | 2      |
    And the crew "oscar" allows tools: "exec/run"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | type      | tool_call | arguments           | content | model |
      | tool_call | exec__run | {"command": "true"} |         | echo  |
      | tool_call | exec__run | {"command": "true"} |         | echo  |
      | tool_call | exec__run | {"command": "true"} |         | echo  |
      | text      |           |                     | done    | echo  |
    When the user sends "count the cans" on session "trash-can"
    Then LLM request 3 matches:
      | key                  | value                                        |
      | messages[-1].role    | user                                         |
      | messages[-1].content | contains "Checkpoint: save work in progress" |
    And LLM request 2 matches:
      | key               | value |
      | messages[-1].role | tool  |
    And LLM request 4 matches:
      | key               | value |
      | messages[-1].role | tool  |
    And the log has entries matching:
      | level | event                   | session   | cycle |
      | :info | :turn/checkpoint-nudged | trash-can | 2     |
    And session "trash-can" has transcript matching:
      | type       | cycle |
      | checkpoint | 2     |

  @wip
  Scenario: a crew's checkpoint-prompt replaces the default nudge text
    Given the isaac EDN file "config/crew/oscar.edn" exists with:
      | path                    | value                                          |
      | model                   | grover                                         |
      | cycle.limit             | 10                                             |
      | cycle.checkpoint-every  | 1                                              |
      | cycle.checkpoint-prompt | Oscar, put the lid back on and note the count. |
    And the crew "oscar" allows tools: "exec/run"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | type      | tool_call | arguments           | content | model |
      | tool_call | exec__run | {"command": "true"} |         | echo  |
      | text      |           |                     | done    | echo  |
    When the user sends "count the cans" on session "trash-can"
    Then the last LLM request matches:
      | key                  | value                                          |
      | messages[-1].role    | user                                           |
      | messages[-1].content | Oscar, put the lid back on and note the count. |
    And the last LLM request does not contain "save work in progress"

  @wip
  Scenario: no checkpoint-every means no nudge, ever
    Given the isaac EDN file "config/crew/oscar.edn" exists with:
      | path        | value  |
      | model       | grover |
      | cycle.limit | 10     |
    And the crew "oscar" allows tools: "exec/run"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | type      | tool_call | arguments           | content | model |
      | tool_call | exec__run | {"command": "true"} |         | echo  |
      | tool_call | exec__run | {"command": "true"} |         | echo  |
      | text      |           |                     | done    | echo  |
    When the user sends "count the cans" on session "trash-can"
    Then the last LLM request does not contain "Checkpoint"
    And the log has no entries matching:
      | event                   |
      | :turn/checkpoint-nudged |
