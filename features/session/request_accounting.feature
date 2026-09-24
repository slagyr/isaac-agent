Feature: Per-request token accounting
  A turn's total already rides on the transcript. What was missing is the
  per-request line and the composition behind it: "the request was 358k" does
  not say why, "transcript 326k of 358k" does. The drive logs what each
  request is made of at send time — system/soul, boot files, rules, skill
  menu, tool schemas, transcript — and then logs the provider's own figure
  beside it, so context the provider added and Isaac never assembled shows up
  as a number instead of an argument (isaac-5nx5).

  The breakdown goes in the structured log and nowhere near the transcript:
  the transcript is context that gets re-sent, so accounting written into it
  makes the very thing being measured more expensive.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 1000000    |

  Scenario: a request is logged with the parts that built it
    Given the isaac EDN file "config/crew/ledger.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name       | crew   |
      | accounting | ledger |
    And the following model responses are queued:
      | type | content | model      |
      | text | Noted   | test-model |
    When the user sends "hello" on session "accounting"
    Then the log has entries matching:
      | level | event              | session    | soul-tokens | messages | tool-count |
      | :info | :turn/request-sent | accounting | 4           | 2        | 0          |

  Scenario: the system prefix is attributed to soul, boot files, rules and skill menu
    Given the isaac file "AGENTS.md" exists with:
      """
      Boot me.
      """
    And the isaac EDN file "config/crew/ledger.edn" exists with:
      | path       | value            |
      | model      | local            |
      | soul       | You are Atticus. |
      | boot-files | ["AGENTS.md"]    |
    And the following sessions exist:
      | name      | crew   |
      | bootstrap | ledger |
    And the following model responses are queued:
      | type | content | model      |
      | text | Noted   | test-model |
    When the user sends "hello" on session "bootstrap"
    Then the log has entries matching:
      | level | event              | session   | soul-tokens | boot-files-tokens |
      | :info | :turn/request-sent | bootstrap | 4           | 2                 |

  Scenario: the provider's own figure is reconciled against what Isaac assembled
    Given the isaac EDN file "config/crew/ledger.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name    | crew   |
      | reckons | ledger |
    And the following model responses are queued:
      | type | content | model      | usage.input_tokens | usage.output_tokens |
      | text | Noted   | test-model | 5000               | 12                  |
    When the user sends "hello" on session "reckons"
    Then the log has entries matching:
      | level | event                  | session | reported-prompt-tokens |
      | :info | :turn/request-measured | reckons | 5000                   |

  Scenario: a provider that reports no prompt size is not reconciled against
    Given the isaac EDN file "config/crew/ledger.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name   | crew   |
      | silent | ledger |
    And the following model responses are queued:
      | type | content | model      | usage.input_tokens | usage.output_tokens |
      | text | Noted   | test-model | 0                  | 0                   |
    When the user sends "hello" on session "silent"
    Then the log has entries matching:
      | level | event              | session |
      | :info | :turn/request-sent | silent  |
    And the log has no entries matching:
      | event                  | session |
      | :turn/request-measured | silent  |

  Scenario: the turn's total cost rides on the turn-ended line
    Given the isaac EDN file "config/crew/ledger.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name  | crew   |
      | tally | ledger |
    And the following model responses are queued:
      | type | content | model      | usage.input_tokens | usage.output_tokens |
      | text | Noted   | test-model | 5000               | 12                  |
    When the user sends "hello" on session "tally"
    Then the log has entries matching:
      | level | event      | session | requests | prompt-tokens | output-tokens |
      | :info | :turn/ended | tally  | 1        | 5000          | 12            |

  Scenario: a long-lived :reset session reports the request it sends, not the session it stores
    Given the isaac EDN file "config/crew/pinky.edn" exists with:
      | path         | value                |
      | model        | local                |
      | soul         | You are Pinky. Narf! |
      | context-mode | reset                |
    And the following sessions exist:
      | name       | crew  |
      | long-lived | pinky |
    And session "long-lived" has transcript:
      | type    | message.role | message.content | tokens |
      | message | user         | ancient ask     | 400000 |
      | message | assistant    | ancient reply   | 500000 |
    And the following model responses are queued:
      | type | content | model      |
      | text | Narf!   | test-model |
    When the user sends "hello" on session "long-lived"
    Then the log has entries matching:
      | level | event                       | session    | reason         | total-tokens | stored-tokens | context-window |
      | :info | :session/compaction-skipped | long-lived | :context-reset | 2            | 900002        | 1000000        |

  Scenario: the request sent after a reset carries the trimmed transcript, not the accumulated one
    Given the isaac EDN file "config/crew/pinky.edn" exists with:
      | path         | value                |
      | model        | local                |
      | soul         | You are Pinky. Narf! |
      | context-mode | reset                |
    And the following sessions exist:
      | name    | crew  |
      | trimmed | pinky |
    And session "trimmed" has transcript:
      | type    | message.role | message.content | tokens |
      | message | user         | ancient ask     | 400000 |
      | message | assistant    | ancient reply   | 500000 |
    And the following model responses are queued:
      | type | content | model      |
      | text | Narf!   | test-model |
    When the user sends "hello" on session "trimmed"
    Then the last LLM request matches:
      | key                 | value  |
      | messages[0].role    | system |
      | messages[1].role    | user   |
      | messages[1].content | hello  |
    And the log has entries matching:
      | level | event              | session | messages |
      | :info | :turn/request-sent | trimmed | 2        |

  Scenario: a provider that reports nothing is named, never counted as a free turn
    Given the isaac EDN file "config/crew/ledger.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name   | crew   |
      | mute   | ledger |
    And the following model responses are queued:
      | type | content | model      | usage.input_tokens | usage.output_tokens |
      | text | Noted   | test-model | 0                  | 0                   |
    When the user sends "hello" on session "mute"
    Then the log has entries matching:
      | level | event                  | session | requests |
      | :warn | :turn/usage-unreported | mute    | 1        |
