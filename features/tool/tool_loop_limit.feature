Feature: Cycle-limit configuration
  The tool loop's cycle budget (built-in default 100) is crew-configurable
  as :cycle-limit — same layering as compaction config — and a dispatcher
  may override it on the charge (isaac-ntt6; formerly tool-loop-max).
  Needed so hail crews can carry bigger budgets than chat crews, and so the
  loop-limit behavior is testable without queuing 500 cycles. When the budget
  exhausts with tools still pending, the drive emits the loop-exhausted
  summary; the Comm's on-exhausted policy decides what happens next
  (isaac-ntt6; supersedes isaac-fgo0).

  Background:
    Given default Grover setup

  @wip
  Scenario: a crew-level cycle-limit caps the turn's tool cycles
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 32768      |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path          | value |
      | model         | local |
      | cycle-limit   | 1     |
    And the crew "oscar" allows tools: "exec/run"
    And the built-in tools are registered
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | tool_call | arguments           |
      | exec__run | {"command": "true"} |
      | exec__run | {"command": "true"} |
    When the user sends "count the cans" on session "trash-can"
    Then session "trash-can" has transcript matching:
      | type     | message.role | message.content            | #comment                                   |
      | message  | user         | count the cans             |                                            |
      | toolCall | assistant    | #*                         | cycle 1 executed                           |
      | message  | toolResult   | #*                         |                                            |
      | message  | assistant    | contains "tool loop limit" | cycle 2 never ran — summary/canned message |
