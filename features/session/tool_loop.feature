Feature: Tool Loop Message Format
  When the tool dispatch loop sends tool call results back to
  the LLM for the next round, the messages must be formatted
  correctly for each provider. OpenAI requires type:function
  on tool_calls and role:tool with tool_call_id on results.

  Background:
    Given default Grover setup
    And the built-in tools are registered

  Scenario: tool loop formats messages for OpenAI-compatible providers
    Given the following sessions exist:
      | name      |
      | loop-test |
    And the provider "openai" is configured with:
      | key      | value                     |
      | base-url | https://api.openai.com/v1 |
      | api     | chat-completions        |
    And the following model responses are queued:
      | type      | content                       | model | tool_call | arguments                      |
      | tool_call |                               |       | exec      | {"command": "echo Hieronymus"} |
      | text      | The tortoise says Hieronymus. | echo  |           |                                |
    When the user sends "ask the tortoise his name" on session "loop-test"
    Then the tool loop request contains messages with:
      | role      | tool_calls[0].type | tool_call_id |
      | assistant | function           |              |
      | tool      |                    | #*           |

  Scenario: tool loop works across multiple rounds without type errors
    Given the following sessions exist:
      | name      |
      | loop-test |
    And the following model responses are queued:
      | type      | content                                  | model | tool_call | arguments                     |
      | tool_call |                                          |       | read      | {"filePath": "fridge.txt"}    |
      | tool_call |                                          |       | exec      | {"command": "echo still sad"} |
      | text      | The lemon is still sad after two checks. | echo  |           |                               |
    When the user sends "double check the fridge" on session "loop-test"
    Then session "loop-test" has transcript matching:
      | type    | message.role | message.content                          |
      | message | assistant    | The lemon is still sad after two checks. |
