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
