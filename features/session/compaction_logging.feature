Feature: Context Compaction Logging
  Isaac logs why context compaction was triggered during normal chat flow
  and preserves the new user message after compaction.

  Background:
    Given an Isaac root at "target/test-state"
    Given config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/models/local.edn" exists with:
      | path | value |
      | model | test-model |
      | provider | grover |
      | context-window | 100 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | local |
      | soul | You are Atticus. |

  Scenario: Chat logs the compaction trigger with provider and model context
    Given the following sessions exist:
      | name            | total-tokens | #comment                  |
      | compaction-chat | 95          | exceeds 90% of 100 window |
    And session "compaction-chat" has transcript:
      | type    | message.role | message.content                |
      | message | user         | Please summarize our work      |
      | message | assistant    | We discussed logging and tools |
    And the following model responses are queued:
      | type | content               | model      |
      | text | Summary of prior chat | test-model |
      | text | README summary        | test-model |
    When the user sends "Can you summarize README.md?" on session "compaction-chat"
    Then the memory comm has events matching:
      | event             | provider | model      | total-tokens | context-window |
      | compaction-start  | grover   | test-model | 95           | 100            |

  Scenario: The new user message is preserved after compaction
    Given the following sessions exist:
      | name            | total-tokens | #comment                  |
      | compaction-chat | 95          | exceeds 90% of 100 window |
    And session "compaction-chat" has transcript:
      | type    | message.role | message.content                |
      | message | user         | Please summarize our work      |
      | message | assistant    | We discussed logging and tools |
    And the following model responses are queued:
      | type | content               | model      |
      | text | Summary of prior chat | test-model |
      | text | README summary        | test-model |
    When the user sends "Can you summarize README.md?" on session "compaction-chat"
    Then session "compaction-chat" has transcript matching:
      | type    | message.role | message.content                |
      | message | user         | Please summarize our work      |
      | message | assistant    | We discussed logging and tools |
    And session "compaction-chat" has active transcript matching:
      | #index | type       | summary               | message.role | message.content              |
      | 0      | compaction | Summary of prior chat |              |                              |
      | 1      | message    |                       | user         | Can you summarize README.md? |

  Scenario: Chat completes after compaction
    Given the following sessions exist:
      | name            | total-tokens | #comment                  |
      | compaction-chat | 95          | exceeds 90% of 100 window |
    And session "compaction-chat" has transcript:
      | type    | message.role | message.content                |
      | message | user         | Please summarize our work      |
      | message | assistant    | We discussed logging and tools |
    And the following model responses are queued:
      | type | content               | model      |
      | text | Summary of prior chat | test-model |
      | text | README summary        | test-model |
    When the user sends "Can you summarize README.md?" on session "compaction-chat"
    Then session "compaction-chat" has transcript matching:
      | type    | message.role | message.content |
      | message | assistant    | README summary  |

  Scenario: Compaction failure is logged and chat proceeds without looping
    Given the following sessions exist:
      | name         | total-tokens | #comment                  |
      | failure-chat | 95          | exceeds 90% of 100 window |
    And session "failure-chat" has transcript:
      | type    | message.role | message.content                |
      | message | user         | Please summarize our work      |
      | message | assistant    | We discussed logging and tools |
    And the following model responses are queued:
      | type  | content                 | model      |
      | error | context length exceeded | test-model |
      | text  | Here is my answer       | test-model |
    When the user sends "What was decided?" on session "failure-chat"
    Then the memory comm has events matching:
      | event              | error      | consecutive-failures |
      | compaction-start   |            |                      |
      | compaction-failure | :llm-error | 1                    |
    And session "failure-chat" has transcript matching:
      | type    | message.role | message.content   |
      | message | assistant    | Here is my answer |

  # Note: :session/compaction-stopped (warn) is emitted when max-compaction-attempts (5) is
  # exceeded or when a compaction loop makes no progress. Both cases require complex multi-turn
  # setup and are not exercised here; the :session/compaction-failed error path above is the
  # primary guard. A dedicated test could be added by making grover always return larger context.

  Scenario: Compaction targets only the oldest messages when history exceeds the model context window
    Given the following sessions exist:
      | name            | total-tokens | compaction.strategy | compaction.threshold | compaction.head | #comment                  |
      | partial-compact | 95          | slinky              | 0.8                  | 0.5             | exceeds threshold         |
    And the isaac EDN file "config/models/local.edn" exists with:
      | path | value |
      | model | test-model |
      | provider | grover |
      | context-window | 60 |
    And session "partial-compact" has transcript:
      | type    | message.role | message.content                                       | tokens |
      | message | user         | First question about the project status               | 20     |
      | message | assistant    | The project status is healthy and on track            | 20     |
      | message | user         | Second question about the upcoming release            | 20     |
      | message | assistant    | The release is scheduled for the end of month         | 20     |
    And the following model responses are queued:
      | type | content                   | model      |
      | text | Summary of first exchange | test-model |
      | text | Third answer              | test-model |
    When the user sends "Third question" on session "partial-compact"
    Then session "partial-compact" has transcript matching:
      | type    | message.role | message.content                            |
      | message | user         | First question about the project status    |
      | message | assistant    | The project status is healthy and on track |
    And session "partial-compact" has active transcript matching:
      | #index | type       | message.role | message.content                               | summary                   |
      | 0      | compaction |              |                                               | Summary of first exchange |
      | 1      | message    | user         | Second question about the upcoming release    |                           |
      | 2      | message    | assistant    | The release is scheduled for the end of month |                           |
      | 3      | message    | user         | Third question                                |                           |
      | 4      | message    | assistant    | Third answer                                  |                           |

  Scenario: Switching to a smaller-context model runs compaction repeatedly until chat can continue
    Given the following sessions exist:
      | name          | total-tokens | #comment                             |
      | model-switch  | 200         | accumulated under large-window model |
    And the isaac EDN file "config/models/claude-long.edn" exists with:
      | path | value |
      | model | claude-opus-4-6 |
      | provider | grover |
      | context-window | 96 |
    # context-window kept well inside the "needs 2 compactions" plateau (≤32),
    # not at its 33-token upper edge, so small token-count differences across
    # environments can't tip the loop down to a single compaction (CI flake).
    And the isaac EDN file "config/models/qwen3-coder.edn" exists with:
      | path | value |
      | model | qwen3-coder:30b |
      | provider | grover |
      | context-window | 20 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | qwen3-coder |
      | soul | You are Atticus. |
    And session "model-switch" has transcript:
      | type    | message.role | message.content                                                                                                                                                                         |
      | message | user         | Earlier planning notes from the large-window model.                                                                                                                                      |
      | message | assistant    | Earlier planning summary from the large-window model.                                                                                                                                    |
      | message | user         | Recent facts: release tracking, migration status, provider changes, logging updates, tool execution details, deployment concerns, rollback notes, and monitoring issues remain active after the downgrade. |
      | message | assistant    | Recent answer: release tracking, migration status, provider changes, logging updates, tool execution details, deployment concerns, rollback notes, and monitoring issues remain important after the downgrade. |
    And the following model responses are queued:
      | type | content                     | model           |
      | text | Summary from first compact  | qwen3-coder:30b |
      | text | Summary from second compact | qwen3-coder:30b |
      | text | Final response after shrink | qwen3-coder:30b |
    When the user sends "Continue after model switch" on session "model-switch"
    Then the following sessions match:
      | id           | compaction-count |
      | model-switch | 2               |
    And session "model-switch" has transcript matching:
      | type       | summary                     |
      | compaction | Summary from first compact  |
      | compaction | Summary from second compact |
    And session "model-switch" has transcript matching:
      | type    | message.role | message.content              |
      | message | assistant    | Final response after shrink  |

  Scenario: Successful compaction does not immediately re-trigger on the next user turn
    Given the following sessions exist:
      | name          | input-tokens | output-tokens | total-tokens | #comment                          |
      | rebound-test  | 120         | 30           | 150         | stale accumulators cause rebound  |
    And session "rebound-test" has transcript:
      | type    | message.role | message.content                                                  |
      | message | user         | Please summarize our previous context before we continue. |
      | message | assistant    | We covered tools, logs, and pending compaction fixes.     |
    And the following model responses are queued:
      | type | content                          | model      |
      | text | Summary after compaction         | test-model |
      | text | First reply after compaction     | test-model |
      | text | Second reply without re-compacts | test-model |
    When the user sends "Hello?" on session "rebound-test"
    And the user sends "You there?" on session "rebound-test"
    Then the following sessions match:
      | id           | compaction-count |
      | rebound-test | 1               |
    And session "rebound-test" has transcript matching:
      | type       | message.role | message.content                  | summary                  |
      | compaction |              |                                  | Summary after compaction |
      | message    | user         | Hello?                           |                          |
      | message    | assistant    | First reply after compaction     |                          |
      | message    | user         | You there?                       |                          |
      | message    | assistant    | Second reply without re-compacts |                          |

  Scenario: compaction succeeds and chat continues when the head exceeds the context window
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | enforce-context-window | true |
      | context-window | 600        |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value          |
      | model | local          |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name      | total-tokens |
      | huge-head | 620          |
    And session "huge-head" has transcript:
      | type    | message.role | message.content  | tokens |
      | message | user         | block A (oldest) | 60     |
      | message | assistant    | reply A          | 60     |
      | message | user         | block B          | 60     |
      | message | assistant    | reply B          | 60     |
      | message | user         | latest question  | 61     |
    And the following model responses are queued:
      | type | content              | model      |
      | text | summary of A         | test-model |
      | text | here is my answer    | test-model |
    When the user sends "go" on session "huge-head"
    Then the memory comm has events matching:
      | event               | summary              |
      | compaction-start    |                      |
      | compaction-success  | summary of A         |
    And session "huge-head" has transcript matching:
      | type       | message.role | message.content   | summary              |
      | compaction |              |                   | summary of A         |
      | message    | assistant    | here is my answer |                      |

  Scenario: compaction stops retrying after max-compaction-attempts consecutive cross-turn failures
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 60         |
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
      | event                | error      | consecutive-failures | reason             |
      | compaction-failure   | :llm-error | 6                    |                    |
      | compaction-disabled  |            |                      | :too-many-failures |
    And session "giving-up" matches:
      | key                 | value |
      | compaction-disabled | true  |
    And session "giving-up" has transcript matching:
      | type    | message.role | message.content   |
      | message | assistant    | here is my answer |

  Scenario: switching model clears compaction-disabled and lets the next turn retry
    Given the isaac EDN file "config/models/bigger-model.edn" exists with:
      | path | value |
      | model | bigger-model-upstream |
      | provider | grover |
      | context-window | 200 |
    Given the following sessions exist:
      | name      | model      | compaction-disabled | compaction.consecutive-failures |
      | recovered | test-model | true                | 5                               |
    And the current session is "recovered"
    When the tool "session_model" is called with:
      | model | bigger-model |
    Then session "recovered" matches:
      | key                             | value |
      | compaction-disabled             | false |
      | compaction.consecutive-failures | 0     |

  Scenario: compaction passes the session's provider through so tool format matches
    And the isaac EDN file "config/models/codex.edn" exists with:
      | path           | value         |
      | model          | gpt-5.4       |
      | provider       | grover:chatgpt |
      | context-window | 100           |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value          |
      | model | codex          |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name          | total-tokens | model |
      | codex-compact | 95           | codex |
    And session "codex-compact" has transcript:
      | type    | message.role | message.content |
      | message | user         | older prompt    |
      | message | assistant    | older reply     |
    And the following model responses are queued:
      | type | content           | model   |
      | text | Summary of prior  | gpt-5.4 |
      | text | here is my answer | gpt-5.4 |
    When the user sends "next" on session "codex-compact"
    Then the last outbound HTTP request matches:
      | key                | value        |
      | #index             | 0            |
      | body.tools[0].type | function     |
      | body.tools[0].name | memory_get   |
      | body.tools[2].name | memory_write |
    And the last provider request does not contain path "body.tools[0].function"

  Scenario: Compaction keeps toolCall and toolResult together
    Given the following sessions exist:
      | name        | total-tokens | compaction.strategy | compaction.threshold | compaction.head | #comment                                      |
      | tool-orphan | 95           | slinky              | 0.9                  | 0.15            | tail=15 splits between toolResult & last asst |
    And the isaac EDN file "config/models/local.edn" exists with:
      | path           | value      |
      | model          | test-model |
      | provider       | grover     |
      | context-window | 100        |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value          |
      | model | local          |
      | soul  | You are Atticus. |
    And session "tool-orphan" has transcript:
      | type    | message.role | message.id | message.content                                                                            | tokens | #comment                                       |
      | message | user         |            | What's in fridge.txt?                                                                      | 20     | head of compactables                           |
      | message | assistant    |            | [{"type":"toolCall","id":"call_old","name":"read","arguments":{"filePath":"fridge.txt"}}]  | 5      | bug: ->compact-message returns nil here        |
      | message | toolResult   | call_old   | one sad lemon                                                                              | 15     | enters compactables; will be in compacted-ids  |
      | message | assistant    |            | The fridge has a lemon.                                                                    | 20     | tail=15: this 20-token reply is firstKept      |
    And the following model responses are queued:
      | type | content           | model      | #comment                          |
      | text | Summary of fridge | test-model | compaction LLM response           |
      | text | next answer       | test-model | reply to the new user message     |
    When the user sends "And the freezer?" on session "tool-orphan"
    Then the last compaction request input contains "call_old"
    And session "tool-orphan" has transcript matching:
      | type    | message.role | message.content                                                                           |
      | message | user         | What's in fridge.txt?                                                                     |
      | message | toolResult   | one sad lemon                                                                             |
    And session "tool-orphan" has active transcript matching:
      | #index | type       | summary           | message.role | message.content         | #comment                                |
      | 0      | compaction | Summary of fridge |              |                         | both tc & tr summarized away as a pair  |
      | 1      | message    |                   | assistant    | The fridge has a lemon. | firstKept survives                      |
      | 2      | message    |                   | user         | And the freezer?        | new turn input                          |
      | 3      | message    |                   | assistant    | next answer             | new turn reply                          |

  Scenario: Crew compaction config with unknown :strategy is rejected
    Given an empty Isaac root at "/tmp/isaac"
    And the isaac file "isaac.edn" exists with:
      """
      {:crew {:main {:compaction {:strategy :rainbow :threshold 100000}}}}
      """
    When the config is loaded
    Then the config has validation errors matching:
      | key                           | value            |
      | crew.main.compaction.strategy | must be one of.* |
