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

  Scenario: --crew resolves the crew member's model
    Given the isaac EDN file "config/models/grover2.edn" exists with:
      | path | value |
      | model | echo-alt |
      | provider | grover |
      | context-window | 16384 |
    And the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover2 |
      | soul | You are a pirate. |
    And the following model responses are queued:
      | model    | type | content |
      | echo-alt | text | Ahoy    |
    When isaac is run with "prompt --crew ketch -m 'hello'"
    Then the exit code is 0
    And session "prompt-default" has transcript matching:
      | type    | message.model | message.crew |
      | message | echo-alt      | ketch        |

  Scenario: prompt sets cwd on the created session
    Given the following model responses are queued:
      | type | content | model |
      | text | Hi      | echo  |
    When isaac is run with "prompt -m 'Hi'"
    Then the following sessions match:
      | id             | cwd |
      | prompt-default | #*  |

  Scenario: --crew uses the crew member's soul
    Given the isaac EDN file "config/crew/ketch.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are a pirate. |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Arr     |
    When isaac is run with "prompt --crew ketch -m 'hello'"
    Then the exit code is 0
    And the following sessions match:
      | id             | crew  |
      | prompt-default | ketch |

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
      | type | content            | model |
      | text | Summary so far     | echo  |
      | text | here is the answer | echo  |
    When isaac is run with "prompt -m 'next'"
    Then the stderr matches:
      | 🥬 compacting |
      | 95            |
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
