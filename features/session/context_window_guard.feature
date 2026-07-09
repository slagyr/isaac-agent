Feature: Context-window guard when compaction cannot save the turn

  Background:
    Given an Isaac root at "target/test-state"
    Given config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 100        |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |

  Scenario: compaction disabled over the guard line defers without an LLM request
    Given the following sessions exist:
      | name   | total-tokens | compaction-disabled | compaction.consecutive-failures |
      | wedged | 99           | true                | 5                               |
    And session "wedged" has transcript:
      | type    | message.role | message.content |
      | message | user         | earlier prompt  |
      | message | assistant    | earlier reply   |
    And the following model responses are queued:
      | type | content           | model      |
      | text | should not be hit | test-model |
    When the user sends "one more" on session "wedged"
    Then the turn result is unavailable with retry-after-ms 300000 and reason context-exhausted
    And grover records zero provider requests

  Scenario: compaction enabled over the guard line compacts then the turn proceeds
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | context-window | 200        |
    And the following sessions exist:
      | name      | total-tokens | compaction.head |
      | huge-head | 620          | 0.1             |
    And session "huge-head" has transcript:
      | type    | message.role | message.content                                                              | tokens |
      | message | user         | block A oldest: planning notes about logging, tools, and the dispatch loop    | 60     |
      | message | assistant    | reply A: we agreed on output sinks, the compaction trigger, and tool dispatch | 60     |
      | message | user         | block B: more notes on retry behavior and the backoff between dispatch tries  | 60     |
      | message | assistant    | reply B: dispatcher retry is now idempotent with backoff between attempts     | 60     |
      | message | user         | latest question about what was finally decided across all of the above        | 61     |
    And the following model responses are queued:
      | type | content           | model      |
      | text | summary of A      | test-model |
      | text | here is my answer | test-model |
    When the user sends "go" on session "huge-head"
    Then session "huge-head" has transcript matching:
      | type       | message.role | message.content   | summary      |
      | compaction |              |                   | summary of A |
      | message    | assistant    | here is my answer |              |

  Scenario: compaction failure cap posts attention to the comm outbox
    Given the isaac EDN file "config/isaac.edn" exists with:
      | path                    | value       |
      | attention.notify.comm   | discord     |
      | attention.notify.target | boiler-room |
    And the following sessions exist:
      | name      | total-tokens | compaction.consecutive-failures |
      | giving-up | 95           | 5                               |
    And session "giving-up" has transcript:
      | type    | message.role | message.content |
      | message | user         | earlier prompt  |
      | message | assistant    | earlier reply   |
    And the following model responses are queued:
      | type  | content                 | model      |
      | error | context length exceeded | test-model |
      | text  | here is my answer       | test-model |
    When the user sends "next thing" on session "giving-up"
    Then the memory comm has events matching:
      | event               | reason             |
      | compaction-disabled | :too-many-failures |
    And session "giving-up" matches:
      | key                 | value |
      | compaction-disabled | true  |
    And the directory "comm/delivery/pending" has exactly 1 file
    And the only file in "comm/delivery/pending" EDN contains:
      | path    | value                                          |
      | comm    | discord                                        |
      | target  | boiler-room                                    |
      | content | contains "Compaction disabled" and "giving-up" |
