Feature: Cancel Aborts In-Flight Turn Work
  When a turn is cancelled, in-flight work — LLM streams, tool
  executions, slash command handlers — stops within a bounded
  window rather than waiting for the natural tool-loop to exhaust
  itself. The observable contract is that ESC takes effect promptly.

  Per the cooperative-cancellation design: cancel stops *further*
  work, not *current* work. Tools run to completion; cancel takes
  effect at the next tool-loop iteration. Tools that can safely
  abort mid-stride (HTTP requests, LLM SSE streams) opt in.

  Background:
    Given default Grover setup
    And the built-in tools are registered
    And the crew "main" allows tools: "exec"
    And the following sessions exist:
      | name        |
      | cancel-test |

  Scenario: cancel between tool-loop iterations skips the next chat call
    Given the following model responses are queued:
      | type      | tool_call | arguments                  | content              |
      | tool_call | exec      | {"command": "sleep 0.05"}  |                      |
      | text      |           |                            | Should never appear  |
    When the user sends "do stuff" on session "cancel-test"
    And the turn is cancelled on session "cancel-test" after 1 tool call
    Then the turn result is "cancelled"
    And session "cancel-test" has transcript not matching:
      | type    | message.content     |
      | message | Should never appear |

  Scenario: session remains usable after a cancel mid-loop
    Given the following model responses are queued:
      | type      | tool_call | arguments                  | content              |
      | tool_call | exec      | {"command": "sleep 0.05"}  |                      |
      | text      |           |                            | Should never appear  |
    When the user sends "do stuff" on session "cancel-test"
    And the turn is cancelled on session "cancel-test" after 1 tool call
    Then the turn result is "cancelled"
    Given the following model responses are queued:
      | type | content     | model |
      | text | Still here! | echo  |
    When the user sends "you ok?" on session "cancel-test"
    Then session "cancel-test" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Still here!     |
