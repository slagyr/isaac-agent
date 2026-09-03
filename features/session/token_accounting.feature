Feature: Token accounting — one unit, one source
  Compaction plans (should-compact?, compaction-target, needs-chunking?,
  chunk sizing, tokens-saved) must run on real per-entry token counts, not
  on a chars/4 guess over the stringified entry map. Today nothing in the
  turn driver stamps :tokens on transcript entries, so every planning number
  is a heuristic over `(str message)` — it produced :tokens-before of 1.4M
  and 2.4K for the same kind of history on 2026-08-25 and tripped chunking
  while the provider reported 128K in a 256K window. Contract: (1) every
  transcript entry is stamped at write time from CONTENT (text chars/4,
  ceiling); (2) compaction reads stamped counts only; (3) after every model
  response the provider's prompt tokens are reconciled against the stamped
  sum and logged as :session/token-drift — report-only. (isaac-pqjn)

  Background:
    Given default Grover setup
    And the built-in tools are registered

  Scenario: every transcript entry carries a content-based token count
    Given the crew "main" allows tools: "fs/read"
    And the isaac file "crew/main/notes.txt" exists with:
      """
      12345678901234567890123456789012345678901234567890123456789012345678901234567
      """
    And the following sessions exist:
      | name   |
      | ledger |
    And the following model responses are queued:
      | type      | tool_call | arguments                                     | content                                  | model |
      | tool_call | fs__read  | {"file_path":"/target/test-state/crew/main/notes.txt"} |                                          | echo  |
      | text      |           |                                               | Forty chars of reply text, exactly forty | echo  |
    When the user sends "Read my notes please" on session "ledger"
    Then session "ledger" has transcript matching:
      | type    | message.role | tokens | #comment                         |
      | message | user         | 5      | 20 chars / 4                     |
      | message | assistant    | #*     | tool call — arguments counted    |
      | message | toolResult   | 20     | 80-char file body / 4            |
      | message | assistant    | 10     | 40 chars / 4                     |

  Scenario: compaction plans from stamped counts, not a stringified guess
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 32768      |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name  | total-tokens |
      | tally | 1700         |
    And session "tally" has transcript:
      | type    | message.role | message.content                                   | tokens |
      | message | user         | dump the config                                   | 4      |
      | message | assistant    | dump output, stamped far above its text length    | 750    |
    And the following model responses are queued:
      | type | content         | model      | usage.input_tokens |
      | text | Summary of dump | test-model | 900                |
      | text | Here you go     | test-model | 300                |
    When the user sends "and again" on session "tally"
    Then the log has entries matching:
      | event                | stamped        | provider | ratio        |
      | :session/token-drift | #"7[0-9][0-9]" | 900      | #"1\.[0-9]+" |

  Scenario: provider prompt tokens are reconciled against stamped counts and drift is logged
    Given the following sessions exist:
      | name  |
      | gauge |
    And session "gauge" has transcript:
      | type    | message.role | message.content | tokens |
      | message | user         | earlier ask     | 100    |
      | message | assistant    | earlier reply   | 100    |
    And the following model responses are queued:
      | type | content | model | usage.input_tokens |
      | text | ok      | echo  | 260                |
    When the user sends "now this" on session "gauge"
    Then the log has entries matching:
      | event                | stamped        | provider | ratio        |
      | :session/token-drift | #"2[0-9][0-9]" | 260      | #"1\.[0-9]+" |

  @wip
  Scenario: a mid-turn provider count over the threshold compacts before the next cycle
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 1000       |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the built-in tools are registered
    And the crew "main" allows tools: "exec/run"
    And the following sessions exist:
      | name |
      | loop |
    And session "loop" has transcript:
      | type    | message.role | message.content | tokens |
      | message | user         | older ask       | 20     |
      | message | assistant    | older reply     | 20     |
    And the following model responses are queued:
      | type      | tool_call | arguments              | content      | model      | usage.input_tokens |
      | tool_call | exec__run | {"command": "echo hi"} |              | test-model | 850                |
      | text      |           |                        | folded older | test-model |                    |
      | text      |           |                        | done         | test-model | 120                |
    When the user sends "run it" on session "loop"
    Then session "loop" has transcript matching:
      | type       | message.role | message.content | summary      |
      | toolCall   |              |                 |              |
      | toolResult |              |                 |              |
      | compaction |              |                 | folded older |
      | message    | assistant    | done            |              |

  @wip
  Scenario: the provider stamp is the last cycle's prompt count, never the sum of cycles
    Given the built-in tools are registered
    And the crew "main" allows tools: "exec/run"
    And the following sessions exist:
      | name  |
      | cycles |
    And the following model responses are queued:
      | type      | tool_call | arguments               | content | model | usage.input_tokens |
      | tool_call | exec__run | {"command": "echo one"} |         | echo  | 300                |
      | tool_call | exec__run | {"command": "echo two"} |         | echo  | 320                |
      | text      |           |                         | done    | echo  | 340                |
    When the user sends "twice" on session "cycles"
    Then the following sessions match:
      | name   | last-input-tokens | turn-input-tokens |
      | cycles | 340               | 960               |

  @wip
  Scenario: the gauge is calibrated by the last observed drift ratio
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
      | name       |
      | calibrated |
    And session "calibrated" has transcript:
      | type    | message.role | message.content | tokens |
      | message | user         | first ask       | 100    |
      | message | assistant    | first reply     | 100    |
    And the following model responses are queued:
      | type | content      | model      | usage.input_tokens |
      | text | second reply | test-model | 300                |
      | text | folded       | test-model |                    |
      | text | third reply  | test-model | 200                |
    When the user sends "second ask" on session "calibrated"
    Then the log has entries matching:
      | event                | provider | ratio        |
      | :session/token-drift | 300      | #"1\.[0-9]+" |
    Given session "calibrated" has transcript:
      | type    | message.role | message.content | tokens |
      | message | user         | padding ask     | 200    |
      | message | assistant    | padding reply   | 200    |
    When the user sends "third ask" on session "calibrated"
    Then the log has entries matching:
      | event                     | gauge         | ratio        |
      | :session/compaction-check | #"[89][0-9]{2}" | #"1\.[0-9]+" |
    And session "calibrated" has transcript matching:
      | type       | message.role | message.content | summary |
      | compaction |              |                 | folded  |
      | message    | assistant    | third reply     |         |
