Feature: Token accounting — one unit, one source
  Compaction plans (should-compact?, compaction-target, needs-chunking?,
  chunk sizing, tokens-saved) must run on real per-entry token counts, not
  on a chars/4 guess over the stringified entry map. Contract: (1) every
  transcript entry is stamped at write time from CONTENT (text chars/4,
  ceiling); (2) compaction reads stamped counts only; (3) the gauge is a
  running tally — the last prompt tokens plus that response's output tokens
  plus the stamped tokens of entries appended since.

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
    Then the following sessions match:
      | name | last-input-tokens | turn-input-tokens | compaction-count |
      | loop | 120               | 970               | 1                |
    And session "loop" has transcript matching:
      | type       | summary      |
      | compaction | folded older |
    And session "loop" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | done            |

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

  Scenario: anthropic-shaped cached input stamps 908 and compacts on the next turn
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
      | name   |
      | cached |
    And the following model responses are queued:
      | type | content      | model      | usage.input_tokens | usage.cache_read_input_tokens | usage.cache_creation_input_tokens |
      | text | first reply  | test-model | 8                  | 700                           | 200                               |
      | text | folded cache | test-model |                    |                               |                                   |
      | text | second reply | test-model | 20                 |                               |                                   |
    When the user sends "first ask" on session "cached"
    Then the following sessions match:
      | name   | last-input-tokens |
      | cached | 908               |
    When the user sends "second ask" on session "cached"
    Then the following sessions match:
      | name   | compaction-count | last-input-tokens |
      | cached | 1                | 20                |
    And session "cached" has transcript matching:
      | type       | summary      |
      | compaction | folded cache |
    And session "cached" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | second reply    |

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
      | event                        | tokens-before |
      | :session/compaction-analysis | #"75[0-9]"    |

  Scenario: the gauge is the last prompt plus its output plus the entries appended since
    Given the crew "main" allows tools: "fs/read"
    And the isaac file "crew/main/notes.txt" exists with:
      """
      12345678901234567890123456789012345678901234567890123456789012345678901234567
      """
    And the following sessions exist:
      | name   |
      | tiller |
    And the following model responses are queued:
      | type      | tool_call | arguments                                              | content | model | usage.input_tokens | usage.output_tokens |
      | tool_call | fs__read  | {"file_path":"/target/test-state/crew/main/notes.txt"} |         | echo  | 300                | 40                  |
      | text      |           |                                                        | done    | echo  | 360                |                     |
    When the user sends "read my notes" on session "tiller"
    Then the log has entries matching:
      | event                     | gauge |
      | :session/compaction-check | 360   |

  Scenario: entries appended since the last response can cross the threshold and compact before the next cycle
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 1000       |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | local            |
      | soul  | You are Atticus. |
    And the crew "main" allows tools: "fs/read"
    And the isaac file "crew/main/manifest.txt" exists with:
      """
      Cordelia's manifest: flour, salt, coffee, water casks, starcore spares, skybeam lenses, and longwave tubes.
      Cordelia's manifest: flour, salt, coffee, water casks, starcore spares, skybeam lenses, and longwave tubes.
      Cordelia's manifest: flour, salt, coffee, water casks, starcore spares, skybeam lenses, and longwave tubes.
      Cordelia's manifest: flour, salt, coffee, water casks, starcore spares, skybeam lenses, and longwave tubes.
      """
    And the following sessions exist:
      | name   |
      | purser |
    And the following model responses are queued:
      | type      | tool_call | arguments                                                 | content | model      | usage.input_tokens | usage.output_tokens |
      | tool_call | fs__read  | {"file_path":"/target/test-state/crew/main/manifest.txt"} |         | test-model | 700                | 20                  |
      | text      |           |                                                           | folded  | test-model |                    |                     |
      | text      |           |                                                           | done    | test-model | 150                |                     |
    When the user sends "check the manifest" on session "purser"
    Then the following sessions match:
      | name   | compaction-count |
      | purser | 1                |
    And session "purser" has transcript matching:
      | type       | summary |
      | compaction | folded  |
    And session "purser" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | done            |

  Scenario: after a compaction the gauge counts the stamped history, not the stale provider count
    Given the following sessions exist:
      | name   | last-input-tokens |
      | galley | 900               |
    And session "galley" has transcript:
      | type    | message.role | message.content          |
      | message | user         | inventory the galley     |
      | message | assistant    | twelve casks, four short |
    When compaction is spliced into session "galley" with:
      | key            | value                     |
      | summary        | Galley counted, casks low |
      | firstKeptIndex | 1                         |
    And the following model responses are queued:
      | type | content | model |
      | text | aye     | echo  |
    And the user sends "restock?" on session "galley"
    Then the log has entries matching:
      | event                     | gauge           |
      | :session/compaction-check | #"^[0-9]{1,2}$" |
