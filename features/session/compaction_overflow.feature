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

  Scenario: a prompt above the window compacts before the next request (isaac-dgod)
    A stamp larger than the window is not impossible — it says the working
    context is over budget, which is the loudest compaction trigger there is.
    Throwing it away blinded the gauge: isaac-work-2 ran nineteen requests at
    271k-304k against a 200k budget, every stamp was discarded, the gauge fell
    back to its chars/4 tally of ~125k and compaction never fired.
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
      | ledger | 0                 |
    And session "ledger" has transcript:
      | type    | message.role | message.content | tokens |
      | message | user         | older ask       | 20     |
      | message | assistant    | older reply     | 20     |
    And the following model responses are queued:
      | type | content      | model      | usage.input_tokens |
      | text | over budget  | test-model | 1400               |
      | text | folded older | test-model | 30                 |
      | text | here you go  | test-model | 30                 |
    When the user sends "and again" on session "ledger"
    Then the following sessions match:
      | name   | last-input-tokens |
      | ledger | 1400              |
    When the user sends "once more" on session "ledger"
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

  Scenario: prompt-too-long on a blocked conversation is blocked weather
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
      | name   | last-input-tokens | block.reason       |
      | wedged | 180               | :compaction-failed |
    And session "wedged" has transcript:
      | type    | message.role | message.content |
      | message | user         | earlier prompt  |
      | message | assistant    | earlier reply   |
    And the following model responses are queued:
      | type       | status | message                                                   | model      |
      | http-error | 400    | maximum prompt length is 200 but the request contains 250 | test-model |
    When the user sends "one more" on session "wedged"
    Then the turn result is unavailable with retry-after-ms 300000 and reason blocked
    And grover records zero provider requests

  Scenario: an entry bigger than the window is left out of the summary and the rest compacts (isaac-ebup)
    A chunk cannot be smaller than one message, so a single entry larger than
    the window used to make compaction infeasible forever: isaac-work-2 held a
    511,568-token assistant entry (raw claude-code stream-json written as the
    reply) against a 200k window. The entry stays in the transcript; only the
    summary request stands a one-line stub in its place.
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 4000       |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name       | last-input-tokens |
      | log-keeper | 0                 |
    And session "log-keeper" has transcript:
      | type    | message.role | message.content                                            | tokens |
      | message | user         | Week one: the Marigold cleared port under Cordelia         | 100    |
      | message | assistant    | {"type":"system","subtype":"init"} {"type":"stream_event"} | 5000   |
      | message | user         | Week two: Joe rerouted the quantum-anvil coolant           | 100    |
      | message | assistant    | Logged week two with the coolant reroute                   | 100    |
    And the following model responses are queued:
      | type | content                  | model      | usage.input_tokens |
      | text | Summary of weeks one-two | test-model | 30                 |
      | text | Logged.                  | test-model | 30                 |
    When the user sends "and week three?" on session "log-keeper"
    Then the compaction request matches:
      | key                 | value                                                                                                          |
      | messages[1].content | #"(?s)(?=.*Week one)(?=.*Week two)(?=.*\[entry omitted: 5000 tokens, larger than the 4000-token window\])(?!.*stream_event).*" |
    And session "log-keeper" has transcript matching:
      | type       | message.role | message.content | summary                  |
      | compaction |              |                 | Summary of weeks one-two |
      | message    | assistant    | Logged.         |                          |

  Scenario: a reply bigger than the transcript cap is stored as its head and tail (isaac-ebup)
    No entry may be written at a size that can wedge a session. A parse
    failure once wrote 2.2 MB of stream-json in as a reply. The cap is
    defaults.transcript.max-bytes (default 262144) and keeps the head and
    tail the way the tool output cap does.
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 100000     |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And config:
      | defaults.transcript.max-bytes | 64 |
    And the following sessions exist:
      | name       |
      | log-keeper |
    And the following model responses are queued:
      | type | content                                                                                                   | model      |
      | text | HEAD of the dump, then a long middle that runs on well past the cap and keeps going, then the TAIL of it | test-model |
    When the user sends "dump it" on session "log-keeper"
    Then session "log-keeper" has transcript matching:
      | type    | message.role | message.content                                           |
      | message | assistant    | #"(?s)^HEAD of the dump.*bytes truncated; byte cap hit.*the TAIL of it$" |
    And the log has entries matching:
      | level | event                    | session    | role      |
      | :warn | :session/entry-truncated | log-keeper | assistant |
