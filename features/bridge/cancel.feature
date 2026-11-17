Feature: Turn Cancellation
  The bridge can cancel a running turn. Any channel can trigger
  cancellation — CLI via Ctrl-C, ACP via session/cancel, etc.
  The bridge interrupts in-flight work (tool calls, LLM requests)
  and the session remains usable.

  Background:
    Given default Grover setup
    And the crew "main" allows tools: "exec/run"
    And the built-in tools are registered

  Scenario: cancel interrupts a running exec tool call
    Given the following sessions exist:
      | name        |
      | cancel-test |
    And the following model responses are queued:
      | tool_call | arguments               |
      | exec__run | {"command": "while :; do sleep 1; done"} |
    When the user sends "run it" on session "cancel-test"
    And the turn is cancelled on session "cancel-test"
    Then the turn result is "cancelled"

  Scenario: cancel interrupts a running LLM request
    Given the following sessions exist:
      | name        |
      | cancel-test |
    And the LLM response is delayed by 30 seconds
    When the user sends "think hard" on session "cancel-test"
    And the turn is cancelled on session "cancel-test"
    Then the turn result is "cancelled"
    And the log has entries matching:
      | level | event           |
      | :info | :chat/cancelled |
    And the log has 0 entries matching ":chat/stream-error"

  Scenario: session remains usable after cancel
    Given the following sessions exist:
      | name        |
      | cancel-test |
    And the following model responses are queued:
      | tool_call | arguments               |
      | exec__run | {"command": "while :; do sleep 1; done"} |
    When the user sends "run it" on session "cancel-test"
    And the turn is cancelled on session "cancel-test"
    Then the turn result is "cancelled"
    Given the following model responses are queued:
      | type | content     | model |
      | text | Still here! | echo  |
    When the user sends "you ok?" on session "cancel-test"
    Then session "cancel-test" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Still here!     |

  Scenario: a :cancelled stamp on the turn marker stops the running turn
    Given the following sessions exist:
      | name        |
      | cancel-test |
    And the following model responses are queued:
      | type | content | model | wait |
      | text | working | echo  | true |
    When the user sends "think hard" on session "cancel-test"
    And isaac is run with "sessions cancel cancel-test"
    Then the turn result is "cancelled"
    And no turn marker exists for session "cancel-test"

  Scenario: a cancel with no turn running does not cancel the next turn
    Given the following sessions exist:
      | name      |
      | idle-test |
    When the turn is cancelled on session "idle-test"
    Given the following model responses are queued:
      | type | content     | model |
      | text | Still here! | echo  |
    When the user sends "hello" on session "idle-test"
    Then session "idle-test" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Still here!     |
