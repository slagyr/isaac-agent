Feature: Cycle timing — every step between a tool batch and the next request is measured (isaac-vfg8)
  After a tool batch, isaac persists the pair, checks for compaction, and
  builds the next request. On zanebot that gap was a median 2.3s with nothing
  logged inside it. Each step logs its own elapsed time at debug level.

  Background:
    Given default Grover setup
    And the built-in tools are registered
    And the crew "main" allows tools: "exec/run"

  Scenario: one tool batch logs the elapsed time of each step before the next request
    Given the following sessions exist:
      | name |
      | helm |
    And the following model responses are queued:
      | type      | tool_call | arguments              | content | model |
      | tool_call | exec__run | {"command": "echo hi"} |         | echo  |
      | text      |           |                        | done    | echo  |
    When the user sends "run it" on session "helm"
    Then the log has entries matching:
      | event                    | caller  | entries | bytes | elapsed-ms |
      | :tool/call-persisted     |         |         |       | #*         |
      | :tool/result-persisted   |         |         |       | #*         |
      | :turn/followup-built     |         |         |       | #*         |
      | :turn/after-tools        |         |         |       | #*         |
    And the log has no entries matching:
      | event                    | caller  |
      | :session/transcript-read |         |
      | :session/token-estimate  | :before |
      | :session/token-estimate  | :check  |
      | :session/token-estimate  | :after  |

  Scenario: the prompt build reports its own elapsed time (isaac-3uy9)
    Given the following sessions exist:
      | name   |
      | tiller |
    And the following model responses are queued:
      | type      | tool_call | arguments              | content | model |
      | tool_call | exec__run | {"command": "echo hi"} |         | echo  |
      | text      |           |                        | done    | echo  |
    When the user sends "run it" on session "tiller"
    Then the log has entries matching:
      | event               | build-ms | messages-count |
      | :turn/request-built | #*       | #*             |

  Scenario: the compaction check reports where its own time went (isaac-h8o9)
    The check costs ~715ms on zanebot and does not scale with transcript size
    (208 vs 537 entries land in the same band; 6 vs 1005 entries differ by 1.1%).
    :gauge is a token tally, not a measure of work, so the check records the
    entry count and byte size it actually walked, plus a breakdown of its own
    steps — the point is to name the step that holds the fixed cost instead of
    guessing at it again.
    Given the following sessions exist:
      | name    |
      | capstan |
    And the following model responses are queued:
      | type      | tool_call | arguments              | content | model |
      | tool_call | exec__run | {"command": "echo hi"} |         | echo  |
      | text      |           |                        | done    | echo  |
    When the user sends "run it" on session "capstan"
    Then the log has entries matching:
      | event                     | entry-count | transcript-bytes | entry-ms | transcript-ms | gauge-ms | plan-ms | elapsed-ms |
      | :session/compaction-check | #*          | #*               | #*       | #*            | #*       | #*      | #*         |
