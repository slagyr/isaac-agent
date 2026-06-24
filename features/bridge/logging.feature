Feature: Chat and Provider Logging
  Isaac logs chat and provider lifecycle events with structured context.

  Background:
    Given default Grover setup
    And config:
      | key        | value  |
      | log.output | memory |

  Scenario: Provider failure is logged with chat context
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path | value |
      | model | llama3.2:latest |
      | provider | ollama |
      | context-window | 32000 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | local |
      | soul | You are Atticus. |
    And the provider "ollama" is configured with:
      | key     | value                  |
      | base-url | http://localhost:99999 |
    And the following sessions exist:
      | name          |
      | log-fail-test |
    When the user sends "Hello" on session "log-fail-test"
    Then the log has entries matching:
      | level  | event                 | provider | session       |
      | :error | :chat/response-failed | ollama   | log-fail-test |

  Scenario: Successful chat response storage is logged at debug
    Given the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    And the following sessions exist:
      | name             |
      | log-success-test |
    When the user sends "Hi" on session "log-success-test"
    Then session "log-success-test" has transcript matching:
      | type    | message.role |
      | message | assistant    |
    And the log has entries matching:
      | level  | event                   | session          | model |
      | :debug | :session/message-stored | log-success-test | echo  |

  Scenario: Streaming completion is logged at debug
    Given the following sessions exist:
      | name            |
      | log-stream-test |
    And the following model responses are queued:
      | type | content | model |
      | text | Hi back | echo  |
    When the user sends "Hi" on session "log-stream-test"
    Then the log has entries matching:
      | level  | event                     | session         |
      | :debug | :chat/stream-completed | log-stream-test |

  Scenario: Compaction check and start are logged during chat
    # Compaction keys off the live outbound-prompt estimate (system floor +
    # transcript), so a small window plus a non-trivial transcript pushes the
    # estimate past 0.8 * window. head 0.1 keeps the post-compaction estimate
    # under threshold so compaction makes progress.
    Given the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value |
      | model          | echo  |
      | provider       | grover |
      | context-window | 200   |
    And the following sessions exist:
      | name             | compaction.head |
      | log-compact-test | 0.1             |
    And session "log-compact-test" has transcript:
      | type    | message.role | message.content                                                              |
      | message | user         | Please summarize the work we did on the logging subsystem and the tool loop   |
      | message | assistant    | We discussed logging output sinks, the compaction trigger, and tool dispatch  |
      | message | user         | And what about the retry behavior we changed in the dispatcher last week      |
      | message | assistant    | We made the dispatcher retry idempotent and added backoff between attempts    |
    And the following model responses are queued:
      | type | content               | model |
      | text | Summary of prior chat | echo  |
      | text | Here is my answer     | echo  |
    When the user sends "Continue" on session "log-compact-test"
    Then the log has entries matching:
      | level  | event                       | session          |
      | :debug | :session/compaction-check   | log-compact-test |
      | :info  | :session/compaction-started | log-compact-test |

  Scenario: Compaction entry precedes the triggering user message in transcript
    # Compaction keys off the live outbound-prompt estimate (system floor +
    # transcript), so a small window plus a non-trivial transcript pushes the
    # estimate past 0.8 * window. head 0.1 keeps the post-compaction estimate
    # under threshold so compaction makes progress.
    Given the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value |
      | model          | echo  |
      | provider       | grover |
      | context-window | 200   |
    And the following sessions exist:
      | name           | compaction.head |
      | log-order-test | 0.1             |
    And session "log-order-test" has transcript:
      | type    | message.role | message.content                                                              |
      | message | user         | Please summarize the work we did on the logging subsystem and the tool loop   |
      | message | assistant    | We discussed logging output sinks, the compaction trigger, and tool dispatch  |
      | message | user         | And what about the retry behavior we changed in the dispatcher last week      |
      | message | assistant    | We made the dispatcher retry idempotent and added backoff between attempts    |
    And the following model responses are queued:
      | type | content               | model |
      | text | Summary of prior chat | echo  |
      | text | Here is my answer     | echo  |
    When the user sends "Continue" on session "log-order-test"
    Then session "log-order-test" has active transcript matching:
      | #index | type       | message.role |
      | 0      | compaction |              |
      | 1      | message    | user         |
