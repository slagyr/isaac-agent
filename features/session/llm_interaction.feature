Feature: LLM Interaction
  Isaac sends prompts to LLM providers and records responses
  in the session transcript.

  Background:
    Given default Grover setup
    And the following sessions exist:
      | name     |
      | llm-chat |

  # --- Basic Chat ---

  Scenario: Send a message and receive a response
    Given the following model responses are queued:
      | type | content       | model |
      | text | Four, I think | echo  |
    When the user sends "What is 2+2?" on session "llm-chat"
    Then session "llm-chat" has transcript matching:
      | type    | message.role | message.model | message.provider |
      | message | assistant    | echo          | grover           |
    And the following sessions match:
      | id       | input-tokens | output-tokens |
      | llm-chat | #"\d+"      | #"\d+"       |

  Scenario: Streaming response
    Given the following model responses are queued:
      | type | content                | model |
      | text | Once upon a time...    | echo  |
    When the user sends "Tell me a story" on session "llm-chat"
    Then session "llm-chat" has transcript matching:
      | type    | message.role |
      | message | assistant    |

  # --- Tool Calling ---

  Scenario: Model requests a tool call and receives the result
    Given the built-in tools are registered
    And the crew member has tools:
      | name | description      | parameters             |
      | exec | Run a command    | {"command": "string"}  |
    And the following model responses are queued:
      | tool_call | arguments              |
      | exec      | {"command": "echo hi"} |
    When the user sends "Run echo hi" on session "llm-chat"
    Then session "llm-chat" has transcript matching:
      | type    | message.role | message.content[0].type | message.content[0].name |
      | message | assistant    | toolCall                | exec                    |
      | message | toolResult   |                         |                         |
      | message | assistant    |                         |                         |
    And session "llm-chat" has transcript matching:
      | type    | message.role | message.content |
      | message | toolResult   | #"hi"           |

  Scenario: Tool calls dispatch when provider lacks streaming tool support
    Given the provider "grover" is configured with:
      | key                        | value | #comment                                                                                    |
      | stream-supports-tool-calls | false | models real ollama/qwen — its stream endpoint doesn't return structured tool_calls         |
    And the built-in tools are registered
    And the crew member has tools:
      | name | description   | parameters             |
      | exec | Run a command | {"command": "string"}  |
    And the following model responses are queued:
      | tool_call | arguments              |
      | exec      | {"command": "echo hi"} |
    When the user sends "Run echo hi" on session "llm-chat"
    Then session "llm-chat" has transcript matching:
      | type    | message.role | message.content |
      | message | toolResult   | #"hi"           |

  # --- Error Handling ---

  Scenario: LLM errors are recorded in the session transcript
    Given the isaac EDN file "config/models/local.edn" exists with:
      | path | value |
      | model | llama3.2:latest |
      | provider | ollama |
      | context-window | 32000 |
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path | value |
      | model | local |
      | soul | You are Atticus. |
    And the provider "ollama" is configured with:
      | key      | value                  |
      | base-url | http://localhost:99999 |
    And the following sessions exist:
      | name      |
      | llm-error |
    When the user sends "Hello" on session "llm-error"
    Then session "llm-error" has transcript matching:
      | type  | error               |
      | error | :connection-refused |

  Scenario: tools-using turns stream text deltas as they arrive
    Given the built-in tools are registered
    And the crew "main" allows tools: grep
    And the following sessions exist:
      | name        |
      | stream-test |
    And the following model responses are queued:
      | type        | content                    | model |
      | text-stream | ["chunkA","chunkB","chunkC"] | echo  |
    When the user sends "hi" on session "stream-test"
    Then the memory comm has events matching:
      | event      | text  |
      | text-chunk | chunkA |
      | text-chunk | chunkB |
      | text-chunk | chunkC |

  Scenario: tool loop produces a real final message when the LLM keeps requesting tools
    Given the tool loop max is 1
    And the built-in tools are registered
    And the crew "main" allows tools: grep
    And the following sessions exist:
      | name  |
      | loopy |
    And the following model responses are queued:
      | type      | tool_call | arguments | model | content |
      | tool_call | grep      | {}        | echo  |         |
      | tool_call | grep      | {}        | echo  |         |
      | text      |           |           | echo  | I checked grep once, hit the limit, and need to continue manually. |
    When the user sends "poke around" on session "loopy"
    Then session "loopy" has transcript matching:
      | #index | type    | message.role | message.content                                              |
      | -1     | message | assistant    | I checked grep once, hit the limit, and need to continue manually. |
