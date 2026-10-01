Feature: Every tool call has a timeout (isaac-4g2k)
  The tool registry runs each call against a deadline. The default comes from
  `defaults.tools.timeout-ms` (60000 when unset); a crew may override it with
  `crew.<id>.tools.timeout-ms`; a tool may declare its own default (exec__run
  uses its `timeout` argument plus a margin). A call past its deadline returns
  an ordinary tool error the model can react to, logs :tool/timed-out, and the
  turn moves on; the stuck work is interrupted, or abandoned if it ignores the
  interrupt.

  The "stall" and "slow" tools are test fixtures: stall never returns; slow
  returns "done" after the given delay.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name    |
      | logbook |

  @wip
  Scenario: a call past the default timeout returns an error and the turn moves on
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                          | value |
      | defaults.tools.timeout-ms     | 200   |
    And a fixture tool "stall" that never returns is registered
    And the crew "main" allows tools: "stall"
    And the following model responses are queued:
      | type      | tool_call | arguments | content    | model |
      | tool_call | stall     | {}        |            | echo  |
      | text      |           |           | Moving on. | echo  |
    When the user sends "check the gauge" on session "logbook"
    Then the tool result is an error
    And the tool result contains "timed out after 200ms"
    And the log has entries matching:
      | level | event           | tool  |
      | warn  | :tool/timed-out | stall |
    And session "logbook" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | Moving on.      |

  @wip
  Scenario: a crew's timeout overrides the default
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                          | value |
      | defaults.tools.timeout-ms     | 200   |
      | crew.main.tools.timeout-ms    | 5000  |
    And a fixture tool "slow" that returns after 500ms is registered
    And the crew "main" allows tools: "slow"
    And the following model responses are queued:
      | type      | tool_call | arguments | content | model |
      | tool_call | slow      | {}        |         | echo  |
      | text      |           |           | Done.   | echo  |
    When the user sends "check the gauge" on session "logbook"
    Then the tool result is not an error
    And the tool result contains "done"

  @wip
  Scenario: exec__run's own timeout argument outlasts the default
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                          | value |
      | defaults.tools.timeout-ms     | 200   |
    And the crew "main" allows tools: "exec/run"
    And the built-in tools are registered
    And the following model responses are queued:
      | type      | tool_call | arguments                                          | content | model |
      | tool_call | exec__run | {"command":"sleep 1; echo finished","timeout":5000} |         | echo  |
      | text      |           |                                                    | Done.   | echo  |
    When the user sends "run the diagnostic" on session "logbook"
    Then the tool result is not an error
    And the tool result contains "finished"
