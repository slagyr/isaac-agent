Feature: Compaction request shaping — low effort, a size cap, and one retry at half size
  A compaction summary is extraction, not reasoning. Isaac sends it at a
  fixed low effort (compaction.effort, default 2, any compaction policy
  layer), never larger than compaction.max-request-tokens (default 32k)
  regardless of the model window, and when a summary request is dropped by
  the provider it retries once at half size before counting a failure.
  Background: the 2026-09-04 zanebot stall — 48k-token summaries at effort 7
  went silent on chatgpt for 15 minutes apiece, five strikes disabled
  compaction, the turn ran to context exhaustion.

  Background:
    Given an Isaac root at "target/test-state"
    Given config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |

  Scenario: compaction summarizes at a fixed low effort while the reply keeps the session's effort
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 200        |
    And the following sessions exist:
      | name       | last-input-tokens | compaction.head | effort | #comment                       |
      | log-keeper | 165               | 0.1             | 7      | last provider tokens over line |
    And session "log-keeper" has transcript:
      | type    | message.role | message.content                                                     |
      | message | user         | Log the watch changes for the Marigold's third week out of port      |
      | message | assistant    | Cordelia took the middle watch; Joe and Oscar split the morning      |
    And the following model responses are queued:
      | type | content               | model      |
      | text | Summary of the watch  | test-model |
      | text | Logged.               | test-model |
    When the user sends "and the fourth week?" on session "log-keeper"
    Then the compaction request matches:
      | key    | value |
      | effort | 2     |
    And the last LLM request matches:
      | key    | value |
      | effort | 7     |

  Scenario: a crew can set its own compaction effort
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 200        |
    And the isaac EDN file "config/crew/purser.edn" exists with:
      | path              | value                  |
      | model             | local                  |
      | soul              | You keep the accounts. |
      | compaction.effort | 5                      |
    And the following sessions exist:
      | name   | crew   | last-input-tokens | compaction.head | #comment                       |
      | ledger | purser | 165               | 0.1             | last provider tokens over line |
    And session "ledger" has transcript:
      | type    | message.role | message.content                                   |
      | message | user         | Reconcile the galley stores against the manifest  |
      | message | assistant    | Flour and salt match; the coffee count is short   |
    And the following model responses are queued:
      | type | content                | model      |
      | text | Summary of the ledger  | test-model |
      | text | Reconciled.            | test-model |
    When the user sends "and the water casks?" on session "ledger"
    Then the compaction request matches:
      | key    | value |
      | effort | 5     |

  Scenario: a history under the window but over the request cap is compacted in chunks
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path                          | value      |
      | model                         | test-model |
      | provider                      | grover     |
      | context-window                | 800        |
      | compaction.max-request-tokens | 670        |
    And the following sessions exist:
      | name       | last-input-tokens | compaction.head | #comment                       |
      | log-keeper | 700               | 0.1             | last provider tokens over line |
    And session "log-keeper" has transcript:
      | type    | message.role | message.content                                                                  |
      | message | user         | Week one: the Marigold cleared port under Cordelia with stores for ninety days   |
      | message | assistant    | Logged week one with the manifest, the watch bill, and the starcore trim figures |
      | message | user         | Week two: Joe rerouted the quantum-anvil coolant after the aft junction fouled   |
      | message | assistant    | Logged week two with the coolant reroute, the junction inspection, and the fix   |
      | message | user         | Week three: Oscar recalibrated skybeam after the solar flare scrambled its lock  |
      | message | assistant    | Logged week three with the recalibration, the flare timing, and the new offsets  |
    And the following model responses are queued:
      | type | content                    | model      |
      | text | Summary of week one        | test-model |
      | text | Summary of week two        | test-model |
      | text | Summary of week three      | test-model |
      | text | Merged log of three weeks  | test-model |
      | text | Logged.                    | test-model |
    When the user sends "and week four?" on session "log-keeper"
    Then the log has entries matching:
      | level | event                       | chunks |
      | :info | :session/compaction-chunked | 3      |
    And session "log-keeper" has chronicle matching:
      | type       | summary                   |
      | compaction | Merged log of three weeks |

  Scenario: a dropped summary request is retried at half size before it counts as a failure
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 200        |
    And the following sessions exist:
      | name       | last-input-tokens | compaction.head | #comment                       |
      | log-keeper | 165               | 0.1             | last provider tokens over line |
    And session "log-keeper" has transcript:
      | type    | message.role | message.content                                                                  |
      | message | user         | Week one: the Marigold cleared port under Cordelia with stores for ninety days   |
      | message | assistant    | Logged week one with the manifest, the watch bill, and the starcore trim figures |
      | message | user         | Week two: Joe rerouted the quantum-anvil coolant after the aft junction fouled   |
      | message | assistant    | Logged week two with the coolant reroute, the junction inspection, and the fix   |
    And the following model responses are queued:
      | type  | content                   | model      |
      | error | closed                    | test-model |
      | text  | Summary of week one       | test-model |
      | text  | Summary of week two       | test-model |
      | text  | Merged log of two weeks   | test-model |
      | text  | Logged.                   | test-model |
    When the user sends "and week three?" on session "log-keeper"
    Then the log has entries matching:
      | level | event                           | attempt |
      | :info | :session/compaction-chunk-retry | 1       |
    And the following sessions match:
      | name       | compaction.consecutive-failures |
      | log-keeper | 0                               |
    And session "log-keeper" has chronicle matching:
      | type       | summary                 |
      | compaction | Merged log of two weeks |
