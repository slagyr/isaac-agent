Feature: Async Compaction
  When a session's compaction strategy has async enabled,
  compaction runs in a background thread without blocking
  the active turn. A per-session lock coordinates transcript
  appends with the compaction splice.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/local.edn" exists with:
      | path | value |
      | model | test-model |
      | provider | grover |
      | context-window | 180 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | local |
      | soul | You are Atticus. |

  Scenario: async slinky compaction does not block the turn
    Given the following sessions exist:
      | name       | total-tokens | compaction.strategy | compaction.threshold | compaction.head | compaction.async |
      | async-test | 170         | slinky              | 0.8                 | 0.4             | true             |
    And session "async-test" has transcript:
      | type    | message.role | message.content | tokens |
      | message | user         | old topic       | 40     |
      | message | assistant    | old reply       | 40     |
      | message | user         | recent topic    | 40     |
      | message | assistant    | recent reply    | 50     |
    And the following model responses are queued:
      | type | content        | model      |
      | text | Tail summary   | test-model |
      | text | Fresh response | test-model |
    When the user sends "hello" on session "async-test"
    Then an async compaction for session "async-test" is in flight
    And session "async-test" has transcript matching:
      | type    | message.role | message.content | #comment                                     |
      | message | user         | old topic       | still here — compaction hasn't spliced yet    |
      | message | assistant    | old reply       |                                                |
      | message | user         | recent topic    |                                                |
      | message | assistant    | recent reply    |                                                |
      | message | user         | hello           | turn completed without waiting for compaction |
      | message | assistant    | Fresh response  |                                                |
    When the async compaction for session "async-test" completes
    Then session "async-test" has transcript matching:
      | type       | message.role | message.content | #comment                       |
      | compaction |              | Tail summary    | old tail replaced with summary |
      | message    | user         | recent topic    | head preserved                 |
      | message    | assistant    | recent reply    |                                |
      | message    | user         | hello           | turn entries survived splice   |
      | message    | assistant    | Fresh response  |                                |

  Scenario: second turn skips compaction when one is already in-flight
    Given the following sessions exist:
      | name      | total-tokens | compaction.strategy | compaction.threshold | compaction.head | compaction.async |
      | busy-test | 170         | slinky              | 0.8                 | 0.4             | true             |
    And session "busy-test" has transcript:
      | type    | message.role | message.content | tokens |
      | message | user         | old topic       | 40     |
      | message | assistant    | old reply       | 40     |
      | message | user         | recent topic    | 40     |
      | message | assistant    | recent reply    | 50     |
    And the following model responses are queued:
      | type | content        | model      |
      | text | Tail summary   | test-model |
      | text | First reply    | test-model |
      | text | Second reply   | test-model |
    When the user sends "first" on session "busy-test"
    Then an async compaction for session "busy-test" is in flight
    When the user sends "second" on session "busy-test"
    Then an async compaction for session "busy-test" is in flight
    When the async compaction for session "busy-test" completes
    Then session "busy-test" has transcript matching:
      | type       | message.role | message.content | #comment                           |
      | compaction |              | Tail summary    | only one compaction ran             |
      | message    | user         | recent topic    |                                    |
      | message    | assistant    | recent reply    |                                    |
      | message    | user         | first           |                                    |
      | message    | assistant    | First reply     |                                    |
      | message    | user         | second          | second turn didn't trigger another |
      | message    | assistant    | Second reply    |                                    |
