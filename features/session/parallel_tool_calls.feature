Feature: Parallel tool calls — invite, permit, execute
  The tool loop already executes every call in a provider response batch.
  Standing system instructions invite batching; adapters must not disable
  parallel_tool_calls on OpenAI-dialect wire requests.

  Background:
    Given default Grover setup
    And the built-in tools are registered

  Scenario: a single grover response with two tool calls runs both in order and persists both pairs
    Given the following sessions exist:
      | name        |
      | batch-tools |
    And the following model responses are queued:
      | model | type       | tool_calls                                                                                                                      | content          |
      |       | tool_calls | [{"function":{"name":"read","arguments":{"filePath":"a.txt"}}},{"function":{"name":"read","arguments":{"filePath":"b.txt"}}}] |                  |
      | echo  | text       |                                                                                                                               | Both files read. |
    When the user sends "read a and b" on session "batch-tools"
    Then session "batch-tools" has transcript matching:
      | type    | message.role | message.content[0].name |
      | message | assistant    | read                    |
      | message | toolResult   |                         |
      | message | assistant    | read                    |
      | message | toolResult   |                         |
      | message | assistant    |                         |

  Scenario: chat-completions outbound request does not set parallel_tool_calls false
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/models/g5.edn" exists with:
      | path           | value             |
      | model          | gpt-5             |
      | provider       | grover:openai     |
      | context-window | 128000            |
    And the isaac EDN file "config/crew/thinker.edn" exists with:
      | path  | value       |
      | model | g5          |
      | soul  | Think hard. |
    And the following sessions exist:
      | name | crew    |
      | desk | thinker |
    And the following model responses are queued:
      | model | type | content |
      | gpt-5 | text | ok      |
    When the user sends "hi" on session "desk"
    Then the last provider request does not contain path "body.parallel_tool_calls"

  Scenario: responses outbound request does not set parallel_tool_calls false
    Given an Isaac root at "target/test-state"
    And config:
      | key        | value  |
      | log.output | memory |
    And the isaac EDN file "config/models/snuffy.edn" exists with:
      | path           | value          |
      | model          | snuffy-codex   |
      | provider       | grover:chatgpt |
      | context-window | 128000         |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value                 |
      | model | snuffy                |
      | soul  | Lives in a trash can. |
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |
    And the following model responses are queued:
      | model        | type | content |
      | snuffy-codex | text | ok      |
    When the user sends "hi" on session "trash-can"
    Then the last provider request does not contain path "body.parallel_tool_calls"
