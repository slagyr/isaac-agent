Feature: Compact from the last provider count; overflow compact-and-retry
  Compaction must fire from the last successful provider prompt_tokens
  (session :last-input-tokens) when that is over the threshold, even if
  the local prompt estimate is under. (str prompt-map)/4 undercounted
  Grok by ~150k on tono-work-3: Isaac sat at 347k/500k, never compacted,
  Grok rejected at 500173. A prompt-too-long 400 must compact and retry
  in the drive. isaac-bs5b parks hail as context-exhausted only when
  compaction cannot save the turn.

  Background:
    Given default Grover setup
    And the built-in tools are registered

  Scenario: last provider prompt tokens over the threshold compact even when the local estimate is under
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 1000       |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name   | last-input-tokens |
      | ledger | 850               |
    And session "ledger" has transcript:
      | type    | message.role | message.content | tokens |
      | message | user         | older ask       | 20     |
      | message | assistant    | older reply     | 20     |
    And the following model responses are queued:
      | type | content      | model      |
      | text | folded older | test-model |
      | text | here you go  | test-model |
    When the user sends "and again" on session "ledger"
    Then session "ledger" has transcript matching:
      | type       | message.role | message.content | summary      |
      | compaction |              |                 | folded older |
      | message    | assistant    | here you go     |              |

  Scenario: a provider 400 for prompt length compact-and-retries
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 200        |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name  | last-input-tokens |
      | tally | 100               |
    And session "tally" has transcript:
      | type    | message.role | message.content                                                              | tokens |
      | message | user         | block A oldest: planning notes about logging, tools, and the dispatch loop    | 60     |
      | message | assistant    | reply A: we agreed on output sinks, the compaction trigger, and tool dispatch | 60     |
      | message | user         | block B: more notes on retry behavior and the backoff between dispatch tries  | 60     |
      | message | assistant    | reply B: dispatcher retry is now idempotent with backoff between attempts     | 60     |
    And the following model responses are queued:
      | type       | status | message                                                     | content           | model      |
      | http-error | 400    | maximum prompt length is 200 but the request contains 250   |                   | test-model |
      | text       |        |                                                             | summary of A      | test-model |
      | text       |        |                                                             | here is my answer | test-model |
    When the user sends "go" on session "tally"
    Then session "tally" has transcript matching:
      | type       | message.role | message.content   | summary      |
      | compaction |              |                   | summary of A |
      | message    | assistant    | here is my answer |              |

  Scenario: prompt-too-long with compaction disabled is context-exhausted weather
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 200        |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name   | last-input-tokens | compaction-disabled |
      | wedged | 180               | true                |
    And session "wedged" has transcript:
      | type    | message.role | message.content |
      | message | user         | earlier prompt  |
      | message | assistant    | earlier reply   |
    And the following model responses are queued:
      | type       | status | message                                                   | model      |
      | http-error | 400    | maximum prompt length is 200 but the request contains 250 | test-model |
    When the user sends "one more" on session "wedged"
    Then the turn result is unavailable with retry-after-ms 300000 and reason context-exhausted
