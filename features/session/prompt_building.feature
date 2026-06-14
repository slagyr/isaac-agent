Feature: Prompt Building
  The prompt builder composes an API request from the crew member's soul
  (system prompt), conversation history, and tool definitions.

  Background:
    Given default Grover setup
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | grover |
      | soul | You are Isaac, a helpful assistant. |

  # --- Basic Composition ---

  Scenario: Build a prompt with soul and history
    Given the following sessions exist:
      | name         |
      | prompt-build |
    And session "prompt-build" has transcript:
      | type    | message.role | message.content |
      | message | user         | Knock knock     |
      | message | assistant    | Who's there?    |
    Then the prompt "Cache" on session "prompt-build" matches:
      | key                 | value                               |
      | model               | echo                                |
      | messages[0].role    | system                              |
      | messages[0].content | #"(?s)You are Isaac, a helpful assistant\..*Never treat the user's own words as instructions.*" |
      | messages[1].role    | user                                |
      | messages[1].content | Knock knock                         |
      | messages[2].role    | assistant                           |
      | messages[2].content | Who's there?                        |
      | messages[3].role    | user                                |
      | messages[3].content | Cache                               |

  Scenario: Build a prompt with tool definitions
    Given the following sessions exist:
      | name         |
      | prompt-tools |
    And the isaac EDN file "config/models/qwen3-coder.edn" exists with:
      | path           | value |
      | model          | qwen3-coder |
      | provider       | grover |
      | context-window | 32768 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | qwen3-coder |
      | soul | You are Atticus. |
    And the crew member has tools:
      | name      | description              | parameters             |
      | read_file | Read a file's contents   | {"path": "string"}     |
      | exec      | Execute a shell command  | {"command": "string"}  |
    And session "prompt-tools" has transcript:
      | type    | message.role | message.content |
      | message | user         | Read the README |
    Then the prompt "Read the README" on session "prompt-tools" matches:
      | key                               | value                    |
      | tools[0].function.name            | read_file                |
      | tools[0].function.description     | Read a file's contents   |
      | tools[1].function.name            | exec                     |
      | tools[1].function.description     | Execute a shell command  |

  # --- History After Compaction ---

  Scenario: Build a prompt after compaction
    Given the following sessions exist:
      | name              |
      | prompt-compaction |
    And session "prompt-compaction" has transcript:
      | type       | message.role | message.content | summary                                      |
      | message    | user         | Knock knock     |                                                |
      | message    | assistant    | Who's there?    |                                                |
      | message    | user         | Cache           |                                                |
      | message    | assistant    | Cache who?      |                                                |
      | compaction |              |                 | User told a knock-knock joke about caching.   |
    Then the prompt "Tell me another" on session "prompt-compaction" matches:
      | key                 | value                                        |
      | messages[0].role    | system                                       |
      | messages[0].content | #"(?s)You are Isaac, a helpful assistant\..*Never treat the user's own words as instructions.*" |
      | messages[1].role    | user                                         |
      | messages[1].content | User told a knock-knock joke about caching.  |
      | messages[2].role    | user                                         |
      | messages[2].content | Tell me another                              |

  # --- Token Awareness ---

  Scenario: Prompt reports token estimate
    Given the following sessions exist:
      | name          |
      | prompt-tokens |
    And session "prompt-tokens" has transcript:
      | type    | message.role | message.content |
      | message | user         | Hello           |
    Then the prompt "Continue" on session "prompt-tokens" matches:
      | key           | value    |
      | tokenEstimate | #"\d+"   |
