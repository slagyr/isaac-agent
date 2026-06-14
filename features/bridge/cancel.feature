Feature: Turn Cancellation
  The bridge can cancel a running turn. Any channel can trigger
  cancellation — CLI via Ctrl-C, ACP via session/cancel, etc.
  The bridge interrupts in-flight work (tool calls, LLM requests)
  and the session remains usable.

  Background:
    Given default Grover setup
    And the crew "main" allows tools: "exec"
    And the built-in tools are registered

  Scenario: cancel interrupts a running exec tool call
    Given the following sessions exist:
      | name        |
      | cancel-test |
    And the following model responses are queued:
      | tool_call | arguments               |
      | exec      | {"command": "sleep 30"} |
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

  Scenario: session remains usable after cancel
    Given the following sessions exist:
      | name        |
      | cancel-test |
    And the following model responses are queued:
      | tool_call | arguments               |
      | exec      | {"command": "sleep 30"} |
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
