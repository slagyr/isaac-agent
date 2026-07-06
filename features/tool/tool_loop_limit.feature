Feature: Tool-loop limit configuration
  The tool loop's cycle budget (:max-loops, default 100) becomes
  crew-configurable as :tool-loop-max — same layering as compaction config.
  Needed so hail crews can carry bigger budgets than chat crews, and so the
  loop-limit behavior is testable without queuing 100 cycles. When the budget
  exhausts with tools still pending, the drive already emits the
  loop-exhausted summary/canned message (turn.clj); a delivery-driven turn
  ending this way is re-queued as a continuation by the hail worker instead
  of completing — see isaac-hail delivery.feature. (isaac-5ru9)

  Background:
    Given default Grover setup

  @wip
  Scenario: a crew-level tool-loop-max caps the turn's tool cycles
    Given the isaac EDN file "config/crew/oscar.edn" exists with:
      | path          | value |
      | model         | local |
      | tool-loop-max | 1     |
    And the crew "oscar" allows tools: "exec"
    And the built-in tools are registered
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | tool_call | arguments           |
      | exec      | {"command": "true"} |
      | exec      | {"command": "true"} |
    When the user sends "count the cans" on session "trash-can"
    Then session "trash-can" has transcript matching:
      | type       | message.content    | #comment                                   |
      | message    | count the cans     |                                            |
      | toolCall   | #*                 | cycle 1 executed                           |
      | toolResult | #*                 |                                            |
      | message    | #"tool loop limit" | cycle 2 never ran — summary/canned message |
