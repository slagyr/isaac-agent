Feature: Prompt single-turn command
  `isaac prompt` runs a single turn and exits. Conversations
  persist across invocations via --session.

  Background:
    Given default Grover setup

  Scenario: prompt command runs one turn and exits
    Given the following model responses are queued:
      | type | content       | model |
      | text | Four, I think | echo  |
    When isaac is run with "prompt -m 'What is 2+2?'"
    Then the stdout contains "Four, I think"
    And the exit code is 0

  Scenario: Default session is prompt-default
    Given the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When isaac is run with "prompt -m 'Hi'"
    Then the following sessions match:
      | id              |
      | prompt-default  |
    And session "prompt-default" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Hi              |
      | message | assistant    | Hello           |

  Scenario: --session resumes an existing session
    Given the following sessions exist:
      | name           |
      | prompt-resume  |
    And session "prompt-resume" has transcript:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Earlier reply   |
    And the following model responses are queued:
      | type | content | model |
      | text | New one | echo  |
    When isaac is run with "prompt -m 'Next' --session prompt-resume"
    Then session "prompt-resume" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Earlier reply   |
      | message | user         | Next            |
      | message | assistant    | New one         |

  Scenario: Missing --message exits non-zero
    When isaac is run with "prompt"
    Then the stdout contains "required"
    And the exit code is 1

  Scenario: --json outputs structured result
    Given the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When isaac is run with "prompt -m 'Hi' --json"
    Then the stdout contains "response"
    And the stdout contains "Hello"
    And the exit code is 0

  Scenario: Provider error prints a readable message to stderr
    Given the following model responses are queued:
      | model | type  | content                 |
      | echo  | error | context length exceeded |
    When isaac is run with "prompt -m 'Hi'"
    Then the stderr contains "context length exceeded"
    And the exit code is 1

  Scenario: --crew resolves the crew member's model on the crew session
    Given the isaac EDN file "config/models/grover2.edn" exists with:
      | path | value |
      | model | echo-alt |
      | provider | grover |
      | context-window | 16384 |
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover2 |
      | soul | You are a pirate. |
    And the following sessions exist:
      | name          | crew  |
      | ketch-session | ketch |
    And the following model responses are queued:
      | model    | type | content |
      | echo-alt | text | Ahoy    |
    When isaac is run with "prompt --crew ketch -m 'hello'"
    Then the exit code is 0
    And session "ketch-session" has transcript matching:
      | type    | message.model | message.crew |
      | message | echo-alt      | ketch        |

  Scenario: --crew targets the crew's existing session and resumes its history
    Given the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    And the following sessions exist:
      | name          | crew  |
      | ketch-session | ketch |
    And session "ketch-session" has transcript:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Aye             |
    And the following model responses are queued:
      | type | content  | model |
      | text | On deck. | echo  |
    When isaac is run with "prompt --crew ketch -m 'Status?'"
    Then session "ketch-session" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Aye             |
      | message | user         | Status?         |
      | message | assistant    | On deck.        |

  Scenario: --crew with no match creates one (no silent fallback to prompt-default)
    Given the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When isaac is run with "prompt --crew ketch -m 'Hi'"
    Then the exit code is 0
    And the stdout contains "Hello"
    And the following sessions match:
      | crew  |
      | ketch |
    And session "prompt-default" does not exist

  Scenario: --crew with multiple matches resumes the most recent
    Given the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    And the following sessions exist:
      | name    | crew  | updated-at           |
      | older   | ketch | 2026-04-10T10:00:00 |
      | recent  | ketch | 2026-04-12T15:00:00 |
    And session "recent" has transcript:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Earlier reply   |
    And the following model responses are queued:
      | type | content   | model |
      | text | Continued | echo  |
    When isaac is run with "prompt --crew ketch -m 'Next'"
    Then session "recent" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Earlier reply   |
      | message | user         | Next            |
      | message | assistant    | Continued       |

  Scenario: --session-tag selects by tag and ignores differently-tagged sessions
    Given the following sessions exist:
      | name   | tags              |
      | tagged | #{:project/chess} |
      | other  | #{:project/nav}   |
    And session "tagged" has transcript:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Aye             |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When isaac is run with "prompt --session-tag project/chess -m 'Hi'"
    Then session "tagged" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Aye             |
      | message | user         | Hi              |
      | message | assistant    | Hello           |

  Scenario: repeated --session-tag AND-composes
    Given the following sessions exist:
      | name    | tags                            |
      | tagged  | #{:project/chess :wip}          |
      | partial | #{:project/chess}               |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When isaac is run with "prompt --session-tag project/chess --session-tag wip -m 'Hi'"
    Then session "tagged" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Hi              |
      | message | assistant    | Hello           |

  Scenario: --create never with no match errors
    When isaac is run with "prompt --crew ketch --create never -m 'Hi'"
    Then the stderr contains "no session"
    And the exit code is 1

  Scenario: --create always starts a fresh session and leaves the matching one untouched
    Given the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    And the following sessions exist:
      | name          | crew  |
      | ketch-session | ketch |
    And session "ketch-session" has transcript:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Aye             |
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When isaac is run with "prompt --crew ketch --create always -m 'Hi'"
    Then the exit code is 0
    And the stdout contains "Hello"
    And the session count is 2
    And session "ketch-session" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Aye             |

  Scenario: --with-model overrides the model while --crew selects (orthogonal axes)
    Given the isaac EDN file "config/models/grover2.edn" exists with:
      | path | value |
      | model | echo-alt |
      | provider | grover |
      | context-window | 16384 |
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    And the following sessions exist:
      | name          | crew  |
      | ketch-session | ketch |
    And the following model responses are queued:
      | model    | type | content |
      | echo-alt | text | Ahoy    |
    When isaac is run with "prompt --crew ketch --with-model grover2 -m 'hello'"
    Then the exit code is 0
    And session "ketch-session" has transcript matching:
      | type    | message.model | message.crew |
      | message | echo-alt      | ketch        |

  Scenario: --session with selection flags errors clearly
    When isaac is run with "prompt --session bridge --crew main -m 'Hi'"
    Then the stderr contains "mutually exclusive"
    And the exit code is 1

  # --prefer: the multi-match tiebreak for :reach :one (isaac-4e4b). Replaces the
  # confusingly-named --resume; --session stays the exact selector.

  Scenario: --prefer oldest picks the oldest of multiple matching sessions
    Given the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    And the following sessions exist:
      | name   | crew  | updated-at          |
      | older  | ketch | 2026-04-10T10:00:00 |
      | recent | ketch | 2026-04-12T15:00:00 |
    And session "older" has transcript:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Aye             |
    And the following sessions exist:
      | name   | crew  | updated-at          |
      | older  | ketch | 2026-04-10T10:00:00 |
      | recent | ketch | 2026-04-12T15:00:00 |
    And the following model responses are queued:
      | type | content | model |
      | text | On it.  | echo  |
    When isaac is run with "prompt --crew ketch --prefer oldest -m 'Status?'"
    Then session "older" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Aye             |
      | message | user         | Status?         |
      | message | assistant    | On it.          |
    And the exit code is 0

  Scenario: --prefer recent picks the most recent of multiple matching sessions
    Given the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    And the following sessions exist:
      | name   | crew  | updated-at          |
      | older  | ketch | 2026-04-10T10:00:00 |
      | recent | ketch | 2026-04-12T15:00:00 |
    And session "recent" has transcript:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Aye             |
    And the following model responses are queued:
      | type | content | model |
      | text | On it.  | echo  |
    When isaac is run with "prompt --crew ketch --prefer recent -m 'Status?'"
    Then session "recent" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Aye             |
      | message | user         | Status?         |
      | message | assistant    | On it.          |
    And the exit code is 0

  Scenario: --prefer is a no-op when the match is unambiguous
    Given the following sessions exist:
      | name |
      | foo  |
    And session "foo" has transcript:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Aye             |
    And the following model responses are queued:
      | type | content | model |
      | text | On it.  | echo  |
    When isaac is run with "prompt --session foo --prefer oldest -m 'Status?'"
    Then session "foo" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Aye             |
      | message | user         | Status?         |
      | message | assistant    | On it.          |
    And the exit code is 0

  Scenario: --prefer with an unknown value errors clearly
    When isaac is run with "prompt --crew ketch --prefer sideways -m 'Hi'"
    Then the stderr contains "--prefer must be recent or oldest"
    And the exit code is 1

  Scenario: prompt sets cwd on the created session
    Given the following model responses are queued:
      | type | content | model |
      | text | Hi      | echo  |
    When isaac is run with "prompt -m 'Hi'"
    Then the following sessions match:
      | id             | cwd |
      | prompt-default | #*  |

  Scenario: --crew uses the crew member's soul on the crew session
    Given the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    And the following sessions exist:
      | name          | crew  |
      | ketch-session | ketch |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Arr     |
    When isaac is run with "prompt --crew ketch -m 'hello'"
    Then the exit code is 0
    And the following sessions match:
      | id            | crew  |
      | ketch-session | ketch |

  Scenario: prompt-created sessions load AGENTS.md from cwd
    Given the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When isaac is run with "prompt -m 'Hi'"
    Then the exit code is 0
    And the system prompt contains "Micah's AI assistant management tools."

  Scenario: --resume uses the most recent session
    Given the following sessions exist:
      | name    | updated-at           |
      | older   | 2026-04-10T10:00:00 |
      | recent  | 2026-04-12T15:00:00 |
    And session "recent" has transcript:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Earlier reply   |
    And the following model responses are queued:
      | type | content   | model |
      | text | Continued | echo  |
    When isaac is run with "prompt --resume -m 'Next'"
    Then session "recent" has transcript matching:
      | type    | message.role | message.content |
      | message | user         | Earlier         |
      | message | assistant    | Earlier reply   |
      | message | user         | Next            |
      | message | assistant    | Continued       |

  Scenario: --resume with no existing sessions creates one
    And the following model responses are queued:
      | type | content | model |
      | text | Hello   | echo  |
    When isaac is run with "prompt --resume -m 'Hi'"
    Then the exit code is 0
    And the stdout contains "Hello"

  Scenario: prompt shows compaction lifecycle on stderr
    # The compacting banner prints the live outbound-prompt estimate (system
    # floor + transcript), not the lagging total-tokens counter. A small window
    # plus a non-trivial transcript pushes the estimate past 0.8 * window;
    # head 0.1 keeps the post-compaction estimate under threshold so compaction
    # makes progress and succeeds.
    Given the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value |
      | model          | echo  |
      | provider       | grover |
      | context-window | 200   |
    Given the following sessions exist:
      | name           | compaction.head |
      | prompt-default | 0.1             |
    And session "prompt-default" has transcript:
      | type    | message.role | message.content                                                              |
      | message | user         | Please summarize the work we did on the logging subsystem and the tool loop   |
      | message | assistant    | We discussed logging output sinks, the compaction trigger, and tool dispatch  |
      | message | user         | And what about the retry behavior we changed in the dispatcher last week      |
      | message | assistant    | We made the dispatcher retry idempotent and added backoff between attempts    |
    And the following model responses are queued:
      | type | content            | model |
      | text | Summary so far     | echo  |
      | text | here is the answer | echo  |
    When isaac is run with "prompt -m 'next'"
    Then the stderr matches:
      | 🥬 compacting |
      | ✨ compacted  |
    And the stdout contains "here is the answer"
    And the stdout does not contain "🥬 compacting"

  Scenario: prompt shows tool calls and results on stderr with kind icons
    Given the crew "main" allows tools: grep
    Given the following model responses are queued:
      | type     | tool_call | arguments                          | content  | model |
      | toolCall | grep      | {"pattern":"lettuce","path":"src"} |          | echo  |
      | text     |           |                                    | found it | echo  |
      When isaac is run with "prompt -m 'find the lettuce'"
      Then the stderr matches:
        | 🔍 grep |
        | lettuce |
        | ← grep  |
    And the stdout contains "found it"

  Scenario: prompt shows compaction failure inline with the underlying error
    Given the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value |
      | model          | echo  |
      | provider       | grover |
      | context-window | 100   |
    Given the following sessions exist:
      | name           | total-tokens |
      | prompt-default | 95           |
    And session "prompt-default" has transcript:
      | type    | message.role | message.content |
      | message | user         | older prompt    |
      | message | assistant    | older reply     |
    And the following model responses are queued:
      | type  | content                 | model |
      | error | context length exceeded | echo  |
      | text  | here is the answer      | echo  |
    When isaac is run with "prompt -m 'next'"
    Then the stderr matches:
      | 🥬 compacting           |
      | 🥀 compaction failed    |
      | context length exceeded |
    And the stdout contains "here is the answer"

  Scenario: prompt shows a banner when compaction surrenders after repeated failures
    Given the isaac EDN file "config/models/grover.edn" exists with:
      | path           | value |
      | model          | echo  |
      | provider       | grover |
      | context-window | 100   |
    Given the following sessions exist:
      | name           | total-tokens | compaction.consecutive-failures |
      | prompt-default | 95           | 4                               |
    And session "prompt-default" has transcript:
      | type    | message.role | message.content |
      | message | user         | older prompt    |
      | message | assistant    | older reply     |
    And the following model responses are queued:
      | type  | content                 | model |
      | error | context length exceeded | echo  |
      | text  | here is the answer      | echo  |
    When isaac is run with "prompt -m 'next'"
    Then the stderr matches:
      | 🪦 compaction disabled |
      | too-many-failures      |
    And the stdout contains "here is the answer"
