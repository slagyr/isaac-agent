@wip
Feature: OpenAI Responses API — tool call arguments (isaac-9qv9)
  A function call's arguments are whatever its done events say:
  response.function_call_arguments.done carries them as "arguments", and
  response.output_item.done carries the finished item. Argument deltas
  are progress only. The ChatGPT backend skips the deltas for parallel
  calls in about two of three responses (probed 2026-10-07 against
  gpt-6-sol); OpenAI's own Codex client ignores the deltas and reads the
  done item.

  Background:
    Given an Isaac root at "target/test-state"
    And the isaac EDN file "config/models/snuffy.edn" exists with:
      | path           | value          |
      | model          | snuffy-codex   |
      | provider       | grover:chatgpt |
      | context-window | 128000         |
    And the isaac EDN file "config/crew/oscar.edn" exists with:
      | path  | value                 |
      | model | snuffy                |
      | soul  | Lives in a trash can. |
    And the crew "oscar" allows tools: "exec/run"
    And the following sessions exist:
      | name      | crew  |
      | trash-can | oscar |

  Scenario: parallel calls whose arguments arrive only in the done events run with those arguments
    Given the next Responses stream is:
      | type                                   | item.type     | item.id | item.call_id | item.name | item.arguments          | item_id | arguments               |
      | response.output_item.added             | function_call | fc-1    | call-1       | exec__run |                         |         |                         |
      | response.function_call_arguments.done  |               |         |              |           |                         | fc-1    | {"command":"echo main"} |
      | response.output_item.done              | function_call | fc-1    | call-1       | exec__run | {"command":"echo main"} |         |                         |
      | response.output_item.added             | function_call | fc-2    | call-2       | exec__run |                         |         |                         |
      | response.function_call_arguments.done  |               |         |              |           |                         | fc-2    | {"command":"echo jib"}  |
      | response.output_item.done              | function_call | fc-2    | call-2       | exec__run | {"command":"echo jib"}  |         |                         |
      | response.completed                     |               |         |              |           |                         |         |                         |
    And the following model responses are queued:
      | model        | type | content           |
      | snuffy-codex | text | Main up, jib set. |
    When the user sends "hoist the sails" on session "trash-can"
    Then session "trash-can" has transcript matching:
      | type       | name      | arguments               | message.content |
      | toolCall   | exec__run | {"command":"echo main"} |                 |
      | toolCall   | exec__run | {"command":"echo jib"}  |                 |
      | toolResult |           |                         | main            |
      | toolResult |           |                         | jib             |
