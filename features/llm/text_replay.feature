Feature: Text replay keeps the whole conversation
  Providers without a native tool-message shape (Ollama, Claude Code, Grover)
  replay the transcript as plain text. The user message that led to tool use,
  every tool call, and every result stay in the history: calls become assistant
  "[tool call name {args}]" lines and results become user "[tool result]"
  messages. Adjacent calls merge into one message, as do adjacent results.

  Scenario: a follow-up turn sees the earlier question, tool calls, and results
    Given default Grover setup
    And the following sessions exist:
      | name     |
      | longhaul |
    And session "longhaul" has transcript:
      | type       | message.role | message.content | id   | name      | arguments               | isError |
      | message    | user         | hoist the sails |      |           |                         |         |
      | toolCall   |              |                 | tc-1 | exec__run | {"command":"echo main"} |         |
      | toolCall   |              |                 | tc-2 | exec__run | {"command":"echo jib"}  |         |
      | toolResult |              | main up         | tc-1 |           |                         |         |
      | toolResult |              | jib jammed      | tc-2 |           |                         | true    |
      | message    | assistant    | Main is up.     |      |           |                         |         |
    Then the prompt "trim them" on session "longhaul" matches:
      | path                | value                                                                                    |
      | messages[1].role    | user                                                                                     |
      | messages[1].content | hoist the sails                                                                          |
      | messages[2].role    | assistant                                                                                |
      | messages[2].content | #"\[tool call exec__run \{\"command\":\"echo main\"\}\]\n\[tool call exec__run \{\"command\":\"echo jib\"\}\]" |
      | messages[3].role    | user                                                                                     |
      | messages[3].content | #"\[tool result\]\nmain up\n\n\[tool error\]\njib jammed"                                |
      | messages[4].role    | assistant                                                                                |
      | messages[4].content | Main is up.                                                                              |
