Feature: Session Storage
  Isaac persists conversation sessions as JSONL transcript files
  with an EDN index. Sessions are stored flat under the state
  directory, independent of any crew member.

  Background:
    Given an Isaac root at "target/test-state"

  # --- Session Lifecycle ---

  Scenario: Create a new session
    Given the following sessions exist:
      | name       |
      | first-chat |
    Then the session count is 1
    And the following sessions match:
      | id         | file                | compaction-count | input-tokens | output-tokens | total-tokens |
      | first-chat | #".+\.jsonl"        | 0               | 0           | 0            | 0           |
    And session "first-chat" has 1 transcript entry
    And session "first-chat" has transcript matching:
      | type    | id              | timestamp                               |
      | session | #"[a-f0-9]{8}" | #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" |
    And the log has entries matching:
      | level | event             | sessionId  |
      | :info | :session/created  | first-chat |

  Scenario: List sessions
    Given the following sessions exist:
      | name   |
      | chat-1 |
      | chat-2 |
    Then the session count is 2
    And the following sessions match:
      | id     | updated-at                               |
      | chat-1 | #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" |
      | chat-2 | #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" |

  Scenario: Opening an existing session resumes it
    Given the following sessions exist:
      | name       |
      | first-chat |
    And session "first-chat" has transcript:
      | type    | message.role | message.content |
      | message | user         | Hello           |
    When session "first-chat" is opened
    Then the session count is 1
    And session "first-chat" has 2 transcript entries
    And the log has entries matching:
      | level | event            | sessionId  |
      | :info | :session/opened  | first-chat |

  Scenario: Resume an existing session
    Given the following sessions exist:
      | name       |
      | first-chat |
    And session "first-chat" has transcript:
      | type    | message.role | message.content |
      | message | user         | Hello           |
      | message | assistant    | Hi there        |
      | message | user         | How are you?    |
    Then session "first-chat" has 4 transcript entries
    And the session count is 1

  # --- Message Entries ---

  Scenario: Append a user message
    Given the following sessions exist:
      | name       |
      | first-chat |
    When entries are appended to session "first-chat":
      | type    | message.role | message.content |
      | message | user         | Hello           |
    Then session "first-chat" has 2 transcript entries
    And session "first-chat" has transcript matching:
      | #index | type    | message.role | message.content |
      | 1      | message | user         | Hello           |

  Scenario: Append an assistant message
    Given the following sessions exist:
      | name       |
      | first-chat |
    And session "first-chat" has transcript:
      | type    | message.role | message.content |
      | message | user         | Hello           |
    When entries are appended to session "first-chat":
      | type    | message.role | message.content | message.model | message.provider |
      | message | assistant    | Hi there        | qwen3-coder   | ollama           |
    Then session "first-chat" has 3 transcript entries
    And session "first-chat" has transcript matching:
      | #index | type    | message.role | message.content | message.model | message.provider |
      | 2      | message | assistant    | Hi there        | qwen3-coder   | ollama           |

  Scenario: Append a tool call and result
    Given the following sessions exist:
      | name      |
      | tool-chat |
    And session "tool-chat" has transcript:
      | type    | message.role | message.content |
      | message | user         | Read the README |
    When entries are appended to session "tool-chat":
      | type       | name      | id       | arguments          | message.content   | isError |
      | toolCall   | read_file | call_123 | {"path": "README"} |                   |         |
      | toolResult |           | call_123 |                    | # Isaac\nA CLI... | false   |
    Then session "tool-chat" has transcript matching:
      | type    | message.role | message.content[0].type | message.content[0].name |
      | message | assistant    | toolCall                | read_file               |
    And session "tool-chat" has transcript matching:
      | type    | message.role | message.toolCallId |
      | message | toolResult   | call_123           |

  Scenario: Compaction splice keeps tool calls paired by toolCallId
    Given the following sessions exist:
      | name      |
      | tool-chat |
    And session "tool-chat" has transcript:
      | type       | message.role | message.content         | id       | name      | arguments          |
      | message    | user         | Earlier question        |          |           |                    |
      | toolCall   |              |                         | call_123 | read_file | {"path": "README"} |
      | toolResult |              | one sad lemon           | call_123 |           |                    |
      | message    | assistant    | The fridge has a lemon. |          |           |                    |
    When compaction is spliced into session "tool-chat" with:
      | key              | value                  |
      | summary          | Summary of earlier work |
      | firstKeptIndex   | 2                      |
      | compactedIndexes | [1]                    |
      | tokensBefore     | 20                     |
    Then session "tool-chat" has transcript matching:
      | type       | summary                 |
      | compaction | Summary of earlier work |
    And session "tool-chat" has transcript matching:
      | type    | message.role | message.content[0].type | message.content[0].id |
      | message | assistant    | toolCall                | call_123              |
    And session "tool-chat" has transcript matching:
      | type    | message.role | message.toolCallId | message.content |
      | message | toolResult   | call_123           | one sad lemon   |

  # --- Entry Linking ---

  Scenario: Entries form a linked chain via parentId
    Given the following sessions exist:
      | name       |
      | chain-test |
    When entries are appended to session "chain-test":
      | type    | message.role | message.content |
      | message | user         | Hello           |
      | message | assistant    | Hi there        |
      | message | user         | How are you?    |
    Then session "chain-test" has transcript matching:
      | #index | id                     | parentId |
      | 0      | #"[a-f0-9]{8}":header  |          |
      | 1      | #"[a-f0-9]{8}":msg1    | #header  |
      | 2      | #"[a-f0-9]{8}":msg2    | #msg1    |
      | 3      | #"[a-f0-9]{8}":msg3    | #msg2    |

  # --- Index Updates ---

  Scenario: Index is updated on each append
    Given the following sessions exist:
      | name       |
      | index-test |
    When entries are appended to session "index-test":
      | type    | message.role | message.content |
      | message | user         | Hello           |
    Then the following sessions match:
      | id         | updated-at                               |
      | index-test | #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" |

  # --- Transcript Format ---

  Scenario: Session header includes version and working directory
    Given the following sessions exist:
      | name        |
      | header-test |
    Then session "header-test" has transcript matching:
      | #index | type    | version | cwd   |
      | 0      | session | 3       | #".+" |

  Scenario: Entry IDs are 8-character hex strings
    Given the following sessions exist:
      | name    |
      | id-test |
    When entries are appended to session "id-test":
      | type    | message.role | message.content |
      | message | user         | Hello           |
    Then session "id-test" has transcript matching:
      | #index | id             |
      | 0      | #"[a-f0-9]{8}" |
      | 1      | #"[a-f0-9]{8}" |

  Scenario: Timestamps use ISO 8601 format
    Given the following sessions exist:
      | name    |
      | ts-test |
    When entries are appended to session "ts-test":
      | type    | message.role | message.content |
      | message | user         | Hello           |
    Then session "ts-test" has transcript matching:
      | #index | timestamp                               |
      | 0      | #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" |
      | 1      | #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" |
    And the following sessions match:
      | id      | updated-at                               |
      | ts-test | #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" |

  Scenario: Session sidecars are keyed by session id
    Given the session store uses the file implementation
    And the following sessions exist:
      | name   |
      | chat-1 |
      | chat-2 |
    Then the file "sessions/chat-1.edn" exists
    And the file "sessions/chat-2.edn" exists

  Scenario: Message content stored as block arrays
    Given the following sessions exist:
      | name       |
      | block-test |
    When entries are appended to session "block-test":
      | type    | message.role | message.content |
      | message | user         | Hello           |
    Then session "block-test" has transcript matching:
      | #index | message.content[0].type | message.content[0].text |
      | 1      | text                    | Hello                   |

  Scenario: Assistant messages include per-turn usage metadata
    Given the following sessions exist:
      | name       |
      | usage-test |
    When entries are appended to session "usage-test":
      | type    | message.role | message.content | message.model | message.provider | message.api | message.usage.input | message.usage.output | message.stopReason |
      | message | assistant    | Hi there        | qwen3-coder   | ollama           | ollama      | 100                 | 25                   | stop               |
    Then session "usage-test" has transcript matching:
      | type    | message.role | message.usage.input | message.usage.output | message.stopReason | message.api |
      | message | assistant    | 100                 | 25                   | stop               | ollama      |
