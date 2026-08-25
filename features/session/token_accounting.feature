@wip
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

  Scenario: every transcript entry carries a content-based token count
    Given the following sessions exist:
      | name   |
      | ledger |
    And a file "notes.txt" exists with content:
      """
      0123456789012345678901234567890123456789012345678901234567890123456789012345678
      """
    And the following model responses are queued:
      | type      | tool_call | arguments                  | content                                  | model |
      | tool_call | fs__read  | {"file_path": "notes.txt"} |                                          | echo  |
      | text      |           |                            | Forty chars of reply text, exactly forty | echo  |
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
      | context-window | 2000       |
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
      | message | assistant    | #"x{3000}" — three thousand characters of output  | 750    |
    And the following model responses are queued:
      | type | content         | model      | usage.input_tokens |
      | text | Summary of dump | test-model | 900                |
      | text | Here you go     | test-model | 300                |
    When the user sends "and again" on session "tally"
    Then the log has entries matching:
      | event                        | needs-chunking | tokens-before  |
      | :session/compaction-analysis | false          | #"7[0-9][0-9]" |
    And the log has no entries matching:
      | event                                |
      | :session/compaction-chunk-infeasible |

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
