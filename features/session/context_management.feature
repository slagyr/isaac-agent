Feature: Context Management
  Isaac tracks token usage and compacts conversation history
  when approaching the model's context window limit.

  Background:
    Given default Grover setup
    And the isaac EDN file "config/models/grover.edn" exists with:
      | path | value |
      | model | echo |
      | provider | grover |
      | context-window | 100 |

  # --- Token Tracking ---

  Scenario: Token usage is tracked per session
    Given the following sessions exist:
      | name          |
      | context-track |
    And the following model responses are queued:
      | type | content               | model |
      | text | Here is my response   | echo  |
    When the user sends "Hello" on session "context-track"
    Then the following sessions match:
      | id            | input-tokens | output-tokens | total-tokens |
      | context-track | #"\d+"      | #"\d+"       | #"\d+"      |

  # --- Compaction Trigger ---

  Scenario: Compaction triggers at 90% context usage
    Given the following sessions exist:
      | name            | last-input-tokens | #comment                    |
      | context-compact | 95                | exceeds 90% of 100 window   |
    And session "context-compact" has transcript:
      | type    | message.role | message.content               |
      | message | user         | Please summarize our work     |
      | message | assistant    | We discussed logging and tools |
    And the following model responses are queued:
      | type | content                | model |
      | text | Summary of prior chat  | echo  |
      | text | Here is my answer      | echo  |
    When the user sends "What was decided?" on session "context-compact"
    Then session "context-compact" has transcript matching:
      | type       |
      | compaction |

  Scenario: Cumulative billing across many small turns does not trigger compaction
    The compaction trigger compares context window against the size of the
    prompt the model just saw (last-input-tokens), not cumulative billed
    tokens. A session that has spent many tokens across many small turns
    should not compact while the live conversation is still small.
    Given the following sessions exist:
      | name           | total-tokens | last-input-tokens | #comment                  |
      | context-cheap  | 5000         | 30                | cheap turns, small prompt |
    And session "context-cheap" has transcript:
      | type    | message.role | message.content |
      | message | user         | hi              |
      | message | assistant    | hello           |
    And the following model responses are queued:
      | type | content | model |
      | text | sure    | echo  |
    When the user sends "again?" on session "context-cheap"
    Then session "context-cheap" has transcript not matching:
      | type       |
      | compaction |

  # --- Compaction Process ---

  Scenario: Conversation is compacted into a summary
    Given the following sessions exist:
      | name            | last-input-tokens | #comment                    |
      | context-summary | 95                | exceeds 90% of 100 window   |
    And session "context-summary" has transcript:
      | type    | message.role | message.content              |
      | message | user         | What is Clojure?             |
      | message | assistant    | A functional Lisp on JVM... |
      | message | user         | What about Babashka?         |
      | message | assistant    | A fast Clojure scripting... |
    And the following model responses are queued:
      | type | content                | model |
      | text | Summary of prior chat  | echo  |
      | text | Here is my answer      | echo  |
    When the user sends "Continue" on session "context-summary"
    Then session "context-summary" has transcript matching:
      | type       | summary   |
      | compaction | #".{10,}" |
    And the following sessions match:
      | id              | compaction-count |
      | context-summary | 1               |

  Scenario: Compaction summarizer receives truncated tool results
    The compaction request currently inlines the FULL tool result via a
    synthesized "I called tool X... The tool result was: <FULL>" message,
    bypassing the truncation applied to live prompts. A single huge tool
    result can therefore make the summarizer prompt larger than the
    conversation it is trying to summarize. The summarizer prompt must
    use the same head-and-tail truncation as the live prompt path.
    Given the following sessions exist:
      | name              | last-input-tokens |
      | context-summarize | 95                |
    And session "context-summarize" has transcript:
      | type       | message.role | message.content                                                                                                                                                                                            |
      | message    | user         | Read the big file                                                                                                                                                                                           |
      | toolCall   |              |                                                                                                                                                                                                             |
      | toolResult |              | AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ |
    And the following model responses are queued:
      | type | content        | model |
      | text | brief summary  | echo  |
      | text | follow-up      | echo  |
    When the user sends "go on" on session "context-summarize"
    Then the compaction request matches:
      | key                 | value                    |
      | messages[1].content | #"(?s).*AAAA.*truncated.*ZZZZ.*" |

  # --- Per-Turn Token Updates ---

  Scenario: last-input-tokens is updated from response usage on every turn
    Each LLM response reports usage.input_tokens — the size of the prompt
    the model just saw. The session's last-input-tokens must be replaced
    (not added) with that value on every turn so the compaction trigger
    reflects current conversation size, not cumulative cost.
    Given the following sessions exist:
      | name             | last-input-tokens |
      | context-progress | 10                |
    And the following model responses are queued:
      | type | content | model | usage.input_tokens |
      | text | ok      | echo  | 42                 |
    When the user sends "hi" on session "context-progress"
    Then the following sessions match:
      | id               | last-input-tokens |
      | context-progress | 42                |

  Scenario: Assistant response persists per-entry token count
    Each persisted assistant message carries its own :tokens field so the
    summarizer and diagnostic tooling can see per-turn cost without
    re-estimating from content length.
    Given the following sessions exist:
      | name           |
      | context-entry  |
    And the following model responses are queued:
      | type | content | model | usage.input_tokens | usage.output_tokens |
      | text | hello   | echo  | 30                 | 5                   |
    When the user sends "hi" on session "context-entry"
    Then session "context-entry" has transcript matching:
      | type    | message.role | message.content | tokens |
      | message | user         | hi              |        |
      | message | assistant    | hello           | 35     |

  Scenario: Assistant response persists usage breakdown in transcript entry
    Per-turn input and output token counts are stored on the transcript entry
    so cost dashboards can read them without re-computing from content.
    Given the following sessions exist:
      | name          |
      | usage-persist |
    And the following model responses are queued:
      | type | content | model | usage.input_tokens | usage.output_tokens |
      | text | hello   | echo  | 30                 | 5                   |
    When the user sends "hi" on session "usage-persist"
    Then session "usage-persist" has transcript matching:
      | type    | message.role | message.usage.input-tokens | message.usage.output-tokens |
      | message | assistant    | 30                         | 5                           |

  Scenario: Assistant response persists reasoning on transcript entry
    A reasoning block (effort + summary) in the API response is stored on
    the transcript entry for UI and audit use.
    Given the following sessions exist:
      | name              |
      | reasoning-persist |
    And the following model responses are queued:
      | type | content | model | reasoning.effort | reasoning.summary |
      | text | done    | echo  | high             | Thought about it  |
    When the user sends "think" on session "reasoning-persist"
    Then session "reasoning-persist" has transcript matching:
      | type    | message.role | message.reasoning.effort | message.reasoning.summary |
      | message | assistant    | high                     | Thought about it          |

  # --- Tool Result Truncation ---

  Scenario: Large tool results are truncated in prompts
    Given the following sessions exist:
      | name             |
      | context-truncate |
    And session "context-truncate" has transcript:
      | type       | message.role | message.content                                                                                                                                                                                            |
      | message    | user         | Read the big file                                                                                                                                                                                           |
      | toolCall   |              |                                                                                                                                                                                                             |
      | toolResult |              | AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ |
    Then the prompt "What does it say?" on session "context-truncate" matches:
      | key                 | value                    |
      | messages[1].content | #"AAAA.*truncated.*ZZZZ" |
