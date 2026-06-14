Feature: Tool Call History in Prompts
  When rebuilding the prompt from transcript, tool call history
  must be formatted correctly for each provider. OpenAI requires
  the full tool call chain with type: function. Ollama strips
  tool calls and converts results to user messages.

  Background:
    Given default Grover setup

  Scenario: prompt includes tool call history for OpenAI-compatible providers
    Given the following sessions exist:
      | name         |
      | tool-history |
    And session "tool-history" has transcript:
      | type       | message.role | message.content                                                                               | id       | name | arguments                       |
      | message    | user         | What's in the fridge?                                                                         |          |      |                                 |
      | toolCall   |              |                                                                                               | call_123 | read | {"filePath":"fridge.txt"}       |
      | message    | toolResult   | 1 sad lemon, mass of unidentified cheese, Hieronymus's emergency lettuce (DO NOT TOUCH)        |          |      |                                 |
      | message    | assistant    | The fridge contains a lemon of questionable morale, some cheese, and forbidden tortoise rations. |          |      |                                 |
    When the prompt for session "tool-history" is built for provider "openai"
    Then the prompt messages contain a tool call with:
      | key                         | value    |
      | role                        | assistant |
      | tool_calls[0].type          | function |
      | tool_calls[0].function.name | read     |
      | tool_calls[0].id            | call_123 |
    And the prompt messages contain a tool result with:
      | key          | value                                                                                  |
      | role         | tool                                                                                   |
      | tool_call_id | call_123                                                                               |
      | content      | 1 sad lemon, mass of unidentified cheese, Hieronymus's emergency lettuce (DO NOT TOUCH) |

  Scenario: prompt strips tool calls for Ollama provider
    Given the following sessions exist:
      | name         |
      | tool-history |
    And session "tool-history" has transcript:
      | type       | message.role | message.content                                                                               | id       | name | arguments                 |
      | message    | user         | What's in the fridge?                                                                         |          |      |                           |
      | toolCall   |              |                                                                                               | call_123 | read | {"filePath":"fridge.txt"} |
      | message    | toolResult   | 1 sad lemon, mass of unidentified cheese, Hieronymus's emergency lettuce (DO NOT TOUCH)        |          |      |                           |
      | message    | assistant    | The fridge contains a lemon of questionable morale, some cheese, and forbidden tortoise rations. |          |      |                           |
    When the prompt for session "tool-history" is built for provider "ollama"
    Then the prompt messages do not contain role "tool"
    And the prompt messages do not contain key "tool_calls"
