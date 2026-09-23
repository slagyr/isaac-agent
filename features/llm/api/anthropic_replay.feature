Feature: Anthropic replay keeps tool calls and results paired (isaac-lddb)
  When a prompt is rebuilt from the transcript for an Anthropic-shaped
  provider, every tool call replays as an assistant tool_use block and every
  result as a user tool_result block with the matching tool_use_id — the same
  shape followup-messages builds mid-turn. Adjacent tool calls group into one
  assistant message and adjacent results into one user message, so batch
  entries and older one-call entries replay alike.

  Background:
    Given default Grover setup
    And config:
      | key                       | value                |
      | defaults.crew.tools.allow | [:all :test :test/*] |
    And the built-in tools are registered
    And the isaac EDN file "config/models/harbor-haiku.edn" exists with:
      | path     | value            |
      | model    | claude-haiku-4-5 |
      | provider | anthropic        |

  Scenario: a tool batch replays as one assistant message of tool_use blocks, then one user message of tool_result blocks in call order
    Given a gated tool "test__slow" is registered that returns "slow done" once tool "test__quick" has completed
    And a streaming tool "test__quick" is registered that emits progress [] and returns "quick done"
    And the following sessions exist:
      | name    |
      | on-deck |
    And the following model responses are queued:
      | model | type       | tool_calls                                                                                             | content |
      |       | tool_calls | [{"function":{"name":"test__slow","arguments":{}}},{"function":{"name":"test__quick","arguments":{}}}] |         |
      | echo  | text       |                                                                                                        | Noted.  |
    When the user sends "go" on session "on-deck" via memory comm
    And the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | harbor-haiku     |
      | soul  | You are Atticus. |
    Then the prompt "and the winch?" on session "on-deck" matches:
      | path                               | value       |
      | messages[1].role                   | assistant   |
      | messages[1].content[0].type        | tool_use    |
      | messages[1].content[0].name        | test__slow  |
      | messages[1].content[1].type        | tool_use    |
      | messages[1].content[1].name        | test__quick |
      | messages[2].role                   | user        |
      | messages[2].content[0].type        | tool_result |
      | messages[2].content[0].tool_use_id | #*          |
      | messages[2].content[0].content     | slow done   |
      | messages[2].content[1].type        | tool_result |
      | messages[2].content[1].content     | quick done  |

  Scenario: older one-call entries and the user message before them replay with ids paired
    Given the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | harbor-haiku     |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name     |
      | longhaul |
    And session "longhaul" has transcript:
      | type       | message.role | message.content | id   | name      | arguments               |
      | message    | user         | hoist the sails |      |           |                         |
      | toolCall   |              |                 | tc-1 | exec__run | {"command":"echo main"} |
      | toolCall   |              |                 | tc-2 | exec__run | {"command":"echo jib"}  |
      | toolResult |              | main up         | tc-1 |           |                         |
      | toolResult |              | jib up          | tc-2 |           |                         |
      | message    | assistant    | Both sails up.  |      |           |                         |
    Then the prompt "trim them" on session "longhaul" matches:
      | path                                 | value           |
      | messages[0].role                     | user            |
      | messages[0].content[0].text          | hoist the sails |
      | messages[1].role                     | assistant       |
      | messages[1].content[0].id            | tc-1            |
      | messages[1].content[0].input.command | echo main       |
      | messages[1].content[1].id            | tc-2            |
      | messages[2].role                     | user            |
      | messages[2].content[0].tool_use_id   | tc-1            |
      | messages[2].content[0].content       | main up         |
      | messages[2].content[1].tool_use_id   | tc-2            |
      | messages[2].content[1].content       | jib up          |
      | messages[3].role                     | assistant       |
      | messages[3].content[0].text          | Both sails up.  |

  Scenario: a failed tool result replays with is_error
    Given the isaac EDN file "config/crew/main.edn" exists with:
      | path  | value            |
      | model | harbor-haiku     |
      | soul  | You are Atticus. |
    And the following sessions exist:
      | name  |
      | winch |
    And session "winch" has transcript:
      | type       | message.role | message.content      | id   | name      | arguments                | isError |
      | message    | user         | check the winch      |      |           |                          |         |
      | toolCall   |              |                      | tc-w | exec__run | {"command":"winch test"} |         |
      | toolResult |              | Error: winch jammed  | tc-w |           |                          | true    |
      | message    | assistant    | The winch is jammed. |      |           |                          |         |
    Then the prompt "fix it" on session "winch" matches:
      | path                               | value               |
      | messages[2].content[0].tool_use_id | tc-w                |
      | messages[2].content[0].is_error    | true                |
      | messages[2].content[0].content     | Error: winch jammed |
