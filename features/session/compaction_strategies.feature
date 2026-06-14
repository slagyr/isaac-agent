Feature: Compaction Strategies
  Sessions define a compaction strategy that controls when and how
  transcript history is summarized. :rubberband is the default —
  fold everything when the window fills. :slinky folds the tail
  (oldest portion) and preserves a recent head intact, sized by the
  :head config (defaults to 30% of the context window).

  Background:
    Given an Isaac root at "target/test-state"

  Scenario: default compaction parameters are fixed percentages of context window
    Then the compaction defaults are:
      | context-window | threshold | head |
      | 100           | 0.8       | 0.3  |
      | 8192          | 0.8       | 0.3  |
      | 32768         | 0.8       | 0.3  |
      | 65536         | 0.8       | 0.3  |
      | 128000        | 0.8       | 0.3  |
      | 200000        | 0.8       | 0.3  |
      | 272000        | 0.8       | 0.3  |
      | 1048576       | 0.8       | 0.3  |

  Scenario: rubberband compacts entire transcript when threshold exceeded
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path | value |
      | model | test-model |
      | provider | grover |
      | context-window | 100 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | local |
      | soul | You are Atticus. |
    And the following sessions exist:
      | name    | total-tokens |
      | rb-test | 95          |
    And session "rb-test" has transcript:
      | type    | message.role | message.content            |
      | message | user         | Tell me about compaction   |
      | message | assistant    | It summarizes old messages |
    And the following model responses are queued:
      | type | content               | model      |
      | text | Summary of prior chat | test-model |
      | text | Here is my response   | test-model |
    When the user sends "hello" on session "rb-test"
    Then session "rb-test" has 6 transcript entries
    And session "rb-test" has 3 active transcript entries
    And session "rb-test" has transcript matching:
      | type    | message.role | message.content            |
      | message | user         | Tell me about compaction   |
      | message | assistant    | It summarizes old messages |
    And session "rb-test" has active transcript matching:
      | type       | message.role | message.content     | summary               |
      | compaction |              |                     | Summary of prior chat |
      | message    | user         | hello               |                       |
      | message    | assistant    | Here is my response |                       |

  Scenario: slinky folds the tail and preserves the head
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path | value |
      | model | test-model |
      | provider | grover |
      | context-window | 200 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | local |
      | soul | You are Atticus. |
    And the following sessions exist:
      | name        | total-tokens | compaction.strategy | compaction.threshold | compaction.head |
      | slinky-test | 170         | slinky              | 0.8                 | 0.4             |
    And session "slinky-test" has transcript:
      | type    | message.role | message.content  | tokens |
      | message | user         | old topic        | 40     |
      | message | assistant    | old reply        | 40     |
      | message | user         | recent topic     | 40     |
      | message | assistant    | recent reply     | 50     |
    And the following model responses are queued:
      | type | content        | model      |
      | text | Tail summary   | test-model |
      | text | Fresh response | test-model |
    When the user sends "hello" on session "slinky-test"
    Then session "slinky-test" has 8 transcript entries
    And session "slinky-test" has 5 active transcript entries
    And session "slinky-test" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | old topic       |
      | message | assistant    | old reply       |
    And session "slinky-test" has active transcript matching:
      | type       | message.role | message.content | summary      |
      | compaction |              |                 | Tail summary |
      | message    | user         | recent topic    |              |
      | message    | assistant    | recent reply    |              |
      | message    | user         | hello           |              |
      | message    | assistant    | Fresh response  |              |
